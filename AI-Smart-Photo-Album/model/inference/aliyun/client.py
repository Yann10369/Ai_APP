"""阿里云百炼多模态客户端（OpenAI 兼容模式）。"""
from __future__ import annotations

import base64
import copy
import json
import logging
import mimetypes
import os
import re
from pathlib import Path
from typing import Any

from openai import OpenAI

log = logging.getLogger(__name__)

DEFAULT_MODEL = "qwen3-vl-plus"
DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
WORKSPACE_BASE_URL_TEMPLATE = "https://{workspace_id}.{region}.maas.aliyuncs.com/compatible-mode/v1"


class AliyunAPIError(RuntimeError):
    """阿里云 API 调用失败。"""


def _resolve_base_url() -> str:
    """从环境变量解析 base_url。优先级: DASHSCOPE_BASE_URL > workspace 拼接 > 默认标准端点。"""
    if explicit := os.getenv("DASHSCOPE_BASE_URL", "").strip():
        return explicit
    if ws := os.getenv("DASHSCOPE_WORKSPACE_ID", "").strip():
        region = os.getenv("DASHSCOPE_REGION", "cn-beijing")
        return WORKSPACE_BASE_URL_TEMPLATE.format(workspace_id=ws, region=region)
    return DEFAULT_BASE_URL


class AliyunVisionClient:
    """阿里云百炼多模态客户端。

    两个 model:
      - model: 图片/多模态（默认 qwen3-vl-plus）
      - text_model: 纯文本（默认 qwen3.6-flash），用于搜索 query -> 候选 tag
    同一个 OpenAI client 共享 base_url，调用时按 model id 区分。
    """

    def __init__(
        self,
        api_key: str | None = None,
        base_url: str | None = None,
        model: str | None = None,
        text_model: str | None = None,
    ):
        self.api_key = api_key or os.getenv("DASHSCOPE_API_KEY", "")
        if not self.api_key:
            raise AliyunAPIError("DASHSCOPE_API_KEY 未配置")
        self.base_url = base_url or _resolve_base_url()
        self.model = model or os.getenv("DASHSCOPE_MODEL", DEFAULT_MODEL)
        self.text_model = text_model or os.getenv("DASHSCOPE_TEXT_MODEL", "qwen3.6-flash")
        self.client = OpenAI(api_key=self.api_key, base_url=self.base_url)
        log.info(
            "AliyunVisionClient ready: vision_model=%s, text_model=%s, base_url=%s",
            self.model, self.text_model, self.base_url,
        )

    def _encode_image(self, image_path: str | Path) -> str:
        """读取图片并转为 data URL（base64）。"""
        path = Path(image_path)
        if not path.is_file():
            raise FileNotFoundError(f"Image file not found: {path}")
        mime, _ = mimetypes.guess_type(str(path))
        mime = mime or "image/jpeg"
        image_b64 = base64.b64encode(path.read_bytes()).decode("ascii")
        return f"data:{mime};base64,{image_b64}"

    def vision(self, image_path: str | Path, text: str) -> str:
        """单张图片 + 单条文本，返回模型原始回答。"""
        image_url = self._encode_image(image_path)
        messages = [{
            "role": "user",
            "content": [
                {"type": "image_url", "image_url": {"url": image_url}},
                {"type": "text", "text": text},
            ],
        }]
        return self._chat(messages)

    def vision_messages(self, image_path: str | Path, messages: list[dict[str, Any]]) -> str:
        """vision 模式：messages 由调用方提供，自动向最后一条 user 注入图片。"""
        image_url = self._encode_image(image_path)
        msgs = copy.deepcopy(messages)

        for msg in reversed(msgs):
            if msg.get("role") == "user":
                content = msg.get("content", "")
                if isinstance(content, str):
                    msg["content"] = [
                        {"type": "image_url", "image_url": {"url": image_url}},
                        {"type": "text", "text": content},
                    ]
                elif isinstance(content, list):
                    msg["content"].insert(0, {"type": "image_url", "image_url": {"url": image_url}})
                break
        else:
            msgs.append({
                "role": "user",
                "content": [{"type": "image_url", "image_url": {"url": image_url}}],
            })

        return self._chat(msgs)

    def text(self, text: str, system: str | None = None) -> str:
        """纯文本对话，返回模型原始回答（走 text_model，不开深度思考）。"""
        messages: list[dict[str, Any]] = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": text})
        return self._chat(messages, use_text_model=True)

    def text_messages(self, messages: list[dict[str, Any]]) -> str:
        """纯文本对话，messages 由调用方提供（走 text_model，不开深度思考）。"""
        return self._chat(list(messages), use_text_model=True)

    def _chat(self, messages: list[dict[str, Any]], use_text_model: bool = False) -> str:
        """核心调用：同步阻塞，跑在 classifier 的 to_thread 上下文。

        text / vision 两条路径都显式传 extra_body={"enable_thinking": False}，
        跳过深度思考（百炼的 qwen3.7-plus 默认会进入 thinking 模式，分类/搜索都不需要）。
        """
        model_id = self.text_model if use_text_model else self.model
        try:
            completion = self.client.chat.completions.create(
                model=model_id,
                messages=messages,
                extra_body={"enable_thinking": False},
            )
        except Exception as e:
            raise AliyunAPIError(f"阿里云 API 调用失败(model={model_id}): {e}") from e

        try:
            return completion.choices[0].message.content
        except (AttributeError, IndexError, TypeError) as e:
            raise AliyunAPIError(f"返回结构异常: {e}") from e


_JSON_FENCE_RE = re.compile(r"```(?:json)?\s*(\{.*?\})\s*```", re.DOTALL)


def parse_json_answer(answer: str) -> dict[str, Any]:
    """从模型文本回答中抽取 JSON dict。兼容 markdown 包裹与前后缀废话。"""
    if not answer:
        return {}

    match = _JSON_FENCE_RE.search(answer)
    text_to_parse = match.group(1) if match else answer

    if not match:
        start_idx = text_to_parse.find('{')
        end_idx = text_to_parse.rfind('}')
        if start_idx != -1 and end_idx != -1 and end_idx >= start_idx:
            text_to_parse = text_to_parse[start_idx: end_idx + 1]

    try:
        return json.loads(text_to_parse)
    except json.JSONDecodeError as e:
        log.warning("parse_json_answer 失败. Error: %s, 原始输入: %s", e, repr(answer))
        return {}


__all__ = ["AliyunVisionClient", "AliyunAPIError", "parse_json_answer", "DEFAULT_MODEL"]
