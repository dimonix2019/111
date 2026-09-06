"""UI: OHLC шапка, запас до ГО, год в форматтере, хвост Src."""
from __future__ import annotations

import os
import re

import pytest
from playwright.sync_api import Page, expect

BASE_URL = os.environ.get("MOEX_DESK_URL", "http://127.0.0.1:8765").rstrip("/")
CACHE_BUST = "20260905deskRestore1"


@pytest.mark.ui
def test_desk_restore_markers_in_dom(page: Page) -> None:
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    page.add_init_script(
        """() => { localStorage.setItem('moexReplay.viewMode', 'replay'); }"""
    )
    page.goto(f"{BASE_URL}/?v={CACHE_BUST}", wait_until="domcontentloaded")
    expect(page.locator("#app")).to_be_visible(timeout=30_000)

    expect(page.locator("#testCandleOhlc")).to_be_attached()
    expect(page.locator("#tradeCandleOhlc")).to_be_attached()
    expect(page.locator("#tradeMarginHeadroom")).to_be_attached()

    year_fn = page.evaluate(
        """() => typeof window.formatMskAxisDayMonthYear === 'function'
          ? window.formatMskAxisDayMonthYear(1704067200, false)
          : ''"""
    )
    assert re.search(r"\b2[3-6]\b", str(year_fn)), year_fn

    ohlc_color = page.locator("#testCandleOhlc").evaluate(
        "el => getComputedStyle(el).color"
    )
    assert ohlc_color

    src_hint = page.evaluate(
        """async () => {
          const r = await fetch('/static/replay-sim.js?v=20260905deskRestore1');
          const t = await r.text();
          const m = t.match(/key: 'Source'[^\\n]*hint: '([^']+)'/);
          return m ? m[1] : '';
        }"""
    )
    assert "в Тесте —" in str(src_hint)

    assert not errors, errors
