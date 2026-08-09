"""
项目五模型模块 —— 最小可运行 Demo
运行：python -m model.main   （在项目根目录）
"""
import asyncio
from pathlib import Path

from backends.registry import get_backend
from config import settings
from core.image_types import InpaintRequest, InpaintResult
from services.inpaint_service import InpaintCache, InpaintService
from utils.image_io import load_image, save_image
from utils.logger import setup_logger


async def main() -> None:
    setup_logger(settings.log_dir, "INFO")

    # ---- 1. 初始化后端（默认 LaMa）----
    try:
        backend = get_backend(
            settings.default_backend,
            model_path=settings.lama_model_path,
            provider=settings.lama_provider,
        )
    except Exception as e:
        print(f"[!] 默认后端 {settings.default_backend} 初始化失败: {e}")
        print("[!] 切换到内存 mock 测试模式")
        backend = MockBackend()

    # ---- 2. 测试图片 ----
    img_path = Path("tests/fixtures/sample.jpg")
    if not img_path.exists():
        print(f"[!] 测试图片不存在: {img_path}，跳过 Demo")
        return

    image = load_image(img_path)
    mask = _make_demo_mask(image.shape[:2])

    # ---- 3. 执行修复 ----
    cache = InpaintCache()
    service = InpaintService(backend, cache=cache)
    req = InpaintRequest(image=image, mask=mask, scene="object", quality="standard", mask_blur=5)

    result = await service.run(req)
    print(f"[完成] backend={result.backend} 耗时={result.elapsed_ms}ms cached={result.cached}")

    # ---- 4. 保存结果 ----
    out_path = Path("tests/fixtures/sample_result.jpg")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    save_image(result.image, out_path)
    print(f"[保存] {out_path}")

    await backend.close()


def _make_demo_mask(shape) -> "np.ndarray":
    """在图片中央生成一个圆形蒙版，用于演示"""
    import numpy as np
    import cv2
    h, w = shape
    mask = np.zeros((h, w), dtype=np.uint8)
    cv2.circle(mask, (w // 2, h // 2), min(h, w) // 4, 255, thickness=-1)
    return mask


class MockBackend:
    """用于演示的 Mock 后端：当 LaMa 权重不存在时直接返回原图"""
    name = "mock"

    async def inpaint(self, req):
        return InpaintResult(image=req.image, elapsed_ms=0, backend=self.name)

    async def close(self):
        pass


if __name__ == "__main__":
    asyncio.run(main())