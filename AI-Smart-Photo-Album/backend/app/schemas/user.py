"""用户信息 / 统计数据 schema。"""
from pydantic import BaseModel


class UserMeResponse(BaseModel):
    userId: int
    username: str
    email: str | None = None
    avatarUrl: str | None = None
    createdAt: str


class CategoryDistributionItem(BaseModel):
    name: str
    count: int
    percentage: float


class UserStatisticsResponse(BaseModel):
    totalPhotos: int
    analyzedPhotos: int
    favoriteCount: int
    categoryDistribution: dict[str, list[CategoryDistributionItem]]