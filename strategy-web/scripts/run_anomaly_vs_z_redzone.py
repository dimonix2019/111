#!/usr/bin/env python3
"""Z 0.7/0.5 vs перцентильная anomaly (90d p95/p5) с/без принудительного red-zone exit."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from anomaly_redzone_sim import SimConfig, StrategyKind, run_strategy_sim, summarize_result
from zsim import load_bars_from_csv


def run_suite(label: str, csv_path: Path) -> None:
    bars = load_bars_from_csv(str(csv_path), recalc_z=True, z_mode="rolling30")
    print(f"\n{'=' * 72}")
    print(f"{label}  |  bars={len(bars)}  {bars[0].timestamp[:10]} … {bars[-1].timestamp[:10]}")
    print(f"{'=' * 72}")
    print(
        f"{'Сценарий':<42} {'PnL':>12} {'#':>5} {'MDD':>9} "
        f"{'med h':>6} {'>48h':>5} {'RZ exit':>7} {'win%':>6}"
    )
    print("-" * 72)

    scenarios = [
        ("Z 0.7/0.5", SimConfig(kind=StrategyKind.Z, force_red_zone_exit=False)),
        ("Z 0.7/0.5 + red zone exit", SimConfig(kind=StrategyKind.Z, force_red_zone_exit=True)),
        (
            "Anomaly p95/p5 → median (90d)",
            SimConfig(kind=StrategyKind.PERCENTILE, force_red_zone_exit=False),
        ),
        (
            "Anomaly p95/p5 + red zone exit",
            SimConfig(kind=StrategyKind.PERCENTILE, force_red_zone_exit=True),
        ),
    ]

    rows = []
    for name, cfg in scenarios:
        r = run_strategy_sim(bars, cfg)
        s = summarize_result(name, r, entry_threshold=cfg.entry_z)
        rows.append(s)
        win_pct = 100.0 * s["wins"] / s["trades"] if s["trades"] else 0.0
        print(
            f"{name:<42} {s['pnl']:>11,.0f} ₽ {s['trades']:>5} {s['mdd']:>8,.0f} "
            f"{s['median_hold_h']:>6.1f} {s['hold_gt_48h']:>5} {s['red_zone_exits']:>7} {win_pct:>5.1f}%"
        )

    print("\nRed zone на закрытии (baseline, без force exit):")
    for s in rows:
        if s["name"].startswith("Z 0.7/0.5") and "red zone" not in s["name"]:
            print(f"  {s['name']}: {s['red_zone_at_close']} сделок закрылись уже в red zone")

    base = rows[0]["pnl"]
    z_rz = rows[1]["pnl"]
    anom = rows[2]["pnl"]
    anom_rz = rows[3]["pnl"]
    print("\nΔ PnL vs Z baseline:")
    print(f"  Z + red zone:     {z_rz - base:+,.0f} ₽")
    print(f"  Anomaly:          {anom - base:+,.0f} ₽")
    print(f"  Anomaly + red:    {anom_rz - base:+,.0f} ₽")


def main() -> None:
    run_suite("255d", ROOT / "data" / "m15_tatn_255d.csv")
    run_suite("3y", ROOT / "data" / "m15_tatn_1095d.csv")


if __name__ == "__main__":
    main()
