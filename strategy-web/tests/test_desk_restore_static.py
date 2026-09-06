"""Статика стола 4.09: OHLC, год на оси, запас до ГО, Src, тысячи."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STATIC = ROOT / "replay" / "static"


def test_axis_year_formatter_in_chart_js():
    text = (STATIC / "chart.js").read_text(encoding="utf-8")
    assert "year: '2-digit'" in text
    assert "function formatMskAxisDayMonthYear" in text
    assert "function formatOhlcLine" in text
    assert "testCandleOhlc" in text


def test_trade_ohlc_header_and_tp_line():
    html = (STATIC / "index.html").read_text(encoding="utf-8")
    js = (STATIC / "trade.js").read_text(encoding="utf-8")
    css = (STATIC / "css-shell.css").read_text(encoding="utf-8")
    assert 'id="tradeCandleOhlc"' in html
    assert 'id="testCandleOhlc"' in html
    assert 'id="tradeMarginHeadroom"' in html
    assert "CURRENT_PRICE_LINE_COLOR" in js
    assert "TP_EXIT_LINE_COLOR" in js
    assert "setTpExitSpreadLine" in js
    assert "renderMarginHeadroom" in js
    assert "#fde68a" in css


def test_src_hint_test_tail():
    text = (STATIC / "replay-sim.js").read_text(encoding="utf-8")
    assert "в Тесте — база / добор / экстра / полка" in text


def test_account_rub_uses_grouping_not_k():
    text = (STATIC / "replay-sim.js").read_text(encoding="utf-8")
    assert "useGrouping: true" in text
    assert "toFixed(2)}k" not in text.split("function formatAccountRub")[1].split("function formatCostRub")[0]


def test_cache_bust_desk_restore():
    html = (STATIC / "index.html").read_text(encoding="utf-8")
    assert "chart.js?v=20260905deskRestore1" in html
    assert "trade.js?v=20260906liveSp1" in html


def test_weekend_aligns_last_candle_to_live_spread():
    """Жёлтая last-value на оси = шапка/дилер, не last close 1м (игла/parquet)."""
    js = (STATIC / "trade.js").read_text(encoding="utf-8")
    assert "function alignTip1mBarsToLiveTip" in js
    assert "useDealerPx && dealer.spread" in js
    # Регрессия: выходные пропускали align — ось жила на flattened close.
    assert "} else if (!weekendMonitor && chartBars.length)" not in js
    assert "alignTip1mBarsToLiveTip(chartBars, data, data.open || null)" in js
