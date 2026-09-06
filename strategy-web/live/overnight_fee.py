"""T‑Invest плата за перенос непокрытой позиции (ступени тарифа).

Документация брокера:
https://www.tbank.ru/invest/help/brokerage/account/margin/about/

Для парного спреда непокрытая база ≈ рыночная стоимость короткой ноги
(Long → short TATNP; Short → short TATN). Шорт всегда непокрытый;
кэш от продажи шорта обычно финансирует лонг, поэтому полный номинал пары
завышает базу (∼70k → ступень 70₽/день), а брокер на этой сделке берёт 35₽.

Тариф «Премиум»: до 50k → 35₽/день (совпадает с выпиской). Инвестор/Трейдер
в том же диапазоне — 40₽; при необходимости сменить таблицу.
"""

from __future__ import annotations

from typing import Any

# Старая %‑модель (Android / архивные скрипты). Desk mark + Test/sim — ступени Премиум.
OVERNIGHT_FEE_PERCENT_PER_DAY = 0.033

# Тариф «Премиум»: (верхняя граница суммы непокрытой позиции, ₽/день).
# Выше 10 млн — процент от суммы (отдельная таблица).
_PREMIUM_FIXED_TIERS: tuple[tuple[float, float], ...] = (
    (5_000.0, 0.0),
    (50_000.0, 35.0),
    (100_000.0, 70.0),
    (250_000.0, 175.0),
    (500_000.0, 340.0),
    (1_000_000.0, 680.0),
    (2_500_000.0, 1_700.0),
    (5_000_000.0, 3_400.0),
    (10_000_000.0, 6_800.0),
)

_PREMIUM_PCT_TIERS: tuple[tuple[float, float], ...] = (
    (25_000_000.0, 0.066),
    (50_000_000.0, 0.063),
    (float("inf"), 0.055),
)


def short_leg_uncovered_rub(
    *,
    direction: str | None,
    lots: int | float | None,
    fill_tatn: float | None,
    fill_tatnp: float | None,
    notional_rub: float | None = None,
) -> float:
    """Рыночная стоимость короткой ноги пары (база непокрытой позиции)."""
    qty = max(0, int(lots or 0))
    d = (direction or "").upper()
    tn = float(fill_tatn) if fill_tatn is not None else None
    tp = float(fill_tatnp) if fill_tatnp is not None else None
    if qty > 0 and tn is not None and tp is not None and tn > 0 and tp > 0:
        if d.startswith("L"):
            return qty * tp
        if d.startswith("S"):
            return qty * tn
    nom = float(notional_rub) if notional_rub is not None else 0.0
    if nom > 0:
        return nom / 2.0
    return 0.0


def overnight_fee_per_day_rub(uncovered_rub: float) -> float:
    """Ступень тарифа Премиум → ₽/календарный день."""
    u = max(0.0, float(uncovered_rub or 0.0))
    if u <= 0:
        return 0.0
    for limit, fee in _PREMIUM_FIXED_TIERS:
        if u <= limit:
            return float(fee)
    for limit, pct in _PREMIUM_PCT_TIERS:
        if u <= limit:
            return u * (pct / 100.0)
    return u * (0.055 / 100.0)


def overnight_fee_rub(
    *,
    direction: str | None,
    lots: int | float | None,
    fill_tatn: float | None,
    fill_tatnp: float | None,
    notional_rub: float | None,
    days: int,
) -> float:
    """Итого overnight = ступень(короткая нога) × календарные дни."""
    d = max(0, int(days or 0))
    if d <= 0:
        return 0.0
    uncovered = short_leg_uncovered_rub(
        direction=direction,
        lots=lots,
        fill_tatn=fill_tatn,
        fill_tatnp=fill_tatnp,
        notional_rub=notional_rub,
    )
    return overnight_fee_per_day_rub(uncovered) * d


def overnight_fee_from_open(
    open_t: dict[str, Any],
    *,
    fill_tatn: float | None,
    fill_tatnp: float | None,
    days: int,
) -> float:
    return overnight_fee_rub(
        direction=str(open_t.get("direction") or ""),
        lots=open_t.get("quantity_lots"),
        fill_tatn=fill_tatn,
        fill_tatnp=fill_tatnp,
        notional_rub=open_t.get("execution_notional_rub") or open_t.get("notional_rub"),
        days=days,
    )
