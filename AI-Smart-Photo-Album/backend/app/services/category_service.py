"""分类数据访问：列表、计数、封面预览、分类下照片。"""
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.exceptions import BizException
from app.models import Category, CategoryType, Photo, PhotoCategory
from app.services.file_storage import thumb_url


async def list_by_type(db: AsyncSession, type_: CategoryType | None = None) -> list[Category]:
    """按 type 过滤获取启用的分类列表。"""
    stmt = (
        select(Category)
        .where(Category.is_enabled == 1)
        .order_by(Category.type, Category.sort_order)
    )
    if type_:
        stmt = stmt.where(Category.type == type_)
    return list((await db.execute(stmt)).scalars().all())


async def get_or_404(db: AsyncSession, category_id: int) -> Category:
    """按 id 取分类，未找到或被禁用则抛 404。"""
    cat = await db.get(Category, category_id)
    if not cat or not cat.is_enabled:
        raise BizException(404, "分类不存在")
    return cat


async def count_photos_by_category(
    db: AsyncSession, category_ids: list[int]
) -> dict[int, int]:
    """返回 {category_id: photo_count}；空入参直接返回 {}。"""
    if not category_ids:
        return {}
    rows = (await db.execute(
        select(PhotoCategory.category_id, func.count(PhotoCategory.photo_id))
        .where(PhotoCategory.category_id.in_(category_ids))
        .group_by(PhotoCategory.category_id)
    )).all()
    return dict(rows)


async def preview_cover(
    db: AsyncSession, category_id: int, user_id: int, n: int = 4
) -> list[dict]:
    """返回某分类下用户最近 n 张照片的 [{photoId, thumbnailUrl}] 列表。"""
    rows = (await db.execute(
        select(Photo.photo_id)
        .join(PhotoCategory, PhotoCategory.photo_id == Photo.photo_id)
        .where(
            PhotoCategory.category_id == category_id,
            Photo.user_id == user_id,
            Photo.deleted_at.is_(None),
        )
        .order_by(Photo.created_at.desc())
        .limit(n)
    )).all()
    return [{"photoId": pid, "thumbnailUrl": thumb_url(pid)} for (pid,) in rows]


async def cover_photo(db: AsyncSession, category_id: int, user_id: int) -> str | None:
    """获取某分类下用户最新一张照片的缩略图 URL 作为封面。"""
    covers = await preview_cover(db, category_id, user_id, n=1)
    return covers[0]["thumbnailUrl"] if covers else None


async def list_photos_in_category(
    db: AsyncSession, category_id: int, user_id: int, page: int, page_size: int
) -> tuple[list[Photo], int]:
    """分页获取某分类下用户的照片列表。"""
    base = (
        select(Photo)
        .join(PhotoCategory, PhotoCategory.photo_id == Photo.photo_id)
        .where(
            PhotoCategory.category_id == category_id,
            Photo.user_id == user_id,
            Photo.deleted_at.is_(None),
        )
    )
    total = (await db.execute(
        select(func.count()).select_from(base.subquery())
    )).scalar_one()
    rows = (await db.execute(
        base.order_by(Photo.created_at.desc())
            .offset((page - 1) * page_size)
            .limit(page_size)
    )).scalars().all()
    return list(rows), total