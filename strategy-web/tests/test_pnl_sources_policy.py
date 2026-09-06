"""PnL sources policy — broker display contract (docs/pnl-sources.md)."""

from __future__ import annotations

from pathlib import Path

from live.open_mark import enrich_open_trade
from live.pnl_sources import BROKER_DISPLAY_SOURCE, response_pnl_source

ROOT = Path(__file__).resolve().parents[1]
TRADE_JS = ROOT / "replay" / "static" / "trade.js"


def test_response_pnl_source_from_enriched_mark():
    open_t = enrich_open_trade(
        {
            "direction": "LONG",
            "quantity_lots": 100,
            "entry_time": "2026-09-06 10:00:00",
            "entry_spread": 3.0,
            "legs_json": "[]",
        },
        z_now=0.1,
        spread_now=3.1,
        trade_date="2026-09-06 10:30:00",
        broker_pnl={"net_gross_rub": -42.0},
    )
    assert open_t is not None
    assert open_t["mark"]["pnl_source"] == "tinkoff_expected_yield"
    assert response_pnl_source(open_t) == BROKER_DISPLAY_SOURCE


def test_response_pnl_source_from_broker_when_mark_plain():
    open_t = {"direction": "LONG", "id": 1}
    broker = {"expected_yield_rub": -100.0, "total_rub": 99000.0}
    assert response_pnl_source(open_t, broker=broker) == BROKER_DISPLAY_SOURCE
    assert response_pnl_source(None, broker=broker) is None
    assert response_pnl_source(open_t, broker={"error": "offline"}) is None


def test_trade_js_broker_pnl_display_guard():
    text = TRADE_JS.read_text(encoding="utf-8")
    assert "isBrokerPnlMark" in text
    assert "never recompute spread MTM for display" in text
    assert "tinkoff_expected_yield" in text


def test_trade_js_open_profit_rub_early_return():
    text = TRADE_JS.read_text(encoding="utf-8")
    assert "function openProfitRub(open)" in text
    assert "isBrokerPnlMark(mark)" in text
