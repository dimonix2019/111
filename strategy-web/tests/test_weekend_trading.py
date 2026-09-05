"""Test-only weekend trading session for replay tip1m."""
from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

import numpy as np

from replay import tip_touch
from replay.tip_touch import PreparedTips, run_touch_1m_trades, with_weekend_trading_session


def _weekend_prep() -> PreparedTips:
    dates = [
        "2026-08-01 09:59:00",  # Saturday, before the enabled window
        "2026-08-01 10:00:00",  # Short entry: 6.0 -> 6.3
        "2026-08-01 10:01:00",
        "2026-08-01 10:02:00",  # Short exit: 6.0 -> 5.7
        "2026-08-01 19:00:00",  # outside the enabled window
    ]
    msk = ZoneInfo("Europe/Moscow")
    ts_ms = np.asarray(
        [
            int(datetime.strptime(x, "%Y-%m-%d %H:%M:%S").replace(tzinfo=msk).timestamp() * 1000)
            for x in dates
        ],
        dtype=np.int64,
    )
    return PreparedTips(
        ts_ms=ts_ms,
        z=np.zeros(len(dates), dtype=np.float64),
        spread=np.asarray([6.0, 6.3, 6.0, 5.7, 5.6], dtype=np.float64),
        day_ord=np.full(len(dates), 20_306, dtype=np.int32),
        session=np.zeros(len(dates), dtype=np.bool_),
        trade_dates=dates,
        edge_i=np.empty(0, dtype=np.int32),
        n=len(dates),
    )


def test_weekend_false_excludes_edges_and_true_enables_trade(monkeypatch):
    prep = _weekend_prep()
    unchanged = with_weekend_trading_session(prep, enabled=False)
    assert unchanged is prep
    assert unchanged.edge_i.tolist() == []

    enabled = with_weekend_trading_session(prep, enabled=True)
    assert enabled.session.tolist() == [False, True, True, True, False]
    assert enabled.edge_i.tolist() == [1, 2, 3]

    monkeypatch.setattr(tip_touch, "_prod_fill_maps", lambda: {})
    result = run_touch_1m_trades(
        enabled,
        entry=1.6,
        exit_z=1.3,
        spread_level_mode=True,
        spread_levels={
            "enter_wide": 6.2,
            "exit_wide": 5.8,
            "enter_narrow": 3.2,
            "exit_narrow": 4.0,
        },
        now_ms=int(enabled.ts_ms[-1] + 60_000),
        settle_sec=0,
    )
    assert result["summary"]["trades"] == 1
    trade = result["trades"][0]
    assert trade["entryDate"].startswith("2026-08-01 10:00")
    assert trade["exitDate"].startswith("2026-08-01 10:02")


def test_weekend_flag_separates_sim_cache_keys(monkeypatch):
    prep = _weekend_prep()
    monkeypatch.setattr(tip_touch, "ensure_tip_series", lambda _csv, **_k: (prep, {"mode": "tip1m"}))
    monkeypatch.setattr(tip_touch, "_prod_fill_maps", lambda: {})
    with tip_touch._lock:
        tip_touch._tip_cache["key"] = "weekend-test-series"
        tip_touch._sim_cache.clear()

    common = dict(
        csv="m15_tatn_255d.csv",
        entry=1.6,
        exit_z=1.3,
        spread_level_mode=True,
        spread_levels={
            "enter_wide": 6.2,
            "exit_wide": 5.8,
            "enter_narrow": 3.2,
            "exit_narrow": 4.0,
        },
    )
    baseline = tip_touch.sim_tip1m(**common, weekend_trading=False)
    weekend = tip_touch.sim_tip1m(**common, weekend_trading=True)

    assert baseline["summary"]["trades"] == 0
    assert baseline["params"]["weekendTrading"] is False
    assert weekend["summary"]["trades"] == 1
    assert weekend["params"]["weekendTrading"] is True
    assert weekend["meta"]["weekendWindowMsk"] == "10:00–18:59"
    with tip_touch._lock:
        keys = list(tip_touch._sim_cache)
    assert len(keys) == 2
    assert any("|we=0|" in key for key in keys)
    assert any("|we=1|" in key for key in keys)


def test_tip1m_api_parses_weekend_flag_and_defaults_off(monkeypatch):
    from replay import replay_app

    calls: list[dict] = []

    def _fake_sim(**kwargs):
        calls.append(kwargs)
        return {"trades": [], "summary": {}, "params": {}, "meta": {}}

    monkeypatch.setattr(tip_touch, "sim_tip1m", _fake_sim)
    replay_app.api_sim_tip1m({"csv": "m15_tatn_255d.csv"})
    replay_app.api_sim_tip1m(
        {"csv": "m15_tatn_255d.csv", "weekend_trading": "true"}
    )

    assert calls[0]["weekend_trading"] is False
    assert calls[1]["weekend_trading"] is True
