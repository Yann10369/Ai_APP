"""模型抽象基类"""
from abc import ABC, abstractmethod
from typing import AsyncIterator, List, Optional

from .message import Message
from .response import ModelResponse


class BaseModel(ABC):
    name: str = "base"

    def __init__(self, api_key: str, model_name: str, **kwargs):
        self.api_key = api_key
        self.model_name = model_name
        self.kwargs = kwargs

    @abstractmethod
    async def chat(
        self,
        messages: List[Message],
        *,
        temperature: float = 0.7,
        max_tokens: int = 2048,
        response_format: Optional[dict] = None,
        **extra,
    ) -> ModelResponse:
        """非流式调用"""

    @abstractmethod
    async def stream_chat(
        self,
        messages: List[Message],
        *,
        temperature: float = 0.7,
        max_tokens: int = 2048,
        **extra,
    ) -> AsyncIterator[str]:
        """流式调用，逐块返回文本"""

    async def close(self):
        """关闭 HTTP 连接，子类按需重写"""