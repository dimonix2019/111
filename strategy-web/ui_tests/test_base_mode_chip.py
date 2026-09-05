"""UI: Test «База» chip — cyan, default ON, click sends base=false."""
from __future__ import annotations

import os
import re

import pytest
from playwright.sync_api import Page, Route, expect

BASE_URL = os.environ.get("MOEX_DESK_URL", "http://127.0.0.1:8765").rstrip("/")
CACHE_BUST = "20260905baseChip1"


@pytest.mark.ui
def test_base_chip_default_on_and_sends_base_false(page: Page) -> None:
    requests: list[dict] = []
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    page.add_init_script(
        """() => {
          localStorage.setItem('moexReplay.viewMode', 'replay');
          localStorage.removeItem('moexReplay.baseMode');
          localStorage.removeItem('moexReplay.addonMode27');
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
                    "baseMode": bool(payload.get("base", payload.get("enable_base"))),
                    "addonMode": bool(payload.get("addon_mode")),
                },
                "meta": {
                    "baseMode": bool(payload.get("base", payload.get("enable_base"))),
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

    base = page.locator("#btnBaseMode")
    addon = page.locator("#btnAddon27")
    expect(base).to_be_visible()
    expect(base).to_have_text("База")
    expect(base).to_have_class(re.compile(r"\bchip-base-mode\b"))
    expect(base).to_have_attribute("aria-pressed", "true")
    expect(addon).to_be_visible()
    expect(addon).not_to_have_class(re.compile(r"\bchip-base-mode\b"))
    expect(addon).to_have_attribute("aria-pressed", "false")

    expect(page.locator("#status .badge-base")).to_be_visible(timeout=15_000)

    addon.click()
    expect(addon).to_have_attribute("aria-pressed", "true")
    base_bg = base.evaluate("el => getComputedStyle(el).backgroundColor")
    addon_bg = addon.evaluate("el => getComputedStyle(el).backgroundColor")
    assert base_bg != addon_bg, (base_bg, addon_bg)
    assert "124, 58, 237" not in str(base_bg) and "124,58,237" not in str(base_bg).replace(
        " ", ""
    )

    with page.expect_request(
        lambda request: (
            request.url.endswith("/api/sim/tip1m")
            and (request.post_data_json or {}).get("base") is False
        ),
        timeout=15_000,
    ):
        base.click()
    expect(base).to_have_attribute("aria-pressed", "false")
    page.wait_for_function(
        "() => localStorage.getItem('moexReplay.baseMode') === '0'"
    )
    assert any(req.get("base") is False for req in requests), requests
    assert any(req.get("enable_base") in (0, False) for req in requests), requests
    expect(page.locator("#status .badge-base")).to_have_count(0)
    assert not errors, f"pageerror: {errors}"
