"""3 года на Тесте: плохой time не роняет UI («Ошибка загрузки» / isUTCTimestamp)."""
from __future__ import annotations

import os
import re

import pytest
from playwright.sync_api import Page, Route, expect

BASE_URL = os.environ.get("MOEX_DESK_URL", "http://127.0.0.1:8765").rstrip("/")
CACHE_BUST = "20260905utcTs1"

_T0 = 1_704_067_200  # 2023-12-31 12:00 UTC ≈ unix sec


def _bar(i: int, *, date_only: bool = False, zero_ms: bool = False) -> dict:
    sec = _T0 + i * 15 * 60
    if date_only:
        return {
            "timestampMs": 0 if zero_ms else sec * 1000,
            "tradeDate": "2026-03-03",
            "zScore": 0.4,
            "spreadPercent": 3.4,
        }
    return {
        "timestampMs": 0 if zero_ms else sec * 1000,
        "tradeDate": f"2024-01-01 {10 + (i % 8):02d}:{(i * 15) % 60:02d}:00",
        "zScore": 0.2 + (i % 5) * 0.1,
        "spreadPercent": 3.2 + (i % 7) * 0.05,
    }


def _bars_payload() -> dict:
    bars = [_bar(i) for i in range(24)]
    bars.insert(8, _bar(8, date_only=True, zero_ms=True))
    bars.append(_bar(30, date_only=True, zero_ms=False))
    return {
        "ok": True,
        "csv": "m15_tatn_1095d.csv",
        "count": len(bars),
        "bars": bars,
        "first": bars[0]["tradeDate"],
        "last": bars[-1]["tradeDate"],
        "source": "ui_test",
        "dbCount": len(bars),
        "online": False,
        "refreshed": False,
    }


def _stub_bars(route: Route) -> None:
    route.fulfill(status=200, content_type="application/json", json=_bars_payload())


def _stub_bars1m(route: Route) -> None:
    data = _bars_payload()
    data["chartDays"] = 1095
    data["displayStepMin"] = 15
    route.fulfill(status=200, content_type="application/json", json=data)


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
    page.route("**/api/sim/tip1m/**", _stub_tip1m)
    page.route("**/api/bars1m?**", _stub_bars1m)
    page.route("**/api/bars?**", _stub_bars)
    page.goto(f"{BASE_URL}/?v={CACHE_BUST}", wait_until="domcontentloaded")
    expect(page.locator("#app")).to_be_visible(timeout=30_000)
    replay_chip = page.locator('.chip-view[data-view="replay"]')
    if "active" not in (replay_chip.get_attribute("class") or ""):
        replay_chip.click()
    expect(replay_chip).to_have_class(re.compile(r"\bactive\b"))
    expect(page.locator("#testChartPane")).to_be_visible(timeout=30_000)


def _page_errors(errors: list[str]) -> list[str]:
    return [e for e in errors if "isUTCTimestamp" in e or "Ошибка загрузки" in e]


@pytest.mark.ui
def test_three_year_chip_does_not_show_load_error(page: Page) -> None:
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    _open_test_tab(page)

    src = page.evaluate(
        """() => {
          const chart = document.querySelector('script[src*="chart.js"]');
          const core = document.querySelector('script[src*="replay-engine-core.js"]');
          return {
            chart: chart ? chart.getAttribute('src') : '',
            core: core ? core.getAttribute('src') : '',
          };
        }"""
    )
    assert "utcTs1" in (src.get("chart") or ""), f"cache-bust chart.js: {src}"
    assert "utcTs1" in (src.get("core") or ""), f"cache-bust engine-core: {src}"

    date_only = page.evaluate("() => typeof labelToUnixSec === 'function' ? labelToUnixSec('2026-03-03') : null")
    assert isinstance(date_only, int) and date_only > 1_000_000_000, f"date-only unix: {date_only}"

    chip = page.locator('#startPresetChips .chip[data-start-preset="3y"]')
    expect(chip).to_be_visible()
    chip.click()
    page.wait_for_timeout(400)

    loading = page.locator("#loading")
    title = (page.locator("#loadingTitle").text_content() or "") if page.locator("#loadingTitle").count() else ""
    body = loading.text_content() or ""
    hidden = loading.evaluate("el => el.classList.contains('hidden')")
    assert hidden or "Ошибка загрузки" not in (title + body)
    assert "Ошибка загрузки" not in title
    assert "isUTCTimestamp" not in (title + body)
    expect(page.locator("#app")).to_be_visible()
    expect(page.locator("#chart canvas").first).to_be_visible(timeout=20_000)

    state = page.evaluate(
        """() => {
          const c = window.__replayChart;
          const times = (c && c.lastCandleTimes) || [];
          const bad = times.filter((t) => typeof t !== 'number' || !Number.isFinite(t) || t <= 1e9 || !Number.isInteger(t));
          return {
            n: times.length,
            bad: bad.length,
            loadTitle: (document.getElementById('loadingTitle') || {}).textContent || '',
          };
        }"""
    )
    assert state["n"] > 0, "после «3 года» нет свечей"
    assert state["bad"] == 0, f"в график попал не-UTC time: {state}"
    assert "Ошибка загрузки" not in (state["loadTitle"] or "")
    assert not _page_errors(errors), f"pageerror: {errors}"


@pytest.mark.ui
def test_set_replay_skips_bad_times_without_crash(page: Page) -> None:
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    _open_test_tab(page)

    page.wait_for_function(
        "() => !!(window.__replayChart && window.__replayChart.series)",
        timeout=30_000,
    )
    result = page.evaluate(
        """() => {
          const c = window.__replayChart;
          const good = 1_704_067_200;
          const candles = [
            { time: good, open: 1, high: 1.2, low: 0.9, close: 1.1 },
            { time: 0, open: 1, high: 1, low: 1, close: 1 },
            { time: NaN, open: 1, high: 1, low: 1, close: 1 },
            { time: null, open: 1, high: 1, low: 1, close: 1 },
            { time: '2026-03-03', open: 2, high: 2.1, low: 1.9, close: 2 },
            { time: { year: 2026, month: 3, day: 3 }, open: 1, high: 1, low: 1, close: 1 },
            { time: good + 900, open: 1.1, high: 1.3, low: 1.0, close: 1.2 },
          ];
          try {
            c.setReplay({
              candles,
              equity: [{ time: 0, value: 1 }, { time: good, value: 10 }, { time: good + 900, value: 12 }],
              deltaPp: [{ time: good, value: 0.1 }],
              markers: [{ time: 0, position: 'belowBar', color: '#fff', shape: 'circle', text: 'x' }],
              trades: [],
              hlines: [],
              primaryMetric: 'spread',
            });
          } catch (e) {
            return { threw: String(e && e.message || e) };
          }
          const times = c.lastCandleTimes || [];
          return {
            threw: null,
            n: times.length,
            times,
            loadTitle: (document.getElementById('loadingTitle') || {}).textContent || '',
          };
        }"""
    )
    assert result.get("threw") is None, f"setReplay бросил: {result}"
    assert result["n"] >= 2, f"должны остаться валидные свечи: {result}"
    assert all(isinstance(t, (int, float)) and t > 1e9 for t in result["times"])
    assert "Ошибка загрузки" not in (result.get("loadTitle") or "")
    assert not _page_errors(errors), f"pageerror: {errors}"
    expect(page.locator("#app")).to_be_visible()
