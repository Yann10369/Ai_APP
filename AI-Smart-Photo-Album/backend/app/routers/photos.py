"""照片路由：上传 / 列表 / 最近 / 详情 / 修改 / 删除 / 收藏 / 搜索。"""
from fastapi import APIRouter, Depends, File, Query, UploadFile
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.deps import get_current_user
from app.exceptions import BizException
from app.models import (
    AnalysisStatus, AITask, AITaskStatus, Category, CategorySource,
    Photo, PhotoAIAnalysis, PhotoCategory, User,
)
from app.response import ok
from app.schemas.photo import (
    BatchDeleteRequest,
    FilterRequest,
    PhotoDetail, PhotoDetailMetadata, AIAnalysisBlock,
    PhotoUploadItem, PhotoUploadResponse,
    PhotoUpdateRequest, SearchRequest,
)
from app.services import photo_service, favorite_service, file_storage
from app.services.ai import analyze_photo, extract_query_tags
from app.services.file_storage import origin_url, thumb_url
from app.utils.image import make_thumbnail, normalize_ext, read_image_info, SUPPORTED_EXTS
from app.utils.time import to_iso
from app.models.ai_task import notify_new_task

router = APIRouter(prefix="/api/v1/photos", tags=["photos"])


def _photo_to_upload_item(p: Photo) -> PhotoUploadItem:
    """将 Photo 模型转为上传响应项。"""
    return PhotoUploadItem(
        photoId=p.photo_id,
        originalName=p.file_name,
        thumbnailUrl=thumb_url(p.photo_id),
        size=p.file_size,
        analysisStatus=p.analysis_status.value,
    )


@router.post("/upload")
async def upload(
    files: list[UploadFile] = File(...),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """批量上传照片，按 sha256 去重，生成缩略图并加入 AI 分析队列。"""
    max_bytes = settings.MAX_UPLOAD_SIZE_MB * 1024 * 1024
    success: list[PhotoUploadItem] = []
    failed: list[dict] = []
    new_task_photo_ids: list[int] = []

    for f in files:
        try:
            data = await f.read()
            if len(data) > max_bytes:
                raise BizException(400, f"文件 {f.filename} 超过 {settings.MAX_UPLOAD_SIZE_MB}MB")
            ext = normalize_ext(f.filename or "")
            if ext not in SUPPORTED_EXTS:
                raise BizException(400, f"不支持的格式: {ext}")
            file_hash = file_storage.sha256_of_bytes(data)

            existing = await photo_service.find_by_hash(db, user.user_id, file_hash)
            if existing:
                success.append(_photo_to_upload_item(existing))
                continue

            # 同 hash 的软删记录：恢复它（避免触发 UNIQUE(user_id, file_hash) 冲突）
            recycled = await photo_service.recycle_by_hash(db, user.user_id, file_hash)
            if recycled is not None:
                recycled.file_name = f.filename or recycled.file_name
                recycled.file_size = len(data)
                recycled.analysis_status = AnalysisStatus.pending
                recycled.deleted_at = None
                # 重写文件 + 重做缩略图 + 重读尺寸
                origin = file_storage.origin_path(recycled.photo_id, ext)
                recycled.original_path = str(origin)
                await file_storage.save_bytes(origin, data)
                thumb = file_storage.thumb_path(recycled.photo_id)
                try:
                    make_thumbnail(origin, thumb)
                    recycled.thumbnail_path = str(thumb)
                except Exception:
                    recycled.thumbnail_path = None
                info = read_image_info(origin)
                recycled.width = info["width"]
                recycled.height = info["height"]
                recycled.shot_at = info["shot_at"]
                db.add(AITask(photo_id=recycled.photo_id, status=AITaskStatus.queued))
                await db.commit()
                await db.refresh(recycled)
                new_task_photo_ids.append(recycled.photo_id)
                success.append(_photo_to_upload_item(recycled))
                continue

            photo = Photo(
                user_id=user.user_id,
                file_name=f.filename or f"{file_hash}.{ext}",
                file_hash=file_hash,
                file_size=len(data),
                original_path="",
                analysis_status=AnalysisStatus.pending,
            )
            db.add(photo)
            await db.flush()
            origin = file_storage.origin_path(photo.photo_id, ext)
            photo.original_path = str(origin)
            await file_storage.save_bytes(origin, data)

            thumb = file_storage.thumb_path(photo.photo_id)
            try:
                make_thumbnail(origin, thumb)
                photo.thumbnail_path = str(thumb)
            except Exception:
                photo.thumbnail_path = None

            info = read_image_info(origin)
            photo.width = info["width"]
            photo.height = info["height"]
            photo.shot_at = info["shot_at"]

            db.add(AITask(photo_id=photo.photo_id, status=AITaskStatus.queued))
            await db.commit()
            await db.refresh(photo)
            new_task_photo_ids.append(photo.photo_id)
            success.append(_photo_to_upload_item(photo))
        except Exception as e:
            await db.rollback()
            failed.append({"fileName": f.filename or "", "reason": str(e)})

    # 一次唤醒 worker（上传多张也只 notify 一次）
    if new_task_photo_ids:
        notify_new_task()

    return ok(data=PhotoUploadResponse(
        successCount=len(success),
        failCount=len(failed),
        uploadedPhotos=success,
        failedFiles=failed,
    ).model_dump())


@router.get("")
async def list_photos(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """分页返回当前用户的照片列表。"""
    rows, total, fav_ids = await photo_service.list_user_photos(db, user.user_id, page, pageSize)
    items = [
        {
            "photoId": p.photo_id,
            "thumbnailUrl": thumb_url(p.photo_id),
            "width": p.width,
            "height": p.height,
            "createdAt": to_iso(p.created_at),
            "isFavorite": p.photo_id in fav_ids,
            "analysisStatus": p.analysis_status.value,
        } for p in rows
    ]
    return ok(data={"list": items, "total": total, "page": page, "pageSize": pageSize})


@router.get("/recent")
async def recent(
    limit: int = Query(10, ge=1, le=50),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """返回当前用户最近 N 张照片。"""
    rows = await photo_service.recent_photos(db, user.user_id, limit)
    items = [
        {"photoId": p.photo_id, "thumbnailUrl": thumb_url(p.photo_id), "createdAt": to_iso(p.created_at)}
        for p in rows
    ]
    return ok(data={"list": items})


@router.get("/{photo_id}")
async def detail(
    photo_id: int,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """返回单张照片的详情（含元数据、AI 分析结果、收藏状态）。"""
    p, analysis, scene, emotion, tags, is_fav = await photo_service.photo_with_analysis(
        db, photo_id, user.user_id
    )
    ext = p.original_path.rsplit(".", 1)[-1] if "." in p.original_path else "jpg"
    ai_block = None
    if analysis or scene or emotion or tags:
        ai_block = AIAnalysisBlock(
            description=analysis.description if analysis else None,
            scene=scene,
            emotion=emotion,
            tags=tags,
        )
    return ok(data=PhotoDetail(
        photoId=p.photo_id,
        originalUrl=origin_url(p.photo_id, ext),
        thumbnailUrl=thumb_url(p.photo_id),
        metadata=PhotoDetailMetadata(
            fileName=p.file_name,
            size=p.file_size,
            width=p.width,
            height=p.height,
            shotAt=to_iso(p.shot_at),
        ),
        aiAnalysis=ai_block,
        isFavorite=is_fav,
        createdAt=to_iso(p.created_at),
    ).model_dump())


@router.patch("/{photo_id}")
async def update_photo(
    photo_id: int,
    body: PhotoUpdateRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """修改照片的用户自定义标签或描述。"""
    p = await photo_service.get_or_404(db, photo_id, user.user_id)
    if body.tags is not None:
        cats = list((await db.execute(
            select(Category).where(Category.name.in_(body.tags), Category.is_enabled == 1)
        )).scalars().all())
        found = {c.name for c in cats}
        if missing := set(body.tags) - found:
            raise BizException(400, f"以下标签不存在: {missing}")
        # 替换所有 user-source 的旧标签
        old = (await db.execute(
            select(PhotoCategory).where(
                PhotoCategory.photo_id == photo_id,
                PhotoCategory.source == CategorySource.user,
            )
        )).scalars().all()
        for row in old:
            await db.delete(row)
        for c in cats:
            db.add(PhotoCategory(
                photo_id=photo_id,
                category_id=c.category_id,
                source=CategorySource.user,
                is_primary=0,
            ))
    if body.description is not None:
        a = await db.get(PhotoAIAnalysis, photo_id)
        if a:
            a.description = body.description
        else:
            db.add(PhotoAIAnalysis(photo_id=photo_id, description=body.description))
    await db.commit()
    return ok(message="修改成功")


@router.delete("/batch")
async def delete_batch(
    body: BatchDeleteRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """批量软删除当前用户的照片。"""
    if not body.photoIds:
        raise BizException(400, "photoIds 必须为非空数组")
    s, f = await photo_service.soft_delete_many(db, body.photoIds, user.user_id)
    return ok(data={"successCount": s, "failCount": f})


@router.delete("/{photo_id}")
async def delete_one(
    photo_id: int,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """软删除单张照片。"""
    await photo_service.soft_delete(db, photo_id, user.user_id)
    return ok(message="删除成功")


@router.post("/{photo_id}/favorite")
async def favorite(
    photo_id: int,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """收藏当前用户的某张照片。"""
    await favorite_service.add(db, user.user_id, photo_id)
    return ok(message="已收藏")


@router.delete("/{photo_id}/favorite")
async def unfavorite(
    photo_id: int,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """取消收藏当前用户的某张照片。"""
    await favorite_service.remove(db, user.user_id, photo_id)
    return ok(message="已取消收藏")


@router.post("/search")
async def search(
    body: SearchRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """按自然语言 query 检索用户照片，返回按命中数排序的结果。"""
    query = body.query.strip()
    if not query:
        raise BizException(400, "query 不能为空")
    tag_pairs = await extract_query_tags(query)
    tag_names = [n for n, _ in tag_pairs]
    tag_conf_by_name = {n: c for n, c in tag_pairs}
    rows, total = await photo_service.search_by_tags(
        db, user.user_id, tag_names, body.page, body.pageSize
    )
    items = []
    for p, matched, cnt in rows:
        # 命中标签里 confidence 最高的作为锚点；命中越多权重加成
        anchor = max((tag_conf_by_name.get(n, 0.5) for n in matched), default=0.5)
        score = round(min(1.0, anchor * (1.0 + 0.1 * (cnt - 1))), 4)
        items.append({
            "photoId": p.photo_id,
            "thumbnailUrl": thumb_url(p.photo_id),
            "matchedTags": matched,
            "score": score,
        })
    return ok(data={"list": items, "total": total, "page": body.page, "pageSize": body.pageSize})


@router.post("/filter")
async def filter_photos(
    body: FilterRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """精准标签筛选（无 AI 调用）：按 scene / emotion / tag 三个独立字段，跨类型 AND 语义。

    输入字段（详见 FilterRequest）：
      - sceneId:   可选，scene 类 category_id
      - emotionId: 可选，emotion 类 category_id
      - tagId:     可选，tag 类 category_id
      至少一个非空（全空时前端不会发请求）；非空字段之间取交集（AND）。
    输出复用 /search 形态：{list, total, page, pageSize}，list 内含 matchedTags + score。
    score：跨类型全部命中 → 1.0。
    """
    if body.sceneId is None and body.emotionId is None and body.tagId is None:
        raise BizException(400, "sceneId / emotionId / tagId 至少需要一个非空")
    page = max(1, body.page)
    page_size = max(1, min(100, body.pageSize))

    rows, total = await photo_service.filter_by_tags(
        db, user.user_id,
        body.sceneId, body.emotionId, body.tagId,
        page, page_size,
    )

    items = [
        {
            "photoId": p.photo_id,
            "thumbnailUrl": thumb_url(p.photo_id),
            "matchedTags": matched,
            "score": 1.0,
        }
        for p, matched, _cnt in rows
    ]
    return ok(data={"list": items, "total": total, "page": page, "pageSize": page_size})