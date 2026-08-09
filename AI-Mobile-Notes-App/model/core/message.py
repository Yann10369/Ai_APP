"""统一消息结构，屏蔽各厂商差异"""
from dataclasses import dataclass
from typing import List, Literal, Union


@dataclass
class TextPart:
    text: str


@dataclass
class ImagePart:
    """图片内容块，支持 URL 或 base64"""
    data: str                       # URL 或 base64 字符串
    type: Literal["url", "base64"] = "url"
    mime_type: str = "image/jpeg"
    detail: Literal["low", "high", "auto"] = "auto"


ContentPart = Union[TextPart, ImagePart]


@dataclass
class Message:
    role: Literal["system", "user", "assistant"]
    content: List[ContentPart]

    @classmethod
    def user_text(cls, text: str) -> "Message":
        return cls(role="user", content=[TextPart(text=text)])

    @classmethod
    def user_multimodal(cls, text: str, images: List[ImagePart]) -> "Message":
        return cls(role="user", content=[TextPart(text=text), *images])