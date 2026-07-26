"""Compact Z/spread distributions for Trade desk hover tips (≈3y M15)."""

from __future__ import annotations

import math
import threading
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

LOOKBACK_DAYS = 1095
CSV_3Y = "m15_tatn_1095d.csv"
# Soft TTL; also invalidated when last bar trade_date advances.
_CACHE_TTL_SEC = 300.0
# Below this → try seed 1095d CSV; still below → degraded / empty.
_MIN_BARS_OK = 8_000

_LOCK = threading.Lock()
_CACHE: dict[str, Any] = {
    "key": None,
    "ts": 0.0,
    "payload": None,
}
_BUILD_LOCK = threading.Lock()


def compute_numeric_distribution(
    values: list[float],
    *,
    bin_target: int = 24,
) -> dict[str, Any] | None:
    """Match replay-sim.js ``computeNumericDistribution`` (without ``sorted``)."""
    n = len(values)
    if n == 0:
        return None
    vmin = min(values)
    vmax = max(values)
    mean = sum(values) / n
    if n > 1:
        var_acc = sum((v - mean) ** 2 for v in values)
        stdev = math.sqrt(var_acc / (n - 1))
    else:
        stdev = 0.0
    bin_count = min(28, max(10, round(math.sqrt(n) * 2.2) or bin_target))
    lo = vmin
    hi = vmax
    if hi <= lo:
        pad = max(abs(lo) * 0.05, 1e-6)
        lo -= pad
        hi += pad
    width = (hi - lo) / bin_count
    bins = [0] * bin_count
    for v in values:
        i = int(math.floor((v - lo) / width))
        if i < 0:
            i = 0
        if i >= bin_count:
            i = bin_count - 1
        bins[i] += 1
    return {
        "n": n,
        "min": vmin,
        "max": vmax,
        "mean": mean,
        "stdev": stdev,
        "bins": bins,
        "lo": lo,
        "hi": hi,
        "width": width,
        "binCount": bin_count,
    }


def _load_z_spread_series(start_date: str) -> tuple[list[float], list[float], str | None]:
    """Lightweight SQL: only z + spread for lookback window."""
    from replay.replay_db import _connect, db_retry

    def _op() -> tuple[list[float], list[float], str | None]:
        with _connect() as conn:
            cur = conn.execute(
                """
                SELECT z_score, spread_percent, trade_date
                FROM m15_bars
                WHERE trade_date >= ?
                ORDER BY timestamp_ms
                """,
                (start_date,),
            )
            zs: list[float] = []
            sps: list[float] = []
            last: str | None = None
            for row in cur:
                z = row[0]
                sp = row[1]
                last = str(row[2]) if row[2] is not None else last
                if z is not None:
                    try:
                        zf = float(z)
                    except (TypeError, ValueError):
                        zf = float("nan")
                    if math.isfinite(zf):
                        zs.append(zf)
                if sp is not None:
                    try:
                        spf = float(sp)
                    except (TypeError, ValueError):
                        spf = float("nan")
                    if math.isfinite(spf):
                        sps.append(spf)
            return zs, sps, last

    return db_retry(_op)


def _ensure_3y_seeded() -> None:
    """If SQLite is short, seed from Testing 1095d CSV (no MOEX block)."""
    from replay.replay_db import db_bar_count, ensure_replay_bars

    if db_bar_count() >= _MIN_BARS_OK:
        return
    data_dir = Path(__file__).resolve().parent.parent / "data"
    csv_path = data_dir / CSV_3Y
    if not csv_path.is_file():
        return
    ensure_replay_bars(csv_path, CSV_3Y, online=False, start_date=None)


def invalidate_desk_metric_dists() -> None:
    with _LOCK:
        _CACHE["key"] = None
        _CACHE["payload"] = None
        _CACHE["ts"] = 0.0


def _build_payload() -> dict[str, Any]:
    from replay.replay_db import _db_last_trade_date

    _ensure_3y_seeded()
    start = (datetime.now() - timedelta(days=LOOKBACK_DAYS + 5)).strftime("%Y-%m-%d")
    zs, sps, series_last = _load_z_spread_series(start)
    db_last = _db_last_trade_date()
    last_bar = series_last or db_last

    z_dist = compute_numeric_distribution(zs)
    sp_dist = compute_numeric_distribution(sps)
    n = max(z_dist["n"] if z_dist else 0, sp_dist["n"] if sp_dist else 0)
    ok = bool(z_dist and sp_dist and n >= _MIN_BARS_OK)
    return {
        "ok": ok,
        "degraded": not ok,
        "source": "3y" if ok else ("partial" if n > 0 else "none"),
        "lookback_days": LOOKBACK_DAYS,
        "last_bar": last_bar,
        "n": n,
        "z": z_dist,
        "spread": sp_dist,
    }


def get_desk_metric_dists(
    *,
    force: bool = False,
    allow_build: bool = True,
) -> dict[str, Any]:
    """
    Compact Z/spread histogram stats for Trade desk tips.

    Cached in memory; rebuild when last bar advances or soft TTL expires.
    Never raises — returns ``ok: false`` on failure.
    Lite desk passes ``allow_build=False`` so a cold 3y rebuild cannot
    block first paint; a background thread fills the cache.
    """
    degraded = {
        "ok": False,
        "degraded": True,
        "source": "warming",
        "lookback_days": LOOKBACK_DAYS,
        "last_bar": None,
        "n": 0,
        "z": None,
        "spread": None,
    }
    try:
        from replay.replay_db import _db_last_trade_date

        last_bar = _db_last_trade_date() or ""
        now = time.time()
        with _LOCK:
            cached = _CACHE["payload"]
            if (
                not force
                and cached is not None
                and _CACHE["key"] == last_bar
                and (now - float(_CACHE["ts"])) < _CACHE_TTL_SEC
            ):
                return cached

        if not allow_build and not force:
            # Kick one background build; return stale or warming stub.
            stale = cached
            if stale is not None:
                return stale
            if _BUILD_LOCK.acquire(blocking=False):
                def _bg() -> None:
                    try:
                        get_desk_metric_dists(force=True, allow_build=True)
                    finally:
                        try:
                            _BUILD_LOCK.release()
                        except RuntimeError:
                            pass

                threading.Thread(
                    target=_bg, name="metric-dist-warm", daemon=True
                ).start()
            degraded["last_bar"] = last_bar or None
            return degraded

        t0 = time.perf_counter()
        payload = _build_payload()
        payload["build_ms"] = round((time.perf_counter() - t0) * 1000.0, 1)
        # Key on DB tip so a new M15 bar invalidates even within TTL.
        key = str(payload.get("last_bar") or last_bar)
        with _LOCK:
            _CACHE["key"] = key
            _CACHE["ts"] = time.time()
            _CACHE["payload"] = payload
        return payload
    except Exception as exc:
        return {
            "ok": False,
            "degraded": True,
            "source": "error",
            "lookback_days": LOOKBACK_DAYS,
            "last_bar": None,
            "n": 0,
            "z": None,
            "spread": None,
            "error": str(exc),
        }
