"""MTLR Phase‑3: broker soft reconcile + take-profit on m15."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pandas as pd

from live import store
from live.mtlr_engine import (
    _maybe_mtlr_tp_exit,
    maybe_mtlr_auto_tick,
    reconcile_broker_mtlr_open_trade,
)
from live.signals import Position


def _iso_db(tmp_path, monkeypatch):
    db = tmp_path / "live_mtlr_phase3.db"
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)


def _insert_mtlr_long(tmp_path, monkeypatch):
    _iso_db(tmp_path, monkeypatch)
    store.set_setting("token_prod", "t.dummy")
    store.set_setting("account_prod", "acc")
    store.set_setting("execution_mode", "prod")
    store.insert_mtlr_open_trade(
        {
            "pair_id": "MTLR",
            "mode": "prod",
            "account_id": "acc",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": 1,
            "entry_time": "2026-08-16 10:00:00",
            "entry_spread": 3.0,
            "entry_ord": 40.0,
            "entry_pref": 38.8,
            "execution_notional_rub": 10000,
            "source": "MANUAL",
            "legs": [],
        }
    )


def test_reconcile_closes_local_mtlr_when_broker_flat(tmp_path, monkeypatch):
    _insert_mtlr_long(tmp_path, monkeypatch)
    assert store.get_mtlr_open_trade() is not None

    fake_client = MagicMock()
    fake_client.detect_spread_position.return_value = None
    fake_client.get_portfolio.return_value = {}

    with patch("live.mtlr_engine.TInvestClient", return_value=fake_client):
        with patch(
            "live.mtlr_engine._prices_from_frame_or_broker",
            return_value=(40.0, 38.5, {"bar": "2026-08-16 12:00:00", "source": "m15"}),
        ):
            out = reconcile_broker_mtlr_open_trade(portfolio={})

    assert out.get("ok") is True
    assert out.get("closed_local") is True
    assert store.get_mtlr_open_trade() is None
    fake_client.detect_spread_position.assert_called()
    kwargs = fake_client.detect_spread_position.call_args.kwargs
    assert kwargs.get("ord_ticker") == "MTLR"
    assert kwargs.get("pref_ticker") == "MTLRP"


def test_reconcile_adopts_broker_mtlr_without_local(tmp_path, monkeypatch):
    _iso_db(tmp_path, monkeypatch)
    store.set_setting("token_prod", "t.dummy")
    store.set_setting("account_prod", "acc")
    store.set_setting("execution_mode", "prod")

    fake_client = MagicMock()
    fake_client.detect_spread_position.return_value = {
        "direction": "SHORT",
        "entry_signal": "ENTER_SHORT",
        "quantity_lots": 2,
        "qty_ord": -2.0,
        "qty_pref": 2.0,
    }

    with patch("live.mtlr_engine.TInvestClient", return_value=fake_client):
        with patch(
            "live.mtlr_engine._prices_from_frame_or_broker",
            return_value=(40.0, 38.5, {"bar": "2026-08-16 12:00:00", "source": "m15"}),
        ):
            out = reconcile_broker_mtlr_open_trade(portfolio={})

    assert out.get("ok") is True
    assert out.get("adopted") is True
    open_t = store.get_mtlr_open_trade()
    assert open_t is not None
    assert open_t["direction"] == "SHORT"
    assert open_t["quantity_lots"] == 2
    assert open_t["source"] == "MANUAL"


def test_mtlr_tp_triggers_close(tmp_path, monkeypatch):
    _insert_mtlr_long(tmp_path, monkeypatch)
    store.set_setting("take_profit_pct", "1")
    store.set_setting("leverage", "7")
    store.set_setting("mtlr_deposit_rub", "10000")

    # Long + spread rose enough: tip1m MTM% = lev * (cur-entry) ≥ 1
    # 7 * (3.2 - 3.0) = 1.4 ≥ 1
    rows = [
        ("2026-08-16 10:00:00", 3.0),
        ("2026-08-16 10:15:00", 3.2),
    ]
    df = pd.DataFrame(rows, columns=["timestamp", "spread_percent"])

    closed = {"ok": True}

    def _fake_close(**kwargs):
        assert kwargs.get("source") == "AUTO_MTLR_TP"
        store.close_mtlr_open_trade(
            exit_time="2026-08-16 10:15:00",
            exit_z=None,
            exit_spread=3.2,
            pnl_rub=0.0,
            legs=[],
        )
        return closed

    monkeypatch.setattr("live.mtlr_engine.close_mtlr_position", _fake_close)
    msg, fired = _maybe_mtlr_tp_exit(
        settings=store.get_settings_bundle(),
        settled=df,
    )
    assert fired is True
    assert "TP" in msg
    assert store.get_mtlr_open_trade() is None


def test_mtlr_tp_skipped_when_tp_off(tmp_path, monkeypatch):
    _insert_mtlr_long(tmp_path, monkeypatch)
    store.set_setting("take_profit_pct", "0")
    rows = [("2026-08-16 10:00:00", 3.0), ("2026-08-16 10:15:00", 9.0)]
    df = pd.DataFrame(rows, columns=["timestamp", "spread_percent"])
    msg, fired = _maybe_mtlr_tp_exit(
        settings=store.get_settings_bundle(),
        settled=df,
    )
    assert fired is False
    assert msg == ""
    assert store.get_mtlr_open_trade() is not None


def test_auto_tick_tp_path(tmp_path, monkeypatch):
    _insert_mtlr_long(tmp_path, monkeypatch)
    store.set_setting("mtlr_enabled", "1")
    store.set_setting("mtlr_auto_execute", "1")
    store.set_setting("take_profit_pct", "1")
    store.set_setting("leverage", "7")
    store.set_setting("mtlr_deposit_rub", "10000")
    # Already caught up — TP still runs on latest settled bar.
    store.set_setting("mtlr_last_processed_bar_ms", "9999999999999")

    rows = [
        ("2026-08-16 10:00:00", 3.0, 40.0, 38.8),
        ("2026-08-16 10:15:00", 3.2, 40.5, 39.0),
    ]
    df = pd.DataFrame(
        rows, columns=["timestamp", "spread_percent", "ord_close", "pref_close"]
    )
    monkeypatch.setattr(
        "live.mtlr_engine.load_mtlr_m15_frame",
        lambda force_refresh=False: (df, {"rows": 2}),
    )
    monkeypatch.setattr(
        "live.mtlr_engine._drop_unsettled_tail",
        lambda d, now=None: d,
    )

    def _fake_close(**kwargs):
        store.close_mtlr_open_trade(
            exit_time="2026-08-16 10:15:00",
            exit_z=None,
            exit_spread=3.2,
            pnl_rub=0.0,
            legs=[],
        )
        return {"ok": True}

    monkeypatch.setattr("live.mtlr_engine.close_mtlr_position", _fake_close)
    out = maybe_mtlr_auto_tick(store.get_settings_bundle())
    assert out.get("acted") is True
    assert out.get("tp") is True
    assert store.get_mtlr_open_trade() is None


def test_manual_source_on_open_helpers():
    """Position enum path used by POST /api/live/mtlr/trade."""
    assert Position.LONG.value == "LONG"
    assert Position.SHORT.value == "SHORT"
