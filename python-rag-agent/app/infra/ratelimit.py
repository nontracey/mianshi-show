"""限流:滑动窗口每 IP 每分钟 N 次。

X-LLM-Key 存在时用用户自带 Key(不计公共额度,直接放行)。
dev 用内存 dict;prod 可换 redis(接口一致)。

架构位置:main.py 中间件在路由分发前调用 get_limiter().check(ip),
被限流的请求直接返回 429(附 reset_in 提示),不会触达业务逻辑,
保护的是后面昂贵的 LLM 调用。

为什么用滑动窗口而不是固定窗口:固定窗口在窗口交界处可能出现
2 倍突发(前一个窗口末尾 + 后一个窗口开头),滑动窗口以"过去
60 秒内的请求列表"计数,任何连续 60 秒都不会超限,更平滑;
代价是要存时间戳列表,但内存版在单实例场景完全够用。

X-LLM-Key 旁路设计:带自己 Key 的用户消耗的是自己的 token 额度,
不占公共资源,所以直接放行——限流本质是保护"公共额度"而非
限制用户本身。
"""

from __future__ import annotations

import time
from collections import defaultdict
from dataclasses import dataclass

from app.config import get_settings


@dataclass
class RateLimitResult:
    """限流判定结果:allowed 是否放行;remaining 剩余额度(-1 表示自带 Key 不限);
    reset_in 被限时还需等待的秒数(供 429 响应提示用户)。"""

    allowed: bool
    remaining: int
    reset_in: int  # 秒


class SlidingWindowLimiter:
    """每 IP 每分钟 N 次,滑动窗口。

    实现:为每个 IP 维护一个"过去 60 秒内的请求时间戳列表",
    每次 check 先清掉过期时间戳,再判断窗口内数量是否达到上限。
    """

    def __init__(self, per_minute: int | None = None) -> None:
        s = get_settings()
        # 允许测试注入自定义限额;默认取配置 rate_limit_per_minute
        self._limit = per_minute or s.rate_limit_per_minute
        self._window = 60  # 秒:固定窗口长度,与"每分钟 N 次"语义一致
        self._hits: dict[str, list[float]] = defaultdict(list)  # IP -> 时间戳列表

    def check(self, ip: str, has_own_key: bool = False) -> RateLimitResult:
        """有自带 Key 直接放行(用户自负额度)。

        滑动窗口判定步骤:
        1. 自带 Key 直接放行(不占公共额度,remaining=-1 表示不限);
        2. 用 time.monotonic() 取当前时间(不受系统时钟回调影响);
        3. 过滤掉窗口外(早于 cutoff)的旧时间戳并回写,顺带清理内存;
        4. 窗口内已达上限 → 拒绝,reset_in 为最早那条记录过期所需秒数;
        5. 未达上限 → 记录本次时间戳并放行,返回剩余额度。
        """
        if has_own_key:
            return RateLimitResult(allowed=True, remaining=-1, reset_in=0)

        now = time.monotonic()
        cutoff = now - self._window
        hits = [t for t in self._hits[ip] if t > cutoff]
        self._hits[ip] = hits  # 回写清理过期记录,防止列表无限增长

        if len(hits) >= self._limit:
            # hits[0] 是窗口内最早的记录,它过期后才有新额度
            reset_in = int(self._window - (now - hits[0]))
            return RateLimitResult(allowed=False, remaining=0, reset_in=reset_in)

        self._hits[ip].append(now)
        return RateLimitResult(allowed=True, remaining=self._limit - len(hits), reset_in=self._window)


# 进程级单例(懒加载):限流状态必须全局共享才有意义。
_limiter: SlidingWindowLimiter | None = None


def get_limiter() -> SlidingWindowLimiter:
    """获取限流器单例(首次调用时创建)。"""
    global _limiter
    if _limiter is None:
        _limiter = SlidingWindowLimiter()
    return _limiter


def reset_limiter() -> None:
    """重置单例(测试用:清掉限流状态,避免用例间互相影响)。"""
    global _limiter
    _limiter = None
