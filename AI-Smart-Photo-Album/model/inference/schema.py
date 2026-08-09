"""分类结果数据结构（与 backend AIAnalysisResult 对齐）。"""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class ScoredTag:
    """带置信度的标签条目。"""
    name: str
    confidence: float


@dataclass
class AIAnalysisResult:
    """AI 分类结果（与 backend AIAnalysisResult 对齐）。"""
    description: str
    scene_category_name: str
    scene_confidence: float
    emotion_category_name: str
    emotion_confidence: float
    tag_category_names: list[tuple[str, float]] = field(default_factory=list)


__all__ = ["ScoredTag", "AIAnalysisResult"]
