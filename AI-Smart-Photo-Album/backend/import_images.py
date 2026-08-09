"""把 docs/image_data/ 下的 JPG 通过 /api/v1/photos/upload 批量导入到后端。"""
import argparse
import asyncio
import json
import sys
import time
from pathlib import Path
from typing import Iterable

import httpx

API_DEFAULT = "http://localhost:8000"
IMG_DIR = Path(r"D:\college.study\Project\AI-Smart-Photo-Album\docs\image_data")
BATCH_DEFAULT = 5
USERS = [
    {"username": "alice", "password": "123456", "email": "alice@example.com"},
    {"username": "bob",   "password": "123456", "email": "bob@example.com"},
]
# 锁重试参数
LOCK_RETRY_MAX = 6
LOCK_RETRY_BACKOFF = 2.0   # 每次失败 sleep = backoff * (1 + attempt * 0.5)


def _load_app_for_db():
    """延迟 import：避免 httpx 模式下 --retry-failed 也要加载 SQLAlchemy。"""
    from sqlalchemy import select
    from app.database import AsyncSessionLocal
    from app.models.photo import Photo
    return select, AsyncSessionLocal, Photo


def get_uploaded_names_from_db() -> set[str]:
    """直接查 DB 取已入库 file_name（比 /photos 接口快，且返回完整字段）。"""
    sel, AsyncSessionLocal, Photo = _load_app_for_db()

    async def _q():
        async with AsyncSessionLocal() as s:
            r = await s.execute(
                sel(Photo.file_name).where(Photo.deleted_at.is_(None))
            )
            return {row[0] for row in r.all()}

    return asyncio.run(_q())


def ensure_user(client: httpx.Client, base: str, u: dict) -> str:
    """注册（若已存在则忽略），再登录，返回 token。"""
    r = client.post(f"{base}/api/v1/auth/register", json=u)
    r = client.post(f"{base}/api/v1/auth/login", json={"username": u["username"], "password": u["password"]})
    r.raise_for_status()
    token = r.json()["data"]["token"]
    print(f"  [login] {u['username']:5s} OK (token {token[:20]}...)")
    return token


def upload_batch(client: httpx.Client, base: str, token: str, files: list[Path]) -> dict:
    """上传一个 batch；遇到 5xx / 连接错误自动锁重试。"""
    files_payload = [
        ("files", (f.name, f.read_bytes(), "image/jpeg"))
        for f in files
    ]
    last_err: Exception | None = None
    for attempt in range(LOCK_RETRY_MAX + 1):
        try:
            r = client.post(
                f"{base}/api/v1/photos/upload",
                files=files_payload,
                headers={"Authorization": f"Bearer {token}"},
                timeout=180,
            )
            if r.status_code >= 500:
                raise httpx.HTTPStatusError(
                    f"server {r.status_code}: {r.text[:200]}",
                    request=r.request,
                    response=r,
                )
            r.raise_for_status()
            return r.json()["data"]
        except (httpx.HTTPError, httpx.HTTPStatusError) as e:
            last_err = e
            if attempt >= LOCK_RETRY_MAX:
                break
            sleep_s = LOCK_RETRY_BACKOFF * (1 + attempt * 0.5)
            print(f"\n  [upload] 第 {attempt+1} 次失败（{type(e).__name__}: {str(e)[:80]}），"
                  f"{sleep_s:.1f}s 后重试...", end="", flush=True)
            time.sleep(sleep_s)
    raise RuntimeError(f"upload 多次重试仍失败: {last_err}")


def wait_for_ai_done(client: httpx.Client, base: str, token: str, timeout: int = 900) -> dict:
    """轮询 /ai/status 直到 done == total。"""
    hdr = {"Authorization": f"Bearer {token}"}
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        r = client.get(f"{base}/api/v1/ai/status", headers=hdr)
        r.raise_for_status()
        s = r.json()["data"]
        last = s
        pct = f"{s['done']}/{s['total']} ({s['progress']*100:.1f}%)"
        print(f"\r  [ai] {pct}", end="", flush=True)
        if s["total"] > 0 and s["done"] == s["total"]:
            print()
            return s
        time.sleep(2)
    print(f"\n  [ai] TIMEOUT（最后状态 done={last['done'] if last else '?'}）")
    return last or {}


def get_uploaded_names(client: httpx.Client, base: str, token: str) -> set[str]:
    """已弃用：改用 get_uploaded_names_from_db。"""
    return get_uploaded_names_from_db()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default=API_DEFAULT)
    ap.add_argument("--batch", type=int, default=BATCH_DEFAULT)
    ap.add_argument("--user", choices=["alice", "bob", "all"], default="all")
    ap.add_argument("--force", action="store_true", help="强制全部重传（默认只补传未入库的）")
    ap.add_argument("--retry-failed", type=Path, help="从 JSON 文件读取文件名列表重传")
    ap.add_argument("--failed-out", type=Path, default=Path("failed_files.json"),
                    help="失败文件列表输出（默认 ./failed_files.json）")
    args = ap.parse_args()

    if not IMG_DIR.exists():
        print(f"图片目录不存在: {IMG_DIR}")
        return 1

    images = sorted(IMG_DIR.glob("*.jpg"))
    if not images:
        print("没找到 jpg")
        return 1
    print(f"找到 {len(images)} 张 JPG（{sum(p.stat().st_size for p in images) / 1024 / 1024:.1f} MB）")

    targets = USERS if args.user == "all" else [u for u in USERS if u["username"] == args.user]
    half = len(images) // len(targets)
    splits: list[list[Path]] = []
    for i, _ in enumerate(targets):
        start = i * half
        end = (i + 1) * half if i < len(targets) - 1 else len(images)
        splits.append(images[start:end])

    all_failed: list[str] = []

    with httpx.Client() as client:
        for u, share in zip(targets, splits):
            print(f"\n=== {u['username']}（分配 {len(share)} 张）===")
            token = ensure_user(client, args.api, u)

            if args.retry_failed:
                names = {p.name for p in args.retry_failed.read_text(encoding="utf-8").splitlines() if p.strip()}
                to_upload = [p for p in share if p.name in names]
                print(f"  [mode] 重传模式: {len(to_upload)} 张（来自 {args.retry_failed}）")
            elif args.force:
                to_upload = list(share)
                print(f"  [mode] 强制全传: {len(to_upload)} 张")
            else:
                uploaded = get_uploaded_names(client, args.api, token)
                to_upload = [p for p in share if p.name not in uploaded]
                print(f"  [mode] 补传模式: 已入 {len(uploaded)}/{len(share)} 张, 待补 {len(to_upload)} 张")

            if not to_upload:
                print("  [skip] 没有要传的")
                continue

            t0 = time.time()
            ok = 0
            for i in range(0, len(to_upload), args.batch):
                batch = to_upload[i : i + args.batch]
                try:
                    d = upload_batch(client, args.api, token, batch)
                    ok += d.get("successCount", 0)
                    failed_in_batch = d.get("failedFiles") or []
                    if failed_in_batch:
                        all_failed.extend(failed_in_batch)
                        print(f"  [upload] batch {i // args.batch + 1} 失败: {failed_in_batch}")
                except Exception as e:
                    print(f"  [upload] batch {i // args.batch + 1} 整批失败: {e}")
                    all_failed.extend(f.name for f in batch)
                print(f"  [upload] {min(i + args.batch, len(to_upload))}/{len(to_upload)}  ok={ok}")
            print(f"  [upload] 耗时 {time.time() - t0:.1f}s")
            print("  [ai] 等待分析完成...")
            s = wait_for_ai_done(client, args.api, token)
            print(f"  [ai] 完成: total={s.get('total')} done={s.get('done')} "
                  f"failed={s.get('failed', 0)} progress={s.get('progress', 0)*100:.1f}%")

    if all_failed:
        args.failed_out.write_text("\n".join(all_failed), encoding="utf-8")
        print(f"\n失败文件 {len(all_failed)} 个，已写入 {args.failed_out}")
    else:
        if args.failed_out.exists():
            args.failed_out.unlink()
        print("\n=== 全部成功！===")

    print("\n=== 汇总 ===")
    with httpx.Client() as client:
        for u in USERS:
            r = client.post(f"{args.api}/api/v1/auth/login", json={"username": u["username"], "password": u["password"]})
            token = r.json()["data"]["token"]
            me = client.get(f"{args.api}/api/v1/users/me/statistics",
                            headers={"Authorization": f"Bearer {token}"}).json()["data"]
            print(f"  {u['username']:5s}  photos={me['totalPhotos']}  "
                  f"analyzed={me['analyzedPhotos']}  favorites={me['favoriteCount']}")
    return 0 if not all_failed else 2


if __name__ == "__main__":
    sys.exit(main())
