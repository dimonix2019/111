#!/usr/bin/env python3
"""
Walk-forward по календарным кварталам: на каждом шаге подбор entry/exit на train
(все предыдущие кварталы), оценка OOS на следующем квартале.

Z-score rolling 30д считается на полном ряду, затем бары режутся по кварталам.

  cd strategy-web && python3 scripts/threshold_walkforward_quarters.py
  python3 scripts/threshold_walkforward_quarters.py --csv data/m15_tatn_365d.csv
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from config import SESSION_DEFAULTS
from zsim import Bar, load_bars_from_csv, param_sweep, run_z_strategy_sim

STEP = 0.1
MAX_TH = 2.5

SIM_KW = {
    "notional_rub": float(SESSION_DEFAULTS.get("inp_notional", 100_000)),
    "leverage": float(SESSION_DEFAULTS.get("inp_leverage", 7.0)),
    "commission_pct_per_side": float(SESSION_DEFAULTS.get("inp_commission", 0.04)),
    "compound_returns": bool(SESSION_DEFAULTS.get("inp_compound", False)),
}


@dataclass(frozen=True)
class QuarterKey:
    year: int
    q: int

    def label(self) -> str:
        return f"{self.year}-Q{self.q}"


def quarter_of(ts: str) -> QuarterKey:
    dt = pd.to_datetime(ts)
    return QuarterKey(int(dt.year), int((dt.month - 1) // 3 + 1))


def threshold_steps() -> list[float]:
    return [round(x, 1) for x in np.arange(0.0, MAX_TH + 0.001, STEP)]


def bars_for_quarters(bars: list[Bar], keys: set[QuarterKey]) -> list[Bar]:
    return [b for b in bars if quarter_of(b.timestamp) in keys]


def best_thresholds_on_train(train_bars: list[Bar], min_trades: int = 5) -> tuple[float, float, float, int]:
    if len(train_bars) < 48:
        return 0.8, 0.7, 0.0, 0
    steps = threshold_steps()
    df = param_sweep(train_bars, steps, steps, **SIM_KW)
    valid = df[(df["exit_z"] < df["entry"]) & (df["trade_count"] >= min_trades)]
    if valid.empty:
        row = df[df["exit_z"] < df["entry"]].sort_values("total_pnl_rub", ascending=False).head(1)
        if row.empty:
            return 0.8, 0.7, 0.0, 0
        r = row.iloc[0]
        return float(r["entry"]), float(r["exit_z"]), float(r["total_pnl_rub"]), int(r["trade_count"])
    r = valid.loc[valid["total_pnl_rub"].idxmax()]
    return float(r["entry"]), float(r["exit_z"]), float(r["total_pnl_rub"]), int(r["trade_count"])


def sim_quarter(test_bars: list[Bar], entry: float, exit_z: float) -> dict:
    if len(test_bars) < 2:
        return {"pnl": 0.0, "trades": 0, "max_dd": 0.0}
    res = run_z_strategy_sim(test_bars, entry=entry, exit_z=exit_z, **SIM_KW)
    st = res.stats()
    return {
        "pnl": float(res.total_pnl_rub),
        "trades": int(st["trade_count"]),
        "max_dd": float(st["max_drawdown_rub"]),
    }


def main() -> None:
    p = argparse.ArgumentParser(description="Walk-forward threshold sweep by calendar quarter")
    p.add_argument("--csv", type=str, default=str(ROOT / "data" / "m15_tatn_365d.csv"))
    p.add_argument("--min-train-trades", type=int, default=5)
    args = p.parse_args()

    csv_path = Path(args.csv)
    bars = load_bars_from_csv(str(csv_path), recalc_z=True, z_mode="rolling30")
    print(f"Bars: {len(bars)} ({bars[0].timestamp} … {bars[-1].timestamp})")

    q_order: list[QuarterKey] = []
    seen: set[QuarterKey] = set()
    for b in bars:
        qk = quarter_of(b.timestamp)
        if qk not in seen:
            seen.add(qk)
            q_order.append(qk)
    q_order.sort(key=lambda x: (x.year, x.q))
    print(f"Quarters: {[q.label() for q in q_order]}")

    rows: list[dict] = []
    for i in range(1, len(q_order)):
        train_keys = set(q_order[:i])
        test_key = q_order[i]
        train_bars = bars_for_quarters(bars, train_keys)
        test_bars = bars_for_quarters(bars, {test_key})

        entry, exit_z, train_pnl, train_trades = best_thresholds_on_train(
            train_bars, min_trades=args.min_train_trades
        )
        oos = sim_quarter(test_bars, entry, exit_z)
        gap = round(entry - exit_z, 1)

        base_oos = sim_quarter(test_bars, 0.8, 0.7)
        full_best_oos = sim_quarter(test_bars, 1.3, 1.1)

        rows.append(
            {
                "test_quarter": test_key.label(),
                "train_quarters": ",".join(q.label() for q in q_order[:i]),
                "train_bars": len(train_bars),
                "test_bars": len(test_bars),
                "wf_entry": entry,
                "wf_exit": exit_z,
                "wf_gap": gap,
                "train_pnl_is": train_pnl,
                "train_trades_is": train_trades,
                "oos_pnl": oos["pnl"],
                "oos_trades": oos["trades"],
                "oos_max_dd": oos["max_dd"],
                "baseline_08_07_oos": base_oos["pnl"],
                "fixed_13_11_oos": full_best_oos["pnl"],
            }
        )

    out = pd.DataFrame(rows)
    out_path = ROOT / "data" / "threshold_walkforward_quarters.csv"
    out.to_csv(out_path, index=False)
    print(f"\nWrote {out_path}")

    print("\n=== WALK-FORWARD OOS BY QUARTER ===")
    print(
        f"{'Q test':<8} {'train':<20} {'WF вх/вых':<12} {'Δ':>4} "
        f"{'OOS PnL':>10} {'сд':>4} {'0.8/0.7':>10} {'1.3/1.1':>10}"
    )
    print("-" * 88)
    for _, r in out.iterrows():
        print(
            f"{r.test_quarter:<8} {r.train_quarters[-20:]:<20} "
            f"{r.wf_entry:.1f}/{r.wf_exit:.1f}     {r.wf_gap:4.1f} "
            f"{r.oos_pnl:10,.0f} {int(r.oos_trades):4d} "
            f"{r.baseline_08_07_oos:10,.0f} {r.fixed_13_11_oos:10,.0f}"
        )

    total_oos = out["oos_pnl"].sum()
    neg_q = out[out["oos_pnl"] < 0]
    print(f"\nСумма OOS PnL (WF подбор): {total_oos:,.0f} ₽")
    print(f"Кварталов с OOS < 0: {len(neg_q)} / {len(out)}")
    if len(neg_q):
        for _, r in neg_q.iterrows():
            print(f"  {r.test_quarter}: {r.oos_pnl:,.0f} ₽ (вх {r.wf_entry:.1f}/вых {r.wf_exit:.1f})")

    print(f"\nСумма OOS baseline 0.8/0.7: {out['baseline_08_07_oos'].sum():,.0f} ₽")
    print(f"Сумма OOS fixed 1.3/1.1: {out['fixed_13_11_oos'].sum():,.0f} ₽")


if __name__ == "__main__":
    main()
