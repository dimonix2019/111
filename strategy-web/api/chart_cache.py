"""Кэш ряда Z/spread для графиков — не зависит от entry/exit."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

from api.moex_time import moex_ts_to_unix
from zsim import Bar

_CHART_Z: dict[str, list[dict]] = {}


def zscore_chart_records(path: Path, bars: list[Bar]) -> list[dict]:
    """Unix time series z_score + spread_percent (до CHART_MAX_POINTS)."""
    if not bars:
        return []
    path = path.resolve()
    key = f"{path}:{path.stat().st_mtime_ns}:{len(bars)}"
    hit = _CHART_Z.get(key)
    if hit is not None:
        return hit

    ts = [b.timestamp for b in bars]
    times = moex_ts_to_unix(pd.Series(ts))
    zs = np.asarray([b.z_score for b in bars], dtype=float)
    sp = np.asarray([b.spread_percent for b in bars], dtype=float)

    max_pts = 2500
    if len(bars) > max_pts:
        step = max(1, len(bars) // max_pts)
        idx = np.arange(0, len(bars), step)
        times = times[idx]
        zs = zs[idx]
        sp = sp[idx]

    by_time: dict[int, dict] = {}
    for i, t in enumerate(times):
        ts_i = int(t)
        by_time[ts_i] = {"time": ts_i, "z_score": float(zs[i]), "spread_percent": float(sp[i])}
    out = [by_time[k] for k in sorted(by_time.keys())]
    _CHART_Z[key] = out
    if len(_CHART_Z) > 8:
        _CHART_Z.pop(next(iter(_CHART_Z)))
    return out


def invalidate_chart_cache(path: Path | None = None) -> None:
    if path is None:
        _CHART_Z.clear()
        return
    prefix = f"{path.resolve()}:"
    for k in list(_CHART_Z.keys()):
        if k.startswith(prefix):
            _CHART_Z.pop(k, None)
