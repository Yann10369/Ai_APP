"""分类路由：首页预览 / 按 type 列表 / 分类下照片。"""
from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.deps import get_current_user
from app.exceptions import BizException
from app.models import CategoryType, User
from app.response import ok
from app.services import category_service
from app.services.file_storage import thumb_url
from app.schemas.category import (
    CategoryPreviewItem, CategoryPreviewResponse,
)
from app.utils.time import to_iso

router = APIRouter(prefix="/api/v1/categories", tags=["categories"])


def _parse_type(raw: str | None) -> CategoryType | None:
    """将 scene/emotion/tag 字符串转 CategoryType enum；非法抛 400。"""
    if not raw:
        return None
    try:
        return CategoryType(raw)
    except ValueError:
        raise BizException(400, "type 必须是 scene/emotion/tag")


@router.get("/preview")
async def preview(
    previewSize: int = Query(4, ge=1, le=20),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """返回首页三类分类的预览项（含数量与最近 N 张照片）。"""
    all_cats = await category_service.list_by_type(db)
    by_type: dict[str, list] = {t.value: [] for t in CategoryType}
    for c in all_cats:
        by_type[c.type.value].append(c)

    counts = await category_service.count_photos_by_category(
        db, [c.category_id for c in all_cats]
    )

    items: dict[str, list[CategoryPreviewItem]] = {t.value: [] for t in CategoryType}
    for t in CategoryType:
        for c in by_type[t.value]:
            cnt = counts.get(c.category_id, 0)
            if cnt == 0:
                continue
            items[t.value].append(CategoryPreviewItem(
                categoryId=c.category_id,
                categoryName=c.name,
                photoCount=cnt,
                previewPhotos=await category_service.preview_cover(
                    db, c.category_id, user.user_id, previewSize
                ),
            ))
    return ok(data=CategoryPreviewResponse(**items).model_dump())


@router.get("")
async def list_categories(
    type: str | None = Query(None),
    db: AsyncSession = Depends(get_db),
    _user: User = Depends(get_current_user),
):
    """按 type 列出分类及其封面照片。"""
    type_enum = _parse_type(type)
    cats = await category_service.list_by_type(db, type_enum)
    counts = await category_service.count_photos_by_category(db, [c.category_id for c in cats])
    items = []
    for c in cats:
        items.append({
            "categoryId": c.category_id,
            "categoryName": c.name,
            "photoCount": counts.get(c.category_id, 0),
            "coverThumbnail": await category_service.cover_photo(db, c.category_id, _user.user_id),
        })
    return ok(data={"type": type or "all", "list": items})


@router.get("/{category_id}/photos")
async def photos_in_category(
    category_id: int,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """分页获取某分类下当前用户的照片列表。"""
    cat = await category_service.get_or_404(db, category_id)
    rows, total = await category_service.list_photos_in_category(
        db, category_id, user.user_id, page, pageSize
    )
    list_items = [
        {"photoId": p.photo_id, "thumbnailUrl": thumb_url(p.photo_id), "createdAt": to_iso(p.created_at)}
        for p in rows
    ]
    return ok(data={
        "categoryId": cat.category_id,
        "categoryName": cat.name,
        "list": list_items,
        "total": total,
        "page": page,
        "pageSize": pageSize,
    })