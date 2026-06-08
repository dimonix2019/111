#!/usr/bin/env python3
"""
Сетка порогов вход/выход 0.0…2.5 (шаг 0.1) на 15м ряду TATN/TATNP.
Экспорт CSV с полной сеткой и лучшими парами по каждой разнице Δ.

Примеры:
  cd strategy-web && python3 scripts/threshold_gap_sweep.py
  python3 scripts/threshold_gap_sweep.py --days 365 --fetch
  python3 scripts/threshold_gap_sweep.py --csv data/m15_tatn_365d.csv
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from config import DEFAULT_M15, SESSION_DEFAULTS
from m15_iss_loader import fetch_m15_from_iss, save_m15_csv
from zsim import load_bars_from_csv, param_sweep

STEP = 0.1
MAX_TH = 2.5

SIM_KW = {
    "notional_rub": float(SESSION_DEFAULTS.get("inp_notional", 100_000)),
    "leverage": float(SESSION_DEFAULTS.get("inp_leverage", 7.0)),
    "commission_pct_per_side": float(SESSION_DEFAULTS.get("inp_commission", 0.04)),
    "compound_returns": bool(SESSION_DEFAULTS.get("inp_compound", False)),
}


def main() -> None:
    p = argparse.ArgumentParser(description="Threshold gap sweep 0…2.5")
    p.add_argument("--csv", type=str, default="", help="Input CSV (default: data/m15_tatn_{days}d.csv)")
    p.add_argument("--days", type=int, default=255, help="Lookback days for --fetch or default filename")
    p.add_argument("--fetch", action="store_true", help="Download from MOEX ISS before sweep")
    p.add_argument("--out-prefix", type=str, default="", help="Output prefix, default threshold_sweep_{days}d")
    args = p.parse_args()

    csv_path = Path(args.csv) if args.csv else ROOT / "data" / f"m15_tatn_{args.days}d.csv"
    prefix = args.out_prefix or f"threshold_sweep_{args.days}d"
    out_full = ROOT / "data" / f"{prefix}_0_2.5.csv"
    out_gap = ROOT / "data" / f"{prefix}_best_per_gap.csv"

    if args.fetch:
        print(f"MOEX ISS fetch {args.days}d → {csv_path}")
        save_m15_csv(fetch_m15_from_iss(days=args.days), csv_path)
    elif not csv_path.is_file():
        csv_path = DEFAULT_M15

    bars = load_bars_from_csv(str(csv_path), recalc_z=True, z_mode="rolling30")
    steps = [round(x, 1) for x in np.arange(0.0, MAX_TH + 0.001, STEP)]
    print(f"Bars: {len(bars)} ({bars[0].timestamp} … {bars[-1].timestamp})")
    print(f"Grid: {len(steps)}×{len(steps)} = {len(steps) ** 2}")

    df = param_sweep(bars, steps, steps, **SIM_KW)
    df["gap"] = (df["entry"] - df["exit_z"]).round(1)
    df["valid"] = df["exit_z"] < df["entry"]
    df.to_csv(out_full, index=False)
    print(f"Wrote {out_full} ({len(df)} rows)")

    valid = df[df["valid"]].copy()
    neg = valid[valid["total_pnl_rub"] < 0]
    best_rows = []
    for g in sorted(valid["gap"].unique()):
        sub = valid[valid["gap"] == g]
        best_rows.append(sub.loc[sub["total_pnl_rub"].idxmax()])
    by_gap = pd.DataFrame(best_rows)
    by_gap.to_csv(out_gap, index=False)
    print(f"Wrote {out_gap} ({len(by_gap)} gaps)")

    best = valid.loc[valid["total_pnl_rub"].idxmax()]
    print()
    print("=== BEST OVERALL ===")
    print(f"  вход ±{best.entry:.1f}, выход ±{best.exit_z:.1f}, Δ={best.gap:.1f}")
    print(f"  PnL {best.total_pnl_rub:,.0f} ₽, сделок {int(best.trade_count)}, DD {best.max_drawdown_rub:,.0f} ₽")
    print(f"  valid={len(valid)}, negative PnL={len(neg)}, min PnL={valid.total_pnl_rub.min():,.0f} ₽")

    bl = valid[(valid.entry == 0.8) & (valid.exit_z == 0.7)]
    if len(bl):
        b = bl.iloc[0]
        print(f"  baseline 0.8/0.7: {b.total_pnl_rub:,.0f} ₽ ({int(b.trade_count)} сд.)")


if __name__ == "__main__":
    main()
