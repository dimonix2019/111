"""Прод: лоты = депозит×плечо/пара, без потолка 80."""
from __future__ import annotations

from live.constants import SPREAD_LOT_MAX_LOTS, SPREAD_LOT_MIN_LOTS
from live.lot_sizing import compute_spread_quantity_lots


def _flat(**kwargs):
    return compute_spread_quantity_lots(
        corrected_margin_rub=0.0,
        leverage_for_notional=7.0,
        **kwargs,
    )


def test_prod_cap_is_not_eighty():
    assert SPREAD_LOT_MIN_LOTS == 1
    assert SPREAD_LOT_MAX_LOTS > 80


def test_prod_ten_thousand_matches_test_sixty_six():
    r = _flat(
        cash_rub=10_000,
        price_tatn=540.0,
        price_tatnp=520.0,
        liquid_portfolio_rub=10_000,
    )
    assert r.lots_from_leverage == 66
    assert r.quantity_lots == 66


def test_prod_40k_no_eighty_cap():
    r = _flat(
        cash_rub=40_000,
        price_tatn=560.0,
        price_tatnp=540.0,
        liquid_portfolio_rub=40_000,
    )
    assert r.quantity_lots == 254
    assert r.quantity_lots > 80
    assert r.quantity_lots == r.lots_from_leverage


def test_prod_100k_over_200_lots():
    # пара 1160 ₽, 100k × 7 / 1160 ≈ 603 — как ожидание «>200 на ногу».
    r = _flat(
        cash_rub=100_000,
        price_tatn=580.0,
        price_tatnp=580.0,
        liquid_portfolio_rub=100_000,
    )
    assert r.quantity_lots == 603
    assert r.quantity_lots > 200
    assert r.quantity_lots == r.lots_from_leverage


def test_prod_no_eighty_cap_on_large_deposit():
    r = _flat(
        cash_rub=200_000,
        price_tatn=560.0,
        price_tatnp=540.0,
        liquid_portfolio_rub=200_000,
    )
    assert r.quantity_lots == 1272
    assert r.quantity_lots > 80


def test_prod_does_not_exceed_leverage():
    r = _flat(
        cash_rub=100_000,
        price_tatn=580.0,
        price_tatnp=580.0,
        liquid_portfolio_rub=100_000,
    )
    assert r.quantity_lots <= r.lots_from_leverage
    assert r.lots_from_leverage == 603


def test_prod_too_small_is_zero():
    r = compute_spread_quantity_lots(cash_rub=100.0, price_tatn=560.0, price_tatnp=540.0)
    assert r.quantity_lots == 0
