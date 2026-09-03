"""Spread lot sizing — port of TinkoffSpreadLotSizing.computeSpreadQuantityLots."""

from __future__ import annotations

from dataclasses import dataclass
from math import floor

from live.constants import (
    SPREAD_LOT_COMMISSION_BUFFER_FRACTION,
    SPREAD_LOT_MARGIN_PAIR_FRACTION,
    SPREAD_LOT_MARGIN_RATE_PER_LEG,
    SPREAD_LOT_MAX_LOTS,
    SPREAD_LOT_MIN_LOTS,
    SPREAD_LOT_RESERVE_CASH_FRACTION,
    SPREAD_LOT_RESERVE_MIN_RUB,
)


@dataclass
class SpreadLotSizingResult:
    quantity_lots: int
    cash_rub: float
    reserve_rub: float
    available_rub: float
    go_per_lot_rub: float
    pair_notional_per_lot_rub: float
    price_tatn: float
    price_tatnp: float
    lot_size: int
    execution_notional_rub: float
    liquid_portfolio_rub: float | None = None
    margin_headroom_rub: float | None = None
    lots_from_cash: int = 0
    lots_from_leverage: int = 0
    lots_from_margin_headroom: int = 0


def compute_spread_quantity_lots(
    *,
    cash_rub: float,
    price_tatn: float,
    price_tatnp: float,
    lot_size: int = 1,
    reserve_fraction: float = SPREAD_LOT_RESERVE_CASH_FRACTION,
    reserve_min_rub: float = SPREAD_LOT_RESERVE_MIN_RUB,
    margin_rate_per_leg: float = SPREAD_LOT_MARGIN_RATE_PER_LEG,
    commission_buffer_fraction: float = SPREAD_LOT_COMMISSION_BUFFER_FRACTION,
    min_lots: int = SPREAD_LOT_MIN_LOTS,
    max_lots: int = SPREAD_LOT_MAX_LOTS,
    liquid_portfolio_rub: float | None = None,
    corrected_margin_rub: float | None = None,
    leverage_for_notional: float | None = None,
    margin_pair_fraction: float = SPREAD_LOT_MARGIN_PAIR_FRACTION,
) -> SpreadLotSizingResult:
    lot = max(1, lot_size)
    p_n = max(0.0, price_tatn)
    p_np = max(0.0, price_tatnp)
    if p_n <= 0.0 or p_np <= 0.0:
        raise ValueError("Некорректные цены TATN/TATNP для расчёта лотов")

    pair_notional = (p_n + p_np) * lot
    go_buy = p_n * lot
    go_sell = p_np * lot * margin_rate_per_leg
    commission = pair_notional * commission_buffer_fraction
    go_per_lot = go_buy + go_sell + commission
    margin_per_lot = pair_notional * max(0.01, margin_pair_fraction)

    liquid = liquid_portfolio_rub if liquid_portfolio_rub and liquid_portfolio_rub > 0 else None
    reserve_base = liquid if liquid is not None else cash_rub
    reserve = max(reserve_min_rub, reserve_base * reserve_fraction)
    available = max(0.0, cash_rub - reserve)
    lots_cash = 0 if go_per_lot <= 0 else int(floor(available / go_per_lot))

    leverage = leverage_for_notional if leverage_for_notional and leverage_for_notional >= 1.0 else None
    lots_lev = 0
    if liquid is not None and leverage is not None and pair_notional > 0:
        lots_lev = int(floor(liquid * leverage / pair_notional))

    corrected = max(0.0, corrected_margin_rub) if corrected_margin_rub is not None else None
    headroom = None
    if liquid is not None and corrected is not None:
        headroom = max(0.0, liquid - corrected - reserve)
    lots_head = 0
    if headroom is not None and headroom > 0 and margin_per_lot > 0:
        lots_head = int(floor(headroom / margin_per_lot))

    mostly_flat = liquid is not None and corrected is not None and corrected < liquid * 0.05
    if leverage is not None and liquid is not None and mostly_flat:
        raw = max(lots_cash, lots_lev)
    elif leverage is not None and liquid is not None:
        raw = max(lots_cash, min(lots_lev, lots_head))
    elif headroom is not None:
        raw = max(lots_cash, lots_head)
    else:
        raw = lots_cash

    qty = max(0, min(max_lots, raw))
    exec_notional = (p_n + p_np) * lot * max(1, qty)
    return SpreadLotSizingResult(
        quantity_lots=qty,
        cash_rub=cash_rub,
        reserve_rub=reserve,
        available_rub=available,
        go_per_lot_rub=go_per_lot,
        pair_notional_per_lot_rub=pair_notional,
        price_tatn=p_n,
        price_tatnp=p_np,
        lot_size=lot,
        execution_notional_rub=exec_notional,
        liquid_portfolio_rub=liquid,
        margin_headroom_rub=headroom,
        lots_from_cash=lots_cash,
        lots_from_leverage=lots_lev,
        lots_from_margin_headroom=lots_head,
    )
