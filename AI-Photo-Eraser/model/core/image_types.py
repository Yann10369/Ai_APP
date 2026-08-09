"""输入 / 输出数据类型"""
from dataclasses import dataclass, field
from typing import Literal, Optional

import numpy as np

Scene = Literal["person", "pet", "object", "text", "watermark"]
Quality = Literal["standard", "hd"]


@dataclass
class InpaintRequest:
    image: np.ndarray               # RGB, uint8, (H, W, 3)
    mask: np.ndarray                # 单通道 uint8, (H, W), 0/255
    scene: Scene = "object"
    quality: Quality = "standard"
    mask_blur: int = 5              # 边缘羽化像素
    mask_dilate: int = 0            # 蒙版扩张像素
    extra: dict = field(default_factory=dict)


@dataclass
class InpaintResult:
    image: np.ndarray               # 修复后 RGB 图像
    elapsed_ms: int
    backend: str                    # lama / sd / doubao
    model_version: str = ""
    cached: bool = False
    meta: dict = field(default_factory=dict)