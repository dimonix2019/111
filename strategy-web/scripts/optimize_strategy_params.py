#!/usr/bin/env python3
"""
Полный подбор параметров: Z-пороги, перцентильная anomaly, red zone (off / always / losing).

Walk-forward по кварталам на 3y для проверки устойчивости лучших конфигов.

  cd strategy-web && python3 scripts/optimize_strategy_params.py
  python3 scripts/optimize_strategy_params.py --quick
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from anomaly_redzone_sim import (
    SimConfig,
    StrategyKind,
    precompute_spread_percentiles,
    run_strategy_sim,
    summarize_result,
)
from config import SESSION_DEFAULTS
from zsim import Bar, load_bars_from_csv, param_sweep, run_z_strategy_sim

SIM_KW = {
    "notional_rub": float(SESSION_DEFAULTS.get("inp_notional", 100_000)),
    "leverage": float(SESSION_DEFAULTS.get("inp_leverage", 7.0)),
    "commission_pct_per_side": float(SESSION_DEFAULTS.get("inp_commission", 0.04)),
    "compound_returns": bool(SESSION_DEFAULTS.get("inp_compound", False)),
}

RED_ZONE_MODES = ("off", "always", "losing")


@dataclass(frozen=True)
class QuarterKey:
    year: int
    q: int

    def label(self) -> str:
        return f"{self.year}-Q{self.q}"


def quarter_of(ts: str) -> QuarterKey:
    dt = pd.to_datetime(ts)
    return QuarterKey(int(dt.year), int((dt.month - 1) // 3 + 1))


def bars_for_quarters(bars: list[Bar], keys: set[QuarterKey]) -> list[Bar]:
    return [b for b in bars if quarter_of(b.timestamp) in keys]


def composite_score(pnl: float, mdd: float, trades: int, min_trades: int = 30) -> float:
    if trades < min_trades:
        return -1e12
    return pnl - 0.35 * mdd


def z_threshold_grid(quick: bool) -> tuple[list[float], list[float]]:
    if quick:
        entries = [round(x, 1) for x in np.arange(0.5, 1.05, 0.1)]
        exits = [round(x, 1) for x in np.arange(0.3, 0.75, 0.1)]
    else:
        entries = [round(x, 2) for x in np.arange(0.5, 1.15, 0.05)]
        exits = [round(x, 2) for x in np.arange(0.3, 0.75, 0.05)]
    return entries, exits


def percentile_grid(quick: bool) -> list[tuple[int, float, float]]:
    lookbacks = [60, 90] if quick else [30, 60, 90, 120]
    entry_pcts = [92.0, 95.0] if quick else [90.0, 92.0, 93.0, 95.0, 97.0]
    exit_bands = [0.3, 0.4] if quick else [0.2, 0.3, 0.4, 0.5]
    out: list[tuple[int, float, float]] = []
    for lb in lookbacks:
        for ep in entry_pcts:
            for band in exit_bands:
                out.append((lb, ep, band))
    return out


def run_z_sweep(bars: list[Bar], quick: bool) -> pd.DataFrame:
    entries, exits = z_threshold_grid(quick)
    base = param_sweep(bars, entries, exits, **SIM_KW)
    base = base[base["exit_z"] < base["entry"]].copy()
    rows: list[dict] = []
    for _, r in base.iterrows():
        for rz in RED_ZONE_MODES:
            cfg = SimConfig(
                kind=StrategyKind.Z,
                entry_z=float(r["entry"]),
                exit_z=float(r["exit_z"]),
                red_zone_mode=rz,
                notional_rub=SIM_KW["notional_rub"],
                leverage=SIM_KW["leverage"],
                commission_pct_per_side=SIM_KW["commission_pct_per_side"],
            )
            res = run_strategy_sim(bars, cfg)
            s = summarize_result(f"Z {r['entry']}/{r['exit_z']} rz={rz}", res, cfg.entry_z)
            rows.append(
                {
                    "strategy": "z",
                    "entry_z": cfg.entry_z,
                    "exit_z": cfg.exit_z,
                    "lookback_days": None,
                    "entry_pct": None,
                    "exit_band_pp": None,
                    "red_zone": rz,
                    "pnl": s["pnl"],
                    "trades": s["trades"],
                    "mdd": s["mdd"],
                    "win_pct": 100.0 * s["wins"] / s["trades"] if s["trades"] else 0.0,
                    "median_hold_h": s["median_hold_h"],
                    "hold_gt_48h": s["hold_gt_48h"],
                    "red_zone_exits": s["red_zone_exits"],
                    "score": composite_score(s["pnl"], s["mdd"], s["trades"]),
                }
            )
    return pd.DataFrame(rows)


def run_percentile_sweep(bars: list[Bar], quick: bool) -> pd.DataFrame:
    cache: dict[int, tuple[list, list]] = {}
    rows: list[dict] = []
    for lb, ep, band in percentile_grid(quick):
        if lb not in cache:
            print(f"  precompute percentiles lookback={lb}d …")
            spreads = [b.spread_percent for b in bars]
            ts = [b.timestamp for b in bars]
            cache[lb] = precompute_spread_percentiles(spreads, ts, lb, min_bars=48)
        pcts, meds = cache[lb]
        low = 100.0 - ep
        for rz in RED_ZONE_MODES:
            cfg = SimConfig(
                kind=StrategyKind.PERCENTILE,
                lookback_days=lb,
                entry_pct_high=ep,
                entry_pct_low=low,
                exit_median_band_pp=band,
                red_zone_mode=rz,
                notional_rub=SIM_KW["notional_rub"],
                leverage=SIM_KW["leverage"],
                commission_pct_per_side=SIM_KW["commission_pct_per_side"],
            )
            res = run_strategy_sim(bars, cfg, precomputed_pcts=pcts, precomputed_meds=meds)
            name = f"pct {lb}d p{ep:.0f}/p{low:.0f} band={band} rz={rz}"
            s = summarize_result(name, res, 0.8)
            rows.append(
                {
                    "strategy": "percentile",
                    "entry_z": None,
                    "exit_z": None,
                    "lookback_days": lb,
                    "entry_pct": ep,
                    "exit_band_pp": band,
                    "red_zone": rz,
                    "pnl": s["pnl"],
                    "trades": s["trades"],
                    "mdd": s["mdd"],
                    "win_pct": 100.0 * s["wins"] / s["trades"] if s["trades"] else 0.0,
                    "median_hold_h": s["median_hold_h"],
                    "hold_gt_48h": s["hold_gt_48h"],
                    "red_zone_exits": s["red_zone_exits"],
                    "score": composite_score(s["pnl"], s["mdd"], s["trades"]),
                }
            )
    return pd.DataFrame(rows)


def config_label(row: pd.Series) -> str:
    if row["strategy"] == "z":
        return f"Z ±{row['entry_z']}/±{row['exit_z']} red={row['red_zone']}"
    return (
        f"Pct {int(row['lookback_days'])}d p{row['entry_pct']:.0f} "
        f"band={row['exit_band_pp']} red={row['red_zone']}"
    )


def sim_row_on_bars(bars: list[Bar], row: pd.Series) -> float:
    if row["strategy"] == "z":
        cfg = SimConfig(
            kind=StrategyKind.Z,
            entry_z=float(row["entry_z"]),
            exit_z=float(row["exit_z"]),
            red_zone_mode=str(row["red_zone"]),
            notional_rub=SIM_KW["notional_rub"],
            leverage=SIM_KW["leverage"],
            commission_pct_per_side=SIM_KW["commission_pct_per_side"],
        )
        return run_strategy_sim(bars, cfg).total_pnl_rub
    cfg = SimConfig(
        kind=StrategyKind.PERCENTILE,
        lookback_days=int(row["lookback_days"]),
        entry_pct_high=float(row["entry_pct"]),
        entry_pct_low=100.0 - float(row["entry_pct"]),
        exit_median_band_pp=float(row["exit_band_pp"]),
        red_zone_mode=str(row["red_zone"]),
        notional_rub=SIM_KW["notional_rub"],
        leverage=SIM_KW["leverage"],
        commission_pct_per_side=SIM_KW["commission_pct_per_side"],
    )
    return run_strategy_sim(bars, cfg).total_pnl_rub


def walkforward_oos(bars: list[Bar], top: pd.DataFrame, min_train_quarters: int = 2) -> pd.DataFrame:
    quarters = sorted({quarter_of(b.timestamp) for b in bars}, key=lambda k: (k.year, k.q))
    wf_rows: list[dict] = []
    for _, row in top.iterrows():
        oos_pnls: list[float] = []
        for i in range(min_train_quarters, len(quarters)):
            test_bars = bars_for_quarters(bars, {quarters[i]})
            if len(test_bars) < 10:
                continue
            oos_pnls.append(sim_row_on_bars(test_bars, row))
        wf_rows.append(
            {
                "label": config_label(row),
                "oos_quarters": len(oos_pnls),
                "oos_pnl_sum": sum(oos_pnls),
                "oos_pnl_mean": float(np.mean(oos_pnls)) if oos_pnls else 0.0,
                "oos_positive_q": sum(1 for p in oos_pnls if p > 0),
            }
        )
    return pd.DataFrame(wf_rows)


def print_top(df: pd.DataFrame, title: str, n: int = 15) -> None:
    print(f"\n{'=' * 80}")
    print(title)
    print(f"{'=' * 80}")
    print(
        f"{'#':>3} {'Конфиг':<44} {'PnL':>11} {'Score':>10} {'#tr':>5} "
        f"{'MDD':>8} {'win%':>6} {'>48h':>5}"
    )
    print("-" * 80)
    for i, (_, r) in enumerate(df.head(n).iterrows(), 1):
        print(
            f"{i:>3} {config_label(r):<44} {r['pnl']:>10,.0f} {r['score']:>10,.0f} "
            f"{int(r['trades']):>5} {r['mdd']:>8,.0f} {r['win_pct']:>5.1f}% "
            f"{int(r['hold_gt_48h']):>5}"
        )


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--csv-3y", type=str, default=str(ROOT / "data" / "m15_tatn_1095d.csv"))
    p.add_argument("--csv-255", type=str, default=str(ROOT / "data" / "m15_tatn_255d.csv"))
    p.add_argument("--quick", action="store_true")
    p.add_argument("--out", type=str, default=str(ROOT / "data" / "strategy_optimize_full.csv"))
    args = p.parse_args()

    bars_3y = load_bars_from_csv(args.csv_3y, recalc_z=True, z_mode="rolling30")
    bars_255 = load_bars_from_csv(args.csv_255, recalc_z=True, z_mode="rolling30")
    print(f"3y: {len(bars_3y)} bars, {bars_3y[0].timestamp[:10]} … {bars_3y[-1].timestamp[:10]}")
    print(f"255d: {len(bars_255)} bars")

    print("\n[1/3] Z threshold × red zone …")
    z_df = run_z_sweep(bars_3y, args.quick)
    print("[2/3] Percentile anomaly × red zone …")
    pct_df = run_percentile_sweep(bars_3y, args.quick)
    full = pd.concat([z_df, pct_df], ignore_index=True)
    full = full.sort_values("score", ascending=False)
    full.to_csv(args.out, index=False)
    print(f"Wrote {args.out} ({len(full)} rows)")

    print_top(full, "TOP by composite score (PnL − 0.35×MDD) on 3y")

    # Baseline row
    bl = full[
        (full["strategy"] == "z")
        & (full["entry_z"] == 0.7)
        & (full["exit_z"] == 0.5)
        & (full["red_zone"] == "off")
    ]
    if len(bl):
        b = bl.iloc[0]
        print(f"\nBaseline Z 0.7/0.5 off: PnL {b['pnl']:,.0f} ₽, score {b['score']:,.0f}")

    best = full.iloc[0]
    print(f"\nBest overall: {config_label(best)}")
    print(f"  3y PnL {best['pnl']:,.0f} ₽, MDD {best['mdd']:,.0f}, {int(best['trades'])} trades")

    # 255d validation of top-5
    print("\n[3/3] Walk-forward OOS + 255d check for top configs …", flush=True)
    top_n = 8 if args.quick else 12
    top = full.head(top_n).copy()
    wf = walkforward_oos(bars_3y, top)
    merged = top.reset_index(drop=True).join(wf)
    merged = merged.sort_values("oos_pnl_sum", ascending=False)
    print_top(merged, f"TOP {min(10, len(merged))} after walk-forward OOS (sorted by OOS sum)")

    print("\n255d PnL for top-5 by OOS:")
    for _, r in merged.head(5).iterrows():
        pnl_255 = sim_row_on_bars(bars_255, r)
        print(f"  {config_label(r):<44} 255d={pnl_255:>9,.0f} ₽  OOS sum={r['oos_pnl_sum']:>9,.0f}")

    wf_out = ROOT / "data" / "strategy_optimize_wf.csv"
    merged.to_csv(wf_out, index=False)
    print(f"\nWrote {wf_out}")


if __name__ == "__main__":
    main()
