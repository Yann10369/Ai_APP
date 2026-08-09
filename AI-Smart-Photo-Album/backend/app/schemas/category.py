"""分类相关 schema：预览、列表、详情、Admin CRUD。"""
from pydantic import BaseModel


class CategoryPreviewItem(BaseModel):
    categoryId: int
    categoryName: str
    photoCount: int
    previewPhotos: list[dict]  # [{photoId, thumbnailUrl}]


class CategoryPreviewResponse(BaseModel):
    scene: list[CategoryPreviewItem]
    emotion: list[CategoryPreviewItem]
    tag: list[CategoryPreviewItem]


class CategoryListItem(BaseModel):
    categoryId: int
    categoryName: str
    photoCount: int
    coverThumbnail: str | None = None


class CategoryListResponse(BaseModel):
    type: str
    list: list[CategoryListItem]


class CategoryPhotoItem(BaseModel):
    photoId: int
    thumbnailUrl: str
    createdAt: str


class CategoryPhotoResponse(BaseModel):
    categoryId: int
    categoryName: str
    list: list[CategoryPhotoItem]
    total: int
    page: int
    pageSize: int


class AdminCategoryItem(BaseModel):
    categoryId: int
    type: str
    name: str
    iconUrl: str | None = None
    photoCount: int
    createdAt: str


class AdminCategoryListResponse(BaseModel):
    list: list[AdminCategoryItem]
    total: int


class AdminCategoryCreateRequest(BaseModel):
    type: str
    name: str
    iconUrl: str | None = None


class AdminCategoryCreateResponse(BaseModel):
    categoryId: int


class AdminCategoryUpdateRequest(BaseModel):
    name: str | None = None
    iconUrl: str | None = None


class AdminCategoryResetRequest(BaseModel):
    confirm: bool


class AdminCategoryResetResponse(BaseModel):
    resetCount: int
    removedCount: int