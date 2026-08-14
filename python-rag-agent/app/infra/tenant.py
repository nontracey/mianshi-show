"""Trusted tenant context propagated across FastAPI tasks and LangGraph nodes."""
from contextvars import ContextVar, Token

_tenant: ContextVar[str] = ContextVar("tenant_id", default="default")

def current_tenant() -> str:
    return _tenant.get()

def set_tenant(value: str) -> Token:
    return _tenant.set(value)

def reset_tenant(token: Token) -> None:
    _tenant.reset(token)
