#!/usr/bin/env python3
"""Basket backtest: TATN + MTLR, 50k notional each, ~1y m15 spread levels.

Independent legs (max 1 open per pair). Flat PnL + MTM max DD. Shelf via
pref_hang_screener «полка». Optional TATN tip1m sidecar (same levels/window).
Does NOT touch Prod / Watchdog.
"""
from __future__ import annotations

import json
import statistics
import sys
import time
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from live.pref_hang_screener import (  # noqa: E402
    LOOKBACK_DAYS as HANG_LOOKBACK,
    _SHELF_MIN_DAYS,
    _iss_history_closes,
    _percentile,
    _spread_series,
    classify_status,
)
from live.spread_regime import (  # noqa: E402
    SPREAD_WIDTH_NARROW_MAX,
    SPREAD_WIDTH_WIDE_MIN,
)
from m15_iss_loader import (  # noqa: E402
    _build_m15_frame,
    _fetch_candles,
    _recalc_z,
)
from replay.tip_touch import COMM_PCT, LEVERAGE  # noqa: E402
from zsim import OVERNIGHT_FEE_PERCENT_PER_DAY  # noqa: E402

MSK = ZoneInfo("Europe/Moscow")
OUT = ROOT / "data" / "_bt_basket_tatn_mtlr_50k.json"
TATN_CSV = ROOT / "data" / "m15_tatn_400d_basket.csv"
MTLR_CSV = ROOT / "data" / "m15_mtlr_400d.csv"

NOTIONAL = 50_000.0
SLIP = 0.02
LOOKBACK_DAYS = 400
WINDOW_DAYS = 365

# Live base TATN wardrobe
TATN_W = (6.2, 5.8)
TATN_N = (3.2, 4.0)
TATN_CUTS = (SPREAD_WIDTH_NARROW_MAX, SPREAD_WIDTH_WIDE_MIN)  # 3.5 / 5.5

# MTLR optimum from _bt_mtlr_365d_levels (user-rounded)
MTLR_W = (8.9, 8.4)
MTLR_N = (3.2, 4.3)
# Adaptive cuts from that sweep (Mechel year ~p40/p60)
MTLR_CUTS = (4.52, 6.48)


def _fees(notional: float) -> tuple[float, float, float]:
    eff = notional * LEVERAGE
    comm = eff * (COMM_PCT / 100.0)
    ovn = notional * max(0.0, LEVERAGE - 1.0) * (OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)
    return eff, comm, ovn


def classify(sp: float, narrow_max: float, wide_min: float) -> str:
    if sp < narrow_max:
        return "narrow"
    if sp > wide_min:
        return "wide"
    return "transition"


def fetch_m15_generic(ord_: str, pref: str, days: int) -> pd.DataFrame:
    """Same closed m15 builder for both pairs (no tip1m overlay)."""
    end = datetime.now(tz=MSK)
    start = (end - timedelta(days=int(days))).strftime("%Y-%m-%d")
    till = end.strftime("%Y-%m-%d")
    print(f"ISS 10m {ord_}+{pref} {start} … {till}", flush=True)
    ord10 = _fetch_candles(ord_, start, till, interval=10)
    pref10 = _fetch_candles(pref, start, till, interval=10)
    print(f"  candles 10m: {ord_}={len(ord10)} {pref}={len(pref10)}", flush=True)
    if ord10.empty or pref10.empty:
        raise RuntimeError(f"empty ISS candles for {ord_}/{pref}")
    frame = _build_m15_frame(ord10, pref10, include_forming=False)
    frame = frame.rename(columns={"tatn_close": "ord_close", "tatnp_close": "pref_close"})
    tmp = frame.copy()
    tmp["timestamp"] = pd.to_datetime(tmp["timestamp"])
    tmp = _recalc_z(tmp)
    tmp["timestamp"] = tmp["timestamp"].dt.strftime("%Y-%m-%d %H:%M:%S")
    return tmp[["timestamp", "z_score", "spread_percent", "ord_close", "pref_close"]]


def load_pair_csv(
    path: Path,
    *,
    ord_: str,
    pref: str,
    force_refresh: bool = False,
) -> pd.DataFrame:
    reuse = False
    if path.exists() and not force_refresh:
        df = pd.read_csv(path)
        try:
            last = pd.to_datetime(df["timestamp"].iloc[-1])
            age_h = (
                datetime.now() - last.to_pydatetime().replace(tzinfo=None)
            ).total_seconds() / 3600
            if age_h < 36 and len(df) > 5000:
                reuse = True
                print(f"reuse {path.name} rows={len(df)} age_h={age_h:.0f}", flush=True)
        except Exception:
            reuse = False
        if reuse:
            if "ord_close" not in df.columns and "tatn_close" in df.columns:
                df = df.rename(
                    columns={"tatn_close": "ord_close", "tatnp_close": "pref_close"}
                )
            return df
    df = fetch_m15_generic(ord_, pref, LOOKBACK_DAYS)
    if "ord_close" not in df.columns and "tatn_close" in df.columns:
        out = df.rename(columns={"tatn_close": "ord_close", "tatnp_close": "pref_close"})
    else:
        out = df
    # Persist TATN-style column names for cache compatibility
    save = out.rename(columns={"ord_close": "tatn_close", "pref_close": "tatnp_close"})
    save.to_csv(path, index=False)
    print(f"wrote {path} rows={len(save)}", flush=True)
    if "ord_close" not in out.columns:
        out = out.rename(columns={"tatn_close": "ord_close", "tatnp_close": "pref_close"})
    return out


def window_slice(
    df: pd.DataFrame, *, end: pd.Timestamp, window_days: int = WINDOW_DAYS
) -> pd.DataFrame:
    ts = pd.to_datetime(df["timestamp"])
    start = end - pd.Timedelta(days=window_days)
    mask = (ts >= start) & (ts <= end)
    sub = df.loc[mask].reset_index(drop=True)
    return sub


def sim_leg(
    df: pd.DataFrame,
    *,
    enter_wide: float,
    exit_wide: float,
    enter_narrow: float,
    exit_narrow: float,
    narrow_max: float,
    wide_min: float,
    slip: float = SLIP,
    notional: float = NOTIONAL,
    use_regime: bool = True,
    label: str = "",
) -> dict[str, Any]:
    """Closed-bar m15 touch sim with flat PnL and MTM equity/DD."""
    ts = pd.to_datetime(df["timestamp"])
    sp = df["spread_percent"].astype(float).to_numpy()
    day_ord = ts.dt.normalize().map(lambda d: d.toordinal()).to_numpy(dtype=np.int64)
    dates_str = ts.dt.strftime("%Y-%m-%d %H:%M:%S").to_numpy()
    day_str = ts.dt.strftime("%Y-%m-%d").to_numpy()

    pos = 0
    entry_sp = 0.0
    entry_day = 0
    entry_i = 0
    entry_side = 0
    entry_eff = entry_comm = entry_ovn_day = exit_comm = 0.0
    base = float(notional)
    realized = 0.0
    peak_mtm = 0.0
    max_dd_mtm = 0.0
    peak_flat = 0.0
    max_dd_flat = 0.0
    closed = wins = 0
    hold_days: list[float] = []
    monthly: dict[str, float] = {}
    trades: list[dict[str, Any]] = []
    # Daily last equity / position / mtm for shelf merge
    daily_last: dict[str, dict[str, Any]] = {}

    equity_ts: list[str] = []
    equity_mtm: list[float] = []
    pos_series: list[int] = []

    def _unrealized(i: int) -> float:
        if pos == 0:
            return 0.0
        cur = float(sp[i])
        is_long = pos == 1
        # MTM without exit slip/exit comm (parity tip_touch._mtm_at)
        pnl_pts = (cur - entry_sp) if is_long else (entry_sp - cur)
        gross = entry_eff * (pnl_pts / 100.0)
        ovn = entry_ovn_day * max(0, int(day_ord[i]) - entry_day)
        return gross - entry_comm - ovn

    n = len(sp)
    for i in range(n):
        if i >= 1:
            prev = float(sp[i - 1])
            cur = float(sp[i])
            sig = 0
            if pos == 0:
                if prev < enter_wide and cur >= enter_wide:
                    if (not use_regime) or classify(cur, narrow_max, wide_min) == "wide":
                        sig = 2
                elif prev > enter_narrow and cur <= enter_narrow:
                    if (not use_regime) or classify(cur, narrow_max, wide_min) == "narrow":
                        sig = 1
            elif pos == 1:
                if prev < exit_narrow and cur >= exit_narrow:
                    sig = 3
            elif pos == 2:
                if prev > exit_wide and cur <= exit_wide:
                    sig = 4

            if sig in (1, 2):
                entry_eff, entry_comm, entry_ovn_day = _fees(base)
                exit_comm = entry_comm
                entry_sp = cur + slip if sig == 1 else cur - slip
                entry_day = int(day_ord[i])
                entry_i = i
                entry_side = sig
                pos = sig
            elif sig in (3, 4):
                is_long = pos == 1
                exit_sp = cur - slip if is_long else cur + slip
                pnl_pts = (exit_sp - entry_sp) if is_long else (entry_sp - exit_sp)
                gross = entry_eff * (pnl_pts / 100.0)
                ovn = entry_ovn_day * max(0, int(day_ord[i]) - entry_day)
                net = gross - (entry_comm + exit_comm) - ovn
                realized += net
                closed += 1
                if net > 0:
                    wins += 1
                hd = max(0, int(day_ord[i]) - entry_day)
                hold_days.append(float(hd))
                ym = str(dates_str[i])[:7]
                monthly[ym] = monthly.get(ym, 0.0) + net
                if realized > peak_flat:
                    peak_flat = realized
                max_dd_flat = max(max_dd_flat, peak_flat - realized)
                trades.append(
                    {
                        "side": "Long" if entry_side == 1 else "Short",
                        "entry": str(dates_str[entry_i]),
                        "exit": str(dates_str[i]),
                        "entry_S": round(entry_sp, 4),
                        "exit_S": round(exit_sp, 4),
                        "hold_days": hd,
                        "pnl": round(net, 2),
                    }
                )
                pos = 0

        mtm_eq = realized + _unrealized(i)
        equity_ts.append(str(dates_str[i]))
        equity_mtm.append(mtm_eq)
        pos_series.append(pos)
        if mtm_eq > peak_mtm:
            peak_mtm = mtm_eq
        max_dd_mtm = max(max_dd_mtm, peak_mtm - mtm_eq)
        d = str(day_str[i])
        daily_last[d] = {
            "equity_mtm": mtm_eq,
            "realized": realized,
            "pos": pos,
            "unrealized": _unrealized(i),
            "S": float(sp[i]),
        }

    # Force-mark open at end (not closed — flat PnL excludes; MTM includes)
    open_at_end = pos != 0
    open_mtm = _unrealized(n - 1) if open_at_end and n else 0.0

    worst_month = None
    if monthly:
        wm = min(monthly.items(), key=lambda kv: kv[1])
        worst_month = {"month": wm[0], "pnl": round(wm[1], 2)}

    pnl_dd = round(realized / max_dd_mtm, 3) if max_dd_mtm > 1e-9 else None
    return {
        "label": label,
        "levels": {
            "wide": [enter_wide, exit_wide],
            "narrow": [enter_narrow, exit_narrow],
            "cuts": [narrow_max, wide_min],
            "use_regime": use_regime,
            "slip": slip,
            "notional": notional,
        },
        "window": {
            "from": str(dates_str[0]) if n else None,
            "to": str(dates_str[-1]) if n else None,
            "n_bars": n,
        },
        "flat_pnl": round(realized, 2),
        "ret_pct": round(100.0 * realized / base, 2) if base else 0.0,
        "max_dd_mtm": round(max_dd_mtm, 2),
        "max_dd_flat_closed": round(max_dd_flat, 2),
        "pnl_dd": pnl_dd,
        "trades": closed,
        "wins": wins,
        "winrate": round(100.0 * wins / closed, 1) if closed else 0.0,
        "avg_hold_days": round(sum(hold_days) / len(hold_days), 2) if hold_days else 0.0,
        "max_hold_days": round(max(hold_days), 2) if hold_days else 0.0,
        "worst_month": worst_month,
        "monthly_pnl": {k: round(v, 2) for k, v in sorted(monthly.items())},
        "open_at_end": open_at_end,
        "open_mtm": round(open_mtm, 2),
        "final_equity_mtm": round(equity_mtm[-1], 2) if equity_mtm else 0.0,
        "trade_list": trades,
        "_equity_ts": equity_ts,
        "_equity_mtm": equity_mtm,
        "_pos": pos_series,
        "_daily": daily_last,
    }


def basket_from_legs(a: dict[str, Any], b: dict[str, Any]) -> dict[str, Any]:
    """Sum MTM equities on union of timestamps (ffill each leg)."""
    sa = pd.Series(a["_equity_mtm"], index=pd.to_datetime(a["_equity_ts"]))
    sb = pd.Series(b["_equity_mtm"], index=pd.to_datetime(b["_equity_ts"]))
    idx = sa.index.union(sb.index).sort_values()
    ea = sa.reindex(idx).ffill().fillna(0.0)
    eb = sb.reindex(idx).ffill().fillna(0.0)
    eq = ea + eb
    peak = eq.cummax()
    dd = (peak - eq).max()
    flat = float(a["flat_pnl"]) + float(b["flat_pnl"])
    # monthly from legs
    months = sorted(set(a["monthly_pnl"]) | set(b["monthly_pnl"]))
    monthly = {
        m: round(a["monthly_pnl"].get(m, 0.0) + b["monthly_pnl"].get(m, 0.0), 2)
        for m in months
    }
    worst = None
    if monthly:
        wm = min(monthly.items(), key=lambda kv: kv[1])
        worst = {"month": wm[0], "pnl": wm[1]}
    n_tr = int(a["trades"]) + int(b["trades"])
    wins = int(a["wins"]) + int(b["wins"])
    holds = []
    for t in a.get("trade_list") or []:
        holds.append(float(t["hold_days"]))
    for t in b.get("trade_list") or []:
        holds.append(float(t["hold_days"]))
    capital = 2.0 * NOTIONAL
    pnl_dd = round(flat / float(dd), 3) if float(dd) > 1e-9 else None
    return {
        "label": "basket_TATN+MTLR",
        "notional_total": capital,
        "flat_pnl": round(flat, 2),
        "ret_pct": round(100.0 * flat / capital, 2),
        "max_dd_mtm": round(float(dd), 2),
        "pnl_dd": pnl_dd,
        "trades": n_tr,
        "wins": wins,
        "winrate": round(100.0 * wins / n_tr, 1) if n_tr else 0.0,
        "avg_hold_days": round(sum(holds) / len(holds), 2) if holds else 0.0,
        "max_hold_days": round(max(holds), 2) if holds else 0.0,
        "worst_month": worst,
        "monthly_pnl": monthly,
        "final_equity_mtm": round(float(eq.iloc[-1]), 2) if len(eq) else 0.0,
        "window": {
            "from": str(idx[0])[:19] if len(idx) else None,
            "to": str(idx[-1])[:19] if len(idx) else None,
            "n_bars_union": int(len(idx)),
        },
    }


def shelf_flags_for_pair(
    spreads: list[tuple[str, float]],
    *,
    lookback_calendar_days: int,
) -> tuple[list[str], list[bool]]:
    dates = [d for d, _ in spreads]
    vals = [s for _, s in spreads]
    n = len(vals)
    flags: list[bool] = [False] * n
    dts = [datetime.strptime(d, "%Y-%m-%d").date() for d in dates]
    for i in range(n):
        t0 = dts[i] - timedelta(days=lookback_calendar_days)
        lo = i
        while lo > 0 and dts[lo - 1] >= t0:
            lo -= 1
        window = vals[lo : i + 1]
        if len(window) < 30:
            continue
        ordered = sorted(window)
        med = float(statistics.median(window))
        p10 = _percentile(ordered, 10)
        p90 = _percentile(ordered, 90)
        st = classify_status(window, median=med, p10=p10, p90=p90)
        flags[i] = st["status"] == "полка"
    return dates, flags


def _runs(flags: list[bool], dates: list[str]) -> list[dict]:
    runs: list[dict] = []
    i = 0
    n = len(flags)
    while i < n:
        if not flags[i]:
            i += 1
            continue
        j = i
        while j < n and flags[j]:
            j += 1
        runs.append({"start": dates[i], "end": dates[j - 1], "days": j - i})
        i = j
    return runs


def _run_stats(runs: list[dict]) -> dict:
    lens = [r["days"] for r in runs]
    if not lens:
        return {
            "n_episodes": 0,
            "p50_days": None,
            "p90_days": None,
            "mean_days": None,
            "max_days": None,
            "top": [],
        }

    def pct(p: float) -> float:
        sl = sorted(lens)
        k = (len(sl) - 1) * (p / 100.0)
        lo = int(k)
        hi = min(lo + 1, len(sl) - 1)
        w = k - lo
        return round(sl[lo] * (1.0 - w) + sl[hi] * w, 2)

    return {
        "n_episodes": len(runs),
        "p50_days": pct(50),
        "p90_days": pct(90),
        "mean_days": round(sum(lens) / len(lens), 2),
        "max_days": max(lens),
        "top": sorted(runs, key=lambda r: -r["days"])[:6],
    }


def shelf_block(
    name: str,
    dates: list[str],
    flags: list[bool],
    daily_pos: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    n = len(flags)
    n_shelf = sum(1 for f in flags if f)
    adverse_days = 0
    for d, f in zip(dates, flags):
        if not f or not daily_pos:
            continue
        info = daily_pos.get(d)
        if not info:
            continue
        if int(info.get("pos") or 0) != 0 and float(info.get("unrealized") or 0.0) < 0:
            adverse_days += 1
    return {
        "pair": name,
        "n_days": n,
        "from": dates[0] if dates else None,
        "to": dates[-1] if dates else None,
        "shelf_days": n_shelf,
        "shelf_day_pct": round(100.0 * n_shelf / n, 2) if n else None,
        "open_adverse_shelf_days": adverse_days,
        "open_adverse_shelf_pct": round(100.0 * adverse_days / n, 2) if n else None,
        "episodes": _run_stats(_runs(flags, dates)),
    }


def soft_shelf_from_m15(
    df: pd.DataFrame,
    *,
    long_entry: float,
    short_entry: float,
) -> dict[str, Any]:
    """Daily median soft shelf: med < long_entry or med > short_entry."""
    ts = pd.to_datetime(df["timestamp"])
    tmp = df.copy()
    tmp["day"] = ts.dt.normalize()
    daily = tmp.groupby("day")["spread_percent"].median()
    below = daily < long_entry
    above = daily > short_entry
    n = len(daily)
    return {
        "n_days": n,
        "med_below_long_entry_days": int(below.sum()),
        "med_below_long_entry_pct": round(100.0 * float(below.sum()) / n, 2) if n else None,
        "med_above_short_entry_days": int(above.sum()),
        "med_above_short_entry_pct": round(100.0 * float(above.sum()) / n, 2) if n else None,
        "long_entry": long_entry,
        "short_entry": short_entry,
        "note_ru": (
            "Мягкая полка: дневная медиана спреда ниже входа Long "
            "или выше входа Short (без статуса панели зависания)."
        ),
    }


def run_shelf_stats(
    win_from: date,
    win_to: date,
    tatn_daily: dict[str, dict[str, Any]],
    mtlr_daily: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    print("shelf: ISS daily closes + hang classify …", flush=True)
    till = win_to
    start_fetch = till - timedelta(days=HANG_LOOKBACK + 40)
    closes: dict[str, list[tuple[str, float]]] = {}
    for sec in ("TATN", "TATNP", "MTLR", "MTLRP"):
        closes[sec] = _iss_history_closes(sec, start_fetch, till)
        print(f"  {sec} days={len(closes[sec])}", flush=True)
    series = {
        "TATN": _spread_series(closes["TATN"], closes["TATNP"]),
        "MTLR": _spread_series(closes["MTLR"], closes["MTLRP"]),
    }
    t_dates, t_flags = shelf_flags_for_pair(series["TATN"], lookback_calendar_days=HANG_LOOKBACK)
    m_dates, m_flags = shelf_flags_for_pair(series["MTLR"], lookback_calendar_days=HANG_LOOKBACK)

    def _slice(dates: list[str], flags: list[bool]) -> tuple[list[str], list[bool]]:
        od, of = [], []
        for d, f in zip(dates, flags):
            dd = date.fromisoformat(d)
            if win_from <= dd <= win_to:
                od.append(d)
                of.append(f)
        return od, of

    td, tf = _slice(t_dates, t_flags)
    md, mf = _slice(m_dates, m_flags)

    # Align common trading days
    mb = dict(zip(md, mf))
    common_d: list[str] = []
    ca: list[bool] = []
    cb: list[bool] = []
    for d, a in zip(td, tf):
        if d not in mb:
            continue
        common_d.append(d)
        ca.append(a)  # TATN
        cb.append(mb[d])  # MTLR

    any_f = [a or b for a, b in zip(ca, cb)]
    both_f = [a and b for a, b in zip(ca, cb)]

    # Capital-weighted adverse shelf: weight NOTIONAL each when open+shelf+unrealized<0
    cap_w: list[float] = []
    for d, a, b in zip(common_d, ca, cb):
        w = 0.0
        ti = tatn_daily.get(d)
        mi = mtlr_daily.get(d)
        if a and ti and int(ti.get("pos") or 0) and float(ti.get("unrealized") or 0) < 0:
            w += NOTIONAL
        if b and mi and int(mi.get("pos") or 0) and float(mi.get("unrealized") or 0) < 0:
            w += NOTIONAL
        cap_w.append(w / (2.0 * NOTIONAL))
    avg_cap = round(float(np.mean(cap_w)), 4) if cap_w else None
    days_cap_pos = sum(1 for x in cap_w if x > 0)

    return {
        "definition_ru": (
            f"Статус «полка» панели зависания (trailing ≥{_SHELF_MIN_DAYS} дн. "
            "в полосе hang_band вокруг S_now, вдали от медианы). "
            "Корзина: any = хотя бы одна нога на полке; both = обе. "
            "Капитал-взвешенная adverse: доля дней × доля капитала в открытой "
            "позиции с отрицательным MTM при статусе полка у этой ноги."
        ),
        "window": {"from": win_from.isoformat(), "to": win_to.isoformat()},
        "TATN": shelf_block("TATN/TATNP", td, tf, tatn_daily),
        "MTLR": shelf_block("MTLR/MTLRP", md, mf, mtlr_daily),
        "basket": {
            "n_common_days": len(common_d),
            "any_leg_shelved_days": sum(1 for x in any_f if x),
            "any_leg_shelved_pct": round(100.0 * sum(1 for x in any_f if x) / len(any_f), 2)
            if any_f
            else None,
            "both_shelved_days": sum(1 for x in both_f if x),
            "both_shelved_pct": round(100.0 * sum(1 for x in both_f if x) / len(both_f), 2)
            if both_f
            else None,
            "capital_weighted_adverse_shelf": {
                "avg_capital_share": avg_cap,
                "days_with_any_adverse": days_cap_pos,
                "days_with_any_adverse_pct": round(
                    100.0 * days_cap_pos / len(cap_w), 2
                )
                if cap_w
                else None,
            },
            "episodes_any": _run_stats(_runs(any_f, common_d)),
            "episodes_both": _run_stats(_runs(both_f, common_d)),
        },
    }


def strip_internal(r: dict[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in r.items() if not k.startswith("_")}


def optional_tatn_tip1m(win_from: str, win_to: str) -> dict[str, Any] | None:
    """Sidecar: TATN tip1m geometric same levels / ~same calendar window."""
    try:
        from replay.tip_touch import ensure_tip_series, run_touch_1m_trades
        from replay import tip_touch as tt
    except Exception as e:
        return {"error": f"import failed: {e}"}
    try:
        print("optional TATN tip1m …", flush=True)
        prep, meta = ensure_tip_series("m15_tatn_365d.csv")
        start = win_from[:10]
        wms = tt._window_start_ms(prep, start)
        levels = {
            "enter_wide": TATN_W[0],
            "exit_wide": TATN_W[1],
            "enter_narrow": TATN_N[0],
            "exit_narrow": TATN_N[1],
        }
        r = run_touch_1m_trades(
            prep,
            1.6,
            1.3,
            window_start_ms=wms,
            slip=SLIP,
            notional=NOTIONAL,
            compound=False,
            spread_level_mode=True,
            spread_levels=levels,
        )
        s = dict(r.get("summary") or {})
        return {
            "bar_type": "tip1m_geometric",
            "note_ru": (
                "Сравнение «яблоко к яблоку» — оба на m15 выше; tip1m только для "
                "Татнефти (кэш есть). Окно от той же даты начала, что m15-корзина."
            ),
            "levels": levels,
            "notional": NOTIONAL,
            "slip": SLIP,
            "window_start": start,
            "tip_meta": {
                k: meta.get(k)
                for k in ("csv", "nTips", "from", "to", "cacheTier", "cacheHit")
            },
            "summary": {
                "flat_pnl": s.get("pnlRub"),
                "max_dd_mtm": s.get("maxDdRub"),
                "trades": s.get("trades"),
                "winrate": s.get("winratePct"),
                "ret_pct": s.get("returnPct"),
            },
            "raw_summary": s,
        }
    except Exception as e:
        return {"error": str(e)}


def main() -> None:
    t0 = time.time()
    print("=== Basket TATN+MTLR 50k x2 ~1y m15 ===", flush=True)

    # Fresh caches (TATN 365d was stale to Jul-26)
    tatn_raw = load_pair_csv(TATN_CSV, ord_="TATN", pref="TATNP", force_refresh=False)
    mtlr_raw = load_pair_csv(MTLR_CSV, ord_="MTLR", pref="MTLRP", force_refresh=False)

    # Normalize column names
    for df in (tatn_raw, mtlr_raw):
        if "ord_close" not in df.columns and "tatn_close" in df.columns:
            df.rename(
                columns={"tatn_close": "ord_close", "tatnp_close": "pref_close"},
                inplace=True,
            )

    end = min(
        pd.to_datetime(tatn_raw["timestamp"]).iloc[-1],
        pd.to_datetime(mtlr_raw["timestamp"]).iloc[-1],
    )
    print(f"aligned end={end}", flush=True)
    tatn = window_slice(tatn_raw, end=end)
    mtlr = window_slice(mtlr_raw, end=end)
    print(
        f"TATN bars={len(tatn)} {tatn['timestamp'].iloc[0]} → {tatn['timestamp'].iloc[-1]}",
        flush=True,
    )
    print(
        f"MTLR bars={len(mtlr)} {mtlr['timestamp'].iloc[0]} → {mtlr['timestamp'].iloc[-1]}",
        flush=True,
    )

    r_tatn = sim_leg(
        tatn,
        enter_wide=TATN_W[0],
        exit_wide=TATN_W[1],
        enter_narrow=TATN_N[0],
        exit_narrow=TATN_N[1],
        narrow_max=TATN_CUTS[0],
        wide_min=TATN_CUTS[1],
        label="TATN/TATNP",
    )
    r_mtlr = sim_leg(
        mtlr,
        enter_wide=MTLR_W[0],
        exit_wide=MTLR_W[1],
        enter_narrow=MTLR_N[0],
        exit_narrow=MTLR_N[1],
        narrow_max=MTLR_CUTS[0],
        wide_min=MTLR_CUTS[1],
        label="MTLR/MTLRP",
    )
    r_basket = basket_from_legs(r_tatn, r_mtlr)

    win_from = date.fromisoformat(str(r_tatn["window"]["from"])[:10])
    win_to = date.fromisoformat(str(r_tatn["window"]["to"])[:10])
    shelves = run_shelf_stats(win_from, win_to, r_tatn["_daily"], r_mtlr["_daily"])
    soft = {
        "TATN": soft_shelf_from_m15(tatn, long_entry=TATN_N[0], short_entry=TATN_W[0]),
        "MTLR": soft_shelf_from_m15(mtlr, long_entry=MTLR_N[0], short_entry=MTLR_W[0]),
    }

    tip = optional_tatn_tip1m(str(r_tatn["window"]["from"]), str(r_tatn["window"]["to"]))

    out = {
        "params": {
            "capital_per_pair": NOTIONAL,
            "capital_basket": 2.0 * NOTIONAL,
            "slip": SLIP,
            "leverage": LEVERAGE,
            "commissionPctPerSide": COMM_PCT,
            "overnightFeePercentPerDay": OVERNIGHT_FEE_PERCENT_PER_DAY,
            "bar_type": "m15_from_iss_10m",
            "window_days": WINDOW_DAYS,
            "aligned_end": str(end)[:19],
            "max_positions_per_pair": 1,
            "independent_signals": True,
            "levels": {
                "TATN": {"wide": list(TATN_W), "narrow": list(TATN_N), "cuts": list(TATN_CUTS)},
                "MTLR": {
                    "wide": list(MTLR_W),
                    "narrow": list(MTLR_N),
                    "cuts": list(MTLR_CUTS),
                    "cuts_note_ru": "адаптивные пороги режима из _bt_mtlr_365d_levels (~p40/p60)",
                },
            },
            "note_ru": (
                "Оба актива на одинаковых 15м барах (~1 год, общая дата конца). "
                "PnL flat по закрытым; max DD — по MTM эквити. "
                "Без TP/удержания по дням. Комиссии/перенос как в tip_touch/_fees."
            ),
        },
        "TATN_m15": strip_internal(r_tatn),
        "MTLR_m15": strip_internal(r_mtlr),
        "basket_m15": r_basket,
        "shelf_hang": shelves,
        "shelf_soft_daily_median": soft,
        "TATN_tip1m_sidecar": tip,
        "elapsed_sec": round(time.time() - t0, 1),
    }
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {OUT} in {out['elapsed_sec']}s", flush=True)

    def _line(name: str, r: dict[str, Any]) -> None:
        print(
            f"{name:18} pnl={r.get('flat_pnl'):>10}  "
            f"dd_mtm={r.get('max_dd_mtm'):>9}  "
            f"pnl/dd={r.get('pnl_dd')}  "
            f"n={r.get('trades'):3}  wr={r.get('winrate')}%  "
            f"avgHold={r.get('avg_hold_days')}  "
            f"worst={r.get('worst_month')}",
            flush=True,
        )

    _line("TATN", r_tatn)
    _line("MTLR", r_mtlr)
    _line("BASKET", r_basket)
    b = shelves["basket"]
    print(
        f"shelf hang %: TATN={shelves['TATN']['shelf_day_pct']}  "
        f"MTLR={shelves['MTLR']['shelf_day_pct']}  "
        f"any={b['any_leg_shelved_pct']}  both={b['both_shelved_pct']}  "
        f"cap_w_avg={b['capital_weighted_adverse_shelf']['avg_capital_share']}",
        flush=True,
    )


if __name__ == "__main__":
    main()
