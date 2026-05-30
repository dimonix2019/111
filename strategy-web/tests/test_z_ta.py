"""Unit tests for Z-score TA precursors."""

import numpy as np

from api.z_ta import entry_threshold_distance, idle_duration_forecast, idle_risk_score, z_ta_features


def test_entry_threshold_distance_in_neutral():
    assert abs(entry_threshold_distance(0.0, 0.8) - 0.8) < 1e-9
    assert abs(entry_threshold_distance(0.5, 0.8) - 0.3) < 1e-9


def test_neutral_streak_detected():
    z = np.concatenate([np.linspace(2.0, 0.2, 40), np.full(60, 0.1)])
    feat = z_ta_features(z, entry=0.8, exit_z=0.7)
    assert feat["neutral_streak_bars"] >= 59
    assert feat["in_neutral"] == 1.0


def test_idle_duration_forecast_remaining():
    gaps = [{"days": d} for d in (2, 5, 8, 12, 20)]
    fc = idle_duration_forecast(gaps, current_idle_days=5, in_position=False, risk_score=50)
    assert fc["applicable"] is True
    assert fc["median_remaining_days"] is not None
    assert fc["median_total_days"] >= 5


def test_idle_risk_score_bounds():
    z = np.concatenate([np.linspace(2.0, 0.2, 40), np.full(60, 0.1)])
    feat = z_ta_features(z, entry=0.8, exit_z=0.7)
    risk, signs = idle_risk_score(feat, {}, entry=0.8, idle_days=10)
    assert 0 <= risk <= 100
    assert len(signs) >= 5
    assert any(s["id"] == "neutral_streak" and s["active"] for s in signs)
