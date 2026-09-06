"""PnL source policy — Prod broker vs Test sim (docs/pnl-sources.md)."""

from __future__ import annotations

from typing import Any

BROKER_DISPLAY_SOURCE = "broker_expected_yield"
MARK_BROKER_SOURCE = "tinkoff_expected_yield"


def response_pnl_source(
    open_trade: dict[str, Any] | None,
    *,
    broker: dict[str, Any] | None = None,
) -> str | None:
    """Top-level API field when Prod display uses GetPortfolio.expectedYield."""
    if not open_trade:
        return None
    mark = open_trade.get("mark") or {}
    if mark.get("pnl_source") == MARK_BROKER_SOURCE:
        return BROKER_DISPLAY_SOURCE
    if broker and not broker.get("error"):
        if broker.get("expected_yield_rub") is not None or broker.get("leg_yield"):
            return BROKER_DISPLAY_SOURCE
    return None
