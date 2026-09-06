"""Weekend AUTO: сб/вс 10:00–18:59 МСК по tip1m; вне окна — запрет."""

from __future__ import annotations

from datetime import datetime
from unittest.mock import patch

import pytest

from live import engine, store
from live.dealer_quotes import MSK, is_msk_auto_session
from live.signals import Position, is_moex_equity_session_bar


def _msk(stamp: str) -> datetime:
    return datetime.strptime(stamp, "%Y-%m-%d %H:%M").replace(tzinfo=MSK)


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

# Saturday 2026-09-05
_TP_TIP_SAT_MID = {
    "tradeDate": "2026-09-05 12:00:00",
    "timestampMs": 1,
    "zScore": 1.39,
    "spread": 3.50,
}
_TP_TIP_SAT_MORNING = {
    "tradeDate": "2026-09-05 09:00:00",
    "timestampMs": 1,
    "zScore": 1.39,
    "spread": 3.50,
}
_TP_TIP_SAT_EVENING = {
    "tradeDate": "2026-09-05 19:00:00",
    "timestampMs": 1,
    "zScore": 1.39,
    "spread": 3.50,
}
_TP_TIP_SAT_LATE = {
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


def test_auto_session_hours_saturday_and_weekday():
    assert is_msk_auto_session(_msk("2026-09-05 10:00")) is True
    assert is_msk_auto_session(_msk("2026-09-05 18:59")) is True
    assert is_msk_auto_session(_msk("2026-09-05 09:00")) is False
    assert is_msk_auto_session(_msk("2026-09-05 19:00")) is False
    assert is_msk_auto_session(_msk("2026-09-04 07:00")) is True
    assert is_msk_auto_session(_msk("2026-09-04 23:49")) is True
    assert is_msk_auto_session(_msk("2026-09-04 06:59")) is False
    assert is_msk_auto_session(_msk("2026-09-04 23:50")) is False


def test_session_bar_saturday_window():
    assert is_moex_equity_session_bar("2026-09-05 10:00") is True
    assert is_moex_equity_session_bar("2026-09-05 18:59") is True
    assert is_moex_equity_session_bar("2026-09-05 09:00") is False
    assert is_moex_equity_session_bar("2026-09-05 19:00") is False
    assert is_moex_equity_session_bar("2026-09-04 12:00") is True


def test_auto_orders_allowed_saturday_midday():
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-05 12:00")):
        assert engine._auto_orders_allowed() is True
        assert engine._auto_orders_allowed(_TP_TIP_SAT_MID) is True
        assert engine._auto_orders_allowed(_TP_TIP_SAT_MORNING) is False
        assert engine._auto_orders_allowed(_TP_TIP_SAT_EVENING) is False


def test_auto_orders_blocked_saturday_09_and_19():
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-05 09:00")):
        assert engine._auto_orders_allowed() is False
        assert engine._auto_orders_allowed(_TP_TIP_SAT_MID) is False
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-05 19:00")):
        assert engine._auto_orders_allowed() is False
        assert engine._auto_orders_allowed(_TP_TIP_SAT_MID) is False
        assert engine._auto_orders_allowed(_TP_TIP_SAT_LATE) is False


def test_auto_orders_weekday_unchanged():
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-04 12:00")):
        assert engine._auto_orders_allowed() is True
        assert engine._auto_orders_allowed(_TP_TIP_WEEKDAY) is True
        assert engine._auto_orders_allowed(_TP_TIP_SAT_LATE) is False
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-04 06:30")):
        assert engine._auto_orders_allowed() is False
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-04 23:50")):
        assert engine._auto_orders_allowed() is False


def test_close_position_auto_tp_refuses_saturday_evening():
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-05 20:22")):
        with pytest.raises(RuntimeError, match="вне сессии TQBR"):
            engine.close_position(source="AUTO_TP")
        with pytest.raises(RuntimeError, match="вне сессии TQBR"):
            engine.close_position(source="AUTO")


def test_open_position_auto_refuses_saturday_morning():
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-05 09:00")):
        with pytest.raises(RuntimeError, match="вне сессии TQBR"):
            engine.open_position(Position.LONG, source="AUTO")


def test_weekend_auto_tp_does_not_call_close_outside_window(tmp_path, monkeypatch):
    _cred_db(tmp_path, monkeypatch, "weekend_tp.db")
    _insert_long()
    result: dict = {}
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-05 20:22")):
        with patch.object(engine, "close_position") as close:
            msg, fired = engine._maybe_tp_exit_on_tip(
                tip=_TP_TIP_SAT_LATE,
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


def test_saturday_midday_auto_tp_still_closes(tmp_path, monkeypatch):
    _cred_db(tmp_path, monkeypatch, "weekend_tp_mid.db")
    _insert_long()
    result: dict = {}
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-05 12:00")):
        with patch.object(engine, "close_position") as close:
            close.return_value = {"ok": True}
            _msg, fired = engine._maybe_tp_exit_on_tip(
                tip=_TP_TIP_SAT_MID,
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


def test_weekday_auto_tp_still_closes(tmp_path, monkeypatch):
    _cred_db(tmp_path, monkeypatch, "weekday_tp.db")
    _insert_long()
    result: dict = {}
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-04 12:00")):
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


def test_msk_session_block_reason_sunday_morning():
    from live.dealer_quotes import msk_session_block_reason

    assert msk_session_block_reason(_msk("2026-09-06 09:00")) == "сессии нет · до 10:00 МСК"
    assert msk_session_block_reason(_msk("2026-09-06 10:00")) is None
    assert msk_session_block_reason(_msk("2026-09-06 18:59")) is None
    assert "10:00–18:59" in (msk_session_block_reason(_msk("2026-09-06 19:00")) or "")


def test_manual_sunday_09_reject():
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-06 09:00")):
        with pytest.raises(RuntimeError, match="вне сессии TQBR"):
            engine.open_position(Position.LONG, source="MANUAL")
        with pytest.raises(RuntimeError, match="вне сессии TQBR"):
            engine.open_position(Position.SHORT, source="MANUAL")
        with pytest.raises(RuntimeError, match="вне сессии TQBR"):
            engine.close_position(source="MANUAL")
        with pytest.raises(RuntimeError, match="вне сессии TQBR"):
            engine.close_position(source="PORTFOLIO")


def test_manual_sunday_10_allow_session_gate():
    with patch("live.dealer_quotes.now_msk", return_value=_msk("2026-09-06 10:00")):
        assert engine._auto_orders_allowed() is True
        with patch.object(engine.store, "get_credentials", return_value=("prod", "", "")):
            with pytest.raises(RuntimeError, match="токен"):
                engine.open_position(Position.LONG, source="MANUAL")
            with pytest.raises(RuntimeError, match="токен"):
                engine.close_position(source="MANUAL")

