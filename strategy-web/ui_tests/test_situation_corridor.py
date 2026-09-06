"""UI: Ситуация — без узкой зоны; коридор S только если сформирован."""
from __future__ import annotations

import os
import re

import pytest
from playwright.sync_api import Page, Route, expect

BASE_URL = os.environ.get("MOEX_DESK_URL", "http://127.0.0.1:8765").rstrip("/")
CACHE_BUST = "20260906sitCorr1"


def _ok_json(route: Route, payload: dict) -> None:
    route.fulfill(status=200, content_type="application/json", json=payload)


def _desk_payload(corridor: dict | None) -> dict:
    return {
        "ok": True,
        "days": 7,
        "lite": True,
        "spread_level_mode": True,
        "settings": {
            "spread_level_mode": True,
            "auto_execute": False,
            "mode": "prod",
            "entry_z": 1.6,
            "exit_z": 1.3,
            "leverage": 7,
            "entry_deposit_rub": 60000,
            "signal_mode": "tip1m",
        },
        "summary": {
            "z": -0.2,
            "spread": 2.99,
            "trade_date": "2026-09-06 10:00",
            "tatn": 500.0,
            "tatnp": 484.8,
            "online": True,
            "window_count": 10,
            "source": "desk",
        },
        "bars": [],
        "bars_iss": [],
        "bars_mode": "dealer_1m",
        "weekend_monitor": False,
        "monitor": {"running": True},
        "position": "FLAT",
        "spread_levels": {
            "spread_level_mode": True,
            "levels": {
                "enter_wide": 6.1,
                "exit_wide": 5.8,
                "enter_narrow": 3.2,
                "exit_narrow": 4.0,
            },
            "current_label_ru": "узкий",
        },
        "open": None,
        "closed": [],
        "broker": {"mode": "prod", "cash_rub": 98000, "total_rub": 99000},
        "dealer": {"ok": True, "spread": 2.99, "tatn": 500.0, "tatnp": 484.8},
        "regime": {},
        "corridor": corridor,
        "open_stats": None,
        "close_forecast": None,
    }


def _formed_corridor() -> dict:
    return {
        "phase": "formed",
        "label_ru": "сформирован",
        "lo": 2.77,
        "hi": 3.65,
        "width": 0.88,
        "spread": 2.99,
        "dwell_days": 8,
        "bounces": 4,
        "touches_lo": 3,
        "touches_hi": 3,
        "bounds_mode": "calculated",
        "n_days": 12,
        "since_date": "2026-08-25",
        "title": "Коридор 2.77…3.65%",
    }


def _open_situation(page: Page, corridor: dict | None) -> list[str]:
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    page.add_init_script(
        """() => {
          localStorage.setItem('moexReplay.viewMode', 'trade');
          localStorage.setItem('moexReplay.tradeSideTab', 'situation');
        }"""
    )
    page.route("**/api/trade/desk**", lambda route: _ok_json(route, _desk_payload(corridor)))
    page.route("**/api/health**", lambda route: _ok_json(route, {"ok": True}))
    page.route(
        "**/api/sim/tip1m",
        lambda route: _ok_json(route, {"trades": [], "summary": {}, "params": {}, "meta": {}}),
    )
    page.route("**/api/bars**", lambda route: _ok_json(route, {"ok": True, "bars": [], "count": 0}))
    page.goto(f"{BASE_URL}/?v={CACHE_BUST}", wait_until="domcontentloaded")
    expect(page.locator("#app")).to_be_visible(timeout=30_000)

    trade_chip = page.locator('.chip-view[data-view="trade"]')
    if "active" not in (trade_chip.get_attribute("class") or ""):
        trade_chip.click()
    expect(trade_chip).to_have_class(re.compile(r"\bactive\b"))

    sit = page.locator("#tradeSideTabSituation")
    if sit.count() and "active" not in (sit.get_attribute("class") or ""):
        sit.click()
    expect(page.locator("#tradeSideSituationPane")).to_be_visible(timeout=20_000)
    return errors


@pytest.mark.ui
def test_situation_no_narrow_zone_corridor_visible_when_formed(page: Page) -> None:
    errors = _open_situation(page, _formed_corridor())

    nz = page.locator("#tradeNarrowZoneBox")
    assert nz.count() == 0 or not nz.is_visible()
    expect(page.locator("#tradeSideSituationPane")).not_to_contain_text("Узкая зона")
    expect(page.locator("#tradeSideSituationPane")).not_to_contain_text("УЗКАЯ ЗОНА")

    box = page.locator("#tradeCorridorBox")
    expect(box).to_be_visible(timeout=20_000)
    expect(box).to_contain_text("Коридор S")
    expect(box.locator("#tradeCorridorBadge")).to_have_text(re.compile(r"сформирован", re.I))
    expect(box).to_contain_text("Ширина")
    expect(box).to_contain_text("Удержание")
    expect(box).to_contain_text("Отскоки")
    assert not errors, errors


@pytest.mark.ui
@pytest.mark.parametrize(
    "corridor",
    [
        {"phase": "none", "label_ru": "нет"},
        {"phase": "forming", "label_ru": "формируется", "lo": 2.77, "hi": 3.65},
        {"phase": "broken", "label_ru": "нет"},
    ],
)
def test_situation_corridor_hidden_when_not_formed(page: Page, corridor: dict) -> None:
    errors = _open_situation(page, corridor)

    nz = page.locator("#tradeNarrowZoneBox")
    assert nz.count() == 0 or not nz.is_visible()

    box = page.locator("#tradeCorridorBox")
    expect(box).to_be_hidden()
    expect(page.locator("#tradeSideSituationPane")).not_to_contain_text("не сформирован")
    expect(page.locator("#tradeSideSituationPane")).not_to_contain_text("Узкая зона")
    assert not errors, errors
