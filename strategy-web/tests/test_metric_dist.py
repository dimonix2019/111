"""Unit tests for Trade desk Z/spread distribution builder."""

from __future__ import annotations

import math

from live.metric_dist import compute_numeric_distribution, invalidate_desk_metric_dists


def test_compute_numeric_distribution_basic():
    values = [1.0, 2.0, 3.0, 4.0, 5.0]
    d = compute_numeric_distribution(values)
    assert d is not None
    assert d["n"] == 5
    assert d["nHist"] == 5
    assert d["histClipped"] is False
    assert d["min"] == 1.0
    assert d["max"] == 5.0
    assert abs(d["mean"] - 3.0) < 1e-9
    assert abs(d["stdev"] - math.sqrt(2.5)) < 1e-9
    assert abs(d["median"] - 3.0) < 1e-9
    assert abs(d["p25"] - 2.0) < 1e-9
    assert abs(d["p75"] - 4.0) < 1e-9
    assert d["mad"] == 1.0
    assert d["binCount"] == len(d["bins"])
    assert sum(d["bins"]) == 5
    assert "sorted" not in d


def test_compute_numeric_distribution_skewed_robust():
    # Right-skewed: peak near 3, long tail — mean≠median; hist drops extremes.
    values = [2.0, 3.0, 3.0, 3.0, 4.0, 5.0, 56.0, 4000.0]
    d = compute_numeric_distribution(values)
    assert d is not None
    assert d["median"] == 3.5
    assert d["mean"] > d["median"]
    assert d["p25"] <= d["median"] <= d["p75"]
    assert d["mad"] >= 0.0
    assert d["p95"] > d["p75"]
    assert d["n"] == 8
    assert d["histClipped"] is True
    assert d["nHist"] < d["n"]
    assert sum(d["bins"]) == d["nHist"]
    assert d["histMax"] < d["max"]
    assert d["hi"] < d["max"]


def test_compute_numeric_distribution_empty():
    assert compute_numeric_distribution([]) is None


def test_compute_numeric_distribution_constant():
    d = compute_numeric_distribution([2.5, 2.5, 2.5])
    assert d is not None
    assert d["n"] == 3
    assert d["nHist"] == 3
    assert d["stdev"] == 0.0
    assert d["hi"] > d["lo"]
    assert sum(d["bins"]) == 3
    assert d["histClipped"] is False


def test_compute_numeric_distribution_duration_like_outliers():
    # Mass near ~2h, a few multi-day holds — hist must not span 63 days.
    core = [60.0 + i * 15.0 for i in range(40)] + [120.0 + i * 20.0 for i in range(40)]
    outliers = [60.0 * 24 * 10, 60.0 * 24 * 30, 60.0 * 24 * 63]
    values = core + outliers
    d = compute_numeric_distribution(values)
    assert d is not None
    assert d["n"] == len(values)
    assert d["histClipped"] is True
    assert d["nHist"] < d["n"]
    assert d["histMax"] < 60.0 * 24 * 5  # well below multi-week tail
    assert sum(d["bins"]) == d["nHist"]
    # Full-sample summary still sees the mean pull from outliers.
    assert d["mean"] > d["median"]


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
