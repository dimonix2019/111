"""Risk flags for spread-level trades (not Z).

Hold / overnight / clock stay as operational costs.
Z-based «|Z|<1» and «~порог Z» are replaced by:

- S≈вход — вход едва за уровнем (мало глубины)
- нет хода — за сутки спред не ушёл к выходу ≥0.2 п.п.
- S против — спред ушёл от выхода ≥0.2 п.п.
"""

from __future__ import annotations

from typing import Any

from live.constants import (
    DEFAULT_SPREAD_ENTER_NARROW,
    DEFAULT_SPREAD_ENTER_WIDE,
    DEFAULT_SPREAD_EXIT_NARROW,
    DEFAULT_SPREAD_EXIT_WIDE,
)
from live import constants as _constants

MTLR_SPREAD_ENTER_WIDE = getattr(_constants, "MTLR_SPREAD_ENTER_WIDE", 8.9)
MTLR_SPREAD_EXIT_WIDE = getattr(_constants, "MTLR_SPREAD_EXIT_WIDE", 8.4)
MTLR_SPREAD_ENTER_NARROW = getattr(_constants, "MTLR_SPREAD_ENTER_NARROW", 3.2)
MTLR_SPREAD_EXIT_NARROW = getattr(_constants, "MTLR_SPREAD_EXIT_NARROW", 4.3)

WEAK_ENTRY_PP = 0.15
NO_PROGRESS_PP = 0.20
AGAINST_PP = 0.20

_DEFAULT_LEVELS = {
    "enter_wide": DEFAULT_SPREAD_ENTER_WIDE,
    "exit_wide": DEFAULT_SPREAD_EXIT_WIDE,
    "enter_narrow": DEFAULT_SPREAD_ENTER_NARROW,
    "exit_narrow": DEFAULT_SPREAD_EXIT_NARROW,
}


def _f(v: Any) -> float | None:
    if v is None:
        return None
    try:
        x = float(v)
    except (TypeError, ValueError):
        return None
    if x != x:
        return None
    return x


def normalize_direction(direction: str | None) -> str:
    d = str(direction or "").upper()
    if "SHORT" in d:
        return "SHORT"
    if "LONG" in d:
        return "LONG"
    return ""


def levels_from_settings(settings: dict[str, Any] | None = None) -> dict[str, float]:
    s = settings or {}
    out = dict(_DEFAULT_LEVELS)

    def _pick(*keys: str, default: float) -> float:
        for k in keys:
            x = _f(s.get(k))
            if x is not None:
                return x
        return float(default)

    out["enter_wide"] = _pick("spread_enter_wide", "enter_wide", default=out["enter_wide"])
    out["exit_wide"] = _pick("spread_exit_wide", "exit_wide", default=out["exit_wide"])
    out["enter_narrow"] = _pick("spread_enter_narrow", "enter_narrow", default=out["enter_narrow"])
    out["exit_narrow"] = _pick("spread_exit_narrow", "exit_narrow", default=out["exit_narrow"])
    return out


def levels_for_trade(
    trade: dict[str, Any] | None = None,
    settings: dict[str, Any] | None = None,
) -> dict[str, float]:
    """TATN levels by default; MTLR pair uses mtlr_* (or Mechel defaults)."""
    blob = " ".join(
        str((trade or {}).get(k) or "")
        for k in ("pair_id", "pair", "ord_ticker", "ticker")
    ).upper()
    if "MTLR" in blob:
        s = settings or {}
        return {
            "enter_wide": _f(s.get("mtlr_enter_wide")) or MTLR_SPREAD_ENTER_WIDE,
            "exit_wide": _f(s.get("mtlr_exit_wide")) or MTLR_SPREAD_EXIT_WIDE,
            "enter_narrow": _f(s.get("mtlr_enter_narrow")) or MTLR_SPREAD_ENTER_NARROW,
            "exit_narrow": _f(s.get("mtlr_exit_narrow")) or MTLR_SPREAD_EXIT_NARROW,
        }
    return levels_from_settings(settings)


def spread_depth_pp(
    direction: str | None,
    entry_s: float | None,
    levels: dict[str, Any] | None = None,
) -> float | None:
    """How far past the entry level (positive = deeper extreme)."""
    d = normalize_direction(direction)
    s = _f(entry_s)
    lv = levels or _DEFAULT_LEVELS
    if s is None or not d:
        return None
    if d == "LONG":
        en = _f(lv.get("enter_narrow"))
        return (en - s) if en is not None else None
    ew = _f(lv.get("enter_wide"))
    return (s - ew) if ew is not None else None


def spread_progress_pp(
    direction: str | None,
    entry_s: float | None,
    s_now: float | None,
) -> float | None:
    """Move toward the exit level (positive = closer to TP/level exit)."""
    d = normalize_direction(direction)
    a = _f(entry_s)
    b = _f(s_now)
    if a is None or b is None or not d:
        return None
    if d == "LONG":
        return b - a
    return a - b


def spread_risk_flags(
    *,
    direction: str | None,
    entry_spread: float | None,
    spread_now: float | None,
    hold_hours: float | None,
    levels: dict[str, Any] | None = None,
) -> tuple[list[str], int]:
    """Extra flags/score from spread geometry. Hold/overnight are applied by the caller."""
    flags: list[str] = []
    score = 0
    lv = levels or _DEFAULT_LEVELS
    depth = spread_depth_pp(direction, entry_spread, lv)
    progress = spread_progress_pp(direction, entry_spread, spread_now)
    hold_h = _f(hold_hours) or 0.0

    # «едва за уровнем»: глубина ≥0 и <0.15 п.п. (отрицательная = не дошли до уровня — не этот флаг)
    if depth is not None and 0 <= depth < WEAK_ENTRY_PP and hold_h >= 6:
        flags.append("S≈вход")
        score += 1
    if progress is not None and progress <= -AGAINST_PP:
        flags.append("S против")
        score += 2
    elif progress is not None and progress < NO_PROGRESS_PP and hold_h >= 24:
        flags.append("нет хода")
        score += 1
    return flags, score


def risk_level_from_score(score: int) -> str:
    if score >= 6:
        return "Critical"
    if score >= 4:
        return "High"
    if score >= 3:
        return "Elevated"
    return "Ok"
