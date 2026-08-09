"""主分类器: vision(图片+prompt -> AIAnalysisResult) / text(搜索 query -> 相关分类)。"""
from __future__ import annotations

import asyncio
import logging
from pathlib import Path

from model.inference.aliyun import AliyunVisionClient
from model.inference.aliyun.client import AliyunAPIError, parse_json_answer
from model.inference.config import ClassifierConfig
from model.inference.postprocess import normalize, normalize_query_tags
from model.inference.prompts.classify import (
    build_classification_prompt,
    build_query_tag_prompt,
)
from model.inference.schema import AIAnalysisResult

log = logging.getLogger(__name__)


class PhotoClassifier:
    """异步图片分类器: vision + text 两条路径，调用阿里云百炼 (qwen3-vl-plus)。"""

    def __init__(self, cfg: ClassifierConfig | None = None):
        self.cfg = cfg or ClassifierConfig.from_env()
        # AliyunVisionClient 在缺 DASHSCOPE_API_KEY 时会抛 AliyunAPIError
        self._client = AliyunVisionClient(
            api_key=self.cfg.aliyun_api_key,
            base_url=self.cfg.aliyun_base_url or None,
            model=self.cfg.aliyun_model,
            text_model=self.cfg.aliyun_text_model,
        )

    async def analyze(self, photo_path: str | Path, photo_id: int | None = None) -> AIAnalysisResult:
        """vision: 对单张图片分类。失败抛出异常由调用方处理（worker 会重试）。"""
        path = Path(photo_path)
        if not path.exists():
            raise FileNotFoundError(f"photo not found: {path}")

        messages = build_classification_prompt(
            self.cfg.scene_labels,
            self.cfg.emotion_labels,
            self.cfg.tag_labels,
            max_tags=self.cfg.max_tags,
        )
        answer = await asyncio.to_thread(
            self._client.vision_messages, path, messages
        )
        raw = parse_json_answer(answer)
        if not raw:
            raise AliyunAPIError(f"模型返回非 JSON: {answer[:200]}")
        result = normalize(raw, self.cfg)
        log.info(
            "PhotoClassifier.analyze photo_id=%s -> scene=%s(%.2f) emotion=%s(%.2f) tags=%d",
            photo_id,
            result.scene_category_name, result.scene_confidence,
            result.emotion_category_name, result.emotion_confidence,
            len(result.tag_category_names),
        )
        return result

    async def extract_query_tags(self, query: str) -> list[tuple[str, float]]:
        """text: 自然语言搜索 -> 相关分类列表，失败返回空列表不抛异常。"""
        if not query:
            return []
        all_labels = (
            self.cfg.scene_labels + self.cfg.emotion_labels + self.cfg.tag_labels
        )
        messages = build_query_tag_prompt(query, all_labels)
        answer = await asyncio.to_thread(self._client.text_messages, messages)
        raw = parse_json_answer(answer)
        return normalize_query_tags(raw, all_labels)

    async def extract_query_tags_names(self, query: str) -> list[str]:
        """兼容旧接口: 返回纯 name 列表。"""
        return [n for n, _ in await self.extract_query_tags(query)]


__all__ = ["PhotoClassifier"]
