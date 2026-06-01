"""Unit tests for protection grid search."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from zsim import build_protection_grid_combos, load_bars_from_csv, run_protection_grid_search


def test_build_combos_capped():
    combos = build_protection_grid_combos(max_combinations=50)
    assert 1 <= len(combos) <= 50
    for c in combos:
        assert c["exit_z"] < c["entry"]


def test_grid_search_smoke():
    csv = ROOT / "data" / "m15_tatn_255d.csv"
    if not csv.is_file():
        return
    bars = load_bars_from_csv(str(csv), recalc_z=False)
    axes = {
        "entry": [0.8],
        "exit_z": [0.7],
        "slippage_spread_pts": [0.0, 0.02],
        "max_loss_spread_pts": [0.0],
        "max_loss_rub": [0.0],
        "min_spread_pct": [0.0],
        "max_spread_pct": [0.0],
        "entry_z_buffer": [0.0],
        "max_drawdown_halt_rub": [0.0],
        "max_drawdown_halt_pct": [0.0],
    }
    out = run_protection_grid_search(
        bars,
        max_combinations=10,
        top_n=5,
        only_good=False,
        axes=axes,
    )
    assert out["evaluated"] >= 1
    assert isinstance(out["rows"], list)
