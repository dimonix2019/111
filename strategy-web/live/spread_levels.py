"""Spread-% level signals for tip1m AUTO (no rolling μ/σ Z).

Point-in-time ``spread_percent`` = (TATN/TATNP−1)×100 on each 1m tip bar.

Regimes (cuts from ``spread_regime``):
  узкий S < 3.5 · переход 3.5…5.5 (no new entries) · широкий S > 5.5

Mean-reversion on spread:
  широкий → only Short: enter cross-up ``enter_wide``, exit cross-down ``exit_wide``
  узкий   → only Long:  enter cross-down ``enter_narrow``, exit cross-up ``exit_narrow``

While in position: exit on exit level; ignore opposite-regime entries.
Transition can still EXIT if the exit level is crossed.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any

from live.constants import (
    DEFAULT_SPREAD_ENTER_NARROW,
    DEFAULT_SPREAD_ENTER_WIDE,
    DEFAULT_SPREAD_EXIT_NARROW,
    DEFAULT_SPREAD_EXIT_WIDE,
    DEFAULT_SPREAD_LEVEL_MODE,
)
from live.signals import Position, Signal
from live.spread_regime import (
    REGIME_LABEL_RU,
    REGIME_NARROW,
    REGIME_TRANSITION,
    REGIME_WIDE,
    SPREAD_WIDTH_NARROW_MAX,
    SPREAD_WIDTH_WIDE_MIN,
    classify_spread_pct,
)


@dataclass(frozen=True)
class SpreadLevels:
    enter_wide: float = DEFAULT_SPREAD_ENTER_WIDE
    exit_wide: float = DEFAULT_SPREAD_EXIT_WIDE
    enter_narrow: float = DEFAULT_SPREAD_ENTER_NARROW
    exit_narrow: float = DEFAULT_SPREAD_EXIT_NARROW

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def parse_spread_level_mode(settings: dict[str, Any] | None) -> bool:
    """Primary AUTO path when ON (default)."""
    if not settings:
        return bool(DEFAULT_SPREAD_LEVEL_MODE)
    v = settings.get("spread_level_mode")
    if v is None:
        return bool(DEFAULT_SPREAD_LEVEL_MODE)
    if isinstance(v, bool):
        return v
    s = str(v).strip().lower()
    return s in ("1", "true", "yes", "on")


def levels_from_settings(settings: dict[str, Any] | None = None) -> SpreadLevels:
    s = settings or {}

    def _f(*keys: str, default: float) -> float:
        for key in keys:
            raw = s.get(key)
            if raw is None:
                continue
            try:
                return float(raw)
            except (TypeError, ValueError):
                continue
        return float(default)

    return SpreadLevels(
        enter_wide=_f("spread_enter_wide", "enter_wide", default=DEFAULT_SPREAD_ENTER_WIDE),
        exit_wide=_f("spread_exit_wide", "exit_wide", default=DEFAULT_SPREAD_EXIT_WIDE),
        enter_narrow=_f(
            "spread_enter_narrow", "enter_narrow", default=DEFAULT_SPREAD_ENTER_NARROW
        ),
        exit_narrow=_f(
            "spread_exit_narrow", "exit_narrow", default=DEFAULT_SPREAD_EXIT_NARROW
        ),
    )


def determine_spread_level_signal(
    previous_s: float | None,
    current_s: float,
    position: Position,
    levels: SpreadLevels | None = None,
) -> Signal:
    """Cross/touch on absolute spread % (same edge style as Z, but on S)."""
    if previous_s is None:
        return Signal.NONE
    try:
        prev = float(previous_s)
        cur = float(current_s)
    except (TypeError, ValueError):
        return Signal.NONE
    if not (prev == prev and cur == cur):  # NaN
        return Signal.NONE

    lv = levels or SpreadLevels()
    pos = position if isinstance(position, Position) else Position(str(position or "FLAT"))

    if pos == Position.FLAT:
        # Short only in wide: cross up through enter_wide (touch from below).
        if prev < lv.enter_wide and cur >= lv.enter_wide:
            if classify_spread_pct(cur) == REGIME_WIDE:
                return Signal.ENTER_SHORT
            return Signal.NONE
        # Long only in narrow: cross down through enter_narrow.
        if prev > lv.enter_narrow and cur <= lv.enter_narrow:
            if classify_spread_pct(cur) == REGIME_NARROW:
                return Signal.ENTER_LONG
            return Signal.NONE
        return Signal.NONE

    if pos == Position.LONG:
        # Exit Long: cross up through exit_narrow (toward center / wider).
        if prev < lv.exit_narrow and cur >= lv.exit_narrow:
            return Signal.EXIT_LONG
        return Signal.NONE

    if pos == Position.SHORT:
        # Exit Short: cross down through exit_wide.
        if prev > lv.exit_wide and cur <= lv.exit_wide:
            return Signal.EXIT_SHORT
        return Signal.NONE

    return Signal.NONE


def lock_fields_for_spread_entry(spread: float | None) -> dict[str, Any]:
    """Record entry regime; exit levels are fixed by side (not Z)."""
    reg = classify_spread_pct(spread)
    return {
        "entry_regime": reg,
        "locked_entry_z": None,
        "locked_exit_z": None,
    }


def desk_spread_levels_payload(
    settings: dict[str, Any],
    *,
    spread: float | None,
    position: Position | str = Position.FLAT,
) -> dict[str, Any]:
    lv = levels_from_settings(settings)
    cur_reg = classify_spread_pct(spread)
    pos_s = position.value if isinstance(position, Position) else str(position or "FLAT")
    flat = pos_s.upper() == "FLAT"
    allow_entry = cur_reg in (REGIME_NARROW, REGIME_WIDE)
    return {
        "spread_level_mode": parse_spread_level_mode(settings),
        "current_regime": cur_reg,
        "current_label_ru": REGIME_LABEL_RU.get(cur_reg, cur_reg),
        "spread": float(spread) if spread is not None else None,
        "levels": lv.as_dict(),
        "cuts": {
            "narrow_max": SPREAD_WIDTH_NARROW_MAX,
            "wide_min": SPREAD_WIDTH_WIDE_MIN,
        },
        "rules": {
            REGIME_NARROW: {
                "side": "Long",
                "enter": lv.enter_narrow,
                "exit": lv.exit_narrow,
                "enter_dir": "cross_down",
                "exit_dir": "cross_up",
            },
            REGIME_WIDE: {
                "side": "Short",
                "enter": lv.enter_wide,
                "exit": lv.exit_wide,
                "enter_dir": "cross_up",
                "exit_dir": "cross_down",
            },
            REGIME_TRANSITION: {"allow_entry": False},
        },
        "entry_blocked": flat and not allow_entry,
        "badge_ru": "спред-уровни",
    }
