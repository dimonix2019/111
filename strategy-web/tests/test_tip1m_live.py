"""Prod tip1m Mode B: consecutive 1m edges + TP exit semantics."""
from __future__ import annotations

from live.signals import Position, Signal, determine_z_signal
from live.tip_touch_signals import (
    collect_tip1m_sim_edges,
    is_consecutive_1m,
    plan_tip1m_catchup,
    should_exit_take_profit,
    tip1m_mtm_pct_of_deposit,
)


def _tip(td: str, ms: int, z: float, sp: float = 4.0) -> dict:
    return {
        "tradeDate": td,
        "timestampMs": ms,
        "zScore": z,
        "spreadPercent": sp,
    }


def test_consecutive_1m():
    assert is_consecutive_1m(1_000_000, 1_060_000)
    assert not is_consecutive_1m(1_000_000, 1_120_000)
    assert not is_consecutive_1m(0, 60_000)


def test_plan_tip1m_catchup_live_and_bootstrap():
    base = 1_700_000_000_000
    tips = [
        _tip(f"2026-07-25 10:{i:02d}:00", base + i * 60_000, 0.1 * i)
        for i in range(5)
    ]
    mode, edges = plan_tip1m_catchup(tips, 0)
    assert mode == "bootstrap"
    assert edges == []

    last = tips[1]["timestampMs"]
    mode, edges = plan_tip1m_catchup(tips, last, max_edges=10)
    assert mode == "live"
    assert len(edges) == 3
    assert edges[0][0]["timestampMs"] == last
    assert edges[0][1]["timestampMs"] == tips[2]["timestampMs"]

    mode, edges = plan_tip1m_catchup(tips, tips[-1]["timestampMs"])
    assert mode == "up_to_date"


def test_tip1m_edge_enter_short_like_testing():
    # Flat: prev Z < +entry and cur >= +entry → ENTER_SHORT (weekday session)
    tips = [
        _tip("2026-07-24 11:00:00", 1_000_000, 1.0),
        _tip("2026-07-24 11:01:00", 1_060_000, 1.7),
    ]
    edges = collect_tip1m_sim_edges(tips, entry=1.6, exit_z=1.3, respect_live_signal=False)
    assert len(edges) == 1
    assert edges[0]["signal"] == Signal.ENTER_SHORT.value


def test_determine_z_same_as_tip_geometry():
    sig = determine_z_signal(1.0, 1.7, Position.FLAT, 1.6, 1.3)
    assert sig == Signal.ENTER_SHORT
    sig = determine_z_signal(-1.0, -1.7, Position.FLAT, 1.6, 1.3)
    assert sig == Signal.ENTER_LONG


def test_tp_pct_matches_testing_leverage_semantics():
    # deposit 10k, lev 7, long, +0.2 pp → mtm = 10k*7*0.002 = 140; pct = 1.4%
    pct = tip1m_mtm_pct_of_deposit(
        direction="LONG",
        entry_spread=4.0,
        cur_spread=4.2,
        deposit_rub=10_000,
        leverage=7,
    )
    assert abs(pct - 1.4) < 1e-9
    assert should_exit_take_profit(
        position=Position.LONG,
        entry_spread=4.0,
        cur_spread=4.2,
        take_profit_pct=1.0,
        deposit_rub=10_000,
        leverage=7,
    )
    assert not should_exit_take_profit(
        position=Position.LONG,
        entry_spread=4.0,
        cur_spread=4.2,
        take_profit_pct=2.0,
        deposit_rub=10_000,
        leverage=7,
    )
    assert not should_exit_take_profit(
        position=Position.LONG,
        entry_spread=4.0,
        cur_spread=4.2,
        take_profit_pct=0,
        deposit_rub=10_000,
        leverage=7,
    )


def test_tp_short_side():
    # Short profits when spread falls
    assert should_exit_take_profit(
        position=Position.SHORT,
        entry_spread=4.2,
        cur_spread=4.0,
        take_profit_pct=1.0,
        deposit_rub=10_000,
        leverage=7,
    )
