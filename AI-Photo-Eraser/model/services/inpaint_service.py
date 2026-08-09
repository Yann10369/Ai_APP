"""
InpaintService —— 业务编排
流程：校验 → 缓存去重 → 蒙版预处理 → 后端推理 → 后处理 → 缓存
"""
import hashlib
from typing import Optional

import numpy as np

from ..core.base_inpainter import BaseInpainter
from ..core.exceptions import SizeMismatchError
from ..core.image_types import InpaintRequest, InpaintResult
from ..postprocessing.alpha_blend import alpha_blend
from ..postprocessing.seam_smooth import seam_smooth
from ..preprocessing.mask_blur import blur_mask
from ..preprocessing.mask_dilation import dilate_mask
from ..utils.hash import image_hash


class InpaintService:
    def __init__(self, backend: BaseInpainter, cache: Optional["InpaintCache"] = None):
        self.backend = backend
        self.cache = cache

    async def run(self, req: InpaintRequest) -> InpaintResult:
        # 1. 校验
        if req.image.shape[:2] != req.mask.shape[:2]:
            raise SizeMismatchError(
                f"image shape {req.image.shape[:2]} != mask shape {req.mask.shape[:2]}"
            )

        # 2. 缓存去重
        cache_key = self._cache_key(req)
        if self.cache:
            cached = await self.cache.get(cache_key)
            if cached is not None:
                cached.cached = True
                return cached

        # 3. 蒙版预处理
        processed_mask = req.mask
        if req.mask_dilate > 0:
            processed_mask = dilate_mask(processed_mask, req.mask_dilate)
        if req.mask_blur > 0:
            processed_mask = blur_mask(processed_mask, req.mask_blur)

        # 4. 推理
        req_p = InpaintRequest(
            image=req.image,
            mask=processed_mask,
            scene=req.scene,
            quality=req.quality,
            mask_blur=0,
            mask_dilate=0,
        )
        result = await self.backend.inpaint(req_p)

        # 5. 后处理：先泊松，再 alpha 混合
        smoothed = seam_smooth(result.image, req.image, processed_mask)
        final = alpha_blend(smoothed, req.image, processed_mask, feather=3)
        result.image = final

        # 6. 缓存
        if self.cache:
            await self.cache.set(cache_key, result)
        return result

    @staticmethod
    def _cache_key(req: InpaintRequest) -> str:
        h_img = image_hash(req.image)
        h_msk = image_hash(req.mask)
        return f"eraser:{req.scene}:{req.quality}:{h_img}:{h_msk}"


# ====== 简易缓存实现 ======
class InpaintCache:
    """(image_hash + mask_hash) 联合去重，支持 Redis 或本地内存"""

    def __init__(self, redis_client=None, default_ttl: int = 86400):
        self.r = redis_client
        self.local_store: dict = {}
        self.ttl = default_ttl

    async def get(self, key: str):
        if self.r is not None:
            import pickle
            data = await self.r.get(key)
            return pickle.loads(data) if data else None
        return self.local_store.get(key)

    async def set(self, key: str, value, ttl: Optional[int] = None) -> None:
        if self.r is not None:
            import pickle
            await self.r.set(key, pickle.dumps(value), ex=ttl or self.ttl)
        else:
            self.local_store[key] = value