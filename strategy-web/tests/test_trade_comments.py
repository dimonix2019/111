"""Комментарии закрытых сделок Прода: MANUAL / подхват / AUTO_TP / сверка ghost."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

from live import engine, store
from live.trade_comments import (
    auto_entry_comment,
    close_comment_for_source,
    entry_comment_for_open,
    infer_missing_closed_comments,
    known_id_comment_patch,
    manual_entry_comment,
)


def test_manual_and_adopt_entry_text():
    assert manual_entry_comment(direction="LONG", quantity_lots=80) == "MANUAL LONG 80+80"
    assert (
        entry_comment_for_open(
            source="MANUAL", direction="LONG", quantity_lots=80, adopted=True
        )
        == "подхват MANUAL LONG 80+80"
    )


def test_auto_entry_uses_long_levels_and_tp():
    text = auto_entry_comment(
        direction="LONG",
        settings={
            "spread_enter_narrow": 3.2,
            "spread_exit_narrow": 4.0,
            "take_profit_pct": 2.0,
        },
    )
    assert text.startswith("AUTO")
    assert "L 3.2→4" in text
    assert "ТП 2%" in text


def test_close_comment_ghost_and_auto_tp_weekend():
    from datetime import datetime

    from live.dealer_quotes import MSK

    assert close_comment_for_source("RECONCILE", ghost=True) == "сверка ghost"
    assert (
        close_comment_for_source(
            "AUTO_TP", signal_bar={"tradeDate": "2026-09-05 20:24"}
        )
        == "AUTO_TP вне сессии"
    )
    sat_mid = datetime(2026, 9, 5, 12, 0, tzinfo=MSK)
    with patch("live.dealer_quotes.now_msk", return_value=sat_mid):
        assert (
            close_comment_for_source(
                "AUTO_TP",
                settings={"take_profit_pct": 2.0},
                signal_bar={"tradeDate": "2026-09-05 12:00"},
            )
            == "ТП 2%"
        )


def test_infer_ghost_row_fills_only_empty():
    row = {
        "id": 99,
        "direction": "LONG",
        "quantity_lots": 80,
        "source": "MANUAL",
        "legs": [{"note": "broker flat — local open cleared"}],
        "entry_comment": None,
        "close_comment": None,
    }
    patch = infer_missing_closed_comments(row)
    assert patch["close_comment"] == "сверка ghost"
    assert patch["entry_comment"] == "MANUAL LONG 80+80"
    row["close_comment"] = "уже было"
    assert "close_comment" not in infer_missing_closed_comments(row)


def test_known_ids_20_21():
    p20 = known_id_comment_patch(
        {"id": 20, "direction": "LONG", "quantity_lots": 80}
    )
    assert p20["close_comment"] == "сверка ghost"
    assert p20["entry_comment"] == "MANUAL LONG 80+80"
    p21 = known_id_comment_patch(
        {"id": 21, "direction": "LONG", "quantity_lots": 80}
    )
    assert p21["close_comment"] == "AUTO_TP вне сессии"
    assert "подхват" in p21["entry_comment"]


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


def test_reconcile_ghost_writes_close_comment(tmp_path, monkeypatch):
    _cred_db(tmp_path, monkeypatch, "comments_ghost.db")
    store.insert_open_trade(
        {
            "mode": "prod",
            "account_id": "acc",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": 80,
            "entry_time": "2026-09-05 14:30",
            "entry_z": 0.9,
            "entry_spread": 3.3,
            "entry_tatn": 100.0,
            "entry_tatnp": 96.0,
            "execution_notional_rub": None,
            "source": "MANUAL",
            "legs": [],
        }
    )
    fake_client = MagicMock()
    fake_client.detect_spread_position.return_value = None
    market = {"trade_date": "2026-09-05 14:35", "z": 0.8, "spread": 3.4}
    with patch.object(engine, "TInvestClient", return_value=fake_client):
        with patch("live.dealer_quotes.want_dealer_quotes", return_value=False):
            for _ in range(engine._BROKER_FLAT_CLOSE_N):
                engine.reconcile_broker_open_trade(portfolio={}, market=market)
    closed = store.get_closed_trades(1)[0]
    assert closed.get("close_comment") == "сверка ghost"
    assert closed.get("entry_comment") == "MANUAL LONG 80+80"


def test_reconcile_adopt_writes_entry_comment(tmp_path, monkeypatch):
    _cred_db(tmp_path, monkeypatch, "comments_adopt.db")
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
        "z": 0.9,
        "spread": 3.34,
        "tatn": 700.0,
        "tatnp": 677.0,
    }
    with patch.object(engine, "TInvestClient", return_value=fake_client):
        engine.reconcile_broker_open_trade(portfolio={}, market=market)
    open_t = store.get_open_trade()
    assert open_t is not None
    assert open_t.get("entry_comment") == "подхват MANUAL LONG 80+80"


def test_close_open_trade_copies_entry_and_close_comment(tmp_path, monkeypatch):
    _cred_db(tmp_path, monkeypatch, "comments_close.db")
    store.insert_open_trade(
        {
            "mode": "prod",
            "account_id": "acc",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": 80,
            "entry_time": "2026-09-04 10:00",
            "entry_z": 1.0,
            "entry_spread": 3.2,
            "entry_tatn": 100.0,
            "entry_tatnp": 96.0,
            "execution_notional_rub": None,
            "source": "AUTO",
            "legs": [],
            "entry_comment": "AUTO · L 3.2→4 · ТП 2%",
        }
    )
    store.close_open_trade(
        exit_time="2026-09-04 11:00",
        exit_z=0.5,
        exit_spread=3.5,
        pnl_rub=10.0,
        legs=[],
        metrics={"close_comment": "ТП 2%"},
    )
    closed = store.get_closed_trades(1)[0]
    assert closed.get("entry_comment") == "AUTO · L 3.2→4 · ТП 2%"
    assert closed.get("close_comment") == "ТП 2%"
