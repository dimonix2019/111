"""Почасовая σ спреда — parity с MoexSpreadHourlyVolatility."""
from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

from live.spread_hourly_vol import (
    ELEVATED_MULT,
    HOUR_START_MSK,
    LOOKBACK_DAYS,
    LOOKBACK_MAX_DAYS,
    LOOKBACK_MIN_DAYS,
    MIN_DAYS_FOR_ELEVATED,
    build_spread_hourly_vol_report,
    clamp_hourly_vol_lookback,
    elevated_day_counts,
    is_consecutive_m15,
    trading_hours,
)

MSK = ZoneInfo("Europe/Moscow")


def _pt(label: str, spread: float) -> dict:
    dt = datetime.strptime(label, "%Y-%m-%d %H:%M").replace(tzinfo=MSK)
    return {
        "timestamp_ms": int(dt.timestamp() * 1000),
        "trade_date": label,
        "spread": spread,
    }


def test_consecutive_m15_is_exactly_15_minutes():
    a = _pt("2026-05-19 10:00", 5.0)
    b = _pt("2026-05-19 10:15", 5.1)
    c = _pt("2026-05-19 10:30", 5.2)
    gap = _pt("2026-05-19 11:00", 5.0)
    assert is_consecutive_m15(a["timestamp_ms"], b["timestamp_ms"])
    assert is_consecutive_m15(b["timestamp_ms"], c["timestamp_ms"])
    assert not is_consecutive_m15(c["timestamp_ms"], gap["timestamp_ms"])


def test_groups_deltas_by_bar_hour():
    points = [
        _pt("2026-05-19 10:00", 5.00),
        _pt("2026-05-19 10:15", 5.10),
        _pt("2026-05-19 10:30", 5.30),
        _pt("2026-05-19 11:00", 5.00),
        _pt("2026-05-19 11:15", 5.05),
        _pt("2026-05-20 10:00", 5.10),
        _pt("2026-05-20 10:15", 5.20),
        _pt("2026-05-20 10:30", 5.50),
    ]
    report = build_spread_hourly_vol_report(points)
    assert report is not None
    hour10 = report["bars"][10]
    hour11 = report["bars"][11]
    assert hour10["delta_sample_count"] == 4
    assert hour11["delta_sample_count"] == 1
    assert hour10["volatility"] > 0.0
    assert hour10["n_days"] == 2
    assert hour11["n_days"] == 1
    assert hour10["elevated_frac"] is None
    assert report["peak_hour"] == 10
    assert report["calendar_days"] == 2


def test_trading_hours_skip_night():
    assert HOUR_START_MSK == 8
    hours = list(trading_hours())
    assert hours[0] == 8
    assert hours[-1] == 23
    assert 3 not in hours


def test_short_series_is_none():
    assert build_spread_hourly_vol_report([]) is None
    assert (
        build_spread_hourly_vol_report(
            [
                _pt("2026-05-19 10:00", 5.0),
                _pt("2026-05-19 10:15", 5.1),
            ]
        )
        is None
    )


def _pair(day: str, hour: int, delta: float) -> list[dict]:
    """Два соседних 15м бара в часе: шаг = |delta|."""
    base = 5.0
    return [
        _pt(f"{day} {hour:02d}:00", base),
        _pt(f"{day} {hour:02d}:15", base + delta),
    ]


def test_elevated_threshold_is_1_5_times_median():
    assert ELEVATED_MULT == 1.5
    quiet = [0.08 + i * 0.001 for i in range(20)]
    out = elevated_day_counts(quiet)
    assert out["n_days"] == 20
    assert out["n_elevated"] == 0
    assert out["elevated_frac"] == 0.0
    # Перцентиль того же ряда дал бы ~25%; 1,5× медианы — нет.
    p75_cut = sorted(quiet)[15]
    n_p75 = sum(1 for x in quiet if x >= p75_cut)
    assert n_p75 >= 5
    fat = [0.10] * 15 + [0.40] * 5
    fat_out = elevated_day_counts(fat)
    assert fat_out["n_elevated"] == 5
    assert fat_out["elevated_frac"] == 0.25
    assert fat_out["median_step"] == 0.10


def test_few_days_frac_is_none():
    out = elevated_day_counts([0.1, 0.1, 0.4])
    assert out["n_days"] == 3
    assert out["n_days"] < MIN_DAYS_FOR_ELEVATED
    assert out["n_elevated"] == 1
    assert out["elevated_frac"] is None


def test_fat_hour_elevated_more_often_than_quiet():
    points: list[dict] = []
    for i in range(20):
        day = f"2026-04-{i + 1:02d}"
        # 10:00 — толстый хвост: 4 дня с крупным шагом.
        d10 = 0.40 if i >= 16 else 0.05
        points.extend(_pair(day, 10, d10))
        # 15:00 — ровный тихий час.
        points.extend(_pair(day, 15, 0.08 + i * 0.001))
        # 12:00 — только 3 дня (мало данных).
        if i < 3:
            points.extend(_pair(day, 12, 0.06))
    points.sort(key=lambda p: p["timestamp_ms"])
    report = build_spread_hourly_vol_report(points)
    assert report is not None
    assert report["elevated_mult"] == 1.5
    assert report["min_days_for_elevated"] == 10
    h10 = report["bars"][10]
    h15 = report["bars"][15]
    h12 = report["bars"][12]
    assert h10["n_days"] == 20
    assert h10["n_elevated"] == 4
    assert h10["elevated_frac"] == 0.2
    assert h15["n_days"] == 20
    assert h15["n_elevated"] == 0
    assert h15["elevated_frac"] == 0.0
    assert h12["n_days"] == 3
    assert h12["elevated_frac"] is None
    assert h10["volatility"] > h15["volatility"]


def test_lookback_clamp_allows_week_month_quarter():
    assert LOOKBACK_MIN_DAYS == 7
    assert LOOKBACK_DAYS == 90
    assert LOOKBACK_MAX_DAYS == 365
    assert clamp_hourly_vol_lookback(7) == 7
    assert clamp_hourly_vol_lookback(30) == 30
    assert clamp_hourly_vol_lookback(90) == 90
    assert clamp_hourly_vol_lookback(3) == 7
    assert clamp_hourly_vol_lookback(999) == 365
