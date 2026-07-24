"""Closed-trade history metrics."""

from __future__ import annotations

from live.closed_metrics import compute_closed_breakdown, compute_path_metrics


def test_resolve_entry_slip_picks_prev_bar():
    from live.open_mark import resolve_entry_slip_pts
    from live.closed_metrics import _parse_ms

    entry = "2026-07-22 07:15"
    prev = "2026-07-22 07:00"
    bars = [
        {
            "tradeDate": prev,
            "timestampMs": _parse_ms(prev),
            "spreadPercent": 4.362722704431232,
        },
        {
            "tradeDate": entry,
            "timestampMs": _parse_ms(entry),
            "spreadPercent": 3.8409,
        },
    ]
    slip, iss = resolve_entry_slip_pts(
        {
            "direction": "LONG",
            "entry_time": entry,
            "entry_spread": 4.379562043795631,
        },
        bars=bars,
    )
    assert iss is not None and abs(iss - 4.362722704431232) < 1e-6
    assert slip is not None and abs(slip - 0.016839339364398853) < 1e-6


def test_closed_breakdown_long_profit():
    m = compute_closed_breakdown(
        {
            "direction": "LONG",
            "entry_spread": 4.0,
            "exit_spread": 4.2,
            "entry_time": "2026-07-20 16:45",
            "exit_time": "2026-07-20 17:00",
        },
        deposit_rub=10_000,
        leverage=7,
    )
    # 0.2 п.п. × 70000 / 100 = 140 gross; comm 56; ovn 0
    assert abs(m["gross_rub"] - 140.0) < 1e-6
    assert abs(m["commission_rub"] - 56.0) < 1e-6
    assert abs(m["pnl_rub"] - 84.0) < 1e-6


def test_path_min_max_and_hit():
    bars = [
        {"timestampMs": 1_000, "tradeDate": "2026-07-20 16:45", "spreadPercent": 4.0},
        {"timestampMs": 2_000, "tradeDate": "2026-07-20 17:00", "spreadPercent": 4.3},
        {"timestampMs": 3_000, "tradeDate": "2026-07-20 17:15", "spreadPercent": 4.1},
    ]
    # fake ms via parse — use real-looking timestamps by embedding in tradeDate only;
    # compute_path_metrics falls back to parse of tradeDate when timestampMs set.
    trade = {
        "direction": "LONG",
        "entry_spread": 4.0,
        "exit_spread": 4.1,
        "entry_time": "2026-07-20 16:45",
        "exit_time": "2026-07-20 17:15",
    }
    # Build bars with parseable dates only (timestampMs from dates)
    from live.closed_metrics import _parse_ms

    bars2 = []
    for b in bars:
        bars2.append(
            {
                **b,
                "timestampMs": _parse_ms(b["tradeDate"]),
            }
        )
    m = compute_path_metrics(trade, bars2, deposit_rub=10_000, leverage=7)
    assert m["pnl_min_rub"] is not None
    assert m["pnl_max_rub"] is not None
    assert m["pnl_max_rub"] >= m["pnl_min_rub"]
    assert m["hit1_time"] is not None
