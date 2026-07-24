"""Open-trade history stats (M15 zsim)."""

from __future__ import annotations

from live.open_stats import compute_open_trade_stats


def test_hold_hours_intraday():
    from live.open_stats import _hold_hours_precise

    h = _hold_hours_precise("2023-11-17 17:30", "2023-11-17 19:00")
    assert abs(h - 1.5) < 1e-9


def test_compute_open_stats_long_smoke():
    from replay.replay_db import load_bars_from_db

    bars = load_bars_from_db() or []
    if len(bars) < 500:
        return
    s = compute_open_trade_stats(
        direction="LONG",
        entry_z=1.3,
        exit_z=1.2,
        notional_rub=10_000,
        leverage=7,
        slippage_spread_pts=0.12,
        bars=bars[-8000:],
    )
    assert s.get("ok") is True
    sm = s["summary"]
    assert sm["trade_count"] >= 1
    assert sm["median_hold_hours_winners"] is None or sm["median_hold_hours_winners"] >= 0
    assert len(s["typical_mtm"]) == 6
    assert len(s["spread_move"]) == 6
    assert len(s["p_profit"]) == 6
    assert s["mae"]["n"] >= 1
    assert len(s["overnight_share"]) == 4
    assert len(s["slip_sensitivity"]) == 3
    assert s["hit1"]["n"] >= 1
    assert s["hit2"]["n"] >= 1
