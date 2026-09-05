"""Вариант 2: база + добор 2/7 (Testing sim, не Prod AUTO)."""
from __future__ import annotations

import numpy as np

from replay.tip_touch import PreparedTips, run_base_plus_addon, run_touch_1m_trades


def _prep(dates, spread) -> PreparedTips:
    n = len(dates)
    return PreparedTips(
        ts_ms=np.arange(n, dtype=np.int64) * 60_000,
        z=np.zeros(n, dtype=np.float64),
        spread=np.asarray(spread, dtype=np.float64),
        day_ord=np.zeros(n, dtype=np.int32),
        session=np.ones(n, dtype=bool),
        trade_dates=list(dates),
        edge_i=np.arange(1, n, dtype=np.int32),
        n=n,
    )


def _closed(result):
    return [t for t in result["trades"] if t.get("status") == "Закрыта"]


def test_addon_long_while_base_open():
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
        "2024-01-02 10:06",
    ]
    # 4.5→3.2 база Long; →2.0 добор; →3.2 выход добора; →4.0 выход базы.
    # ТП=99 — проверяем именно уровень OR (при ТП 2% сработал бы tp раньше).
    spread = [4.5, 3.2, 2.0, 3.2, 4.0, 4.0, 4.0]
    r = run_base_plus_addon(
        prep=_prep(dates, spread), slip=0.02, notional=10_000.0, take_profit_pct=99.0
    )
    closed = _closed(r)
    assert r["summary"]["addonOpens"] == 1
    assert r["summary"]["peakLegs"] == 2
    tags = [t["tag"] for t in closed]
    assert tags == ["addon", "main"]
    addon = closed[0]
    main = closed[1]
    assert addon["direction"] == "Long"
    assert addon["exitReason"] == "addon_exit"
    assert abs(addon["exitSpread"] - (3.2 - 0.02)) < 1e-9
    assert main["direction"] == "Long"
    assert main["exitReason"] == "spread_exit"


def test_addon_short_while_base_open():
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
        "2024-01-02 10:06",
    ]
    # 5.5→6.2 база Short; →7.0 добор; →6.2 выход добора; →5.8 выход базы.
    spread = [5.5, 6.2, 7.0, 6.2, 5.8, 5.8, 5.8]
    r = run_base_plus_addon(
        prep=_prep(dates, spread), slip=0.02, notional=10_000.0, take_profit_pct=99.0
    )
    closed = _closed(r)
    assert r["summary"]["addonOpens"] == 1
    tags = [t["tag"] for t in closed]
    assert tags == ["addon", "main"]
    assert closed[0]["direction"] == "Short"
    assert closed[0]["exitReason"] == "addon_exit"
    assert abs(closed[0]["exitSpread"] - (6.2 + 0.02)) < 1e-9
    assert closed[1]["direction"] == "Short"


def test_addon_does_not_enter_when_base_flat():
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
    ]
    # Уже узкий (2.5), касание 2% без входа базы (нет пересечения 3.2 сверху).
    spread = [2.5, 2.0, 2.1, 2.2, 2.3]
    r = run_base_plus_addon(prep=_prep(dates, spread), slip=0.02, notional=10_000.0)
    assert r["summary"]["addonOpens"] == 0
    assert r["summary"]["trades"] == 0
    assert r["summary"]["openCount"] == 0


def test_addon_off_path_has_no_second_leg():
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
        "2024-01-02 10:06",
    ]
    spread = [4.5, 3.2, 2.0, 3.2, 4.0, 4.0, 4.0]
    prep = _prep(dates, spread)
    base = run_touch_1m_trades(
        prep, 1.6, 1.3, slip=0.02, notional=10_000.0,
        spread_level_mode=True,
    )
    addon = run_base_plus_addon(prep, slip=0.02, notional=10_000.0)
    assert base["summary"]["trades"] == 1
    assert addon["summary"]["trades"] == 2
    assert addon["summary"]["addonOpens"] == 1


def test_addon_compound_sizes_next_leg_after_closed_pnl():
    """Капит. ON: после закрытой прибыли следующая база/добор > номинала."""
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        # вторая база + добор
        "2024-01-02 10:05",
        "2024-01-02 10:06",
        "2024-01-02 10:07",
        "2024-01-02 10:08",
        "2024-01-02 10:09",
    ]
    # цикл1: Long 4.5→3.2→2→3.2(добор out)→4(база out); цикл2 снова Long
    spread = [4.5, 3.2, 2.0, 3.2, 4.0, 4.5, 3.2, 2.0, 3.2, 4.0]
    flat = run_base_plus_addon(
        prep=_prep(dates, spread), slip=0.02, notional=10_000.0, compound=False
    )
    cap = run_base_plus_addon(
        prep=_prep(dates, spread), slip=0.02, notional=10_000.0, compound=True
    )
    assert flat["params"]["compound"] is False
    assert cap["params"]["compound"] is True
    flat_closed = _closed(flat)
    cap_closed = _closed(cap)
    assert len(flat_closed) >= 4
    assert len(cap_closed) >= 4
    # первая база одинаковая
    assert abs(flat_closed[1]["notional"] - 10_000.0) < 1e-6
    assert abs(cap_closed[1]["notional"] - 10_000.0) < 1e-6
    # вторая база: без капит. снова 10k; с капит. больше (после прибыли цикла 1)
    flat_second_main = next(t for t in flat_closed if t["tag"] == "main" and t["index"] > 2)
    cap_second_main = next(t for t in cap_closed if t["tag"] == "main" and t["index"] > 2)
    assert abs(flat_second_main["notional"] - 10_000.0) < 1e-6
    assert cap_second_main["notional"] > 10_000.0
    # PnL с капит. должен отличаться (больше экспозиция на 2-м цикле)
    assert abs(cap["summary"]["pnlRub"] - flat["summary"]["pnlRub"]) > 1.0


def test_extra_same_bar_as_base_dump_through_1():
    """Экстра независима: бар базы 4.5→−1.7 сразу открывает Long и экстра."""
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
    ]
    spread = [4.5, -1.7, 2.05, 2.05, 4.0, 4.0]
    r = run_base_plus_addon(
        prep=_prep(dates, spread),
        slip=0.02,
        notional=10_000.0,
        enable_addon=True,
        enable_extreme=True,
    )
    closed = _closed(r)
    tags = [t["tag"] for t in closed]
    assert r["summary"]["extraOpens"] == 1
    assert "extra" in tags
    extra = next(t for t in closed if t["tag"] == "extra")
    assert extra["direction"] == "Long"
    assert extra["entryDate"].startswith("2024-01-02 10:01")


def test_extra_not_in_zone_at_28():
    """Long у 2.8 без зоны ≤1 — экстра не входит."""
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
        "2024-01-02 10:06",
    ]
    spread = [4.5, 3.2, 2.8, 2.8, 4.0, 4.0, 4.0]
    r = run_base_plus_addon(
        prep=_prep(dates, spread),
        slip=0.02,
        notional=10_000.0,
        enable_addon=True,
        enable_extreme=True,
    )
    assert r["summary"]["extraOpens"] == 0


def test_ref_100k_scales_legs_to_live_deposits():
    """К 100 000: те же сделки, база 40к / добор 30к, без роста лота."""
    from replay.tip_touch import apply_ref_100k_metrics

    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
        "2024-01-02 10:06",
    ]
    spread = [4.5, 3.2, 2.0, 3.2, 4.0, 4.0, 4.0]
    prep = _prep(dates, spread)
    toy = apply_ref_100k_metrics(
        run_base_plus_addon(prep=prep, slip=0.02, notional=10_000.0, compound=False)
    )
    live = run_base_plus_addon(
        prep=prep,
        slip=0.02,
        notional=40_000.0,
        compound=False,
        main_notional=40_000.0,
        addon_notional=30_000.0,
    )
    assert toy["summary"]["retPctFlatOf100k"] is not None
    live_pct = 100.0 * float(live["summary"]["pnlRub"]) / 100_000.0
    assert abs(toy["summary"]["retPctFlatOf100k"] - live_pct) < 0.6
    cap = apply_ref_100k_metrics(
        run_base_plus_addon(prep=prep, slip=0.02, notional=10_000.0, compound=True)
    )
    assert abs(cap["summary"]["retPctFlatOf100k"] - toy["summary"]["retPctFlatOf100k"]) < 0.05
    main_ref = next(t for t in _closed(toy) if t["tag"] == "main")
    assert main_ref["netRef100k"] is not None
    assert abs(main_ref["netRef100k"] / main_ref["net"] - 4.0) < 0.05


def test_thin_chart_bars_keeps_last_in_bucket():
    from replay.tip_touch import _thin_chart_bars

    bars = [
        {"timestampMs": 1_000_000 + i * 60_000, "i": i}
        for i in range(20)
    ]
    out, step = _thin_chart_bars(bars, max_bars=4)
    assert step >= 5
    assert len(out) <= 20
    assert out[-1]["i"] == 19


def test_thin_chart_bars_repairs_zero_timestamp_date_only():
    from replay.replay_data import parse_ts_ms
    from replay.tip_touch import _repair_chart_timestamp_ms, _thin_chart_bars

    repaired = _repair_chart_timestamp_ms(0, "2026-03-03")
    assert repaired == parse_ts_ms("2026-03-03")
    assert repaired > 1_000_000_000_000

    bars = [
        {"timestampMs": 0, "tradeDate": "2026-03-03", "i": 0},
        {
            "timestampMs": parse_ts_ms("2026-03-03 12:00"),
            "tradeDate": "2026-03-03 12:00",
            "i": 1,
        },
        {"timestampMs": 0, "tradeDate": "", "i": 2},
    ]
    out, step = _thin_chart_bars(bars, max_bars=100)
    assert step == 1
    assert all(int(b["timestampMs"]) > 0 for b in out)
    assert [b["i"] for b in out] == [0, 1]


def test_select_chart_bar_indices_covers_full_span():
    from replay.tip_touch import _select_chart_bar_indices

    n = 50_000
    ts = np.arange(n, dtype=np.int64) * 60_000
    idx, step = _select_chart_bar_indices(ts, 0, n, max_bars=1_000)
    assert step >= 5
    assert 2 <= len(idx) <= 1_000
    assert int(idx[0]) == 0
    assert int(idx[-1]) == n - 1
    # Не «хвост 90д»: есть точки из первой и второй половины ряда.
    assert int(idx[len(idx) // 4]) < n // 2


def test_risk_policy_skip_weak_entry():
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
    ]
    # Cross Long 3.2 at 3.18 (fill 3.20 → глубина 0), затем выход 4.0.
    spread = [3.45, 3.18, 3.18, 4.05, 4.05, 4.05]
    prep = _prep(dates, spread)
    base = run_base_plus_addon(prep, slip=0.02, notional=10_000.0)
    skipped = run_base_plus_addon(
        prep, slip=0.02, notional=10_000.0, risk_policy={"skip_weak_entry": True}
    )
    assert any(t.get("tag") == "main" for t in _closed(base))
    assert not any(t.get("tag") == "main" for t in _closed(skipped))


def test_risk_policy_exit_spread_against():
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
        "2024-01-02 10:06",
    ]
    # Long 3.2, затем спред против ≥0.20 п.п., потом выход 4.0.
    spread = [3.45, 3.18, 2.90, 2.90, 4.05, 4.05, 4.05]
    prep = _prep(dates, spread)
    cut = run_base_plus_addon(
        prep, slip=0.02, notional=10_000.0, risk_policy={"exit_spread_against": True}
    )
    mains = [t for t in _closed(cut) if t.get("tag") == "main"]
    assert mains
    assert mains[0]["exitReason"] == "risk_against"


def test_ui_trade_risk_score_red_zone():
    from replay.tip_touch import ui_trade_risk_score

    r = ui_trade_risk_score(
        direction="Short",
        entry_td="2025-06-02 11:00:00",
        now_td="2025-06-05 12:00:00",
        entry_spread=6.40,
        now_spread=6.70,
        overnight_rub=120.0,
        levels={"enter_wide": 6.2, "exit_wide": 5.8, "enter_narrow": 3.2, "exit_narrow": 4.0},
    )
    assert ">2д" in r["flags"]
    assert "Ovn100" in r["flags"]
    assert "S против" in r["flags"]
    assert r["score"] >= 4
    assert r["isRed"] is True


def test_addon_or_tp_before_level():
    """OR: ТП 2% срабатывает раньше уровня 3.2."""
    from replay.tip_touch import effective_addon_extra_tp_pct

    assert effective_addon_extra_tp_pct(0.0, tag="addon") == 2.0
    assert effective_addon_extra_tp_pct(1.5, tag="addon") == 1.5
    assert effective_addon_extra_tp_pct(0.0, tag="main") == 0.0

    # База Long на 3.2; добор на 2.0; затем резкий ход вверх к ~2.7 без касания 3.2 —
    # при плече 7 и депозите 10k порог ТП 2% ≈ +0.3 п.п. → закрытие по tp.
    dates = [f"2024-01-02 10:{m:02d}" for m in range(0, 12)]
    # 4.5→3.2 база; →2.0 добор; держим 2.0..2.05; скачок 2.9 (ещё <3.2) → TP
    spread = [4.5, 3.2, 2.0, 2.0, 2.01, 2.02, 2.03, 2.04, 2.05, 2.9, 2.9, 2.9]
    r = run_base_plus_addon(
        prep=_prep(dates, spread),
        slip=0.02,
        notional=10_000.0,
        take_profit_pct=2.0,
        enable_addon=True,
    )
    closed = _closed(r)
    addon = [t for t in closed if t.get("tag") == "addon"]
    assert addon, closed
    assert addon[0]["exitReason"] == "tp"
    assert addon[0]["exitSpread"] < 3.2


def test_addon_or_level_when_tp_off_still_has_floor_tp():
    """Общий ТП=0: добор всё равно OR с полом 2% (не только уровень)."""
    dates = [f"2024-01-02 10:{m:02d}" for m in range(0, 12)]
    spread = [4.5, 3.2, 2.0, 2.0, 2.01, 2.02, 2.03, 2.04, 2.05, 2.9, 2.9, 2.9]
    r = run_base_plus_addon(
        prep=_prep(dates, spread),
        slip=0.02,
        notional=10_000.0,
        take_profit_pct=0.0,
        enable_addon=True,
    )
    closed = _closed(r)
    addon = [t for t in closed if t.get("tag") == "addon"]
    assert addon, closed
    assert addon[0]["exitReason"] == "tp"


def test_addon_or_level_exit_when_no_tp_hit():
    """OR: при недостижимом ТП — выход по уровню 3.2."""
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
        "2024-01-02 10:06",
    ]
    spread = [4.5, 3.2, 2.0, 3.2, 4.0, 4.0, 4.0]
    r = run_base_plus_addon(
        prep=_prep(dates, spread),
        slip=0.02,
        notional=10_000.0,
        take_profit_pct=99.0,
        enable_addon=True,
    )
    closed = _closed(r)
    addon = [t for t in closed if t.get("tag") == "addon"][0]
    assert addon["exitReason"] == "addon_exit"

