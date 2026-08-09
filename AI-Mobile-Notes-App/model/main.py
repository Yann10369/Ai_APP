"""
项目四模型模块 —— 最小可运行 Demo
运行：python -m model.main   （在项目根目录）
"""
import asyncio
import base64
from pathlib import Path

from adapters.registry import get_model
from cache.prompt_cache import PromptCache
from config import settings
from core.prompt_loader import PromptLoader
from services.note_ai_service import NoteAIService
from utils.logger import setup_logger


async def main() -> None:
    setup_logger(settings.log_dir, settings.log_level)
    prompts = PromptLoader(settings.prompts_dir)
    cache = PromptCache(max_size=settings.cache_size, default_ttl=settings.cache_ttl)

    # ---- 1. 初始化模型 ----
    model = get_model(
        settings.model_provider,
        api_key=settings.openai_api_key,
        model_name=settings.openai_model,
        base_url=settings.openai_base_url,
        timeout=settings.request_timeout,
    )

    # ---- 2. 测试摘要 ----
    note_ai = NoteAIService(model, prompts, cache)
    sample_text = (
        "极限是高等数学的核心概念之一，包括数列极限和函数极限。"
        "ε-δ 定义是分析学的基础语言，左极限与右极限刻画了函数在一点的趋势。"
        "极限的四则运算与复合运算是常考内容。"
    )
    summary = await note_ai.summarize(sample_text, max_chars=80)
    print(f"[摘要] {summary}")

    # ---- 3. 测试知识点提炼 ----
    knowledge = await note_ai.extract_knowledge(sample_text)
    print(f"[知识点] {knowledge}")

    # ---- 4. （可选）测试多模态 OCR ----
    img_path = Path("tests/fixtures/sample.jpg")
    if img_path.exists():
        img_b64 = base64.b64encode(img_path.read_bytes()).decode()
        from services.ocr_service import OCRService
        from core.message import Message, ImagePart, TextPart
        ocr = OCRService(model, prompts)
        text = await ocr.recognize(img_b64)
        print(f"[OCR] {text[:120]}...")

    await model.close()


if __name__ == "__main__":
    asyncio.run(main())