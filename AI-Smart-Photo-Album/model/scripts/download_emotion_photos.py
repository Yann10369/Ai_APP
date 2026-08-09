"""
download_emotion_photos.py

以流式方式从 HuggingFace 下载 N 张情绪照片（emotion photos）。
由于 datasets 库在中国大陆网络下 HF_ENDPOINT 配置常常不生效，
本脚本绕过 datasets.load_dataset，直接用 huggingface_hub 下载 parquet
文件，再用 pyarrow 流式读取前 N 条样本。

数据集说明:
  默认 Piro17/dataset-affecthqnet-fer2013（FER-2013 的 parquet 镜像，~28k 张）
  含 7 类基础情绪: angry / disgust / fear / happy / sad / surprise / neutral

依赖:
  pip install huggingface_hub Pillow pyarrow requests

运行:
  python model/scripts/download_emotion_photos.py
  python model/scripts/download_emotion_photos.py --num 200 --dataset <other>
"""

from __future__ import annotations

import argparse
import io
import os
import sys
from pathlib import Path

# 中国大陆网络友好：强制使用 hf-mirror 镜像端点
HF_MIRROR = "https://hf-mirror.com"

from huggingface_hub import HfApi
import requests
from PIL import Image

# 候选数据集：必须含可直接下载的 parquet 文件
DEFAULT_DATASETS = [
    "Piro17/dataset-affecthqnet-fer2013",   # parquet, 28k FER-2013
    "Piro17/balancednumber-affecthqnet-fer2013",  # parquet, balanced subset
]

# FER-2013 标准 7 类情绪标签
EMOTION_LABELS = {
    0: "angry",
    1: "disgust",
    2: "fear",
    3: "happy",
    4: "sad",
    5: "surprise",
    6: "neutral",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="流式下载 N 张情绪照片")
    parser.add_argument("--num", type=int, default=100, help="下载数量（默认 100）")
    parser.add_argument("--dataset", type=str, default=None, help="指定数据集 ID")
    parser.add_argument("--split", type=str, default="train", help="split 名（默认 train）")
    parser.add_argument("--out", type=str, default="./emotion_photos", help="输出目录")
    parser.add_argument("--no-save", action="store_true", help="只读不保存")
    parser.add_argument("--endpoint", type=str, default=HF_MIRROR,
                        help=f"HF 端点（默认 {HF_MIRROR}）")
    parser.add_argument("--seed", type=int, default=42,
                        help="随机种子（默认 42；设 0 关闭随机，按顺序取）")
    return parser.parse_args()


def find_parquet_files(dataset_id: str, endpoint: str) -> list[str]:
    """列出数据集中所有 parquet 文件名。"""
    api = HfApi(endpoint=endpoint)
    files = api.list_repo_files(dataset_id, repo_type="dataset")
    return [f for f in files if f.endswith(".parquet")]


def stream_parquet_to_images(
    dataset_id: str,
    parquet_file: str,
    n: int,
    endpoint: str,
    random_sample: bool = True,
    seed: int = 42,
):
    """
    直接用 requests + 流式下载 parquet 到内存，再用 pyarrow 按 row group 迭代。
    绕过 huggingface_hub 的复杂下载逻辑（避免其在中国镜像下的兼容问题）。
    返回最多 n 条 (label, PIL.Image) 元组。

    random_sample=True 时，先在 parquet 全部行中生成 n 个随机索引，
    用 pf.take() 取出对应行，确保情绪分布多样化。
    """
    import pyarrow.parquet as pq
    import random

    # 构造镜像 URL（与 list_repo_files 用的 endpoint 一致）
    url = f"{endpoint}/datasets/{dataset_id}/resolve/main/{parquet_file}"
    print(f"    下载 {url[:80]}...")

    # 流式下载到内存
    resp = requests.get(url, stream=True, timeout=120, allow_redirects=True)
    resp.raise_for_status()
    total = int(resp.headers.get("Content-Length", 0))
    buf = io.BytesIO()
    downloaded = 0
    for chunk in resp.iter_content(chunk_size=1024 * 256):
        if chunk:
            buf.write(chunk)
            downloaded += len(chunk)
    print(f"    完成下载: {downloaded:,} bytes" + (f" / {total:,}" if total else ""))

    buf.seek(0)
    pf = pq.ParquetFile(buf)

    # 读取 schema
    schema = pf.schema_arrow
    img_col = None
    lbl_col = None
    for name in schema.names:
        col_type = schema.field(name).type
        if str(col_type).startswith("list") or "binary" in str(col_type).lower() or "struct" in str(col_type).lower():
            img_col = name
        elif str(col_type) in ("int8", "int16", "int32", "int64"):
            lbl_col = name
    print(f"    schema: img_col={img_col!r}, lbl_col={lbl_col!r}, total_rows={pf.metadata.num_rows if pf.metadata else 'N/A'}")

    yielded = 0
    # 选择迭代器
    if random_sample:
        total_rows = pf.metadata.num_rows if pf.metadata else 0
        if total_rows <= 0:
            random_sample = False  # 退化为顺序
        else:
            rng = random.Random(seed)
            indices = sorted(rng.sample(range(total_rows), min(n * 2, total_rows)))
            print(f"    随机采样 {len(indices)} 行（seed={seed}，原 {total_rows} 行）...")
            # 读全表到内存再 take（parquet 已全部在内存 BytesIO 中）
            table = pq.read_table(buf)
            table = table.take(indices).combine_chunks()
            col_names = [c for c in [img_col, lbl_col] if c]
            batches = [table.select(col_names).to_pandas()]  # 单批，直接迭代
    else:
        batches = None  # 走下面 iter_batches 路径

    if batches is None:
        # 顺序模式：按 batch 迭代
        for batch in pf.iter_batches(batch_size=64, columns=[c for c in [img_col, lbl_col] if c]):
            cols = {c: batch.column(c).to_pylist() for c in batch.schema.names}
            for i in range(len(batch)):
                if yielded >= n:
                    return
                label = cols.get(lbl_col, [None] * len(batch))[i] if lbl_col else None
                img_data = cols.get(img_col, [None] * len(batch))[i] if img_col else None
                if img_data is None:
                    continue
                try:
                    if isinstance(img_data, dict):
                        img_data = img_data.get("bytes") or img_data.get("path")
                    if isinstance(img_data, (bytes, bytearray, memoryview)):
                        img = Image.open(io.BytesIO(img_data))
                    else:
                        continue
                    yield label, img
                    yielded += 1
                except Exception as e:
                    print(f"    ! 第 {yielded} 条样本解析失败: {e}", file=sys.stderr)
                    continue
    else:
        # 随机模式：单批 pandas DataFrame 迭代
        df = batches[0]
        labels = df[lbl_col].tolist() if lbl_col else [None] * len(df)
        imgs_raw = df[img_col].tolist() if img_col else [None] * len(df)
        for label, img_data in zip(labels, imgs_raw):
            if yielded >= n:
                return
            if img_data is None:
                continue
            try:
                if isinstance(img_data, dict):
                    img_data = img_data.get("bytes") or img_data.get("path")
                if isinstance(img_data, (bytes, bytearray, memoryview)):
                    img = Image.open(io.BytesIO(img_data))
                else:
                    continue
                yield label, img
                yielded += 1
            except Exception as e:
                print(f"    ! 第 {yielded} 条样本解析失败: {e}", file=sys.stderr)
                continue


def main() -> int:
    args = parse_args()
    candidates = [args.dataset] if args.dataset else DEFAULT_DATASETS
    out_dir = Path(args.out)

    print(f"[1/4] 选数据集（endpoint={args.endpoint}）...")
    chosen = None
    for did in candidates:
        try:
            parquets = find_parquet_files(did, args.endpoint)
            if parquets:
                chosen = (did, parquets)
                print(f"  ✓ 使用: {did}  (parquet 文件: {len(parquets)} 个)")
                break
            else:
                print(f"  ✗ {did} 无 parquet 文件")
        except Exception as e:
            print(f"  ✗ {did} 列举失败: {str(e)[:120]}")

    if chosen is None:
        print("无可用数据集。", file=sys.stderr)
        return 1

    dataset_id, parquets = chosen

    if not args.no_save:
        out_dir.mkdir(parents=True, exist_ok=True)
        print(f"[2/4] 输出目录: {out_dir.resolve()}")
    else:
        print("[2/4] --no-save 模式，跳过保存")

    print(f"[3/4] 流式读取前 {args.num} 张 ...")
    saved = 0
    for parquet_file in parquets:
        if saved >= args.num:
            break
        try:
            for label, img in stream_parquet_to_images(
                dataset_id=dataset_id,
                parquet_file=parquet_file,
                n=args.num - saved,
                endpoint=args.endpoint,
                random_sample=(args.seed != 0),
                seed=args.seed,
            ):
                label_name = EMOTION_LABELS.get(label, str(label)) if label is not None else "unknown"
                if args.no_save:
                    if saved < 5:
                        print(f"  sample[{saved}]: label={label_name}, size={img.size}")
                else:
                    filename = f"{saved:04d}_{label_name}.jpg"
                    img.convert("RGB").save(out_dir / filename, quality=90)
                saved += 1
                if saved % 20 == 0:
                    print(f"  ... 已 {saved}/{args.num}")
                if saved >= args.num:
                    break
        except Exception as e:
            print(f"  ! {parquet_file} 处理失败: {e}", file=sys.stderr)
            continue

    print(f"[4/4] 完成！{'已保存' if not args.no_save else '已读取'} {saved}/{args.num} 张图片")
    if not args.no_save:
        print(f"  路径: {out_dir.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())