"""UI: Test weekend and shelf toggles reach the tip1m API."""
from __future__ import annotations

import os
import re

import pytest
from playwright.sync_api import Page, Route, expect

BASE_URL = os.environ.get("MOEX_DESK_URL", "http://127.0.0.1:8765").rstrip("/")
CACHE_BUST = "20260905weekendShelf1"


@pytest.mark.ui
def test_weekend_and_shelf_toggles_send_flags_without_zone_swing(page: Page) -> None:
    requests: list[dict] = []
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    page.add_init_script(
        """() => {
          localStorage.setItem('moexReplay.viewMode', 'replay');
          localStorage.removeItem('moexReplay.weekendTrading');
          localStorage.removeItem('moexReplay.shelfFloorCeiling');
          localStorage.setItem('moexReplay.transitionSwingMode', '1');
        }"""
    )

    def fulfill_tip_sim(route: Route) -> None:
        payload = route.request.post_data_json or {}
        requests.append(payload)
        route.fulfill(
            status=200,
            content_type="application/json",
            json={
                "trades": [],
                "summary": {
                    "trades": 0,
                    "wins": 0,
                    "pnlRub": 0,
                    "openCount": 0,
                },
                "params": {
                    "weekendTrading": bool(payload.get("weekend_trading")),
                    "shelfFloorCeilingMode": bool(
                        payload.get("shelf_floor_ceiling_mode")
                    ),
                },
                "meta": {
                    "weekendTrading": bool(payload.get("weekend_trading")),
                    "weekendWindowMsk": (
                        "10:00–18:59" if payload.get("weekend_trading") else None
                    ),
                },
            },
        )

    page.route("**/api/sim/tip1m", fulfill_tip_sim)
    page.goto(f"{BASE_URL}/?v={CACHE_BUST}", wait_until="domcontentloaded")
    expect(page.locator("#app")).to_be_visible(timeout=30_000)
    replay_chip = page.locator('.chip-view[data-view="replay"]')
    if "active" not in (replay_chip.get_attribute("class") or ""):
        replay_chip.click()
    expect(replay_chip).to_have_class(re.compile(r"\bactive\b"))

    button = page.locator("#btnWeekendTrading")
    expect(button).to_be_visible()
    expect(button).to_have_text("выходные")
    expect(button).to_have_attribute("aria-pressed", "false")
    shelf = page.locator("#btnShelfFloorCeil")
    expect(shelf).to_be_visible()
    expect(shelf).to_have_text("пол–потолок (полка)")
    expect(shelf).to_have_attribute("aria-pressed", "false")
    assert page.locator("#btnZoneSwing").count() == 0
    assert page.get_by_text("качалка зоны", exact=True).count() == 0

    with page.expect_request(
        lambda request: (
            request.url.endswith("/api/sim/tip1m")
            and bool((request.post_data_json or {}).get("weekend_trading"))
        ),
        timeout=15_000,
    ):
        button.click()
    expect(button).to_have_attribute("aria-pressed", "true")
    page.wait_for_function(
        "() => localStorage.getItem('moexReplay.weekendTrading') === '1'"
    )
    assert "weekend_trading=true" in page.url
    expect(page.locator("#status")).to_contain_text("выходные", timeout=15_000)
    assert any(req.get("weekend_trading") is True for req in requests), requests

    with page.expect_request(
        lambda request: (
            request.url.endswith("/api/sim/tip1m")
            and bool(
                (request.post_data_json or {}).get("shelf_floor_ceiling_mode")
            )
        ),
        timeout=15_000,
    ):
        shelf.click()
    expect(shelf).to_have_attribute("aria-pressed", "true")
    page.wait_for_function(
        "() => localStorage.getItem('moexReplay.shelfFloorCeiling') === '1'"
    )
    assert any(
        req.get("shelf_floor_ceiling_mode") == 1 for req in requests
    ), requests

    expect(page.locator("#tradesTable")).to_be_visible()
    expect(page.locator("#tradesBody")).to_be_attached()
    assert not errors, f"pageerror: {errors}"
