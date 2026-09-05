"""MTLR Phase‑2: position isolation, auto gate, basket limit."""

from __future__ import annotations

import pandas as pd

from live import store
from live.constants import BASKET_MAX_OPEN, DEFAULT_MTLR_AUTO_EXECUTE, DEFAULT_MTLR_DEPOSIT_RUB
from live.mtlr_engine import (
    basket_open_count,
    can_open_mtlr,
    current_mtlr_position,
    maybe_mtlr_auto_tick,
    mtlr_deposit_rub,
)
from live.mtlr_shadow import mtlr_auto_execute, mtlr_enabled
from live.signals import Position


def _iso_db(tmp_path, monkeypatch):
    db = tmp_path / "live_mtlr_phase2.db"
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)


def test_mtlr_auto_default_off():
    assert DEFAULT_MTLR_AUTO_EXECUTE is False
    assert mtlr_auto_execute({}) is False
    assert mtlr_auto_execute({"mtlr_auto_execute": False}) is False
    assert mtlr_auto_execute({"mtlr_auto_execute": True}) is True
    assert mtlr_auto_execute({"mtlr_auto_execute": "1"}) is True
    assert mtlr_auto_execute({"mtlr_auto_execute": "0"}) is False
    assert mtlr_enabled({"mtlr_enabled": "1"}) is True


def test_position_isolation_tatn_vs_mtlr(tmp_path, monkeypatch):
    _iso_db(tmp_path, monkeypatch)
    store.insert_open_trade(
        {
            "mode": "prod",
            "account_id": "a1",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": 2,
            "entry_time": "2026-08-16 10:00:00",
            "entry_z": -1.6,
            "entry_spread": 3.1,
            "entry_tatn": 700.0,
            "entry_tatnp": 680.0,
            "execution_notional_rub": 10000,
            "source": "AUTO",
            "legs": [],
        }
    )
    assert store.get_open_trade() is not None
    assert store.get_mtlr_open_trade() is None
    assert current_mtlr_position() == Position.FLAT

    mid = store.insert_mtlr_open_trade(
        {
            "pair_id": "MTLR",
            "mode": "prod",
            "account_id": "a1",
            "direction": "SHORT",
            "entry_signal": "ENTER_SHORT",
            "quantity_lots": 1,
            "entry_time": "2026-08-16 11:00:00",
            "entry_spread": 9.0,
            "entry_ord": 150.0,
            "entry_pref": 138.0,
            "execution_notional_rub": 12000,
            "source": "AUTO_MTLR",
            "legs": [],
        }
    )
    assert mid >= 1
    tatn = store.get_open_trade()
    mtlr = store.get_mtlr_open_trade()
    assert tatn is not None and tatn["direction"] == "LONG"
    assert mtlr is not None and mtlr["direction"] == "SHORT"
    # Separate tables may both start at id=1 — isolation is by table, not id.
    assert "entry_ord" in mtlr or mtlr.get("entry_tatn") is not None
    assert current_mtlr_position() == Position.SHORT
    assert basket_open_count() == 2
    # Closing Mechel must not clear TATN.
    store.close_mtlr_open_trade(
        exit_time="2026-08-16 12:00:00",
        exit_z=None,
        exit_spread=8.5,
        pnl_rub=10.0,
        legs=[],
    )
    assert store.get_mtlr_open_trade() is None
    assert store.get_open_trade() is not None
    assert store.get_open_trade()["direction"] == "LONG"


def test_basket_limit_blocks_third(tmp_path, monkeypatch):
    _iso_db(tmp_path, monkeypatch)
    # Empty → can open
    ok, _ = can_open_mtlr()
    assert ok is True
    assert BASKET_MAX_OPEN == 2

    store.insert_open_trade(
        {
            "mode": "prod",
            "account_id": "a1",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": 1,
            "entry_time": "2026-08-16 10:00:00",
            "source": "AUTO",
            "legs": [],
        }
    )
    # TATN open alone → still room for Mechel (1 of 2)
    ok, _ = can_open_mtlr()
    assert ok is True

    store.insert_mtlr_open_trade(
        {
            "mode": "prod",
            "account_id": "a1",
            "direction": "SHORT",
            "entry_signal": "ENTER_SHORT",
            "quantity_lots": 1,
            "entry_time": "2026-08-16 11:00:00",
            "source": "AUTO_MTLR",
            "legs": [],
        }
    )
    ok, reason = can_open_mtlr()
    assert ok is False
    assert "Мечел" in reason or "корзина" in reason
    assert basket_open_count() == 2


def test_auto_tick_skips_when_flag_off(tmp_path, monkeypatch):
    _iso_db(tmp_path, monkeypatch)
    store.set_setting("mtlr_enabled", "1")
    store.set_setting("mtlr_auto_execute", "0")
    out = maybe_mtlr_auto_tick(store.get_settings_bundle())
    assert out.get("skipped") == "auto_off"
    assert out.get("acted") is False


def test_auto_tick_seeds_without_orders(tmp_path, monkeypatch):
    _iso_db(tmp_path, monkeypatch)
    store.set_setting("mtlr_enabled", "1")
    store.set_setting("mtlr_auto_execute", "1")
    store.set_setting("mtlr_last_processed_bar_ms", "0")

    # Fake settled frame with 2 bars — no broker calls if we only seed.
    rows = [
        ("2026-01-01 10:00:00", 5.0, 100.0, 95.0),
        ("2026-01-01 10:15:00", 5.1, 101.0, 96.0),
    ]
    df = pd.DataFrame(rows, columns=["timestamp", "spread_percent", "ord_close", "pref_close"])

    monkeypatch.setattr(
        "live.mtlr_engine.load_mtlr_m15_frame",
        lambda force_refresh=False: (df, {"rows": 2}),
    )
    monkeypatch.setattr(
        "live.mtlr_engine._drop_unsettled_tail",
        lambda d, now=None: d,
    )

    out = maybe_mtlr_auto_tick(store.get_settings_bundle())
    assert out.get("skipped") == "seeded"
    assert out.get("acted") is False
    assert store.get_mtlr_open_trade() is None
    assert int(store.get_setting("mtlr_last_processed_bar_ms", "0") or 0) > 0


def test_mtlr_deposit_default():
    assert DEFAULT_MTLR_DEPOSIT_RUB == 12_000.0
    assert mtlr_deposit_rub({"mtlr_deposit_rub": 15_000}) == 15_000.0
    assert mtlr_deposit_rub({}) == 12_000.0
