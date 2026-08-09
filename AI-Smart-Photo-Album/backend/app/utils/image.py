"""图片处理：扩展名、缩略图、EXIF 信息。"""
from datetime import datetime
from pathlib import Path

from PIL import Image, ImageOps

try:
    from pillow_heif import register_heif_opener
    register_heif_opener()
except Exception:
    pass  # pillow-heif 缺失时仅 HEIC 不支持


SUPPORTED_EXTS = {"jpg", "jpeg", "png", "heic", "webp"}


def normalize_ext(filename: str) -> str:
    """从文件名提取小写扩展名；无扩展名时返回 jpg。"""
    if "." not in filename:
        return "jpg"
    return filename.rsplit(".", 1)[-1].lower()


def make_thumbnail(src: Path, dst: Path, size: int = 200) -> None:
    """生成 size×size 居中裁剪的 WebP 缩略图（短边等比放大后中心裁切）。"""
    with Image.open(src) as im:
        im = ImageOps.exif_transpose(im)
        if im.mode in ("RGBA", "P", "LA"):
            bg = Image.new("RGB", im.size, (255, 255, 255))
            mask = im.split()[-1] if im.mode in ("RGBA", "LA") else None
            bg.paste(im, mask=mask)
            im = bg
        elif im.mode != "RGB":
            im = im.convert("RGB")

        w, h = im.size
        if w == 0 or h == 0:
            raise ValueError(f"invalid image size: {w}x{h}")
        scale = size / min(w, h)
        new_w, new_h = max(int(round(w * scale)), size), max(int(round(h * scale)), size)
        im = im.resize((new_w, new_h), Image.Resampling.LANCZOS)

        left = (new_w - size) // 2
        top = (new_h - size) // 2
        im = im.crop((left, top, left + size, top + size))

        dst.parent.mkdir(parents=True, exist_ok=True)
        im.save(dst, "WEBP", quality=85)


def read_image_info(src: Path) -> dict:
    """读取尺寸与 EXIF 拍摄时间；失败时返回空值。"""
    info = {"width": None, "height": None, "shot_at": None}
    try:
        with Image.open(src) as im:
            info["width"], info["height"] = im.size
            raw = im.getexif().get(36867)  # DateTimeOriginal
            if raw:
                try:
                    info["shot_at"] = datetime.strptime(raw, "%Y:%m:%d %H:%M:%S")
                except ValueError:
                    pass
    except Exception:
        pass
    return info