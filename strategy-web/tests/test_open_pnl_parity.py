"""Open PnL parity: Prod fills vs Test spread after entry alignment."""

from __future__ import annotations

import json

from live import store
from live.open_mark import enrich_open_trade
from live.parity import check_open_pnl_parity, ensure_open_entry_from_fills


def test_ensure_open_entry_rewrites_iss_spread(tmp_path, monkeypatch):
    db = tmp_path / "pnl_parity.db"
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)
    legs = [
        {
            "ticker": "TATN",
            "order": {"executedOrderPrice": {"units": "457", "nano": 600000000}},
        },
        {
            "ticker": "TATNP",
            "order": {"executedOrderPrice": {"units": "438", "nano": 400000000}},
        },
    ]
    tid = store.insert_open_trade(
        {
            "mode": "prod",
            "account_id": "x",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": 80,
            "entry_time": "2026-07-22 07:15",
            "entry_z": -1.65,
            "entry_spread": 3.94,
            "entry_tatn": 445.4,
            "entry_tatnp": 428.5,
            "execution_notional_rub": 69912,
            "source": "AUTO",
            "legs": legs,
        }
    )
    open_t = store.get_open_trade()
    assert open_t is not None
    fixed = ensure_open_entry_from_fills(open_t)
    assert fixed["id"] == tid
    assert abs(float(fixed["entry_spread"]) - 4.380) < 0.01
    assert float(fixed["entry_tatn"]) == 457.6


def test_check_open_pnl_matched_after_fill_entry(tmp_path, monkeypatch):
    db = tmp_path / "pnl_parity2.db"
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)
    legs = [
        {
            "ticker": "TATN",
            "order": {"executedOrderPrice": {"units": "457", "nano": 600000000}},
        },
        {
            "ticker": "TATNP",
            "order": {"executedOrderPrice": {"units": "438", "nano": 400000000}},
        },
    ]
    store.insert_open_trade(
        {
            "mode": "prod",
            "account_id": "x",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": 80,
            "entry_time": "2026-07-22 07:15",
            "entry_z": -1.65,
            "entry_spread": 3.94,
            "entry_tatn": 445.4,
            "entry_tatnp": 428.5,
            "execution_notional_rub": 69912,
            "source": "AUTO",
            "legs": legs,
        }
    )
    market = {
        "z": -1.45,
        "spread": 4.1486,
        "tatn": 456.9,
        "tatnp": 438.7,
        "trade_date": "2026-07-22 07:45",
    }
    out = check_open_pnl_parity(market=market)
    assert out["kind"] == "open_pnl"
    assert out["ok"] is True
    assert out["status"] == "matched"
    assert abs(out["prod_pnl_rub"] - out["test_pnl_rub"]) < out["tol_rub"]
    assert out["spread_approx_pnl_rub"] is not None  # диагностика спред×номинал
