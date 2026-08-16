"""Spread-width regimes for tip1m AUTO Z thresholds.

Cuts match Testing UI (``classifySpreadWidthRegime`` / ``_regime_z_sweep``):
  узкий  < 3.5% · переход 3.5…5.5% · широкий > 5.5%

Exit thresholds are **locked at entry regime** (stable while holding).
Transition: no new entries; open positions still manage/exit.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any

from live.signals import Position, Signal

# Absolute spread % cuts (same as replay-sim.js / _regime_z_sweep.py).
SPREAD_WIDTH_NARROW_MAX = 3.5
SPREAD_WIDTH_WIDE_MIN = 5.5

REGIME_NARROW = "narrow"
REGIME_TRANSITION = "transition"
REGIME_WIDE = "wide"
REGIME_NA = "na"
REGIME_OFF = "off"

# Fixed Z by regime (Prod tip1m AUTO when regime_z_mode ON).
REGIME_Z: dict[str, tuple[float, float]] = {
    REGIME_NARROW: (1.0, 0.7),
    REGIME_WIDE: (1.6, 1.3),
}

REGIME_LABEL_RU: dict[str, str] = {
    REGIME_NARROW: "узкий",
    REGIME_TRANSITION: "переход",
    REGIME_WIDE: "широкий",
    REGIME_NA: "—",
    REGIME_OFF: "выкл",
}


@dataclass(frozen=True)
class RegimeThresholds:
    regime: str
    entry: float
    exit: float
    allow_entry: bool
    locked_at_entry: bool
    label_ru: str

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def classify_spread_pct(spread: float | None) -> str:
    """Return narrow|transition|wide|na from absolute spread %."""
    try:
        if spread is None:
            return REGIME_NA
        sp = float(spread)
    except (TypeError, ValueError):
        return REGIME_NA
    if sp != sp:  # NaN
        return REGIME_NA
    if sp < SPREAD_WIDTH_NARROW_MAX:
        return REGIME_NARROW
    if sp > SPREAD_WIDTH_WIDE_MIN:
        return REGIME_WIDE
    return REGIME_TRANSITION


def z_for_regime(regime: str) -> tuple[float, float] | None:
    """(entry, exit) for narrow/wide; None for transition/na/off."""
    return REGIME_Z.get(regime)


def parse_regime_z_mode(settings: dict[str, Any] | None) -> bool:
    """Legacy Z-by-regime. Default OFF — spread levels are primary AUTO."""
    from live.constants import DEFAULT_REGIME_Z_MODE

    if not settings:
        return bool(DEFAULT_REGIME_Z_MODE)
    v = settings.get("regime_z_mode")
    if v is None:
        return bool(DEFAULT_REGIME_Z_MODE)
    if isinstance(v, bool):
        return v
    s = str(v).strip().lower()
    return s in ("1", "true", "yes", "on")


def _bar_spread(bar: dict[str, Any] | None) -> float | None:
    if not isinstance(bar, dict):
        return None
    sp = bar.get("spreadPercent")
    if sp is None:
        sp = bar.get("spread")
    try:
        return float(sp) if sp is not None else None
    except (TypeError, ValueError):
        return None


def resolve_thresholds(
    *,
    regime_z_mode: bool,
    classic_entry: float,
    classic_exit: float,
    spread: float | None,
    position: Position | str = Position.FLAT,
    open_trade: dict[str, Any] | None = None,
) -> RegimeThresholds:
    """Effective tip1m Z for AUTO / checklist / desk.

    While in position and regime mode ON: prefer ``locked_exit_z`` /
    ``entry_regime`` on the open trade (exit locked at entry regime).
    """
    pos = position if isinstance(position, Position) else Position(str(position or "FLAT"))
    ce = float(classic_entry)
    cx = float(classic_exit)

    if not regime_z_mode:
        return RegimeThresholds(
            REGIME_OFF, ce, cx, True, False, REGIME_LABEL_RU[REGIME_OFF]
        )

    if pos in (Position.LONG, Position.SHORT) and open_trade:
        locked_exit = open_trade.get("locked_exit_z")
        entry_reg = str(open_trade.get("entry_regime") or "") or None
        locked_entry = open_trade.get("locked_entry_z")
        if locked_exit is not None:
            try:
                lx = float(locked_exit)
            except (TypeError, ValueError):
                lx = cx
            if entry_reg not in REGIME_Z:
                # infer from entry_spread if column missing/legacy
                entry_reg = classify_spread_pct(open_trade.get("entry_spread"))
            try:
                le = float(locked_entry) if locked_entry is not None else (
                    REGIME_Z.get(entry_reg or "", (ce, cx))[0]
                )
            except (TypeError, ValueError):
                le = ce
            return RegimeThresholds(
                entry_reg or REGIME_NA,
                le,
                lx,
                False,
                True,
                REGIME_LABEL_RU.get(entry_reg or "", entry_reg or "—"),
            )
        # Legacy open without lock: classify from entry_spread, else current.
        entry_reg = classify_spread_pct(
            open_trade.get("entry_spread")
            if open_trade.get("entry_spread") is not None
            else spread
        )
        pair = z_for_regime(entry_reg)
        if pair:
            return RegimeThresholds(
                entry_reg,
                pair[0],
                pair[1],
                False,
                True,
                REGIME_LABEL_RU[entry_reg],
            )
        return RegimeThresholds(
            entry_reg,
            ce,
            cx,
            False,
            True,
            REGIME_LABEL_RU.get(entry_reg, "—"),
        )

    # Flat: thresholds from current spread regime.
    reg = classify_spread_pct(spread)
    if reg == REGIME_TRANSITION:
        # Entry ignored; exit unused while flat. Keep wide defaults for display.
        return RegimeThresholds(
            REGIME_TRANSITION,
            REGIME_Z[REGIME_WIDE][0],
            REGIME_Z[REGIME_WIDE][1],
            False,
            False,
            REGIME_LABEL_RU[REGIME_TRANSITION],
        )
    pair = z_for_regime(reg)
    if pair:
        return RegimeThresholds(
            reg, pair[0], pair[1], True, False, REGIME_LABEL_RU[reg]
        )
    # Unknown spread → classic fallback, allow entry.
    return RegimeThresholds(REGIME_NA, ce, cx, True, False, REGIME_LABEL_RU[REGIME_NA])


def lock_fields_for_entry(spread: float | None) -> dict[str, Any]:
    """Columns to store on open trade when regime mode is ON."""
    reg = classify_spread_pct(spread)
    pair = z_for_regime(reg)
    if not pair:
        # Should not enter in transition; still record regime.
        return {
            "entry_regime": reg,
            "locked_entry_z": None,
            "locked_exit_z": None,
        }
    return {
        "entry_regime": reg,
        "locked_entry_z": pair[0],
        "locked_exit_z": pair[1],
    }


def gate_signal(signal: Signal, th: RegimeThresholds) -> Signal:
    """Suppress new entries when regime forbids them (переход)."""
    if th.allow_entry:
        return signal
    if signal in (Signal.ENTER_LONG, Signal.ENTER_SHORT):
        return Signal.NONE
    return signal


def resolve_from_settings(
    settings: dict[str, Any],
    *,
    spread: float | None,
    position: Position | str = Position.FLAT,
    open_trade: dict[str, Any] | None = None,
    bar: dict[str, Any] | None = None,
) -> RegimeThresholds:
    sp = spread if spread is not None else _bar_spread(bar)
    try:
        ce = float(settings.get("entry_z") or 1.6)
    except (TypeError, ValueError):
        ce = 1.6
    try:
        cx = float(settings.get("exit_z") or 1.3)
    except (TypeError, ValueError):
        cx = 1.3
    return resolve_thresholds(
        regime_z_mode=parse_regime_z_mode(settings),
        classic_entry=ce,
        classic_exit=cx,
        spread=sp,
        position=position,
        open_trade=open_trade,
    )


def desk_regime_payload(
    settings: dict[str, Any],
    *,
    spread: float | None,
    position: Position | str = Position.FLAT,
    open_trade: dict[str, Any] | None = None,
) -> dict[str, Any]:
    th = resolve_from_settings(
        settings, spread=spread, position=position, open_trade=open_trade
    )
    cur_reg = classify_spread_pct(spread)
    pos_s = position.value if isinstance(position, Position) else str(position or "FLAT")
    return {
        "regime_z_mode": parse_regime_z_mode(settings),
        "current_regime": cur_reg,
        "current_label_ru": REGIME_LABEL_RU.get(cur_reg, cur_reg),
        "effective": th.as_dict(),
        "cuts": {
            "narrow_max": SPREAD_WIDTH_NARROW_MAX,
            "wide_min": SPREAD_WIDTH_WIDE_MIN,
        },
        "z_by_regime": {
            REGIME_NARROW: {"entry": REGIME_Z[REGIME_NARROW][0], "exit": REGIME_Z[REGIME_NARROW][1]},
            REGIME_WIDE: {"entry": REGIME_Z[REGIME_WIDE][0], "exit": REGIME_Z[REGIME_WIDE][1]},
            REGIME_TRANSITION: {"entry": None, "exit": None, "allow_entry": False},
        },
        "exit_policy": "locked_at_entry",
        "entry_blocked": (not th.allow_entry) and pos_s.upper() == "FLAT",
    }
