"""Z-edge signals — parity with MoexIssStrategy.determineZStrategySignal."""

from __future__ import annotations

from enum import Enum

M15_MS = 15 * 60 * 1000


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
    return (cur_ms - prev_ms) == M15_MS


def is_implausible_spread_jump(
    prev_spread: float | None,
    cur_spread: float | None,
    *,
    max_abs_pp: float = 1.5,
) -> bool:
    """Защита от битого LAST/tip: скачок спреда > max_abs_pp за один M15 — не AUTO-вход."""
    try:
        if prev_spread is None or cur_spread is None:
            return False
        return abs(float(cur_spread) - float(prev_spread)) > float(max_abs_pp)
    except (TypeError, ValueError):
        return False


def is_moex_equity_session_bar(trade_date: str | None) -> bool:
    """AUTO tip1m: пн–пт 07:00–23:50; сб/вс 10:00–18:59 МСК. Без 06:30/06:45."""
    from datetime import datetime

    s = str(trade_date or "").replace("T", " ").strip()
    if len(s) < 16:
        return False
    try:
        dt = datetime.strptime(s[:16], "%Y-%m-%d %H:%M")
    except ValueError:
        return False
    mins = dt.hour * 60 + dt.minute
    if dt.weekday() >= 5:
        return (10 * 60) <= mins < (19 * 60)
    return (7 * 60) <= mins < (23 * 60 + 50)


def is_bar_settled(
    bar_ms: int,
    now_ms: int,
    *,
    has_next_bar: bool,
    settle_sec: float = 45.0,
) -> bool:
    """
    Бар с меткой T = слот [T, T+15м). AUTO только после закрытия слота (+settle)
    либо когда в серии уже есть следующий бар (T+15).
    """
    if bar_ms <= 0 or now_ms <= 0:
        return False
    if has_next_bar:
        return True
    return now_ms >= bar_ms + M15_MS + int(settle_sec * 1000)


def bar_has_next(bars: list[dict], index: int) -> bool:
    if index < 0 or index >= len(bars) - 1:
        return False
    cur_ms = int(bars[index].get("timestampMs") or 0)
    next_ms = int(bars[index + 1].get("timestampMs") or 0)
    return is_consecutive_m15(cur_ms, next_ms)


def is_bar_settled_in_series(
    bars: list[dict],
    index: int,
    now_ms: int,
    *,
    settle_sec: float = 45.0,
) -> bool:
    if index < 0 or index >= len(bars):
        return False
    bar_ms = int(bars[index].get("timestampMs") or 0)
    return is_bar_settled(
        bar_ms,
        now_ms,
        has_next_bar=bar_has_next(bars, index),
        settle_sec=settle_sec,
    )


def last_settled_bar_index(
    bars: list[dict],
    now_ms: int,
    *,
    settle_sec: float = 45.0,
) -> int | None:
    for i in range(len(bars) - 1, -1, -1):
        if is_bar_settled_in_series(bars, i, now_ms, settle_sec=settle_sec):
            return i
    return None


def find_bar_index(bars: list[dict], bar_ms: int) -> int | None:
    for i, b in enumerate(bars):
        if int(b.get("timestampMs") or 0) == bar_ms:
            return i
    return None


def plan_monitor_catchup(
    bars: list[dict],
    last_proc_ms: int,
    *,
    max_edges: int = 64,
    now_ms: int | None = None,
    settle_sec: float = 45.0,
) -> tuple[str, list[tuple[dict, dict]]]:
    """
    Живой монитор с догоном consecutive-рёбер (parity APK collectZStrategy15m…SinceProcessedBar).

    Returns:
      ("bootstrap", []) — якорь на хвост, без сигналов
      ("up_to_date", []) — новых баров нет / tip ещё не settled
      ("live", [(prev, cur), ...]) — 1..max_edges consecutive рёбер после last_proc (AUTO)
      ("skip_gap", [...]) — дыра относительно last_proc: якорь вперёд без AUTO
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
        # last_proc старше первого бара в окне — нельзя восстановить prev.
        return "skip_gap", []

    # Цепочка только пока шаг ровно 15м и начинается с last_proc.
    pending: list[tuple[dict, dict]] = []
    expected_prev_ms = last_proc_ms
    for i in range(start_i, len(bars)):
        prev, cur = bars[i - 1], bars[i]
        prev_ms = int(prev.get("timestampMs") or 0)
        cur_ms = int(cur.get("timestampMs") or 0)
        if prev_ms != expected_prev_ms or not is_consecutive_m15(prev_ms, cur_ms):
            if not pending:
                # Сразу дыра — без AUTO (как раньше skip_gap).
                return "skip_gap", [(prev, cur)]
            break
        # Tip mid-bar: не отдаём в AUTO, ждём settle (как закрытый бар в Тесте).
        if now_ms is not None and not is_bar_settled_in_series(
            bars, i, now_ms, settle_sec=settle_sec
        ):
            break
        pending.append((prev, cur))
        expected_prev_ms = cur_ms
        if len(pending) >= max_edges:
            break

    if not pending:
        return "up_to_date", []
    # Несколько баров подряд после лага / рестарта — догоняем, не пропускаем вход.
    return "live", pending


def should_revise_none_to_signal(
    *,
    old_signal: str,
    new_signal: Signal,
    old_z: float,
    new_z: float,
    min_delta: float,
) -> bool:
    """Один late-revise: только NONE → ENTER/EXIT при существенном уточнении Z."""
    if old_signal != Signal.NONE.value:
        return False
    if new_signal == Signal.NONE:
        return False
    if not (new_z == new_z and old_z == old_z):  # NaN check
        return False
    return abs(new_z - old_z) >= min_delta
