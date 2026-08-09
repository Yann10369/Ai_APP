"""文本 AI 服务：润色 / 翻译 / 思维导图"""
from ..core.base_model import BaseModel
from ..core.message import Message
from ..core.prompt_loader import PromptLoader


class TextAIService:
    def __init__(self, model: BaseModel, prompts: PromptLoader):
        self.model = model
        self.prompts = prompts

    async def polish(self, text: str, mode: str = "polish") -> str:
        """mode: polish / expand / shorten / formal"""
        sys_msg, user_msg, meta = self.prompts.render(
            "text_polish", {"text": text, "mode": mode}
        )
        resp = await self.model.chat(
            [Message.user_text(sys_msg + "\n" + user_msg)],
            temperature=meta.get("temperature", 0.6),
            max_tokens=meta.get("max_tokens", 1500),
        )
        return resp.content.strip()

    async def translate(self, text: str, src: str = "zh", tgt: str = "en") -> str:
        sys_msg, user_msg, meta = self.prompts.render(
            "translation", {"text": text, "src_lang": src, "tgt_lang": tgt}
        )
        resp = await self.model.chat(
            [Message.user_text(sys_msg + "\n" + user_msg)],
            temperature=meta.get("temperature", 0.2),
            max_tokens=meta.get("max_tokens", 1500),
        )
        return resp.content.strip()

    async def mindmap(self, text: str) -> str:
        sys_msg, user_msg, meta = self.prompts.render(
            "mindmap_generation", {"text": text}
        )
        resp = await self.model.chat(
            [Message.user_text(sys_msg + "\n" + user_msg)],
            temperature=meta.get("temperature", 0.4),
            max_tokens=meta.get("max_tokens", 1500),
        )
        return resp.content.strip()