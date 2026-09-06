"""UI: Test status shows trades/PnL, not sim timings; tag donut instead of heatmap title."""
from __future__ import annotations

import os
import re

import pytest
from playwright.sync_api import Page, Route, expect

BASE_URL = os.environ.get("MOEX_DESK_URL", "http://127.0.0.1:8765").rstrip("/")
CACHE_BUST = "20260906tipBars1"


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
    expect(chart.locator(".tag-share-bar")).to_have_count(4)
    expect(chart.locator(".tag-share-bars")).to_be_visible()
    expect(chart).to_contain_text("База")
    expect(chart).to_contain_text("добор")
    expect(chart).to_contain_text("экстра")
    expect(chart).to_contain_text("полка")
    chart_text = chart.inner_text()
    assert "качалка" not in chart_text.lower()
    assert "%" in chart_text
    assert "₽" in chart_text
    assert not errors, f"pageerror: {errors}"
