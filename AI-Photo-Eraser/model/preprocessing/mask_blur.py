"""蒙版边缘羽化"""
import cv2
import numpy as np


def blur_mask(mask: np.ndarray, ksize: int = 5) -> np.ndarray:
    """对蒙版做高斯模糊，让边缘羽化，避免硬边
    Args:
        mask: 单通道 uint8, 0~255
        ksize: 高斯核大小（自动调整为奇数）
    """
    if ksize <= 0:
        return mask
    if ksize % 2 == 0:
        ksize += 1
    return cv2.GaussianBlur(mask, (ksize, ksize), sigmaX=0)