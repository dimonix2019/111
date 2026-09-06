"""Часовая волатильность спреда — как MoexSpreadHourlyVolatility на телефоне.

σ |Δspread%| между соседними 15м барами, группировка по часу МСК (8–23).
Глубина по умолчанию — 90 календарных дней.

«Повышенная» по дням (не перцентиль того же часа): шаг часа в день =
среднее |ΔS| 15м внутри часа; повышенная, если шаг ≥ 1,5 × медианы этого
часа за окно. Тихий час редко, час с толстыми хвостами — чаще.
"""

from __future__ import annotations

import math
import statistics
import threading
import time
from collections import defaultdict
from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

MSK = ZoneInfo("Europe/Moscow")
LOOKBACK_DAYS = 90
LOOKBACK_MIN_DAYS = 7
LOOKBACK_MAX_DAYS = 365
HOUR_START_MSK = 8
HOUR_END_MSK = 23
MIN_DELTA_SAMPLES = 2
ELEVATED_MULT = 1.5
MIN_DAYS_FOR_ELEVATED = 10
_M15_MS = 15 * 60 * 1000

_LOCK = threading.Lock()
_CACHE: dict[str, dict[str, Any]] = {}
_CACHE_TTL_SEC = 300.0
_CACHE_MAX_KEYS = 8


def trading_hours() -> range:
    return range(HOUR_START_MSK, HOUR_END_MSK + 1)


def is_consecutive_m15(prev_ms: int, cur_ms: int) -> bool:
    if prev_ms <= 0 or cur_ms <= 0:
        return False
    return abs(int(cur_ms) - int(prev_ms)) == _M15_MS


def _hour_msk(*, timestamp_ms: int, trade_date: str | None) -> int:
    td = str(trade_date or "").replace("T", " ").strip()
    if len(td) >= 13 and td[11:13].isdigit():
        return int(td[11:13])
    dt = datetime.fromtimestamp(int(timestamp_ms) / 1000.0, tz=MSK)
    return int(dt.hour)


def _population_std(values: list[float]) -> float:
    n = len(values)
    if n < 2:
        return 0.0
    mean = sum(values) / n
    var = sum((x - mean) ** 2 for x in values) / n
    return math.sqrt(var)


def elevated_day_counts(daily_steps: list[float]) -> dict[str, Any]:
    """По дням: повышенная = шаг ≥ ELEVATED_MULT × медианы этого часа."""
    n_days = len(daily_steps)
    if n_days == 0:
        return {
            "n_days": 0,
            "n_elevated": 0,
            "elevated_frac": None,
            "median_step": None,
        }
    med = float(statistics.median(daily_steps))
    thr = ELEVATED_MULT * med
    n_elevated = sum(1 for step in daily_steps if step >= thr)
    frac = (n_elevated / n_days) if n_days >= MIN_DAYS_FOR_ELEVATED else None
    return {
        "n_days": n_days,
        "n_elevated": n_elevated,
        "elevated_frac": None if frac is None else round(frac, 4),
        "median_step": round(med, 6),
    }


def build_spread_hourly_vol_report(
    points: list[dict[str, Any]],
    *,
    min_delta_samples: int = MIN_DELTA_SAMPLES,
) -> dict[str, Any] | None:
    """points: {timestamp_ms, trade_date, spread} — по возрастанию времени."""
    if len(points) < 3:
        return None
    deltas: list[list[float]] = [[] for _ in range(24)]
    spreads: list[list[float]] = [[] for _ in range(24)]
    day_hour_deltas: list[dict[str, list[float]]] = [defaultdict(list) for _ in range(24)]
    days: set[str] = set()

    parsed: list[tuple[int, int, float, str]] = []
    for p in points:
        try:
            ms = int(p.get("timestamp_ms") or 0)
            sp = float(p.get("spread"))
        except (TypeError, ValueError):
            continue
        if ms <= 0 or not math.isfinite(sp):
            continue
        td = str(p.get("trade_date") or "")
        hour = _hour_msk(timestamp_ms=ms, trade_date=td)
        hour = max(0, min(23, hour))
        day = td.replace("T", " ").strip()[:10]
        parsed.append((ms, hour, sp, day))

    if len(parsed) < 3:
        return None

    for ms, hour, sp, day in parsed:
        spreads[hour].append(sp)
        if day:
            days.add(day)

    for i in range(1, len(parsed)):
        prev_ms, _, prev_sp, _ = parsed[i - 1]
        cur_ms, hour, cur_sp, day = parsed[i]
        if not is_consecutive_m15(prev_ms, cur_ms):
            continue
        delta = abs(cur_sp - prev_sp)
        deltas[hour].append(delta)
        if day:
            day_hour_deltas[hour][day].append(delta)

    bars: list[dict[str, Any]] = []
    for hour in range(24):
        xs = deltas[hour]
        vol = _population_std(xs) if len(xs) >= min_delta_samples else 0.0
        daily_steps = [
            (sum(vs) / len(vs)) for vs in day_hour_deltas[hour].values() if vs
        ]
        elev = elevated_day_counts(daily_steps)
        bars.append(
            {
                "hour": hour,
                "volatility": round(vol, 6),
                "delta_sample_count": len(xs),
                "spread_sample_count": len(spreads[hour]),
                "n_days": elev["n_days"],
                "n_elevated": elev["n_elevated"],
                "elevated_frac": elev["elevated_frac"],
                "median_step": elev["median_step"],
            }
        )

    peak = None
    for b in bars:
        if b["hour"] not in trading_hours():
            continue
        if b["delta_sample_count"] < min_delta_samples:
            continue
        if peak is None or b["volatility"] > peak["volatility"]:
            peak = b

    return {
        "ok": True,
        "bars": bars,
        "calendar_days": len(days),
        "total_delta_samples": sum(len(x) for x in deltas),
        "peak_hour": None if peak is None else int(peak["hour"]),
        "peak_volatility": 0.0 if peak is None else float(peak["volatility"]),
        "hour_start": HOUR_START_MSK,
        "hour_end": HOUR_END_MSK,
        "elevated_mult": ELEVATED_MULT,
        "min_days_for_elevated": MIN_DAYS_FOR_ELEVATED,
    }


def _load_m15_points(start_date: str) -> list[dict[str, Any]]:
    from replay.replay_db import _connect, db_retry

    def _op() -> list[dict[str, Any]]:
        with _connect() as conn:
            cur = conn.execute(
                """
                SELECT timestamp_ms, trade_date, spread_percent
                FROM m15_bars
                WHERE trade_date >= ?
                ORDER BY timestamp_ms
                """,
                (start_date,),
            )
            out: list[dict[str, Any]] = []
            for row in cur:
                try:
                    ms = int(row[0] or 0)
                    sp = float(row[2])
                except (TypeError, ValueError):
                    continue
                if ms <= 0 or not math.isfinite(sp):
                    continue
                out.append(
                    {
                        "timestamp_ms": ms,
                        "trade_date": str(row[1] or ""),
                        "spread": sp,
                    }
                )
            return out

    return db_retry(_op)


def clamp_hourly_vol_lookback(lookback_days: int) -> int:
    try:
        days = int(lookback_days)
    except (TypeError, ValueError):
        return LOOKBACK_DAYS
    return max(LOOKBACK_MIN_DAYS, min(LOOKBACK_MAX_DAYS, days))


def get_spread_hourly_vol(*, lookback_days: int = LOOKBACK_DAYS) -> dict[str, Any]:
    days = clamp_hourly_vol_lookback(lookback_days)
    from live.metric_dist import _ensure_3y_seeded
    from replay.replay_db import _db_last_trade_date

    _ensure_3y_seeded()
    last = _db_last_trade_date() or ""
    cache_key = f"{days}|{last}"
    now = time.time()
    with _LOCK:
        hit = _CACHE.get(cache_key)
        if (
            hit
            and hit.get("payload")
            and now - float(hit.get("ts") or 0) < _CACHE_TTL_SEC
        ):
            return dict(hit["payload"])

    start = (datetime.now(tz=MSK) - timedelta(days=days + 2)).strftime("%Y-%m-%d")
    points = _load_m15_points(start)
    report = build_spread_hourly_vol_report(points)
    if report is None:
        payload = {
            "ok": False,
            "error": "мало 15м баров для почасовой волатильности",
            "lookback_days": days,
            "bars": [],
        }
    else:
        payload = {
            **report,
            "lookback_days": days,
            "last_bar": last or None,
        }
    with _LOCK:
        _CACHE[cache_key] = {"ts": now, "payload": payload}
        if len(_CACHE) > _CACHE_MAX_KEYS:
            stale = [
                k
                for k, v in _CACHE.items()
                if now - float(v.get("ts") or 0) >= _CACHE_TTL_SEC
            ]
            for k in stale:
                _CACHE.pop(k, None)
            while len(_CACHE) > _CACHE_MAX_KEYS:
                oldest = min(_CACHE.items(), key=lambda kv: float(kv[1].get("ts") or 0))
                _CACHE.pop(oldest[0], None)
    return dict(payload)
