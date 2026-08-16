"""Broker reconcile: clear local ghost when portfolio has no spread."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

from live import engine, store


def test_reconcile_closes_local_when_broker_flat(tmp_path, monkeypatch):
    db = tmp_path / "live_reconcile.db"
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)

    store.set_setting("token_prod", "t.dummy")
    store.set_setting("account_prod", "acc")
    store.set_setting("execution_mode", "prod")

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
        out = engine.reconcile_broker_open_trade(portfolio={}, market=market)

    assert out.get("ok") is True
    assert out.get("closed_local") is True
    assert store.get_open_trade() is None


def test_reconcile_skips_broker_position_without_local(tmp_path, monkeypatch):
    """На брокере есть спред, локально пусто — не создаём open (без подхвата)."""
    db = tmp_path / "live_adopt.db"
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)

    store.set_setting("token_prod", "t.dummy")
    store.set_setting("account_prod", "acc")
    store.set_setting("execution_mode", "prod")

    fake_client = MagicMock()
    fake_client.detect_spread_position.return_value = {
        "direction": "LONG",
        "entry_signal": "ENTER_LONG",
        "quantity_lots": 75,
        "qty_tatn": 75.0,
        "qty_tatnp": -75.0,
    }

    market = {
        "trade_date": "2026-07-29 15:00",
        "tip_trade_date": "2026-07-29 14:47",
        "z": -1.5,
        "spread": 4.0,
    }

    with patch.object(engine, "TInvestClient", return_value=fake_client):
        out = engine.reconcile_broker_open_trade(portfolio={}, market=market)

    assert out.get("adopted") is False
    assert out.get("skipped_adopt") is True
    assert store.get_open_trade() is None
