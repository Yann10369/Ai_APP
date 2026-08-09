"""用户维度统计：总数/已分析/收藏数 + 三类分类分布。"""
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import AnalysisStatus, Category, CategoryType, Favorite, Photo, PhotoCategory


async def statistics(db: AsyncSession, user_id: int) -> dict:
    """汇总用户照片总数 / 已分析数 / 收藏数 / 各类分类分布。"""
    total = (await db.execute(
        select(func.count(Photo.photo_id)).where(Photo.user_id == user_id, Photo.deleted_at.is_(None))
    )).scalar_one()
    analyzed = (await db.execute(
        select(func.count(Photo.photo_id)).where(
            Photo.user_id == user_id, Photo.deleted_at.is_(None),
            Photo.analysis_status == AnalysisStatus.done,
        )
    )).scalar_one()
    favorite_count = (await db.execute(
        select(func.count(Favorite.photo_id)).where(Favorite.user_id == user_id)
    )).scalar_one()

    distribution: dict[str, list] = {t.value: [] for t in CategoryType}
    for t in CategoryType:
        rows = (await db.execute(
            select(Category.name, func.count(PhotoCategory.photo_id))
            .join(PhotoCategory, PhotoCategory.category_id == Category.category_id)
            .join(Photo, Photo.photo_id == PhotoCategory.photo_id)
            .where(
                Category.type == t,
                Photo.user_id == user_id,
                Photo.deleted_at.is_(None),
            )
            .group_by(Category.name)
            .order_by(func.count(PhotoCategory.photo_id).desc())
        )).all()
        distribution[t.value] = [
            {"name": n, "count": c, "percentage": (c / total if total else 0)}
            for n, c in rows
        ]

    return {
        "totalPhotos": total,
        "analyzedPhotos": analyzed,
        "favoriteCount": favorite_count,
        "categoryDistribution": distribution,
    }