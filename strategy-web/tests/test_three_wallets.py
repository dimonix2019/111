"""Три кошелька 40/30/30 и пол–потолок с кошелька базы."""
from __future__ import annotations

import numpy as np

from replay.tip_touch import PreparedTips, run_base_plus_addon, size_test_spread_lots


def _prep(dates, spread, tatn=560.0, tatnp=540.0) -> PreparedTips:
    n = len(dates)
    return PreparedTips(
        ts_ms=np.arange(n, dtype=np.int64) * 60_000,
        z=np.zeros(n, dtype=np.float64),
        spread=np.asarray(spread, dtype=np.float64),
        day_ord=np.zeros(n, dtype=np.int32),
        session=np.ones(n, dtype=bool),
        trade_dates=list(dates),
        edge_i=np.arange(1, n, dtype=np.int32),
        n=n,
        tatn=np.full(n, tatn, dtype=np.float64),
        tatnp=np.full(n, tatnp, dtype=np.float64),
    )


def _closed(result):
    return [t for t in result["trades"] if t.get("status") == "Закрыта"]


def test_lots_from_leg_wallet_not_100k():
    """Лот = floor(кошелёк_ноги × 7 / пара), не от 100 000."""
    pair = 560.0 + 540.0
    assert size_test_spread_lots(
        deposit_rub=40_000, price_tatn=560.0, price_tatnp=540.0
    )["lots"] == int(np.floor(40_000 * 7 / pair))
    assert size_test_spread_lots(
        deposit_rub=30_000, price_tatn=560.0, price_tatnp=540.0
    )["lots"] == int(np.floor(30_000 * 7 / pair))
    assert int(np.floor(100_000 * 7 / pair)) == 636

    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
        "2024-01-02 10:06",
    ]
    spread = [4.5, 3.2, 2.0, 3.2, 4.0, 4.0, 4.0]
    r = run_base_plus_addon(
        prep=_prep(dates, spread),
        slip=0.02,
        notional=40_000.0,
        main_notional=40_000.0,
        addon_notional=30_000.0,
        extra_notional=30_000.0,
        enable_addon=True,
        enable_extreme=True,
    )
    closed = _closed(r)
    main = next(t for t in closed if t["tag"] == "main")
    addon = next(t for t in closed if t["tag"] == "addon")
    assert main["quantity_lots"] == 254
    assert addon["quantity_lots"] == 190
    assert main["quantity_lots"] != 636
    assert addon["quantity_lots"] != 636
    assert abs(float(main["entry_deposit_rub"]) - 40_000.0) < 1e-6
    assert abs(float(addon["entry_deposit_rub"]) - 30_000.0) < 1e-6


def test_dynamic_pool_main_60_addon_40_extra_remainder():
    """Динамический пул 100k: база 60k, добор 40k; журнал — одна цепь пула."""
    from replay.tip_touch import make_dynamic_pool_deposit_fn

    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
        "2024-01-02 10:06",
    ]
    spread = [4.5, 3.2, 2.0, 3.2, 4.0, 4.0, 4.0]
    r = run_base_plus_addon(
        prep=_prep(dates, spread),
        slip=0.02,
        notional=40_000.0,
        main_notional=40_000.0,
        addon_notional=30_000.0,
        extra_notional=30_000.0,
        enable_addon=True,
        enable_extreme=True,
        leg_deposit_fn=make_dynamic_pool_deposit_fn(
            pool=100_000.0, main_cap=60_000.0, addon_reserve=40_000.0
        ),
    )
    closed = _closed(r)
    main = next(t for t in closed if t["tag"] == "main")
    addon = next(t for t in closed if t["tag"] == "addon")
    assert abs(float(main["notional"]) - 60_000.0) < 1e-6
    assert abs(float(addon["notional"]) - 40_000.0) < 1e-6
    assert abs(float(r["summary"]["accountSeedRub"]) - 100_000.0) < 1e-6
    assert r["params"].get("walletMode") == "dynamic_pool"
    # Одна цепь пула: добор «До» = пул после закрытия базы (не отдельный 30к).
    assert abs(float(main["accountBefore"]) - 100_000.0) < 1e-6
    assert abs(float(addon["accountBefore"]) - float(main["accountAfter"])) < 1e-4


def test_dynamic_pool_caps_grow_with_compound():
    """При капитализации потолки — доли пула, поэтому растут вместе с прибылью."""
    from replay.tip_touch import (
        DYNAMIC_POOL_ADDON_RESERVE_FRAC,
        DYNAMIC_POOL_MAIN_CAP_FRAC,
        make_dynamic_pool_deposit_fn,
        scale_dynamic_pool_caps,
    )

    assert DYNAMIC_POOL_MAIN_CAP_FRAC == 0.60
    assert DYNAMIC_POOL_ADDON_RESERVE_FRAC == 0.40
    mc, ar = scale_dynamic_pool_caps(100_000.0)
    assert abs(mc - 60_000.0) < 1e-6
    assert abs(ar - 40_000.0) < 1e-6

    fn = make_dynamic_pool_deposit_fn(pool=100_000.0)
    flat = {
        "used": 0.0,
        "addon_open": False,
        "realized_by": {"main": 50_000.0},
        "compound": False,
    }
    # Без капитализации прибыль пул не увеличивает: прежние 60k / 40k.
    assert abs(fn("main", flat) - 60_000.0) < 1e-6
    assert abs(fn("addon", flat) - 40_000.0) < 1e-6

    grown = dict(flat, compound=True)
    # Пул 150k: база 90k, резерв добора 60k.
    assert fn("main", grown) > 60_000.0
    assert abs(fn("main", grown) - 90_000.0) < 1e-6
    assert abs(fn("addon", grown) - 60_000.0) < 1e-6
    # Экстра — свободный остаток за вычетом резерва, потолка нет.
    assert abs(fn("extra", grown) - 90_000.0) < 1e-6


def test_plaque_is_sum_of_three_wallets():
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
        "2024-01-02 10:06",
    ]
    spread = [4.5, 3.2, 2.0, 3.2, 4.0, 4.0, 4.0]
    r = run_base_plus_addon(
        prep=_prep(dates, spread),
        slip=0.02,
        notional=40_000.0,
        main_notional=40_000.0,
        addon_notional=30_000.0,
        extra_notional=30_000.0,
    )
    seed = 100_000.0
    pnl = float(r["summary"]["pnlRub"])
    open_mtm = float(r["summary"].get("openMtmRub") or 0)
    assert abs(float(r["summary"]["accountSeedRub"]) - seed) < 1e-6
    assert abs(float(r["summary"]["finalEquityRub"]) - (seed + pnl + open_mtm)) < 1e-4
    main = next(t for t in _closed(r) if t["tag"] == "main")
    addon = next(t for t in _closed(r) if t["tag"] == "addon")
    assert abs(float(main["accountBefore"]) - 40_000.0) < 1e-6
    assert abs(float(addon["accountBefore"]) - 30_000.0) < 1e-6


def test_shelf_closes_base_then_enters_from_40k():
    """Пока база открыта, полка не входит: сначала выход базы, потом вход с 40к."""
    dates = [
        "2024-02-01 10:00",
        "2024-02-01 10:01",
        "2024-02-01 10:02",
        "2024-02-01 10:03",
        "2024-02-01 10:04",
        "2024-02-01 10:05",
        "2024-02-01 10:06",
    ]
    # база Long 4.5→3.2; пол 3.0 пока база ещё открыта (выход базы 4.0)
    spread = [4.5, 3.2, 3.10, 3.00, 3.40, 4.50, 4.50]
    by = {
        d[:10]: {
            "as_of": d[:10],
            "phase": "formed",
            "lo": 3.0,
            "hi": 4.5,
            "width": 1.5,
        }
        for d in dates
    }
    r = run_base_plus_addon(
        prep=_prep(dates, spread),
        slip=0.02,
        notional=40_000.0,
        main_notional=40_000.0,
        addon_notional=30_000.0,
        extra_notional=30_000.0,
        enable_addon=False,
        enable_extreme=False,
        enable_shelf_ff=True,
        shelf_by_date=by,
        take_profit_pct=99.0,
        max_hold_days_no_exit_trend=0.0,
        settle_sec=0.0,
    )
    closed = _closed(r)
    tags = [t["tag"] for t in closed]
    assert "main" in tags
    assert "shelf_ff" in tags
    base = next(t for t in closed if t["tag"] == "main")
    shelf = next(t for t in closed if t["tag"] == "shelf_ff")
    assert base["exitReason"] == "shelf_displace"
    assert base["exitDate"] <= shelf["entryDate"]
    assert not (
        base["entryDate"] <= shelf["entryDate"] < base["exitDate"]
        and shelf["entryDate"] < base["exitDate"]
    )
    assert abs(float(shelf["entry_deposit_rub"]) - 40_000.0) < 1e-6
    assert shelf["quantity_lots"] == 254
    assert shelf["wallet"] == "main"
    assert base["wallet"] == "main"


def test_shelf_does_not_touch_addon_or_extra_wallets():
    dates = [
        "2024-02-01 10:00",
        "2024-02-01 10:01",
        "2024-02-01 10:02",
        "2024-02-01 10:03",
        "2024-02-01 10:04",
        "2024-02-01 10:05",
        "2024-02-01 10:06",
        "2024-02-01 10:07",
        "2024-02-01 10:08",
    ]
    # база Long + добор 2%; пол 1.5 ниже зоны добора — база уходит, добор остаётся
    spread = [4.5, 3.2, 2.0, 1.80, 1.40, 3.20, 4.00, 4.00, 4.00]
    by = {
        d[:10]: {
            "as_of": d[:10],
            "phase": "formed",
            "lo": 1.5,
            "hi": 4.5,
            "width": 3.0,
        }
        for d in dates
    }
    r = run_base_plus_addon(
        prep=_prep(dates, spread),
        slip=0.02,
        notional=40_000.0,
        main_notional=40_000.0,
        addon_notional=30_000.0,
        extra_notional=30_000.0,
        enable_addon=True,
        enable_extreme=True,
        enable_shelf_ff=True,
        shelf_by_date=by,
        take_profit_pct=99.0,
        max_hold_days_no_exit_trend=0.0,
        settle_sec=0.0,
    )
    closed = _closed(r)
    addon = [t for t in closed if t["tag"] == "addon"]
    extra = [t for t in closed if t["tag"] == "extra"]
    shelf = [t for t in r["trades"] if t.get("tag") == "shelf_ff"]
    assert addon, "добор должен остаться своим кошельком"
    assert shelf
    for t in addon:
        assert abs(float(t["accountBefore"]) - 30_000.0) < 1e-6 or t["wallet"] == "addon"
        assert t["wallet"] == "addon"
        assert abs(float(t["entry_deposit_rub"]) - 30_000.0) < 1e-6
    for t in extra:
        assert t["wallet"] == "extra"
        assert abs(float(t["entry_deposit_rub"]) - 30_000.0) < 1e-6
    for t in shelf:
        assert t["wallet"] == "main"
        assert abs(float(t["entry_deposit_rub"]) - 40_000.0) < 1e-6
    # кошелёк добора не уменьшился из-за полки: старт 30к
    a0 = addon[0]
    assert abs(float(a0["accountBefore"]) - 30_000.0) < 1e-6
    # экстра-кошелёк не тронут, если сделок экстра не было
    if not extra:
        assert abs(float(r["params"]["walletExtraRub"]) - 30_000.0) < 1e-6
