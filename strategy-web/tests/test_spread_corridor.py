"""Unit tests for adaptive spread corridor detector."""

from live.spread_corridor import (
    corridor_history,
    daily_spread_extremes_from_bars,
    daily_spreads_from_bars,
    detect_spread_corridor,
    rolling_corridor_history,
    spreads_from_bars,
)


def _days(vals: list[float]) -> list[tuple[str, float]]:
    return [(f"2026-01-{i + 1:02d}", v) for i, v in enumerate(vals)]


def _bars_from_daily(daily: list[tuple[str, float]]) -> list[dict]:
    bars = []
    for d, med in daily:
        bars.append({"time": f"{d} 10:00", "spread": med - 0.05})
        bars.append({"time": f"{d} 15:00", "spread": med + 0.05})
    return bars


def test_daily_from_bars_time_field():
    bars = [
        {"time": "2026-01-05 10:00", "spread": 2.0},
        {"time": "2026-01-05 15:00", "spread": 2.5},
        {"tradeDate": "2026-01-06 10:00", "spreadPercent": 1.8},
        {"timestampMs": 1736179200000, "spread": 1.9},
    ]
    daily = daily_spreads_from_bars(bars)
    assert len(daily) >= 3
    by_day = dict(daily)
    assert by_day.get("2026-01-05") == 2.25
    assert by_day.get("2026-01-06") == 1.8


def test_calculated_wide_corridor_from_ping_pong():
    vals = []
    for i in range(14):
        vals.append(1.05 if i % 2 == 0 else 3.15)
    daily = _days(vals)
    bars = _bars_from_daily(daily)
    out = detect_spread_corridor(
        daily,
        bar_spreads=spreads_from_bars(bars),
        touch_mins=[(d, v - 0.08) for d, v in daily],
        touch_maxs=[(d, v + 0.08) for d, v in daily],
    )
    assert out["bounds_mode"] == "calculated"
    assert 0.9 <= out["lo"] <= 1.2
    assert 3.0 <= out["hi"] <= 3.35
    assert out["width"] >= 1.8
    assert out["touches_lo"] >= 2
    assert out["touches_hi"] >= 2
    assert out["phase"] == "formed"


def test_adaptive_narrow_corridor():
    vals = [1.7 + (i % 2) * 0.35 for i in range(12)]
    daily = _days(vals)
    bars = _bars_from_daily(daily)
    out = detect_spread_corridor(
        daily,
        bar_spreads=spreads_from_bars(bars),
        touch_mins=[(d, v - 0.05) for d, v in daily],
        touch_maxs=[(d, v + 0.05) for d, v in daily],
    )
    assert out["bounds_mode"] == "adaptive"
    assert out["width"] is not None and out["width"] <= 1.5


def test_broken_after_calculated_range():
    vals = []
    for i in range(10):
        vals.append(1.1 if i % 2 == 0 else 3.1)
    vals.append(5.0)
    daily = _days(vals)
    bars = _bars_from_daily(daily)
    out = detect_spread_corridor(
        _days(vals),
        spread_now=5.0,
        bar_spreads=spreads_from_bars(bars),
        touch_mins=[(d, v - 0.08) for d, v in daily],
        touch_maxs=[(d, v + 0.08) for d, v in daily],
    )
    assert out["phase"] == "broken"
    assert out["spread"] == 5.0
    assert out["hi"] is not None and out["hi"] < 4.0


def test_broken_sticky_after_intraday_spike_not_swallowed():
    """Шип дневного max не должен расширить полосу и вернуть «сформирован»."""
    vals = []
    for i in range(12):
        vals.append(1.1 if i % 2 == 0 else 3.0)
    daily = _days(vals)
    # Последний день: медиана ещё «внутри», но max ушёл выше потолка.
    touch_mins = [(d, v - 0.08) for d, v in daily]
    touch_maxs = [(d, v + 0.08) for d, v in daily[:-1]]
    touch_maxs.append((daily[-1][0], 4.05))
    out = detect_spread_corridor(
        daily,
        spread_now=3.15,
        touch_mins=touch_mins,
        touch_maxs=touch_maxs,
    )
    assert out["phase"] == "broken"
    assert out["hi"] is not None and out["hi"] < 3.5
    assert out["bounds_mode"] == "frozen"


def test_corridor_history_phases():
    vals = []
    for i in range(14):
        vals.append(1.05 if i % 2 == 0 else 3.15)
    daily = _days(vals)
    bars = _bars_from_daily(daily)
    hist = corridor_history(daily, bars=bars)
    assert len(hist) >= 1
    assert all(h["phase"] in ("forming", "formed") for h in hist)
    out = detect_spread_corridor([1.0, 2.0, 3.0])
    assert out["phase"] == "none"
    assert out["n_days"] == 3


def test_rolling_history_keeps_earlier_high_corridor():
    daily = []
    for i in range(18):
        # Верхний коридор 5.2…6.8 — без фильтра «узкий режим».
        daily.append((f"2026-06-{i + 1:02d}", 5.3 if i % 2 == 0 else 6.7))
    bars = _bars_from_daily(daily)
    hist = rolling_corridor_history(bars, lookback_days=12)
    assert hist
    assert any(h["phase"] == "formed" for h in hist)
    assert min(float(h["lo"]) for h in hist) > 4.5
    assert all(int(h["segment_id"]) >= 1 for h in hist)


def test_high_corridor_without_low_regime_slice():
    """Полка 5–7 видна и без дней с серединой < 2.8."""
    vals = [5.25 if i % 2 == 0 else 6.75 for i in range(12)]
    daily = _days(vals)
    out = detect_spread_corridor(
        daily,
        touch_mins=[(d, v - 0.10) for d, v in daily],
        touch_maxs=[(d, v + 0.10) for d, v in daily],
    )
    assert out["phase"] in ("forming", "formed")
    assert out["lo"] is not None and out["lo"] > 4.5
    assert out["hi"] is not None and out["hi"] > 6.0


def test_reforms_new_shelf_after_old_corridor_breaks():
    """После выноса из 1.x–3.x новая полка ~3.2–4.0 — forming, не вечный слом."""
    daily: list[tuple[str, float]] = []
    touch_mins: list[tuple[str, float]] = []
    touch_maxs: list[tuple[str, float]] = []
    # Старая полка 1.1…3.1
    for i in range(10):
        med = 1.15 if i % 2 == 0 else 3.05
        d = f"2026-08-{i + 1:02d}"
        daily.append((d, med))
        touch_mins.append((d, med - 0.10))
        touch_maxs.append((d, med + 0.10))
    # День выноса: середина ещё внутри, max ушёл выше потолка.
    daily.append(("2026-08-11", 3.10))
    touch_mins.append(("2026-08-11", 2.90))
    touch_maxs.append(("2026-08-11", 4.20))
    # Новая полка ~3.0–4.0 (середины уже вне старых границ).
    new = [
        (3.30, 3.05, 3.95),
        (3.80, 3.22, 3.55),
        (3.35, 3.08, 4.02),
        (3.85, 3.20, 3.60),
        (3.42, 3.00, 3.98),
    ]
    for i, (med, mn, mx) in enumerate(new):
        d = f"2026-08-{12 + i:02d}"
        daily.append((d, med))
        touch_mins.append((d, mn))
        touch_maxs.append((d, mx))
    out = detect_spread_corridor(
        daily,
        spread_now=3.48,
        touch_mins=touch_mins,
        touch_maxs=touch_maxs,
    )
    assert out["phase"] in ("forming", "formed")
    assert out["lo"] is not None and 2.85 <= out["lo"] <= 3.35
    assert out["hi"] is not None and 3.70 <= out["hi"] <= 4.20
    assert out["bounds_mode"] != "frozen"
    assert out["since_date"] >= "2026-08-11"


def test_broken_stays_until_new_shelf_has_enough_days():
    """Один день после выноса — ещё сломан, без новой полосы."""
    vals = []
    for i in range(10):
        vals.append(1.1 if i % 2 == 0 else 3.1)
    vals.append(5.0)
    vals.append(3.6)
    daily = _days(vals)
    out = detect_spread_corridor(
        daily,
        spread_now=3.6,
        touch_mins=[(d, (v - 0.08) if v < 4 else 3.4) for d, v in daily],
        touch_maxs=[(d, (v + 0.08) if v < 4 else 5.1) for d, v in daily],
    )
    assert out["phase"] == "broken"
    assert out["hi"] is not None and out["hi"] < 4.0


def test_shelf_32_40_not_inflated_by_old_lows_or_wicks():
    """Полка ~3.2–4.0 не раздувается вниз к 1.x из-за старых дней или хвоста."""
    daily: list[tuple[str, float]] = []
    touch_mins: list[tuple[str, float]] = []
    touch_maxs: list[tuple[str, float]] = []
    # Старая полка 1.1…3.2 (как на TATN до 25.08).
    for i in range(12):
        low_day = i % 2 == 0
        med = 1.20 if low_day else 3.10
        d = f"2026-08-{i + 1:02d}"
        daily.append((d, med))
        touch_mins.append((d, 1.08 if low_day else 2.95))
        touch_maxs.append((d, 1.35 if low_day else 3.22))
    # День перехода: минимум ещё низкий, максимум уже 4.x.
    daily.append(("2026-08-13", 2.76))
    touch_mins.append(("2026-08-13", 2.43))
    touch_maxs.append(("2026-08-13", 4.07))
    # Новая полка ~3.0–4.0 + редкий верхний хвост 4.41.
    new = [
        (3.35, 3.07, 3.98),
        (3.41, 3.17, 4.41),
        (3.33, 3.04, 3.80),
        (3.53, 3.41, 3.82),
        (3.45, 3.10, 3.90),
    ]
    for i, (med, mn, mx) in enumerate(new):
        d = f"2026-08-{14 + i:02d}"
        daily.append((d, med))
        touch_mins.append((d, mn))
        touch_maxs.append((d, mx))
    out = detect_spread_corridor(
        daily,
        spread_now=3.45,
        touch_mins=touch_mins,
        touch_maxs=touch_maxs,
    )
    assert out["phase"] in ("forming", "formed")
    assert out["lo"] is not None and 2.95 <= out["lo"] <= 3.30
    assert out["hi"] is not None and 3.70 <= out["hi"] <= 4.25
    assert out["lo"] > 2.5
    assert out["bounds_mode"] != "frozen"


def test_rare_low_wick_does_not_pull_current_shelf_floor():
    """Один дневной минимум 1.2 внутри полки 3.x не опускает пол к 1.x."""
    daily: list[tuple[str, float]] = []
    touch_mins: list[tuple[str, float]] = []
    touch_maxs: list[tuple[str, float]] = []
    days = [
        (3.30, 3.05, 3.95),
        (3.80, 3.22, 3.55),
        (3.35, 1.20, 4.02),  # редкий хвост вниз
        (3.85, 3.20, 3.60),
        (3.42, 3.00, 3.98),
        (3.55, 3.12, 3.88),
        (3.40, 3.08, 3.92),
    ]
    for i, (med, mn, mx) in enumerate(days):
        d = f"2026-08-{10 + i:02d}"
        daily.append((d, med))
        touch_mins.append((d, mn))
        touch_maxs.append((d, mx))
    out = detect_spread_corridor(
        daily,
        spread_now=3.48,
        touch_mins=touch_mins,
        touch_maxs=touch_maxs,
    )
    assert out["phase"] in ("forming", "formed")
    assert out["lo"] is not None and out["lo"] >= 2.90
    assert out["hi"] is not None and 3.55 <= out["hi"] <= 4.20


def test_corridor_history_splits_old_and_new_shelf():
    """История не склеивает полку 1.x–3.x с новой 3.2–4.0 в один сегмент."""
    daily: list[tuple[str, float]] = []
    bars: list[dict] = []
    for i in range(10):
        med = 1.15 if i % 2 == 0 else 3.05
        d = f"2026-08-{i + 1:02d}"
        daily.append((d, med))
        bars.append({"time": f"{d} 10:00", "spread": med - 0.10})
        bars.append({"time": f"{d} 15:00", "spread": med + 0.10})
    daily.append(("2026-08-11", 3.10))
    bars.append({"time": "2026-08-11 10:00", "spread": 2.90})
    bars.append({"time": "2026-08-11 15:00", "spread": 4.20})
    for i, med in enumerate([3.30, 3.80, 3.35, 3.85, 3.42]):
        d = f"2026-08-{12 + i:02d}"
        daily.append((d, med))
        bars.append({"time": f"{d} 10:00", "spread": med - 0.25})
        bars.append({"time": f"{d} 15:00", "spread": med + 0.20})
    hist = corridor_history(daily, bars=bars)
    assert hist
    ids = {int(h["segment_id"]) for h in hist if h.get("segment_id") is not None}
    assert len(ids) >= 2
    last = hist[-1]
    assert 2.85 <= float(last["lo"]) <= 3.40
    assert 3.50 <= float(last["hi"]) <= 4.30
    early_low = [h for h in hist if float(h["lo"]) < 2.0]
    assert early_low
    assert int(early_low[-1]["segment_id"]) != int(last["segment_id"])


def _bounce_bars(
    day: str,
    lo: float,
    hi: float,
    n: int = 24,
    *,
    spike: float | None = None,
    spike_at: int = 2,
) -> list[dict]:
    bars: list[dict] = []
    mid = (lo + hi) / 2.0
    for i in range(n):
        k = i % 4
        if k == 0:
            sp = lo
        elif k == 2:
            sp = hi
        else:
            sp = mid
        if spike is not None and i == spike_at:
            sp = spike
        minute = 10 * 60 + i * 15
        hh, mm = divmod(minute, 60)
        bars.append({"time": f"{day} {hh:02d}:{mm:02d}", "spread": sp})
    return bars


def test_inner_narrow_shelf_inside_wider_recent():
    """Узкая полка внутри более широкой недавней: текущие границы = узкая."""
    bars: list[dict] = []
    for i in range(5):
        bars.extend(_bounce_bars(f"2026-07-{i + 1:02d}", 3.02, 4.05, 24))
    for i in range(2):
        bars.extend(_bounce_bars(f"2026-07-{6 + i:02d}", 3.18, 3.42, 24))
    daily, mins, maxs = daily_spread_extremes_from_bars(bars)
    outer = detect_spread_corridor(
        daily, spread_now=3.28, touch_mins=mins, touch_maxs=maxs,
    )
    assert outer["lo"] is not None and outer["hi"] is not None
    assert outer["hi"] - outer["lo"] >= 0.70
    out = detect_spread_corridor(
        daily,
        spread_now=3.28,
        touch_mins=mins,
        touch_maxs=maxs,
        bars=bars,
    )
    assert out["phase"] in ("forming", "formed")
    assert 3.12 <= float(out["lo"]) <= 3.24
    assert 3.36 <= float(out["hi"]) <= 3.50
    assert float(out["hi"]) - float(out["lo"]) < 0.55
    assert str(out["since_date"]) >= "2026-07-06"


def test_inner_shelf_ignores_isolated_spike():
    """Один шип 5.x не расширяет узкую текущую полку."""
    bars: list[dict] = []
    for i in range(5):
        bars.extend(_bounce_bars(f"2026-07-{i + 1:02d}", 3.02, 4.05, 24))
    bars.extend(_bounce_bars("2026-07-06", 3.18, 3.42, 24))
    bars.extend(_bounce_bars("2026-07-07", 3.18, 3.42, 24, spike=5.10, spike_at=5))
    daily, mins, maxs = daily_spread_extremes_from_bars(bars)
    out = detect_spread_corridor(
        daily,
        spread_now=3.30,
        touch_mins=mins,
        touch_maxs=maxs,
        bars=bars,
    )
    assert out["phase"] in ("forming", "formed")
    assert 3.12 <= float(out["lo"]) <= 3.24
    assert 3.36 <= float(out["hi"]) <= 3.50
    assert float(out["hi"]) < 4.5


def test_three_bars_do_not_make_inner_shelf():
    """Три свечи узкого качания не подменяют широкий коридор."""
    bars: list[dict] = []
    for i in range(7):
        bars.extend(_bounce_bars(f"2026-07-{i + 1:02d}", 3.02, 4.05, 24))
    bars[-3]["spread"] = 3.20
    bars[-2]["spread"] = 3.40
    bars[-1]["spread"] = 3.21
    daily, mins, maxs = daily_spread_extremes_from_bars(bars)
    out = detect_spread_corridor(
        daily,
        spread_now=3.21,
        touch_mins=mins,
        touch_maxs=maxs,
        bars=bars,
    )
    assert out["lo"] is not None and out["hi"] is not None
    assert float(out["hi"]) - float(out["lo"]) >= 0.70
    assert float(out["lo"]) <= 3.20
    assert float(out["hi"]) >= 3.80


def test_wide_1x_3x_m15_not_replaced_by_inner():
    """Качалка 1.x–3.x с плотными m15 остаётся широкой полкой, не схлопывается."""
    bars: list[dict] = []
    for i in range(12):
        d = f"2026-07-{i + 1:02d}"
        if i % 2 == 0:
            bars.extend(_bounce_bars(d, 1.08, 1.22, 20))
        else:
            bars.extend(_bounce_bars(d, 3.00, 3.18, 20))
    daily, mins, maxs = daily_spread_extremes_from_bars(bars)
    out = detect_spread_corridor(
        daily,
        spread_now=3.05,
        touch_mins=mins,
        touch_maxs=maxs,
        bars=bars,
    )
    assert out["phase"] in ("forming", "formed")
    assert 0.90 <= float(out["lo"]) <= 1.30
    assert 2.90 <= float(out["hi"]) <= 3.40


def test_inner_keeps_outer_bounds_in_payload():
    bars: list[dict] = []
    for i in range(5):
        bars.extend(_bounce_bars(f"2026-07-{i + 1:02d}", 3.02, 4.05, 24))
    for i in range(2):
        bars.extend(_bounce_bars(f"2026-07-{6 + i:02d}", 3.18, 3.42, 24))
    daily, mins, maxs = daily_spread_extremes_from_bars(bars)
    out = detect_spread_corridor(
        daily,
        spread_now=3.28,
        touch_mins=mins,
        touch_maxs=maxs,
        bars=bars,
    )
    assert out["inner_shelf"] is True
    assert out["outer_lo"] is not None and out["outer_hi"] is not None
    assert float(out["outer_hi"]) - float(out["outer_lo"]) >= 0.70
    assert float(out["hi"]) - float(out["lo"]) < 0.55
    assert out["inner"] is not None
    assert float(out["inner"]["hi"]) - float(out["inner"]["lo"]) < 0.55


def test_desk_payload_canon_without_inner_is_outer():
    """Стол Ситуации: фаза и границы — внешний коридор, не 2-дневная полка."""
    from live.spread_corridor import desk_corridor_payload

    bars: list[dict] = []
    for i in range(5):
        bars.extend(_bounce_bars(f"2026-07-{i + 1:02d}", 3.02, 4.05, 24))
    for i in range(2):
        bars.extend(_bounce_bars(f"2026-07-{6 + i:02d}", 3.18, 3.42, 24))
    inner = desk_corridor_payload(bars, spread_now=3.28, allow_inner=True)
    outer = desk_corridor_payload(bars, spread_now=3.28, allow_inner=False)
    assert float(inner["hi"]) - float(inner["lo"]) < 0.55
    assert inner["phase"] == "forming"
    assert float(outer["hi"]) - float(outer["lo"]) >= 0.70
    assert outer["phase"] in ("forming", "formed")
    assert not outer.get("inner_shelf")


def test_short_window_keeps_cached_full_desk_corridor(monkeypatch):
    """Урезанные 7 дней не должны сменить канон полного окна."""
    import time

    from live import spread_corridor as sc

    full = {
        "phase": "formed",
        "label_ru": "сформирован",
        "lo": 2.96,
        "hi": 3.96,
        "spread": 3.20,
        "pct_in_band": 24.0,
        "history": [],
    }
    sc._DESK_CORR["payload"] = full
    sc._DESK_CORR["n_days"] = 30
    sc._DESK_CORR["last_date"] = "2026-09-02"
    sc._DESK_CORR["ts"] = time.time()
    sc._LOOKBACK_BARS["ts"] = 0.0
    sc._LOOKBACK_BARS["days"] = 0
    sc._LOOKBACK_BARS["bars"] = []
    monkeypatch.setattr(sc, "_m15_bars_from_replay_db", lambda _days: [])
    short: list[dict] = []
    for i in range(5):
        short.extend(_bounce_bars(f"2026-09-{i + 1:02d}", 2.90, 3.40, 8))
    out = sc.desk_corridor_payload_for_desk(
        {"bars": short}, chart_days=7, spread_now=3.15
    )
    assert out["phase"] == "formed"
    assert out["lo"] == 2.96
    assert out["hi"] == 3.96
    assert out["spread"] == 3.15


def test_lite_desk_corridor_keeps_cached_canon(monkeypatch):
    import time

    from live import spread_corridor as sc

    full = {
        "phase": "formed",
        "label_ru": "сформирован",
        "lo": 2.96,
        "hi": 3.96,
        "spread": 3.20,
        "pct_in_band": 24.0,
        "history": [],
    }
    sc._DESK_CORR["payload"] = full
    sc._DESK_CORR["n_days"] = 30
    sc._DESK_CORR["last_date"] = "2026-09-02"
    sc._DESK_CORR["ts"] = time.time()
    warmed = {"n": 0}
    monkeypatch.setattr(sc, "kick_desk_corridor_warm", lambda *a, **k: warmed.__setitem__("n", 1))
    out = sc.desk_corridor_payload_for_desk(
        {"bars": []}, chart_days=7, spread_now=3.15, lite=True
    )
    assert out["phase"] == "formed"
    assert out["lo"] == 2.96
    assert out["hi"] == 3.96
    assert out["spread"] == 3.15
    assert warmed["n"] == 0


def test_lite_desk_corridor_does_not_compute_short_window(monkeypatch):
    from live import spread_corridor as sc

    sc._DESK_CORR["payload"] = None
    sc._DESK_CORR["n_days"] = 0
    sc._DESK_CORR["ts"] = 0.0
    warmed = {"n": 0}
    monkeypatch.setattr(sc, "kick_desk_corridor_warm", lambda *a, **k: warmed.__setitem__("n", 1))
    short: list[dict] = []
    for i in range(5):
        short.extend(_bounce_bars(f"2026-09-{i + 1:02d}", 2.90, 3.40, 8))
    out = sc.desk_corridor_payload_for_desk(
        {"bars": short}, chart_days=7, spread_now=3.15, lite=True
    )
    assert out.get("phase") is None
    assert out.get("warming") is True
    assert warmed["n"] == 1

