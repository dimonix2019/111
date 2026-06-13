"""Симуляция Z vs перцентильной anomaly-стратегии с принудительным выходом в red zone (≥4 балла)."""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import Enum
from typing import List, Literal, Optional, Tuple

RedZoneMode = Literal["off", "always", "losing"]

import numpy as np

from zsim import (
    MSK,
    OVERNIGHT_FEE_PERCENT_PER_DAY,
    Bar,
    ClosedTrade,
    Position,
    SimResult,
    _entry_spread,
    _exit_spread,
    _hold_hours,
    _hold_hours,
    _parse_ts_day,
    _ts_to_millis,
    overnight_days,
    spread_pnl_to_rub,
)

# MoexStrategyTestTradeRisk.kt
RISK_STRONG_ENTRY_Z = 1.0
RISK_OVERNIGHT_RUB = 50.0
RISK_OVERNIGHT_HIGH_RUB = 100.0
RISK_PEAK_HOUR = 13
RISK_MIDDAY_START = 12
RISK_MIDDAY_END = 14
RED_ZONE_MIN_SCORE = 4

MS_PER_HOUR = 3_600_000
MS_PER_SIX_HOURS = 6 * MS_PER_HOUR
MS_PER_DAY = 24 * MS_PER_HOUR


class StrategyKind(str, Enum):
    Z = "z"
    PERCENTILE = "percentile"


@dataclass
class SimConfig:
    kind: StrategyKind
    notional_rub: float = 100_000.0
    leverage: float = 7.0
    commission_pct_per_side: float = 0.04
    force_red_zone_exit: bool = False
    red_zone_mode: RedZoneMode = "off"
    # Z
    entry_z: float = 0.7
    exit_z: float = 0.5
    # Percentile anomaly
    lookback_days: int = 90
    min_bars: int = 48
    entry_pct_high: float = 95.0
    entry_pct_low: float = 5.0
    exit_median_band_pp: float = 0.3
    exit_pct_mid_low: float = 45.0
    exit_pct_mid_high: float = 55.0


def _parse_ts_msk_full(ts: str) -> Optional[datetime]:
    trimmed = ts.strip()
    for fmt, size in (("%Y-%m-%d %H:%M:%S", 19), ("%Y-%m-%d %H:%M", 16)):
        try:
            return datetime.strptime(trimmed[:size], fmt).replace(tzinfo=MSK)
        except ValueError:
            continue
    return None


def hold_hours_between(entry_ts: str, exit_ts: str) -> float:
    e = _parse_ts_msk_full(entry_ts)
    x = _parse_ts_msk_full(exit_ts)
    if not e or not x:
        return 0.0
    return max(0.0, (x - e).total_seconds() / 3600.0)


def _entry_hour_msk(ts: str) -> Optional[int]:
    dt = _parse_ts_msk_full(ts)
    return dt.hour if dt else None


def _is_friday_entry(entry_ts: str) -> bool:
    dt = _parse_ts_msk_full(entry_ts)
    return dt.weekday() == 4 if dt else False


def trade_risk_score(
    entry_ts: str,
    as_of_ts: str,
    entry_z: float,
    entry_threshold: float,
    overnight_rub: float,
) -> int:
    """Порт buildTradeRiskAssessmentFromInputs (баллы, red zone при ≥4)."""
    e = _parse_ts_msk_full(entry_ts)
    x = _parse_ts_msk_full(as_of_ts)
    duration_ms = int((x - e).total_seconds() * 1000) if e and x else None
    score = 0
    if duration_ms is not None:
        if duration_ms > 5 * MS_PER_DAY:
            score += 4
        elif duration_ms > 2 * MS_PER_DAY:
            score += 3

    if overnight_rub > RISK_OVERNIGHT_HIGH_RUB:
        score += 2
    elif overnight_rub > RISK_OVERNIGHT_RUB and duration_ms is not None and duration_ms > MS_PER_DAY:
        score += 2

    if (
        abs(entry_z) < RISK_STRONG_ENTRY_Z
        and duration_ms is not None
        and duration_ms > MS_PER_SIX_HOURS
    ):
        score += 1

    if duration_ms is not None and duration_ms > MS_PER_SIX_HOURS:
        hour = _entry_hour_msk(entry_ts)
        if hour == RISK_PEAK_HOUR or (
            hour is not None and RISK_MIDDAY_START <= hour <= RISK_MIDDAY_END
        ):
            score += 1

    if (
        duration_ms is not None
        and duration_ms > 2 * MS_PER_DAY
        and _is_friday_entry(entry_ts)
    ):
        score += 1

    if (
        abs(entry_z) < entry_threshold + 0.05
        and duration_ms is not None
        and duration_ms > MS_PER_DAY
    ):
        score += 1

    return score


def is_red_zone(score: int) -> bool:
    return score >= RED_ZONE_MIN_SCORE


def _rolling_spread_stats(
    spreads: List[float],
    timestamps: List[str],
    i: int,
    lookback_days: int,
    min_bars: int,
) -> Tuple[Optional[float], Optional[float], Optional[float]]:
    """percentile rank (0–100), median, mean для spreads[i] vs окно [lookback_days] без look-ahead."""
    bar_millis = _ts_to_millis(timestamps[i])
    dt = datetime.fromtimestamp(bar_millis / 1000.0, MSK)
    from_date = dt.date() - timedelta(days=lookback_days)
    from_millis = int(datetime.combine(from_date, datetime.min.time(), MSK).timestamp() * 1000)

    hist: List[float] = []
    for j in range(i):
        if _ts_to_millis(timestamps[j]) >= from_millis:
            hist.append(spreads[j])
    if len(hist) < min_bars:
        return None, None, None
    cur = spreads[i]
    pct = 100.0 * sum(1 for s in hist if s <= cur) / len(hist)
    med = float(np.median(hist))
    mean = float(np.mean(hist))
    return pct, med, mean


def _percentile_exit(
    position: Position,
    spread: float,
    pct: Optional[float],
    median: Optional[float],
    cfg: SimConfig,
) -> bool:
    if median is not None and abs(spread - median) <= cfg.exit_median_band_pp:
        return True
    if pct is not None and cfg.exit_pct_mid_low <= pct <= cfg.exit_pct_mid_high:
        return True
    return False


def precompute_spread_percentiles(
    spreads: List[float],
    timestamps: List[str],
    lookback_days: int,
    min_bars: int,
) -> Tuple[List[Optional[float]], List[Optional[float]]]:
    """percentile rank и median на каждом баре (без look-ahead для rank)."""
    n = len(spreads)
    millis = [_ts_to_millis(ts) for ts in timestamps]
    pcts: List[Optional[float]] = [None] * n
    meds: List[Optional[float]] = [None] * n
    window_start = 0
    for i in range(n):
        bar_millis = millis[i]
        dt = datetime.fromtimestamp(bar_millis / 1000.0, MSK)
        from_date = dt.date() - timedelta(days=lookback_days)
        from_millis = int(datetime.combine(from_date, datetime.min.time(), MSK).timestamp() * 1000)
        while window_start < i and millis[window_start] < from_millis:
            window_start += 1
        hist = spreads[window_start:i]
        if len(hist) < min_bars:
            continue
        cur = spreads[i]
        pcts[i] = 100.0 * sum(1 for s in hist if s <= cur) / len(hist)
        meds[i] = float(np.median(hist))
    return pcts, meds


def _effective_red_zone_mode(cfg: SimConfig) -> RedZoneMode:
    if cfg.red_zone_mode != "off":
        return cfg.red_zone_mode
    return "always" if cfg.force_red_zone_exit else "off"


def run_strategy_sim(
    bars: List[Bar],
    cfg: SimConfig,
    *,
    precomputed_pcts: Optional[List[Optional[float]]] = None,
    precomputed_meds: Optional[List[Optional[float]]] = None,
) -> SimResult:
    if len(bars) < 2:
        return SimResult([], 0.0, 0.0, 0.0, signals=__import__("pandas").DataFrame())

    red_zone_mode = _effective_red_zone_mode(cfg)
    spreads = [b.spread_percent for b in bars]
    timestamps = [b.timestamp for b in bars]
    pct_series: List[Optional[float]]
    med_series: List[Optional[float]]
    if cfg.kind == StrategyKind.PERCENTILE:
        if precomputed_pcts is not None and precomputed_meds is not None:
            pct_series, med_series = precomputed_pcts, precomputed_meds
        else:
            pct_series, med_series = precompute_spread_percentiles(
                spreads, timestamps, cfg.lookback_days, cfg.min_bars
            )
    else:
        pct_series = [None] * len(bars)
        med_series = [None] * len(bars)

    position = Position.FLAT
    entry_spread = 0.0
    entry_z = 0.0
    entry_time = ""
    entry_commission = 0.0
    entry_tatn: Optional[float] = None
    entry_tatnp: Optional[float] = None
    realized_rub = 0.0
    position_notional = cfg.notional_rub
    closed: List[ClosedTrade] = []
    equity_history: List[float] = []
    total_commission = 0.0
    total_overnight = 0.0
    slip = 0.0

    def refresh_fees(nom: float) -> tuple[float, float, float]:
        eff = nom * cfg.leverage
        comm = eff * (cfg.commission_pct_per_side / 100.0)
        ovn_day = nom * max(0.0, cfg.leverage - 1.0) * (OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)
        return eff, comm, ovn_day

    eff_notional, comm_per_side, overnight_per_day = refresh_fees(position_notional)

    def close_pos(bar: Bar, reason: str) -> None:
        nonlocal position, realized_rub, entry_commission, total_commission, total_overnight
        nonlocal entry_tatn, entry_tatnp
        if position == Position.LONG:
            exit_sp = _exit_spread(bar.spread_percent, Position.LONG, slip)
            pnl_pts = exit_sp - entry_spread
        else:
            exit_sp = _exit_spread(bar.spread_percent, Position.SHORT, slip)
            pnl_pts = entry_spread - exit_sp
        gross = spread_pnl_to_rub(pnl_pts, eff_notional)
        ovn = overnight_per_day * overnight_days(entry_time, bar.timestamp)
        comm_total = entry_commission + comm_per_side
        net = gross - comm_total - ovn
        total_commission += comm_per_side
        total_overnight += ovn
        realized_rub += gross - comm_per_side - ovn
        closed.append(
            ClosedTrade(
                position,
                entry_time,
                bar.timestamp,
                entry_spread,
                exit_sp,
                entry_z,
                bar.z_score,
                pnl_pts,
                net,
                commission_rub=comm_total,
                overnight_rub=ovn,
                exit_reason=reason,
            )
        )
        position = Position.FLAT
        entry_tatn = None
        entry_tatnp = None

    def enter_pos(bar: Bar, direction: Position) -> None:
        nonlocal position, entry_spread, entry_z, entry_time, entry_commission, realized_rub
        nonlocal total_commission, entry_tatn, entry_tatnp
        position = direction
        entry_spread = _entry_spread(bar.spread_percent, direction, slip)
        entry_z = bar.z_score
        entry_time = bar.timestamp
        entry_tatn = bar.tatn_close
        entry_tatnp = bar.tatnp_close
        entry_commission = comm_per_side
        total_commission += comm_per_side
        realized_rub -= comm_per_side

    def mtm_rub(bar: Bar) -> float:
        if position == Position.FLAT:
            return 0.0
        if position == Position.LONG:
            mtm_pts = bar.spread_percent - entry_spread
        else:
            mtm_pts = entry_spread - bar.spread_percent
        return spread_pnl_to_rub(mtm_pts, eff_notional)

    def red_zone_now(bar: Bar) -> bool:
        ovn = overnight_per_day * overnight_days(entry_time, bar.timestamp)
        score = trade_risk_score(
            entry_time,
            bar.timestamp,
            entry_z,
            cfg.entry_z if cfg.kind == StrategyKind.Z else 0.8,
            ovn,
        )
        return is_red_zone(score)

    for i in range(1, len(bars)):
        prev, cur = bars[i - 1], bars[i]
        prev_pct, prev_med = pct_series[i - 1], med_series[i - 1]
        cur_pct, cur_med = pct_series[i], med_series[i]

        if position != Position.FLAT and red_zone_mode != "off" and red_zone_now(cur):
            if red_zone_mode == "always" or (
                red_zone_mode == "losing" and mtm_rub(cur) < 0.0
            ):
                close_pos(cur, "red_zone")
        elif position == Position.LONG:
            if cfg.kind == StrategyKind.Z:
                if prev.z_score < -cfg.exit_z and cur.z_score >= -cfg.exit_z:
                    close_pos(cur, "z_exit")
            elif _percentile_exit(Position.LONG, cur.spread_percent, cur_pct, cur_med, cfg):
                close_pos(cur, "pct_exit")
        elif position == Position.SHORT:
            if cfg.kind == StrategyKind.Z:
                if prev.z_score > cfg.exit_z and cur.z_score <= cfg.exit_z:
                    close_pos(cur, "z_exit")
            elif _percentile_exit(Position.SHORT, cur.spread_percent, cur_pct, cur_med, cfg):
                close_pos(cur, "pct_exit")

        if position == Position.FLAT:
            if cfg.kind == StrategyKind.Z:
                if prev.z_score > -cfg.entry_z and cur.z_score <= -cfg.entry_z:
                    enter_pos(cur, Position.LONG)
                elif prev.z_score < cfg.entry_z and cur.z_score >= cfg.entry_z:
                    enter_pos(cur, Position.SHORT)
            elif cur_pct is not None and prev_pct is not None:
                if prev_pct < cfg.entry_pct_high and cur_pct >= cfg.entry_pct_high:
                    enter_pos(cur, Position.SHORT)
                elif prev_pct > cfg.entry_pct_low and cur_pct <= cfg.entry_pct_low:
                    enter_pos(cur, Position.LONG)

        equity_history.append(realized_rub + mtm_rub(cur))

    last = bars[-1]
    unrealized = 0.0
    if position != Position.FLAT:
        gross_u = mtm_rub(last)
        ovn_u = overnight_per_day * overnight_days(entry_time, last.timestamp)
        total_overnight += ovn_u
        unrealized = gross_u - comm_per_side - ovn_u

    total = realized_rub + unrealized
    return SimResult(
        trades=closed,
        total_pnl_rub=total,
        realized_pnl_rub=realized_rub,
        unrealized_pnl_rub=unrealized,
        signals=__import__("pandas").DataFrame(),
        equity_history=equity_history,
        total_commission_rub=total_commission,
        total_overnight_rub=total_overnight,
        bar_count=len(bars),
        first_ts=bars[0].timestamp,
        last_ts=bars[-1].timestamp,
    )


def summarize_result(name: str, result: SimResult, entry_threshold: float = 0.7) -> dict:
    trades = result.trades
    holds = [hold_hours_between(t.entry_time, t.exit_time) for t in trades]
    red_exits = sum(1 for t in trades if t.exit_reason == "red_zone")
    red_at_close = 0
    for t in trades:
        score = trade_risk_score(
            t.entry_time,
            t.exit_time,
            t.entry_z,
            entry_threshold,
            t.overnight_rub,
        )
        if is_red_zone(score):
            red_at_close += 1

    eq = result.equity_history
    peak = 0.0
    mdd = 0.0
    for e in eq:
        peak = max(peak, e)
        mdd = max(mdd, peak - e)

    return {
        "name": name,
        "pnl": result.total_pnl_rub,
        "trades": len(trades),
        "mdd": mdd,
        "median_hold_h": float(np.median(holds)) if holds else 0.0,
        "p95_hold_h": float(np.percentile(holds, 95)) if holds else 0.0,
        "hold_gt_48h": sum(1 for h in holds if h > 48),
        "hold_gt_5d": sum(1 for h in holds if h > 120),
        "red_zone_exits": red_exits,
        "red_zone_at_close": red_at_close,
        "avg_pnl": result.total_pnl_rub / len(trades) if trades else 0.0,
        "wins": sum(1 for t in trades if t.pnl_rub > 0),
    }
