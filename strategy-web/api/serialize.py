"""JSON-сериализация результатов симуляции."""

from __future__ import annotations

import math
from typing import Any

import numpy as np
import pandas as pd

from api.moex_time import moex_ts_to_unix
from zsim import SimResult


def _ts_unix_series(series: pd.Series) -> np.ndarray:
    """Векторная конвертация timestamp → unix sec (MOEX MSK → UTC instant)."""
    return moex_ts_to_unix(series)


def _sanitize(obj: Any) -> Any:
    if isinstance(obj, float):
        if math.isinf(obj):
            return None
        if math.isnan(obj):
            return None
        return obj
    if isinstance(obj, dict):
        return {k: _sanitize(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_sanitize(x) for x in obj]
    return obj


CHART_MAX_POINTS = 2500


def _series_records(df: pd.DataFrame, cols: list[str], time_col: str = "timestamp") -> list[dict]:
    """Unix time series для lightweight-charts (строго возрастающие уникальные time)."""
    if len(df) > CHART_MAX_POINTS:
        step = max(1, len(df) // CHART_MAX_POINTS)
        df = df.iloc[::step]
    times = _ts_unix_series(df[time_col])
    arrays = [df[c].to_numpy() for c in cols]
    by_time: dict[int, dict] = {}
    for i, t in enumerate(times):
        ts = int(t)
        row: dict = {"time": ts}
        for c, arr in zip(cols, arrays):
            row[c] = float(arr[i])
        by_time[ts] = row
    return [by_time[ts] for ts in sorted(by_time.keys())]


def pack_to_dict(pack: dict) -> dict:
    res: SimResult = pack["result"]
    stats = _sanitize(res.stats())
    eq = res.equity_frame()
    zf = res.zscore_frame()
    markers = res.trade_markers_frame()

    trades = [
        {
            "no": i,
            "direction": t.direction.value,
            "entry_time": t.entry_time,
            "exit_time": t.exit_time,
            "entry_spread": t.entry_spread,
            "exit_spread": t.exit_spread,
            "entry_z": t.entry_z,
            "exit_z": t.exit_z,
            "pnl_spread_pts": t.pnl_spread_pts,
            "pnl_rub": t.pnl_rub,
        }
        for i, t in enumerate(res.trades, start=1)
    ]

    equity = _series_records(eq, ["equity_rub", "drawdown_rub"])
    zscore = _series_records(zf, ["z_score", "spread_percent"])

    if not markers.empty:
        m_times = _ts_unix_series(markers["timestamp"])
        trade_markers = [
            {
                "trade_no": int(row.trade_no),
                "event": row.event,
                "time": int(m_times[i]),
                "z_score": float(row.z_score),
                "direction": row.direction,
                "entry_time": row.entry_time,
                "exit_time": row.exit_time,
                "pnl_rub": float(row.pnl_rub),
                "pnl_spread_pts": float(row.pnl_spread_pts),
                "marker_symbol": row.marker_symbol,
                "marker_color": row.marker_color,
            }
            for i, row in enumerate(markers.itertuples(index=False))
        ]
    else:
        trade_markers = []

    return {
        "label": pack["label"],
        "entry": pack["entry"],
        "exit_z": pack["exit_z"],
        "stats": stats,
        "trades": trades,
        "equity": equity,
        "zscore": zscore,
        "trade_markers": trade_markers,
        "unrealized_pnl_rub": res.unrealized_pnl_rub,
        "idle_gaps": _sanitize(res.idle_gaps_analysis()),
        "market_context": _sanitize(res.market_context(pack["entry"], pack["exit_z"])),
    }


def sweep_row_to_dict(row) -> dict:
    return _sanitize(row.to_dict())
