"""Unit-тесты «ситуация 3D»: решение, clear по входу, блок входа, режимы."""

from __future__ import annotations

from live.force_close_3d import (
    activate_situation,
    decide_force_close_3d,
    entry_blocked_by_situation,
    should_force_close_3d,
    situation_cleared,
    step_situation,
)


def test_day2_no_decision():
    d = decide_force_close_3d(
        hold_days=2,
        side="Long",
        entry_spread=3.2,
        current_spread=3.0,
        exit_level=4.0,
        mtm=-500.0,
    )
    assert d["decision"] == "none"
    assert should_force_close_3d(
        hold_days=2,
        side="Long",
        entry_spread=3.2,
        current_spread=3.0,
        exit_level=4.0,
        mtm=-500.0,
        mode="force",
    ) is False


def test_day3_mtm_plus_hold():
    d = decide_force_close_3d(
        hold_days=3,
        side="Long",
        entry_spread=3.2,
        current_spread=3.5,
        exit_level=4.0,
        mtm=120.0,
    )
    assert d["decision"] == "hold"
    assert d["label"] == "держать"


def test_day3_long_far_from_exit_close_signal():
    d = decide_force_close_3d(
        hold_days=3,
        side="Long",
        entry_spread=3.2,
        current_spread=2.5,
        exit_level=4.0,
        mtm=-800.0,
    )
    assert d["decision"] == "close"
    assert d["label"] == "плохая ситуация"
    assert d["dist_exit_pp"] == 1.5
    # indicator: не режем
    assert should_force_close_3d(
        hold_days=3,
        side="Long",
        entry_spread=3.2,
        current_spread=2.5,
        exit_level=4.0,
        mtm=-800.0,
        mode="indicator",
    ) is False
    # force: режем
    assert should_force_close_3d(
        hold_days=3,
        side="Long",
        entry_spread=3.2,
        current_spread=2.5,
        exit_level=4.0,
        mtm=-800.0,
        mode="force",
    ) is True


def test_day3_long_adverse_close():
    d = decide_force_close_3d(
        hold_days=3,
        side="Long",
        entry_spread=3.2,
        current_spread=2.6,
        exit_level=4.0,
        mtm=-900.0,
    )
    assert d["decision"] == "close"
    assert d["adverse_pp"] == 0.6


def test_day3_long_near_wait():
    d = decide_force_close_3d(
        hold_days=3,
        side="Long",
        entry_spread=3.2,
        current_spread=3.3,
        exit_level=4.0,
        mtm=-200.0,
    )
    assert d["decision"] == "wait"
    assert d["label"] == "ждём 1–2 дня"


def test_day4_still_wait_if_borderline():
    d = decide_force_close_3d(
        hold_days=4,
        side="Short",
        entry_spread=6.2,
        current_spread=6.0,
        exit_level=5.8,
        mtm=-150.0,
    )
    assert d["decision"] == "wait"


def test_day5_still_minus_close():
    d = decide_force_close_3d(
        hold_days=5,
        side="Short",
        entry_spread=6.2,
        current_spread=6.0,
        exit_level=5.8,
        mtm=-100.0,
    )
    assert d["decision"] == "close"
    assert "день≥5" in d["reason"]


def test_day5_plus_hold():
    d = decide_force_close_3d(
        hold_days=5,
        side="Short",
        entry_spread=6.2,
        current_spread=5.9,
        exit_level=5.8,
        mtm=50.0,
    )
    assert d["decision"] == "hold"


def test_short_adverse_close():
    d = decide_force_close_3d(
        hold_days=3,
        side="Short",
        entry_spread=6.2,
        current_spread=6.8,
        exit_level=5.8,
        mtm=-700.0,
    )
    assert d["decision"] == "close"
    assert d["adverse_pp"] == 0.6


def test_situation_cleared_by_entry_not_exit():
    # Long: снятие при ≤ enter 3.2; зона выхода 4.0 сама по себе НЕ снимает.
    assert situation_cleared(side="Long", current_spread=3.2, entry_level=3.2)
    assert situation_cleared(side="Long", current_spread=3.0, entry_level=3.2)
    assert not situation_cleared(side="Long", current_spread=3.5, entry_level=3.2)
    assert not situation_cleared(side="Long", current_spread=4.0, entry_level=3.2)
    # Short: снятие при ≥ enter 6.2; зона выхода 5.8 НЕ снимает.
    assert situation_cleared(side="Short", current_spread=6.2, entry_level=6.2)
    assert situation_cleared(side="Short", current_spread=6.5, entry_level=6.2)
    assert not situation_cleared(side="Short", current_spread=6.0, entry_level=6.2)
    assert not situation_cleared(side="Short", current_spread=5.8, entry_level=6.2)


def test_situation_cleared_legacy_toward_exit():
    assert situation_cleared(
        side="Long", current_spread=4.0, entry_level=4.0, toward_entry=False
    )
    assert situation_cleared(
        side="Short", current_spread=5.5, entry_level=5.8, toward_entry=False
    )


def test_entry_blocked_same_side_only():
    assert entry_blocked_by_situation(
        active=True, situation_side="Short", want_side="Short"
    )
    assert not entry_blocked_by_situation(
        active=True, situation_side="Short", want_side="Long"
    )
    assert not entry_blocked_by_situation(
        active=False, situation_side="Short", want_side="Short"
    )


def test_step_situation_indicator_no_force():
    st, force = step_situation(
        None,
        enabled=True,
        mode="indicator",
        flat=False,
        open_side="Short",
        hold_days=3,
        entry_spread=6.2,
        current_spread=6.8,
        exit_level=5.8,
        entry_level=6.2,
        mtm=-700.0,
    )
    assert force is False
    assert st is not None and st["active"]
    assert st["side"] == "Short"
    assert st["entry_blocked"] is True
    assert st["entry_level"] == 6.2
    assert "входу" in st["clear_hint"]

    # Снятие по выходу 5.7 — НЕ должно (нужен возврат ≥ 6.2)
    st_mid, force_mid = step_situation(
        st,
        enabled=True,
        mode="indicator",
        flat=True,
        open_side=None,
        hold_days=None,
        entry_spread=None,
        current_spread=5.7,
        exit_level=5.8,
        entry_level=6.2,
        mtm=None,
    )
    assert force_mid is False
    assert st_mid is not None and st_mid["active"]

    # Снятие: спред снова у входа Short ≥ 6.2
    st2, force2 = step_situation(
        st,
        enabled=True,
        mode="indicator",
        flat=True,
        open_side=None,
        hold_days=None,
        entry_spread=None,
        current_spread=6.25,
        exit_level=5.8,
        entry_level=6.2,
        mtm=None,
    )
    assert force2 is False
    assert st2 is None


def test_step_situation_force_cuts():
    st, force = step_situation(
        None,
        enabled=True,
        mode="force",
        flat=False,
        open_side="Long",
        hold_days=3,
        entry_spread=3.2,
        current_spread=2.5,
        exit_level=4.0,
        entry_level=3.2,
        mtm=-800.0,
    )
    assert force is True
    assert st is not None and st["active"]
    assert st["entry_level"] == 3.2


def test_activate_situation_hints():
    act = activate_situation(
        side="Long",
        decision_row={"decision": "close", "label": "плохая ситуация", "reason": "x"},
        current_spread=2.5,
        exit_level=4.0,
        entry_level=3.2,
        mode="indicator",
    )
    assert "Long" in act["clear_hint"]
    assert "3,2" in act["clear_hint"] or "3.2" in act["clear_hint"]
    assert "входу" in act["clear_hint"]
    assert "не режем" in act["action"]
    assert act["entry_level"] == 3.2
