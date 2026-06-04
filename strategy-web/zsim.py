"""Z-стратегия спрэда TATN/TATNP — порт MoexZStrategySim (fixed threshold)."""

from __future__ import annotations

import logging
import math
import os
import numpy as np
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from itertools import product
from typing import Any, Callable, List, Literal, Optional, Union
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
    tatn_close: Optional[float] = None
    tatnp_close: Optional[float] = None
    tatn_open: Optional[float] = None
    tatn_high: Optional[float] = None
    tatn_low: Optional[float] = None
    tatnp_open: Optional[float] = None
    tatnp_high: Optional[float] = None
    tatnp_low: Optional[float] = None
    spread_open: Optional[float] = None
    spread_high: Optional[float] = None
    spread_low: Optional[float] = None
    spread_close: Optional[float] = None
    tatn_volume: Optional[float] = None
    tatnp_volume: Optional[float] = None


@dataclass
class OrderLeg:
    """Одна нога арбитражной пары (ордер по бумаге)."""
    ticker: str
    side: str
    entry_price: float
    exit_price: float
    qty: float
    pnl_rub: float = 0.0


@dataclass
class ClosedTrade:
    direction: Position
    entry_time: str
    exit_time: str
    entry_spread: float
    exit_spread: float
    entry_z: float
    exit_z: float
    pnl_spread_pts: float
    pnl_rub: float
    commission_rub: float = 0.0
    overnight_rub: float = 0.0
    exit_reason: str = "z_exit"
    legs: List["OrderLeg"] = field(default_factory=list)


@dataclass
class SimResult:
    trades: List[ClosedTrade]
    total_pnl_rub: float  # realized + unrealized (как totalPnlRubApprox в Android)
    realized_pnl_rub: float
    unrealized_pnl_rub: float
    signals: pd.DataFrame
    equity_history: List[float] = field(default_factory=list)
    total_commission_rub: float = 0.0
    total_overnight_rub: float = 0.0
    bar_count: int = 0
    first_ts: str = ""
    last_ts: str = ""
    trading_halted: bool = False
    halt_reason: str = ""

    def risk_metrics(self) -> dict:
        trades = self.trades
        pnls = [t.pnl_rub for t in trades]
        worst = min(pnls) if pnls else 0.0
        best = max(pnls) if pnls else 0.0
        wins = [p for p in pnls if p > 0]
        avg_win = sum(wins) / len(wins) if wins else 0.0
        tail_ratio = abs(worst) / avg_win if avg_win > 0 else None
        gross_pnl = self.realized_pnl_rub + self.total_commission_rub
        commission_pct = (
            100.0 * self.total_commission_rub / gross_pnl if gross_pnl > 0 else 0.0
        )
        total_pnl = sum(pnls)
        n = len(trades)
        top_k = max(1, math.ceil(n * 0.1)) if n else 0
        sorted_by_pnl = sorted(trades, key=lambda t: t.pnl_rub, reverse=True)
        top_sum = sum(t.pnl_rub for t in sorted_by_pnl[:top_k])
        top10_concentration = (
            100.0 * top_sum / total_pnl if total_pnl != 0 else 0.0
        )
        stop_loss_exits = sum(1 for t in trades if t.exit_reason == "stop_loss")
        z_exits = sum(1 for t in trades if t.exit_reason == "z_exit")
        end_of_data_exits = sum(1 for t in trades if t.exit_reason == "end_of_data")
        return {
            "worst_trade_rub": worst,
            "best_trade_rub": best,
            "tail_ratio": tail_ratio,
            "commission_pct_of_gross": commission_pct,
            "top10_concentration_pct": top10_concentration,
            "stop_loss_exits": stop_loss_exits,
            "z_exits": z_exits,
            "end_of_data_exits": end_of_data_exits,
            "trading_halted": self.trading_halted,
            "halt_reason": self.halt_reason,
        }

    def sim_meta(self) -> dict:
        return {
            "trading_halted": self.trading_halted,
            "halt_reason": self.halt_reason,
        }

    def equity_frame(self) -> pd.DataFrame:
        if not self.equity_history:
            return pd.DataFrame(columns=["timestamp", "equity_rub", "drawdown_rub"])
        eq = np.asarray(self.equity_history, dtype=float)
        peak = np.maximum.accumulate(eq)
        dd = eq - peak
        ts = (
            self.signals["timestamp"].tolist()
            if not self.signals.empty and "timestamp" in self.signals.columns
            else list(range(len(eq)))
        )
        n = min(len(ts), len(eq))
        return pd.DataFrame(
            {
                "timestamp": ts[:n],
                "equity_rub": eq[:n],
                "drawdown_rub": dd[:n],
            }
        )

    def zscore_frame(self) -> pd.DataFrame:
        if self.signals.empty:
            return pd.DataFrame(columns=["timestamp", "z_score", "spread_percent", "position", "signal"])
        return self.signals.copy()

    def spread_frame(self) -> pd.DataFrame:
        return self.zscore_frame()

    def _z_by_timestamp(self) -> dict[str, float]:
        """Кэш Z по нормализованному timestamp (без 13k вызовов pd.to_datetime)."""
        cached = getattr(self, "_z_by_ts_cache", None)
        if cached is not None:
            return cached
        if self.signals.empty:
            self._z_by_ts_cache = {}
            return self._z_by_ts_cache
        sig = self.signals
        ts_norm = pd.to_datetime(sig["timestamp"], errors="coerce").dt.strftime("%Y-%m-%d %H:%M:%S")
        self._z_by_ts_cache = dict(zip(ts_norm.astype(str), sig["z_score"].astype(float)))
        return self._z_by_ts_cache

    @staticmethod
    def _norm_trade_ts(ts: str) -> str:
        return pd.to_datetime(ts, errors="coerce").strftime("%Y-%m-%d %H:%M:%S")

    def trade_markers_frame(self) -> pd.DataFrame:
        if not self.trades or self.signals.empty:
            return pd.DataFrame()
        z_by_ts = self._z_by_timestamp()
        rows: list[dict] = []
        for trade_no, trade in enumerate(self.trades, start=1):
            for event, ts in (("вход", trade.entry_time), ("выход", trade.exit_time)):
                fallback = trade.entry_z if event == "вход" else trade.exit_z
                z = float(z_by_ts.get(self._norm_trade_ts(ts), fallback))
                is_long = trade.direction == Position.LONG
                if event == "вход":
                    marker_symbol = "triangle-up" if is_long else "triangle-down"
                    marker_color = "#10b981" if is_long else "#ef4444"
                else:
                    marker_symbol = "circle"
                    marker_color = "#fb923c"
                rows.append(
                    {
                        "trade_no": trade_no,
                        "event": event,
                        "timestamp": ts,
                        "z_score": z,
                        "direction": trade.direction.value,
                        "entry_time": trade.entry_time,
                        "exit_time": trade.exit_time,
                        "entry_spread": trade.entry_spread,
                        "exit_spread": trade.exit_spread,
                        "pnl_rub": trade.pnl_rub,
                        "pnl_spread_pts": trade.pnl_spread_pts,
                        "marker_symbol": marker_symbol,
                        "marker_color": marker_color,
                    }
                )
        return pd.DataFrame(rows)

    def stats(self) -> dict:
        trades = self.trades
        eq = self.equity_frame()
        mdd = float(eq["drawdown_rub"].min()) if not eq.empty else 0.0
        wins = [t for t in trades if t.pnl_rub > 0]
        losses = [t for t in trades if t.pnl_rub <= 0]
        gross_win = sum(t.pnl_rub for t in wins)
        gross_loss = abs(sum(t.pnl_rub for t in losses))
        if gross_loss > 0:
            profit_factor = gross_win / gross_loss
        elif gross_win > 0:
            profit_factor = float("inf")
        else:
            profit_factor = 0.0
        long_pnl = sum(t.pnl_rub for t in trades if t.direction == Position.LONG)
        short_pnl = sum(t.pnl_rub for t in trades if t.direction == Position.SHORT)
        n = len(trades)
        hold_hours: list[float] = []
        for t in trades:
            hold_hours.append(_hold_hours(t.entry_time, t.exit_time))
        return {
            "trade_count": n,
            "total_pnl_rub": self.total_pnl_rub,
            "realized_pnl_rub": self.realized_pnl_rub,
            "unrealized_pnl_rub": self.unrealized_pnl_rub,
            "win_rate_pct": 100.0 * len(wins) / n if n else 0.0,
            "avg_pnl_rub": self.total_pnl_rub / n if n else 0.0,
            "max_drawdown_rub": mdd,
            "profit_factor": profit_factor,
            "long_pnl_rub": long_pnl,
            "short_pnl_rub": short_pnl,
            "wins": len(wins),
            "losses": len(losses),
            "avg_hold_hours": sum(hold_hours) / len(hold_hours) if hold_hours else 0.0,
            "total_commission_rub": self.total_commission_rub,
            "total_overnight_rub": self.total_overnight_rub,
        }

    def idle_gaps_analysis(self) -> dict:
        """Паузы между сделками (календарные дни flat) + текущая ситуация на конце ряда."""
        trades = self.trades
        if not self.last_ts:
            return {"gaps": [], "histogram": [], "current": {"idle_days": 0, "since_display": "", "in_position": False, "label": "—"}}

        last_dt = _parse_ts_day(self.last_ts)
        first_dt = _parse_ts_day(self.first_ts) if self.first_ts else last_dt
        if not last_dt:
            return {"gaps": [], "histogram": [], "current": {"idle_days": 0, "since_display": "", "in_position": False, "label": "—"}}
        in_position = self.unrealized_pnl_rub != 0
        gaps: list[dict] = []

        def _gap_days(from_dt: datetime, to_dt: datetime) -> int:
            return max(0, (to_dt.date() - from_dt.date()).days)

        if trades:
            first_entry = _parse_ts_day(trades[0].entry_time)
            if first_entry and first_dt:
                d0 = _gap_days(first_dt, first_entry)
                if d0 > 0:
                    gaps.append(
                        {
                            "days": d0,
                            "from": first_dt.strftime("%Y-%m-%d"),
                            "to": trades[0].entry_time[:10],
                        }
                    )
            for i in range(len(trades) - 1):
                exit_i = _parse_ts_day(trades[i].exit_time)
                entry_n = _parse_ts_day(trades[i + 1].entry_time)
                if not exit_i or not entry_n:
                    continue
                d = _gap_days(exit_i, entry_n)
                gaps.append(
                    {
                        "days": d,
                        "from": trades[i].exit_time[:10],
                        "to": trades[i + 1].entry_time[:10],
                    }
                )

        if in_position:
            last_entry = _parse_ts_day(trades[-1].entry_time) if trades else last_dt
            current = {
                "idle_days": 0,
                "since_display": last_entry.strftime("%d.%m.%Y") if last_entry else "",
                "in_position": True,
                "label": f"Открыта позиция (вход {last_entry.strftime('%d.%m')})" if last_entry else "Открыта позиция",
            }
        elif not trades:
            idle = _gap_days(first_dt, last_dt) if first_dt else 0
            current = {
                "idle_days": idle,
                "since_display": first_dt.strftime("%d.%m.%Y") if first_dt else "",
                "in_position": False,
                "label": f"Сделок не было · пауза {idle} дн.",
            }
            if first_dt:
                gaps.append({"days": idle, "from": first_dt.strftime("%Y-%m-%d"), "to": last_dt.strftime("%Y-%m-%d")})
        else:
            last_exit = _parse_ts_day(trades[-1].exit_time)
            idle = _gap_days(last_exit, last_dt) if last_exit else 0
            since = last_exit.strftime("%d.%m.%Y") if last_exit else ""
            if idle > 0 and last_exit:
                gaps.append(
                    {
                        "days": idle,
                        "from": trades[-1].exit_time[:10],
                        "to": last_dt.strftime("%Y-%m-%d"),
                    }
                )
                label = f"Без сделок с {last_exit.strftime('%d.%m')} ({idle} дн.)"
            else:
                label = f"Последняя сделка закрыта {since}"
            current = {
                "idle_days": idle,
                "since_display": since,
                "in_position": False,
                "label": label,
            }

        buckets: dict[str, int] = {}
        for g in gaps:
            d = int(g["days"])
            if d == 0:
                key = "0"
            elif d == 1:
                key = "1"
            elif d <= 3:
                key = "2–3"
            elif d <= 7:
                key = "4–7"
            elif d <= 14:
                key = "8–14"
            elif d <= 30:
                key = "15–30"
            else:
                key = "31+"
            buckets[key] = buckets.get(key, 0) + 1

        order = ["0", "1", "2–3", "4–7", "8–14", "15–30", "31+"]
        histogram = [{"bucket": k, "count": buckets[k]} for k in order if k in buckets]

        return {"gaps": gaps, "histogram": histogram, "current": current}

    def idle_precursors(
        self,
        entry: float = 0.8,
        exit_z: float = 0.7,
        idle: dict | None = None,
    ) -> dict:
        """TA по Z-score: предвестники затяжного простоя (mean-reversion, вход на экстремумах)."""
        from api.z_ta import (
            idle_duration_forecast,
            idle_event_correlation,
            idle_risk_score,
            z_ta_features,
            z_ta_percentiles,
        )

        zdf = self.zscore_frame()
        if zdf.empty:
            return {"risk_score": 0, "signs": [], "summary": "Нет ряда Z-score", "features": {}}

        z_arr = zdf["z_score"].to_numpy(dtype=float)
        idle = idle if idle is not None else self.idle_gaps_analysis()
        cur = idle["current"]
        feat = z_ta_features(z_arr, entry, exit_z)
        pct = z_ta_percentiles(z_arr, entry, exit_z)
        risk, signs = idle_risk_score(
            feat,
            pct,
            entry,
            idle_days=float(cur.get("idle_days", 0)),
            in_position=bool(cur.get("in_position", False)),
        )
        forecast = idle_duration_forecast(
            idle.get("gaps", []),
            current_idle_days=float(cur.get("idle_days", 0)),
            in_position=bool(cur.get("in_position", False)),
            risk_score=risk,
        )
        event_corr = idle_event_correlation(
            idle.get("gaps", []),
            first_ts=self.first_ts,
            last_ts=self.last_ts,
            window_days=5,
        )

        active = [s for s in signs if s.get("active")]
        if risk >= 65:
            verdict = "Высокий риск затяжного простоя: Z в флэте/сжатии, до входа далеко."
        elif risk >= 40:
            verdict = "Умеренный риск затишья: часть TA-признаков совпадает с длинными паузами в истории."
        else:
            verdict = "Слабые признаки простоя: Z ещё «живой», возможен скорый сигнал."

        summary = f"Риск простоя {risk}/100 · активно {len(active)} из {len(signs)} предвестников. {verdict}"
        if forecast.get("applicable") and forecast.get("display_short"):
            summary += f" Прогноз: {forecast['display_short']}."

        return {
            "risk_score": risk,
            "signs": signs,
            "summary": summary,
            "verdict": verdict,
            "duration_forecast": forecast,
            "event_correlation": event_corr,
            "features": {k: round(v, 4) if isinstance(v, float) else v for k, v in feat.items()},
            "percentiles": {k: round(v, 1) for k, v in pct.items()},
            "idle_current": cur,
        }

    def market_context(self, entry: float = 0.8, exit_z: float = 0.7, idle: dict | None = None) -> dict:
        """Сравнение текущего рынка (конец ряда) с историческими распределениями."""
        zdf = self.zscore_frame()
        if zdf.empty:
            return {"as_of": self.last_ts or "", "metrics": []}

        z_arr = zdf["z_score"].to_numpy(dtype=float)
        sp_arr = zdf["spread_percent"].to_numpy(dtype=float)
        cur_z = float(z_arr[-1])
        cur_sp = float(sp_arr[-1])
        in_position = self.unrealized_pnl_rub != 0

        def _pct_le(arr: np.ndarray, x: float) -> float:
            return 100.0 * float(np.mean(arr <= x)) if len(arr) else 50.0

        def _hist(arr: np.ndarray, edges: list[float], labels: list[str]) -> list[dict]:
            counts, _ = np.histogram(arr, bins=edges)
            return [{"bucket": labels[i], "count": int(counts[i])} for i in range(len(labels))]

        def _bucket_for(x: float, edges: list[float], labels: list[str]) -> str:
            for i in range(len(labels)):
                if edges[i] <= x < edges[i + 1]:
                    return labels[i]
            return labels[-1]

        z_edges = [-999.0, -2.0, -1.5, -1.0, -0.5, 0.5, 1.0, 1.5, 2.0, 999.0]
        z_labels = ["≤-2", "-2…-1.5", "-1.5…-1", "-1…-0.5", "-0.5…0.5", "0.5…1", "1…1.5", "1.5…2", ">2"]
        z_pct = _pct_le(z_arr, cur_z)
        abs_pct = _pct_le(np.abs(z_arr), abs(cur_z))
        if abs(cur_z) >= 1.5:
            z_tone = "экстремальный"
        elif abs(cur_z) >= 1.0:
            z_tone = "повышенный"
        else:
            z_tone = "типичный"
        z_zone = ""
        if cur_z <= -entry:
            z_zone = " · зона входа LONG"
        elif cur_z >= entry:
            z_zone = " · зона входа SHORT"
        elif abs(cur_z) <= exit_z:
            z_zone = " · около exit"

        sp_lo, sp_hi = float(np.percentile(sp_arr, 2)), float(np.percentile(sp_arr, 98))
        n_sp_bins = 8
        sp_edges = np.linspace(sp_lo, sp_hi, n_sp_bins + 1).tolist()
        sp_edges[0] = -999.0
        sp_edges[-1] = 999.0
        sp_labels = [f"{sp_edges[i]:.1f}–{sp_edges[i+1]:.1f}" for i in range(n_sp_bins)]
        sp_pct = _pct_le(sp_arr, cur_sp)

        hold_hours: list[float] = [_hold_hours(t.entry_time, t.exit_time) for t in self.trades]

        hold_edges = [0.0, 4.0, 12.0, 24.0, 48.0, 72.0, 120.0, 9999.0]
        hold_labels = ["<4ч", "4–12ч", "12–24ч", "1–2д", "2–3д", "3–5д", ">5д"]
        cur_hold: float | None = None
        hold_highlight = False
        hold_label = "Нет открытой позиции"
        if in_position and self.trades:
            last_dt = _parse_ts_day(self.last_ts)
            entry_dt = _parse_ts_day(self.trades[-1].entry_time)
            cur_hold = (
                max(0.0, (last_dt - entry_dt).total_seconds() / 3600.0)
                if last_dt and entry_dt
                else None
            )
            h_pct = _pct_le(np.asarray(hold_hours), cur_hold) if hold_hours and cur_hold is not None else 50.0
            hold_label = (
                f"Удержание {cur_hold:.0f} ч — дольше {h_pct:.0f}% сделок"
                if cur_hold is not None
                else "Открыта позиция"
            )
            hold_highlight = True
        elif hold_hours:
            mean_h = float(np.mean(hold_hours))
            med_h = float(np.median(hold_hours))
            last_h = hold_hours[-1]
            cur_hold = med_h
            med_pct = _pct_le(np.asarray(hold_hours), med_h)
            hold_label = (
                f"Flat · медиана {med_h:.0f} ч ({med_pct:.0f}-й перц.) · "
                f"ср. {mean_h:.0f} ч · последняя {last_h:.0f} ч"
            )
            hold_highlight = True

        idle = idle if idle is not None else self.idle_gaps_analysis()
        idle_bucket_map = {h["bucket"]: h["count"] for h in idle["histogram"]}
        idle_order = ["0", "1", "2–3", "4–7", "8–14", "15–30", "31+"]
        idle_histogram = [{"bucket": k, "count": idle_bucket_map.get(k, 0)} for k in idle_order]
        idle_vals = [int(g["days"]) for g in idle["gaps"]]
        cur_idle = idle["current"]["idle_days"] if not idle["current"]["in_position"] else None
        idle_pct = _pct_le(np.asarray(idle_vals, dtype=float), float(cur_idle)) if idle_vals and cur_idle is not None else None

        def _idle_bucket(d: int) -> str:
            if d == 0:
                return "0"
            if d == 1:
                return "1"
            if d <= 3:
                return "2–3"
            if d <= 7:
                return "4–7"
            if d <= 14:
                return "8–14"
            if d <= 30:
                return "15–30"
            return "31+"

        metrics = [
            {
                "id": "z_score",
                "title": "Z-score сейчас vs история",
                "current_value": cur_z,
                "current_display": f"{cur_z:+.3f}",
                "percentile": round(z_pct, 1),
                "abs_percentile": round(abs_pct, 1),
                "histogram": _hist(z_arr, z_edges, z_labels),
                "current_bucket": _bucket_for(cur_z, z_edges, z_labels),
                "label": f"Z {cur_z:+.2f} — {z_tone} ({z_pct:.0f}-й перц., |Z| {abs_pct:.0f}%){z_zone}",
                "highlight_current": True,
            },
            {
                "id": "spread",
                "title": "Spread % vs история",
                "current_value": cur_sp,
                "current_display": f"{cur_sp:.3f}%",
                "percentile": round(sp_pct, 1),
                "histogram": _hist(sp_arr, sp_edges, sp_labels),
                "current_bucket": _bucket_for(cur_sp, sp_edges, sp_labels),
                "label": f"Spread {cur_sp:.2f}% — {sp_pct:.0f}-й перцентиль истории",
                "highlight_current": True,
            },
            {
                "id": "hold_hours",
                "title": "Длительность сделок (ч)",
                "current_value": cur_hold,
                "current_display": f"{cur_hold:.0f} ч (медиана)" if cur_hold is not None and not in_position else (f"{cur_hold:.0f} ч" if cur_hold is not None else "—"),
                "percentile": round(_pct_le(np.asarray(hold_hours), cur_hold), 1) if cur_hold is not None and hold_hours else None,
                "histogram": _hist(np.asarray(hold_hours), hold_edges, hold_labels) if hold_hours else [],
                "current_bucket": _bucket_for(cur_hold, hold_edges, hold_labels) if cur_hold is not None else "",
                "label": hold_label,
                "highlight_current": hold_highlight,
            },
            {
                "id": "idle_days",
                "title": "Дней без сделок",
                "current_value": cur_idle,
                "current_display": f"{cur_idle} дн." if cur_idle is not None else "—",
                "percentile": round(idle_pct, 1) if idle_pct is not None else None,
                "histogram": idle_histogram,
                "current_bucket": _idle_bucket(int(cur_idle or 0)) if cur_idle is not None else "",
                "label": idle["current"]["label"]
                + (f" · дольше {idle_pct:.0f}% пауз" if idle_pct is not None else ""),
                "highlight_current": cur_idle is not None and cur_idle > 0 and not in_position,
            },
        ]

        return {
            "as_of": self.last_ts,
            "as_of_display": pd.to_datetime(self.last_ts).strftime("%d.%m.%Y %H:%M") if self.last_ts else "",
            "in_position": in_position,
            "metrics": metrics,
        }


def apply_z_scores(spread_percent: Union[List[float], np.ndarray]) -> np.ndarray:
    """Z по всему ряду (legacy global — look-ahead на истории)."""
    if len(spread_percent) == 0:
        return np.array([])
    arr = np.array(spread_percent, dtype=float)
    mean = np.mean(arr)
    std = np.std(arr)
    if std <= 0:
        std = 1.0
    return (arr - mean) / std


def _parse_ts_msk(ts: str) -> Optional[datetime]:
    s = str(ts).strip()
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            n = 19 if fmt.endswith("S") else (16 if "H" in fmt[11:] else 10)
            dt = datetime.strptime(s[:n], fmt)
            return dt.replace(tzinfo=MSK)
        except ValueError:
            continue
    return None


def _ts_to_millis(ts: str) -> int:
    dt = _parse_ts_msk(ts)
    return int(dt.timestamp() * 1000) if dt else 0


def apply_z_scores_rolling(
    spread_percent: Union[List[float], np.ndarray],
    timestamps: List[str],
    lookback_days: int = Z_SCORE_ROLLING_LOOKBACK_DAYS,
    min_bars_in_window: int = Z_SCORE_ROLLING_MIN_BARS,
) -> List[float]:
    """Как applyZScoresRolling() — μ/σ только за последние lookback_days (MSK, без look-ahead)."""
    spreads = [float(x) for x in spread_percent]
    n = len(spreads)
    if n == 0:
        return []
    if len(timestamps) != n:
        raise ValueError("spread_percent and timestamps must have the same length")
    if lookback_days <= 0:
        return apply_z_scores(spreads).tolist()

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
            s = spreads[j]
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
            result.append((spreads[i] - mean) / std)

    return result


def z_scores_for_mode(
    spreads: List[float],
    timestamps: List[str],
    z_mode: ZMode,
) -> List[float]:
    if z_mode == "rolling30":
        return apply_z_scores_rolling(spreads, timestamps)
    return apply_z_scores(spreads).tolist()


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
    compound_returns: bool = False,
) -> pd.DataFrame:
    """Две симуляции на одном CSV: global vs rolling 30д."""
    rows = []
    sim_kw = {
        "notional_rub": notional_rub,
        "leverage": leverage,
        "commission_pct_per_side": commission_pct_per_side,
        "compound_returns": compound_returns,
    }
    for label, mode in (("Global 255д", "global"), ("Rolling 30д", "rolling30")):
        bars = load_bars_from_csv(path, recalc_z=True, z_mode=mode)
        diag = z_diagnostics(bars)
        sim = run_z_strategy_sim(bars, entry=entry, exit_z=exit_z, **sim_kw)
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


def _hold_hours(entry_ts: str, exit_ts: str) -> float:
    e = _parse_ts_day(entry_ts)
    x = _parse_ts_day(exit_ts)
    if not e or not x:
        return 0.0
    return max(0.0, (x - e).total_seconds() / 3600.0)


def spread_pnl_to_rub(pnl_spread_pts: float, effective_notional_rub: float) -> float:
    """spreadPnlToRubApprox — аргумент уже notional×leverage."""
    return effective_notional_rub * (pnl_spread_pts / 100.0)


def _entry_spread(sp: float, direction: Position, slip: float) -> float:
    if direction == Position.LONG:
        return sp + slip
    return sp - slip


def _exit_spread(sp: float, direction: Position, slip: float) -> float:
    if direction == Position.LONG:
        return sp - slip
    return sp + slip


def _leg_pnl_rub(side: str, entry: float, exit_price: float, qty: float) -> float:
    if side == "buy":
        return qty * (exit_price - entry)
    return qty * (entry - exit_price)


def _scale_leg_pnls(legs: List[OrderLeg], gross_pnl_rub: float) -> None:
    """Распределить валовый PnL сделки (по спреду) между ногами пропорционально ценам."""
    if not legs:
        return
    raw_sum = sum(leg.pnl_rub for leg in legs)
    if abs(raw_sum) > 1e-9:
        k = gross_pnl_rub / raw_sum
        legs[0].pnl_rub = round(legs[0].pnl_rub * k, 2)
        if len(legs) > 1:
            legs[1].pnl_rub = round(gross_pnl_rub - legs[0].pnl_rub, 2)
    else:
        legs[0].pnl_rub = round(gross_pnl_rub / len(legs), 2)
        for i in range(1, len(legs)):
            legs[i].pnl_rub = round(gross_pnl_rub - legs[0].pnl_rub, 2) if i == 1 else 0.0


def _order_legs(
    direction: Position,
    entry_tatn: float,
    entry_tatnp: float,
    exit_tatn: float,
    exit_tatnp: float,
    eff_notional: float,
    *,
    gross_pnl_rub: float | None = None,
) -> List[OrderLeg]:
    """Две ноги арбитража: половина номинала×плечо на каждую бумагу."""
    prices = (entry_tatn, entry_tatnp, exit_tatn, exit_tatnp)
    if any(p is None or p <= 0 for p in prices):
        return []
    half = eff_notional / 2.0

    def leg(ticker: str, side: str, entry: float, exit_p: float) -> OrderLeg:
        qty = half / entry
        return OrderLeg(
            ticker,
            side,
            entry,
            exit_p,
            qty,
            _leg_pnl_rub(side, entry, exit_p, qty),
        )

    if direction == Position.LONG:
        legs = [
            leg("TATN", "buy", entry_tatn, exit_tatn),
            leg("TATNP", "sell", entry_tatnp, exit_tatnp),
        ]
    else:
        legs = [
            leg("TATN", "sell", entry_tatn, exit_tatn),
            leg("TATNP", "buy", entry_tatnp, exit_tatnp),
        ]
    if gross_pnl_rub is not None:
        _scale_leg_pnls(legs, gross_pnl_rub)
    return legs


def run_z_strategy_sim(
    bars: List[Bar],
    entry: float,
    exit_z: float,
    notional_rub: float = 100_000.0,
    leverage: float = 7.0,
    commission_pct_per_side: float = 0.04,
    compound_returns: bool = False,
    slippage_spread_pts: float = 0.0,
    max_loss_spread_pts: float = 0.0,
    max_loss_rub: float = 0.0,
    min_spread_pct: float = 0.0,
    max_spread_pct: float = 0.0,
    entry_z_buffer: float = 0.0,
    max_drawdown_halt_rub: float = 0.0,
    max_drawdown_halt_pct: float = 0.0,
    underwater_exit_hours: float = 0.0,
    pyramid_add_notional_rub: float = 0.0,
    pyramid_z_depth: float = 1.0,
) -> SimResult:
    """
    Векторизованная симуляция стратегии.
    Использует NumPy для быстрого поиска сигналов (входа/выхода),
    но сохраняет итеративный цикл только для учета PnL сделок и капитализации.

    underwater_exit_hours: на первом баре с удержанием >= N ч, если net PnL < 0 — выход (сценарий A).
    0 — правило выключено (текущий тест / baseline).

    pyramid_add_notional_rub: добавка собственного номинала при углублении |Z| (0 = выкл.).
    pyramid_z_depth: long — Z <= -depth; short — Z >= +depth; одна добавка за круг (parity с Android).
    """
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
    entry_z = 0.0
    entry_time = ""
    entry_commission = 0.0
    entry_tatn_close: Optional[float] = None
    entry_tatnp_close: Optional[float] = None
    realized_rub = 0.0
    position_notional = notional_rub
    closed: List[ClosedTrade] = []
    signal_rows: List[dict] = []
    equity_history: List[float] = []
    total_commission = 0.0
    total_overnight = 0.0
    peak_equity = 0.0
    trading_halted = False
    halt_reason = ""
    underwater_checked = False
    pyramid_added = False
    slip = max(0.0, slippage_spread_pts)
    use_max_spread = max_spread_pct > 0.0
    uw_hours = max(0.0, float(underwater_exit_hours))
    pyramid_add = max(0.0, float(pyramid_add_notional_rub))
    pyramid_depth = max(0.0, float(pyramid_z_depth))
    use_pyramid = pyramid_add > 0.0

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

    def _check_halt(equity: float) -> None:
        nonlocal trading_halted, halt_reason, peak_equity
        peak_equity = max(peak_equity, equity)
        dd = peak_equity - equity
        if max_drawdown_halt_rub > 0 and dd >= max_drawdown_halt_rub:
            trading_halted = True
            halt_reason = f"drawdown_rub>={max_drawdown_halt_rub:.0f}"
            return
        if max_drawdown_halt_pct > 0 and peak_equity > 0:
            dd_pct = 100.0 * dd / peak_equity
            if dd_pct >= max_drawdown_halt_pct:
                trading_halted = True
                halt_reason = f"drawdown_pct>={max_drawdown_halt_pct:.1f}%"

    def _spread_ok(sp: float) -> bool:
        if min_spread_pct > 0 and sp < min_spread_pct:
            return False
        if use_max_spread and sp > max_spread_pct:
            return False
        return True

    def _entry_z_ok(z: float, direction: Position) -> bool:
        if entry_z_buffer <= 0:
            return True
        if direction == Position.LONG:
            return z <= -(entry + entry_z_buffer)
        return z >= entry + entry_z_buffer

    def _stop_loss_hit(bar: Bar) -> bool:
        if position == Position.FLAT:
            return False
        if position == Position.LONG:
            mtm_pts = bar.spread_percent - entry_spread
        else:
            mtm_pts = entry_spread - bar.spread_percent
        if max_loss_spread_pts > 0 and -mtm_pts >= max_loss_spread_pts:
            return True
        if max_loss_rub > 0:
            gross = spread_pnl_to_rub(mtm_pts, eff_notional)
            ovn = overnight_per_day * overnight_days(entry_time, bar.timestamp)
            net_loss = -(gross - comm_per_side - ovn)
            if net_loss >= max_loss_rub:
                return True
        return False

    def close_long(bar: Bar, exit_reason: str = "z_exit") -> None:
        nonlocal position, realized_rub, entry_commission, total_commission, total_overnight
        nonlocal entry_tatn_close, entry_tatnp_close, underwater_checked, pyramid_added
        exit_sp = _exit_spread(bar.spread_percent, Position.LONG, slip)
        pnl_pts = exit_sp - entry_spread
        gross = spread_pnl_to_rub(pnl_pts, eff_notional)
        ovn = overnight_per_day * overnight_days(entry_time, bar.timestamp)
        comm_total = entry_commission + comm_per_side
        net = gross - comm_total - ovn
        total_commission += comm_per_side
        total_overnight += ovn
        realized_rub += gross - comm_per_side - ovn
        legs: List[OrderLeg] = []
        if (
            entry_tatn_close is not None
            and entry_tatnp_close is not None
            and bar.tatn_close is not None
            and bar.tatnp_close is not None
        ):
            legs = _order_legs(
                Position.LONG,
                entry_tatn_close,
                entry_tatnp_close,
                bar.tatn_close,
                bar.tatnp_close,
                eff_notional,
                gross_pnl_rub=gross,
            )
        closed.append(
            ClosedTrade(
                Position.LONG, entry_time, bar.timestamp,
                entry_spread, exit_sp, entry_z, bar.z_score, pnl_pts, net,
                commission_rub=comm_total,
                overnight_rub=ovn,
                exit_reason=exit_reason,
                legs=legs,
            )
        )
        position = Position.FLAT
        entry_tatn_close = None
        entry_tatnp_close = None
        underwater_checked = False
        pyramid_added = False

    def close_short(bar: Bar, exit_reason: str = "z_exit") -> None:
        nonlocal position, realized_rub, entry_commission, total_commission, total_overnight
        nonlocal entry_tatn_close, entry_tatnp_close, underwater_checked, pyramid_added
        exit_sp = _exit_spread(bar.spread_percent, Position.SHORT, slip)
        pnl_pts = entry_spread - exit_sp
        gross = spread_pnl_to_rub(pnl_pts, eff_notional)
        ovn = overnight_per_day * overnight_days(entry_time, bar.timestamp)
        comm_total = entry_commission + comm_per_side
        net = gross - comm_total - ovn
        total_commission += comm_per_side
        total_overnight += ovn
        realized_rub += gross - comm_per_side - ovn
        legs = []
        if (
            entry_tatn_close is not None
            and entry_tatnp_close is not None
            and bar.tatn_close is not None
            and bar.tatnp_close is not None
        ):
            legs = _order_legs(
                Position.SHORT,
                entry_tatn_close,
                entry_tatnp_close,
                bar.tatn_close,
                bar.tatnp_close,
                eff_notional,
                gross_pnl_rub=gross,
            )
        closed.append(
            ClosedTrade(
                Position.SHORT, entry_time, bar.timestamp,
                entry_spread, exit_sp, entry_z, bar.z_score, pnl_pts, net,
                commission_rub=comm_total,
                overnight_rub=ovn,
                exit_reason=exit_reason,
                legs=legs,
            )
        )
        position = Position.FLAT
        entry_tatn_close = None
        entry_tatnp_close = None
        underwater_checked = False
        pyramid_added = False

    def enter_long(bar: Bar) -> None:
        nonlocal position, entry_spread, entry_z, entry_time, entry_commission, realized_rub, total_commission
        nonlocal entry_tatn_close, entry_tatnp_close, underwater_checked, pyramid_added
        apply_sizing_from_realized()
        underwater_checked = False
        pyramid_added = False
        position = Position.LONG
        entry_spread = _entry_spread(bar.spread_percent, Position.LONG, slip)
        entry_z = bar.z_score
        entry_time = bar.timestamp
        entry_tatn_close = bar.tatn_close
        entry_tatnp_close = bar.tatnp_close
        entry_commission = comm_per_side
        total_commission += comm_per_side
        realized_rub -= comm_per_side

    def enter_short(bar: Bar) -> None:
        nonlocal position, entry_spread, entry_z, entry_time, entry_commission, realized_rub, total_commission
        nonlocal entry_tatn_close, entry_tatnp_close, underwater_checked, pyramid_added
        apply_sizing_from_realized()
        underwater_checked = False
        pyramid_added = False
        position = Position.SHORT
        entry_spread = _entry_spread(bar.spread_percent, Position.SHORT, slip)
        entry_z = bar.z_score
        entry_time = bar.timestamp
        entry_tatn_close = bar.tatn_close
        entry_tatnp_close = bar.tatnp_close
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

    def _net_if_close_now(bar: Bar) -> float:
        """Net PnL сделки, если закрыть на этом баре (как при close_long/short)."""
        gross = mtm_rub(bar)
        ovn = overnight_per_day * overnight_days(entry_time, bar.timestamp)
        return gross - entry_commission - comm_per_side - ovn

    def _add_pyramid_leg(bar: Bar) -> None:
        nonlocal entry_spread, position_notional, eff_notional, comm_per_side, overnight_per_day
        nonlocal entry_commission, realized_rub, total_commission, pyramid_added
        add_n = pyramid_add
        entry_spread = (entry_spread * position_notional + bar.spread_percent * add_n) / (
            position_notional + add_n
        )
        position_notional += add_n
        eff_notional, comm_per_side, overnight_per_day = refresh_fees(position_notional)
        add_comm = add_n * leverage * (commission_pct_per_side / 100.0)
        entry_commission += add_comm
        total_commission += add_comm
        realized_rub -= add_comm
        pyramid_added = True

    for i in range(1, len(bars)):
        prev, cur = bars[i - 1], bars[i]
        sig = ""

        if position == Position.LONG:
            if use_pyramid and not pyramid_added and cur.z_score <= -pyramid_depth:
                _add_pyramid_leg(cur)
                sig = "pyramid_long"
            if _stop_loss_hit(cur):
                close_long(cur, "stop_loss")
                sig = "stop_loss_long"
            elif (
                uw_hours > 0
                and not underwater_checked
                and _hold_hours(entry_time, cur.timestamp) >= uw_hours - 1e-6
            ):
                underwater_checked = True
                if _net_if_close_now(cur) < 0:
                    close_long(cur, "underwater_cap")
                    sig = "underwater_long"
            elif prev.z_score < -exit_z and cur.z_score >= -exit_z:
                close_long(cur, "z_exit")
                sig = "exit_long"
        elif position == Position.SHORT:
            if use_pyramid and not pyramid_added and cur.z_score >= pyramid_depth:
                _add_pyramid_leg(cur)
                sig = "pyramid_short"
            if _stop_loss_hit(cur):
                close_short(cur, "stop_loss")
                sig = "stop_loss_short"
            elif (
                uw_hours > 0
                and not underwater_checked
                and _hold_hours(entry_time, cur.timestamp) >= uw_hours - 1e-6
            ):
                underwater_checked = True
                if _net_if_close_now(cur) < 0:
                    close_short(cur, "underwater_cap")
                    sig = "underwater_short"
            elif prev.z_score > exit_z and cur.z_score <= exit_z:
                close_short(cur, "z_exit")
                sig = "exit_short"

        if position == Position.FLAT and not trading_halted:
            if (
                prev.z_score > -entry
                and cur.z_score <= -entry
                and _spread_ok(cur.spread_percent)
                and _entry_z_ok(cur.z_score, Position.LONG)
            ):
                enter_long(cur)
                sig = "enter_long"
            elif (
                prev.z_score < entry
                and cur.z_score >= entry
                and _spread_ok(cur.spread_percent)
                and _entry_z_ok(cur.z_score, Position.SHORT)
            ):
                enter_short(cur)
                sig = "enter_short"

        equity = realized_rub + mtm_rub(cur)
        _check_halt(equity)
        equity_history.append(equity)
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
        total_overnight += ovn_u
        unrealized = gross_u - comm_per_side - ovn_u

    total = realized_rub + unrealized

    return SimResult(
        trades=closed,
        total_pnl_rub=total,
        realized_pnl_rub=realized_rub,
        unrealized_pnl_rub=unrealized,
        signals=pd.DataFrame(signal_rows),
        equity_history=equity_history,
        total_commission_rub=total_commission,
        total_overnight_rub=total_overnight,
        bar_count=len(bars),
        first_ts=bars[0].timestamp,
        last_ts=bars[-1].timestamp,
        trading_halted=trading_halted,
        halt_reason=halt_reason,
    )


def _pf_num(pf: float | None) -> float:
    if pf is None:
        return 0.0
    if pf == float("inf"):
        return 99.0
    return float(pf)


def analyze_oos_verdict(
    train: dict,
    test: dict,
    train_risk: dict,
    test_risk: dict,
    train_ratio: float,
    compound_returns: bool = False,
) -> dict:
    """Rule-based интерпретация train vs test (переобучение, устойчивость, что делать)."""
    train_tr = int(train.get("trade_count") or 0)
    test_tr = int(test.get("trade_count") or 0)
    test_pct = round(100 * (1.0 - train_ratio))

    if test_tr < 8:
        return {
            "grade": "weak_data",
            "title": "Мало данных на test",
            "summary": (
                f"На test ({test_pct}% истории) всего {test_tr} сделок — статистически мало для выводов."
            ),
            "signals": [
                f"Train: {train_tr} сделок · Test: {test_tr} сделок",
                "При малом test любой убыток или супер-прибыль могут быть случайностью",
            ],
            "actions": [
                "Уменьшите Train ratio (например 0.6) или возьмите более длинную историю CSV",
                "Смотрите на test только вместе со стресс-тестом ×2, а не как единственный критерий",
                "Не меняйте Entry/Exit только ради улучшения train",
            ],
        }

    train_pnl = float(train.get("total_pnl_rub") or 0)
    test_pnl = float(test.get("total_pnl_rub") or 0)
    train_avg = train_pnl / max(1, train_tr)
    test_avg = test_pnl / max(1, test_tr)
    train_pf = _pf_num(train.get("profit_factor"))
    test_pf = _pf_num(test.get("profit_factor"))
    train_wr = float(train.get("win_rate_pct") or 0)
    test_wr = float(test.get("win_rate_pct") or 0)
    train_dd = abs(float(train.get("max_drawdown_rub") or 0))
    test_dd = abs(float(test.get("max_drawdown_rub") or 0))

    signals: list[str] = []
    if compound_returns:
        signals.append(
            "Капитализация PnL: включена — train и test считаются отдельно, на границе split equity не переносится"
        )
    else:
        signals.append(
            "Капитализация PnL: выключена — фиксированный номинал (ближе к live без реинвестирования)"
        )

    overfit_pts = 0
    robust_pts = 0
    high_wr = train_wr >= 97 or test_wr >= 97
    dd_worse = test_dd > train_dd * 1.6 and test_dd > 0

    if train_pnl > 0 and test_pnl <= 0:
        signals.append(f"Train PnL {train_pnl:+.0f} ₽, test PnL {test_pnl:+.0f} ₽ — на «невидимом» периоде минус")
        overfit_pts += 3
    elif train_avg > 0 and test_avg < train_avg * 0.45:
        signals.append(
            f"Ср. PnL/сделку: train {train_avg:+.0f} ₽ vs test {test_avg:+.0f} ₽ "
            f"({100 * test_avg / train_avg:.0f}% от train)"
        )
        overfit_pts += 2
    elif test_avg >= train_avg * 0.55 and test_pnl > 0:
        signals.append(f"Ср. PnL/сделку на test удержался ({test_avg:+.0f} ₽ vs train {train_avg:+.0f} ₽)")
        robust_pts += 2

    if train_pf >= 1.3 and test_pf < 1.0:
        signals.append(f"Profit Factor: train {train_pf:.2f} → test {test_pf:.2f} (< 1)")
        overfit_pts += 2
    elif test_pf >= 1.15:
        pf_label = "∞" if test_pf >= 99 else f"{test_pf:.2f}"
        signals.append(f"Profit Factor на test {pf_label} — приемлемо")
        robust_pts += 1

    if train_wr - test_wr > 12:
        signals.append(f"Win rate: train {train_wr:.1f}% → test {test_wr:.1f}%")
        overfit_pts += 1

    if high_wr:
        signals.append(
            f"Win rate {max(train_wr, test_wr):.1f}% — проверьте реализм (комиссии, slippage, фильтры spread)"
        )
        overfit_pts += 1

    if dd_worse:
        signals.append(f"Max DD на test ({test_dd:.0f} ₽) заметно выше train ({train_dd:.0f} ₽)")
        overfit_pts += 1

    tail_t = train_risk.get("tail_ratio")
    tail_v = test_risk.get("tail_ratio")
    if tail_v is not None and tail_t is not None and tail_t > 0.5 and tail_v < tail_t * 0.5:
        signals.append(f"Tail ratio упал на test ({tail_v:.2f} vs train {tail_t:.2f}) — другой профиль риска")
        overfit_pts += 1
    elif tail_v is not None and tail_t is not None and tail_v > tail_t * 1.4:
        signals.append(f"Tail ratio вырос на test ({tail_v:.2f} vs train {tail_t:.2f}) — хвостовые риски")
        overfit_pts += 1

    if test_tr < 20:
        signals.append(f"На test только {test_tr} сделок — выводы предварительные")
        overfit_pts += 1

    if overfit_pts >= 4:
        grade = "overfit"
        title = "Признаки переобучения"
        summary = (
            "Train выглядит сильно, test заметно слабее. Параметры, вероятно, подогнаны под первую часть истории."
        )
        actions = [
            "Не переносите в live текущие Entry/Exit без проверки на другом периоде или train_ratio 0.6",
            "Запустите «Стресс ×2» и добавьте slippage — переобученные стратегии часто ломаются на издержках",
            "Упростите правила: буфер Entry Z, фильтры spread, stop-loss / max DD halt",
            "Подбирайте параметры на train, финальную оценку делайте только по test (walk-forward)",
        ]
    elif overfit_pts >= 2:
        grade = "caution"
        title = "Умеренный риск переобучения"
        summary = "Test не полностью подтверждает train — стратегия может быть чувствительна к режиму рынка."
        actions = [
            "Сравните OOS при train_ratio 0.6 и 0.8 — результат должен быть похожим по знаку PnL",
            "Не увеличивайте номинал/плечо, пока test не стабилен",
            "Смотрите heatmap Entry×Exit: выберите плато параметров, а не один пик",
        ]
    elif test_pnl > 0 and test_pf >= 1.0 and robust_pts >= 2:
        grade = "good"
        title = "Test подтверждает train"
        summary = "На отложенном периоде метрики согласованы с train — переобучение не доминирует."
        actions = [
            "Можно рассматривать осторожный live с текущими порогами и лимитами риска",
            "Раз в 1–2 недели пересчитывайте OOS на свежих MOEX-данных",
            "Держите circuit breaker (max DD halt) и стресс-тест в чек-листе перед увеличением размера",
        ]
    elif test_pnl <= 0:
        grade = "caution"
        title = "Test не прибылен"
        summary = "На последнем отрезке истории стратегия не заработала — осторожность обязательна."
        actions = [
            "Не масштабируйте позицию; проверьте фильтры Min/Max spread и стресс ×2",
            "Смените Entry/Exit только если улучшение видно и на test, не только на train",
        ]
    else:
        grade = "good"
        title = "Приемлемо"
        summary = "Явного разрыва train/test нет, но следите за числом сделок на test."
        actions = [
            "Повторите OOS после дозагрузки свежих баров с MOEX",
            "Сверьте результат со стресс-тестом и max DD halt",
        ]

    if grade == "good" and (high_wr or dd_worse):
        grade = "caution"
        title = "Test прибылен, но есть оговорки"
        parts: list[str] = []
        if high_wr:
            parts.append("win rate ≥ 97%")
        if dd_worse:
            parts.append("Max DD на test заметно выше train")
        summary = (
            f"PnL на test согласован с train, но {' и '.join(parts)} — "
            "полного подтверждения для live нет."
        )
        actions = [
            "Запустите «Стресс ×2» и добавьте slippage перед live",
            "Не увеличивайте номинал/плечо, пока DD и win rate не выглядят реалистично",
        ]
        if compound_returns:
            actions.insert(
                0,
                "Сравните OOS с капитализацией и без — для live без реинвестирования важнее режим «выкл»",
            )
        else:
            actions.insert(
                0,
                "При включении капитализации вердикт может стать мягче — ориентируйтесь на ваш реальный режим",
            )

    if compound_returns and grade == "good":
        actions.append("OOS с капитализацией завышает train DD — сравните с фиксированным номиналом")

    return {
        "grade": grade,
        "title": title,
        "summary": summary,
        "signals": signals or [f"Train {train_tr} сделок / Test {test_tr} сделок · split {train_ratio:.0%}/{1-train_ratio:.0%}"],
        "actions": actions,
    }


def run_oos_evaluation(bars: List[Bar], train_ratio: float = 0.7, **sim_kw) -> dict:
    split = int(len(bars) * train_ratio)
    if split < 2 or split >= len(bars):
        return {
            "train_ratio": train_ratio,
            "split_bar": split,
            "error": "insufficient bars for OOS split",
        }
    train_bars = bars[:split]
    test_bars = bars[split:]
    train = run_z_strategy_sim(train_bars, **sim_kw)
    test = run_z_strategy_sim(test_bars, **sim_kw)
    train_stats = train.stats()
    test_stats = test.stats()
    train_risk = train.risk_metrics()
    test_risk = test.risk_metrics()
    compound = bool(sim_kw.get("compound_returns"))
    return {
        "train_ratio": train_ratio,
        "split_bar": split,
        "compound_returns": compound,
        "train": train_stats,
        "test": test_stats,
        "train_risk": train_risk,
        "test_risk": test_risk,
        "verdict": analyze_oos_verdict(
            train_stats, test_stats, train_risk, test_risk, train_ratio, compound_returns=compound
        ),
    }


DEFAULT_PROTECTION_GRID: dict[str, list[float]] = {
    "entry": [0.65, 0.75, 0.8, 0.85, 0.95],
    "exit_z": [0.55, 0.65, 0.75, 0.85],
    "slippage_spread_pts": [0.0, 0.02, 0.05],
    "max_loss_spread_pts": [0.0, 0.25],
    "max_loss_rub": [0.0, 8_000.0],
    "min_spread_pct": [0.0],
    "max_spread_pct": [0.0, 0.12],
    "entry_z_buffer": [0.0, 0.1],
    "max_drawdown_halt_rub": [0.0, 12_000.0],
    "max_drawdown_halt_pct": [0.0, 10.0],
}


def _cap_combo_list(combos: list[dict], max_combinations: int) -> list[dict]:
    if max_combinations <= 0 or len(combos) <= max_combinations:
        return combos
    step = len(combos) / max_combinations
    return [combos[int(i * step)] for i in range(max_combinations)]


def build_protection_grid_combos(
    axes: Optional[dict[str, list[float]]] = None,
    max_combinations: int = 400,
) -> list[dict[str, float]]:
    """Cartesian grid Entry×Exit×Защита; при переполнении — равномерная выборка."""
    src = axes if axes is not None else DEFAULT_PROTECTION_GRID
    keys = [
        "entry",
        "exit_z",
        "slippage_spread_pts",
        "max_loss_spread_pts",
        "max_loss_rub",
        "min_spread_pct",
        "max_spread_pct",
        "entry_z_buffer",
        "max_drawdown_halt_rub",
        "max_drawdown_halt_pct",
    ]
    lists = [src.get(k, [0.0]) for k in keys]
    combos: list[dict[str, float]] = []
    for vals in product(*lists):
        row = dict(zip(keys, vals))
        if row["exit_z"] >= row["entry"]:
            continue
        combos.append(row)
    return _cap_combo_list(combos, max_combinations)


log = logging.getLogger(__name__)

# Per-process cache when using ProcessPoolExecutor (Windows spawn).
_WORKER_BARS_CACHE: dict[str, List[Bar]] = {}


def default_parallel_workers() -> int:
    """Default worker count: leave one CPU, cap at 8."""
    n = os.cpu_count() or 4
    return min(8, max(1, n - 1))


def resolve_parallel_workers(
    requested: Optional[int],
    bar_count: int,
) -> tuple[int, Optional[str]]:
    """
    Effective ProcessPool size. requested 0/1 → sequential (1).
    Large bar series reduce workers to limit RAM (each worker loads CSV).
    """
    if requested is not None and requested <= 1:
        return 1, None

    if requested is None or requested < 0:
        base = default_parallel_workers()
    else:
        base = min(int(requested), 32)

    warning: Optional[str] = None
    capped = base
    if bar_count > 12_000:
        capped = min(capped, 2)
        if capped < base:
            warning = (
                f"Много баров ({bar_count}): процессов ограничено до {capped} "
                "(каждый воркер держит копию ряда в RAM)."
            )
    elif bar_count > 8_000:
        capped = min(capped, 4)
        if capped < base:
            warning = (
                f"Большой ряд ({bar_count} баров): процессов снижено до {capped} "
                "из‑за риска нехватки памяти."
            )

    return max(1, capped), warning


def _protection_sim_common(
    *,
    notional_rub: float,
    leverage: float,
    commission_pct_per_side: float,
    compound_returns: bool,
) -> dict[str, Any]:
    return {
        "notional_rub": notional_rub,
        "leverage": leverage,
        "commission_pct_per_side": commission_pct_per_side,
        "compound_returns": compound_returns,
    }


def _sim_kw_from_combo(combo: dict[str, float], common: dict[str, Any]) -> dict[str, Any]:
    return {
        "entry": float(combo["entry"]),
        "exit_z": float(combo["exit_z"]),
        "notional_rub": common["notional_rub"],
        "leverage": common["leverage"],
        "commission_pct_per_side": common["commission_pct_per_side"],
        "compound_returns": common["compound_returns"],
        "slippage_spread_pts": float(combo["slippage_spread_pts"]),
        "max_loss_spread_pts": float(combo["max_loss_spread_pts"]),
        "max_loss_rub": float(combo["max_loss_rub"]),
        "min_spread_pct": float(combo["min_spread_pct"]),
        "max_spread_pct": float(combo["max_spread_pct"]),
        "entry_z_buffer": float(combo["entry_z_buffer"]),
        "max_drawdown_halt_rub": float(combo["max_drawdown_halt_rub"]),
        "max_drawdown_halt_pct": float(combo["max_drawdown_halt_pct"]),
    }


def _evaluate_protection_combo(
    bars: List[Bar],
    combo: dict[str, float],
    common: dict[str, Any],
    *,
    train_ratio: float,
    only_good: bool,
) -> Optional[dict[str, Any]]:
    sim_kw = _sim_kw_from_combo(combo, common)
    full = run_z_strategy_sim(bars, **sim_kw)
    stats = full.stats()
    oos = run_oos_evaluation(bars, train_ratio=train_ratio, **sim_kw)
    verdict = oos.get("verdict") or {}
    grade = verdict.get("grade", "")
    if only_good and grade != "good":
        return None
    test = oos.get("test") or {}
    return {
        **combo,
        "notional_rub": common["notional_rub"],
        "leverage": common["leverage"],
        "commission_pct_per_side": common["commission_pct_per_side"],
        "compound_returns": common["compound_returns"],
        "stats": stats,
        "oos": {
            "train_ratio": oos.get("train_ratio"),
            "train": oos.get("train"),
            "test": test,
            "verdict": verdict,
        },
        "verdict_grade": grade,
        "test_pnl_rub": float(test.get("total_pnl_rub") or 0),
        "train_pnl_rub": float((oos.get("train") or {}).get("total_pnl_rub") or 0),
    }


def _protection_grid_combo_worker(
    task: tuple[str, dict[str, float], dict[str, Any], float, bool],
) -> Optional[dict[str, Any]]:
    """Top-level worker for ProcessPoolExecutor (picklable on Windows spawn)."""
    csv_path, combo, common, train_ratio, only_good = task
    bars = _WORKER_BARS_CACHE.get(csv_path)
    if bars is None:
        bars = load_bars_from_csv(csv_path, recalc_z=False)
        _WORKER_BARS_CACHE[csv_path] = bars
    return _evaluate_protection_combo(
        bars, combo, common, train_ratio=train_ratio, only_good=only_good
    )


def run_protection_grid_search(
    bars: List[Bar],
    *,
    csv_path: Optional[str] = None,
    parallel_workers: Optional[int] = None,
    notional_rub: float = 100_000.0,
    leverage: float = 7.0,
    commission_pct_per_side: float = 0.04,
    compound_returns: bool = False,
    train_ratio: float = 0.7,
    max_combinations: int = 400,
    top_n: int = 25,
    only_good: bool = True,
    axes: Optional[dict[str, list[float]]] = None,
    progress_cb: Optional[Callable[[float], None]] = None,
) -> dict[str, Any]:
    """
    Перебор Entry/Exit и параметров «Защита» с OOS-вердиктом.
    Возвращает строки с grade=='good' (если only_good), отсортированные по test PnL.

    parallel_workers: None → default (cpu_count-1, max 8); 0/1 → последовательно;
    >1 → ProcessPoolExecutor (нужен csv_path — бари грузятся в каждом процессе).
    """
    from concurrent.futures import ProcessPoolExecutor, as_completed

    combos = build_protection_grid_combos(axes, max_combinations)
    total = max(1, len(combos))
    common = _protection_sim_common(
        notional_rub=notional_rub,
        leverage=leverage,
        commission_pct_per_side=commission_pct_per_side,
        compound_returns=compound_returns,
    )
    workers, memory_warning = resolve_parallel_workers(parallel_workers, len(bars))
    use_pool = workers > 1 and bool(csv_path)
    if workers > 1 and not csv_path:
        log.warning("parallel grid search requested but csv_path missing — running sequential")
        workers = 1

    rows: list[dict[str, Any]] = []
    evaluated = 0

    def _consume(row: Optional[dict[str, Any]]) -> None:
        nonlocal evaluated
        evaluated += 1
        if progress_cb:
            progress_cb(evaluated / total)
        if evaluated % 50 == 0 or evaluated == total:
            log.info("protection grid search %d/%d", evaluated, total)
        if row is not None:
            rows.append(row)

    if use_pool:
        tasks = [
            (str(csv_path), combo, common, train_ratio, only_good) for combo in combos
        ]
        with ProcessPoolExecutor(max_workers=workers) as pool:
            futures = [pool.submit(_protection_grid_combo_worker, t) for t in tasks]
            for fut in as_completed(futures):
                try:
                    _consume(fut.result())
                except Exception:
                    log.exception("grid combo worker failed")
                    _consume(None)
    else:
        for combo in combos:
            row = _evaluate_protection_combo(
                bars,
                combo,
                common,
                train_ratio=train_ratio,
                only_good=only_good,
            )
            _consume(row)

    rows.sort(key=lambda r: (r["test_pnl_rub"], r["stats"].get("total_pnl_rub", 0)), reverse=True)
    top = rows[:top_n] if top_n > 0 else rows
    return {
        "evaluated": evaluated,
        "combinations_planned": len(combos),
        "good_count": len(rows),
        "rows": top,
        "all_good_rows": rows,
        "parallel_workers_used": workers if use_pool else 1,
        "parallel_workers_requested": parallel_workers,
        "memory_warning": memory_warning,
    }


def param_sweep(
    bars: List[Bar],
    entries: List[float],
    exits: List[float],
    progress_cb=None,
    **sim_kw,
) -> pd.DataFrame:
    rows: list[dict] = []
    total = max(1, len(entries) * len(exits))
    step = 0
    for entry in entries:
        for exit_z in exits:
            res = run_z_strategy_sim(bars, entry=entry, exit_z=exit_z, **sim_kw)
            st = res.stats()
            rows.append(
                {
                    "entry": entry,
                    "exit_z": exit_z,
                    "total_pnl_rub": res.total_pnl_rub,
                    "trade_count": st["trade_count"],
                    "max_drawdown_rub": st["max_drawdown_rub"],
                }
            )
            step += 1
            if progress_cb:
                progress_cb(step / total)
    return pd.DataFrame(rows)


def load_bars_from_csv(
    path: str,
    recalc_z: bool = True,
    z_mode: ZMode = "rolling30",
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

    has_tatn = "tatn_close" in df.columns
    has_tatnp = "tatnp_close" in df.columns
    tatn = df["tatn_close"] if has_tatn else None
    tatnp = df["tatnp_close"] if has_tatnp else None

    def _opt(col: str, i: int) -> Optional[float]:
        if col not in df.columns:
            return None
        v = df[col].iloc[i]
        return float(v) if pd.notna(v) else None

    bars: List[Bar] = []
    for i, (ts, z, sp) in enumerate(zip(df["timestamp"], zs, spreads)):
        t_close = float(tatn.iloc[i]) if tatn is not None and pd.notna(tatn.iloc[i]) else None
        p_close = float(tatnp.iloc[i]) if tatnp is not None and pd.notna(tatnp.iloc[i]) else None
        bars.append(
            Bar(
                timestamp=str(ts),
                z_score=float(z),
                spread_percent=float(sp),
                tatn_close=t_close,
                tatnp_close=p_close,
                tatn_open=_opt("tatn_open", i),
                tatn_high=_opt("tatn_high", i),
                tatn_low=_opt("tatn_low", i),
                tatnp_open=_opt("tatnp_open", i),
                tatnp_high=_opt("tatnp_high", i),
                tatnp_low=_opt("tatnp_low", i),
                spread_open=_opt("spread_open", i),
                spread_high=_opt("spread_high", i),
                spread_low=_opt("spread_low", i),
                spread_close=_opt("spread_close", i),
                tatn_volume=_opt("tatn_volume", i),
                tatnp_volume=_opt("tatnp_volume", i),
            )
        )
    return bars


def history_quality(bars: List[Bar], z_mode: ZMode = "rolling30") -> dict:
    """Подсказка: совпадает ли ряд с Android (255д ≈ 13k+ баров)."""
    n = len(bars)
    ok = n >= MIN_BARS_FULL_HISTORY
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
        "z_mode": z_mode,
        "expected_trades_hint": trade_hint,
    }
