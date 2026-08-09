"""图像内容哈希，用于缓存键"""
import hashlib

import numpy as np


def image_hash(arr: np.ndarray) -> str:
    """对 ndarray 字节做 SHA256"""
    return hashlib.sha256(arr.tobytes()).hexdigest()[:32]