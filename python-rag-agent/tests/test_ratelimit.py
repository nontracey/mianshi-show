"""限流单测。"""

from __future__ import annotations

from app.infra.ratelimit import SlidingWindowLimiter


def test_limiter_allows_under_limit():
    lim = SlidingWindowLimiter(per_minute=3)
    for _ in range(3):
        r = lim.check("1.2.3.4")
        assert r.allowed
    # 第 4 次应被拒
    r = lim.check("1.2.3.4")
    assert not r.allowed
    assert r.reset_in > 0


def test_limiter_own_key_bypasses():
    lim = SlidingWindowLimiter(per_minute=2)
    # 自带 Key 不计公共额度
    for _ in range(5):
        r = lim.check("1.2.3.4", has_own_key=True)
        assert r.allowed


def test_limiter_isolated_per_ip():
    lim = SlidingWindowLimiter(per_minute=2)
    lim.check("1.1.1.1")
    lim.check("1.1.1.1")
    # 不同 IP 不受影响
    r = lim.check("2.2.2.2")
    assert r.allowed
