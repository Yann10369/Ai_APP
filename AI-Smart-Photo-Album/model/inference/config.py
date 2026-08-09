"""分类器配置: 从环境变量读取, 兼容 backend .env。"""
from __future__ import annotations

import os
from dataclasses import dataclass, field


# 60 个默认分类(scene/emotion/tag), 与 docs/interface.md 保持一致
DEFAULT_SCENES: list[str] = [
    "🏖️ 海滩", "🏙️ 城市", "🏠 室内", "⛰️ 山景", "🌲 森林",
    "🌾 草原", "🏜️ 沙漠", "❄️ 雪景", "🏞️ 湖泊", "🌊 河流",
    "🏡 乡村", "🛣️ 街景", "🌃 夜景", "🏞️ 公园", "🌷 花园",
    "🏯 古镇", "🏝️ 海岛", "⚓ 码头", "🎓 校园", "🍽️ 餐厅",
]
DEFAULT_EMOTIONS: list[str] = [
    "😄 快乐", "😌 平静", "😢 忧伤", "🤩 兴奋", "🥰 温馨",
    "😔 孤独", "💕 浪漫", "🥹 怀旧", "💚 治愈", "🌿 清新",
    "🥲 感动", "😲 惊喜", "🕊️ 宁静", "🌧️ 忧郁", "😎 放松",
    "⚡ 活力", "☕ 惬意", "🌅 期待", "🤔 沉思", "😊 愉悦",
]
DEFAULT_TAGS: list[str] = [
    "👤 人物", "🏞️ 风景", "🐾 动物", "🍜 美食", "🏛️ 建筑",
    "🌿 植物", "🌸 花卉", "🐱 宠物", "👶 孩童", "👴 老人",
    "💑 情侣", "👫 朋友", "👨‍👩‍👧 家庭", "🤳 自拍", "📸 合影",
    "✈️ 旅行", "🎉 节日", "⚽ 运动", "🎨 艺术", "📷 街拍",
]


@dataclass
class ClassifierConfig:
    """分类器配置: aliyun_* / max_tags / min_confidence / 候选标签集。"""

    aliyun_api_key: str = field(
        default_factory=lambda: os.getenv("DASHSCOPE_API_KEY", os.getenv("ALIYUN_API_KEY", ""))
    )
    aliyun_workspace_id: str = field(
        default_factory=lambda: os.getenv("DASHSCOPE_WORKSPACE_ID", "")
    )
    aliyun_region: str = field(
        default_factory=lambda: os.getenv("DASHSCOPE_REGION", "cn-beijing")
    )
    aliyun_base_url: str = field(
        default_factory=lambda: os.getenv("DASHSCOPE_BASE_URL", os.getenv("ALIYUN_BASE_URL", ""))
    )
    aliyun_model: str = field(
        default_factory=lambda: os.getenv("DASHSCOPE_MODEL", os.getenv("ALIYUN_MODEL", "qwen3.7-plus"))
    )
    aliyun_text_model: str = field(
        default_factory=lambda: os.getenv("DASHSCOPE_TEXT_MODEL", os.getenv("ALIYUN_TEXT_MODEL", "qwen3.7-plus"))
    )
    aliyun_timeout: float = field(
        default_factory=lambda: float(os.getenv("DASHSCOPE_TIMEOUT", os.getenv("ALIYUN_TIMEOUT", "30")))
    )
    max_tags: int = 5
    min_confidence: float = 0.30
    scene_labels: list[str] = field(default_factory=lambda: list(DEFAULT_SCENES))
    emotion_labels: list[str] = field(default_factory=lambda: list(DEFAULT_EMOTIONS))
    tag_labels: list[str] = field(default_factory=lambda: list(DEFAULT_TAGS))

    @classmethod
    def from_env(cls) -> "ClassifierConfig":
        """从环境变量构造配置（默认行为）。"""
        return cls()
