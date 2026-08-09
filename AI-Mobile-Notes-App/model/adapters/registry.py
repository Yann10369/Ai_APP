"""模型工厂"""
from typing import Dict, Type

from ..core.base_model import BaseModel
from .openai_adapter import OpenAIAdapter

# 后续可加入 DoubaoAdapter / QwenAdapter
MODEL_REGISTRY: Dict[str, Type[BaseModel]] = {
    "openai": OpenAIAdapter,
}


def get_model(provider: str, **kwargs) -> BaseModel:
    if provider not in MODEL_REGISTRY:
        raise ValueError(
            f"Unknown provider '{provider}'. Available: {list(MODEL_REGISTRY.keys())}"
        )
    return MODEL_REGISTRY[provider](**kwargs)