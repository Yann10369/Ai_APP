"""CLI 入口: 单张图片分类 / 批量目录扫描。"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from pathlib import Path

from model.inference.classifier import PhotoClassifier
from model.inference.config import ClassifierConfig
from model.inference.schema import AIAnalysisResult


def _setup_logging(verbose: bool) -> None:
    """配置根 logger 输出格式与级别。"""
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )


async def _run_single(image: Path) -> AIAnalysisResult:
    """对单张图片运行分类并返回结果。"""
    cfg = ClassifierConfig.from_env()
    clf = PhotoClassifier(cfg)
    return await clf.analyze(image)


async def _run_batch(d: Path, out: Path | None) -> int:
    """批量扫描目录下图片分类并可选写 JSON。"""
    cfg = ClassifierConfig.from_env()
    clf = PhotoClassifier(cfg)
    results: list[dict] = []
    paths = sorted(
        p for p in d.rglob("*")
        if p.is_file() and p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".heic"}
    )
    if not paths:
        print(f"目录 {d} 下未找到图片", file=sys.stderr)
        return 1
    print(f"将分析 {len(paths)} 张图片 ...")
    for i, p in enumerate(paths, 1):
        try:
            r = await clf.analyze(p)
            results.append({
                "photo": str(p),
                "description": r.description,
                "scene": {"name": r.scene_category_name, "confidence": r.scene_confidence},
                "emotion": {"name": r.emotion_category_name, "confidence": r.emotion_confidence},
                "tags": [{"name": n, "confidence": c} for n, c in r.tag_category_names],
            })
            print(f"[{i}/{len(paths)}] {p.name}: scene={r.scene_category_name}, emotion={r.emotion_category_name}, tags={len(r.tag_category_names)}")
        except Exception as e:
            print(f"[{i}/{len(paths)}] {p.name}: FAILED {e}", file=sys.stderr)
            results.append({"photo": str(p), "error": str(e)})
    if out is not None:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"结果已写入 {out.resolve()}")
    return 0


def main(argv: list[str] | None = None) -> int:
    """CLI 主入口: 解析参数并分派到 single/batch。"""
    parser = argparse.ArgumentParser(description="AI 智能相册 - 模型端 CLI")
    parser.add_argument("--image", help="单张图片路径")
    parser.add_argument("--dir", help="批量分析目录")
    parser.add_argument("--out", help="批量结果 JSON 输出路径")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)

    _setup_logging(args.verbose)

    if not (args.image or args.dir):
        parser.error("必须提供 --image 或 --dir 之一")

    if args.image:
        result = asyncio.run(_run_single(Path(args.image)))
        print(json.dumps({
            "photo": args.image,
            "description": result.description,
            "scene": {"name": result.scene_category_name, "confidence": result.scene_confidence},
            "emotion": {"name": result.emotion_category_name, "confidence": result.emotion_confidence},
            "tags": [{"name": n, "confidence": c} for n, c in result.tag_category_names],
        }, ensure_ascii=False, indent=2))
        return 0

    return asyncio.run(_run_batch(Path(args.dir), Path(args.out) if args.out else None))


if __name__ == "__main__":
    sys.exit(main())
