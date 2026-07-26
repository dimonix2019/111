"""tip1m sim must populate hit1/2/3Date like M15 Testing milestones."""
from __future__ import annotations

import numpy as np

from replay.tip_touch import PreparedTips, run_touch_1m_trades


def _prep(dates, z, spread) -> PreparedTips:
    n = len(dates)
    return PreparedTips(
        ts_ms=np.arange(n, dtype=np.int64) * 60_000,
        z=np.asarray(z, dtype=np.float64),
        spread=np.asarray(spread, dtype=np.float64),
        day_ord=np.zeros(n, dtype=np.int32),
        session=np.ones(n, dtype=bool),
        trade_dates=list(dates),
        edge_i=np.arange(1, n, dtype=np.int32),
        n=n,
    )


def test_hit_milestones_even_when_tp_off():
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
    ]
    # Enter long i=1 (0 → -1.1 crosses -1); exit i=5 (-0.6 → -0.2 crosses -0.3).
    z = [0.0, -1.1, -1.0, -0.8, -0.6, -0.2]
    # Favorable long move on tip spreads after entry at ~10.02.
    spread = [10.0, 10.0, 10.20, 10.40, 10.50, 10.10]
    r = run_touch_1m_trades(
        _prep(dates, z, spread),
        entry=1.0,
        exit_z=0.3,
        slip=0.02,
        notional=10_000.0,
        take_profit_pct=0.0,
    )
    assert r["summary"]["trades"] == 1
    t = r["trades"][0]
    assert t["hit1Date"] is not None, t
    assert "hit2Date" in t and "hit3Date" in t


def test_tp_close_still_records_hits():
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
    ]
    z = [0.0, -1.1, -1.0, -0.9]
    spread = [10.0, 10.0, 10.50, 10.60]
    r = run_touch_1m_trades(
        _prep(dates, z, spread),
        entry=1.0,
        exit_z=0.3,
        slip=0.02,
        notional=10_000.0,
        take_profit_pct=1.0,
    )
    assert r["summary"]["trades"] >= 1
    t = r["trades"][0]
    assert t["exitReason"] == "tp"
    assert t["hit1Date"] is not None
