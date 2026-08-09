"""指数退避重试装饰器"""
import asyncio
import random
from functools import wraps

from ..core.exceptions import ModelTimeoutError, RateLimitError


def retry_with_backoff(max_retries: int = 3, base_delay: float = 1.0):
    def decorator(func):
        @wraps(func)
        async def wrapper(*args, **kwargs):
            last_exc = None
            for attempt in range(max_retries):
                try:
                    return await func(*args, **kwargs)
                except (RateLimitError, ModelTimeoutError) as e:
                    last_exc = e
                    if attempt == max_retries - 1:
                        break
                    delay = base_delay * (2 ** attempt) + random.uniform(0, 0.5)
                    await asyncio.sleep(delay)
            raise last_exc
        return wrapper
    return decorator