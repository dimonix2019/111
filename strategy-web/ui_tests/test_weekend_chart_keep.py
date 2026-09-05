"""Выходные: lite-poll без dealer-баров не должен очищать график Торговли.

Регрессия: renderDesk раньше делал chartBars=[] на weekend без dealer-source,
затем setData([]) в LC — свечи пропадали раз в ~минуту при живых осях.
"""
from __future__ import annotations

import os
import re
import urllib.request

import pytest
from playwright.sync_api import Page, expect

BASE_URL = os.environ.get("MOEX_DESK_URL", "http://127.0.0.1:8765").rstrip("/")
CACHE_BUST = "20260905chartKeep2"


def _make_dealer_bars(n: int = 40) -> list[dict]:
    """Синтетические 1м дилер-бары со спредом (для инъекции в renderDesk)."""
    t0 = 1_725_500_000  # unix sec
    bars = []
    for i in range(n):
        sp = 5.0 + (i % 7) * 0.05
        bars.append({
            "time": t0 + i * 60,
            "timestampMs": (t0 + i * 60) * 1000,
            "spread": sp,
            "spread_open": sp - 0.01,
            "spread_high": sp + 0.02,
            "spread_low": sp - 0.02,
            "source": "tinvest_dealer_1m",
            "z_kind": "dealer_monitor",
            "z": 0.1 * (i % 5),
        })
    return bars


@pytest.fixture(scope="session")
def browser_context_args(browser_context_args):
    return {
        **browser_context_args,
        "viewport": {"width": 1400, "height": 900},
    }


def _open_trade(page: Page) -> None:
    page.add_init_script(
        """() => {
          try { localStorage.setItem('moexReplay.viewMode', 'trade'); } catch (_) {}
        }"""
    )
    page.goto(f"{BASE_URL}/?v={CACHE_BUST}", wait_until="domcontentloaded")
    expect(page.locator("#app")).to_be_visible(timeout=30_000)
    chip = page.locator('.chip-view[data-view="trade"]')
    if chip.count():
        cls = chip.first.get_attribute("class") or ""
        if "active" not in cls:
            chip.first.click()
        expect(chip.first).to_have_class(re.compile(r"\bactive\b"))
    page.wait_for_function(
        "() => !!(window.__deskUiTest && typeof window.__deskUiTest.renderDesk === 'function')",
        timeout=30_000,
    )
    # Показать вкладку и инициализировать графики
    page.evaluate(
        """() => {
          if (window.MoexTrade && typeof MoexTrade.onShow === 'function') {
            MoexTrade.onShow();
          }
        }"""
    )
    page.wait_for_selector("#tradeZChart", timeout=15_000)


@pytest.mark.ui
def test_weekend_empty_poll_does_not_clear_chart(page: Page) -> None:
    """После poll с пустыми/не-dealer барами на weekend график не пустеет."""
    errors: list[str] = []
    page.on("pageerror", lambda e: errors.append(str(e)))

    _open_trade(page)

    dealer_bars = _make_dealer_bars(48)
    seed = page.evaluate(
        """(bars) => {
          const t = window.__deskUiTest;
          window.__deskChartEmptySkip = 0;
          const good = {
            ok: true,
            weekend_monitor: true,
            bars_mode: 'dealer_1m',
            bars,
            bars_iss: [],
            settings: { mode: 'prod', auto_execute: false, signal_mode: 'spread_levels' },
            summary: { online: true, window_count: bars.length, source: 'dealer' },
            position: 'FLAT',
            monitor: { running: true },
            dealer: {
              ok: true, quotes_ok: true, from_cache: true,
              tatn: 700, tatnp: 650, spread: 5.2, bars_count: bars.length,
            },
            spread_levels: {
              levels: { enter_narrow: 3.2, exit_narrow: 4.0, enter_wide: 6.2, exit_wide: 5.8 },
            },
            open: null,
            closed: [],
            broker: null,
          };
          t.rememberGoodChartBars(bars, good);
          t.renderDesk(good);
          return {
            lastGood: t.lastGoodBarCount(),
            lastPaint: t.lastPaintZCount(),
            skip: t.emptySkipCount(),
            canvas: !!document.querySelector('#tradeZChart canvas'),
          };
        }""",
        dealer_bars,
    )
    assert seed["lastGood"] >= 5, f"lastGood не запомнился: {seed}"
    assert seed["lastPaint"] >= 5, f"график не нарисовался: {seed}"
    assert seed["canvas"], "нет canvas в #tradeZChart"
    paint_before = int(seed["lastPaint"])
    skip_before = int(seed["skip"])

    # Имитация minute lite-poll: weekend, без dealer-source, пустые bars
    after_empty = page.evaluate(
        """() => {
          const t = window.__deskUiTest;
          const emptyWeekend = {
            ok: true,
            lite: true,
            weekend_monitor: true,
            bars_mode: 'iss_m15',
            bars: [],
            bars_iss: [],
            settings: { mode: 'prod', auto_execute: false, signal_mode: 'spread_levels' },
            summary: { online: true, window_count: 0, source: 'iss' },
            position: 'FLAT',
            monitor: { running: true, last_message: 'отставание' },
            dealer: {
              ok: true, quotes_ok: true, from_cache: true,
              tatn: 700, tatnp: 650, spread: 5.2,
            },
            partial: true,
            open: null,
            closed: [],
            broker: null,
          };
          // Как refreshImpl: remember на пустом ответе не должен стереть lastGood
          t.rememberGoodChartBars(emptyWeekend.bars || [], emptyWeekend);
          t.renderDesk(emptyWeekend);
          return {
            lastGood: t.lastGoodBarCount(),
            lastPaint: t.lastPaintZCount(),
            skip: t.emptySkipCount(),
            canvas: !!document.querySelector('#tradeZChart canvas'),
            zEmptyMsg: (document.getElementById('tradeZEmptyMsg')?.textContent || '').trim(),
          };
        }"""
    )
    assert after_empty["lastGood"] >= 5, f"lastGood стёрт пустым poll: {after_empty}"
    assert after_empty["lastPaint"] >= 5, f"lastPaint очищен: {after_empty}"
    assert after_empty["lastPaint"] == paint_before, (
        f"серия перезаписана пустым: before={paint_before} after={after_empty}"
    )
    assert after_empty["canvas"], "canvas пропал после пустого weekend poll"
    # Либо early-return skip, либо chartBars=lastGood (paint не empty) — оба ок.
    # Главное: не уйти в setData([]) с нулём точек.
    assert int(after_empty["skip"]) >= skip_before

    # Второй сценарий: weekend + не-dealer бары (без source dealer) — не [] и не wipe
    tipish = []
    t0 = 1_725_500_000
    for i in range(12):
        tipish.append({
            "time": t0 + i * 60,
            "spread": 4.5 + i * 0.01,
            "source": "iss_m15",
        })
    after_nondaeler = page.evaluate(
        """(bars) => {
          const t = window.__deskUiTest;
          const payload = {
            ok: true,
            lite: true,
            weekend_monitor: true,
            bars_mode: '',
            bars,
            bars_iss: [],
            settings: { mode: 'prod' },
            summary: { online: true, window_count: bars.length },
            position: 'FLAT',
            monitor: { running: true },
            dealer: { from_cache: true, tatn: 700, tatnp: 650, spread: 5.1 },
            open: null,
            closed: [],
          };
          t.rememberGoodChartBars(bars, payload);
          t.renderDesk(payload);
          return {
            lastGood: t.lastGoodBarCount(),
            lastPaint: t.lastPaintZCount(),
            skip: t.emptySkipCount(),
          };
        }""",
        tipish,
    )
    assert after_nondaeler["lastPaint"] >= 5, (
        f"не-dealer weekend poll очистил график: {after_nondaeler}"
    )
    assert after_nondaeler["lastGood"] >= 5

    bad = [e for e in errors if "Value is null" in e or "Ошибка скрипта" in e]
    assert not bad, f"pageerror: {bad}"


@pytest.mark.ui
def test_desk_health() -> None:
    with urllib.request.urlopen(f"{BASE_URL}/api/health", timeout=5) as r:
        assert r.status == 200
