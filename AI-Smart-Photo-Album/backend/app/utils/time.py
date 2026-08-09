"""时间工具：UTC naive 与 ISO8601 字符串。"""
from datetime import datetime, timezone


def to_iso(dt: datetime | None) -> str | None:
    """datetime → 'YYYY-MM-DDTHH:MM:SSZ'；无时区视为 UTC。"""
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def utcnow_naive() -> datetime:
    """当前 UTC 时间，tzinfo=None（兼容 MySQL DATETIME 列）。"""
    return datetime.now(timezone.utc).replace(tzinfo=None)