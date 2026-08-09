"""AI 服务封装：analyze_photo 与 extract_query_tags。"""
from __future__ import annotations

import logging
import sys
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger(__name__)


def _load_backend_env():
    """把 backend/.env 显式注入到 os.environ。

    ClassifierConfig 用 os.getenv 读，pydantic-settings 只把它读进 Settings 对象
    不会自动 export 到 os.environ，所以这里手动 dotenv load 一次。
    """
    from dotenv import load_dotenv
    env_path = Path(__file__).resolve().parents[2] / ".env"  # backend/.env
    if env_path.exists():
        load_dotenv(env_path, override=False)


@dataclass
class AIAnalysisResult:
    """单张照片的分类结果。"""

    description: str
    scene_category_name: str
    scene_confidence: float
    emotion_category_name: str
    emotion_confidence: float
    tag_category_names: list[tuple[str, float]]


# 懒加载 PhotoClassifier（缺包 / 缺 DASHSCOPE_API_KEY 时首次调用抛错）
_clf = None


def _get_classifier():
    global _clf
    if _clf is not None:
        return _clf
    _load_backend_env()
    # 文件布局: <repo_root>/backend/app/services/ai.py → parents[3] = repo_root (含 model/)
    _PROJECT_ROOT = Path(__file__).resolve().parents[3]
    if str(_PROJECT_ROOT) not in sys.path:
        sys.path.insert(0, str(_PROJECT_ROOT))
    from model.inference import ClassifierConfig, PhotoClassifier  # noqa: E402

    cfg = ClassifierConfig.from_env()
    if not cfg.aliyun_api_key.strip():
        raise RuntimeError("DASHSCOPE_API_KEY 未配置，AI 服务无法启动")
    _clf = PhotoClassifier(cfg)
    log.info(
        "AI 服务初始化完成: model=%s, base_url=%s",
        cfg.aliyun_model, cfg.aliyun_base_url or "<default>",
    )
    return _clf


def reset_classifier() -> None:
    """测试用: 重置单例。"""
    global _clf
    _clf = None


async def analyze_photo(photo_path: str, photo_id: int) -> AIAnalysisResult:
    """分析单张照片。失败抛异常。"""
    clf = _get_classifier()
    result = await clf.analyze(photo_path, photo_id=photo_id)
    return AIAnalysisResult(
        description=result.description,
        scene_category_name=result.scene_category_name,
        scene_confidence=result.scene_confidence,
        emotion_category_name=result.emotion_category_name,
        emotion_confidence=result.emotion_confidence,
        tag_category_names=list(result.tag_category_names),
    )


async def extract_query_tags(query: str) -> list[tuple[str, float]]:
    """从自然语言 query 抽取相关分类。失败抛异常（搜索路由会处理）。"""
    query = (query or "").strip()
    if not query:
        return []
    clf = _get_classifier()
    return await clf.extract_query_tags(query)


__all__ = ["AIAnalysisResult", "analyze_photo", "extract_query_tags", "reset_classifier"]
