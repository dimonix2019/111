"""Broker reconcile: ghost-close, weekend keep, MANUAL adopt."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

from live import engine, store


def _cred_db(tmp_path, monkeypatch, name: str):
    db = tmp_path / name
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)
    store.set_setting("token_prod", "t.dummy")
    store.set_setting("account_prod", "acc")
    store.set_setting("execution_mode", "prod")
    engine._broker_flat_streak = 0
    engine._last_reconcile_warn_ms = 0


def _insert_local_long():
    store.insert_open_trade(
        {
            "mode": "prod",
            "account_id": "acc",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": 10,
            "entry_time": "2026-07-21 15:45",
            "entry_z": -1.5,
            "entry_spread": 4.0,
            "entry_tatn": 100.0,
            "entry_tatnp": 96.0,
            "execution_notional_rub": None,
            "source": "BROKER",
            "legs": [],
        }
    )


def test_reconcile_closes_local_when_broker_flat(tmp_path, monkeypatch):
    _cred_db(tmp_path, monkeypatch, "live_reconcile.db")
    _insert_local_long()
    assert store.get_open_trade() is not None

    fake_client = MagicMock()
    fake_client.detect_spread_position.return_value = None

    market = {
        "trade_date": "2026-07-21 17:15",
        "z": -1.0,
        "spread": 4.5,
        "tatn": 1.0,
        "tatnp": 1.0,
    }

    with patch.object(engine, "TInvestClient", return_value=fake_client):
        with patch("live.dealer_quotes.want_dealer_quotes", return_value=False):
            for _ in range(engine._BROKER_FLAT_CLOSE_N - 1):
                out = engine.reconcile_broker_open_trade(portfolio={}, market=market)
                assert out.get("closed_local") is False
                assert store.get_open_trade() is not None
            out = engine.reconcile_broker_open_trade(portfolio={}, market=market)

    assert out.get("ok") is True
    assert out.get("closed_local") is True
    assert store.get_open_trade() is None


def test_reconcile_keeps_local_when_weekend_broker_empty(tmp_path, monkeypatch):
    _cred_db(tmp_path, monkeypatch, "live_weekend_keep.db")
    _insert_local_long()

    fake_client = MagicMock()
    fake_client.detect_spread_position.return_value = None
    market = {"trade_date": "2026-09-05 19:00", "z": 0.7, "spread": 3.3}

    with patch.object(engine, "TInvestClient", return_value=fake_client):
        with patch("live.dealer_quotes.want_dealer_quotes", return_value=True):
            for _ in range(5):
                out = engine.reconcile_broker_open_trade(portfolio={}, market=market)

    assert out.get("ok") is True
    assert out.get("closed_local") is False
    assert out.get("deferred_flat") is True
    assert store.get_open_trade() is not None


def test_reconcile_adopts_broker_position_when_local_empty(tmp_path, monkeypatch):
    """На брокере есть спред, локально пусто — подхват MANUAL (видно на столе)."""
    _cred_db(tmp_path, monkeypatch, "live_adopt.db")

    fake_client = MagicMock()
    fake_client.detect_spread_position.return_value = {
        "direction": "LONG",
        "entry_signal": "ENTER_LONG",
        "quantity_lots": 80,
        "qty_tatn": 80.0,
        "qty_tatnp": -80.0,
    }

    market = {
        "trade_date": "2026-09-05 14:30",
        "tip_trade_date": "2026-09-05 14:30",
        "z": 0.9,
        "spread": 3.34,
        "tatn": 700.0,
        "tatnp": 677.0,
    }

    with patch.object(engine, "TInvestClient", return_value=fake_client):
        out = engine.reconcile_broker_open_trade(portfolio={}, market=market)

    assert out.get("adopted") is True
    open_t = store.get_open_trade()
    assert open_t is not None
    assert open_t["source"] == "MANUAL"
    assert open_t["direction"] == "LONG"
    assert int(open_t["quantity_lots"]) == 80
