"""Closed-trade field parity Prod ↔ Test."""

from __future__ import annotations

from live.parity import (
    collect_sim_closed_trades,
    compare_trade_fields,
    _match_sim_trade,
)


def _bar(ms: int, z: float, sp: float, label: str) -> dict:
    return {
        "timestampMs": ms,
        "tradeDate": label,
        "zScore": z,
        "spreadPercent": sp,
        "tatnClose": 400.0,
        "tatnpClose": 390.0,
    }


def test_collect_sim_closed_trades_one_roundtrip():
    step = 15 * 60 * 1000
    t0 = 1_700_000_000_000
    # Tue session: flat → enter long → exit long
    bars = [
        _bar(t0, -1.2, 3.0, "2026-07-21 10:00"),
        _bar(t0 + step, -1.5, 2.5, "2026-07-21 10:15"),  # ENTER
        _bar(t0 + 2 * step, -0.5, 3.2, "2026-07-21 10:30"),  # EXIT
    ]
    trades = collect_sim_closed_trades(bars, entry=1.3, exit_z=1.2)
    assert len(trades) == 1
    t = trades[0]
    assert t["direction"] == "LONG"
    assert t["entry_time"] == "2026-07-21 10:15"
    assert t["exit_time"] == "2026-07-21 10:30"
    assert abs(float(t["entry_z"]) - (-1.5)) < 1e-6
    assert abs(float(t["pnl_pts"]) - 0.7) < 1e-6


def test_compare_trade_fields_detects_time_and_z():
    prod = {
        "direction": "LONG",
        "entry_time": "2026-07-21 10:30",
        "exit_time": "2026-07-21 10:45",
        "entry_z": -0.3,
        "exit_z": -0.5,
        "entry_spread": 4.7,
        "exit_spread": 4.8,
        "pnl_rub": -40,
        "execution_notional_rub": 70000,
    }
    test = {
        "direction": "LONG",
        "entry_time": "2026-07-21 10:15",
        "exit_time": "2026-07-21 10:30",
        "entry_z": -1.5,
        "exit_z": -0.5,
        "entry_spread": 2.5,
        "exit_spread": 3.2,
        "pnl_rub": 400,
        "execution_notional_rub": 70000,
    }
    diffs = compare_trade_fields(prod, test)
    fields = {d["field"] for d in diffs}
    assert "entry_time" in fields
    assert "exit_time" in fields
    assert "entry_z" in fields
    assert "entry_spread" in fields
    soft = {d["field"] for d in diffs if d.get("soft")}
    assert "pnl_rub" in soft


def test_match_sim_trade_by_entry_window():
    sim = [
        {
            "direction": "LONG",
            "entry_time": "2026-07-21 10:15",
            "exit_time": "2026-07-21 10:30",
            "entry_spread": 3.0,
        }
    ]
    prod = {
        "direction": "LONG",
        "entry_time": "2026-07-21 10:30",
        "exit_time": "2026-07-21 10:45",
        "entry_spread": 3.05,
    }
    assert _match_sim_trade(prod, sim) is not None
    far = {
        "direction": "LONG",
        "entry_time": "2026-07-21 12:00",
        "exit_time": "2026-07-21 12:15",
        "entry_spread": 3.0,
    }
    assert _match_sim_trade(far, sim) is None
    # ложный матч по выходу при другом входе/спреде — отсекаем
    wrong = {
        "direction": "LONG",
        "entry_time": "2026-07-21 12:30",
        "exit_time": "2026-07-21 10:30",
        "entry_spread": 4.5,
    }
    assert _match_sim_trade(wrong, sim) is None
