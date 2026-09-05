"""UI: Short enter 6.1 in Test + Обновить MOEX without Failed to fetch."""
from __future__ import annotations

import os
import re

import pytest
from playwright.sync_api import Page, Route, expect

BASE_URL = os.environ.get("MOEX_DESK_URL", "http://127.0.0.1:8765").rstrip("/")
CACHE_BUST = "20260905short61"


def _ok_json(route: Route, payload: dict) -> None:
    route.fulfill(status=200, content_type="application/json", json=payload)


def _stub_tip1m(route: Route, sink: list[dict]) -> None:
    payload = route.request.post_data_json or {}
    sink.append(payload)
    _ok_json(
        route,
        {
            "trades": [],
            "summary": {"trades": 0, "wins": 0, "pnlRub": 0, "openCount": 0},
            "params": {},
            "meta": {},
        },
    )


@pytest.mark.ui
def test_test_tab_short_enter_61_in_label_and_sim(page: Page) -> None:
    requests: list[dict] = []
    page.add_init_script(
        """() => {
          localStorage.setItem('moexReplay.viewMode', 'replay');
          localStorage.setItem('moexReplay.hmSpreadEnterWide', '6.2');
          localStorage.removeItem('moexReplay.hmSpreadEnterWideV61');
        }"""
    )
    page.route("**/api/sim/tip1m", lambda route: _stub_tip1m(route, requests))
    page.goto(f"{BASE_URL}/?v={CACHE_BUST}", wait_until="domcontentloaded")
    expect(page.locator("#app")).to_be_visible(timeout=30_000)
    replay_chip = page.locator('.chip-view[data-view="replay"]')
    if "active" not in (replay_chip.get_attribute("class") or ""):
        replay_chip.click()
    expect(replay_chip).to_have_class(re.compile(r"\bactive\b"))

    page.wait_for_function(
        """() => {
          const st = document.getElementById('status');
          return !!(st && /S\\s*6[,.]1\\s*\\/\\s*5[,.]8/.test(st.textContent || ''));
        }""",
        timeout=30_000,
    )

    def _has_61(body: dict) -> bool:
        lv = body.get("spread_levels") or {}
        try:
            ew = float(lv.get("enter_wide") if lv.get("enter_wide") is not None else lv.get("spread_enter_wide"))
        except (TypeError, ValueError):
            return False
        return abs(ew - 6.1) < 1e-9

    page.wait_for_timeout(800)
    if not any(_has_61(b) for b in requests):
        page.wait_for_timeout(2500)
    assert any(_has_61(b) for b in requests), requests[-1:] if requests else requests


@pytest.mark.ui
def test_refresh_moex_retries_and_skips_failed_to_fetch(page: Page) -> None:
    tip_reqs: list[dict] = []
    refresh_n = {"n": 0}
    page.add_init_script(
        """() => { localStorage.setItem('moexReplay.viewMode', 'replay'); }"""
    )

    def on_refresh(route: Route) -> None:
        refresh_n["n"] += 1
        if refresh_n["n"] == 1:
            route.abort("failed")
            return
        _ok_json(route, {"ok": True, "days": 7, "bars": []})

    def on_bars(route: Route) -> None:
        _ok_json(
            route,
            {
                "ok": True,
                "count": 1,
                "last": "2026-09-05 18:00",
                "csv": "m15_tatn_255d.csv",
                "source": "sqlite",
                "online": True,
                "bars": [
                    {
                        "tradeDate": "2026-09-05 18:00",
                        "timestampMs": 1_757_080_800_000,
                        "spreadPercent": 6.17,
                        "zScore": 0,
                    }
                ],
            },
        )

    page.route("**/api/sim/tip1m", lambda route: _stub_tip1m(route, tip_reqs))
    page.route("**/api/health", lambda route: _ok_json(route, {"ok": True}))
    page.route("**/api/markets/refresh**", on_refresh)
    page.route("**/api/bars?**", on_bars)
    page.goto(f"{BASE_URL}/?v={CACHE_BUST}", wait_until="domcontentloaded")
    expect(page.locator("#app")).to_be_visible(timeout=30_000)
    replay_chip = page.locator('.chip-view[data-view="replay"]')
    if "active" not in (replay_chip.get_attribute("class") or ""):
        replay_chip.click()
    btn = page.locator("#btnRefreshMoex")
    expect(btn).to_be_visible()
    btn.click()
    status = page.locator("#moexRefreshStatus")
    expect(status).not_to_contain_text("Failed to fetch", timeout=20_000)
    page.wait_for_function(
        """() => {
          const el = document.getElementById('moexRefreshStatus');
          const t = (el && el.textContent) || '';
          return t.indexOf('Failed to fetch') < 0 && (t.indexOf('ОК') >= 0 || t === '');
        }""",
        timeout=25_000,
    )
    assert refresh_n["n"] >= 2
    assert "Failed to fetch" not in (status.text_content() or "")
