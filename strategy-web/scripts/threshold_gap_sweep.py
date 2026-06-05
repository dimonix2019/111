#!/usr/bin/env python3
"""
Сетка порогов вход/выход 0.0…2.5 (шаг 0.1) на 15м ряду TATN/TATNP.
Экспорт: data/threshold_sweep_0_2.5.csv, data/threshold_sweep_best_per_gap.csv

Запуск из корня репозитория:
  python3 strategy-web/scripts/threshold_gap_sweep.py
или:
  cd strategy-web && python3 scripts/threshold_gap_sweep.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from config import DEFAULT_M15, SESSION_DEFAULTS
from zsim import load_bars_from_csv, param_sweep

OUT_FULL = ROOT / "data" / "threshold_sweep_0_2.5.csv"
OUT_GAP = ROOT / "data" / "threshold_sweep_best_per_gap.csv"

STEP = 0.1
MAX_TH = 2.5

SIM_KW = {
    "notional_rub": float(SESSION_DEFAULTS.get("inp_notional", 100_000)),
    "leverage": float(SESSION_DEFAULTS.get("inp_leverage", 7.0)),
    "commission_pct_per_side": float(SESSION_DEFAULTS.get("inp_commission", 0.04)),
    "compound_returns": bool(SESSION_DEFAULTS.get("inp_compound", False)),
}


def main() -> None:
    csv_path = DEFAULT_M15
    bars = load_bars_from_csv(str(csv_path), recalc_z=True, z_mode="rolling30")
    steps = [round(x, 1) for x in np.arange(0.0, MAX_TH + 0.001, STEP)]
    print(f"Bars: {len(bars)} ({bars[0].timestamp} … {bars[-1].timestamp})")
    print(f"Grid: {len(steps)}×{len(steps)} = {len(steps) ** 2}")

    df = param_sweep(bars, steps, steps, **SIM_KW)
    df["gap"] = (df["entry"] - df["exit_z"]).round(1)
    df["valid"] = df["exit_z"] < df["entry"]
    df.to_csv(OUT_FULL, index=False)
    print(f"Wrote {OUT_FULL} ({len(df)} rows)")

    valid = df[df["valid"]].copy()
    best_rows = []
    for g in sorted(valid["gap"].unique()):
        sub = valid[valid["gap"] == g]
        best_rows.append(sub.loc[sub["total_pnl_rub"].idxmax()])
    by_gap = pd.DataFrame(best_rows)
    by_gap.to_csv(OUT_GAP, index=False)
    print(f"Wrote {OUT_GAP} ({len(by_gap)} gaps)")

    best = valid.loc[valid["total_pnl_rub"].idxmax()]
    print()
    print("=== BEST OVERALL ===")
    print(f"  вход ±{best.entry:.1f}, выход ±{best.exit_z:.1f}, Δ={best.gap:.1f}")
    print(f"  PnL {best.total_pnl_rub:,.0f} ₽, сделок {int(best.trade_count)}, DD {best.max_drawdown_rub:,.0f} ₽")

    bl = valid[(valid.entry == 0.8) & (valid.exit_z == 0.7)]
    if len(bl):
        b = bl.iloc[0]
        print(f"  baseline 0.8/0.7: {b.total_pnl_rub:,.0f} ₽ ({int(b.trade_count)} сд.)")


if __name__ == "__main__":
    main()
