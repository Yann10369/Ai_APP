"""
Stable Diffusion Inpainting 云端后端
- 适配自建 FastAPI 包装的 SD 服务
- 或 replicate / stability.ai 等 OpenAPI 兼容服务
"""
import base64
import io
import time
from typing import Optional

import httpx
import numpy as np
from PIL import Image

from ..core.base_inpainter import BaseInpainter
from ..core.exceptions import BackendUnavailableError, ModelTimeoutError
from ..core.image_types import InpaintRequest, InpaintResult


class SDInpaintingBackend(BaseInpainter):
    name = "sd"

    def __init__(
        self,
        endpoint: str = "",
        api_key: str = "",
        model: str = "stabilityai/stable-diffusion-2-inpainting",
        timeout: int = 60,
        **kwargs,
    ):
        super().__init__(**kwargs)
        if not endpoint:
            raise BackendUnavailableError("SD endpoint 未配置")
        self.endpoint = endpoint
        self.api_key = api_key
        self.model = model
        headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self.client = httpx.AsyncClient(timeout=httpx.Timeout(timeout, connect=10.0), headers=headers)

    async def inpaint(self, req: InpaintRequest) -> InpaintResult:
        t0 = time.time()
        img_b64 = self._np_to_b64(req.image, fmt="PNG")
        mask_b64 = self._np_to_b64(req.mask, fmt="PNG")

        payload = {
            "model": self.model,
            "image": img_b64,
            "mask": mask_b64,
            "prompt": self._prompt_for(req.scene),
            "negative_prompt": "blurry, low quality, distorted, artifacts",
            "guidance_scale": 7.5 if req.quality == "hd" else 5.0,
            "num_inference_steps": 50 if req.quality == "hd" else 25,
            "mask_blur": req.mask_blur,
        }
        try:
            r = await self.client.post(self.endpoint, json=payload)
            r.raise_for_status()
        except httpx.TimeoutException as e:
            raise ModelTimeoutError(str(e)) from e

        body = r.json()
        # 兼容返回 base64 或 URL
        result_img = self._extract_image(body)
        return InpaintResult(
            image=result_img,
            elapsed_ms=int((time.time() - t0) * 1000),
            backend=self.name,
            model_version=self.model,
            meta={"prompt": payload["prompt"]},
        )

    def _prompt_for(self, scene: str) -> str:
        try:
            from ..prompts.loader import PromptLoader
            return PromptLoader().render("sd_inpaint", {"scene": scene})
        except Exception:
            # 兜底 Prompt
            defaults = {
                "person": "Remove the masked person. Naturally reconstruct the background.",
                "pet":    "Remove the masked animal. Reconstruct ground texture seamlessly.",
                "object": "Remove the masked object. Fill with surrounding background.",
                "text":   "Remove the masked text. Keep background texture intact.",
                "watermark": "Remove the masked watermark. Preserve image quality.",
            }
            return defaults.get(scene, defaults["object"])

    def _extract_image(self, body: dict) -> np.ndarray:
        if "image" in body and isinstance(body["image"], str):
            # base64
            data = body["image"].split(",")[-1] if body["image"].startswith("data:") else body["image"]
            return self._b64_to_np(data)
        if "url" in body:
            # 同步下载（简化处理，生产可异步）
            import httpx as _hx
            r = _hx.get(body["url"])
            return np.array(Image.open(io.BytesIO(r.content)).convert("RGB"))
        raise ValueError(f"无法从响应中提取图像: keys={list(body.keys())}")

    @staticmethod
    def _np_to_b64(arr: np.ndarray, fmt: str = "PNG") -> str:
        img = Image.fromarray(arr)
        buf = io.BytesIO()
        img.save(buf, format=fmt)
        return base64.b64encode(buf.getvalue()).decode()

    @staticmethod
    def _b64_to_np(b64: str) -> np.ndarray:
        return np.array(Image.open(io.BytesIO(base64.b64decode(b64))).convert("RGB"))

    async def close(self):
        await self.client.aclose()