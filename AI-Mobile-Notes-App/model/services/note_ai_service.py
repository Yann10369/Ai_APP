"""笔记 AI 服务：摘要 / 知识点 / 题库"""
import hashlib
import json
from typing import List

from ..core.base_model import BaseModel
from ..core.exceptions import OutputParseError
from ..core.message import Message
from ..core.prompt_loader import PromptLoader
from ..cache.prompt_cache import PromptCache


class NoteAIService:
    def __init__(self, model: BaseModel, prompts: PromptLoader, cache: PromptCache):
        self.model = model
        self.prompts = prompts
        self.cache = cache

    async def summarize(self, text: str, max_chars: int = 200) -> str:
        key = f"summary:{hashlib.md5(text.encode()).hexdigest()}:{max_chars}"
        cached = await self.cache.get(key)
        if cached:
            return cached
        sys_msg, user_msg, meta = self.prompts.render(
            "summarization", {"text": text, "max_chars": max_chars}
        )
        resp = await self.model.chat(
            [Message.user_text(sys_msg + "\n" + user_msg)],
            temperature=meta.get("temperature", 0.5),
            max_tokens=meta.get("max_tokens", 600),
        )
        result = resp.content.strip()
        await self.cache.set(key, result)
        return result

    async def extract_knowledge(self, text: str) -> List[dict]:
        key = f"knowledge:{hashlib.md5(text.encode()).hexdigest()}"
        cached = await self.cache.get(key)
        if cached:
            return json.loads(cached)
        sys_msg, user_msg, meta = self.prompts.render(
            "knowledge_extraction", {"text": text}
        )
        resp = await self.model.chat(
            [Message.user_text(sys_msg + "\n" + user_msg)],
            temperature=meta.get("temperature", 0.3),
            max_tokens=meta.get("max_tokens", 800),
            response_format={"type": "json_object"},
        )
        try:
            data = json.loads(resp.content)
            # 兼容 {"items": [...]} / [...] 两种返回
            if isinstance(data, dict) and "items" in data:
                data = data["items"]
            if not isinstance(data, list):
                raise ValueError("not a list")
            await self.cache.set(key, json.dumps(data, ensure_ascii=False))
            return data
        except Exception as e:
            raise OutputParseError(f"knowledge parse failed: {e}; raw={resp.content[:200]}")

    async def generate_questions(self, text: str, count: int = 5) -> List[dict]:
        # 简易实现：与 extract_knowledge 思路一致
        key = f"questions:{hashlib.md5(text.encode()).hexdigest()}:{count}"
        cached = await self.cache.get(key)
        if cached:
            return json.loads(cached)
        sys_msg, user_msg, meta = self.prompts.render(
            "question_generation", {"text": text, "count": count}
        )
        resp = await self.model.chat(
            [Message.user_text(sys_msg + "\n" + user_msg)],
            temperature=meta.get("temperature", 0.7),
            max_tokens=meta.get("max_tokens", 1500),
            response_format={"type": "json_object"},
        )
        try:
            data = json.loads(resp.content)
            await self.cache.set(key, json.dumps(data, ensure_ascii=False))
            return data if isinstance(data, list) else data.get("questions", [])
        except Exception as e:
            raise OutputParseError(f"questions parse failed: {e}")