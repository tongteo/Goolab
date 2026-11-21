"""Context-local access to ColabTerm's Java terminal bridge."""

from contextvars import ContextVar, Token
from typing import Any, Optional

_bridge: ContextVar[Optional[Any]] = ContextVar("colabterm_terminal_bridge", default=None)


def set_bridge(bridge: Any) -> Token:
    """Set the bridge for one embedded CLI invocation and return its reset token."""
    return _bridge.set(bridge)


def reset_bridge(token: Token) -> None:
    """Restore the bridge context after an embedded CLI invocation."""
    _bridge.reset(token)


def get_bridge() -> Optional[Any]:
    """Return the bridge for the current CLI invocation, if embedded on Android."""
    return _bridge.get()
