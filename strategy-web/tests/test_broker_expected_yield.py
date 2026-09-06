"""Prod desk PnL = GetPortfolio.expectedYield (APK / T‑Invest), not local spread MTM."""

from __future__ import annotations

import json

from live.close_forecast import compute_close_forecast
from live.constants import TATN_FIGI, TATNP_FIGI
from live.open_mark import enrich_open_trade
from live.tinvest import parse_spread_leg_broker_pnl


def test_parse_expected_yield_per_ticker_matches_apk():
    portfolio = {
        "positions": [
            {
                "ticker": "TATN",
                "expectedYield": {"units": "-31", "nano": -910000000, "currency": "rub"},
            },
            {
                "ticker": "TATNP",
                "expectedYield": {"units": "40", "nano": 0, "currency": "rub"},
            },
        ]
    }
    pnl = parse_spread_leg_broker_pnl(portfolio, "LONG")
    assert pnl is not None
    assert abs(pnl["long_leg_yield_rub"] - (-31.91)) < 0.02
    assert abs(pnl["short_leg_yield_rub"] - 40.0) < 0.01
    assert abs(pnl["net_gross_rub"] - 8.09) < 0.03
    assert pnl["pnl_source"] == "tinkoff_expected_yield"


def test_parse_expected_yield_by_figi_without_ticker():
    portfolio = {
        "positions": [
            {
                "figi": TATN_FIGI,
                "expected_yield": {"units": "-500", "nano": 0},
            },
            {
                "figi": TATNP_FIGI,
                "expectedYield": {"units": "-305", "nano": 0},
            },
        ]
    }
    pnl = parse_spread_leg_broker_pnl(portfolio, "LONG")
    assert pnl is not None
    assert pnl["expected_yield_rub"] == -805.0
    assert pnl["net_gross_rub"] == -805.0


def test_enrich_uses_broker_yield_not_local_mtm():
    """Screenshot case: local fill/spread MTM ≠ T‑Invest expectedYield."""
    open_t = {
        "direction": "LONG",
        "quantity_lots": 360,
        "entry_time": "2026-09-06 10:04:00",
        "entry_spread": 2.95,
        "entry_tatn": 596.9,
        "entry_tatnp": 579.8,
        "execution_notional_rub": 60000,
        "legs_json": json.dumps(
            [
                {
                    "ticker": "TATN",
                    "order": {"executedOrderPrice": {"units": "596", "nano": 900000000}},
                },
                {
                    "ticker": "TATNP",
                    "order": {"executedOrderPrice": {"units": "579", "nano": 800000000}},
                },
            ]
        ),
    }
    local = enrich_open_trade(
        open_t,
        z_now=-0.5,
        spread_now=3.03,
        trade_date="2026-09-06 10:34:00",
        tatn_now=597.5,
        tatnp_now=579.0,
    )
    broker = enrich_open_trade(
        open_t,
        z_now=-0.5,
        spread_now=3.03,
        trade_date="2026-09-06 10:34:00",
        tatn_now=597.5,
        tatnp_now=579.0,
        broker_pnl={"net_gross_rub": -805.0, "tatn_yield_rub": -1200.0, "tatnp_yield_rub": 395.0},
    )
    assert local is not None and broker is not None
    assert local["mark"]["pnl_source"] == "broker_fills"
    assert local["mark"]["unrealized_pnl_rub"] != -805.0
    assert broker["mark"]["pnl_source"] == "tinkoff_expected_yield"
    assert broker["mark"]["unrealized_pnl_rub"] == -805.0
    assert broker["mark"]["net_approx_rub"] == -805.0
    assert broker["mark"]["expected_yield_rub"] == -805.0


def test_close_forecast_shows_broker_yield_without_iss():
    open_t = {
        "direction": "LONG",
        "quantity_lots": 360,
        "entry_time": "2026-09-06 10:04:00",
        "entry_spread": 2.95,
        "execution_notional_rub": 60000.0,
        "mark": {
            "unrealized_pnl_rub": -805.0,
            "expected_yield_rub": -805.0,
            "pnl_source": "tinkoff_expected_yield",
            "overnight_rub": 0.0,
            "overnight_days": 0,
            "overnight_per_day_rub": 175.0,
            "fill_spread": 2.95,
        },
    }
    broker = {
        "total_rub": 98367.0,
        "cash_rub": 92427.0,
        "expected_yield_rub": -805.0,
        "mode": "prod",
    }
    out = compute_close_forecast(
        open_t,
        broker=broker,
        mark=open_t["mark"],
        settings={
            "entry_deposit_rub": 60000,
            "leverage": 7,
            "spread_exit_narrow": 4.0,
            "spread_exit_wide": 5.8,
        },
        quotes=None,
        dealer=None,
    )
    assert out["ok"] is True
    assert out["expected_yield_rub"] == -805.0
    assert out["quotes_mode"] == "broker_expected_yield"
    assert out["vs_mid_rub"] == 0.0
    assert abs(out["forecast_total_rub"] - (98367.0 - out["exit_commission_rub"])) < 0.02
    assert abs(out["equity_at_open_rub"] - (98367.0 - (-805.0))) < 0.02
    # Thresholds unchanged
    assert out["exit_level_spread"] == 4.0
