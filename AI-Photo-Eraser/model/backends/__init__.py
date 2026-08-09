"""后端实现"""
from .base_inpainter import BaseInpainter  # noqa: F401
from .lama_backend import LaMaBackend
from .sd_backend import SDInpaintingBackend
from .registry import BACKEND_REGISTRY, get_backend

__all__ = ["BaseInpainter", "LaMaBackend", "SDInpaintingBackend", "BACKEND_REGISTRY", "get_backend"]