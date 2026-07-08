"""限流:滑动窗口每 IP 每分钟 N 次。

X-LLM-Key 存在时用用户自带 Key(不计公共额度,直接放行)。
dev 用内存 dict;prod 可换 redis(接口一致)。
"""

from __future__ import annotations

import time
from collections import defaultdict
from dataclasses import dataclass

from app.config import get_settings


@dataclass
class RateLimitResult:
    allowed: bool
    remaining: int
    reset_in: int  # 秒


class SlidingWindowLimiter:
    """每 IP 每分钟 N 次,滑动窗口。"""

    def __init__(self, per_minute: int | None = None) -> None:
        s = get_settings()
        self._limit = per_minute or s.rate_limit_per_minute
        self._window = 60  # 秒
        self._hits: dict[str, list[float]] = defaultdict(list)

    def check(self, ip: str, has_own_key: bool = False) -> RateLimitResult:
        """有自带 Key 直接放行(用户自负额度)。"""
        if has_own_key:
            return RateLimitResult(allowed=True, remaining=-1, reset_in=0)

        now = time.monotonic()
        cutoff = now - self._window
        hits = [t for t in self._hits[ip] if t > cutoff]
        self._hits[ip] = hits

        if len(hits) >= self._limit:
            reset_in = int(self._window - (now - hits[0]))
            return RateLimitResult(allowed=False, remaining=0, reset_in=reset_in)

        self._hits[ip].append(now)
        return RateLimitResult(allowed=True, remaining=self._limit - len(hits), reset_in=self._window)


_limiter: SlidingWindowLimiter | None = None


def get_limiter() -> SlidingWindowLimiter:
    global _limiter
    if _limiter is None:
        _limiter = SlidingWindowLimiter()
    return _limiter


def reset_limiter() -> None:
    global _limiter
    _limiter = None
