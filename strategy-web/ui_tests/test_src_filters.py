"""UI: Test journal source chips next to risk filters."""
from __future__ import annotations

import os
import re

import pytest
from playwright.sync_api import Page, Route, expect

BASE_URL = os.environ.get("MOEX_DESK_URL", "http://127.0.0.1:8765").rstrip("/")
CACHE_BUST = "20260905srcFilter1"


def _ok_json(route: Route, payload: dict) -> None:
    route.fulfill(status=200, content_type="application/json", json=payload)


def _stub_tip1m_mixed(route: Route) -> None:
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
                    "net": 1000,
                    "gross": 1000,
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
                    "net": 5000,
                    "gross": 5000,
                    "commission": 0,
                    "overnight": 0,
                },
            ],
            "summary": {"trades": 2, "wins": 2, "pnlRub": 6000, "openCount": 0},
            "params": {},
            "meta": {},
        },
    )


def _open_test_tab(page: Page) -> None:
    page.add_init_script(
        """() => {
          localStorage.setItem('moexReplay.viewMode', 'replay');
          localStorage.removeItem('moexReplay.srcFilter');
          localStorage.removeItem('moexReplay.riskFilter');
        }"""
    )
    page.route("**/api/sim/tip1m", _stub_tip1m_mixed)
    page.goto(f"{BASE_URL}/?v={CACHE_BUST}", wait_until="domcontentloaded")
    expect(page.locator("#app")).to_be_visible(timeout=30_000)
    replay_chip = page.locator('.chip-view[data-view="replay"]')
    if "active" not in (replay_chip.get_attribute("class") or ""):
        replay_chip.click()
    expect(replay_chip).to_have_class(re.compile(r"\bactive\b"))


@pytest.mark.ui
def test_src_filter_chips_visible_and_filter_journal(page: Page) -> None:
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    _open_test_tab(page)

    filters = page.locator("#tradesRiskFilters")
    expect(filters).to_be_visible()
    expect(filters.locator('.risk-filter[data-risk-filter="all"]')).to_have_text("Все")
    expect(filters.locator('.risk-filter[data-risk-filter="no-red"]')).to_be_visible()
    expect(filters.locator('.risk-filter[data-risk-filter="hit1"]')).to_be_visible()

    expect(filters.locator('.src-filter[data-src-filter="all"]')).to_have_text("Все src")
    expect(filters.locator('.src-filter[data-src-filter="base"]')).to_have_text("AUTO")
    expect(filters.locator('.src-filter[data-src-filter="addon"]')).to_have_text("добор")
    expect(filters.locator('.src-filter[data-src-filter="extra"]')).to_have_text("экстра")
    expect(filters.locator('.src-filter[data-src-filter="shelf"]')).to_have_text("полка")
    expect(filters.locator('.src-filter[data-src-filter="manual"]')).to_have_text("ручн.")

    page.wait_for_function(
        """() => document.querySelectorAll('#tradesBody tr').length >= 2""",
        timeout=30_000,
    )
    expect(page.locator("#tradesBody tr")).to_have_count(2)

    filters.locator('.src-filter[data-src-filter="addon"]').click()
    expect(filters.locator('.src-filter[data-src-filter="addon"]')).to_have_class(
        re.compile(r"\bactive\b")
    )
    expect(filters.locator('.risk-filter[data-risk-filter="all"]')).to_have_class(
        re.compile(r"\bactive\b")
    )
    expect(page.locator("#tradesBody tr")).to_have_count(1)
    expect(page.locator("#tradesSummaryGrid")).to_contain_text("Фильтр Src «добор»")

    filters.locator('.risk-filter[data-risk-filter="all"]').click()
    expect(filters.locator('.src-filter[data-src-filter="all"]')).to_have_class(
        re.compile(r"\bactive\b")
    )
    expect(page.locator("#tradesBody tr")).to_have_count(2)
    assert errors == []
