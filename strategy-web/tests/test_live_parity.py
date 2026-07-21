"""Parity: live edge vs sim on rolling Z bars."""

from __future__ import annotations

from live.parity import collect_sim_edges, find_matching_sim_edge
from live.signals import Position, Signal, determine_z_signal


def _bar(ms: int, z: float, label: str) -> dict:
    return {
        "timestampMs": ms,
        "tradeDate": label,
        "zScore": z,
        "spreadPercent": 3.0,
        "tatnClose": 400.0,
        "tatnpClose": 390.0,
    }


def test_collect_sim_edges_enter_long():
    step = 15 * 60 * 1000
    t0 = 1_700_000_000_000
    bars = [
        _bar(t0, -1.2, "2026-07-19 10:00"),
        _bar(t0 + step, -1.4, "2026-07-19 10:15"),
    ]
    edges = collect_sim_edges(bars, entry=1.3, exit_z=1.2)
    assert len(edges) == 1
    assert edges[0]["signal"] == Signal.ENTER_LONG.value
    assert edges[0]["bar_ts"] == "2026-07-19 10:15"


def test_find_matching_within_tolerance():
    sim = [{"bar_ts": "2026-07-19 10:15", "signal": "ENTER_LONG", "z": -1.4}]
    assert find_matching_sim_edge("2026-07-19 10:15", "ENTER_LONG", sim) is not None
    assert find_matching_sim_edge("2026-07-19 10:30", "ENTER_LONG", sim) is not None
    assert find_matching_sim_edge("2026-07-19 12:00", "ENTER_LONG", sim) is None


def test_edge_matches_determine_z_signal():
    assert determine_z_signal(-1.2, -1.4, Position.FLAT, 1.3, 1.2) == Signal.ENTER_LONG
