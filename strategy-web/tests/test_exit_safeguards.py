"""Exit guards: slippage, broker flat reconcile, AUTO disable on failure."""

from __future__ import annotations

from datetime import datetime
from unittest.mock import MagicMock, patch

import pytest

from live import engine, store
from live.dealer_quotes import MSK
from live.open_mark import adverse_exit_slip_pts


def _msk(stamp: str) -> datetime:
    return datetime.strptime(stamp, "%Y-%m-%d %H:%M").replace(tzinfo=MSK)


def _full_exit_legs(*, tatn_px: float = 600.0, tatnp_px: float = 580.0) -> list[dict]:
    def _leg(ticker: str, px: float, side: str) -> dict:
        return {
            "ticker": ticker,
            "side": side,
            "order": {
                "executionReportStatus": "EXECUTION_REPORT_STATUS_FILL",
                "lotsExecuted": "360",
                "lotsRequested": "360",
                "executedOrderPrice": {"units": str(int(px)), "nano": 0},
            },
        }

    return [_leg("TATN", tatn_px, "sell"), _leg("TATNP", tatnp_px, "buy")]


def _cred_db(tmp_path, monkeypatch, name: str):
    db = tmp_path / name
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)
    store.set_setting("token_prod", "t.dummy")
    store.set_setting("account_prod", "acc")
    store.set_setting("execution_mode", "prod")
    store.set_setting("auto_execute", "1")


def test_adverse_exit_slip_long():
    # Signal 3.50%, fill 3.20% → adverse 0.30 pp for LONG exit
    slip = adverse_exit_slip_pts("LONG", 3.50, 3.20)
    assert slip is not None
    assert abs(slip - 0.30) < 1e-6


def test_close_position_aborts_on_exit_slippage(tmp_path, monkeypatch):
    _cred_db(tmp_path, monkeypatch, "slip_guard.db")
    store.insert_open_trade(
        {
            "mode": "prod",
            "account_id": "acc",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": 360,
            "entry_time": "2026-09-04 18:00",
            "entry_z": 1.5,
            "entry_spread": 3.13,
            "source": "AUTO",
            "legs": [],
        }
    )
    # Fill spread ≈ 2.07% vs signal 3.50% → slip ~1.43 pp > 0.20
    legs = _full_exit_legs(tatn_px=592.0, tatnp_px=580.0)
    client = MagicMock()
    client.execute_spread_exit.return_value = legs
    client.detect_spread_position.return_value = None
    signal_bar = {"tradeDate": "2026-09-04 18:30", "spread": 3.50}
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-04 18:30")):
        with patch.object(engine, "TInvestClient", return_value=client):
            with patch.object(store, "close_open_trade") as close_mock:
                with pytest.raises(RuntimeError, match="проскальзывание|отменён"):
                    engine.close_position(source="AUTO_TP", signal_bar=signal_bar)
                close_mock.assert_not_called()
    assert store.get_open_trade() is not None
    assert store.get_setting("auto_execute", "1") == "0"


def test_close_position_aborts_when_broker_not_flat(tmp_path, monkeypatch):
    _cred_db(tmp_path, monkeypatch, "flat_guard.db")
    store.insert_open_trade(
        {
            "mode": "prod",
            "account_id": "acc",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": 360,
            "entry_time": "2026-09-04 18:00",
            "entry_z": 1.5,
            "entry_spread": 3.13,
            "source": "AUTO",
            "legs": [],
        }
    )
    legs = _full_exit_legs()
    client = MagicMock()
    client.execute_spread_exit.return_value = legs
    client.detect_spread_position.return_value = {
        "direction": "LONG",
        "quantity_lots": 325,
    }
    signal_bar = {"tradeDate": "2026-09-04 18:30", "spread": 3.45}
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-04 18:30")):
        with patch.object(engine, "TInvestClient", return_value=client):
            with patch.object(store, "close_open_trade") as close_mock:
                with pytest.raises(RuntimeError, match="осталась позиция|брокер"):
                    engine.close_position(source="AUTO_TP", signal_bar=signal_bar)
                close_mock.assert_not_called()
    assert store.get_open_trade() is not None
    assert store.get_setting("auto_execute", "1") == "0"


def test_close_position_manual_skips_slippage_guard(tmp_path, monkeypatch):
    _cred_db(tmp_path, monkeypatch, "manual_slip.db")
    store.set_setting("auto_execute", "0")
    store.insert_open_trade(
        {
            "mode": "prod",
            "account_id": "acc",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": 360,
            "entry_time": "2026-09-04 18:00",
            "entry_z": 1.5,
            "entry_spread": 3.13,
            "source": "MANUAL",
            "legs": [],
        }
    )
    legs = _full_exit_legs(tatn_px=592.0, tatnp_px=580.0)
    client = MagicMock()
    client.execute_spread_exit.return_value = legs
    client.detect_spread_position.return_value = None
    client.get_portfolio.return_value = {}
    client.portfolio_total_rub.return_value = 100_000.0
    with patch.object(engine, "TInvestClient", return_value=client):
        with patch.object(engine, "market_snapshot", return_value={"trade_date": "2026-09-04 18:30", "z": 1.0, "spread": 3.5}):
            with patch.object(store, "close_open_trade", return_value={"id": 1}) as close_mock:
                engine.close_position(source="MANUAL")
                close_mock.assert_called_once()
