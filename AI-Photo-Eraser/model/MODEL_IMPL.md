# 项目五：照片智能擦除小助手 — 模型模块实施方案

> 文档版本：v1.0  |  更新日期：2026-08-08
> 目标读者：AI 模型方向开发者
> 适用范围：`AI-Photo-Eraser/model/` 目录

---

## 一、设计目标与原则

| 目标 | 原则 |
|------|------|
| 多后端可切换 | 抽象 `BaseInpainter` 接口，支持 LaMa / SD-Inpainting / Doubao 无缝切换 |
| 端云混合 | 端侧 LaMa 走本地推理，云端 SD/Doubao 走 HTTP API |
| 蒙版预处理统一 | 边缘羽化、resize、bbox 转换集中处理 |
| 后处理可插拔 | Alpha 混合、颜色匹配、纹理合成 |
| Prompt 场景化 | 5 大场景各一套 Prompt 模板，调参灵活 |
| 离线优先 | 端侧模型可用时优先本地，保护隐私 |
| 性能可观测 | 推理耗时、显存占用、缓存命中率均打点 |

---

## 二、目录结构

```
AI-Photo-Eraser/model/
├── README.md
├── requirements.txt
├── .env.example
├── config.py
│
├── core/
│   ├── __init__.py
│   ├── base_inpainter.py          # BaseInpainter 抽象
│   ├── image_types.py             # InpaintRequest / InpaintResult 数据类
│   └── exceptions.py
│
├── backends/                      # 三种后端实现
│   ├── __init__.py
│   ├── lama_backend.py            # LaMa 本地 ONNX Runtime 部署
│   ├── sd_backend.py              # Stable Diffusion Inpainting API
│   ├── doubao_backend.py          # 豆包多模态图像编辑
│   └── registry.py                # BACKEND_REGISTRY 工厂
│
├── prompts/                       # 场景化 Prompt
│   ├── person.yaml
│   ├── pet.yaml
│   ├── object.yaml
│   ├── text.yaml
│   └── watermark.yaml
│
├── preprocessing/                 # 蒙版预处理
│   ├── __init__.py
│   ├── mask_blur.py               # 边缘羽化
│   ├── mask_resize.py             # 与原图对齐
│   ├── mask_dilation.py           # 扩张（避免边缘残留）
│   └── image_normalize.py         # 归一化
│
├── postprocessing/                # 结果后处理
│   ├── __init__.py
│   ├── alpha_blend.py             # 与原图 alpha 混合
│   ├── color_match.py             # 颜色匹配
│   └── seam_smooth.py             # 接缝平滑
│
├── services/
│   ├── __init__.py
│   ├── inpaint_service.py         # 业务编排：预处理 → 推理 → 后处理
│   └── mask_auto_service.py       # 大模型自动生成蒙版
│
├── evaluation/                    # 效果评估
│   ├── __init__.py
│   ├── ssim_metric.py             # SSIM
│   ├── lpips_metric.py            # LPIPS（感知相似度）
│   └── eval_runner.py             # 批量跑测试集
│
├── utils/
│   ├── __init__.py
│   ├── image_io.py                # base64 / 文件 / URL 互转
│   ├── hash.py                    # 内容哈希（用于缓存去重）
│   ├── logger.py
│   └── retry.py
│
├── weights/                       # 模型权重目录（git ignore）
│   ├── lama.onnx                  # LaMa ONNX 权重
│   └── README.md
│
└── tests/
    ├── test_backends.py
    ├── test_preprocessing.py
    ├── test_postprocessing.py
    └── fixtures/                  # 测试样本
```

---

## 三、核心抽象设计

### 3.1 `core/image_types.py`

```python
from dataclasses import dataclass
from typing import Literal, Optional, Tuple
import numpy as np

Scene = Literal["person", "pet", "object", "text", "watermark"]
Quality = Literal["standard", "hd"]

@dataclass
class InpaintRequest:
    image: np.ndarray                  # RGB, uint8, (H, W, 3)
    mask: np.ndarray                   # 单通道 uint8, (H, W), 0/255
    scene: Scene = "object"
    quality: Quality = "standard"
    mask_blur: int = 5                 # 边缘羽化
    mask_dilate: int = 0               # 蒙版扩张像素
    extra: dict = None                 # 后端特定参数

@dataclass
class InpaintResult:
    image: np.ndarray                  # 修复后 RGB 图像
    elapsed_ms: int
    backend: str                       # lama / sd / doubao
    model_version: str
    cached: bool = False
    meta: dict = None
```

### 3.2 `core/base_inpainter.py`

```python
from abc import ABC, abstractmethod
from .image_types import InpaintRequest, InpaintResult

class BaseInpainter(ABC):
    name: str = "base"
    supports_local: bool = False

    def __init__(self, **kwargs):
        self.kwargs = kwargs

    @abstractmethod
    async def inpaint(self, req: InpaintRequest) -> InpaintResult:
        """执行修复，异步封装"""
        ...

    def is_available(self) -> bool:
        """后端是否可用（鉴权、依赖、权重是否齐全）"""
        return True

    async def close(self):
        pass
```

### 3.3 `core/exceptions.py`

```python
class InpaintError(Exception): pass
class BackendUnavailableError(InpaintError): pass
class InvalidMaskError(InpaintError): pass
class SizeMismatchError(InpaintError): pass
class TimeoutError(InpaintError): pass
```

---

## 四、预处理模块

### 4.1 `preprocessing/mask_blur.py`

```python
import cv2
import numpy as np

def blur_mask(mask: np.ndarray, ksize: int = 5) -> np.ndarray:
    """对蒙版做高斯模糊，让边缘羽化，避免硬边"""
    if ksize <= 0: return mask
    if ksize % 2 == 0: ksize += 1
    return cv2.GaussianBlur(mask, (ksize, ksize), 0)
```

### 4.2 `preprocessing/mask_dilation.py`

```python
import cv2
import numpy as np

def dilate_mask(mask: np.ndarray, pixels: int = 2) -> np.ndarray:
    """蒙版向外扩张 N 像素，避免边缘残留目标"""
    if pixels <= 0: return mask
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (pixels * 2 + 1, pixels * 2 + 1))
    return cv2.dilate(mask, kernel, iterations=1)
```

### 4.3 `preprocessing/image_normalize.py`

```python
import numpy as np

def normalize_for_lama(image: np.ndarray) -> np.ndarray:
    """
    LaMa 输入要求：
    - float32
    - 归一化到 [0, 1] 或 [-1, 1]（按模型 checkpoint 配置）
    - 通常 pad 到 8 的倍数
    """
    x = image.astype(np.float32) / 255.0
    # pad to multiple of 8
    h, w = x.shape[:2]
    pad_h = (8 - h % 8) % 8
    pad_w = (8 - w % 8) % 8
    if pad_h or pad_w:
        x = np.pad(x, ((0, pad_h), (0, pad_w), (0, 0)), mode="reflect")
    return x

def denormalize_from_lama(x: np.ndarray, original_shape) -> np.ndarray:
    img = np.clip(x * 255.0, 0, 255).astype(np.uint8)
    return img[:original_shape[0], :original_shape[1]]
```

---

## 五、后端实现要点

### 5.1 `backends/lama_backend.py` — 本地 ONNX 推理

```python
import asyncio, time
import numpy as np
import onnxruntime as ort
from PIL import Image
from ..core.base_inpainter import BaseInpainter
from ..core.image_types import InpaintRequest, InpaintResult
from ..preprocessing.image_normalize import normalize_for_lama, denormalize_from_lama
from ..core.exceptions import BackendUnavailableError

class LaMaBackend(BaseInpainter):
    name = "lama"
    supports_local = True

    def __init__(self, model_path: str = "weights/lama.onnx",
                 provider: str = "cuda",
                 **kw):
        super().__init__(**kw)
        if not Path(model_path).exists():
            raise BackendUnavailableError(f"Model not found: {model_path}")
        providers = self._select_providers(provider)
        self.session = ort.InferenceSession(model_path, providers=providers)
        self.input_name_image = self.session.get_inputs()[0].name
        self.input_name_mask = self.session.get_inputs()[1].name
        self.output_name = self.session.get_outputs()[0].name

    @staticmethod
    def _select_providers(p: str):
        if p == "cuda":
            return ["CUDAExecutionProvider", "CPUExecutionProvider"]
        return ["CPUExecutionProvider"]

    async def inpaint(self, req: InpaintRequest) -> InpaintResult:
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, self._run, req)

    def _run(self, req: InpaintRequest) -> InpaintResult:
        t0 = time.time()
        H, W = req.image.shape[:2]

        img_n = normalize_for_lama(req.image)         # (H', W', 3) float32
        mask_n = req.mask.astype(np.float32) / 255.0   # (H, W)
        mask_n = np.expand_dims(mask_n, axis=-1)       # (H, W, 1)

        # pad mask
        h, w = mask_n.shape[:2]
        pad_h = (8 - h % 8) % 8
        pad_w = (8 - w % 8) % 8
        if pad_h or pad_w:
            mask_n = np.pad(mask_n, ((0, pad_h), (0, pad_w), (0, 0)), mode="constant")

        # 推理
        outputs = self.session.run(
            None,
            {
                self.input_name_image: img_n.transpose(2, 0, 1)[None],    # (1, 3, H', W')
                self.input_name_mask:  mask_n.transpose(2, 0, 1)[None],    # (1, 1, H', W')
            }
        )
        result = outputs[0][0].transpose(1, 2, 0)                          # (H', W', 3)
        result = denormalize_from_lama(result, req.image.shape)

        elapsed_ms = int((time.time() - t0) * 1000)
        return InpaintResult(
            image=result,
            elapsed_ms=elapsed_ms,
            backend=self.name,
            model_version="lama-1.0",
            meta={"device": ort.get_device()}
        )
```

**部署关键点**：
- ONNX 模型可从 LaMa 官方仓库 `export_onnx.py` 导出
- GPU 环境：安装 `onnxruntime-gpu`
- CPU 环境：安装 `onnxruntime`
- 建议最大边长 2048，超出先 resize

### 5.2 `backends/sd_backend.py` — Stable Diffusion Inpainting

```python
import httpx, time, base64
import numpy as np
from PIL import Image
import io
from ..core.base_inpainter import BaseInpainter
from ..core.image_types import InpaintRequest, InpaintResult
from ..core.exceptions import BackendUnavailableError, TimeoutError

class SDInpaintingBackend(BaseInpainter):
    """对接自建的 SD Inpainting 服务（如 replicate / stability.ai / 自建 FastAPI）"""
    name = "sd"

    def __init__(self, endpoint: str, api_key: str = "",
                 model: str = "stabilityai/stable-diffusion-2-inpainting",
                 **kw):
        super().__init__(**kw)
        self.endpoint = endpoint
        self.api_key = api_key
        self.model = model
        self.client = httpx.AsyncClient(
            timeout=httpx.Timeout(60.0, connect=10.0),
            headers={"Authorization": f"Bearer {api_key}"} if api_key else {},
        )

    async def inpaint(self, req: InpaintRequest) -> InpaintResult:
        t0 = time.time()
        img_b64 = self._np_to_base64(req.image)
        mask_b64 = self._np_to_base64_mask(req.mask)
        prompt = self._prompt_for(req.scene)
        negative = "blurry, low quality, distorted, artifacts"

        payload = {
            "model": self.model,
            "prompt": prompt,
            "negative_prompt": negative,
            "image": img_b64,
            "mask": mask_b64,
            "guidance_scale": 7.5 if req.quality == "hd" else 5.0,
            "num_inference_steps": 50 if req.quality == "hd" else 25,
            "mask_blur": req.mask_blur,
        }

        try:
            r = await self.client.post(self.endpoint, json=payload)
            r.raise_for_status()
        except httpx.TimeoutException as e:
            raise TimeoutError(str(e)) from e

        # 响应假设是 base64 图片
        result_b64 = r.json()["image"]
        result_img = self._base64_to_np(result_b64)

        return InpaintResult(
            image=result_img,
            elapsed_ms=int((time.time() - t0) * 1000),
            backend=self.name,
            model_version=self.model,
            meta={"prompt": prompt}
        )

    def _prompt_for(self, scene: str) -> str:
        from ..prompts.loader import PromptLoader
        loader = PromptLoader()
        return loader.render("sd_inpaint", {"scene": scene})

    @staticmethod
    def _np_to_base64(arr: np.ndarray) -> str:
        img = Image.fromarray(arr)
        buf = io.BytesIO(); img.save(buf, format="PNG")
        return base64.b64encode(buf.getvalue()).decode()

    @staticmethod
    def _np_to_base64_mask(mask: np.ndarray) -> str:
        img = Image.fromarray(mask)
        buf = io.BytesIO(); img.save(buf, format="PNG")
        return base64.b64encode(buf.getvalue()).decode()

    @staticmethod
    def _base64_to_np(b64: str) -> np.ndarray:
        img = Image.open(io.BytesIO(base64.b64decode(b64))).convert("RGB")
        return np.array(img)
```

### 5.3 `backends/doubao_backend.py` — 豆包图像编辑

```python
import httpx, time, base64
import numpy as np
from PIL import Image
import io
from ..core.base_inpainter import BaseInpainter
from ..core.image_types import InpaintRequest, InpaintResult

class DoubaoInpaintingBackend(BaseInpainter):
    """豆包（Doubao）多模态图像编辑 API"""
    name = "doubao"
    BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"

    def __init__(self, api_key: str, model: str = "doubao-图像编辑-xxx",
                 endpoint_id: str = "", **kw):
        super().__init__(**kw)
        self.api_key = api_key
        self.model = model
        self.endpoint_id = endpoint_id
        self.client = httpx.AsyncClient(
            base_url=self.BASE_URL,
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=httpx.Timeout(60.0),
        )

    async def inpaint(self, req: InpaintRequest) -> InpaintResult:
        t0 = time.time()
        # 豆包接口通常接收 image + mask + prompt
        payload = {
            "model": self.model or self.endpoint_id,
            "image": f"data:image/png;base64,{self._np_to_b64(req.image)}",
            "mask":  f"data:image/png;base64,{self._np_to_b64_mask(req.mask)}",
            "prompt": self._prompt_for(req.scene),
            "mask_blur": req.mask_blur,
        }
        r = await self.client.post("/images/edits", json=payload)
        r.raise_for_status()
        data = r.json()
        # 解析返回的图片 URL 或 base64
        result_img = self._fetch_or_decode(data)
        return InpaintResult(
            image=result_img,
            elapsed_ms=int((time.time() - t0) * 1000),
            backend=self.name,
            model_version=self.model,
        )

    def _prompt_for(self, scene: str) -> str:
        from ..prompts.loader import PromptLoader
        return PromptLoader().render("doubao_inpaint", {"scene": scene})

    # 同 SD 的图片编解码省略
```

### 5.4 `backends/registry.py`

```python
from typing import Dict, Type
from .base_inpainter import BaseInpainter
from .lama_backend import LaMaBackend
from .sd_backend import SDInpaintingBackend
from .doubao_backend import DoubaoInpaintingBackend

BACKEND_REGISTRY: Dict[str, Type[BaseInpainter]] = {
    "lama":   LaMaBackend,
    "sd":     SDInpaintingBackend,
    "doubao": DoubaoInpaintingBackend,
}

def get_backend(name: str, **kwargs) -> BaseInpainter:
    if name not in BACKEND_REGISTRY:
        raise ValueError(f"Unknown backend: {name}")
    return BACKEND_REGISTRY[name](**kwargs)
```

---

## 六、Prompt 模板

### 6.1 `prompts/person.yaml`

```yaml
name: sd_inpaint_person
version: "1.0"
description: 擦除人物场景
scenes:
  person: |
    Remove the masked person from the image.
    Naturally reconstruct the background scene.
    Maintain consistent lighting, shadows, and perspective.
    Avoid blur, artifacts, or visible seams.
    High quality, photorealistic result.

  pet: |
    Remove the masked animal/pet from the image.
    Reconstruct the ground texture (grass / floor / sand) seamlessly.
    Maintain natural lighting and shadows.

  object: |
    Remove the masked object from the image.
    Fill the area with the surrounding background.
    Preserve original color tone, perspective, and lighting.

  text: |
    Remove the masked text from the image.
    Keep the background texture perfectly intact.
    No visible trace or artifacts in the erased region.

  watermark: |
    Remove the masked watermark.
    Preserve the underlying image quality.
    Fill with natural background texture seamlessly.
```

### 6.2 `prompts/loader.py`

```python
import yaml
from pathlib import Path

class PromptLoader:
    def __init__(self, prompts_dir: str = "model/prompts"):
        self.data = {}
        for f in Path(prompts_dir).glob("*.yaml"):
            d = yaml.safe_load(f.read_text(encoding="utf-8"))
            self.data[d["name"]] = d

    def render(self, name: str, variables: dict) -> str:
        d = self.data[name]
        scene = variables.get("scene", "object")
        return d["scenes"][scene].strip()
```

---

## 七、业务编排：`services/inpaint_service.py`

```python
import asyncio, hashlib
import numpy as np
from ..core.image_types import InpaintRequest, InpaintResult
from ..core.base_inpainter import BaseInpainter
from ..preprocessing.mask_blur import blur_mask
from ..preprocessing.mask_dilation import dilate_mask
from ..postprocessing.alpha_blend import alpha_blend
from ..postprocessing.seam_smooth import seam_smooth
from ..utils.hash import content_hash

class InpaintService:
    def __init__(self, backend: BaseInpainter, cache=None):
        self.backend = backend
        self.cache = cache

    async def run(self, req: InpaintRequest) -> InpaintResult:
        # 1. 校验
        if req.image.shape[:2] != req.mask.shape[:2]:
            raise SizeMismatchError("image and mask shape mismatch")

        # 2. 缓存去重
        key = self._cache_key(req)
        if self.cache and (cached := await self.cache.get(key)):
            return cached

        # 3. 预处理
        processed_mask = req.mask
        if req.mask_dilate > 0:
            processed_mask = dilate_mask(processed_mask, req.mask_dilate)
        if req.mask_blur > 0:
            processed_mask = blur_mask(processed_mask, req.mask_blur)

        req_p = InpaintRequest(
            image=req.image,
            mask=processed_mask,
            scene=req.scene,
            quality=req.quality,
            mask_blur=0,        # 已应用
            mask_dilate=0,
        )

        # 4. 推理
        result = await self.backend.inpaint(req_p)

        # 5. 后处理
        final = seam_smooth(result.image, req.image, processed_mask)
        final = alpha_blend(final, req.image, processed_mask, feather=3)
        result.image = final

        # 6. 缓存
        if self.cache:
            await self.cache.set(key, result, ttl=86400)
        return result

    @staticmethod
    def _cache_key(req: InpaintRequest) -> str:
        h = content_hash(req.image) + content_hash(req.mask)
        return f"{req.scene}:{req.quality}:{h}"
```

---

## 八、后处理

### 8.1 `postprocessing/alpha_blend.py`

```python
import numpy as np
import cv2

def alpha_blend(result: np.ndarray, original: np.ndarray,
                mask: np.ndarray, feather: int = 3) -> np.ndarray:
    """
    在蒙版边缘做羽化混合，避免硬边
    mask: 0~255
    """
    if feather <= 0:
        m = (mask.astype(np.float32) / 255.0)[..., None]
    else:
        m = cv2.GaussianBlur(mask.astype(np.float32) / 255.0,
                             (feather * 2 + 1, feather * 2 + 1), 0)[..., None]
    out = (result.astype(np.float32) * m +
           original.astype(np.float32) * (1 - m))
    return np.clip(out, 0, 255).astype(np.uint8)
```

### 8.2 `postprocessing/seam_smooth.py`

```python
import cv2, numpy as np

def seam_smooth(result: np.ndarray, original: np.ndarray,
                mask: np.ndarray) -> np.ndarray:
    """对修复边界做泊松融合，进一步消除接缝"""
    mask_bool = (mask > 127).astype(np.uint8) * 255
    # cv2.seamlessClone 要求 8-bit 3 通道
    try:
        center = self._mask_center(mask_bool)
        blended = cv2.seamlessClone(result, original, mask_bool, center, cv2.NORMAL_CLONE)
        return blended
    except cv2.error:
        return result

def _mask_center(mask):
    ys, xs = np.where(mask > 127)
    return ((xs.min() + xs.max()) // 2, (ys.min() + ys.max()) // 2)
```

---

## 九、缓存（Redis/本地）

### 9.1 缓存策略

```python
class InpaintCache:
    """(image_hash + mask_hash) 联合去重"""
    def __init__(self, redis_client=None, default_ttl=86400):
        self.r = redis_client
        self.local_store = {} if redis_client is None else None
        self.ttl = default_ttl

    async def get(self, key: str):
        if self.r:
            import pickle
            data = await self.r.get(key)
            return pickle.loads(data) if data else None
        return self.local_store.get(key)

    async def set(self, key: str, value, ttl=None):
        if self.r:
            import pickle
            await self.r.set(key, pickle.dumps(value), ex=ttl or self.ttl)
        else:
            self.local_store[key] = value
```

### 9.2 缓存键设计

```
eraser:{scene}:{quality}:{sha256(image_bytes)}:{sha256(mask_bytes)}
```

- TTL：24 小时
- LRU：磁盘/Redis 内存上限 2GB

---

## 十、自动生成蒙版（可选增强）

### 10.1 `services/mask_auto_service.py`

```python
import numpy as np
from ..core.base_model import BaseModel
from ..core.message import Message, ImagePart, TextPart

class MaskAutoService:
    """调用多模态大模型根据自然语言生成蒙版"""

    def __init__(self, vision_model: BaseModel):
        self.model = vision_model

    async def generate(self, image_b64: str, target_desc: str) -> dict:
        """
        target_desc: "穿红衣服的男孩"
        返回 {"mask_url": ..., "bbox": {...}, "confidence": ...}
        """
        prompt = f"""请定位图中"{target_desc}"的精确像素范围。
        输出 JSON 格式：
        {{"bbox": [x, y, width, height], "confidence": 0.0~1.0}}
        只输出 JSON，不要其他文字。"""
        messages = [
            Message(
                role="user",
                content=[
                    TextPart(prompt),
                    ImagePart(data=image_b64, type="base64"),
                ],
            )
        ]
        resp = await self.model.chat(
            messages,
            temperature=0.1,
            response_format={"type": "json_object"},
        )
        return resp.parsed
```

> 注：仅输出 bbox 后需在后端按 bbox 生成矩形蒙版。如需像素级蒙版，需借助 SAM（Segment Anything）。

---

## 十一、效果评估

### 11.1 `evaluation/ssim_metric.py`

```python
import numpy as np
from skimage.metrics import structural_similarity as ssim

def compute_ssim(img1: np.ndarray, img2: np.ndarray) -> float:
    """结构相似度，1.0 表示完全一致"""
    img1 = img1.astype(np.uint8)
    img2 = img2.astype(np.uint8)
    return ssim(img1, img2, channel_axis=2)
```

### 11.2 `evaluation/eval_runner.py`

```python
import asyncio, json
from pathlib import Path
from ..core.image_types import InpaintRequest
from ..services.inpaint_service import InpaintService
from .ssim_metric import compute_ssim

CASES = json.loads(Path("tests/fixtures/eval_set.json").read_text(encoding="utf-8"))

async def run_eval(backend):
    service = InpaintService(backend)
    results = []
    for case in CASES:
        req = InpaintRequest(
            image=case["image"],
            mask=case["mask"],
            scene=case["scene"],
            quality="hd",
        )
        result = await service.run(req)
        ssim_score = compute_ssim(result.image, case["ground_truth"])
        results.append({"id": case["id"], "scene": case["scene"], "ssim": ssim_score})

    avg = sum(r["ssim"] for r in results) / len(results)
    print(f"SSIM Avg: {avg:.3f}")
    return results
```

### 11.3 评估指标

| 指标 | 说明 | 目标 |
|------|------|------|
| **SSIM** | 结构相似度 | ≥ 0.92 |
| **LPIPS** | 感知相似度（越小越好） | ≤ 0.10 |
| **FID** | 修复区域分布相似度 | 越小越好 |
| **人工评分** | 5 分制，3 人平均 | ≥ 4.0 |
| **耗时** | 端侧 P95 / 云端 P95 | 端 ≤ 8s，云 ≤ 15s |

---

## 十二、配置与环境

### 12.1 `.env.example`

```env
# ===== 默认后端（端云切换）=====
DEFAULT_BACKEND=lama
# 可选: lama / sd / doubao

# ===== LaMa 本地部署 =====
LAMA_MODEL_PATH=./weights/lama.onnx
LAMA_PROVIDER=cpu           # cpu / cuda

# ===== Stable Diffusion =====
SD_ENDPOINT=https://api.replicate.com/v1/predictions
SD_API_KEY=r8_xxx
SD_MODEL=stabilityai/stable-diffusion-2-inpainting

# ===== 豆包 =====
DOUBAO_API_KEY=xxx
DOUBAO_MODEL=doubao-图像编辑-xxx

# ===== 自动蒙版（大模型辅助）=====
VISION_PROVIDER=openai
VISION_API_KEY=sk-xxx
VISION_MODEL=gpt-4o-mini

# ===== 缓存 =====
REDIS_URL=redis://localhost:6379/0
CACHE_TTL=86400

# ===== 评估 =====
EVAL_DATASET=tests/fixtures/eval_set.json
```

### 12.2 `config.py`

```python
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    default_backend: str = "lama"
    lama_model_path: str = "./weights/lama.onnx"
    lama_provider: str = "cpu"
    sd_endpoint: str = ""
    sd_api_key: str = ""
    doubao_api_key: str = ""
    vision_provider: str = "openai"
    vision_api_key: str = ""
    redis_url: str = ""
    cache_ttl: int = 86400

    class Config:
        env_file = ".env"

settings = Settings()
```

---

## 十三、性能优化要点

| 优化点 | 措施 |
|--------|------|
| 端侧推理慢 | GPU + TensorRT；FP16 量化；限制输入边长 2048 |
| 首字节延迟 | 模型预热（启动时跑 1 次 dummy 输入） |
| 并发吞吐 | ONNX Runtime Session 多实例；批量推理 |
| 云端成本 | 缓存去重，相同 (image+mask) 命中直接返回 |
| 网络抖动 | 重试 + 超时回退到本地 LaMa |
| 显存溢出 | 启用 `--enable-cpu-mem-fp16` / 分块推理 |

---

## 十四、上线 Checklist

- [ ] LaMa ONNX 模型导出并放入 `weights/`
- [ ] 至少打通 LaMa + SD 两套后端
- [ ] 5 大场景 Prompt 模板入库
- [ ] 蒙版预处理（resize / blur / dilate）单元测试通过
- [ ] 后处理（alpha 混合 / 泊松融合）通过人眼 50 张抽检
- [ ] 缓存命中率达 15%+（生产数据观察）
- [ ] 评估脚本输出 SSIM ≥ 0.92、LPIPS ≤ 0.10
- [ ] 50 张多场景测试集跑通，平均耗时达标
- [ ] 鉴权 Key 全部走环境变量，前端代码无任何密钥
- [ ] 临时文件 1h 自动清理任务已部署