"""图像 IO 工具"""
import base64
import io
from pathlib import Path
from typing import Union

import numpy as np
from PIL import Image


def load_image(path: Union[str, Path]) -> np.ndarray:
    img = Image.open(str(path)).convert("RGB")
    return np.array(img)


def save_image(arr: np.ndarray, path: Union[str, Path]) -> None:
    Image.fromarray(arr).save(str(path))


def np_to_base64(arr: np.ndarray, fmt: str = "PNG") -> str:
    buf = io.BytesIO()
    Image.fromarray(arr).save(buf, format=fmt)
    return base64.b64encode(buf.getvalue()).decode()


def base64_to_np(b64: str) -> np.ndarray:
    if b64.startswith("data:"):
        b64 = b64.split(",", 1)[1]
    return np.array(Image.open(io.BytesIO(base64.b64decode(b64))).convert("RGB"))