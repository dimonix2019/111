"""Запас до маржин-колла: liquid − corrected (как lot_sizing, без резерва на лоты)."""

from __future__ import annotations

from typing import Any

MARGIN_CALL_HEADROOM_GREEN_PCT = 30.0
MARGIN_CALL_HEADROOM_YELLOW_PCT = 10.0


def compute_margin_call_headroom(
    *,
    liquid_portfolio_rub: float | None,
    corrected_margin_rub: float | None,
) -> dict[str, float | str] | None:
    if liquid_portfolio_rub is None or corrected_margin_rub is None:
        return None
    corrected = max(0.0, float(corrected_margin_rub))
    if corrected <= 0.0:
        return None
    liquid = float(liquid_portfolio_rub)
    free = liquid - corrected
    pct = free / corrected * 100.0
    if pct > MARGIN_CALL_HEADROOM_GREEN_PCT:
        zone = "green"
    elif pct >= MARGIN_CALL_HEADROOM_YELLOW_PCT:
        zone = "yellow"
    else:
        zone = "red"
    return {
        "free_rub": round(free, 2),
        "pct": round(pct, 2),
        "zone": zone,
        "liquid_portfolio_rub": liquid,
        "corrected_margin_rub": corrected,
    }


def enrich_margin_payload(margin: dict[str, float] | None) -> dict[str, Any] | None:
    if not margin:
        return margin
    out = dict(margin)
    headroom = compute_margin_call_headroom(
        liquid_portfolio_rub=margin.get("liquid_portfolio_rub"),
        corrected_margin_rub=margin.get("corrected_margin_rub"),
    )
    if headroom:
        out["headroom"] = headroom
    return out
