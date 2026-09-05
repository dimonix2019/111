"""Spread-regime Z gate + thresholds (tip1m AUTO)."""

from live.signals import Position, Signal, determine_z_signal
from live.spread_regime import (
    REGIME_NARROW,
    REGIME_TRANSITION,
    REGIME_WIDE,
    classify_spread_pct,
    gate_signal,
    lock_fields_for_entry,
    resolve_thresholds,
)
from live.tip_touch_signals import collect_tip1m_sim_edges


def test_classify_cuts():
    assert classify_spread_pct(3.49) == REGIME_NARROW
    assert classify_spread_pct(3.5) == REGIME_TRANSITION
    assert classify_spread_pct(5.5) == REGIME_TRANSITION
    assert classify_spread_pct(5.51) == REGIME_WIDE
    assert classify_spread_pct(None) == "na"


def test_flat_narrow_thresholds():
    th = resolve_thresholds(
        regime_z_mode=True,
        classic_entry=1.6,
        classic_exit=1.3,
        spread=2.8,
        position=Position.FLAT,
    )
    assert th.regime == REGIME_NARROW
    assert th.entry == 1.0
    assert th.exit == 0.7
    assert th.allow_entry is True


def test_flat_wide_thresholds():
    th = resolve_thresholds(
        regime_z_mode=True,
        classic_entry=1.6,
        classic_exit=1.3,
        spread=6.2,
        position=Position.FLAT,
    )
    assert th.regime == REGIME_WIDE
    assert th.entry == 1.6
    assert th.exit == 1.3
    assert th.allow_entry is True


def test_transition_blocks_entry():
    th = resolve_thresholds(
        regime_z_mode=True,
        classic_entry=1.6,
        classic_exit=1.3,
        spread=4.5,
        position=Position.FLAT,
    )
    assert th.regime == REGIME_TRANSITION
    assert th.allow_entry is False
    sig = determine_z_signal(-0.9, -1.1, Position.FLAT, th.entry, th.exit)
    # wide display thresholds would still fire; gate must kill entry
    assert gate_signal(sig, th) == Signal.NONE
    # even with narrow thresholds crossed:
    sig2 = determine_z_signal(-0.9, -1.1, Position.FLAT, 1.0, 0.7)
    assert sig2 == Signal.ENTER_LONG
    assert gate_signal(sig2, th) == Signal.NONE


def test_exit_locked_at_entry_regime():
    open_t = {
        "entry_regime": REGIME_NARROW,
        "locked_entry_z": 1.0,
        "locked_exit_z": 0.7,
        "entry_spread": 2.5,
    }
    # Current spread is wide — exit still 0.7 from entry lock
    th = resolve_thresholds(
        regime_z_mode=True,
        classic_entry=1.6,
        classic_exit=1.3,
        spread=7.0,
        position=Position.LONG,
        open_trade=open_t,
    )
    assert th.locked_at_entry is True
    assert th.exit == 0.7
    assert th.entry == 1.0
    assert th.allow_entry is False


def test_regime_mode_off_uses_classic():
    th = resolve_thresholds(
        regime_z_mode=False,
        classic_entry=1.6,
        classic_exit=1.3,
        spread=2.0,
        position=Position.FLAT,
    )
    assert th.regime == "off"
    assert th.entry == 1.6
    assert th.exit == 1.3
    assert th.allow_entry is True


def test_lock_fields_for_entry():
    f = lock_fields_for_entry(2.2)
    assert f["entry_regime"] == REGIME_NARROW
    assert f["locked_entry_z"] == 1.0
    assert f["locked_exit_z"] == 0.7
    f2 = lock_fields_for_entry(4.0)
    assert f2["entry_regime"] == REGIME_TRANSITION
    assert f2["locked_exit_z"] is None


def test_collect_tip1m_sim_edges_transition_skip():
    # Flat transition: Z would enter on classic 1.6 but regime blocks.
    tips = [
        {
            "timestampMs": 1_000_000,
            "tradeDate": "2026-07-28 12:00:00",
            "zScore": -1.5,
            "spreadPercent": 4.2,
        },
        {
            "timestampMs": 1_060_000,
            "tradeDate": "2026-07-28 12:01:00",
            "zScore": -1.7,
            "spreadPercent": 4.1,
        },
    ]
    classic = collect_tip1m_sim_edges(tips, 1.6, 1.3, respect_live_signal=False)
    assert classic and classic[0]["signal"] == "ENTER_LONG"
    gated = collect_tip1m_sim_edges(
        tips, 1.6, 1.3, respect_live_signal=False, regime_z_mode=True
    )
    assert gated == []


def test_collect_tip1m_sim_edges_narrow_entry():
    tips = [
        {
            "timestampMs": 1_000_000,
            "tradeDate": "2026-07-28 12:00:00",
            "zScore": -0.9,
            "spreadPercent": 2.5,
        },
        {
            "timestampMs": 1_060_000,
            "tradeDate": "2026-07-28 12:01:00",
            "zScore": -1.05,
            "spreadPercent": 2.4,
        },
    ]
    # Classic 1.6 would NOT enter
    assert collect_tip1m_sim_edges(tips, 1.6, 1.3, respect_live_signal=False) == []
    # Narrow regime ±1.0 does
    edges = collect_tip1m_sim_edges(
        tips, 1.6, 1.3, respect_live_signal=False, regime_z_mode=True
    )
    assert len(edges) == 1
    assert edges[0]["signal"] == "ENTER_LONG"
    assert edges[0]["entry_z"] == 1.0
