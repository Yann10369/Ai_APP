"""
LaMa 本地部署（ONNX Runtime）
- CPU：pip install onnxruntime
- GPU：pip install onnxruntime-gpu
- 权重导出：参考 LaMa 官方仓库 scripts/export_onnx.py
"""
import asyncio
import time
from pathlib import Path
from typing import Optional

import numpy as np

try:
    import onnxruntime as ort
except ImportError:                          # pragma: no cover
    ort = None

from ..core.base_inpainter import BaseInpainter
from ..core.exceptions import BackendUnavailableError
from ..core.image_types import InpaintRequest, InpaintResult
from ..preprocessing.image_normalize import (
    denormalize_from_lama,
    normalize_for_lama,
)


class LaMaBackend(BaseInpainter):
    name = "lama"
    supports_local = True

    def __init__(
        self,
        model_path: str = "./weights/lama.onnx",
        provider: str = "cpu",
        **kwargs,
    ):
        super().__init__(**kwargs)
        if ort is None:
            raise BackendUnavailableError("onnxruntime 未安装，请 pip install onnxruntime")

        if not Path(model_path).exists():
            raise BackendUnavailableError(f"LaMa 模型权重不存在: {model_path}")

        providers = self._select_providers(provider)
        # 预热：避免首次请求耗时过长
        self.session = ort.InferenceSession(model_path, providers=providers)
        self.input_names = [i.name for i in self.session.get_inputs()]
        self.output_name = self.session.get_outputs()[0].name
        self._warmup()

    @staticmethod
    def _select_providers(p: str):
        if p == "cuda":
            return ["CUDAExecutionProvider", "CPUExecutionProvider"]
        return ["CPUExecutionProvider"]

    def _warmup(self):
        """使用 dummy 输入预热，避免冷启动"""
        dummy_img = np.zeros((512, 512, 3), dtype=np.float32)
        dummy_mask = np.zeros((512, 512, 1), dtype=np.float32)
        try:
            self.session.run(
                None,
                {
                    self.input_names[0]: dummy_img.transpose(2, 0, 1)[None],
                    self.input_names[1]: dummy_mask.transpose(2, 0, 1)[None],
                },
            )
        except Exception:
            pass

    async def inpaint(self, req: InpaintRequest) -> InpaintResult:
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, self._run, req)

    def _run(self, req: InpaintRequest) -> InpaintResult:
        t0 = time.time()
        H, W = req.image.shape[:2]

        img_n = normalize_for_lama(req.image)         # (H', W', 3) float32
        mask_n = req.mask.astype(np.float32) / 255.0
        if mask_n.ndim == 2:
            mask_n = mask_n[..., None]
        # pad mask
        h, w = mask_n.shape[:2]
        pad_h = (8 - h % 8) % 8
        pad_w = (8 - w % 8) % 8
        if pad_h or pad_w:
            mask_n = np.pad(mask_n, ((0, pad_h), (0, pad_w), (0, 0)), mode="constant")

        outputs = self.session.run(
            None,
            {
                self.input_names[0]: img_n.transpose(2, 0, 1)[None],      # (1, 3, H', W')
                self.input_names[1]: mask_n.transpose(2, 0, 1)[None],     # (1, 1, H', W')
            },
        )
        result = outputs[0][0].transpose(1, 2, 0)                         # (H', W', 3)
        result = denormalize_from_lama(result, req.image.shape)

        return InpaintResult(
            image=result,
            elapsed_ms=int((time.time() - t0) * 1000),
            backend=self.name,
            model_version="lama-onnx",
            meta={"device": ort.get_device()},
        )