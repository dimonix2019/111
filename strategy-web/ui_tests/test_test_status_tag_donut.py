"""UI: Test status shows trades/PnL, not sim timings; tag donut instead of heatmap title."""
from __future__ import annotations

import os
import re

import pytest
from playwright.sync_api import Page, Route, expect

BASE_URL = os.environ.get("MOEX_DESK_URL", "http://127.0.0.1:8765").rstrip("/")
CACHE_BUST = "20260906shelfBar1"


def _ok_json(route: Route, payload: dict) -> None:
    route.fulfill(status=200, content_type="application/json", json=payload)


def _stub_tip1m_tags(route: Route) -> None:
    _ok_json(
        route,
        {
            "trades": [
                {
                    "index": 1,
                    "direction": "Long",
                    "tag": "main",
                    "source": "база",
                    "entryDate": "2026-01-12 10:15",
                    "exitDate": "2026-01-12 11:00",
                    "status": "Закрыта",
                    "net": 4000,
                    "gross": 4000,
                    "commission": 0,
                    "overnight": 0,
                },
                {
                    "index": 2,
                    "direction": "Long",
                    "tag": "addon",
                    "source": "добор",
                    "entryDate": "2026-01-12 10:20",
                    "exitDate": "2026-01-12 11:30",
                    "status": "Закрыта",
                    "net": 2500,
                    "gross": 2500,
                    "commission": 0,
                    "overnight": 0,
                },
                {
                    "index": 3,
                    "direction": "Short",
                    "tag": "extra",
                    "source": "экстра",
                    "entryDate": "2026-01-12 10:25",
                    "exitDate": "2026-01-12 12:00",
                    "status": "Закрыта",
                    "net": 1500,
                    "gross": 1500,
                    "commission": 0,
                    "overnight": 0,
                },
                {
                    "index": 4,
                    "direction": "Short",
                    "tag": "shelf_ff",
                    "source": "полка",
                    "entryDate": "2026-01-12 10:30",
                    "exitDate": "2026-01-12 13:00",
                    "status": "Закрыта",
                    "net": 2000,
                    "gross": 2000,
                    "commission": 0,
                    "overnight": 0,
                },
                {
                    "index": 5,
                    "direction": "Long",
                    "tag": "main",
                    "source": "база",
                    "entryDate": "2026-01-10 11:00",
                    "exitDate": "2026-01-10 16:00",
                    "status": "Закрыта",
                    "net": 8000,
                    "gross": 8000,
                    "commission": 0,
                    "overnight": 0,
                },
            ],
            "summary": {
                "trades": 4,
                "wins": 4,
                "pnlRub": 10000,
                "retPct": 10.0,
                "finalEquityRub": 110000,
                "openCount": 0,
                "by_tag": {
                    "main": {"n": 1, "pnlRub": 4000},
                    "addon": {"n": 1, "pnlRub": 2500},
                    "extra": {"n": 1, "pnlRub": 1500},
                    "shelf_ff": {"n": 1, "pnlRub": 2000},
                    "weekend": {"n": 0, "pnlRub": 0},
                },
            },
            "params": {},
            "meta": {
                "simSec": 1.144,
                "ensureSec": 1.473,
                "cacheTier": "window-mem",
            },
        },
    )


@pytest.mark.ui
def test_status_pnl_not_timings_and_tag_donut(page: Page) -> None:
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    page.add_init_script(
        """() => {
          localStorage.setItem('moexReplay.viewMode', 'replay');
          localStorage.removeItem('moexReplay.compound');
          localStorage.removeItem('moexReplay.weekendTrading');
        }"""
    )
    page.route("**/api/sim/tip1m", _stub_tip1m_tags)
    page.goto(f"{BASE_URL}/?v={CACHE_BUST}", wait_until="domcontentloaded")
    expect(page.locator("#app")).to_be_visible(timeout=30_000)
    replay_chip = page.locator('.chip-view[data-view="replay"]')
    if "active" not in (replay_chip.get_attribute("class") or ""):
        replay_chip.click()
    expect(replay_chip).to_have_class(re.compile(r"\bactive\b"))

    status = page.locator("#status")
    expect(status).to_contain_text("сд", timeout=15_000)
    status_text = status.inner_text()
    assert "window-mem" not in status_text
    assert "сим 1." not in status_text
    assert "₽" in status_text or "%" in status_text

    assert page.locator("#zHeatmapTitle").count() == 0
    head = page.locator("#zHeatmapPane .trades-section-head")
    expect(head).not_to_contain_text("S% вх")
    expect(head).not_to_contain_text("узкий Long")
    expect(head).not_to_contain_text("PNL ·")

    chart = page.locator("#tagShareDonut")
    expect(chart).to_be_visible(timeout=15_000)
    expect(chart.locator("circle, .tag-share-slice, .tag-share-svg")).to_have_count(0)
    expect(chart.locator(".tag-share-bar")).to_have_count(5)
    expect(chart.locator(".tag-share-bars")).to_be_visible()
    expect(chart).to_contain_text("База")
    expect(chart).to_contain_text("добор")
    expect(chart).to_contain_text("экстра")
    expect(chart).to_contain_text("полка")
    expect(chart).to_contain_text("выходные")
    shelf_row = chart.locator(".tag-share-row").filter(has_text="полка")
    expect(shelf_row).to_contain_text("20%")
    expect(shelf_row).to_contain_text("2")
    expect(shelf_row).to_contain_text("₽")
    chart_text = chart.inner_text()
    assert "качалка" not in chart_text.lower()
    assert "%" in chart_text
    assert "₽" in chart_text
    weekend_row = chart.locator(".tag-share-row").filter(has_text="выходные")
    expect(weekend_row).to_be_visible()
    expect(weekend_row).to_contain_text("0%")
    weekend_txt = weekend_row.inner_text()
    assert "8" not in weekend_txt.replace("выходные", "")
    expect(page.locator("#btnWeekendTrading")).to_have_attribute("aria-pressed", "false")
    assert not errors, f"pageerror: {errors}"


def _stub_tip1m_weekend_on(route: Route) -> None:
    _ok_json(
        route,
        {
            "trades": [
                {
                    "index": 1,
                    "direction": "Long",
                    "tag": "main",
                    "source": "база",
                    "entryDate": "2026-01-12 10:15",
                    "exitDate": "2026-01-12 11:00",
                    "status": "Закрыта",
                    "net": 7000,
                    "gross": 7000,
                    "commission": 0,
                    "overnight": 0,
                },
                {
                    "index": 2,
                    "direction": "Long",
                    "tag": "main",
                    "source": "база",
                    "entryDate": "2026-01-10 11:00",
                    "exitDate": "2026-01-10 16:00",
                    "status": "Закрыта",
                    "net": 3000,
                    "gross": 3000,
                    "commission": 0,
                    "overnight": 0,
                },
            ],
            "summary": {
                "trades": 2,
                "wins": 2,
                "pnlRub": 10000,
                "retPct": 10.0,
                "finalEquityRub": 110000,
                "openCount": 0,
                "by_tag": {
                    "main": {"n": 1, "pnlRub": 7000},
                    "addon": {"n": 0, "pnlRub": 0},
                    "extra": {"n": 0, "pnlRub": 0},
                    "shelf_ff": {"n": 0, "pnlRub": 0},
                    "weekend": {"n": 1, "pnlRub": 3000},
                },
            },
            "params": {"weekendTrading": True},
            "meta": {"weekendTrading": True, "weekendWindowMsk": "10:00–18:59"},
        },
    )


@pytest.mark.ui
def test_tag_share_weekend_legend_when_chip_on(page: Page) -> None:
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    page.add_init_script(
        """() => {
          localStorage.setItem('moexReplay.viewMode', 'replay');
          localStorage.setItem('moexReplay.weekendTrading', '1');
        }"""
    )
    page.route("**/api/sim/tip1m", _stub_tip1m_weekend_on)
    page.goto(
        f"{BASE_URL}/?v={CACHE_BUST}&weekend_trading=true",
        wait_until="domcontentloaded",
    )
    expect(page.locator("#app")).to_be_visible(timeout=30_000)
    replay_chip = page.locator('.chip-view[data-view="replay"]')
    if "active" not in (replay_chip.get_attribute("class") or ""):
        replay_chip.click()
    expect(replay_chip).to_have_class(re.compile(r"\bactive\b"))
    expect(page.locator("#btnWeekendTrading")).to_have_attribute("aria-pressed", "true")

    chart = page.locator("#tagShareDonut")
    expect(chart).to_be_visible(timeout=15_000)
    expect(chart.locator(".tag-share-bar")).to_have_count(5)
    weekend_row = chart.locator(".tag-share-row").filter(has_text="выходные")
    expect(weekend_row).to_contain_text("30%")
    expect(weekend_row).to_contain_text("3")
    expect(weekend_row).to_contain_text("₽")
    base_row = chart.locator(".tag-share-row").filter(has_text="База")
    expect(base_row).to_contain_text("70%")
    assert not errors, f"pageerror: {errors}"


def _stub_tip1m_shelf_in_base(route: Route) -> None:
    """API ошибочно свалил полку в main — UI должен вынуть ₽ в столбик полка."""
    _ok_json(
        route,
        {
            "trades": [
                {
                    "index": 1,
                    "direction": "Long",
                    "tag": "main",
                    "source": "база",
                    "entryDate": "2026-01-12 10:15",
                    "exitDate": "2026-01-12 11:00",
                    "status": "Закрыта",
                    "net": 4000,
                    "gross": 4000,
                    "commission": 0,
                    "overnight": 0,
                },
                {
                    "index": 2,
                    "direction": "Short",
                    "tag": "пол–потолок",
                    "source": "база",
                    "exitReason": "shelf_edge",
                    "entryDate": "2026-01-13 10:30",
                    "exitDate": "2026-01-13 13:00",
                    "status": "Закрыта",
                    "net": 6000,
                    "gross": 6000,
                    "commission": 0,
                    "overnight": 0,
                },
            ],
            "summary": {
                "trades": 2,
                "wins": 2,
                "pnlRub": 10000,
                "retPct": 10.0,
                "finalEquityRub": 110000,
                "openCount": 0,
                "by_tag": {
                    "main": {"n": 2, "pnlRub": 10000},
                    "addon": {"n": 0, "pnlRub": 0},
                    "extra": {"n": 0, "pnlRub": 0},
                    "shelf_ff": {"n": 0, "pnlRub": 0},
                    "weekend": {"n": 0, "pnlRub": 0},
                },
            },
            "params": {},
            "meta": {},
        },
    )


@pytest.mark.ui
def test_tag_share_shelf_not_swallowed_by_base(page: Page) -> None:
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    page.add_init_script(
        """() => {
          localStorage.setItem('moexReplay.viewMode', 'replay');
          localStorage.removeItem('moexReplay.weekendTrading');
        }"""
    )
    page.route("**/api/sim/tip1m", _stub_tip1m_shelf_in_base)
    page.goto(f"{BASE_URL}/?v={CACHE_BUST}", wait_until="domcontentloaded")
    expect(page.locator("#app")).to_be_visible(timeout=30_000)
    replay_chip = page.locator('.chip-view[data-view="replay"]')
    if "active" not in (replay_chip.get_attribute("class") or ""):
        replay_chip.click()
    expect(replay_chip).to_have_class(re.compile(r"\bactive\b"))

    chart = page.locator("#tagShareDonut")
    expect(chart).to_be_visible(timeout=15_000)
    shelf_row = chart.locator(".tag-share-row").filter(has_text="полка")
    expect(shelf_row).to_contain_text("60%")
    expect(shelf_row).to_contain_text("6")
    expect(shelf_row).to_contain_text("₽")
    base_row = chart.locator(".tag-share-row").filter(has_text="База")
    expect(base_row).to_contain_text("40%")
    assert not errors, f"pageerror: {errors}"

