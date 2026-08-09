"""蒙版向外扩张，避免边缘残留目标"""
import cv2
import numpy as np


def dilate_mask(mask: np.ndarray, pixels: int = 2) -> np.ndarray:
    """蒙版向外扩张 N 像素
    Args:
        mask: 单通道 uint8, 0~255
        pixels: 扩张半径
    """
    if pixels <= 0:
        return mask
    k = pixels * 2 + 1
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    return cv2.dilate(mask, kernel, iterations=1)