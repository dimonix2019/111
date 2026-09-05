"""UI: overlay chips sit on the Test chart corner, not in the strategy toolbar."""
from __future__ import annotations

import os
import re

import pytest
from playwright.sync_api import Page, Route, expect

BASE_URL = os.environ.get("MOEX_DESK_URL", "http://127.0.0.1:8765").rstrip("/")
CACHE_BUST = "20260905chartOv2"


def _stub_tip1m(route: Route) -> None:
    payload = route.request.post_data_json or {}
    route.fulfill(
        status=200,
        content_type="application/json",
        json={
            "trades": [],
            "summary": {"trades": 0, "wins": 0, "pnlRub": 0, "openCount": 0},
            "params": payload,
            "meta": {},
        },
    )


def _open_test_tab(page: Page) -> None:
    page.add_init_script(
        """() => {
          localStorage.setItem('moexReplay.viewMode', 'replay');
          localStorage.removeItem('testShowCascade');
          localStorage.removeItem('testShowZoneBands');
          localStorage.removeItem('testShowWideShelves');
        }"""
    )
    page.route("**/api/sim/tip1m", _stub_tip1m)
    page.goto(f"{BASE_URL}/?v={CACHE_BUST}", wait_until="domcontentloaded")
    expect(page.locator("#app")).to_be_visible(timeout=30_000)
    replay_chip = page.locator('.chip-view[data-view="replay"]')
    if "active" not in (replay_chip.get_attribute("class") or ""):
        replay_chip.click()
    expect(replay_chip).to_have_class(re.compile(r"\bactive\b"))
    expect(page.locator("#testChartPane")).to_be_visible(timeout=30_000)


@pytest.mark.ui
def test_chart_overlay_chips_in_test_chart_corner(page: Page) -> None:
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    _open_test_tab(page)

    group = page.locator("#testChartOverlayChips")
    expect(group).to_be_visible()
    expect(page.locator("#testChartPane #testChartOverlayChips")).to_have_count(1)
    expect(page.locator("#addonModeChips #btnShowCascade")).to_have_count(0)
    expect(page.locator("#addonModeChips #btnShowWideShelves")).to_have_count(0)

    cascade = page.locator("#btnShowCascade")
    zones = page.locator("#btnShowZoneBands")
    shelves = page.locator("#btnShowWideShelves")
    expect(cascade).to_be_visible()
    expect(cascade).to_have_text("Каскад")
    expect(cascade).to_have_attribute("aria-pressed", "false")
    expect(zones).to_be_visible()
    expect(zones).to_have_text("Зоны в/н/с")
    expect(zones).to_have_attribute("aria-pressed", "false")
    expect(shelves).to_be_visible()
    expect(shelves).to_have_text("Полки")
    expect(shelves).to_have_attribute("aria-pressed", "true")

    expect(page.locator("#btnCollapseCharts")).to_be_visible()
    expect(page.locator("#btnExpandTestChart")).to_be_visible()

    expect(page.locator("#btnAddon27")).to_be_visible()
    expect(page.locator("#btnExtra19")).to_be_visible()
    expect(page.locator("#btnShelfFloorCeil")).to_be_visible()
    assert page.locator("#btnZoneSwing").count() == 0

    cascade.click()
    expect(cascade).to_have_attribute("aria-pressed", "true")
    page.wait_for_function("() => localStorage.getItem('testShowCascade') === '1'")

    shelves.click()
    expect(shelves).to_have_attribute("aria-pressed", "false")
    page.wait_for_function("() => localStorage.getItem('testShowWideShelves') === '0'")

    zones.click()
    expect(zones).to_have_attribute("aria-pressed", "true")
    page.wait_for_function("() => localStorage.getItem('testShowZoneBands') === '1'")

    assert not errors, f"pageerror: {errors}"
