"""Z-стратегия спрэда TATN/TATNP — порт MoexZStrategySim (fixed threshold)."""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import List, Literal, Optional
from zoneinfo import ZoneInfo

import pandas as pd

# MoexConstants.kt
OVERNIGHT_FEE_PERCENT_PER_DAY = 0.033
LOOKBACK_DAYS = 255
MIN_BARS_FULL_HISTORY = 8_000  # ~255д 15м ≈ 13k баров
Z_SCORE_ROLLING_LOOKBACK_DAYS = 30
Z_SCORE_ROLLING_MIN_BARS = 48
MSK = ZoneInfo("Europe/Moscow")

ZMode = Literal["global", "rolling30"]


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

    def equity_frame(self) -> pd.DataFrame:
        """DataFrame: equity_rub, drawdown_rub (для графиков в app.py)."""
        if not self.equity_history:
            return pd.DataFrame(columns=["equity_rub", "drawdown_rub"])
        n = len(self.equity_history)
        if self.signals is None or self.signals.empty:
            idx = pd.RangeIndex(n)
        else:
            idx = pd.to_datetime(self.signals["timestamp"].iloc[:n])
        eq = pd.Series(self.equity_history, index=idx, name="equity_rub", dtype=float)
        drawdown = eq - eq.cummax()
        drawdown.name = "drawdown_rub"
        return pd.DataFrame({"equity_rub": eq, "drawdown_rub": drawdown})


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


def apply_z_scores_rolling(
    spread_percent: List[float],
    timestamps: List[str],
    lookback_days: int = Z_SCORE_ROLLING_LOOKBACK_DAYS,
    min_bars_in_window: int = Z_SCORE_ROLLING_MIN_BARS,
) -> List[float]:
    """Как applyZScoresRolling() — μ/σ только за последние lookback_days (без look-ahead)."""
    n = len(spread_percent)
    if n == 0:
        return []
    if len(timestamps) != n:
        raise ValueError("spread_percent and timestamps must have the same length")
    if lookback_days <= 0:
        return apply_z_scores(spread_percent)

    millis_list = [_ts_to_millis(ts) for ts in timestamps]
    result: List[float] = []
    window_start = 0
    min_bars = max(min_bars_in_window, 2)

    for i in range(n):
        bar_millis = millis_list[i]
        dt = datetime.fromtimestamp(bar_millis / 1000.0, MSK)
        from_date = dt.date() - timedelta(days=lookback_days)
        from_millis = int(
            datetime.combine(from_date, datetime.min.time(), MSK).timestamp() * 1000
        )

        while window_start < i and millis_list[window_start] < from_millis:
            window_start += 1

        count = 0
        total = 0.0
        total_sq = 0.0
        for j in range(window_start, i + 1):
            s = spread_percent[j]
            total += s
            total_sq += s * s
            count += 1

        if count < min_bars:
            result.append(0.0)
        else:
            mean = total / count
            variance = (total_sq / count) - (mean * mean)
            std = math.sqrt(max(variance, 0.0))
            if std <= 1e-12:
                std = 1.0
            result.append((spread_percent[i] - mean) / std)

    return result


def z_scores_for_mode(
    spreads: List[float],
    timestamps: List[str],
    z_mode: ZMode,
) -> List[float]:
    if z_mode == "rolling30":
        return apply_z_scores_rolling(spreads, timestamps)
    return apply_z_scores(spreads)


def z_diagnostics(bars: List[Bar]) -> dict:
    if not bars:
        return {
            "max_abs_z": 0.0,
            "bars_ge_08": 0,
            "bars_ge_07": 0,
            "last_z": 0.0,
            "last_spread": 0.0,
        }
    abs_z = [abs(b.z_score) for b in bars]
    last = bars[-1]
    return {
        "max_abs_z": max(abs_z),
        "bars_ge_08": sum(1 for z in abs_z if z >= 0.8),
        "bars_ge_07": sum(1 for z in abs_z if z >= 0.7),
        "last_z": last.z_score,
        "last_spread": last.spread_percent,
    }


def _parse_ts(ts: str) -> Optional[datetime]:
    s = str(ts).strip()
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            n = 19 if fmt.endswith("S") else (16 if "H" in fmt[11:] else 10)
            return datetime.strptime(s[:n], fmt)
        except ValueError:
            continue
    return None


def _ts_to_millis(ts: str) -> int:
    dt = _parse_ts(ts)
    if not dt:
        return 0
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=MSK)
    return int(dt.timestamp() * 1000)


def _parse_ts_day(ts: str) -> Optional[datetime]:
    return _parse_ts(ts)


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


def load_bars_from_csv(
    path: str,
    recalc_z: bool = True,
    z_mode: ZMode = "global",
) -> List[Bar]:
    df = pd.read_csv(path)
    required = {"timestamp", "spread_percent"}
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"CSV needs columns {required}, missing {missing}")

    timestamps = [str(x) for x in df["timestamp"]]
    spreads = [float(x) for x in df["spread_percent"]]
    if recalc_z or z_mode == "rolling30" or "z_score" not in df.columns:
        zs = z_scores_for_mode(spreads, timestamps, z_mode)
    else:
        zs = [float(x) for x in df["z_score"]]

    bars: List[Bar] = []
    for ts, z, sp in zip(timestamps, zs, spreads):
        bars.append(Bar(timestamp=ts, z_score=float(z), spread_percent=float(sp)))
    return bars


def max_drawdown_rub(equity_history: List[float]) -> float:
    peak = 0.0
    max_dd = 0.0
    for eq in equity_history:
        peak = max(peak, eq)
        max_dd = max(max_dd, peak - eq)
    return max_dd


def compare_z_modes(
    path: str,
    entry: float,
    exit_z: float,
    notional_rub: float = 100_000.0,
    leverage: float = 7.0,
    commission_pct_per_side: float = 0.04,
) -> pd.DataFrame:
    """Две симуляции на одном CSV: global vs rolling 30д."""
    rows = []
    for label, mode in (("Global 255д", "global"), ("Rolling 30д", "rolling30")):
        bars = load_bars_from_csv(path, recalc_z=True, z_mode=mode)
        diag = z_diagnostics(bars)
        sim = run_z_strategy_sim(
            bars,
            entry=entry,
            exit_z=exit_z,
            notional_rub=notional_rub,
            leverage=leverage,
            commission_pct_per_side=commission_pct_per_side,
        )
        rows.append(
            {
                "режим Z": label,
                "сделок": len(sim.trades),
                "PnL ₽": round(sim.total_pnl_rub, 0),
                "max DD ₽": round(max_drawdown_rub(sim.equity_history), 0),
                "max |Z|": round(diag["max_abs_z"], 3),
                "баров |Z|≥0.8": diag["bars_ge_08"],
            }
        )
    return pd.DataFrame(rows)


def history_quality(bars: List[Bar], z_mode: ZMode = "global") -> dict:
    """Подсказка: совпадает ли ряд с Android (255д ≈ 13k+ баров)."""
    n = len(bars)
    ok = n >= MIN_BARS_FULL_HISTORY
    diag = z_diagnostics(bars)
    if z_mode == "rolling30":
        trade_hint = (
            "rolling 30д: Z живее, сделок обычно больше, чем global"
            if ok
            else "мало баров → мало сделок"
        )
    else:
        trade_hint = (
            "global: ≈140 при 0.8/0.7 (legacy Android)" if ok else "мало баров → мало сделок"
        )
    return {
        "bar_count": n,
        "looks_full_255d": ok,
        "first_ts": bars[0].timestamp if bars else "",
        "last_ts": bars[-1].timestamp if bars else "",
        "expected_trades_hint": trade_hint,
        "z_mode": z_mode,
        **diag,
    }
