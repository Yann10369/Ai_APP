"""Loguru 日志封装"""
import sys
from pathlib import Path

from loguru import logger


def setup_logger(log_dir: str = "./logs", level: str = "INFO") -> None:
    Path(log_dir).mkdir(parents=True, exist_ok=True)
    logger.remove()
    logger.add(
        sys.stderr,
        level=level,
        format=(
            "<green>{time:HH:mm:ss}</green> | "
            "<level>{level: <8}</level> | "
            "<cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> - "
            "<level>{message}</level>"
        ),
    )
    logger.add(
        f"{log_dir}/{{time:YYYY-MM-DD}}.log",
        rotation="100 MB",
        retention="30 days",
        encoding="utf-8",
        enqueue=True,
        level=level,
    )


def log_call(provider: str, model: str, tokens: int, cost: float, elapsed_ms: int, status: str) -> None:
    logger.info(
        f"[{provider}/{model}] tokens={tokens} cost=${cost:.4f} "
        f"elapsed={elapsed_ms}ms status={status}"
    )