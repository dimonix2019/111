#!/usr/bin/env python3
"""
Полный тест стратегии на ~3 годах MOEX ISS (15м TATN/TATNP).

  cd strategy-web && python3 scripts/run_3y_strategy_test.py
  python3 scripts/run_3y_strategy_test.py --skip-fetch  # если CSV уже есть
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

from config import SESSION_DEFAULTS
from m15_iss_loader import fetch_m15_from_iss, save_m15_csv
from zsim import load_bars_from_csv, param_sweep, run_z_strategy_sim

# Импорт логики стабильности
sys.path.insert(0, str(ROOT / "scripts"))
from threshold_walkforward_stability import (  # noqa: E402
    FIXED_PRESETS,
    bootstrap_quarter_pnl,
    expanding_walkforward,
    ordered_quarters,
    quarter_pnl_table,
    rolling_walkforward,
    best_thresholds,
    bars_for_quarters,
    sim_quarter,
    QuarterKey,
    quarter_of,
)

LOOKBACK_DAYS = 365 * 3
STEP = 0.1
MAX_TH = 2.5
BOOTSTRAP_N = 10_000

SIM_KW = {
    "notional_rub": float(SESSION_DEFAULTS.get("inp_notional", 100_000)),
    "leverage": float(SESSION_DEFAULTS.get("inp_leverage", 7.0)),
    "commission_pct_per_side": float(SESSION_DEFAULTS.get("inp_commission", 0.04)),
    "compound_returns": bool(SESSION_DEFAULTS.get("inp_compound", False)),
}


def threshold_steps() -> list[float]:
    return [round(x, 1) for x in np.arange(0.0, MAX_TH + 0.001, STEP)]


def full_sweep_summary(bars) -> pd.DataFrame:
    steps = threshold_steps()
    df = param_sweep(bars, steps, steps, **SIM_KW)
    df["gap"] = (df["entry"] - df["exit_z"]).round(1)
    df["valid"] = df["exit_z"] < df["entry"]
    return df


def yearly_fixed_pnl(bars, q_order: list[QuarterKey]) -> pd.DataFrame:
    """Сумма PnL по календарным годам для фикс. пресетов."""
    rows = []
    years = sorted({q.year for q in q_order})
    for year in years:
        yb = [b for b in bars if quarter_of(b.timestamp).year == year]
        row = {"year": year, "bars": len(yb)}
        for name, entry, exit_z in FIXED_PRESETS:
            s = sim_quarter(yb, entry, exit_z)
            row[f"pnl_{name}"] = s["pnl"]
            row[f"trades_{name}"] = s["trades"]
        rows.append(row)
    return pd.DataFrame(rows)


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--days", type=int, default=LOOKBACK_DAYS)
    p.add_argument("--skip-fetch", action="store_true")
    args = p.parse_args()

    csv_path = ROOT / "data" / f"m15_tatn_{args.days}d.csv"
    prefix = f"3y_{args.days}d"
    out_dir = ROOT / "data"

    if not args.skip_fetch or not csv_path.is_file():
        print(f"MOEX fetch {args.days}d → {csv_path}")
        save_m15_csv(fetch_m15_from_iss(days=args.days), csv_path)
    else:
        print(f"Using existing {csv_path}")

    bars = load_bars_from_csv(str(csv_path), recalc_z=True, z_mode="rolling30")
    q_order = ordered_quarters(bars)
    print(f"Bars: {len(bars)} ({bars[0].timestamp} … {bars[-1].timestamp})")
    print(f"Quarters: {len(q_order)} ({q_order[0].label()} … {q_order[-1].label()})\n")

    # 1) Full grid
    print("Full threshold sweep 0…2.5…")
    sweep = full_sweep_summary(bars)
    sweep.to_csv(out_dir / f"{prefix}_threshold_sweep.csv", index=False)
    valid = sweep[sweep["valid"]]
    neg = valid[valid["total_pnl_rub"] < 0]
    best = valid.loc[valid["total_pnl_rub"].idxmax()]
    print(f"  Valid pairs: {len(valid)}, negative: {len(neg)}")
    print(f"  Best IS: ±{best.entry:.1f}/±{best.exit_z:.1f} Δ={best.gap:.1f} → {best.total_pnl_rub:,.0f} ₽")

    # 2) Quarterly fixed presets
    qtab = quarter_pnl_table(bars, q_order)
    qtab.to_csv(out_dir / f"{prefix}_quarter_pnl.csv", index=False)
    yearly = yearly_fixed_pnl(bars, q_order)
    yearly.to_csv(out_dir / f"{prefix}_yearly_pnl.csv", index=False)

    # 3) Walk-forward
    print("Walk-forward (expanding + rolling)…")
    expanding = expanding_walkforward(bars, q_order)
    rolling = rolling_walkforward(bars, q_order)
    expanding.to_csv(out_dir / f"{prefix}_wf_expanding.csv", index=False)
    rolling.to_csv(out_dir / f"{prefix}_wf_rolling.csv", index=False)

    # 4) Bootstrap
    print(f"Bootstrap {BOOTSTRAP_N}…")
    boot_rows = []
    for name, entry, exit_z in FIXED_PRESETS:
        pnls = qtab[f"pnl_{name}"].to_numpy(dtype=float)
        s = bootstrap_quarter_pnl(pnls, BOOTSTRAP_N)
        s["preset"] = name
        boot_rows.append(s)
    if len(rolling):
        s = bootstrap_quarter_pnl(rolling["oos_pnl"].to_numpy(dtype=float), BOOTSTRAP_N)
        s["preset"] = "rolling_WF"
        boot_rows.append(s)
    if len(expanding):
        s = bootstrap_quarter_pnl(expanding["oos_pnl"].to_numpy(dtype=float), BOOTSTRAP_N)
        s["preset"] = "expanding_WF"
        boot_rows.append(s)
    boot_df = pd.DataFrame(boot_rows)
    boot_df.to_csv(out_dir / f"{prefix}_bootstrap.csv", index=False)

    # Summary report
    summary_path = out_dir / f"{prefix}_summary.txt"
    lines = [
        f"=== 3Y STRATEGY TEST ({args.days}d) ===",
        f"Bars: {len(bars)}",
        f"Range: {bars[0].timestamp} … {bars[-1].timestamp}",
        f"Quarters: {len(q_order)}",
        "",
        "FULL GRID (IS):",
        f"  valid={len(valid)} negative={len(neg)}",
        f"  best ±{best.entry:.1f}/±{best.exit_z:.1f} PnL={best.total_pnl_rub:,.0f}",
        "",
        "FIXED PRESETS (full period):",
    ]
    for name, entry, exit_z in FIXED_PRESETS:
        r = run_z_strategy_sim(bars, entry=entry, exit_z=exit_z, **SIM_KW)
        st = r.stats()
        lines.append(
            f"  {name}: PnL={r.total_pnl_rub:,.0f} ₽ trades={st['trade_count']} DD={st['max_drawdown_rub']:,.0f}"
        )
    lines += ["", "YEARLY:"]
    for _, r in yearly.iterrows():
        parts = [str(int(r.year))]
        for name, _, _ in FIXED_PRESETS:
            parts.append(f"{name}={r[f'pnl_{name}']:,.0f}")
        lines.append("  " + " | ".join(parts))

    neg_q = {name: (qtab[f"pnl_{name}"] < 0).sum() for name, _, _ in FIXED_PRESETS}
    lines += ["", f"Negative quarters (of {len(qtab)}):"]
    for name, n in neg_q.items():
        lines.append(f"  {name}: {n}")

    lines += [
        "",
        f"EXPANDING WF OOS sum: {expanding.oos_pnl.sum():,.0f} neg={(expanding.oos_pnl<0).sum()}/{len(expanding)}",
        f"ROLLING WF OOS sum: {rolling.oos_pnl.sum():,.0f} neg={(rolling.oos_pnl<0).sum()}/{len(rolling)}",
        "",
        "BOOTSTRAP P(sum<0):",
    ]
    for _, r in boot_df.iterrows():
        lines.append(
            f"  {r['preset']}: {100*r['prob_sum_negative']:.2f}% "
            f"(observed sum {r['observed_sum']:,.0f})"
        )

    text = "\n".join(lines)
    summary_path.write_text(text, encoding="utf-8")
    print("\n" + text)
    print(f"\nArtifacts in {out_dir}/{prefix}_*")


if __name__ == "__main__":
    main()
