"""JSON-сериализация результатов симуляции."""

from __future__ import annotations

import math
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from api.chart_downsample import CHART_MAX_POINTS, chart_downsample_indices
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


def _series_records(df: pd.DataFrame, cols: list[str], time_col: str = "timestamp") -> list[dict]:
    """Unix time series для lightweight-charts (строго возрастающие уникальные time)."""
    if len(df) > CHART_MAX_POINTS:
        idx = chart_downsample_indices(len(df), max_pts=CHART_MAX_POINTS)
        df = df.iloc[idx]
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


def grid_search_row_to_dict(row: dict) -> dict:
    oos = row.get("oos") or {}
    verdict = oos.get("verdict")
    return _sanitize(
        {
            "entry": float(row["entry"]),
            "exit_z": float(row["exit_z"]),
            "slippage": float(row.get("slippage_spread_pts", row.get("slippage", 0))),
            "max_loss_spread": float(row.get("max_loss_spread_pts", row.get("max_loss_spread", 0))),
            "max_loss_rub": float(row.get("max_loss_rub", 0)),
            "min_spread": float(row.get("min_spread_pct", row.get("min_spread", 0))),
            "max_spread": float(row.get("max_spread_pct", row.get("max_spread", 0))),
            "entry_z_buffer": float(row.get("entry_z_buffer", 0)),
            "max_dd_halt_rub": float(row.get("max_drawdown_halt_rub", row.get("max_dd_halt_rub", 0))),
            "max_dd_halt_pct": float(row.get("max_drawdown_halt_pct", row.get("max_dd_halt_pct", 0))),
            "notional_rub": float(row.get("notional_rub", 100_000)),
            "leverage": float(row.get("leverage", 7)),
            "commission_pct_per_side": float(row.get("commission_pct_per_side", 0.04)),
            "compound_returns": bool(row.get("compound_returns", False)),
            "verdict_grade": str(row.get("verdict_grade", "")),
            "test_pnl_rub": float(row.get("test_pnl_rub", 0)),
            "train_pnl_rub": float(row.get("train_pnl_rub", 0)),
            "stats": row.get("stats") or {},
            "oos_verdict": verdict,
            "oos_test": oos.get("test"),
        }
    )


def latest_quote_from_csv(csv_path: str) -> dict:
    """Последняя строка M15 CSV: цены TATN/TATNP и timestamp."""
    path = Path(csv_path)
    if not path.is_file():
        return {}
    try:
        df = pd.read_csv(path, usecols=lambda c: c in ("timestamp", "tatn_close", "tatnp_close"))
    except (ValueError, OSError):
        return {}
    if df.empty:
        return {}
    row = df.iloc[-1]
    out: dict = {"timestamp": str(row.get("timestamp", ""))}
    if "tatn_close" in row.index and pd.notna(row["tatn_close"]):
        out["tatn_close"] = float(row["tatn_close"])
    if "tatnp_close" in row.index and pd.notna(row["tatnp_close"]):
        out["tatnp_close"] = float(row["tatnp_close"])
    return out
