"""收藏关系：幂等 add/remove + 用户维度分页查询。"""
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Favorite, Photo
from app.services.photo_service import get_or_404 as photo_get_or_404


async def add(db: AsyncSession, user_id: int, photo_id: int) -> None:
    """幂等添加收藏：照片不存在抛 404，已存在则跳过。"""
    await photo_get_or_404(db, photo_id, user_id)
    if await db.get(Favorite, (user_id, photo_id)):
        return
    db.add(Favorite(user_id=user_id, photo_id=photo_id))
    await db.commit()


async def remove(db: AsyncSession, user_id: int, photo_id: int) -> None:
    """幂等取消收藏：记录不存在则跳过。"""
    existing = await db.get(Favorite, (user_id, photo_id))
    if not existing:
        return
    await db.delete(existing)
    await db.commit()


async def list_favorites(
    db: AsyncSession, user_id: int, page: int, page_size: int
) -> tuple[list[tuple[Photo, Favorite]], int]:
    """分页获取用户收藏的照片列表，按收藏时间倒序。"""
    base = (
        select(Photo, Favorite)
        .join(Favorite, Favorite.photo_id == Photo.photo_id)
        .where(Favorite.user_id == user_id, Photo.deleted_at.is_(None))
    )
    total = (await db.execute(
        select(func.count()).select_from(base.subquery())
    )).scalar_one()
    rows = (await db.execute(
        base.order_by(Favorite.created_at.desc())
            .offset((page - 1) * page_size).limit(page_size)
    )).all()
    return list(rows), total