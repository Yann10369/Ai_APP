"""Inpainter 抽象基类"""
from abc import ABC, abstractmethod

from .image_types import InpaintRequest, InpaintResult


class BaseInpainter(ABC):
    name: str = "base"
    supports_local: bool = False

    def __init__(self, **kwargs):
        self.kwargs = kwargs

    @abstractmethod
    async def inpaint(self, req: InpaintRequest) -> InpaintResult:
        """执行修复"""

    def is_available(self) -> bool:
        """后端是否可用（子类可重写做健康检查）"""
        return True

    async def close(self):
        """关闭资源（HTTP client / ONNX session 等）"""