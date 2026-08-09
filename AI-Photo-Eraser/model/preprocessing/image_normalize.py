"""图像归一化（适配 LaMa）"""
import numpy as np


def normalize_for_lama(image: np.ndarray) -> np.ndarray:
    """LaMa 输入要求：
    - float32
    - 归一化到 [0, 1]
    - pad 到 8 的倍数
    """
    x = image.astype(np.float32) / 255.0
    h, w = x.shape[:2]
    pad_h = (8 - h % 8) % 8
    pad_w = (8 - w % 8) % 8
    if pad_h or pad_w:
        x = np.pad(x, ((0, pad_h), (0, pad_w), (0, 0)), mode="reflect")
    return x


def denormalize_from_lama(x: np.ndarray, original_shape) -> np.ndarray:
    """反归一化 + 裁回原图尺寸"""
    img = np.clip(x * 255.0, 0, 255).astype(np.uint8)
    return img[: original_shape[0], : original_shape[1]]