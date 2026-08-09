"""AI 进度查询 + 重新分析请求/响应。"""
from pydantic import BaseModel


class AIStatusResponse(BaseModel):
    total: int
    done: int
    pending: int
    progress: float


class AIReanalyzeRequest(BaseModel):
    photoIds: list[int]


class AIReanalyzeResponse(BaseModel):
    queuedCount: int
    message: str