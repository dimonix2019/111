"""Кэш баров в памяти — CSV читается один раз на файл и режим Z."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from zsim import Bar, ZMode, load_bars_from_csv

_CACHE: dict[str, "CachedBars"] = {}


@dataclass
class CachedBars:
    path: str
    mtime_ns: int
    z_mode: str
    bars: list[Bar]


def get_bars(path: Path, *, recalc_z: bool = False, z_mode: ZMode = "rolling30") -> list[Bar]:
    path = path.resolve()
    key = f"{path}|{z_mode}"
    if not path.is_file():
        raise FileNotFoundError(path)
    mtime_ns = path.stat().st_mtime_ns
    hit = _CACHE.get(key)
    if hit and hit.mtime_ns == mtime_ns and hit.z_mode == z_mode:
        return hit.bars

    import pandas as pd

    df_head = pd.read_csv(path, nrows=0)
    need_recalc = recalc_z or z_mode == "rolling30" or "z_score" not in df_head.columns
    bars = load_bars_from_csv(str(path), recalc_z=need_recalc, z_mode=z_mode)
    _CACHE[key] = CachedBars(path=str(path), mtime_ns=mtime_ns, z_mode=z_mode, bars=bars)
    return bars


def invalidate(path: Path | None = None) -> None:
    from api.chart_cache import invalidate_chart_cache

    if path is None:
        _CACHE.clear()
        invalidate_chart_cache(None)
        return
    resolved = str(path.resolve())
    for k in list(_CACHE.keys()):
        if k.startswith(resolved):
            _CACHE.pop(k, None)
    invalidate_chart_cache(path)


def cache_info() -> dict:
    return {
        "entries": len(_CACHE),
        "paths": list(_CACHE.keys()),
        "bar_counts": {k: len(v.bars) for k, v in _CACHE.items()},
    }
