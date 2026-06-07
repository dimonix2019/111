"""Кэш ряда Z/spread для графиков — не зависит от entry/exit."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

from api.chart_downsample import CHART_MAX_POINTS, chart_downsample_indices
from api.moex_time import moex_ts_to_unix
from zsim import Bar

_CHART_Z: dict[str, list[dict]] = {}
CHART_CACHE_VERSION = 2


def zscore_chart_records(path: Path, bars: list[Bar]) -> list[dict]:
    """Unix time series z_score + spread_percent (до CHART_MAX_POINTS)."""
    if not bars:
        return []
    path = path.resolve()
    key = f"v{CHART_CACHE_VERSION}:{path}:{path.stat().st_mtime_ns}:{len(bars)}"
    hit = _CHART_Z.get(key)
    if hit is not None:
        return hit

    ts = [b.timestamp for b in bars]
    times = moex_ts_to_unix(pd.Series(ts))
    zs = np.asarray([b.z_score for b in bars], dtype=float)
    sp = np.asarray([b.spread_percent for b in bars], dtype=float)

    ohlc_keys = (
        "tatn_open",
        "tatn_high",
        "tatn_low",
        "spread_open",
        "spread_high",
        "spread_low",
        "spread_close",
        "tatn_volume",
    )

    idx = chart_downsample_indices(len(bars), max_pts=CHART_MAX_POINTS)
    if len(idx) < len(bars):
        times = times[idx]
        zs = zs[idx]
        sp = sp[idx]
        slice_bars = [bars[i] for i in idx]
    else:
        slice_bars = bars

    by_time: dict[int, dict] = {}
    for i, t in enumerate(times):
        ts_i = int(t)
        row: dict = {"time": ts_i, "z_score": float(zs[i]), "spread_percent": float(sp[i])}
        b = slice_bars[i]
        if b.tatn_close is not None:
            row["tatn_close"] = float(b.tatn_close)
        if b.tatnp_close is not None:
            row["tatnp_close"] = float(b.tatnp_close)
        for key in ohlc_keys:
            val = getattr(b, key, None)
            if val is not None and np.isfinite(val):
                row[key] = float(val)
        by_time[ts_i] = row
    out = [by_time[k] for k in sorted(by_time.keys())]
    _CHART_Z[key] = out
    if len(_CHART_Z) > 8:
        _CHART_Z.pop(next(iter(_CHART_Z)))
    return out


def invalidate_chart_cache(path: Path | None = None) -> None:
    if path is None:
        _CHART_Z.clear()
        return
    prefix = f"v{CHART_CACHE_VERSION}:{path.resolve()}:"
    for k in list(_CHART_Z.keys()):
        if k.startswith(prefix):
            _CHART_Z.pop(k, None)
