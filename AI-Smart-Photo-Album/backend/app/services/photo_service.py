"""照片数据访问：CRUD、收藏关系、按标签搜索、软删除。"""
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.exceptions import BizException
from app.models import Category, CategoryType, Favorite, Photo, PhotoAIAnalysis, PhotoCategory
from app.utils.time import utcnow_naive


async def get_or_404(db: AsyncSession, photo_id: int, user_id: int) -> Photo:
    """按 id + user 取照片，未找到或已软删除则抛 404。"""
    p = (await db.execute(
        select(Photo).where(
            Photo.photo_id == photo_id,
            Photo.user_id == user_id,
            Photo.deleted_at.is_(None),
        )
    )).scalar_one_or_none()
    if not p:
        raise BizException(404, "照片不存在")
    return p


async def find_by_hash(db: AsyncSession, user_id: int, file_hash: str) -> Photo | None:
    """按 sha256 查找当前用户未删除的照片。"""
    return (await db.execute(
        select(Photo).where(
            Photo.user_id == user_id,
            Photo.file_hash == file_hash,
            Photo.deleted_at.is_(None),
        )
    )).scalar_one_or_none()


async def recycle_by_hash(db: AsyncSession, user_id: int, file_hash: str) -> Photo | None:
    """按 sha256 查找当前用户**已软删除**的照片，供 upload 路由恢复用。

    UNIQUE(user_id, file_hash) 约束没考虑 deleted_at，所以新插入前必须先确认
    是不是同 hash 的软删行——找到了就恢复，不重建（避免约束冲突）。
    """
    return (await db.execute(
        select(Photo).where(
            Photo.user_id == user_id,
            Photo.file_hash == file_hash,
            Photo.deleted_at.is_not(None),
        )
    )).scalar_one_or_none()


async def list_user_photos(
    db: AsyncSession, user_id: int, page: int, page_size: int
) -> tuple[list[Photo], int, set[int]]:
    """分页获取用户照片列表，返回 (rows, total, favorite_photo_ids)。"""
    base = select(Photo).where(Photo.user_id == user_id, Photo.deleted_at.is_(None))
    total = (await db.execute(
        select(func.count()).select_from(base.subquery())
    )).scalar_one()
    rows = (await db.execute(
        base.order_by(Photo.created_at.desc())
            .offset((page - 1) * page_size)
            .limit(page_size)
    )).scalars().all()
    fav_ids = await _favorite_ids(db, user_id, [p.photo_id for p in rows])
    return list(rows), total, fav_ids


async def recent_photos(db: AsyncSession, user_id: int, limit: int) -> list[Photo]:
    """获取用户最近 N 张照片，按创建时间倒序。"""
    return list((await db.execute(
        select(Photo).where(Photo.user_id == user_id, Photo.deleted_at.is_(None))
        .order_by(Photo.created_at.desc()).limit(limit)
    )).scalars().all())


async def photo_with_analysis(
    db: AsyncSession, photo_id: int, user_id: int
) -> tuple[Photo, PhotoAIAnalysis | None, list, list, list, bool]:
    """返回 (photo, analysis, scene, emotion, tags, is_favorite)。"""
    p = await get_or_404(db, photo_id, user_id)
    analysis = await db.get(PhotoAIAnalysis, photo_id)
    pcat_rows = (await db.execute(
        select(PhotoCategory, Category)
        .join(Category, Category.category_id == PhotoCategory.category_id)
        .where(PhotoCategory.photo_id == photo_id)
    )).all()
    scene = emotion = None
    tags: list = []
    for pc, cat in pcat_rows:
        item = {
            "name": cat.name,
            "confidence": float(pc.confidence),
            "category_id": cat.category_id,
            "type": cat.type.value,
        }
        if cat.type.value == "scene" and pc.is_primary:
            scene = item
        elif cat.type.value == "emotion":
            emotion = item
        else:
            tags.append(item)
    is_fav = await _is_favorite(db, user_id, photo_id)
    return p, analysis, scene, emotion, tags, is_fav


async def search_by_tags(
    db: AsyncSession, user_id: int, tag_names: list[str], page: int, page_size: int
) -> tuple[list[tuple[Photo, list[str], int]], int]:
    """按分类名匹配照片并按命中标签数倒序分页。"""
    if not tag_names:
        return [], 0
    matched = (await db.execute(
        select(Category).where(Category.name.in_(tag_names), Category.is_enabled == 1)
    )).scalars().all()
    if not matched:
        return [], 0
    cat_ids = [c.category_id for c in matched]
    name_by_id = {c.category_id: c.name for c in matched}

    rows = (await db.execute(
        select(Photo.photo_id, PhotoCategory.category_id)
        .join(PhotoCategory, PhotoCategory.photo_id == Photo.photo_id)
        .where(
            Photo.user_id == user_id,
            Photo.deleted_at.is_(None),
            PhotoCategory.category_id.in_(cat_ids),
        )
    )).all()

    agg: dict[int, list[str]] = {}
    for pid, cid in rows:
        agg.setdefault(pid, []).append(name_by_id[cid])
    if not agg:
        return [], 0

    pids_sorted = sorted(agg.keys(), key=lambda pid: (-len(agg[pid]), pid))
    total = len(pids_sorted)
    page_pids = pids_sorted[(page - 1) * page_size: page * page_size]

    photos = (await db.execute(
        select(Photo).where(Photo.photo_id.in_(page_pids))
    )).scalars().all()
    photo_map = {p.photo_id: p for p in photos}
    items = [
        (photo_map[pid], agg[pid], len(agg[pid]))
        for pid in page_pids if pid in photo_map
    ]
    return items, total


async def filter_by_tags(
    db: AsyncSession, user_id: int,
    scene_id: int | None,
    emotion_id: int | None,
    tag_id: int | None,
    page: int, page_size: int,
) -> tuple[list[tuple[Photo, list[str], int]], int]:
    """精准标签筛选（跨类型 AND 语义）。

    - scene_id / emotion_id / tag_id：每个最多一个 tag id，None = 不筛选该类型
    - 至少一个必须非空（全空由 router 校验）
    - 跨类型 AND：照片必须同时命中所有非空 id 才返回
    - 不校验 id 与字段名的类型匹配（service 只按 id 查）
    - 不存在的 id 静默丢弃该字段（视为"不筛选"）
    - 返回 ((Photo, matched_names, hit_count), ...), total，按 pid 倒序
    """
    # 收集所有非空 id（不校验类型，service 只按 id 查）
    requested_ids: list[int] = []
    name_by_id: dict[int, str] = {}
    for cid in (scene_id, emotion_id, tag_id):
        if cid is None:
            continue
        requested_ids.append(cid)

    if not requested_ids:
        return [], 0

    # 用 id 反查 name（不存在则丢弃该 id）
    valid_ids: list[int] = []
    rows = (await db.execute(
        select(Category.category_id, Category.name).where(
            Category.category_id.in_(requested_ids),
            Category.is_enabled == 1,
        )
    )).all()
    for cid, name in rows:
        valid_ids.append(cid)
        name_by_id[cid] = name

    # 校验至少有一个 id 有效（全部 id 都查不到 → 视为非法）
    if not valid_ids:
        from app.exceptions import BizException
        raise BizException(400, "请求的 category_id 无效")

    # 跨类型 AND：每个 id 分别取 pid 集合，最后取交集
    pid_sets: list[set[int]] = []
    for cid in valid_ids:
        rows = (await db.execute(
            select(PhotoCategory.photo_id)
            .join(Photo, Photo.photo_id == PhotoCategory.photo_id)
            .where(
                Photo.user_id == user_id,
                Photo.deleted_at.is_(None),
                PhotoCategory.category_id == cid,
            )
        )).scalars().all()
        pid_sets.append(set(rows))

    pids_set = pid_sets[0]
    for s in pid_sets[1:]:
        pids_set &= s
    if not pids_set:
        return [], 0

    # 排序：按 photo_id 倒序（最新优先）
    pids_sorted = sorted(pids_set, reverse=True)
    total = len(pids_sorted)
    page_pids = pids_sorted[(page - 1) * page_size: page * page_size]
    if not page_pids:
        return [], total

    photos = (await db.execute(
        select(Photo).where(Photo.photo_id.in_(page_pids))
    )).scalars().all()
    photo_map = {p.photo_id: p for p in photos}
    # matched_names：按 valid_ids 顺序（前端可预测展示）
    matched_in_order = [name_by_id[cid] for cid in valid_ids]
    items = [
        (photo_map[pid], matched_in_order, len(matched_in_order))
        for pid in page_pids if pid in photo_map
    ]
    return items, total


async def soft_delete(db: AsyncSession, photo_id: int, user_id: int) -> None:
    """软删除单张照片：写入 deleted_at。"""
    p = await get_or_404(db, photo_id, user_id)
    p.deleted_at = utcnow_naive()
    await db.commit()


async def soft_delete_many(
    db: AsyncSession, photo_ids: list[int], user_id: int
) -> tuple[int, int]:
    """逐条软删除，返回 (success, fail)；遇 BizException(404) 计为失败。"""
    success = 0
    for pid in photo_ids:
        try:
            await soft_delete(db, pid, user_id)
            success += 1
        except BizException:
            continue
    return success, len(photo_ids) - success


async def _is_favorite(db: AsyncSession, user_id: int, photo_id: int) -> bool:
    """判断用户是否收藏了某照片。"""
    return (await db.execute(
        select(func.count(Favorite.user_id)).where(
            Favorite.user_id == user_id, Favorite.photo_id == photo_id,
        )
    )).scalar_one() > 0


async def _favorite_ids(db: AsyncSession, user_id: int, photo_ids: list[int]) -> set[int]:
    """从给定照片 id 集合中筛选出当前用户已收藏的子集。"""
    if not photo_ids:
        return set()
    rows = (await db.execute(
        select(Favorite.photo_id).where(
            Favorite.user_id == user_id, Favorite.photo_id.in_(photo_ids),
        )
    )).scalars().all()
    return set(rows)