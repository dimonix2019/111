"""Tesт: целые лоты = депозит×плечо/пара, как живая нога (~66 на 10 000)."""
from __future__ import annotations

import numpy as np

from replay.tip_touch import PreparedTips, run_base_plus_addon, size_test_spread_lots


def test_size_matches_live_ten_thousand():
    # 10_000 × 7 / 1_060 ≈ 66.04 → 66 лотов (как «66 акций TATN на ногу»).
    sz = size_test_spread_lots(deposit_rub=10_000, price_tatn=540.0, price_tatnp=520.0)
    assert sz["lots"] == 66
    assert abs(sz["execution_notional"] - 66 * 1060.0) < 1e-9
    assert abs(sz["deposit"] - 10_000.0) < 1e-9
    assert sz["legacy"] is False
    # 560+540: 10_000×7/1100 = 63.63 → 63, не 10 по ГО.
    sz2 = size_test_spread_lots(deposit_rub=10_000, price_tatn=560.0, price_tatnp=540.0)
    assert sz2["lots"] == 63


def test_size_40k_is_four_times_ten_k():
    # 40_000 × 7 / 1100 = 254.5 → 254, не 80 и не 41 по ГО.
    sz = size_test_spread_lots(deposit_rub=40_000, price_tatn=560.0, price_tatnp=540.0)
    assert sz["lots"] == 254
    assert abs(sz["execution_notional"] - 254 * 1100.0) < 1e-9
    assert abs(sz["deposit"] - 40_000.0) < 1e-9


def test_size_no_eighty_cap():
    sz = size_test_spread_lots(deposit_rub=200_000, price_tatn=560.0, price_tatnp=540.0)
    assert sz["lots"] == 1272
    assert sz["lots"] > 80


def test_size_too_small_is_zero():
    sz = size_test_spread_lots(deposit_rub=100.0, price_tatn=560.0, price_tatnp=540.0)
    assert sz["lots"] == 0
    assert sz["execution_notional"] == 0.0


def test_size_without_prices_is_legacy():
    sz = size_test_spread_lots(deposit_rub=10_000, price_tatn=None, price_tatnp=None)
    assert sz["lots"] is None
    assert sz["legacy"] is True
    assert abs(sz["execution_notional"] - 70_000.0) < 1e-9


def _prep_with_prices(dates, spread, tatn, tatnp) -> PreparedTips:
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


def test_pyramid_trades_have_integer_lots():
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
        prep=_prep_with_prices(dates, spread, 560.0, 540.0),
        slip=0.02,
        notional=10_000.0,
    )
    closed = [t for t in r["trades"] if t.get("status") == "Закрыта"]
    assert closed
    for t in closed:
        assert t.get("quantity_lots") == 63
        assert t.get("lots") == 63
        assert abs(float(t["execution_notional_rub"]) - 63 * 1100.0) < 1e-6
        assert abs(float(t["notional"]) - 10_000.0) < 1e-6
        assert abs(float(t["entry_deposit_rub"]) - 10_000.0) < 1e-6
