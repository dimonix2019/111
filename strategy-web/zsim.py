"""Z-стратегия спрэда TATN/TATNP — порт MoexZStrategySim (fixed threshold)."""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import List, Optional

import pandas as pd

# MoexConstants.kt
OVERNIGHT_FEE_PERCENT_PER_DAY = 0.033
LOOKBACK_DAYS = 255
MIN_BARS_FULL_HISTORY = 8_000  # ~255д 15м ≈ 13k баров


class Position(str, Enum):
    FLAT = "FLAT"
    LONG = "LONG"
    SHORT = "SHORT"


@dataclass
class Bar:
    timestamp: str
    z_score: float
    spread_percent: float


@dataclass
class ClosedTrade:
    direction: Position
    entry_time: str
    exit_time: str
    entry_spread: float
    exit_spread: float
    pnl_spread_pts: float
    pnl_rub: float


@dataclass
class SimResult:
    trades: List[ClosedTrade]
    total_pnl_rub: float  # realized + unrealized (как totalPnlRubApprox в Android)
    realized_pnl_rub: float
    unrealized_pnl_rub: float
    signals: pd.DataFrame
    equity_history: List[float] = field(default_factory=list)
    bar_count: int = 0
    first_ts: str = ""
    last_ts: str = ""


def apply_z_scores(spread_percent: List[float]) -> List[float]:
    """Как applyZScores() в MoexIssData.kt — Z по всему загруженному ряду."""
    if not spread_percent:
        return []
    mean = sum(spread_percent) / len(spread_percent)
    var = sum((x - mean) ** 2 for x in spread_percent) / len(spread_percent)
    std = math.sqrt(var) if var > 0 else 1.0
    if std <= 0:
        std = 1.0
    return [(x - mean) / std for x in spread_percent]


def _parse_ts_day(ts: str) -> Optional[datetime]:
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            return datetime.strptime(ts[: len(fmt.replace("%", "0"))], fmt)
        except ValueError:
            continue
    try:
        return datetime.strptime(ts[:10], "%Y-%m-%d")
    except ValueError:
        return None


def overnight_days(entry_ts: str, exit_ts: str) -> int:
    e = _parse_ts_day(entry_ts)
    x = _parse_ts_day(exit_ts)
    if not e or not x:
        return 0
    return max(0, (x.date() - e.date()).days)


def spread_pnl_to_rub(pnl_spread_pts: float, effective_notional_rub: float) -> float:
    """spreadPnlToRubApprox — аргумент уже notional×leverage."""
    return effective_notional_rub * (pnl_spread_pts / 100.0)


def run_z_strategy_sim(
    bars: List[Bar],
    entry: float,
    exit_z: float,
    notional_rub: float = 100_000.0,
    leverage: float = 7.0,
    commission_pct_per_side: float = 0.04,
    compound_returns: bool = False,
) -> SimResult:
    if len(bars) < 2:
        return SimResult(
            trades=[],
            total_pnl_rub=0.0,
            realized_pnl_rub=0.0,
            unrealized_pnl_rub=0.0,
            signals=pd.DataFrame(),
        )

    position = Position.FLAT
    entry_spread = 0.0
    entry_time = ""
    entry_commission = 0.0
    realized_rub = 0.0
    position_notional = notional_rub
    closed: List[ClosedTrade] = []
    signal_rows: List[dict] = []
    equity_history: List[float] = []

    def refresh_fees(nom: float) -> tuple[float, float, float]:
        eff = nom * leverage
        comm_side = eff * (commission_pct_per_side / 100.0)
        overnight_day = nom * max(0.0, leverage - 1.0) * (OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)
        return eff, comm_side, overnight_day

    eff_notional, comm_per_side, overnight_per_day = refresh_fees(position_notional)

    def apply_sizing_from_realized() -> None:
        nonlocal position_notional, eff_notional, comm_per_side, overnight_per_day
        if compound_returns:
            position_notional = max(1.0, notional_rub + realized_rub)
        else:
            position_notional = notional_rub
        eff_notional, comm_per_side, overnight_per_day = refresh_fees(position_notional)

    def close_long(bar: Bar) -> None:
        nonlocal position, realized_rub, entry_commission
        pnl_pts = bar.spread_percent - entry_spread
        gross = spread_pnl_to_rub(pnl_pts, eff_notional)
        ovn = overnight_per_day * overnight_days(entry_time, bar.timestamp)
        net = gross - entry_commission - comm_per_side - ovn
        realized_rub += gross - comm_per_side - ovn
        closed.append(
            ClosedTrade(
                Position.LONG, entry_time, bar.timestamp,
                entry_spread, bar.spread_percent, pnl_pts, net,
            )
        )
        position = Position.FLAT

    def close_short(bar: Bar) -> None:
        nonlocal position, realized_rub, entry_commission
        pnl_pts = entry_spread - bar.spread_percent
        gross = spread_pnl_to_rub(pnl_pts, eff_notional)
        ovn = overnight_per_day * overnight_days(entry_time, bar.timestamp)
        net = gross - entry_commission - comm_per_side - ovn
        realized_rub += gross - comm_per_side - ovn
        closed.append(
            ClosedTrade(
                Position.SHORT, entry_time, bar.timestamp,
                entry_spread, bar.spread_percent, pnl_pts, net,
            )
        )
        position = Position.FLAT

    def enter_long(bar: Bar) -> None:
        nonlocal position, entry_spread, entry_time, entry_commission, realized_rub
        apply_sizing_from_realized()
        position = Position.LONG
        entry_spread = bar.spread_percent
        entry_time = bar.timestamp
        entry_commission = comm_per_side
        realized_rub -= comm_per_side

    def enter_short(bar: Bar) -> None:
        nonlocal position, entry_spread, entry_time, entry_commission, realized_rub
        apply_sizing_from_realized()
        position = Position.SHORT
        entry_spread = bar.spread_percent
        entry_time = bar.timestamp
        entry_commission = comm_per_side
        realized_rub -= comm_per_side

    def mtm_rub(bar: Bar) -> float:
        if position == Position.FLAT:
            return 0.0
        mtm_pts = (
            (bar.spread_percent - entry_spread)
            if position == Position.LONG
            else (entry_spread - bar.spread_percent)
        )
        return spread_pnl_to_rub(mtm_pts, eff_notional)

    for i in range(1, len(bars)):
        prev, cur = bars[i - 1], bars[i]
        sig = ""

        if position == Position.LONG:
            if prev.z_score < -exit_z and cur.z_score >= -exit_z:
                close_long(cur)
                sig = "exit_long"
        elif position == Position.SHORT:
            if prev.z_score > exit_z and cur.z_score <= exit_z:
                close_short(cur)
                sig = "exit_short"

        if position == Position.FLAT:
            if prev.z_score > -entry and cur.z_score <= -entry:
                enter_long(cur)
                sig = "enter_long"
            elif prev.z_score < entry and cur.z_score >= entry:
                enter_short(cur)
                sig = "enter_short"

        equity_history.append(realized_rub + mtm_rub(cur))
        signal_rows.append(
            {
                "timestamp": cur.timestamp,
                "z_score": cur.z_score,
                "spread_percent": cur.spread_percent,
                "position": position.value,
                "signal": sig,
            }
        )

    last = bars[-1]
    unrealized = 0.0
    if position != Position.FLAT:
        gross_u = mtm_rub(last)
        ovn_u = overnight_per_day * overnight_days(entry_time, last.timestamp)
        unrealized = gross_u - comm_per_side - ovn_u

    total = realized_rub + unrealized

    return SimResult(
        trades=closed,
        total_pnl_rub=total,
        realized_pnl_rub=realized_rub,
        unrealized_pnl_rub=unrealized,
        signals=pd.DataFrame(signal_rows),
        equity_history=equity_history,
        bar_count=len(bars),
        first_ts=bars[0].timestamp,
        last_ts=bars[-1].timestamp,
    )


def load_bars_from_csv(path: str, recalc_z: bool = True) -> List[Bar]:
    df = pd.read_csv(path)
    required = {"timestamp", "spread_percent"}
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"CSV needs columns {required}, missing {missing}")

    spreads = [float(x) for x in df["spread_percent"]]
    if recalc_z or "z_score" not in df.columns:
        zs = apply_z_scores(spreads)
    else:
        zs = [float(x) for x in df["z_score"]]

    bars: List[Bar] = []
    for ts, z, sp in zip(df["timestamp"], zs, spreads):
        bars.append(Bar(timestamp=str(ts), z_score=float(z), spread_percent=float(sp)))
    return bars


def history_quality(bars: List[Bar]) -> dict:
    """Подсказка: совпадает ли ряд с Android (255д ≈ 13k+ баров)."""
    n = len(bars)
    ok = n >= MIN_BARS_FULL_HISTORY
    return {
        "bar_count": n,
        "looks_full_255d": ok,
        "first_ts": bars[0].timestamp if bars else "",
        "last_ts": bars[-1].timestamp if bars else "",
        "expected_trades_hint": "≈140 при 0.8/0.7 и полном ряде" if ok else "мало баров → мало сделок",
    }
