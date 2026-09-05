#!/usr/bin/env python3
"""1y MTLR/MTLRP spread-level sweep (m15 touch, same family as TATN tip1m levels).

Bar type: completed 15m from ISS 10m candles (tip1m for MTLR not cached; m15
is the repo-supported touch-equivalent for non-TATN pairs). Cap OFF. Does NOT
change Prod / Watchdog.

Spread S = (MTLR/MTLRP − 1)×100. Long = narrow (enter↓/exit↑), Short = wide
(enter↑/exit↓). Regime cuts adapted to Mechel year distribution (not TATN 3.5/5.5).
"""
from __future__ import annotations

import itertools
import json
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from m15_iss_loader import (  # noqa: E402
    _build_m15_frame,
    _fetch_candles,
    _recalc_z,
)
from replay.tip_touch import COMM_PCT, DEFAULT_NOTIONAL, LEVERAGE  # noqa: E402
from zsim import OVERNIGHT_FEE_PERCENT_PER_DAY  # noqa: E402

MSK = ZoneInfo("Europe/Moscow")
OUT = ROOT / "data" / "_bt_mtlr_365d_levels.json"
CSV_OUT = ROOT / "data" / "m15_mtlr_400d.csv"
ORD, PREF = "MTLR", "MTLRP"
LOOKBACK_DAYS = 400
WINDOW_DAYS = 365
NOTIONAL = float(DEFAULT_NOTIONAL)
SLIPS = (0.02, 0.04)
MIN_HYST = 0.5
MIN_TRADES = 8

# Naive copy of current TATN Prod wardrobe (for comparison only).
TATN_COPY_W = (6.2, 5.8)
TATN_COPY_N = (3.2, 4.0)


def _fees(notional: float) -> tuple[float, float, float]:
    eff = notional * LEVERAGE
    comm = eff * (COMM_PCT / 100.0)
    ovn = notional * max(0.0, LEVERAGE - 1.0) * (OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)
    return eff, comm, ovn


def fetch_m15_pair(days: int = LOOKBACK_DAYS) -> pd.DataFrame:
    end = datetime.now(tz=MSK)
    start = (end - timedelta(days=int(days))).strftime("%Y-%m-%d")
    till = end.strftime("%Y-%m-%d")
    print(f"ISS 10m {ORD}+{PREF} {start} … {till}", flush=True)
    ord10 = _fetch_candles(ORD, start, till, interval=10)
    pref10 = _fetch_candles(PREF, start, till, interval=10)
    print(f"  candles 10m: {ORD}={len(ord10)} {PREF}={len(pref10)}", flush=True)
    if ord10.empty or pref10.empty:
        raise RuntimeError(f"empty ISS candles for {ORD}/{PREF}")
    # Reuse TATN frame builder (column names stay tatn_/tatnp_ — cosmetic only).
    frame = _build_m15_frame(ord10, pref10, include_forming=False)
    if frame.empty:
        raise RuntimeError("empty m15 frame after merge")
    frame = frame.rename(
        columns={"tatn_close": "ord_close", "tatnp_close": "pref_close"}
    )
    # rebuild z with renamed cols still having spread_percent
    tmp = frame.copy()
    tmp["timestamp"] = pd.to_datetime(tmp["timestamp"])
    tmp = _recalc_z(tmp)
    tmp["timestamp"] = tmp["timestamp"].dt.strftime("%Y-%m-%d %H:%M:%S")
    return tmp[["timestamp", "z_score", "spread_percent", "ord_close", "pref_close"]]


def save_csv(df: pd.DataFrame) -> None:
    out = df.rename(columns={"ord_close": "tatn_close", "pref_close": "tatnp_close"})
    out.to_csv(CSV_OUT, index=False)
    print(f"wrote {CSV_OUT} rows={len(out)}", flush=True)


def load_or_fetch() -> pd.DataFrame:
    if CSV_OUT.exists():
        df = pd.read_csv(CSV_OUT)
        # refresh if stale (>2d) or short
        try:
            last = pd.to_datetime(df["timestamp"].iloc[-1])
            age_h = (datetime.now() - last.to_pydatetime().replace(tzinfo=None)).total_seconds() / 3600
            if age_h < 48 and len(df) > 5000:
                print(f"reuse cache {CSV_OUT} rows={len(df)} age_h={age_h:.0f}", flush=True)
                if "ord_close" not in df.columns and "tatn_close" in df.columns:
                    df = df.rename(
                        columns={"tatn_close": "ord_close", "tatnp_close": "pref_close"}
                    )
                return df
        except Exception:
            pass
    df = fetch_m15_pair(LOOKBACK_DAYS)
    save_csv(df)
    return df


def prep_arrays(df: pd.DataFrame, window_days: int = WINDOW_DAYS) -> dict[str, Any]:
    ts = pd.to_datetime(df["timestamp"])
    end = ts.iloc[-1]
    start = end - pd.Timedelta(days=window_days)
    mask = ts >= start
    sub = df.loc[mask].reset_index(drop=True)
    ts_sub = pd.to_datetime(sub["timestamp"])
    sp = sub["spread_percent"].astype(float).to_numpy()
    day_ord = (ts_sub.dt.normalize().map(lambda d: d.toordinal())).to_numpy(dtype=np.int64)
    dist = {
        "n_bars": int(len(sp)),
        "from": str(ts_sub.iloc[0])[:19],
        "to": str(ts_sub.iloc[-1])[:19],
        "S_min": round(float(np.min(sp)), 3),
        "S_max": round(float(np.max(sp)), 3),
        "S_mean": round(float(np.mean(sp)), 3),
        "S_median": round(float(np.median(sp)), 3),
        "S_p10": round(float(np.percentile(sp, 10)), 3),
        "S_p25": round(float(np.percentile(sp, 25)), 3),
        "S_p75": round(float(np.percentile(sp, 75)), 3),
        "S_p90": round(float(np.percentile(sp, 90)), 3),
        "p10_p90_width_pp": round(
            float(np.percentile(sp, 90) - np.percentile(sp, 10)), 3
        ),
    }
    # Adaptive regime cuts: leave ~middle quintile as transition.
    p40 = float(np.percentile(sp, 40))
    p60 = float(np.percentile(sp, 60))
    # Ensure sensible gap (≥1.5pp); else widen from median.
    med = float(np.median(sp))
    if p60 - p40 < 1.5:
        half = max(1.5, 0.08 * dist["p10_p90_width_pp"])
        p40, p60 = med - half, med + half
    cuts = {
        "narrow_max": round(p40, 2),
        "wide_min": round(p60, 2),
        "note_ru": "адаптивно к распределению Мечела за окно (~p40/p60)",
    }
    return {"sp": sp, "day_ord": day_ord, "dist": dist, "cuts": cuts}


def build_grids(dist: dict[str, Any]) -> dict[str, list[float]]:
    """Level grids around Mechel year distribution — not TATN 3.2/4/6.2/5.8."""
    p10, p25, med = dist["S_p10"], dist["S_p25"], dist["S_median"]
    p75, p90 = dist["S_p75"], dist["S_p90"]

    def _grid(lo: float, hi: float, step: float) -> list[float]:
        if hi < lo:
            lo, hi = hi, lo
        n = int(round((hi - lo) / step)) + 1
        vals = [round(lo + i * step, 2) for i in range(max(1, n))]
        # unique sorted
        return sorted(set(vals))

    # Long (narrow): enter below median, exit between enter and ~median+
    narrow_enter = _grid(p10 - 1.0, med - 0.5, 1.0)
    narrow_exit = _grid(p25, med + max(1.0, 0.15 * (p90 - p10)), 1.0)
    # Short (wide): enter above median, exit between ~median- and enter
    wide_enter = _grid(med + 0.5, p90 + 1.0, 1.0)
    wide_exit = _grid(med - max(1.0, 0.1 * (p90 - p10)), p75, 1.0)

    # Keep grids modest for runtime
    def _thin(xs: list[float], max_n: int = 10) -> list[float]:
        if len(xs) <= max_n:
            return xs
        idx = np.linspace(0, len(xs) - 1, max_n).round().astype(int)
        return [xs[i] for i in sorted(set(idx))]

    return {
        "narrow_enter": _thin(narrow_enter, 9),
        "narrow_exit": _thin(narrow_exit, 9),
        "wide_enter": _thin(wide_enter, 9),
        "wide_exit": _thin(wide_exit, 9),
    }


def pairs_from_grid(
    enter: list[float], exit_: list[float], *, long_side: bool
) -> list[tuple[float, float]]:
    out: list[tuple[float, float]] = []
    for e, x in itertools.product(enter, exit_):
        if long_side:
            if x - e >= MIN_HYST - 1e-9:
                out.append((e, x))
        else:
            if e - x >= MIN_HYST - 1e-9:
                out.append((e, x))
    return out


def classify(sp: float, narrow_max: float, wide_min: float) -> str:
    if sp < narrow_max:
        return "narrow"
    if sp > wide_min:
        return "wide"
    return "transition"


def sim_levels(
    sp: np.ndarray,
    day_ord: np.ndarray,
    *,
    enter_wide: float,
    exit_wide: float,
    enter_narrow: float,
    exit_narrow: float,
    slip: float,
    narrow_max: float,
    wide_min: float,
    use_regime: bool = True,
    compound: bool = False,
    notional: float = NOTIONAL,
) -> dict[str, Any]:
    pos = 0  # 0 flat 1 long 2 short
    entry_sp = 0.0
    entry_day = 0
    entry_comm = 0.0
    entry_side = 0
    entry_eff = 0.0
    entry_ovn_day = 0.0
    exit_comm = 0.0
    base = float(notional)
    capital = float(notional)
    total_pnl = peak = max_dd = 0.0
    closed = wins = 0
    by_side = {
        1: {"trades": 0, "wins": 0, "pnl": 0.0},
        2: {"trades": 0, "wins": 0, "pnl": 0.0},
    }
    n = len(sp)
    for i in range(1, n):
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
        if not sig:
            continue

        stake = capital if compound else base
        if sig in (1, 2):
            entry_eff, entry_comm, entry_ovn_day = _fees(stake)
            exit_comm = entry_comm
            entry_sp = cur + slip if sig == 1 else cur - slip
            entry_day = int(day_ord[i])
            entry_side = sig
            pos = sig
        else:
            is_long = pos == 1
            exit_sp = cur - slip if is_long else cur + slip
            pnl_pts = (exit_sp - entry_sp) if is_long else (entry_sp - exit_sp)
            gross = entry_eff * (pnl_pts / 100.0)
            ovn = entry_ovn_day * max(0, int(day_ord[i]) - entry_day)
            net = gross - (entry_comm + exit_comm) - ovn
            total_pnl += net
            if compound:
                capital = max(100.0, capital + net)
            closed += 1
            if net > 0:
                wins += 1
            if total_pnl > peak:
                peak = total_pnl
            max_dd = max(max_dd, peak - total_pnl)
            br = by_side[entry_side]
            br["trades"] += 1
            br["wins"] += int(net > 0)
            br["pnl"] += net
            pos = 0

    def _side(k: int) -> dict[str, Any]:
        b = by_side[k]
        ntr = b["trades"]
        return {
            "trades": ntr,
            "wins": b["wins"],
            "winrate": round(100.0 * b["wins"] / ntr, 1) if ntr else 0.0,
            "pnl": round(b["pnl"], 2),
        }

    pnl_dd = round(total_pnl / max_dd, 3) if max_dd > 1e-9 else None
    return {
        "enter_wide": enter_wide,
        "exit_wide": exit_wide,
        "enter_narrow": enter_narrow,
        "exit_narrow": exit_narrow,
        "wide": [enter_wide, exit_wide],
        "narrow": [enter_narrow, exit_narrow],
        "slip": slip,
        "trades": closed,
        "wins": wins,
        "winrate": round(100.0 * wins / closed, 1) if closed else 0.0,
        "pnl": round(total_pnl, 2),
        "ret_pct": round(100.0 * total_pnl / base, 2) if base else 0.0,
        "max_dd": round(max_dd, 2),
        "pnl_dd": pnl_dd,
        "final_capital": round(capital, 2) if compound else None,
        "by_side": {"long_narrow": _side(1), "short_wide": _side(2)},
    }


def score_pnl(r: dict[str, Any]) -> float:
    if r["trades"] < MIN_TRADES:
        return -1e12
    return float(r["pnl"])


def score_pnl_dd(r: dict[str, Any]) -> float:
    if r["trades"] < MIN_TRADES:
        return -1e12
    dd = max(float(r["max_dd"]), 1.0)
    return float(r["pnl"]) / dd


def score_conservative(r: dict[str, Any]) -> float:
    if r["trades"] < MIN_TRADES:
        return -1e12
    dd_pct = 100.0 * r["max_dd"] / NOTIONAL
    return r["winrate"] - 0.35 * dd_pct + 0.01 * min(r["trades"], 250) + 0.002 * r["ret_pct"]


def run_sweep(
    sp: np.ndarray,
    day_ord: np.ndarray,
    grids: dict[str, list[float]],
    cuts: dict[str, float],
    *,
    use_regime: bool,
    label: str,
) -> dict[str, Any]:
    wp = pairs_from_grid(grids["wide_enter"], grids["wide_exit"], long_side=False)
    np_ = pairs_from_grid(grids["narrow_enter"], grids["narrow_exit"], long_side=True)
    narrow_max = float(cuts["narrow_max"])
    wide_min = float(cuts["wide_min"])
    t0 = time.time()
    n_combo = len(wp) * len(np_) * len(SLIPS)
    print(
        f"  {label}: joint {n_combo} (W={len(wp)} N={len(np_)} slips={len(SLIPS)}) "
        f"regime={use_regime} cuts={narrow_max}/{wide_min}",
        flush=True,
    )
    rows: list[dict[str, Any]] = []
    done = 0
    for (we, wx), (ne, nx), slip in itertools.product(wp, np_, SLIPS):
        r = sim_levels(
            sp,
            day_ord,
            enter_wide=we,
            exit_wide=wx,
            enter_narrow=ne,
            exit_narrow=nx,
            slip=slip,
            narrow_max=narrow_max,
            wide_min=wide_min,
            use_regime=use_regime,
            compound=False,
        )
        r["_score_pnl"] = round(score_pnl(r), 2)
        r["_score_pnl_dd"] = round(score_pnl_dd(r), 4)
        r["_score_cons"] = round(score_conservative(r), 3)
        rows.append(r)
        done += 1
        if done % 400 == 0:
            print(f"    …{done}/{n_combo} ({time.time() - t0:.0f}s)", flush=True)

    by_slip: dict[str, Any] = {}
    for slip in SLIPS:
        sub = [r for r in rows if r["slip"] == slip]
        by_pnl = sorted(sub, key=score_pnl, reverse=True)
        by_ratio = sorted(sub, key=score_pnl_dd, reverse=True)
        by_cons = sorted(sub, key=score_conservative, reverse=True)
        # among top-quartile PnL, best pnl/dd and conservative
        if by_pnl and by_pnl[0]["trades"] >= MIN_TRADES:
            q = by_pnl[max(1, len(by_pnl) // 4) - 1]["pnl"]
            pool = [r for r in sub if r["pnl"] >= q and r["trades"] >= MIN_TRADES]
        else:
            pool = [r for r in sub if r["trades"] >= MIN_TRADES] or sub
        pool_ratio = sorted(pool, key=score_pnl_dd, reverse=True)
        pool_cons = sorted(pool, key=score_conservative, reverse=True)

        # TATN-copy baseline on same data/regime
        tatn = sim_levels(
            sp,
            day_ord,
            enter_wide=TATN_COPY_W[0],
            exit_wide=TATN_COPY_W[1],
            enter_narrow=TATN_COPY_N[0],
            exit_narrow=TATN_COPY_N[1],
            slip=slip,
            narrow_max=narrow_max,
            wide_min=wide_min,
            use_regime=use_regime,
        )
        # compound for best_pnl
        best = by_pnl[0] if by_pnl else None
        best_comp = None
        if best:
            best_comp = sim_levels(
                sp,
                day_ord,
                enter_wide=best["enter_wide"],
                exit_wide=best["exit_wide"],
                enter_narrow=best["enter_narrow"],
                exit_narrow=best["exit_narrow"],
                slip=slip,
                narrow_max=narrow_max,
                wide_min=wide_min,
                use_regime=use_regime,
                compound=True,
            )

        def _strip(r: dict[str, Any] | None) -> dict[str, Any] | None:
            if r is None:
                return None
            return {k: v for k, v in r.items()}

        by_slip[str(slip)] = {
            "tatn_copy": _strip(tatn),
            "best_pnl": _strip(by_pnl[0]) if by_pnl else None,
            "best_pnl_dd": _strip(pool_ratio[0]) if pool_ratio else None,
            "best_conservative": _strip(pool_cons[0]) if pool_cons else None,
            "best_pnl_compound": _strip(best_comp),
            "top12_pnl": [_strip(r) for r in by_pnl[:12]],
            "top8_pnl_dd": [_strip(r) for r in by_ratio[:8]],
        }
    return {
        "label": label,
        "use_regime": use_regime,
        "cuts": cuts,
        "n_combos": len(rows),
        "n_wide_pairs": len(wp),
        "n_narrow_pairs": len(np_),
        "elapsed_sec": round(time.time() - t0, 1),
        "by_slip": by_slip,
    }


def main() -> None:
    t0 = time.time()
    print("=== MTLR/MTLRP m15 spread-level ~1y sweep ===", flush=True)
    df = load_or_fetch()
    pack = prep_arrays(df, WINDOW_DAYS)
    sp, day_ord = pack["sp"], pack["day_ord"]
    dist, cuts = pack["dist"], pack["cuts"]
    grids = build_grids(dist)
    print(f"window {dist['from']} -> {dist['to']} bars={dist['n_bars']}", flush=True)
    print(f"dist median={dist['S_median']} width={dist['p10_p90_width_pp']} cuts={cuts}", flush=True)
    print(f"grids {grids}", flush=True)

    # Primary: with adaptive regime (family-faithful). Secondary: no regime.
    win_reg = run_sweep(sp, day_ord, grids, cuts, use_regime=True, label="m15_~1y_regime")
    win_free = run_sweep(sp, day_ord, grids, cuts, use_regime=False, label="m15_~1y_no_regime")

    out = {
        "params": {
            "pair": f"{ORD}/{PREF}",
            "spread_def": "(MTLR/MTLRP-1)*100",
            "bar_type": "m15_from_iss_10m",
            "bar_note_ru": (
                "15-минутные закрытые бары из ISS 10м свечей; tip1m для Мечела "
                "не кэширован (только TATN). Логика касания уровней — та же семья, "
                "что tip_touch spread_level_mode, но на m15."
            ),
            "slip_grid": list(SLIPS),
            "notional": NOTIONAL,
            "leverage": LEVERAGE,
            "commissionPctPerSide": COMM_PCT,
            "overnightFeePercentPerDay": OVERNIGHT_FEE_PERCENT_PER_DAY,
            "cap": False,
            "min_hysteresis": MIN_HYST,
            "min_trades_for_rank": MIN_TRADES,
            "lookback_fetch_days": LOOKBACK_DAYS,
            "window_days": WINDOW_DAYS,
            "csv": str(CSV_OUT.name),
            "grids": grids,
            "tatn_copy_levels": {"wide": list(TATN_COPY_W), "narrow": list(TATN_COPY_N)},
            "distribution_1y": dist,
            "adaptive_cuts": cuts,
        },
        "with_regime": win_reg,
        "no_regime": win_free,
        "elapsed_sec": round(time.time() - t0, 1),
    }
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {OUT} in {out['elapsed_sec']}s", flush=True)

    for mode_name, win in (("regime", win_reg), ("no_regime", win_free)):
        print(f"\n======== {mode_name} ========", flush=True)
        for slip, block in win["by_slip"].items():
            print(f"\n--- slip={slip} ---", flush=True)
            for k in ("tatn_copy", "best_pnl", "best_pnl_dd", "best_conservative", "best_pnl_compound"):
                r = block.get(k)
                if not r:
                    continue
                print(
                    f"  {k}: W{r['wide']} N{r['narrow']} "
                    f"pnl={r['pnl']} dd={r['max_dd']} wr={r['winrate']}% "
                    f"n={r['trades']} pnl/dd={r.get('pnl_dd')}",
                    flush=True,
                )


if __name__ == "__main__":
    main()
