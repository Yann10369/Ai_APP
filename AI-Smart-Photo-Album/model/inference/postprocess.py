"""归一化: 模型原始 dict -> AIAnalysisResult / normalize_query_tags。"""
from __future__ import annotations

import logging
from typing import Iterable

from model.inference.config import ClassifierConfig
from model.inference.schema import AIAnalysisResult, ScoredTag

log = logging.getLogger(__name__)


# 与 DB CHECK 约束 + prompt multipleOf=0.01 对齐
CONF_MIN = 0.1
CONF_MAX = 1.0
DESC_MAX = 80


def _clip_conf(x: float) -> float:
    return max(CONF_MIN, min(CONF_MAX, float(x)))


def _round2(x: float) -> float:
    return round(_clip_conf(x), 2)


def _norm_confidence(v, default: float = 0.5) -> float:
    """清洗 confidence: None/非数字 -> default, 其余 clip+round2。"""
    if v is None:
        return _round2(default)
    try:
        return _round2(float(v))
    except (TypeError, ValueError):
        return _round2(default)


def _norm_description(v) -> str:
    """description 清洗: strip + 截断到 DESC_MAX。"""
    if not isinstance(v, str):
        return ""
    desc = v.strip()
    if len(desc) > DESC_MAX:
        desc = desc[:DESC_MAX].rstrip() + "…"
    return desc


def _norm_name(v) -> str:
    """name 清洗: 非字符串返回空, 否则 strip。"""
    if not isinstance(v, str):
        return ""
    return v.strip()


def _pick_one(
    obj: dict,
    candidates: list[str],
    fallback: str,
    min_conf: float,
    field: str,
) -> tuple[str, float]:
    """scene / emotion 单选: 校验名称/置信度, 失败回退到 fallback。"""
    if not isinstance(obj, dict):
        log.warning("postprocess.%s 不是 dict: %r", field, obj)
        return fallback, _round2(max(min_conf, 0.0))

    name = _norm_name(obj.get("name"))
    conf = _norm_confidence(obj.get("confidence"))

    if name and name in candidates:
        if conf < min_conf:
            log.warning("postprocess.%s confidence 低于阈值: %s=%s conf=%s, fallback=%s",
                        field, field, name, conf, fallback)
            return fallback, _round2(max(min_conf, conf))
        return name, conf

    if name:
        log.warning("postprocess.%s 名称不在候选集: name=%r (候选数=%d), fallback=%s",
                    field, name, len(candidates), fallback)
    return fallback, _round2(max(min_conf, conf))


def _filter_tags(
    items: Iterable,
    candidates: list[str],
    max_n: int,
    min_conf: float,
    exclude_names: set[str],
) -> list[tuple[str, float]]:
    """tags 清洗: 去重 + 排除 scene/emotion + 阈值 + 截断 + 按 confidence 倒序。"""
    out: list[tuple[str, float]] = []
    seen: set[str] = set(exclude_names)
    for raw in items or []:
        if not isinstance(raw, dict):
            continue
        name = _norm_name(raw.get("name"))
        conf = _norm_confidence(raw.get("confidence"))
        if not name:
            continue
        if name in seen:
            log.warning("postprocess.tags 跳过重复或与 scene/emotion 冲突: %r", name)
            continue
        if name not in candidates:
            log.warning("postprocess.tags 名称不在候选集: name=%r", name)
            continue
        if conf < min_conf:
            log.warning("postprocess.tags confidence 低于阈值: %s conf=%s, 丢弃", name, conf)
            continue
        out.append((name, conf))
        seen.add(name)
        if len(out) >= max_n:
            break
    out.sort(key=lambda x: x[1], reverse=True)
    return out


def normalize(raw: dict, cfg: ClassifierConfig) -> AIAnalysisResult:
    """将模型 dict 规范化为 AIAnalysisResult; 字段缺失/越界时打 WARNING 并回退。"""
    if not isinstance(raw, dict):
        raise ValueError("模型返回不是 dict")

    description = _norm_description(raw.get("description"))

    scene_name, scene_conf = _pick_one(
        raw.get("scene", {}) or {},
        cfg.scene_labels,
        fallback=cfg.scene_labels[0] if cfg.scene_labels else "🏠 室内",
        min_conf=cfg.min_confidence,
        field="scene",
    )
    emotion_name, emotion_conf = _pick_one(
        raw.get("emotion", {}) or {},
        cfg.emotion_labels,
        fallback=cfg.emotion_labels[0] if cfg.emotion_labels else "😌 平静",
        min_conf=cfg.min_confidence,
        field="emotion",
    )

    if not description:
        scene_clean = scene_name.split(" ", 1)[-1] if " " in scene_name else scene_name
        description = f"在{scene_clean}边拍摄的照片"
        log.warning("postprocess.description 空, 使用兜底: %r", description)

    tags = _filter_tags(
        raw.get("tags", []) or [],
        cfg.tag_labels,
        max_n=cfg.max_tags,
        min_conf=cfg.min_confidence,
        exclude_names={scene_name, emotion_name},
    )

    if not tags:
        safe_fallback = "🏞️ 风景"
        if safe_fallback in {scene_name, emotion_name} or safe_fallback not in cfg.tag_labels:
            for t in cfg.tag_labels:
                if t not in {scene_name, emotion_name}:
                    safe_fallback = t
                    break
            else:
                safe_fallback = cfg.tag_labels[0] if cfg.tag_labels else "🏞️ 风景"
        tags = [(safe_fallback, cfg.min_confidence)]
        log.warning("postprocess.tags 为空, 使用兜底: %s", safe_fallback)

    return AIAnalysisResult(
        description=description,
        scene_category_name=scene_name,
        scene_confidence=scene_conf,
        emotion_category_name=emotion_name,
        emotion_confidence=emotion_conf,
        tag_category_names=tags,
    )


def normalize_query_tags(
    raw: dict | list,
    all_labels: list[str],
    max_n: int = 5,
    min_conf: float = 0.3,
) -> list[tuple[str, float]]:
    """extract_query_tags 输出归一化, 兼容新旧两种 tags 格式。"""
    if isinstance(raw, list):
        items = raw
    elif isinstance(raw, dict):
        items = raw.get("tags", []) or []
    else:
        return []

    out: list[tuple[str, float]] = []
    seen: set[str] = set()
    s = set(all_labels)
    for it in items:
        if isinstance(it, str):
            name = it.strip()
            conf = 0.5
        elif isinstance(it, dict):
            name = _norm_name(it.get("name"))
            conf = _norm_confidence(it.get("confidence"), default=0.5)
        else:
            continue
        if not name or name not in s or name in seen:
            continue
        if conf < min_conf:
            continue
        out.append((name, conf))
        seen.add(name)
        if len(out) >= max_n:
            break
    return out


__all__ = ["normalize", "normalize_query_tags", "ScoredTag", "CONF_MIN", "CONF_MAX", "DESC_MAX"]
