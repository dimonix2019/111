"""GET /api/live/strategy-config — spread thresholds from live/constants.py."""

from __future__ import annotations

from fastapi.testclient import TestClient

from live.constants import (
    DEFAULT_SPREAD_ENTER_NARROW,
    DEFAULT_SPREAD_ENTER_WIDE,
    DEFAULT_SPREAD_EXIT_NARROW,
    DEFAULT_SPREAD_EXIT_WIDE,
)
from live.spread_regime import SPREAD_WIDTH_NARROW_MAX, SPREAD_WIDTH_WIDE_MIN
from live.strategy_config import STRATEGY_CONFIG_VERSION, get_strategy_config
from replay.replay_app import app


def test_get_strategy_config_payload():
    cfg = get_strategy_config()
    assert cfg["version"] == STRATEGY_CONFIG_VERSION
    sp = cfg["spread"]
    assert sp["enter_wide"] == DEFAULT_SPREAD_ENTER_WIDE
    assert sp["exit_wide"] == DEFAULT_SPREAD_EXIT_WIDE
    assert sp["enter_narrow"] == DEFAULT_SPREAD_ENTER_NARROW
    assert sp["exit_narrow"] == DEFAULT_SPREAD_EXIT_NARROW
    assert sp["regime_narrow_max"] == SPREAD_WIDTH_NARROW_MAX
    assert sp["regime_wide_min"] == SPREAD_WIDTH_WIDE_MIN
    assert cfg["tp_pct"] == 2.0


def test_strategy_config_api_matches_constants():
    expected = get_strategy_config()
    with TestClient(app) as client:
        r = client.get("/api/live/strategy-config")
    assert r.status_code == 200
    assert r.json() == expected


def test_strategy_config_spread_matches_signal_contract_thresholds():
    """Same numbers as tests/fixtures/signal_contract_bars.json thresholds."""
    cfg = get_strategy_config()
    sp = cfg["spread"]
    assert sp["enter_wide"] == 6.1
    assert sp["exit_wide"] == 5.8
    assert sp["enter_narrow"] == 3.2
    assert sp["exit_narrow"] == 4.0
    assert sp["regime_narrow_max"] == 3.5
    assert sp["regime_wide_min"] == 5.5
