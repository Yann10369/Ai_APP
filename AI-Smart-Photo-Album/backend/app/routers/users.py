"""用户路由：当前用户信息 / 统计 / 收藏列表。"""
from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.deps import get_current_user
from app.models import User
from app.response import ok
from app.services import user_service, favorite_service
from app.services.file_storage import thumb_url
from app.utils.time import to_iso
from app.schemas.user import UserMeResponse, UserStatisticsResponse

router = APIRouter(prefix="/api/v1/users", tags=["users"])


@router.get("/me")
async def me(user: User = Depends(get_current_user)):
    """返回当前登录用户的个人信息。"""
    return ok(data=UserMeResponse(
        userId=user.user_id,
        username=user.username,
        email=user.email,
        avatarUrl=user.avatar_url,
        createdAt=to_iso(user.created_at),
    ).model_dump())


@router.get("/me/statistics")
async def statistics(
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """返回当前用户的照片 / 收藏 / 分类分布统计。"""
    data = await user_service.statistics(db, user.user_id)
    return ok(data=UserStatisticsResponse(**data).model_dump())


@router.get("/me/favorites")
async def favorites(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """分页返回当前用户收藏的照片列表。"""
    rows, total = await favorite_service.list_favorites(db, user.user_id, page, pageSize)
    items = [
        {"photoId": p.photo_id, "thumbnailUrl": thumb_url(p.photo_id), "favoritedAt": to_iso(f.created_at)}
        for p, f in rows
    ]
    return ok(data={"list": items, "total": total, "page": page, "pageSize": pageSize})