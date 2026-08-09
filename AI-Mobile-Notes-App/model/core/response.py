"""统一响应结构"""
from dataclasses import dataclass
from typing import Any, Optional


@dataclass
class Usage:
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    estimated_cost_usd: float = 0.0


@dataclass
class ModelResponse:
    content: str                                   # 文本输出
    parsed: Optional[Any] = None                   # JSON 模式解析后的对象
    usage: Optional[Usage] = None
    model: str = ""
    elapsed_ms: int = 0
    raw: Optional[dict] = None                     # 原始响应，便于排查