#!/usr/bin/env python3
"""
Сетка Entry/Exit 0.5–1.3: baseline (текущие правила zsim) vs underwater 12ч (сценарий A).
CSV: data/compare_underwater_sweep.csv
"""
from __future__ import annotations

import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from config import DEFAULT_M15, SESSION_DEFAULTS
from zsim import load_bars_from_csv, run_z_strategy_sim

CSV_PATH = ROOT / "data" / "m15_tatn_255d.csv"
OUT_CSV = ROOT / "data" / "compare_underwater_sweep.csv"

# Пороги 0.5 … 1.3 шаг 0.1
THRESHOLDS = [round(0.5 + i * 0.1, 1) for i in range(9)]

SIM_KW = {
    "notional_rub": float(SESSION_DEFAULTS.get("inp_notional", 100_000)),
    "leverage": float(SESSION_DEFAULTS.get("inp_leverage", 7.0)),
    "commission_pct_per_side": float(SESSION_DEFAULTS.get("inp_commission", 0.04)),
    "compound_returns": bool(SESSION_DEFAULTS.get("inp_compound", False)),
}


def _pf_num(pf: float) -> float:
    if pf == float("inf"):
        return 99.0
    return float(pf)


def run_pair(bars, entry: float, exit_z: float, underwater_hours: float) -> dict:
    res = run_z_strategy_sim(
        bars,
        entry=entry,
        exit_z=exit_z,
        underwater_exit_hours=underwater_hours,
        **SIM_KW,
    )
    st = res.stats()
    uw_count = sum(1 for t in res.trades if t.exit_reason == "underwater_cap")
    return {
        "total_pnl_rub": round(res.total_pnl_rub, 0),
        "trade_count": st["trade_count"],
        "win_rate_pct": round(st["win_rate_pct"], 1),
        "max_drawdown_rub": round(st["max_drawdown_rub"], 0),
        "profit_factor": round(_pf_num(st["profit_factor"]), 2),
        "avg_hold_hours": round(st["avg_hold_hours"], 1),
        "underwater_exits": uw_count,
    }


def main() -> None:
    path = CSV_PATH if CSV_PATH.is_file() else DEFAULT_M15
    if not path.is_file():
        print(f"Missing CSV: {path}", file=sys.stderr)
        raise SystemExit(1)

    print(f"Loading {path} (rolling30 Z)...")
    bars = load_bars_from_csv(str(path), z_mode="rolling30")

    rows: list[dict] = []
    pairs = [(e, x) for e in THRESHOLDS for x in THRESHOLDS if x < e]
    total = len(pairs)
    print(f"Grid: {len(THRESHOLDS)} entry x exit (< entry) = {total} pairs x 2 modes")

    for i, (entry, exit_z) in enumerate(pairs, 1):
        base = run_pair(bars, entry, exit_z, 0.0)
        uw = run_pair(bars, entry, exit_z, 12.0)
        delta_pnl = uw["total_pnl_rub"] - base["total_pnl_rub"]
        delta_trades = uw["trade_count"] - base["trade_count"]
        rows.append(
            {
                "entry": entry,
                "exit_z": exit_z,
                "baseline_pnl": base["total_pnl_rub"],
                "baseline_trades": base["trade_count"],
                "baseline_wr": base["win_rate_pct"],
                "baseline_mdd": base["max_drawdown_rub"],
                "baseline_pf": base["profit_factor"],
                "uw_pnl": uw["total_pnl_rub"],
                "uw_trades": uw["trade_count"],
                "uw_wr": uw["win_rate_pct"],
                "uw_mdd": uw["max_drawdown_rub"],
                "uw_pf": uw["profit_factor"],
                "uw_forced": uw["underwater_exits"],
                "delta_pnl": delta_pnl,
                "delta_trades": delta_trades,
                "delta_mdd": uw["max_drawdown_rub"] - base["max_drawdown_rub"],
            }
        )
        if i % 10 == 0 or i == total:
            print(f"  {i}/{total}  entry={entry} exit={exit_z}")

    df = pd.DataFrame(rows)
    df = df.sort_values("delta_pnl", ascending=False)
    OUT_CSV.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(OUT_CSV, index=False, encoding="utf-8-sig")

    # Текущий пресет теста (как в config «Свои» / sidebar по умолчанию)
    ref_entry = float(SESSION_DEFAULTS.get("inp_entry", 0.8))
    ref_exit = float(SESSION_DEFAULTS.get("inp_exit_z", 0.7))
    ref_base = run_pair(bars, ref_entry, ref_exit, 0.0)
    ref_uw = run_pair(bars, ref_entry, ref_exit, 12.0)

    print()
    print("=== Текущий тест (Entry {:.2f} / Exit {:.2f}) ===".format(ref_entry, ref_exit))
    print(
        f"  Baseline:  PnL {ref_base['total_pnl_rub']:,.0f} ₽ | "
        f"{ref_base['trade_count']} сд. | WR {ref_base['win_rate_pct']:.1f}% | "
        f"MDD {ref_base['max_drawdown_rub']:,.0f} ₽ | PF {ref_base['profit_factor']:.2f}"
    )
    print(
        f"  Underwater 12ч: PnL {ref_uw['total_pnl_rub']:,.0f} ₽ | "
        f"{ref_uw['trade_count']} сд. | WR {ref_uw['win_rate_pct']:.1f}% | "
        f"MDD {ref_uw['max_drawdown_rub']:,.0f} ₽ | PF {ref_uw['profit_factor']:.2f} | "
        f"принуд. выходов {ref_uw['underwater_exits']}"
    )
    print(
        f"  Δ PnL {ref_uw['total_pnl_rub'] - ref_base['total_pnl_rub']:+,.0f} ₽ | "
        f"Δ MDD {ref_uw['max_drawdown_rub'] - ref_base['max_drawdown_rub']:+,.0f} ₽"
    )

    best_base = df.loc[df["baseline_pnl"].idxmax()]
    best_uw = df.loc[df["uw_pnl"].idxmax()]
    best_delta = df.iloc[0]

    print()
    print("=== Лучшее по PnL (baseline) ===")
    print(
        f"  Entry {best_base['entry']:.1f} Exit {best_base['exit_z']:.1f}: "
        f"{best_base['baseline_pnl']:,.0f} ₽ ({int(best_base['baseline_trades'])} сд.)"
    )
    print("=== Лучшее по PnL (underwater 12ч) ===")
    print(
        f"  Entry {best_uw['entry']:.1f} Exit {best_uw['exit_z']:.1f}: "
        f"{best_uw['uw_pnl']:,.0f} ₽ ({int(best_uw['uw_trades'])} сд.)"
    )
    print("=== Наибольший выигрыш от underwater vs baseline (Δ PnL) ===")
    print(
        f"  Entry {best_delta['entry']:.1f} Exit {best_delta['exit_z']:.1f}: "
        f"Δ {best_delta['delta_pnl']:+,.0f} ₽ | Δ MDD {best_delta['delta_mdd']:+,.0f} ₽"
    )
    worse = df.loc[df["delta_pnl"].idxmin()]
    print("=== Наибольший проигрыш от underwater ===")
    print(
        f"  Entry {worse['entry']:.1f} Exit {worse['exit_z']:.1f}: "
        f"Δ {worse['delta_pnl']:+,.0f} ₽"
    )

    improved = (df["delta_pnl"] > 0).sum()
    print()
    print(f"Underwater лучше baseline по PnL: {improved}/{len(df)} пар ({100*improved/len(df):.0f}%)")
    print(f"Saved: {OUT_CSV}")


if __name__ == "__main__":
    main()
