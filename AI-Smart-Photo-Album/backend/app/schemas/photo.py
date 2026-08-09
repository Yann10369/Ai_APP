"""照片相关 schema：上传、列表、详情、修改、搜索、收藏。"""
from pydantic import BaseModel


class PhotoUploadItem(BaseModel):
    photoId: int
    originalName: str
    thumbnailUrl: str
    size: int
    analysisStatus: str


class PhotoUploadFailed(BaseModel):
    fileName: str
    reason: str


class PhotoUploadResponse(BaseModel):
    successCount: int
    failCount: int
    uploadedPhotos: list[PhotoUploadItem]
    failedFiles: list[PhotoUploadFailed]


class PhotoListItem(BaseModel):
    photoId: int
    thumbnailUrl: str
    width: int | None = None
    height: int | None = None
    createdAt: str
    isFavorite: bool
    analysisStatus: str


class PhotoRecentItem(BaseModel):
    photoId: int
    thumbnailUrl: str
    createdAt: str


class TagWithConfidence(BaseModel):
    name: str
    confidence: float


class AITagResult(BaseModel):
    name: str
    confidence: float


class AIAnalysisBlock(BaseModel):
    description: str | None = None
    scene: AITagResult | None = None
    emotion: AITagResult | None = None
    tags: list[AITagResult] = []


class PhotoDetailMetadata(BaseModel):
    fileName: str
    size: int
    width: int | None = None
    height: int | None = None
    shotAt: str | None = None


class PhotoDetail(BaseModel):
    photoId: int
    originalUrl: str
    thumbnailUrl: str
    metadata: PhotoDetailMetadata
    aiAnalysis: AIAnalysisBlock | None = None
    isFavorite: bool
    createdAt: str


class PhotoUpdateRequest(BaseModel):
    tags: list[str] | None = None
    description: str | None = None


class BatchDeleteRequest(BaseModel):
    photoIds: list[int]


class BatchDeleteResponse(BaseModel):
    successCount: int
    failCount: int


class SearchRequest(BaseModel):
    query: str
    page: int = 1
    pageSize: int = 20


class SearchItem(BaseModel):
    photoId: int
    thumbnailUrl: str
    matchedTags: list[str]
    score: float


class FilterRequest(BaseModel):
    """精准标签筛选请求（无 AI 调用，纯 SQL join）。

    跨类型 AND 语义：照片必须同时命中所有非空字段。
    每个类别最多一个 tag；至少一个字段必须非空（全空时前端不发请求）。
    """
    sceneId: int | None = None
    emotionId: int | None = None
    tagId: int | None = None
    page: int = 1
    pageSize: int = 20


class FavoriteItem(BaseModel):
    photoId: int
    thumbnailUrl: str
    favoritedAt: str