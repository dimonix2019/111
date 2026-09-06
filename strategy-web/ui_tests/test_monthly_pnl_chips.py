"""UI: monthly PnL bars + Итого follow src-chips; green total is profit, not equity."""
from __future__ import annotations

import os
import re

import pytest
from playwright.sync_api import Page, Route, expect

BASE_URL = os.environ.get("MOEX_DESK_URL", "http://127.0.0.1:8765").rstrip("/")
CACHE_BUST = "20260906monthPnl1"


def _ok_json(route: Route, payload: dict) -> None:
    route.fulfill(status=200, content_type="application/json", json=payload)


def _extra_on(body: dict) -> bool:
    raw = body.get("extreme_addon_mode", body.get("extremeAddonMode"))
    if isinstance(raw, str):
        return raw.strip().lower() in ("1", "true", "yes", "on")
    return bool(raw)


def _payload(extra: bool) -> dict:
    trades = [
        {
            "index": 1,
            "direction": "Long",
            "tag": "main",
            "source": "база",
            "entryDate": "2026-01-12 10:15",
            "exitDate": "2026-01-12 11:00",
            "status": "Закрыта",
            "net": 20000,
            "gross": 20000,
            "commission": 0,
            "overnight": 0,
        }
    ]
    pnl = 20000
    if extra:
        trades.append(
            {
                "index": 2,
                "direction": "Short",
                "tag": "extra",
                "source": "экстра",
                "entryDate": "2026-06-08 11:00",
                "exitDate": "2026-06-08 15:00",
                "status": "Закрыта",
                "net": 80000,
                "gross": 80000,
                "commission": 0,
                "overnight": 0,
            }
        )
        pnl = 100000
    return {
        "trades": trades,
        "summary": {
            "trades": len(trades),
            "wins": len(trades),
            "pnlRub": pnl,
            "retPct": pnl / 100.0,
            "finalEquityRub": 10000 + pnl,
            "openCount": 0,
            "by_tag": {
                "main": {"n": 1, "pnlRub": 20000},
                "addon": {"n": 0, "pnlRub": 0},
                "extra": {"n": 1 if extra else 0, "pnlRub": 80000 if extra else 0},
                "shelf_ff": {"n": 0, "pnlRub": 0},
                "weekend": {"n": 0, "pnlRub": 0},
            },
            "by_tag_mode": "chip_delta",
        },
        "params": {"extremeAddonMode": extra},
        "meta": {},
    }


def _open_test(page: Page) -> None:
    page.add_init_script(
        """() => {
          localStorage.setItem('moexReplay.viewMode', 'replay');
          localStorage.setItem('moexReplay.compound', '1');
          localStorage.removeItem('moexReplay.extremeAddonMode');
          localStorage.removeItem('moexReplay.srcFilter');
        }"""
    )

    def fulfill(route: Route) -> None:
        body = route.request.post_data_json or {}
        _ok_json(route, _payload(_extra_on(body)))

    page.route("**/api/sim/tip1m", fulfill)
    page.goto(f"{BASE_URL}/?v={CACHE_BUST}", wait_until="domcontentloaded")
    expect(page.locator("#app")).to_be_visible(timeout=30_000)
    replay_chip = page.locator('.chip-view[data-view="replay"]')
    if "active" not in (replay_chip.get_attribute("class") or ""):
        replay_chip.click()
    expect(replay_chip).to_have_class(re.compile(r"\bactive\b"))


def _digits(text: str) -> str:
    return re.sub(r"\D+", "", text or "")


@pytest.mark.ui
def test_extra_chip_off_changes_monthly_and_profit_not_equity(page: Page) -> None:
    errors: list[str] = []
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    _open_test(page)

    extra = page.locator("#btnExtra19")
    expect(extra).to_be_visible()
    expect(extra).to_have_attribute("aria-pressed", "false")

    monthly = page.locator("#tradesMonthlyPnl")
    expect(monthly).to_be_visible(timeout=15_000)
    expect(monthly).to_have_attribute("data-pending", "0", timeout=15_000)
    expect(monthly).to_have_attribute("data-month-keys", "2026-01")

    grid = page.locator("#tradesSummaryGrid")
    total = grid.locator(".trades-summary-item.wide .ts-value")
    expect(total).to_contain_text("₽", timeout=15_000)
    expect(grid.locator(".trades-summary-item.wide")).to_have_attribute(
        "data-profit-rub", "20000"
    )
    grid_txt = total.inner_text()
    assert "20000" in _digits(grid_txt)
    assert "30000" not in _digits(grid_txt)

    status = page.locator("#status")
    expect(status).to_contain_text("₽", timeout=15_000)
    status_txt = status.inner_text()
    assert "20000" in _digits(status_txt)
    assert "30000" not in _digits(status_txt)

    keys_before = monthly.get_attribute("data-month-keys")
    extra.click()
    expect(extra).to_have_attribute("aria-pressed", "true")
    expect(monthly).to_have_attribute("data-month-keys", re.compile(r"2026-06"), timeout=15_000)
    keys_after = monthly.get_attribute("data-month-keys")
    assert keys_after != keys_before
    assert "2026-01" in (keys_after or "")
    assert "2026-06" in (keys_after or "")

    expect(grid.locator(".trades-summary-item.wide")).to_have_attribute(
        "data-profit-rub", "100000", timeout=15_000
    )
    grid_txt2 = grid.locator(".trades-summary-item.wide .ts-value").inner_text()
    assert "100000" in _digits(grid_txt2)
    assert "110000" not in _digits(grid_txt2)
    status_txt2 = status.inner_text()
    assert "100000" in _digits(status_txt2)
    assert "110000" not in _digits(status_txt2)
    assert not errors, f"pageerror: {errors}"
