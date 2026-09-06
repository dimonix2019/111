"""Guard: spread exit partial fill + retry logic."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from live import engine, store
from live.signals import Position
from live.partial_close import (
    close_would_reduce_pair_profit,
    partial_fill_filled_lots,
    retry_partial_spread_exit,
)
from live.tinvest import order_fully_filled, spread_legs_fill_issues


def _partial_exit_legs() -> list[dict]:
    return [
        {
            "ticker": "TATN",
            "side": "sell",
            "order": {
                "executionReportStatus": "EXECUTION_REPORT_STATUS_PARTIALLYFILL",
                "lotsExecuted": "35",
                "lotsRequested": "360",
            },
        },
        {
            "ticker": "TATNP",
            "side": "buy",
            "order": {
                "executionReportStatus": "EXECUTION_REPORT_STATUS_FILL",
                "lotsExecuted": "360",
                "lotsRequested": "360",
            },
        },
    ]


def _full_exit_legs() -> list[dict]:
    return [
        {
            "ticker": "TATN",
            "side": "sell",
            "order": {
                "executionReportStatus": "EXECUTION_REPORT_STATUS_FILL",
                "lotsExecuted": "360",
                "lotsRequested": "360",
            },
        },
        {
            "ticker": "TATNP",
            "side": "buy",
            "order": {
                "executionReportStatus": "EXECUTION_REPORT_STATUS_FILL",
                "lotsExecuted": "360",
                "lotsRequested": "360",
            },
        },
    ]


def test_order_fully_filled_rejects_partial():
    order = {
        "executionReportStatus": "EXECUTION_REPORT_STATUS_PARTIALLYFILL",
        "lotsExecuted": "35",
        "lotsRequested": "360",
    }
    assert order_fully_filled(order, 360) is False


def test_order_fully_filled_accepts_fill():
    order = {
        "executionReportStatus": "EXECUTION_REPORT_STATUS_FILL",
        "lotsExecuted": "360",
        "lotsRequested": "360",
    }
    assert order_fully_filled(order, 360) is True


def test_spread_legs_fill_issues_detects_partial_leg():
    issues = spread_legs_fill_issues(_partial_exit_legs(), 360)
    assert len(issues) == 1
    assert "TATN" in issues[0]
    assert "35/360" in issues[0]


def test_partial_fill_filled_lots_min_leg():
    assert partial_fill_filled_lots(_partial_exit_legs(), 360) == 35


def test_close_would_reduce_pair_profit_long():
    open_t = {"direction": "LONG", "entry_spread": 3.5}
    assert close_would_reduce_pair_profit(open_t, 3.0) is True
    assert close_would_reduce_pair_profit(open_t, 3.6) is False


def test_close_would_reduce_pair_profit_short():
    open_t = {"direction": "SHORT", "entry_spread": 3.5}
    assert close_would_reduce_pair_profit(open_t, 4.0) is True
    assert close_would_reduce_pair_profit(open_t, 3.0) is False


def test_retry_partial_succeeds_on_second_attempt(tmp_path, monkeypatch):
    db = tmp_path / "partial_retry.db"
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)
    store.insert_open_trade(
        {
            "mode": "prod",
            "account_id": "acc",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": 360,
            "entry_time": "2026-09-06 18:00",
            "entry_z": 1.5,
            "entry_spread": 3.5,
            "source": "MANUAL",
            "legs": [],
        }
    )
    open_t = store.get_open_trade()
    client = MagicMock()
    client.get_portfolio.return_value = {}
    client.detect_spread_position.side_effect = [
        {"direction": "LONG", "quantity_lots": 325, "entry_signal": "ENTER_LONG"},
        None,
    ]
    client.execute_spread_exit.return_value = _full_exit_legs()

    legs, ok = retry_partial_spread_exit(
        client,
        "acc",
        open_t,
        _partial_exit_legs(),
        source="MANUAL",
        market_snapshot_fn=lambda: {"spread": 4.0},
        sleep_fn=lambda _s: None,
    )
    assert ok is True
    assert not spread_legs_fill_issues(legs, 360)
    refreshed = store.get_open_trade()
    assert refreshed.get("partial_close_status") in (None, "")


def test_retry_partial_force_on_sixth_when_profit_bad(tmp_path, monkeypatch):
    db = tmp_path / "partial_force.db"
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)
    store.insert_open_trade(
        {
            "mode": "prod",
            "account_id": "acc",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": 360,
            "entry_time": "2026-09-06 18:00",
            "entry_spread": 3.5,
            "source": "MANUAL",
            "legs": [],
        }
    )
    open_t = store.get_open_trade()
    client = MagicMock()
    client.get_portfolio.return_value = {}
    # Always partial on broker until force attempt returns full legs
    client.detect_spread_position.return_value = {
        "direction": "LONG",
        "quantity_lots": 325,
        "entry_signal": "ENTER_LONG",
    }
    call_n = {"n": 0}

    def _exit(*_a, **_k):
        call_n["n"] += 1
        return _full_exit_legs()

    client.execute_spread_exit.side_effect = _exit

    legs, ok = retry_partial_spread_exit(
        client,
        "acc",
        open_t,
        _partial_exit_legs(),
        source="MANUAL",
        market_snapshot_fn=lambda: {"spread": 3.0},  # bad for LONG — skip retries 1-5
        sleep_fn=lambda _s: None,
    )
    assert ok is True
    assert call_n["n"] == 1


def test_close_position_aborts_on_partial_exit(tmp_path, monkeypatch):
    db = tmp_path / "fill_guard.db"
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
            "quantity_lots": 360,
            "entry_time": "2026-09-06 18:00",
            "entry_z": 1.5,
            "entry_spread": 3.5,
            "source": "AUTO",
            "legs": [],
        }
    )
    client = MagicMock()
    client.execute_spread_exit.return_value = _partial_exit_legs()
    client.get_portfolio.return_value = {}
    client.detect_spread_position.return_value = {
        "direction": "LONG",
        "quantity_lots": 325,
        "entry_signal": "ENTER_LONG",
    }

    with patch.object(engine, "TInvestClient", return_value=client):
        with patch.object(engine, "market_snapshot", return_value={"spread": 3.0}):
            with patch.object(engine, "retry_partial_spread_exit") as retry_mock:
                retry_mock.return_value = (_partial_exit_legs(), False)
                with patch.object(store, "close_open_trade") as close_mock:
                    with pytest.raises(RuntimeError, match="Неполное исполнение"):
                        engine.close_position(source="MANUAL")
                    close_mock.assert_not_called()
    open_t = store.get_open_trade()
    assert open_t is not None
    assert int(open_t["quantity_lots"]) == 360


def test_open_position_blocks_during_partial_close(tmp_path, monkeypatch):
    db = tmp_path / "partial_block.db"
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
            "quantity_lots": 360,
            "entry_time": "2026-09-06 18:00",
            "entry_spread": 3.5,
            "source": "MANUAL",
            "legs": [],
        }
    )
    tid = store.get_open_trade()["id"]
    store.update_open_trade_fields(
        int(tid),
        {"partial_close_status": "in_progress", "partial_close_attempts": 2},
    )
    with patch.object(engine, "TInvestClient"):
        with pytest.raises(RuntimeError, match="частичное закрытие"):
            engine.open_position(Position.LONG, source="MANUAL")
