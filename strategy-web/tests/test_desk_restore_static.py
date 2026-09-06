"""Статика стола 4.09: OHLC, год на оси, запас до ГО, Src, тысячи."""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STATIC = ROOT / "replay" / "static"
INDEX_HTML = STATIC / "index.html"

# src/href="/static/<file>?v=<ver>" — единый источник правды: index.html
CACHE_BUST_RE = re.compile(
    r'(?:src|href)="/static/(?P<file>[^"?]+\.(?:js|css))\?v=(?P<ver>[^"]+)"'
)


def _read_index_html() -> str:
    return INDEX_HTML.read_text(encoding="utf-8")


def _parse_cache_bust_refs(html: str | None = None) -> dict[str, str]:
    """filename → ?v= token из index.html (без дублирования в тестах)."""
    html = html if html is not None else _read_index_html()
    refs: dict[str, str] = {}
    for m in CACHE_BUST_RE.finditer(html):
        refs[m.group("file")] = m.group("ver")
    return refs


def _load_static(name: str) -> str:
    return (STATIC / name).read_text(encoding="utf-8")


def test_axis_year_formatter_in_chart_js():
    text = _load_static("chart.js")
    assert "year: '2-digit'" in text
    assert "function formatMskAxisDayMonthYear" in text
    assert "function formatOhlcLine" in text
    assert "testCandleOhlc" in text


def test_trade_ohlc_header_and_tp_line():
    html = _read_index_html()
    js = _load_static("trade.js")
    css = _load_static("css-shell.css")
    assert 'id="tradeCandleOhlc"' in html
    assert 'id="testCandleOhlc"' in html
    assert 'id="tradeMarginHeadroom"' in html
    assert "CURRENT_PRICE_LINE_COLOR" in js
    assert "TP_EXIT_LINE_COLOR" in js
    assert "setTpExitSpreadLine" in js
    assert "renderMarginHeadroom" in js
    assert "#fde68a" in css


def test_src_hint_test_tail():
    text = _load_static("replay-sim.js")
    assert "в Тесте — база / добор / экстра / полка" in text


def test_account_rub_uses_grouping_not_k():
    text = _load_static("replay-sim.js")
    assert "useGrouping: true" in text
    assert "toFixed(2)}k" not in text.split("function formatAccountRub")[1].split("function formatCostRub")[0]


def test_tag_share_chip_delta_cache_bust():
    html = _read_index_html()
    refs = _parse_cache_bust_refs(html)
    for name in ("app.js", "app-test-chips.js", "replay-sim.js", "css-testing.css"):
        ver = refs[name]
        assert f'{name}?v={ver}' in html

    js = _load_static("app.js")
    chips = _load_static("app-test-chips.js")
    sim = _load_static("replay-sim.js")
    assert "function isWeekendChipEntry" in js or "function isWeekendChipEntry" in chips
    assert "chip_delta" in chips
    assert "вклад чипа в итог" in chips
    assert "function markMonthlyPnlPending" in js or "function markMonthlyPnlPending" in chips
    assert "data-month-keys" in js or "data-month-keys" in chips
    assert "Чистая прибыль" in js
    assert "function formatProfitAccountRub" in sim
    assert "profitRub" in sim
    tip_note = js.split("const profitRub = Number.isFinite(tipSum.profitRub)", 1)[1].split("tipNote =", 1)[0]
    assert "formatProfitAccountRub" in tip_note
    assert "finalEquityRub" not in tip_note
    assert "equityRub" not in tip_note


def test_cache_bust_desk_restore():
    html = _read_index_html()
    refs = _parse_cache_bust_refs(html)
    for name in ("chart.js", "trade.js", "live.js"):
        assert name in refs
        assert f'{name}?v={refs[name]}' in html

    js = _load_static("trade.js")
    live = _load_static("live.js")
    assert "Сессии нет · до 10:00" not in js
    assert "Сессии нет · до 10:00" not in live
    assert "sessionOrdersBlockReason" not in js
    assert "sessionOrdersBlockReason" not in live


def test_cache_bust_consistency():
    """Все src/href с ?v= в index.html → файл существует на диске."""
    html = _read_index_html()
    refs = _parse_cache_bust_refs(html)
    assert refs, "index.html must reference at least one cache-busted static asset"
    missing = [name for name in refs if not (STATIC / name).is_file()]
    assert not missing, f"cache-busted assets missing on disk: {missing}"


def test_weekend_aligns_last_candle_to_live_spread():
    """Жёлтая last-value на оси = шапка/дилер, не last close 1м (игла/parquet)."""
    js = _load_static("trade.js")
    chart_mod = _load_static("trade-desk-chart.js")
    assert "function alignTip1mBarsToLiveTip" in js or "function alignTip1mBarsToLiveTip" in chart_mod
    assert "useDealerPx && dealer.spread" in chart_mod
    # Регрессия: выходные пропускали align — ось жила на flattened close.
    assert "} else if (!weekendMonitor && chartBars.length)" not in js
    assert "alignTip1mBarsToLiveTip(chartBars, data, data.open || null)" in js
