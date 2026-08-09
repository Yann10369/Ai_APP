"""smoke_real_model.py — 真实模型端到端验证脚本。"""
from __future__ import annotations

import argparse
import asyncio
import os
import sys
import time
from pathlib import Path

# 允许不安装包直接跑
_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from model.inference.config import ClassifierConfig
from model.inference.classifier import PhotoClassifier


def check_classify_result(r, cfg: ClassifierConfig, errors: list[str]) -> None:
    """校验 analyze 返回值是否在候选集 + confidence 范围内。"""
    if not r.description or not r.description.strip():
        errors.append("description 为空")
    if r.scene_category_name not in cfg.scene_labels:
        errors.append(f"scene 不在候选集: {r.scene_category_name!r}")
    if r.emotion_category_name not in cfg.emotion_labels:
        errors.append(f"emotion 不在候选集: {r.emotion_category_name!r}")
    if not (0.1 <= r.scene_confidence <= 1.0):
        errors.append(f"scene_confidence 越界: {r.scene_confidence}")
    if not (0.1 <= r.emotion_confidence <= 1.0):
        errors.append(f"emotion_confidence 越界: {r.emotion_confidence}")
    exclude = {r.scene_category_name, r.emotion_category_name}
    for name, conf in r.tag_category_names:
        if name not in cfg.tag_labels:
            errors.append(f"tag 不在候选集: {name!r}")
        if name in exclude:
            errors.append(f"tag 与 scene/emotion 重复: {name!r}")
        if not (0.1 <= conf <= 1.0):
            errors.append(f"tag confidence 越界: {name}={conf}")


def check_query_result(pairs, all_labels, errors: list[str]) -> None:
    """校验 extract_query_tags 返回 (name, conf) 是否合法。"""
    if not pairs:
        errors.append("extract_query_tags 返回空")
        return
    label_set = set(all_labels)
    for name, conf in pairs:
        if name not in label_set:
            errors.append(f"query tag 不在候选集: {name!r}")
        if not (0.1 <= conf <= 1.0):
            errors.append(f"query tag confidence 越界: {name}={conf}")


def pp_classify(r) -> None:
    """格式化输出 analyze 的结果。"""
    print(f"  description : {r.description}")
    print(f"  scene       : {r.scene_category_name} (conf={r.scene_confidence})")
    print(f"  emotion     : {r.emotion_category_name} (conf={r.emotion_confidence})")
    print(f"  tags        : {', '.join(f'{n}({c})' for n, c in r.tag_category_names)}")


def pp_query(pairs) -> None:
    """格式化输出 extract_query_tags 的结果。"""
    for name, conf in pairs:
        print(f"    {name}  conf={conf}")


async def main() -> int:
    """运行 analyze 与 extract_query_tags 真实调用并校验。"""
    parser = argparse.ArgumentParser(description="真实模型 smoke 验证")
    parser.add_argument("--image", default=str(_ROOT.parent / "backend" / "data" / "origin" / "1.jpg"),
                        help="测试图片路径（默认 backend/data/origin/1.jpg）")
    args = parser.parse_args()

    if not os.getenv("DASHSCOPE_API_KEY", "").strip():
        print("ERROR: DASHSCOPE_API_KEY 未设置", file=sys.stderr)
        return 2

    cfg = ClassifierConfig.from_env()
    clf = PhotoClassifier(cfg)

    print("=" * 70)
    print(f"  vision_model  = {cfg.aliyun_model}")
    print(f"  text_model    = {cfg.aliyun_text_model}")
    print(f"  base_url      = {clf._client.base_url}")
    print("=" * 70)
    print()

    all_labels = cfg.scene_labels + cfg.emotion_labels + cfg.tag_labels
    all_errors: list[str] = []
    rc = 0

    print("=" * 70)
    print(f"[1] analyze(image): {args.image}")
    print("=" * 70)
    img = Path(args.image)
    if not img.is_file():
        print(f"ERROR: 图片不存在 {img}", file=sys.stderr)
        return 2
    t0 = time.perf_counter()
    try:
        r = await clf.analyze(img, photo_id=1)
    except Exception as e:
        print(f"FAILED: {e}", file=sys.stderr)
        rc = 1
    else:
        dt = time.perf_counter() - t0
        pp_classify(r)
        print(f"  ⏱  {dt:.2f}s")
        errs: list[str] = []
        check_classify_result(r, cfg, errs)
        if errs:
            rc = 1
            for e in errs:
                all_errors.append(f"[analyze] {e}")
                print(f"  ❌ {e}")
        else:
            print("  ✅ 全部通过")

    print()
    queries = [
        "海边朋友一起大笑的照片",
        "夜景城市很浪漫",
        "一个人安静地喝咖啡",
        "雪山下的小狗",
        "昨天生日聚会上和家人吃蛋糕",
    ]
    for q in queries:
        print("=" * 70)
        print(f"[2] extract_query_tags(query): {q!r}")
        print("=" * 70)
        t0 = time.perf_counter()
        try:
            pairs = await clf.extract_query_tags(q)
        except Exception as e:
            print(f"FAILED: {e}", file=sys.stderr)
            rc = 1
            continue
        dt = time.perf_counter() - t0
        pp_query(pairs)
        print(f"  ⏱  {dt:.2f}s")
        errs = []
        check_query_result(pairs, all_labels, errs)
        if errs:
            rc = 1
            for e in errs:
                all_errors.append(f"[query={q!r}] {e}")
                print(f"  ❌ {e}")
        else:
            print("  ✅ 全部通过")

    print()
    print("=" * 70)
    if rc == 0:
        print("🎉 SMOKE PASS: 真实模型两个功能均正常")
        return 0
    print("❌ SMOKE FAIL:")
    for e in all_errors:
        print(f"  - {e}")
    return 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
