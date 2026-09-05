"""Окно дат Теста должно брать CSV, который его покрывает; хвост 255d клеится к 1095d."""
from __future__ import annotations

from replay.replay_db import merge_bars_by_timestamp
from replay.tip_touch import resolve_csv_for_window, window_span_days


def test_three_year_window_upgrades_365d_to_1095d():
    assert (
        resolve_csv_for_window(
            "m15_tatn_365d.csv",
            start="2023-09-01",
            end="2026-09-01",
        )
        == "m15_tatn_1095d.csv"
    )


def test_year_window_keeps_365d():
    assert (
        resolve_csv_for_window(
            "m15_tatn_365d.csv",
            start="2025-09-01",
            end="2026-09-01",
        )
        == "m15_tatn_365d.csv"
    )


def test_year_window_upgrades_255d():
    assert (
        resolve_csv_for_window(
            "m15_tatn_255d.csv",
            start="2025-09-01",
            end="2026-09-01",
        )
        == "m15_tatn_365d.csv"
    )


def test_does_not_downgrade_1095d_for_short_window():
    assert (
        resolve_csv_for_window(
            "m15_tatn_1095d.csv",
            start="2026-08-01",
            end="2026-09-01",
        )
        == "m15_tatn_1095d.csv"
    )


def test_test_3d_csv_untouched():
    assert (
        resolve_csv_for_window(
            "m15_test_3d.csv",
            start="2023-09-01",
            end="2026-09-01",
        )
        == "m15_test_3d.csv"
    )


def test_no_start_keeps_selected():
    assert resolve_csv_for_window("m15_tatn_365d.csv", start=None, end="2026-09-01") == (
        "m15_tatn_365d.csv"
    )


def test_bars1m_date_only_window_ms():
    from replay.tip_touch import _window_end_ms, _window_start_ms

    start_ms = _window_start_ms(None, "2026-03-03")
    end_ms = _window_end_ms("2026-03-03")
    assert start_ms > 0
    assert end_ms > start_ms
    assert (end_ms - start_ms) >= 23 * 3600 * 1000


def test_short_week_window_is_short():
    from replay.tip_touch import _SHORT_WINDOW_MAX_DAYS, _is_short_test_window

    assert _is_short_test_window("2026-08-31", "2026-09-02") is True
    assert _is_short_test_window("2023-09-01", "2026-09-01") is False
    assert window_span_days("2026-08-31", "2026-09-02") <= _SHORT_WINDOW_MAX_DAYS


def test_three_month_window_is_short():
    """Чип «3 мес» (~90–93д) не должен идти полным 1095d/npz (~756k)."""
    from replay.tip_touch import _SHORT_WINDOW_MAX_DAYS, _is_short_test_window

    assert _SHORT_WINDOW_MAX_DAYS >= 95
    assert window_span_days("2026-06-06", "2026-09-06") <= _SHORT_WINDOW_MAX_DAYS
    assert _is_short_test_window("2026-06-06", "2026-09-06") is True
    assert _is_short_test_window("2026-06-08", "2026-09-05") is True
    assert _is_short_test_window("2025-09-06", "2026-09-06") is False
    assert _is_short_test_window("2023-09-01", "2026-09-01") is False


def test_short_window_prep_skips_full_build_lock():
    """Неделя Tesта не ждёт лок 3-летней пересборки."""
    import threading
    import time

    from replay import tip_touch

    held = threading.Event()
    release = threading.Event()

    def _hold() -> None:
        with tip_touch._tip_build_lock:
            held.set()
            release.wait(20)

    t = threading.Thread(target=_hold, daemon=True)
    t.start()
    assert held.wait(2)
    t0 = time.perf_counter()
    prep, meta = tip_touch.ensure_tip_series(
        "m15_tatn_1095d.csv",
        start="2026-08-31",
        end="2026-09-02",
    )
    elapsed = time.perf_counter() - t0
    release.set()
    t.join(timeout=3)
    assert elapsed < 12.0, elapsed
    assert prep.n < 120_000, prep.n
    assert meta.get("window") or str(meta.get("cacheTier") or "").startswith("window")


def test_three_month_window_skips_full_build_lock():
    """3мес Tesта — оконный parquet, не лок полной 3y-пересборки."""
    import threading
    import time

    from replay import tip_touch

    if not tip_touch.CACHE_1M.is_file():
        return
    held = threading.Event()
    release = threading.Event()

    def _hold() -> None:
        with tip_touch._tip_build_lock:
            held.set()
            release.wait(20)

    t = threading.Thread(target=_hold, daemon=True)
    t.start()
    assert held.wait(2)
    t0 = time.perf_counter()
    prep, meta = tip_touch.ensure_tip_series(
        "m15_tatn_1095d.csv",
        start="2026-06-06",
        end="2026-09-06",
    )
    elapsed = time.perf_counter() - t0
    release.set()
    t.join(timeout=3)
    assert elapsed < 40.0, elapsed
    assert prep.n < 250_000, prep.n
    assert meta.get("window") or str(meta.get("cacheTier") or "").startswith("window")


def test_short_window_bars1m_not_three_years():
    from replay.tip_touch import CACHE_1M, bars1m_chart

    if not CACHE_1M.is_file():
        return
    data = bars1m_chart(
        "m15_tatn_1095d.csv",
        start="2026-08-31",
        end="2026-09-02",
        chart_days=7,
    )
    assert data.get("ok") is True
    assert int(data.get("fullTipCount") or 0) < 80_000
    first = str(data.get("first") or "")
    last = str(data.get("last") or "")
    assert first[:10] >= "2026-08-31"
    assert last[:10] <= "2026-09-02"


def test_isolated_chart_window_3y_is_90d_tail():
    from replay.tip_touch import isolated_chart_window

    win = isolated_chart_window("2023-09-01", "2026-09-05", 90)
    assert win is not None
    assert win[1] == "2026-09-05"
    assert win[0] == "2026-06-08"
    assert isolated_chart_window("2026-08-31", "2026-09-02", 90) is None
    assert isolated_chart_window("2026-06-06", "2026-09-06", 90) is None
    assert isolated_chart_window("2023-09-01", "2026-09-05", 0) is None


def test_3y_chart_days_skips_full_build_lock():
    """График 90д при окне 3г не ждёт лок полной пересборки."""
    import threading
    import time

    from replay import tip_touch

    if not tip_touch.CACHE_1M.is_file():
        return
    held = threading.Event()
    release = threading.Event()

    def _hold() -> None:
        with tip_touch._tip_build_lock:
            held.set()
            release.wait(25)

    t = threading.Thread(target=_hold, daemon=True)
    t.start()
    assert held.wait(2)
    t0 = time.perf_counter()
    data = tip_touch.bars1m_chart(
        "m15_tatn_1095d.csv",
        start="2023-09-01",
        end="2026-09-05",
        chart_days=90,
    )
    elapsed = time.perf_counter() - t0
    release.set()
    t.join(timeout=3)
    assert elapsed < 40.0, elapsed
    assert data.get("ok") is True
    assert data.get("bars")
    meta = data.get("meta") or {}
    assert meta.get("window") or str(meta.get("cacheTier") or "").startswith("window")
    snap = tip_touch.tip_busy_snapshot()
    assert "tipBuildLock" in snap
    assert "phaseRu" in snap


def test_three_year_span_days():
    n = window_span_days("2023-09-01", "2026-09-01")
    assert n is not None and n >= 1095


def test_parse_ts_ms_date_only():
    from replay.replay_data import parse_ts_ms

    ms = parse_ts_ms("2026-03-03")
    assert ms > 0
    assert parse_ts_ms("2023-06-07 09:45:00") < ms


def test_merge_zero_timestamp_does_not_hide_2023():
    from replay.replay_data import parse_ts_ms

    early = [
        {
            "timestampMs": parse_ts_ms("2023-06-07 09:45:00"),
            "tradeDate": "2023-06-07 09:45:00",
        },
        {
            "timestampMs": parse_ts_ms("2026-06-05 23:30:00"),
            "tradeDate": "2026-06-05 23:30:00",
        },
    ]
    tail = [
        {"timestampMs": 0, "tradeDate": "2026-03-03"},
        {
            "timestampMs": parse_ts_ms("2026-09-05 18:45:00"),
            "tradeDate": "2026-09-05 18:45:00",
        },
    ]
    out = merge_bars_by_timestamp(early, tail)
    assert out[0]["tradeDate"].startswith("2023")
    assert all(int(b["timestampMs"]) > 0 for b in out)
    assert out[-1]["tradeDate"].startswith("2026-09")


def test_sim_tip1m_3y_chart_filter_does_not_empty_trades():
    """Фильтр time графика не должен резать sim_tip1m (ожидание ~515 сделок на 3y)."""
    from replay.tip_touch import CACHE_1M, DATA_DIR, sim_tip1m

    if not CACHE_1M.is_file():
        import pytest

        pytest.skip("нет cache_1m_tatn_spread.parquet")
    if not (DATA_DIR / "m15_tatn_1095d.csv").is_file():
        import pytest

        pytest.skip("нет m15_tatn_1095d.csv")
    sim = sim_tip1m(
        csv="m15_tatn_1095d.csv",
        entry=1.6,
        exit_z=1.3,
        slip=0.02,
        notional=100_000.0,
        compound=True,
        take_profit_pct=2.0,
        spread_level_mode=True,
        addon_mode=True,
        extreme_addon_mode=True,
        shelf_floor_ceiling_mode=True,
        max_hold_days_no_exit_trend=7.0,
    )
    trades = sim.get("trades") or []
    closed = [t for t in trades if str(t.get("status") or "") != "Открыта"]
    n = len(closed)
    # Эталон full_hold7 ≈515; фильтр графика не должен обнулить/укоротить сим.
    assert n >= 480, f"3y sim закрытых={n}, фильтр графика не должен резать сим"
    summary_n = (sim.get("summary") or {}).get("trades")
    if summary_n is not None:
        assert int(summary_n) >= 480


def test_truncated_3y_prep_is_rejected():
    import numpy as np
    from replay.tip_touch import DATA_DIR, PreparedTips, _prep_covers_named_lookback

    if not (DATA_DIR / "m15_tatn_1095d.csv").is_file():
        import pytest

        pytest.skip("нет m15_tatn_1095d.csv")
    n = 3
    prep = PreparedTips(
        ts_ms=np.array([1, 2, 3], dtype=np.int64),
        z=np.zeros(n),
        spread=np.zeros(n),
        day_ord=np.zeros(n, dtype=np.int32),
        session=np.ones(n, dtype=np.bool_),
        trade_dates=["2026-01-22 06:59:00", "2026-01-22 07:00:00", "2026-09-05 15:00:00"],
        edge_i=np.array([0], dtype=np.int32),
        n=n,
        tatn=np.zeros(n),
        tatnp=np.zeros(n),
    )
    assert _prep_covers_named_lookback("m15_tatn_1095d.csv", prep) is False

    n = 3
    prep = PreparedTips(
        ts_ms=np.array([1, 2, 3], dtype=np.int64),
        z=np.zeros(n),
        spread=np.zeros(n),
        day_ord=np.zeros(n, dtype=np.int32),
        session=np.ones(n, dtype=np.bool_),
        trade_dates=["2026-01-22 06:59:00", "2026-01-22 07:00:00", "2026-09-05 15:00:00"],
        edge_i=np.array([0], dtype=np.int32),
        n=n,
        tatn=np.zeros(n),
        tatnp=np.zeros(n),
    )
    assert _prep_covers_named_lookback("m15_tatn_1095d.csv", prep) is False
    early = [
        {"timestampMs": 1, "tradeDate": "2023-09-01 10:00:00"},
        {"timestampMs": 2, "tradeDate": "2026-07-26 15:00:00"},
    ]
    tail = [
        {"timestampMs": 2, "tradeDate": "2026-07-26 15:00:00", "spreadPercent": 5.0},
        {"timestampMs": 3, "tradeDate": "2026-09-01 13:00:00"},
    ]
    out = merge_bars_by_timestamp(early, tail)
    assert [b["timestampMs"] for b in out] == [1, 2, 3]
    assert out[0]["tradeDate"].startswith("2023")
    assert out[-1]["tradeDate"].startswith("2026-09")
    assert out[1].get("spreadPercent") == 5.0
