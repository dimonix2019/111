"""Parity smoke tests for live signal + lot sizing."""

from live.lot_sizing import compute_spread_quantity_lots
from live.signals import (
    Position,
    Signal,
    determine_z_signal,
    is_bar_settled,
    is_moex_equity_session_bar,
    plan_monitor_catchup,
    should_revise_none_to_signal,
)


def test_enter_long_on_cross():
    assert determine_z_signal(-1.2, -1.4, Position.FLAT, 1.3, 1.2) == Signal.ENTER_LONG


def test_enter_short_on_cross():
    assert determine_z_signal(1.2, 1.4, Position.FLAT, 1.3, 1.2) == Signal.ENTER_SHORT


def test_exit_long():
    assert determine_z_signal(-1.3, -1.1, Position.LONG, 1.3, 1.2) == Signal.EXIT_LONG


def test_lot_sizing_cash_only():
    r = compute_spread_quantity_lots(cash_rub=50_000, price_tatn=600, price_tatnp=560)
    assert r.quantity_lots >= 1
    assert r.go_per_lot_rub > 0


def _bar(ms: int, z: float, label: str) -> dict:
    return {"timestampMs": ms, "zScore": z, "tradeDate": label}


def test_plan_monitor_catchup_bootstrap():
    bars = [_bar(1, 0.0, "a"), _bar(2, -1.5, "b")]
    mode, edges = plan_monitor_catchup(bars, 0)
    assert mode == "bootstrap"
    assert edges == []


def test_plan_monitor_catchup_after_sleep():
    step = 15 * 60 * 1000
    bars = [
        _bar(step * 1, -1.4, "t1"),
        _bar(step * 2, -1.5, "t2"),
        _bar(step * 3, -1.7, "t3"),
        _bar(step * 4, -1.2, "t4"),
    ]
    # Отстали на 2 бара → live-догон (parity APK), не skip_gap
    mode, edges = plan_monitor_catchup(bars, step * 2)
    assert mode == "live"
    assert len(edges) == 2
    assert edges[0][1]["tradeDate"] == "t3"
    assert edges[1][1]["tradeDate"] == "t4"


def test_plan_monitor_catchup_hole_is_skip_gap():
    step = 15 * 60 * 1000
    bars = [
        _bar(step * 1, -1.4, "t1"),
        _bar(step * 2, -1.5, "t2"),
        # дыра: нет step*3
        _bar(step * 4, -1.2, "t4"),
    ]
    mode, edges = plan_monitor_catchup(bars, step * 2)
    assert mode == "skip_gap"


def test_plan_monitor_live_one_edge():
    step = 15 * 60 * 1000
    bars = [
        _bar(step * 1, -1.4, "t1"),
        _bar(step * 2, -1.5, "t2"),
        _bar(step * 3, -1.7, "t3"),
    ]
    mode, edges = plan_monitor_catchup(bars, step * 2)
    assert mode == "live"
    assert len(edges) == 1
    assert edges[0][1]["tradeDate"] == "t3"


def test_plan_monitor_catchup_up_to_date():
    step = 15 * 60 * 1000
    bars = [_bar(step, 0.0, "a"), _bar(step * 2, -1.0, "b")]
    mode, edges = plan_monitor_catchup(bars, step * 2)
    assert mode == "up_to_date"
    assert edges == []


def test_moex_session_rejects_preopen_0645():
    assert is_moex_equity_session_bar("2026-07-21 06:45") is False
    assert is_moex_equity_session_bar("2026-07-21 06:30") is False
    assert is_moex_equity_session_bar("2026-07-21 07:00") is True
    assert is_moex_equity_session_bar("2026-07-21 18:45") is True
    assert is_moex_equity_session_bar("2026-07-21 23:45") is True
    assert is_moex_equity_session_bar("2026-07-21 23:50") is False
    assert is_moex_equity_session_bar("2026-07-19 10:00") is True  # Sunday dealer
    assert is_moex_equity_session_bar("2026-07-19 09:00") is True
    assert is_moex_equity_session_bar("2026-07-19 19:00") is True
    assert is_moex_equity_session_bar("2026-07-18 18:59") is True  # Saturday
    assert is_moex_equity_session_bar("2026-07-18 19:00") is True


def test_bar_settled_needs_next_or_time():
    step = 15 * 60 * 1000
    t0 = step * 10
    # Mid-slot: not settled without next bar
    assert is_bar_settled(t0, t0 + 5 * 60 * 1000, has_next_bar=False, settle_sec=45) is False
    # After close + settle
    assert is_bar_settled(t0, t0 + step + 45_000, has_next_bar=False, settle_sec=45) is True
    # Next bar present → settled even mid-slot of next
    assert is_bar_settled(t0, t0 + 60_000, has_next_bar=True, settle_sec=45) is True


def test_plan_monitor_waits_for_unsettled_tip():
    step = 15 * 60 * 1000
    bars = [
        _bar(step * 1, -1.0, "t1"),
        _bar(step * 2, -1.2, "t2"),
        _bar(step * 3, -1.7, "t3"),  # tip mid-bar
    ]
    # now still inside t3 slot → wait (up_to_date), no AUTO on soft tip
    now = step * 3 + 60_000
    mode, edges = plan_monitor_catchup(bars, step * 2, now_ms=now, settle_sec=45)
    assert mode == "up_to_date"
    assert edges == []
    # after settle window → live
    now2 = step * 3 + step + 45_000
    mode2, edges2 = plan_monitor_catchup(bars, step * 2, now_ms=now2, settle_sec=45)
    assert mode2 == "live"
    assert len(edges2) == 1
    assert edges2[0][1]["tradeDate"] == "t3"


def test_should_revise_none_to_enter():
    assert should_revise_none_to_signal(
        old_signal="NONE",
        new_signal=Signal.ENTER_LONG,
        old_z=-0.97,
        new_z=-1.72,
        min_delta=0.08,
    )
    assert not should_revise_none_to_signal(
        old_signal="ENTER_LONG",
        new_signal=Signal.ENTER_LONG,
        old_z=-1.7,
        new_z=-1.8,
        min_delta=0.08,
    )
    assert not should_revise_none_to_signal(
        old_signal="NONE",
        new_signal=Signal.ENTER_LONG,
        old_z=-1.70,
        new_z=-1.75,
        min_delta=0.08,
    )
