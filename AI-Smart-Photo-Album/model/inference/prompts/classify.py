"""Prompt 模板: 极简版(去除 markdown 包裹/冗余 schema description, 强化结构化标题)。"""
from __future__ import annotations

import json
from typing import Sequence


SYSTEM_PROMPT = (
    "你是一名专业的相册图片理解助手。请严格根据图片内容进行客观分类与描述。\n"
    "【最高指令】\n"
    "你必须且只能输出一个合法的纯 JSON 对象。禁止任何前缀后缀, 禁止解释, "
    "绝对禁止使用 markdown 代码块(如 ```json)包裹!"
)

_QUERY_TAG_SYSTEM_PROMPT = (
    "你是一个相册搜索意图理解助手。\n"
    "【最高指令】\n"
    "你必须且只能输出一个合法的纯 JSON 对象。禁止任何额外文字, "
    "绝对禁止使用 markdown 代码块(如 ```json)包裹!"
)


def build_classification_prompt(
    scenes: Sequence[str],
    emotions: Sequence[str],
    tags: Sequence[str],
    max_tags: int = 5,
) -> list[dict]:
    """构造图片分类的 system+user messages。"""
    schema = {
        "type": "object",
        "properties": {
            "description": {"type": "string", "minLength": 10, "maxLength": 40},
            "scene": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "enum": list(scenes)},
                    "confidence": {"type": "number", "minimum": 0.1, "maximum": 1.0}
                },
                "required": ["name", "confidence"]
            },
            "emotion": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "enum": list(emotions)},
                    "confidence": {"type": "number", "minimum": 0.1, "maximum": 1.0}
                },
                "required": ["name", "confidence"]
            },
            "tags": {
                "type": "array",
                "minItems": 1,
                "maxItems": max_tags,
                "items": {
                    "type": "object",
                    "properties": {
                        "name": {"type": "string", "enum": list(tags)},
                        "confidence": {"type": "number", "minimum": 0.1, "maximum": 1.0}
                    },
                    "required": ["name", "confidence"]
                }
            }
        },
        "required": ["description", "scene", "emotion", "tags"],
        "additionalProperties": False
    }

    user_text = (
        "请观察照片, 并严格按以下规则输出纯 JSON 数据:\n\n"
        "### 1. 候选集合 (必须一字不差地从中选择, 禁止自造词或跨类选择)\n"
        f"📍 scene (选1项): {', '.join(scenes)}\n"
        f"💗 emotion (选1项): {', '.join(emotions)}\n"
        f"🏷️ tags (选1~{max_tags}项): {', '.join(tags)}\n\n"
        "### 2. 字段规范与硬性约束\n"
        "- description: 10~40字中文, 必须包含画面主体与场景关键词, 禁止emoji/空串/只写'一张照片'。\n"
        "- name: 必须严格匹配上述候选集合(包含 emoji 和空格)。\n"
        "- confidence: 必须是 0.10~1.00 的数字, 且严格保留 2 位小数(例如 0.92, 不能是 0.9 或 0.923)。小于0.1视为无效。\n"
        "- 排重原则: tags 中每一项的 name 禁止与 scene.name 重复, 也禁止与 emotion.name 重复, 且 tags 自身不能有重复项。\n"
        "- 跨类禁止: scene.name 不要选情绪列表里的词, emotion.name 不要选场景列表里的词。\n\n"
        "### 3. JSON Schema 结构定义\n"
        f"{json.dumps(schema, ensure_ascii=False, indent=2)}\n\n"
        "### 4. 正确输出示例 (注意: 不要输出 ```json 标记, 直接输出大括号开头)\n"
        "{\n"
        '  "description": "海边朋友笑着合影",\n'
        '  "scene": {"name": "🏖️ 海滩", "confidence": 0.92},\n'
        '  "emotion": {"name": "😄 快乐", "confidence": 0.85},\n'
        '  "tags": [\n'
        '    {"name": "👫 朋友", "confidence": 0.88},\n'
        '    {"name": "📸 合影", "confidence": 0.80}\n'
        "  ]\n"
        "}"
    )

    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_text},
    ]


def build_query_tag_prompt(query: str, all_labels: Sequence[str]) -> list[dict]:
    """构造搜索 query -> tag 列表的 system+user messages。"""
    schema = {
        "type": "object",
        "properties": {
            "tags": {
                "type": "array",
                "minItems": 0,
                "maxItems": 5,
                "items": {
                    "type": "object",
                    "properties": {
                        "name": {"type": "string", "enum": list(all_labels)},
                        "confidence": {"type": "number", "minimum": 0.1, "maximum": 1.0}
                    },
                    "required": ["name", "confidence"]
                }
            }
        },
        "required": ["tags"],
        "additionalProperties": False
    }

    user_text = (
        f"用户搜索词:「{query}」\n\n"
        "### 1. 候选分类集合 (必须从中选择, 禁止自造词)\n"
        f"{', '.join(all_labels)}\n\n"
        "### 2. 字段规范与硬性约束\n"
        "- tags: 先理解用户的搜索词挑选最多 5 个最相关的分类（也可以为零）, 按 confidence 从高到低排序。\n"
        "- name: 必须一字不差地匹配候选集合。\n"
        "- confidence: 0.10~1.00 的数字, 严格保留 2 位小数(例如 0.90)。\n\n"
        "### 3. JSON Schema\n"
        f"{json.dumps(schema, ensure_ascii=False, indent=2)}\n\n"
        "### 4. 正确输出示例 (直接输出大括号, 不要用 markdown 包裹)\n"
        '{"tags": [{"name": "🏖️ 海滩", "confidence": 0.90}, {"name": "👫 朋友", "confidence": 0.75}]}'
    )
    return [
        {"role": "system", "content": _QUERY_TAG_SYSTEM_PROMPT},
        {"role": "user", "content": user_text},
    ]


__all__ = ["build_classification_prompt", "build_query_tag_prompt", "SYSTEM_PROMPT"]
