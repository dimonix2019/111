#!/usr/bin/env python3
"""
Стабильность порогов: rolling WF (train = 1 прошлый квартал) + bootstrap по кварталам.

  cd strategy-web && python3 scripts/threshold_walkforward_stability.py
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
BOOTSTRAP_N = 10_000
RNG = np.random.default_rng(42)

SIM_KW = {
    "notional_rub": float(SESSION_DEFAULTS.get("inp_notional", 100_000)),
    "leverage": float(SESSION_DEFAULTS.get("inp_leverage", 7.0)),
    "commission_pct_per_side": float(SESSION_DEFAULTS.get("inp_commission", 0.04)),
    "compound_returns": bool(SESSION_DEFAULTS.get("inp_compound", False)),
}

FIXED_PRESETS = [
    ("0.8/0.7", 0.8, 0.7),
    ("1.3/1.1", 1.3, 1.1),
    ("1.4/1.3", 1.4, 1.3),
]


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


def best_thresholds(train_bars: list[Bar], min_trades: int = 5) -> tuple[float, float, float]:
    if len(train_bars) < 48:
        return 0.8, 0.7, 0.0
    steps = threshold_steps()
    df = param_sweep(train_bars, steps, steps, **SIM_KW)
    valid = df[(df["exit_z"] < df["entry"]) & (df["trade_count"] >= min_trades)]
    if valid.empty:
        row = df[df["exit_z"] < df["entry"]].sort_values("total_pnl_rub", ascending=False).head(1)
        if row.empty:
            return 0.8, 0.7, 0.0
        r = row.iloc[0]
        return float(r["entry"]), float(r["exit_z"]), float(r["total_pnl_rub"])
    r = valid.loc[valid["total_pnl_rub"].idxmax()]
    return float(r["entry"]), float(r["exit_z"]), float(r["total_pnl_rub"])


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


def ordered_quarters(bars: list[Bar]) -> list[QuarterKey]:
    seen: set[QuarterKey] = set()
    order: list[QuarterKey] = []
    for b in bars:
        qk = quarter_of(b.timestamp)
        if qk not in seen:
            seen.add(qk)
            order.append(qk)
    order.sort(key=lambda x: (x.year, x.q))
    return order


def quarter_pnl_table(bars: list[Bar], q_order: list[QuarterKey]) -> pd.DataFrame:
    rows = []
    for qk in q_order:
        qb = bars_for_quarters(bars, {qk})
        row = {"quarter": qk.label(), "bars": len(qb)}
        for name, entry, exit_z in FIXED_PRESETS:
            s = sim_quarter(qb, entry, exit_z)
            row[f"pnl_{name}"] = s["pnl"]
            row[f"trades_{name}"] = s["trades"]
        rows.append(row)
    return pd.DataFrame(rows)


def rolling_walkforward(bars: list[Bar], q_order: list[QuarterKey]) -> pd.DataFrame:
    rows = []
    for i in range(1, len(q_order)):
        train_key = q_order[i - 1]
        test_key = q_order[i]
        train_bars = bars_for_quarters(bars, {train_key})
        test_bars = bars_for_quarters(bars, {test_key})
        entry, exit_z, train_pnl = best_thresholds(train_bars)
        oos = sim_quarter(test_bars, entry, exit_z)
        rows.append(
            {
                "test_quarter": test_key.label(),
                "train_quarter": train_key.label(),
                "wf_entry": entry,
                "wf_exit": exit_z,
                "wf_gap": round(entry - exit_z, 1),
                "train_pnl_is": train_pnl,
                "oos_pnl": oos["pnl"],
                "oos_trades": oos["trades"],
            }
        )
    return pd.DataFrame(rows)


def expanding_walkforward(bars: list[Bar], q_order: list[QuarterKey]) -> pd.DataFrame:
    rows = []
    for i in range(1, len(q_order)):
        train_keys = set(q_order[:i])
        test_key = q_order[i]
        train_bars = bars_for_quarters(bars, train_keys)
        test_bars = bars_for_quarters(bars, {test_key})
        entry, exit_z, train_pnl = best_thresholds(train_bars)
        oos = sim_quarter(test_bars, entry, exit_z)
        rows.append(
            {
                "test_quarter": test_key.label(),
                "train_quarters": ",".join(q.label() for q in q_order[:i]),
                "wf_entry": entry,
                "wf_exit": exit_z,
                "oos_pnl": oos["pnl"],
                "train_pnl_is": train_pnl,
            }
        )
    return pd.DataFrame(rows)


def bootstrap_quarter_pnl(
    q_pnls: np.ndarray,
    n_boot: int = BOOTSTRAP_N,
) -> dict:
    """Ресэмпл кварталов с возвратом → распределение суммы PnL за n кварталов."""
    n = len(q_pnls)
    if n == 0:
        return {}
    sums = np.empty(n_boot, dtype=float)
    for i in range(n_boot):
        pick = RNG.integers(0, n, size=n)
        sums[i] = float(q_pnls[pick].sum())
    neg = float((sums < 0).mean())
    return {
        "n_quarters": n,
        "observed_sum": float(q_pnls.sum()),
        "observed_mean": float(q_pnls.mean()),
        "observed_neg_quarters": int((q_pnls < 0).sum()),
        "boot_mean": float(sums.mean()),
        "boot_median": float(np.median(sums)),
        "boot_p5": float(np.percentile(sums, 5)),
        "boot_p95": float(np.percentile(sums, 95)),
        "prob_sum_negative": neg,
        "prob_mean_quarter_negative": float((q_pnls < 0).mean()),
    }


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--csv", type=str, default=str(ROOT / "data" / "m15_tatn_365d.csv"))
    args = p.parse_args()

    bars = load_bars_from_csv(str(args.csv), recalc_z=True, z_mode="rolling30")
    q_order = ordered_quarters(bars)
    print(f"Bars {len(bars)}, quarters: {[q.label() for q in q_order]}\n")

    qtab = quarter_pnl_table(bars, q_order)
    rolling = rolling_walkforward(bars, q_order)
    expanding = expanding_walkforward(bars, q_order)

    out_dir = ROOT / "data"
    qtab.to_csv(out_dir / "quarter_pnl_fixed_presets.csv", index=False)
    rolling.to_csv(out_dir / "threshold_walkforward_rolling.csv", index=False)
    expanding.to_csv(out_dir / "threshold_walkforward_expanding_summary.csv", index=False)

    print("=== PnL ПО КВАРТАЛАМ (фикс. пороги, in-quarter sim) ===")
    for _, r in qtab.iterrows():
        parts = [f"{r.quarter}"]
        for name, _, _ in FIXED_PRESETS:
            parts.append(f"{name}={r[f'pnl_{name}']:,.0f}")
        print("  " + " | ".join(parts))

    print("\n=== ROLLING WF (train = 1 прошлый квартал) ===")
    for _, r in rolling.iterrows():
        sign = "+" if r.oos_pnl >= 0 else ""
        print(
            f"  {r.test_quarter}: train {r.train_quarter} → "
            f"{r.wf_entry:.1f}/{r.wf_exit:.1f} OOS {sign}{r.oos_pnl:,.0f} ₽"
        )
    print(f"  Сумма OOS: {rolling.oos_pnl.sum():,.0f} ₽, минусовых: {(rolling.oos_pnl < 0).sum()}/{len(rolling)}")

    print("\n=== EXPANDING WF (для сравнения) ===")
    print(f"  Сумма OOS: {expanding.oos_pnl.sum():,.0f} ₽, минусовых: {(expanding.oos_pnl < 0).sum()}/{len(expanding)}")

    print(f"\n=== BOOTSTRAP ({BOOTSTRAP_N} ресэмплов кварталов) ===")
    boot_rows = []
    for name, entry, exit_z in FIXED_PRESETS:
        col = f"pnl_{name}"
        pnls = qtab[col].to_numpy(dtype=float)
        stats = bootstrap_quarter_pnl(pnls)
        stats["preset"] = name
        boot_rows.append(stats)
        print(f"\n  {name}:")
        print(f"    наблюдение: сумма {stats['observed_sum']:,.0f} ₽, минус кварталов {stats['observed_neg_quarters']}/{stats['n_quarters']}")
        print(f"    bootstrap сумма: median {stats['boot_median']:,.0f}, 5–95% [{stats['boot_p5']:,.0f} … {stats['boot_p95']:,.0f}]")
        print(f"    P(сумма OOS < 0 при {stats['n_quarters']} кварталах) ≈ {100*stats['prob_sum_negative']:.1f}%")
        print(f"    P(один случайный квартал < 0) ≈ {100*stats['prob_mean_quarter_negative']:.1f}%")

    # bootstrap rolling WF OOS quarters
    if len(rolling):
        wf_pnls = rolling["oos_pnl"].to_numpy(dtype=float)
        wf_stats = bootstrap_quarter_pnl(wf_pnls)
        wf_stats["preset"] = "rolling_WF"
        boot_rows.append(wf_stats)
        print(f"\n  rolling WF optimizer:")
        print(f"    сумма OOS {wf_stats['observed_sum']:,.0f} ₽, минус {(wf_pnls < 0).sum()}/{len(wf_pnls)}")
        print(f"    P(сумма < 0) ≈ {100*wf_stats['prob_sum_negative']:.1f}%")

    pd.DataFrame(boot_rows).to_csv(out_dir / "quarter_bootstrap_summary.csv", index=False)
    print(f"\nWrote CSVs to {out_dir}/")


if __name__ == "__main__":
    main()
