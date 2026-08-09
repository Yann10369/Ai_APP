"""后端工厂"""
from typing import Dict, Type

from ..core.base_inpainter import BaseInpainter
from .lama_backend import LaMaBackend
from .sd_backend import SDInpaintingBackend

# 后续可加入 DoubaoInpaintingBackend
BACKEND_REGISTRY: Dict[str, Type[BaseInpainter]] = {
    "lama": LaMaBackend,
    "sd":   SDInpaintingBackend,
}


def get_backend(name: str, **kwargs) -> BaseInpainter:
    if name not in BACKEND_REGISTRY:
        raise ValueError(
            f"Unknown backend '{name}'. Available: {list(BACKEND_REGISTRY.keys())}"
        )
    return BACKEND_REGISTRY[name](**kwargs)