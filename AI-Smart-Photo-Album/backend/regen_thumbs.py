"""一次性脚本：把 data/thumb/*.webp 用新 make_thumbnail 中心裁剪 200x200 重建。"""
import sys
from pathlib import Path

# 把 backend 加进 path，方便 import app
sys.path.insert(0, str(Path(__file__).parent))

from sqlalchemy import select
from app.database import AsyncSessionLocal
from app.models.photo import Photo
from app.utils.image import make_thumbnail
import asyncio


async def main():
    base = Path(__file__).parent
    count = 0
    fail = 0
    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(Photo).where(Photo.deleted_at.is_(None))
        )
        photos = result.scalars().all()
        print(f"共 {len(photos)} 张 photo 待重生成")
        for p in photos:
            src = base / p.original_path
            if not src.exists():
                print(f"  [skip] photo_id={p.photo_id} 原图缺失: {src}")
                fail += 1
                continue
            dst = base / p.thumbnail_path if p.thumbnail_path else (base / "data/thumb" / f"{p.photo_id}.webp")
            try:
                make_thumbnail(src, dst)
                count += 1
            except Exception as e:
                print(f"  [fail] photo_id={p.photo_id} {src.name} -> {e}")
                fail += 1
    print(f"\n完成: 成功 {count}, 失败 {fail}")


if __name__ == "__main__":
    asyncio.run(main())
