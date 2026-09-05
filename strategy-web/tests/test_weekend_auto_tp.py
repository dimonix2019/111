"""Weekend AUTO_TP must not PostOrder (BESTPRICE fills off TQBR)."""

from __future__ import annotations

import pytest
from unittest.mock import patch

from live import engine, store
from live.signals import Position


def _cred_db(tmp_path, monkeypatch, name: str):
    db = tmp_path / name
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)
    store.set_setting("token_prod", "t.dummy")
    store.set_setting("account_prod", "acc")
    store.set_setting("execution_mode", "prod")


def _insert_long(*, entry_spread: float = 3.13):
    store.insert_open_trade(
        {
            "mode": "prod",
            "account_id": "acc",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": 80,
            "entry_time": "2026-09-05 14:30",
            "entry_z": 0.9,
            "entry_spread": entry_spread,
            "entry_tatn": 591.0,
            "entry_tatnp": 573.0,
            "execution_notional_rub": None,
            "source": "MANUAL",
            "legs": [{"note": "broker adopt"}],
        }
    )


_TP_SETTINGS = {
    "take_profit_pct": 2.0,
    "leverage": 7.0,
    "entry_deposit_rub": 60_000.0,
}

# 7 * (3.50 - 3.13) = 2.59% ≥ TP 2%
_TP_TIP_WEEKEND = {
    "tradeDate": "2026-09-05 20:22:00",
    "timestampMs": 1,
    "zScore": 1.39,
    "spread": 3.50,
}

_TP_TIP_WEEKDAY = {
    "tradeDate": "2026-09-04 12:00:00",
    "timestampMs": 1,
    "zScore": 1.39,
    "spread": 3.50,
}


def test_auto_orders_blocked_when_dealer_session():
    with patch("live.dealer_quotes.want_dealer_quotes", return_value=True):
        assert engine._auto_orders_allowed() is False
        assert engine._auto_orders_allowed(_TP_TIP_WEEKDAY) is False


def test_auto_orders_blocked_for_saturday_bar_even_if_clock_says_session():
    with patch("live.dealer_quotes.want_dealer_quotes", return_value=False):
        assert engine._auto_orders_allowed(_TP_TIP_WEEKEND) is False
        assert engine._auto_orders_allowed(_TP_TIP_WEEKDAY) is True


def test_close_position_auto_tp_refuses_weekend():
    with patch("live.dealer_quotes.want_dealer_quotes", return_value=True):
        with pytest.raises(RuntimeError, match="вне сессии TQBR"):
            engine.close_position(source="AUTO_TP")
        with pytest.raises(RuntimeError, match="вне сессии TQBR"):
            engine.close_position(source="AUTO")


def test_open_position_auto_refuses_weekend():
    with patch("live.dealer_quotes.want_dealer_quotes", return_value=True):
        with pytest.raises(RuntimeError, match="вне сессии TQBR"):
            engine.open_position(Position.LONG, source="AUTO")


def test_weekend_auto_tp_does_not_call_close(tmp_path, monkeypatch):
    _cred_db(tmp_path, monkeypatch, "weekend_tp.db")
    _insert_long()
    result: dict = {}
    with patch("live.dealer_quotes.want_dealer_quotes", return_value=True):
        with patch.object(engine, "close_position") as close:
            msg, fired = engine._maybe_tp_exit_on_tip(
                tip=_TP_TIP_WEEKEND,
                settings=_TP_SETTINGS,
                auto=True,
                entry=1.6,
                exit_z=1.3,
                msg="x",
                result=result,
            )
    assert fired is False
    close.assert_not_called()
    assert store.get_open_trade() is not None


def test_weekday_auto_tp_still_closes(tmp_path, monkeypatch):
    _cred_db(tmp_path, monkeypatch, "weekday_tp.db")
    _insert_long()
    result: dict = {}
    with patch("live.dealer_quotes.want_dealer_quotes", return_value=False):
        with patch.object(engine, "close_position") as close:
            close.return_value = {"ok": True}
            _msg, fired = engine._maybe_tp_exit_on_tip(
                tip=_TP_TIP_WEEKDAY,
                settings=_TP_SETTINGS,
                auto=True,
                entry=1.6,
                exit_z=1.3,
                msg="x",
                result=result,
            )
    assert fired is True
    close.assert_called_once()
    assert close.call_args.kwargs.get("source") == "AUTO_TP"
