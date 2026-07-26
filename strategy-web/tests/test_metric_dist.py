"""Unit tests for Trade desk Z/spread distribution builder."""

from __future__ import annotations

import math

from live.metric_dist import compute_numeric_distribution, invalidate_desk_metric_dists


def test_compute_numeric_distribution_basic():
    values = [1.0, 2.0, 3.0, 4.0, 5.0]
    d = compute_numeric_distribution(values)
    assert d is not None
    assert d["n"] == 5
    assert d["min"] == 1.0
    assert d["max"] == 5.0
    assert abs(d["mean"] - 3.0) < 1e-9
    assert abs(d["stdev"] - math.sqrt(2.5)) < 1e-9
    assert d["binCount"] == len(d["bins"])
    assert sum(d["bins"]) == 5
    assert "sorted" not in d


def test_compute_numeric_distribution_empty():
    assert compute_numeric_distribution([]) is None


def test_compute_numeric_distribution_constant():
    d = compute_numeric_distribution([2.5, 2.5, 2.5])
    assert d is not None
    assert d["n"] == 3
    assert d["stdev"] == 0.0
    assert d["hi"] > d["lo"]
    assert sum(d["bins"]) == 3


def test_get_desk_metric_dists_shape_from_db():
    """Integration smoke: real SQLite if present; else skip gracefully."""
    from pathlib import Path

    from live.metric_dist import get_desk_metric_dists

    db = Path(__file__).resolve().parent.parent / "data" / "replay_m15.db"
    if not db.is_file():
        return

    invalidate_desk_metric_dists()
    payload = get_desk_metric_dists(force=True)
    assert "ok" in payload
    assert "z" in payload
    assert "spread" in payload
    assert payload.get("lookback_days") == 1095
    if payload.get("ok"):
        assert payload["n"] >= 8000
        assert payload["z"]["n"] == payload["n"] or payload["z"]["n"] > 0
        assert isinstance(payload["z"]["bins"], list)
        assert len(payload["z"]["bins"]) == payload["z"]["binCount"]
        assert payload["build_ms"] is not None
        # Cache hit should be fast and identical key
        again = get_desk_metric_dists()
        assert again["last_bar"] == payload["last_bar"]
        assert again["n"] == payload["n"]
