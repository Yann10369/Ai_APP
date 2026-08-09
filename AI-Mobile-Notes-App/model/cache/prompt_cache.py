"""基于内容哈希的内存 LRU 缓存（可替换为 Redis）"""
import asyncio
import time
from typing import Optional


class PromptCache:
    def __init__(self, max_size: int = 500, default_ttl: int = 3600):
        self.store: dict = {}
        self.max_size = max_size
        self.default_ttl = default_ttl
        self.lock = asyncio.Lock()

    async def get(self, key: str) -> Optional[str]:
        async with self.lock:
            item = self.store.get(key)
            if not item:
                return None
            value, expire_at = item
            if expire_at < time.time():
                self.store.pop(key, None)
                return None
            return value

    async def set(self, key: str, value: str, ttl: Optional[int] = None) -> None:
        async with self.lock:
            if len(self.store) >= self.max_size:
                # 清理最早过期的一批
                items = sorted(self.store.items(), key=lambda x: x[1][1])[: max(1, self.max_size // 10)]
                for k, _ in items:
                    self.store.pop(k, None)
            self.store[key] = (value, time.time() + (ttl or self.default_ttl))

    def size(self) -> int:
        return len(self.store)