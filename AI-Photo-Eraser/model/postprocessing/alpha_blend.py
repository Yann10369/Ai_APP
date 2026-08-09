"""边缘 alpha 混合，消除接缝"""
import cv2
import numpy as np


def alpha_blend(
    result: np.ndarray,
    original: np.ndarray,
    mask: np.ndarray,
    feather: int = 3,
) -> np.ndarray:
    """在蒙版边缘做羽化混合
    Args:
        result:   推理输出
        original: 原图
        mask:     0~255，255=修复区
        feather:  羽化半径像素
    """
    if feather <= 0:
        m = (mask.astype(np.float32) / 255.0)[..., None]
    else:
        k = feather * 2 + 1
        m = cv2.GaussianBlur(
            mask.astype(np.float32) / 255.0, (k, k), sigmaX=0
        )[..., None]
    out = (
        result.astype(np.float32) * m
        + original.astype(np.float32) * (1.0 - m)
    )
    return np.clip(out, 0, 255).astype(np.uint8)