"""OCR 服务：调用多模态大模型识别图片中的文字"""
from ..core.base_model import BaseModel
from ..core.message import ImagePart, Message, TextPart
from ..core.prompt_loader import PromptLoader


class OCRService:
    def __init__(self, model: BaseModel, prompts: PromptLoader):
        self.model = model
        self.prompts = prompts

    async def recognize(self, image_b64: str, mime: str = "image/jpeg") -> str:
        sys_msg, user_msg, meta = self.prompts.render("ocr", {})
        messages = [
            Message(
                role="system",
                content=[TextPart(sys_msg)],
            ),
            Message(
                role="user",
                content=[
                    TextPart(user_msg),
                    ImagePart(data=image_b64, type="base64", mime_type=mime),
                ],
            ),
        ]
        resp = await self.model.chat(
            messages,
            temperature=meta.get("temperature", 0.1),
            max_tokens=meta.get("max_tokens", 2048),
        )
        return resp.content.strip()