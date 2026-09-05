"""Spread-geometry risk flags (replace Z<1 / ~порог)."""

from live.spread_risk import (
    levels_for_trade,
    spread_depth_pp,
    spread_progress_pp,
    spread_risk_flags,
)


def test_long_depth_past_narrow_entry():
    # Long enter 3.2; fill 3.10 → depth 0.10
    assert abs(spread_depth_pp("LONG", 3.10) - 0.10) < 1e-9


def test_short_depth_past_wide_entry():
    # Short enter 6.1; fill 6.35 → depth 0.25
    assert abs(spread_depth_pp("SHORT", 6.35) - 0.25) < 1e-9


def test_progress_long_toward_exit():
    assert abs(spread_progress_pp("LONG", 3.10, 3.40) - 0.30) < 1e-9


def test_progress_short_toward_exit():
    assert abs(spread_progress_pp("SHORT", 6.40, 6.10) - 0.30) < 1e-9


def test_weak_entry_needs_hold_and_shallow_depth():
    flags, pts = spread_risk_flags(
        direction="LONG",
        entry_spread=3.12,
        spread_now=3.20,
        hold_hours=7,
    )
    assert "S≈вход" in flags
    assert pts == 1


def test_weak_entry_not_if_deep():
    flags, pts = spread_risk_flags(
        direction="LONG",
        entry_spread=2.90,
        spread_now=3.10,
        hold_hours=8,
    )
    assert "S≈вход" not in flags
    assert pts == 0


def test_weak_entry_not_if_not_past_level():
    flags, _ = spread_risk_flags(
        direction="LONG",
        entry_spread=3.40,
        spread_now=3.45,
        hold_hours=8,
    )
    assert "S≈вход" not in flags


def test_against_overrides_no_progress():
    flags, pts = spread_risk_flags(
        direction="LONG",
        entry_spread=2.90,
        spread_now=2.60,
        hold_hours=30,
    )
    assert "S против" in flags
    assert "нет хода" not in flags
    assert pts == 2


def test_no_progress_after_a_day():
    flags, pts = spread_risk_flags(
        direction="SHORT",
        entry_spread=6.40,
        spread_now=6.35,
        hold_hours=25,
    )
    assert "нет хода" in flags
    assert pts == 1


def test_mtlr_levels_for_pair():
    lv = levels_for_trade({"pair_id": "MTLR"})
    assert abs(lv["enter_wide"] - 8.9) < 1e-9
    depth = spread_depth_pp("SHORT", 8.95, lv)
    assert depth is not None and abs(depth - 0.05) < 1e-9
    flags, _ = spread_risk_flags(
        direction="SHORT",
        entry_spread=8.95,
        spread_now=8.96,
        hold_hours=7,
        levels=lv,
    )
    assert "S≈вход" in flags
