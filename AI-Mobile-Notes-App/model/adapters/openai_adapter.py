"""
OpenAI / 兼容协议适配器（Chat Completions）
- 官方 OpenAI
- 任何 OpenAI 兼容 API（vLLM / Ollama / OneAPI 等）
"""
import time
from typing import AsyncIterator, List, Optional

import httpx

from ..core.base_model import BaseModel
from ..core.exceptions import (
    AuthError,
    ModelTimeoutError,
    RateLimitError,
)
from ..core.message import ImagePart, Message, TextPart
from ..core.response import ModelResponse, Usage


class OpenAIAdapter(BaseModel):
    name = "openai"

    def __init__(
        self,
        api_key: str,
        model_name: str = "gpt-4o-mini",
        base_url: str = "https://api.openai.com/v1",
        timeout: int = 60,
        **kwargs,
    ):
        super().__init__(api_key, model_name, **kwargs)
        self.client = httpx.AsyncClient(
            base_url=base_url,
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=httpx.Timeout(timeout, connect=10.0),
        )

    # ---------- 消息转换 ----------
    def _convert_messages(self, messages: List[Message]) -> List[dict]:
        out = []
        for m in messages:
            if all(isinstance(p, TextPart) for p in m.content):
                out.append({"role": m.role, "content": "".join(p.text for p in m.content)})
            else:
                blocks = []
                for p in m.content:
                    if isinstance(p, TextPart):
                        blocks.append({"type": "text", "text": p.text})
                    elif isinstance(p, ImagePart):
                        url = (
                            p.data if p.type == "url"
                            else f"data:{p.mime_type};base64,{p.data}"
                        )
                        blocks.append({"type": "image_url", "image_url": {"url": url, "detail": p.detail}})
                out.append({"role": m.role, "content": blocks})
        return out

    # ---------- 非流式 ----------
    async def chat(
        self,
        messages: List[Message],
        *,
        temperature: float = 0.7,
        max_tokens: int = 2048,
        response_format: Optional[dict] = None,
        **extra,
    ) -> ModelResponse:
        t0 = time.time()
        payload = {
            "model": self.model_name,
            "messages": self._convert_messages(messages),
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        if response_format:
            payload["response_format"] = response_format
        payload.update(extra)

        try:
            r = await self.client.post("/chat/completions", json=payload)
        except httpx.TimeoutException as e:
            raise ModelTimeoutError(str(e)) from e

        if r.status_code == 401:
            raise AuthError(r.text)
        if r.status_code == 429:
            raise RateLimitError(r.text)
        r.raise_for_status()
        data = r.json()

        choice = data["choices"][0]
        usage_dict = data.get("usage", {})
        usage = Usage(
            prompt_tokens=usage_dict.get("prompt_tokens", 0),
            completion_tokens=usage_dict.get("completion_tokens", 0),
            total_tokens=usage_dict.get("total_tokens", 0),
        )
        return ModelResponse(
            content=choice["message"]["content"] or "",
            usage=usage,
            model=data.get("model", self.model_name),
            elapsed_ms=int((time.time() - t0) * 1000),
            raw=data,
        )

    # ---------- 流式 ----------
    async def stream_chat(
        self,
        messages: List[Message],
        *,
        temperature: float = 0.7,
        max_tokens: int = 2048,
        **extra,
    ) -> AsyncIterator[str]:
        import json as _json
        payload = {
            "model": self.model_name,
            "messages": self._convert_messages(messages),
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": True,
        }
        payload.update(extra)
        async with self.client.stream("POST", "/chat/completions", json=payload) as r:
            r.raise_for_status()
            async for line in r.aiter_lines():
                if not line.startswith("data: "):
                    continue
                chunk = line[6:].strip()
                if chunk == "[DONE]":
                    break
                try:
                    delta = _json.loads(chunk)["choices"][0]["delta"].get("content", "")
                except Exception:
                    continue
                if delta:
                    yield delta

    async def close(self):
        await self.client.aclose()