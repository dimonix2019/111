"""Close-after-exit equity forecast (desk «Прогноз после закрытия»)."""

from __future__ import annotations

from live.close_forecast import (
    adverse_close_leg_prices,
    compute_close_forecast,
    quotes_from_dealer,
)


def test_quotes_from_dealer_book():
    q = quotes_from_dealer(
        {
            "ok": True,
            "tatn": 501.9,
            "tatnp": 471.7,
            "tatn_bid": 501.6,
            "tatn_ask": 501.8,
            "tatnp_bid": 470.7,
            "tatnp_ask": 471.7,
        }
    )
    assert q and q["ok"] and q["source"] == "dealer"
    assert q["tatn"]["has_book"] and q["tatnp"]["has_book"]
    legs = adverse_close_leg_prices(direction="LONG", quotes=q)
    assert legs["quotes_mode"] == "dealer_book"
    assert legs["close_tatn"] == 501.6
    assert legs["close_tatnp"] == 471.7


def test_last_fallback_when_no_book():
    q = {
        "ok": True,
        "source": "iss",
        "tatn": {"last": 500.0, "bid": None, "ask": None, "has_book": False},
        "tatnp": {"last": 470.0, "bid": None, "ask": None, "has_book": False},
    }
    legs = adverse_close_leg_prices(direction="LONG", quotes=q)
    assert legs["quotes_mode"] == "last_fallback"
    assert legs["close_tatn"] == 499.95
    assert legs["close_tatnp"] == 470.05


def test_forecast_number_with_broker_and_fills():
    open_t = {
        "direction": "LONG",
        "quantity_lots": 70,
        "entry_time": "2026-07-26 15:45",
        "entry_tatn": 504.8,
        "entry_tatnp": 473.6,
        "execution_notional_rub": 69454.0,
        "legs_json": (
            '[{"ticker":"TATN","side":"BUY","price":504.8,"lots":70},'
            '{"ticker":"TATNP","side":"SELL","price":473.6,"lots":70}]'
        ),
        "mark": {"unrealized_pnl_rub": -70.0, "overnight_rub": 0.0, "overnight_days": 0},
    }
    broker = {"total_rub": 99867.68, "cash_rub": 97753.68, "mode": "prod"}
    dealer = {
        "ok": True,
        "tatn": 501.9,
        "tatnp": 471.7,
        "tatn_bid": 501.6,
        "tatn_ask": 501.8,
        "tatnp_bid": 470.7,
        "tatnp_ask": 471.7,
    }
    out = compute_close_forecast(
        open_t,
        broker=broker,
        settings={"entry_deposit_rub": 10000, "leverage": 7},
        tatn_now=501.9,
        tatnp_now=471.7,
        dealer=dealer,
    )
    assert out["ok"] is True
    assert out["has_position"] is True
    assert out["forecast_total_rub"] is not None
    assert isinstance(out["forecast_total_rub"], float)
