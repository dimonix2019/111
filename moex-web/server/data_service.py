"""Загрузка 15м и симуляция — обёртка над strategy-web (порт Kotlin)."""

from __future__ import annotations

import sys
from datetime import timedelta
from pathlib import Path
from typing import Any

import pandas as pd

_REPO = Path(__file__).resolve().parents[2]
_STRATEGY_WEB = _REPO / "strategy-web"
if str(_STRATEGY_WEB) not in sys.path:
    sys.path.insert(0, str(_STRATEGY_WEB))

from m15_iss_loader import DEFAULT_M15_CSV, ensure_m15_data  # noqa: E402
from zsim import Bar, apply_z_scores_rolling, load_bars_from_csv, run_z_strategy_sim  # noqa: E402

from .constants import (  # noqa: E402
    CALENDAR_DAYS_VISIBLE,
    CHART_MAX_DISPLAY_BARS,
    MARKETS_UI_PERIODS,
    PORTFOLIO_M15_LOOKBACK_DAYS,
    markets_m15_lookback_days,
)

MSK = "Europe/Moscow"
_csv_path = DEFAULT_M15_CSV


def ensure_data(force: bool = False) -> Path:
    global _csv_path
    path = Path(_csv_path)
    ensure_m15_data(path, days=PORTFOLIO_M15_LOOKBACK_DAYS, force=force)
    return path


def _load_bars(lookback_days: int, *, z_mode: str = "rolling30") -> list[Bar]:
    path = ensure_data()
    bars = load_bars_from_csv(str(path), recalc_z=True, z_mode=z_mode)
    if not bars:
        return []
    if lookback_days <= 0:
        return bars
    last_ts = pd.to_datetime(bars[-1].timestamp).tz_localize(MSK)
    cutoff = last_ts - timedelta(days=lookback_days + 5)
    return [b for b in bars if pd.to_datetime(b.timestamp).tz_localize(MSK) >= cutoff]


def filter_bars_for_period(bars: list[Bar], period: str) -> list[Bar]:
    if len(bars) < 2:
        return []
    period = period if period in MARKETS_UI_PERIODS else "1D"
    last_ts = pd.to_datetime(bars[-1].timestamp).tz_localize(MSK)
    days = CALENDAR_DAYS_VISIBLE.get(period, 1)
    from_ts = last_ts - timedelta(days=days)
    out: list[Bar] = []
    for b in bars:
        ts = pd.to_datetime(b.timestamp).tz_localize(MSK)
        if ts >= from_ts:
            out.append(b)
    return out if len(out) >= 2 else bars


def downsample_bars(bars: list[Bar], max_bars: int = CHART_MAX_DISPLAY_BARS) -> list[Bar]:
    if len(bars) <= max_bars:
        return bars
    step = len(bars) / max_bars
    out: list[Bar] = []
    i = 0.0
    while len(out) < max_bars and int(i) < len(bars):
        out.append(bars[int(i)])
        i += step
    if out[-1] != bars[-1]:
        out.append(bars[-1])
    return out


def bars_to_candles(bars: list[Bar]) -> list[dict[str, Any]]:
    candles = []
    prev_z = bars[0].z_score
    for i, b in enumerate(bars):
        close = b.z_score
        open_z = bars[i - 1].z_score if i > 0 else close
        candles.append(
            {
                "time": b.timestamp,
                "open": open_z,
                "high": max(open_z, close),
                "low": min(open_z, close),
                "close": close,
                "spread": b.spread_percent,
            }
        )
        prev_z = close
    return candles


def markets_chart_payload(period: str) -> dict[str, Any]:
    period = period if period in MARKETS_UI_PERIODS else "1D"
    lookback = markets_m15_lookback_days(period)
    bars = _load_bars(lookback)
    filtered = filter_bars_for_period(bars, period)
    display = downsample_bars(filtered)
    return {
        "period": period,
        "lookbackDays": lookback,
        "barCount": len(filtered),
        "displayBarCount": len(display),
        "candles": bars_to_candles(display),
        "lastZ": display[-1].z_score if display else None,
        "lastSpread": display[-1].spread_percent if display else None,
    }


def strategy_test_payload(
    entry: float,
    exit_z: float,
    leverage: float,
    commission: float,
    compound: bool,
) -> dict[str, Any]:
    bars = _load_bars(PORTFOLIO_M15_LOOKBACK_DAYS)
    if len(bars) < 48:
        return {"error": "Недостаточно 15м данных", "barCount": len(bars)}
    result = run_z_strategy_sim(
        bars,
        entry=entry,
        exit_z=exit_z,
        notional_rub=DEFAULT_NOTIONAL_RUB,
        leverage=leverage,
        commission_pct_per_side=commission,
        compound_returns=compound,
    )
    trades = [
        {
            "direction": t.direction.value,
            "entryTime": t.entry_time,
            "exitTime": t.exit_time,
            "pnlRub": t.pnl_rub,
            "entryZ": t.entry_z,
            "exitZ": t.exit_z,
        }
        for t in result.trades[-50:]
    ][::-1]
    rm = result.risk_metrics()
    wins = sum(1 for t in result.trades if t.pnl_rub > 0)
    n = len(result.trades)
    return {
        "barCount": result.bar_count,
        "totalPnlRub": result.total_pnl_rub,
        "realizedPnlRub": result.realized_pnl_rub,
        "tradeCount": n,
        "winRate": (wins / n) if n else 0.0,
        "maxDrawdownRub": rm.get("worst_trade_rub"),
        "trades": trades,
    }


def app_info() -> dict[str, Any]:
    path = ensure_data()
    st = path.stat()
    return {
        "name": "MOEX MVP Web",
        "apkParity": "1.7.x",
        "dataFile": str(path),
        "dataSizeKb": round(st.st_size / 1024, 1),
    }
