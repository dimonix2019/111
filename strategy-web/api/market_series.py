"""Полный ряд 15м для LLM-контекста (без прореживания графиков)."""

from __future__ import annotations

from pathlib import Path

import pandas as pd

from api.bars_cache import get_bars
from api.serialize import _ts_unix_series
from zsim import Bar

LLM_MAX_BARS_DEFAULT = 16_000
LLM_MAX_BARS_CAP = 32_000


def zscore_series_from_bars(bars: list[Bar], tail_rows: int = LLM_MAX_BARS_DEFAULT) -> list[dict]:
    if not bars:
        return []
    tail_rows = max(1, min(int(tail_rows), LLM_MAX_BARS_CAP, len(bars)))
    slice_bars = bars[-tail_rows:] if len(bars) > tail_rows else bars
    times = _ts_unix_series(pd.Series([b.timestamp for b in slice_bars]))
    ohlc_keys = (
        "tatn_open",
        "tatn_high",
        "tatn_low",
        "tatnp_open",
        "tatnp_high",
        "tatnp_low",
        "spread_open",
        "spread_high",
        "spread_low",
        "spread_close",
        "tatn_volume",
    )
    out: list[dict] = []
    for i, b in enumerate(slice_bars):
        row: dict = {
            "time": int(times[i]),
            "z_score": float(b.z_score),
            "spread_percent": float(b.spread_percent),
        }
        if b.tatn_close is not None:
            row["tatn_close"] = float(b.tatn_close)
        if b.tatnp_close is not None:
            row["tatnp_close"] = float(b.tatnp_close)
        for key in ohlc_keys:
            val = getattr(b, key, None)
            if val is not None:
                row[key] = float(val)
        out.append(row)
    return out


def market_series_for_llm(csv_path: Path, max_bars: int = LLM_MAX_BARS_DEFAULT) -> dict:
    bars = get_bars(csv_path)
    if not bars:
        return {"path": str(csv_path), "bar_count_total": 0, "bars": []}
    max_bars = max(1, min(int(max_bars), LLM_MAX_BARS_CAP))
    series = zscore_series_from_bars(bars, tail_rows=max_bars)
    return {
        "path": str(csv_path),
        "bar_count_total": len(bars),
        "bar_count_returned": len(series),
        "truncated": len(bars) > len(series),
        "bars": series,
    }
