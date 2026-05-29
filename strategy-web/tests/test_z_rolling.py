"""Тесты apply_z_scores_rolling — parity с Android MoexZScoreRollingTest."""

from __future__ import annotations

import sys
from datetime import datetime, timedelta
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from zsim import (  # noqa: E402
    apply_z_scores,
    apply_z_scores_rolling,
    load_bars_from_csv,
    run_z_strategy_sim,
    z_diagnostics,
)


def _point(day_offset: int, minute: int, spread: float) -> tuple[str, float]:
    dt = datetime(2026, 1, 1, 10, 0) + timedelta(days=day_offset, minutes=minute * 15)
    return dt.strftime("%Y-%m-%d %H:%M"), spread


def test_rolling_causal_past_bar_unchanged_when_future_appended():
    spreads_ts = [_point(d, 0, 1.0 + d * 0.01) for d in range(45)]
    spreads = [s for _, s in spreads_ts]
    ts = [t for t, _ in spreads_ts]
    z_short = apply_z_scores_rolling(spreads[:11], ts[:11], lookback_days=30, min_bars_in_window=2)
    z_ext = apply_z_scores_rolling(spreads, ts, lookback_days=30, min_bars_in_window=2)
    assert z_short[-1] == pytest.approx(z_ext[10], abs=1e-9)


def test_rolling_differs_from_global_on_regime_shift():
    calm = [_point(d, 0, 1.0) for d in range(200)]
    spike = [_point(200, i, 3.5) for i in range(8)]
    spreads_ts = calm + spike
    spreads = [s for _, s in spreads_ts]
    ts = [t for t, _ in spreads_ts]
    idx = 100
    z_global = float(apply_z_scores(spreads)[idx])
    z_roll = apply_z_scores_rolling(spreads, ts, lookback_days=30, min_bars_in_window=2)[idx]
    assert abs(z_global - z_roll) > 0.1


def test_rolling_warmup_zeros_until_min_bars():
    spreads_ts = [_point(0, i, 5.0) for i in range(10)]
    spreads = [s for _, s in spreads_ts]
    ts = [t for t, _ in spreads_ts]
    zs = apply_z_scores_rolling(spreads, ts, lookback_days=30, min_bars_in_window=20)
    assert all(z == 0.0 for z in zs)


def test_load_bars_rolling30_from_sample_csv():
    sample = ROOT / "data" / "sample.csv"
    if not sample.is_file():
        pytest.skip("sample.csv missing")
    bars_g = load_bars_from_csv(str(sample), z_mode="global")
    bars_r = load_bars_from_csv(str(sample), z_mode="rolling30")
    assert len(bars_g) == len(bars_r)
    dg, dr = z_diagnostics(bars_g), z_diagnostics(bars_r)
    assert dg["max_abs_z"] >= 0.0
    assert dr["max_abs_z"] >= 0.0


def test_simulation_runs_on_rolling_sample():
    sample = ROOT / "data" / "sample.csv"
    if not sample.is_file():
        pytest.skip("sample.csv missing")
    bars = load_bars_from_csv(str(sample), z_mode="rolling30")
    if len(bars) < 2:
        pytest.skip("too few bars")
    sim = run_z_strategy_sim(bars, entry=0.8, exit_z=0.7)
    assert sim.bar_count == len(bars)
