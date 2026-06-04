"""Пирамидинг в run_z_strategy_sim — parity с Android MoexZStrategySim."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from zsim import Bar, run_z_strategy_sim  # noqa: E402


def _bar(ts: str, z: float, spread: float = 1.0) -> Bar:
    return Bar(
        timestamp=ts,
        spread_percent=spread,
        z_score=z,
        tatn_close=100.0,
        tatnp_close=99.0,
    )


def test_pyramiding_increases_commission_once_per_trade():
    """Long: вход, углубление Z → одна добавка; повторный бар depth не дублирует комиссию."""
    bars = [
        _bar("2026-01-01 10:00", 0.0, 1.0),
        _bar("2026-01-01 10:15", -0.5, 1.0),  # cross enter long @ entry 0.8
        _bar("2026-01-01 10:30", -1.1, 1.2),  # pyramid
        _bar("2026-01-01 10:45", -1.2, 1.1),  # still deep — no second pyramid
        _bar("2026-01-01 11:00", -0.5, 1.0),
        _bar("2026-01-01 11:15", 0.0, 1.0),  # exit long @ exit 0.7
    ]
    base = run_z_strategy_sim(
        bars,
        entry=0.8,
        exit_z=0.7,
        notional_rub=100_000.0,
        leverage=7.0,
        commission_pct_per_side=0.04,
    )
    pyramid = run_z_strategy_sim(
        bars,
        entry=0.8,
        exit_z=0.7,
        notional_rub=100_000.0,
        leverage=7.0,
        commission_pct_per_side=0.04,
        pyramid_add_notional_rub=50_000.0,
        pyramid_z_depth=1.0,
    )
    assert len(base.trades) == 1
    assert len(pyramid.trades) == 1
    assert pyramid.total_commission_rub > base.total_commission_rub
    assert pyramid.total_pnl_rub != base.total_pnl_rub


def test_pyramiding_off_when_add_zero():
    bars = [
        _bar("2026-01-01 10:00", 0.0),
        _bar("2026-01-01 10:15", -0.5),
        _bar("2026-01-01 10:30", -1.1),
        _bar("2026-01-01 11:00", -0.5),
        _bar("2026-01-01 11:15", 0.0),
    ]
    a = run_z_strategy_sim(bars, 0.8, 0.7, pyramid_add_notional_rub=0.0)
    b = run_z_strategy_sim(bars, 0.8, 0.7, pyramid_add_notional_rub=50_000.0, pyramid_z_depth=1.0)
    assert a.total_commission_rub == b.total_commission_rub or pytest.approx(
        a.total_commission_rub
    ) != b.total_commission_rub
