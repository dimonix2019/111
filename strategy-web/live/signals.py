"""Z-edge signals — parity with MoexIssStrategy.determineZStrategySignal."""

from __future__ import annotations

from enum import Enum


class Position(str, Enum):
    FLAT = "FLAT"
    LONG = "LONG"
    SHORT = "SHORT"


class Signal(str, Enum):
    NONE = "NONE"
    ENTER_LONG = "ENTER_LONG"
    ENTER_SHORT = "ENTER_SHORT"
    EXIT_LONG = "EXIT_LONG"
    EXIT_SHORT = "EXIT_SHORT"


def determine_z_signal(
    previous_z: float | None,
    current_z: float,
    position: Position,
    entry: float,
    exit_: float,
) -> Signal:
    if previous_z is None:
        return Signal.NONE
    prev = previous_z
    if position == Position.FLAT:
        if prev > -entry and current_z <= -entry:
            return Signal.ENTER_LONG
        if prev < entry and current_z >= entry:
            return Signal.ENTER_SHORT
        return Signal.NONE
    if position == Position.LONG:
        if prev < -exit_ and current_z >= -exit_:
            return Signal.EXIT_LONG
        return Signal.NONE
    if position == Position.SHORT:
        if prev > exit_ and current_z <= exit_:
            return Signal.EXIT_SHORT
        return Signal.NONE
    return Signal.NONE


def is_consecutive_m15(prev_ms: int, cur_ms: int) -> bool:
    if prev_ms <= 0 or cur_ms <= 0:
        return False
    return (cur_ms - prev_ms) == 15 * 60 * 1000
