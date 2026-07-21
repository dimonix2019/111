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


def is_moex_equity_session_bar(trade_date: str | None) -> bool:
    """TQBR: пн–пт, 07:00 ≤ t < 23:50 МСК (утро+основная+вечер). Без 06:30/06:45."""
    from datetime import datetime

    s = str(trade_date or "").replace("T", " ").strip()
    if len(s) < 16:
        return False
    try:
        dt = datetime.strptime(s[:16], "%Y-%m-%d %H:%M")
    except ValueError:
        return False
    if dt.weekday() >= 5:
        return False
    mins = dt.hour * 60 + dt.minute
    return (7 * 60) <= mins < (23 * 60 + 50)


def plan_monitor_catchup(
    bars: list[dict],
    last_proc_ms: int,
    *,
    max_edges: int = 64,
) -> tuple[str, list[tuple[dict, dict]]]:
    """
    Один «живой» шаг монитора — без реплея пропусков.

    Returns:
      ("bootstrap", []) — якорь на хвост, без сигналов
      ("up_to_date", []) — новых баров нет
      ("live", [(prev, cur)]) — ровно следующее consecutive-ребро после last_proc
      ("skip_gap", [...pending...]) — отстали >1 бар или дыра: якориться вперёд без AUTO
    """
    if not bars or len(bars) < 2:
        return "bootstrap", []
    if last_proc_ms <= 0:
        return "bootstrap", []

    start_i: int | None = None
    for i, b in enumerate(bars):
        ms = int(b.get("timestampMs") or 0)
        if ms > last_proc_ms:
            start_i = i
            break
    if start_i is None:
        return "up_to_date", []
    if start_i == 0:
        return "skip_gap", []

    pending: list[tuple[dict, dict]] = []
    for i in range(start_i, len(bars)):
        pending.append((bars[i - 1], bars[i]))
        if len(pending) >= max_edges:
            break
    if not pending:
        return "up_to_date", []

    prev, cur = pending[0]
    prev_ms = int(prev.get("timestampMs") or 0)
    cur_ms = int(cur.get("timestampMs") or 0)
    # Только если prev — ровно последний обработанный бар и шаг 15м.
    if prev_ms != last_proc_ms or not is_consecutive_m15(prev_ms, cur_ms):
        return "skip_gap", pending
    if len(pending) > 1:
        # Отстали на несколько баров — не догоняем сделки реплеем.
        return "skip_gap", pending
    return "live", [(prev, cur)]
