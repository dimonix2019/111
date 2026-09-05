"""Close-after-exit equity forecast (desk «Прогноз после закрытия»)."""

from __future__ import annotations

import math

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
        "entry_spread": 3.45,
        "execution_notional_rub": 69454.0,
        "legs_json": (
            '[{"ticker":"TATN","side":"BUY","price":504.8,"lots":70,'
            '"portfolio_total_rub":100000},'
            '{"ticker":"TATNP","side":"SELL","price":473.6,"lots":70,'
            '"portfolio_total_rub":99980}]'
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
        settings={
            "entry_deposit_rub": 10000,
            "leverage": 7,
            "spread_exit_narrow": 4.0,
            "spread_exit_wide": 5.8,
        },
        tatn_now=501.9,
        tatnp_now=471.7,
        dealer=dealer,
    )
    assert out["ok"] is True
    assert out["has_position"] is True
    assert out["forecast_total_rub"] is not None
    assert isinstance(out["forecast_total_rub"], float)
    assert out["equity_at_open_rub"] == 99980.0
    # Long: exit 4.0 − entry 3.45 = 0.55 п.п. → gross − exit_comm − overnight
    assert out["exit_level_spread"] == 4.0
    assert out["exit_level_pnl_rub"] is not None
    assert out["exit_level_pnl_rub"] > 0


def test_exit_level_short_inverted_delta():
    open_t = {
        "direction": "SHORT",
        "quantity_lots": 70,
        "entry_time": "2026-07-26 15:45",
        "entry_spread": 6.4,
        "execution_notional_rub": 70000.0,
        "mark": {"overnight_rub": 0.0, "overnight_days": 0, "unrealized_pnl_rub": 10.0},
    }
    out = compute_close_forecast(
        open_t,
        broker={"total_rub": 100010.0, "cash_rub": 90000.0},
        settings={
            "entry_deposit_rub": 10000,
            "leverage": 7,
            "spread_exit_narrow": 4.0,
            "spread_exit_wide": 5.8,
        },
        quotes={
            "ok": True,
            "source": "iss",
            "tatn": {"last": 500.0, "bid": 499.9, "ask": 500.1, "has_book": True},
            "tatnp": {"last": 470.0, "bid": 469.9, "ask": 470.1, "has_book": True},
        },
    )
    assert out["exit_level_spread"] == 5.8
    # Short: entry 6.4 − exit 5.8 = 0.6 → плюс
    assert out["exit_level_pnl_rub"] is not None
    assert out["exit_level_pnl_rub"] > 0
    assert out["overnight_per_day_rub"] is not None
    assert out["exit_level_ovn_days_to_red"] is not None
    assert out["exit_level_ovn_days_to_red"] == int(
        math.ceil(out["exit_level_pnl_rub"] / out["overnight_per_day_rub"])
    )


def test_exit_level_ovn_days_to_red_cushion():
    """+410 ₽ подушка / 35 ₽·д → минус через 12 суток."""
    assert int(math.ceil(410 / 35)) == 12
    open_t = {
        "direction": "SHORT",
        "quantity_lots": 70,
        "entry_time": "2026-07-26 15:45",
        "entry_spread": 6.4,
        "entry_tatn": 500.0,
        "entry_tatnp": 470.0,
        "execution_notional_rub": 70000.0,
        "mark": {
            "overnight_rub": 0.0,
            "overnight_days": 0,
            "overnight_per_day_rub": 35.0,
            "unrealized_pnl_rub": 10.0,
            "fill_spread": 6.4,
        },
    }
    out = compute_close_forecast(
        open_t,
        broker={"total_rub": 100010.0, "cash_rub": 90000.0},
        settings={
            "entry_deposit_rub": 10000,
            "leverage": 7,
            "spread_exit_narrow": 4.0,
            "spread_exit_wide": 5.8,
        },
        quotes={
            "ok": True,
            "source": "iss",
            "tatn": {"last": 500.0, "bid": 499.9, "ask": 500.1, "has_book": True},
            "tatnp": {"last": 470.0, "bid": 469.9, "ask": 470.1, "has_book": True},
        },
    )
    assert out["overnight_per_day_rub"] == 35.0
    assert out["exit_level_pnl_rub"] is not None
    if out["exit_level_pnl_rub"] > 0:
        assert out["exit_level_ovn_days_to_red"] == int(
            math.ceil(out["exit_level_pnl_rub"] / 35.0)
        )
