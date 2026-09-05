"""UI: Prod Счёт — no Z-risk flags in spread-level mode + pink MANUAL plaque."""
from __future__ import annotations

import os
import re

import pytest
from playwright.sync_api import Page, Route, expect

BASE_URL = os.environ.get("MOEX_DESK_URL", "http://127.0.0.1:8765").rstrip("/")
CACHE_BUST = "20260905manualRisk1"


def _ok_json(route: Route, payload: dict) -> None:
    route.fulfill(status=200, content_type="application/json", json=payload)


def _desk_payload() -> dict:
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
            "spread": 3.11,
            "trade_date": "2026-09-05 19:52",
            "tatn": 500.0,
            "tatnp": 484.8,
        },
        "bars": [],
        "bars_iss": [],
        "bars_mode": "dealer_1m",
        "weekend_monitor": True,
        "monitor": {"running": True},
        "position": "LONG",
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
        "open": {
            "direction": "LONG",
            "quantity_lots": 80,
            "source": "MANUAL",
            "entry_time": "2026-09-05 14:30",
            "entry_spread": 3.13,
            "entry_z": -0.4,
            "execution_notional_rub": 60000,
            "mark": {
                "z_now": -0.2,
                "spread_now": 3.11,
                "unrealized_pnl_rub": -120,
                "net_approx_rub": -120,
                "overnight_rub": 0,
                "overnight_days": 0,
                "hold_hours": 5.4,
                "notional_rub": 60000,
                "lots": 80,
                "risk_score": 3,
                "risk_level": "Elevated",
                "risk_flags": ["Zвх<1", "|Z|<1"],
                "risk_red": False,
                "spread_level_mode": True,
            },
        },
        "closed": [],
        "broker": {
            "mode": "prod",
            "cash_rub": 98259,
            "total_rub": 99715,
        },
        "dealer": {"ok": True, "label": "дилер / выходные", "spread": 3.11},
        "regime": {},
        "open_stats": None,
        "close_forecast": None,
    }


@pytest.mark.ui
def test_prod_account_manual_plaque_and_no_z_risk(page: Page) -> None:
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    page.add_init_script(
        """() => {
          localStorage.setItem('moexReplay.viewMode', 'trade');
          localStorage.setItem('moexReplay.sideTab', 'account');
        }"""
    )

    def on_desk(route: Route) -> None:
        _ok_json(route, _desk_payload())

    page.route("**/api/trade/desk**", on_desk)
    page.route("**/api/health**", lambda route: _ok_json(route, {"ok": True}))
    page.route("**/api/sim/tip1m", lambda route: _ok_json(route, {"trades": [], "summary": {}, "params": {}, "meta": {}}))
    page.route("**/api/bars**", lambda route: _ok_json(route, {"ok": True, "bars": [], "count": 0}))
    page.goto(f"{BASE_URL}/?v={CACHE_BUST}", wait_until="domcontentloaded")
    expect(page.locator("#app")).to_be_visible(timeout=30_000)

    trade_chip = page.locator('.chip-view[data-view="trade"]')
    if "active" not in (trade_chip.get_attribute("class") or ""):
        trade_chip.click()
    expect(trade_chip).to_have_class(re.compile(r"\bactive\b"))

    acc = page.locator("#tradeSideTabAccount")
    if acc.count() and "active" not in (acc.get_attribute("class") or ""):
        acc.click()

    box = page.locator("#tradeOpenBox")
    expect(box).to_be_visible(timeout=20_000)
    expect(box.locator(".trade-manual-badge")).to_have_text("ручная")
    expect(box.locator(".trade-manual-plaque")).to_contain_text("MANUAL")
    expect(box.locator(".trade-open-dir--manual")).to_be_visible()

    risk = box.locator(".trade-risk")
    expect(risk).to_be_visible()
    text = risk.inner_text()
    assert "Zвх" not in text
    assert "Zвx" not in text
    assert "|Z|" not in text
    assert "score 0" in text or "Ok" in text
    assert not errors, errors
