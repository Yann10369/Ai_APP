"""泊松融合，进一步消除接缝"""
import cv2
import numpy as np


def _mask_center(mask: np.ndarray):
    ys, xs = np.where(mask > 127)
    if len(xs) == 0:
        return (mask.shape[1] // 2, mask.shape[0] // 2)
    return ((int(xs.min() + xs.max()) // 2), (int(ys.min() + ys.max()) // 2))


def seam_smooth(result: np.ndarray, original: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """泊松无缝克隆（cv2.seamlessClone）
    失败时（如 mask 越界）返回原 result，不抛错
    """
    mask_bin = (mask > 127).astype(np.uint8) * 255
    if mask_bin.sum() == 0:
        return result
    try:
        center = _mask_center(mask_bin)
        return cv2.seamlessClone(result, original, mask_bin, center, cv2.NORMAL_CLONE)
    except cv2.error:
        return result