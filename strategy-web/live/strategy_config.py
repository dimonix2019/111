"""Public strategy thresholds for desk/replay UI (constants + spread_regime)."""

from __future__ import annotations

from typing import Any

from live.constants import (
    SPREAD_ENTER_NARROW,
    SPREAD_ENTER_WIDE,
    SPREAD_EXIT_NARROW,
    SPREAD_EXIT_WIDE,
)
from live.spread_regime import SPREAD_WIDTH_NARROW_MAX, SPREAD_WIDTH_WIDE_MIN

STRATEGY_CONFIG_VERSION = "20260906"
# Desk UI default when settings omit take_profit_pct (spread_situation parity).
DESK_DEFAULT_TP_PCT = 2.0


def get_strategy_config() -> dict[str, Any]:
    """Single JSON payload for GET /api/live/strategy-config."""
    return {
        "spread": {
            "enter_wide": SPREAD_ENTER_WIDE,
            "exit_wide": SPREAD_EXIT_WIDE,
            "enter_narrow": SPREAD_ENTER_NARROW,
            "exit_narrow": SPREAD_EXIT_NARROW,
            "regime_narrow_max": SPREAD_WIDTH_NARROW_MAX,
            "regime_wide_min": SPREAD_WIDTH_WIDE_MIN,
        },
        "tp_pct": DESK_DEFAULT_TP_PCT,
        "version": STRATEGY_CONFIG_VERSION,
    }
