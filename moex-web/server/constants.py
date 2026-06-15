"""Константы в паритете с MoexConstants.kt / MoexMarketsPeriods.kt."""

from __future__ import annotations

Z_SCORE_ROLLING_LOOKBACK_DAYS = 30
Z_SCORE_ROLLING_MIN_BARS = 48
PORTFOLIO_M15_LOOKBACK_DAYS = 255
PORTFOLIO_TAB_M15_LOOKBACK_DAYS = 90
CHART_MAX_DISPLAY_BARS = 1200
DEFAULT_NOTIONAL_RUB = 100_000.0
DEFAULT_ENTRY = 0.8
DEFAULT_EXIT = 0.7
DEFAULT_LEVERAGE = 7.0
DEFAULT_COMMISSION = 0.04

MARKETS_UI_PERIODS = ("1D", "1W", "1M", "3M")

CALENDAR_DAYS_VISIBLE = {
    "1D": 1,
    "1W": 7,
    "1M": 30,
    "3M": 90,
}


def markets_m15_lookback_days(period: str) -> int:
    visible = CALENDAR_DAYS_VISIBLE.get(period, 90)
    return visible + Z_SCORE_ROLLING_LOOKBACK_DAYS + 7
