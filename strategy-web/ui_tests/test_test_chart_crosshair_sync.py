"""Тест: спред и кривая счёта — связанные crosshair / logical range."""
from __future__ import annotations

import os
import re

import pytest
from playwright.sync_api import Page, Route, expect

BASE_URL = os.environ.get("MOEX_DESK_URL", "http://127.0.0.1:8765").rstrip("/")
CACHE_BUST = "20260905utcTs1"


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
def test_test_spread_and_account_charts_exist(page: Page) -> None:
    """Верхний спред и нижний счёт видны; в ReplayChart есть синхрон range/crosshair."""
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    _open_test_tab(page)

    expect(page.locator("#chart")).to_be_visible()
    expect(page.locator("#pnlChart")).to_be_visible()
    expect(page.locator("#chart canvas").first).to_be_visible(timeout=20_000)
    expect(page.locator("#pnlChart canvas").first).to_be_visible(timeout=20_000)

    hooks = page.evaluate(
        """() => {
          const proto = window.ReplayChart && ReplayChart.prototype;
          return {
            hasClass: typeof window.ReplayChart === 'function',
            hasMove: !!(proto && typeof proto._onCrosshairSyncMove === 'function'),
            hasRange: !!(proto && typeof proto._syncLogicalRangeToCharts === 'function'),
            hasEqualize: !!(proto && typeof proto._equalizePriceScales === 'function'),
            hasReapply: !!(proto && typeof proto._scheduleCrosshairReapply === 'function'),
            instance: !!(window.__replayChart && window.__replayChart.chart && window.__replayChart.pnlChart),
          };
        }"""
    )
    assert hooks["hasClass"], "ReplayChart не загрузился"
    assert hooks["hasMove"], "нет _onCrosshairSyncMove"
    assert hooks["hasRange"], "нет _syncLogicalRangeToCharts"
    assert hooks["hasEqualize"], "нет _equalizePriceScales"
    assert hooks["hasReapply"], "нет _scheduleCrosshairReapply"
    assert hooks["instance"], "нет window.__replayChart с двумя графиками"

    cache = page.evaluate(
        """() => {
          const src = document.querySelector('script[src*="chart.js"]');
          return src ? src.getAttribute('src') : '';
        }"""
    )
    assert "utcTs1" in (cache or ""), f"cache-bust chart.js: {cache}"
    assert not errors, f"pageerror: {errors}"


@pytest.mark.ui
def test_test_spread_crosshair_sets_sync_time(page: Page) -> None:
    """Mousemove по спреду запоминает time и ставит вертикаль на счёт."""
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    _open_test_tab(page)

    page.wait_for_function(
        """() => {
          const c = window.__replayChart;
          return !!(c && c.lastCandleTimes && c.lastCandleTimes.length > 2
            && c._pnlPriceByTime && c._pnlPriceByTime.size > 0);
        }""",
        timeout=45_000,
    )

    box = page.locator("#chart").bounding_box()
    assert box and box["width"] > 40 and box["height"] > 40
    page.mouse.move(box["x"] + box["width"] * 0.55, box["y"] + box["height"] * 0.45)
    page.wait_for_timeout(80)

    state = page.evaluate(
        """() => {
          const c = window.__replayChart;
          if (!c) return null;
          const panes = typeof c._syncPanes === 'function' ? c._syncPanes() : [];
          return {
            time: c._crosshairSyncTime == null ? null : Number(c._crosshairSyncTime),
            source: c._crosshairActiveSource,
            paneIds: panes.map((p) => p.id),
            spreadPts: c._spreadPriceByTime ? c._spreadPriceByTime.size : 0,
            pnlPts: c._pnlPriceByTime ? c._pnlPriceByTime.size : 0,
          };
        }"""
    )
    assert state, "нет __replayChart после hover"
    assert "z" in state["paneIds"] and "pnl" in state["paneIds"]
    assert state["spreadPts"] > 0 and state["pnlPts"] > 0
    assert state["time"] is not None, "после mousemove на спреде нет _crosshairSyncTime"
    assert state["source"] in ("z", "pnl", "delta")
    assert not errors, f"pageerror: {errors}"
