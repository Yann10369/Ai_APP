"""模型适配器：各厂商实现 + 工厂"""
from .base_model import BaseModel  # noqa: F401  兼容旧导入路径
from .openai_adapter import OpenAIAdapter
from .registry import MODEL_REGISTRY, get_model

__all__ = ["BaseModel", "OpenAIAdapter", "MODEL_REGISTRY", "get_model"]