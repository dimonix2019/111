"""MTLR/MTLRP Phase‑1 shadow: m15 levels + cuts (no orders)."""

from live.constants import (
    MTLR_SPREAD_ENTER_NARROW,
    MTLR_SPREAD_ENTER_WIDE,
    MTLR_SPREAD_EXIT_NARROW,
    MTLR_SPREAD_EXIT_WIDE,
    MTLR_SPREAD_WIDTH_NARROW_MAX,
    MTLR_SPREAD_WIDTH_WIDE_MIN,
)
from datetime import datetime
from zoneinfo import ZoneInfo

from live.mtlr_shadow import (
    MTLR_LEVELS,
    _should_skip_iss,
    classify_mtlr_spread,
    determine_mtlr_signal,
    mtlr_auto_execute,
    mtlr_bars_for_chart,
    mtlr_enabled,
    walk_mtlr_paper,
)

MSK = ZoneInfo("Europe/Moscow")
from live.signals import Position, Signal
from live.spread_levels import SpreadLevels, determine_spread_level_signal

import pandas as pd


def test_mtlr_levels_defaults():
    assert MTLR_LEVELS.enter_wide == 8.9
    assert MTLR_LEVELS.exit_wide == 8.4
    assert MTLR_LEVELS.enter_narrow == 3.2
    assert MTLR_LEVELS.exit_narrow == 4.3
    assert MTLR_SPREAD_ENTER_WIDE == 8.9
    assert MTLR_SPREAD_EXIT_WIDE == 8.4
    assert MTLR_SPREAD_ENTER_NARROW == 3.2
    assert MTLR_SPREAD_EXIT_NARROW == 4.3
    assert MTLR_SPREAD_WIDTH_NARROW_MAX == 4.52
    assert MTLR_SPREAD_WIDTH_WIDE_MIN == 6.48


def test_mtlr_regime_cuts_differ_from_tatn():
    # 4.0 is narrow for Mechel, transition for TATN (3.5/5.5).
    assert classify_mtlr_spread(4.0) == "narrow"
    # 5.0 is transition for Mechel; would be transition for TATN too.
    assert classify_mtlr_spread(5.0) == "transition"
    # 6.0 is transition for Mechel (wide starts >6.48), wide for TATN (>5.5).
    assert classify_mtlr_spread(6.0) == "transition"
    assert classify_mtlr_spread(7.0) == "wide"


def test_mtlr_enter_short_needs_mechel_wide():
    # Cross 8.9 while landing in Mechel-wide.
    assert (
        determine_mtlr_signal(8.8, 8.9, Position.FLAT)
        == Signal.ENTER_SHORT
    )
    # Cross 8.9 but still below Mechel wide cut → blocked.
    assert determine_mtlr_signal(8.8, 8.9, Position.FLAT, SpreadLevels(
        enter_wide=8.9, exit_wide=8.4, enter_narrow=3.2, exit_narrow=4.3
    )) == Signal.ENTER_SHORT
    # If we used TATN enter_wide 6.2 with Mechel cuts, 6.2 lands in transition → none.
    assert (
        determine_spread_level_signal(
            6.1,
            6.2,
            Position.FLAT,
            SpreadLevels(enter_wide=6.2, exit_wide=5.8, enter_narrow=3.2, exit_narrow=4.3),
            narrow_max=MTLR_SPREAD_WIDTH_NARROW_MAX,
            wide_min=MTLR_SPREAD_WIDTH_WIDE_MIN,
        )
        == Signal.NONE
    )


def test_mtlr_enter_long_narrow():
    assert (
        determine_mtlr_signal(3.3, 3.2, Position.FLAT) == Signal.ENTER_LONG
    )
    assert determine_mtlr_signal(3.1, 3.0, Position.FLAT) == Signal.NONE


def test_mtlr_exits():
    assert (
        determine_mtlr_signal(4.2, 4.3, Position.LONG) == Signal.EXIT_LONG
    )
    assert (
        determine_mtlr_signal(8.5, 8.4, Position.SHORT) == Signal.EXIT_SHORT
    )


def test_mtlr_auto_flag_reads_settings():
    assert mtlr_auto_execute({"mtlr_auto_execute": True}) is True
    assert mtlr_auto_execute({"mtlr_auto_execute": "1"}) is True
    assert mtlr_auto_execute({"mtlr_auto_execute": False}) is False
    assert mtlr_auto_execute({}) is False
    assert mtlr_enabled({"mtlr_enabled": "1"}) is True
    assert mtlr_enabled({"mtlr_enabled": "0"}) is False


def test_walk_mtlr_paper_enter_and_exit():
    rows = [
        ("2026-01-01 10:00:00", 5.0),  # transition
        ("2026-01-01 10:15:00", 8.8),  # wide, below enter
        ("2026-01-01 10:30:00", 9.0),  # ENTER_SHORT
        ("2026-01-01 10:45:00", 8.6),  # still short
        ("2026-01-01 11:00:00", 8.3),  # EXIT_SHORT
    ]
    df = pd.DataFrame(rows, columns=["timestamp", "spread_percent"])
    # Force settle: timestamps far in the past relative to MONITOR settle.
    out = walk_mtlr_paper(df, MTLR_LEVELS)
    assert out["position"] == "FLAT"
    assert out["last_signal"] == "EXIT_SHORT"
    assert out["edges_count"] >= 2
    sigs = [e["signal"] for e in out["recent_edges"]]
    assert "ENTER_SHORT" in sigs
    assert "EXIT_SHORT" in sigs


def test_mtlr_bars_6m_not_clipped_to_2500():
    """6М (180д) не должен обрезаться старым потолком 2500 m15 (~45д)."""
    start = pd.Timestamp("2026-01-01 10:00:00")
    rows = [
        (start + pd.Timedelta(minutes=15 * i), 5.0 + (i % 10) * 0.1)
        for i in range(3000)
    ]
    df = pd.DataFrame(rows, columns=["timestamp", "spread_percent"])
    bars = mtlr_bars_for_chart(df, days=180)
    assert len(bars) == 3000
    assert bars[0]["time"].startswith("2026-01-01")


def test_mtlr_bars_for_chart_ohlc():
    rows = [
        ("2026-01-01 10:00:00", 5.0),
        ("2026-01-01 10:15:00", 5.5),
        ("2026-01-01 10:30:00", 5.2),
    ]
    df = pd.DataFrame(rows, columns=["timestamp", "spread_percent"])
    bars = mtlr_bars_for_chart(df, days=30)
    assert len(bars) == 3
    assert bars[0]["source"] == "mtlr_m15"
    assert bars[1]["spread"] == 5.5
    assert bars[1]["spread_open"] == 5.0
    assert bars[1]["spread_high"] == 5.5
    assert bars[1]["spread_low"] == 5.0
    assert "timestampMs" in bars[0]


def test_skip_iss_only_when_last_bar_is_fresh():
    now = datetime(2026, 8, 17, 10, 46, tzinfo=MSK)
    stuck = pd.DataFrame(
        [{"timestamp": "2026-08-17 07:30:00", "spread_percent": 0.9}]
    )
    # Old 3h skip would freeze here until 10:30; 07:30 at 10:46 must refresh.
    assert _should_skip_iss(stuck, force=False, now=now) is False
    fresh = pd.DataFrame(
        [{"timestamp": "2026-08-17 10:30:00", "spread_percent": 1.1}]
    )
    assert _should_skip_iss(fresh, force=False, now=now) is True
    assert _should_skip_iss(fresh, force=True, now=now) is False
