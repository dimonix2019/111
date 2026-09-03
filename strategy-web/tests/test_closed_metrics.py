"""Closed-trade history metrics."""

from __future__ import annotations

from live.closed_metrics import compute_closed_breakdown, compute_path_metrics


def test_resolve_entry_slip_picks_prev_bar():
    from live.open_mark import resolve_entry_slip_pts
    from live.closed_metrics import _parse_ms

    entry = "2026-07-22 07:15"
    prev = "2026-07-22 07:00"
    bars = [
        {
            "tradeDate": prev,
            "timestampMs": _parse_ms(prev),
            "spreadPercent": 4.362722704431232,
        },
        {
            "tradeDate": entry,
            "timestampMs": _parse_ms(entry),
            "spreadPercent": 3.8409,
        },
    ]
    slip, iss = resolve_entry_slip_pts(
        {
            "direction": "LONG",
            "entry_time": entry,
            "entry_spread": 4.379562043795631,
        },
        bars=bars,
    )
    assert iss is not None and abs(iss - 4.362722704431232) < 1e-6
    assert slip is not None and abs(slip - 0.016839339364398853) < 1e-6


def test_compare_trade_fields_uses_spread_pnl_not_wallet():
    """Soft pnl_rub: модель Prod (spread_pnl), не Δ кошелька."""
    from live.parity import compare_trade_fields

    prod = {
        "direction": "LONG",
        "entry_time": "2026-08-03 07:22",
        "exit_time": "2026-08-04 17:43",
        "entry_z": -1.95,
        "exit_z": -0.74,
        "entry_spread": 3.067,
        "exit_spread": 4.093,
        "pnl_rub": 300.86,  # wallet
        "spread_pnl_rub": 636.78,  # model
        "gross_rub": 711.93,
        "commission_rub": 55.52,
        "overnight_rub": 19.63,
        "execution_notional_rub": 69398.6,
    }
    test = {
        "direction": "LONG",
        "entry_time": "2026-08-03 07:22",
        "exit_time": "2026-08-04 17:43",
        "entry_z": -1.95,
        "exit_z": -0.74,
        "entry_spread": 3.067,
        "exit_spread": 4.093,
        "pnl_rub": 636.78,
        "gross_rub": 711.93,
        "commission_rub": 55.52,
        "overnight_rub": 19.63,
    }
    diffs = compare_trade_fields(prod, test)
    pnl_diffs = [d for d in diffs if d["field"] == "pnl_rub"]
    assert not pnl_diffs, pnl_diffs


def test_attach_account_deltas_backfills_before_from_chain():
    """Пустой account_before → предыдущий «После»; явный before (#11) не трогаем."""
    from live.closed_metrics import attach_account_deltas

    trades = [
        {
            "id": 10,
            "entry_time": "2026-07-30 09:10",
            "exit_time": "2026-07-31 23:45",
            "pnl_rub": -27.0,
            "gross_rub": -15.0,
            "commission_rub": 9.0,
            "overnight_rub": 3.0,
            "account_after_rub": 99487.13,
        },
        {
            "id": 11,
            "entry_time": "2026-08-03 07:22",
            "exit_time": "2026-08-04 17:59",
            "pnl_rub": 636.0,
            "gross_rub": 712.0,
            "commission_rub": 56.0,
            "overnight_rub": 20.0,
            "account_before_rub": 99966.94,
            "account_after_rub": 100267.8,
            "account_delta_rub": 300.86,
        },
        {
            "id": 9,
            "entry_time": "2026-07-29 14:47",
            "exit_time": "2026-07-29 15:03",
            "pnl_rub": 4.0,
            "gross_rub": 20.0,
            "commission_rub": 10.0,
            "overnight_rub": 0.0,
            "account_after_rub": 99492.739,
        },
    ]
    out = attach_account_deltas(trades)
    by_id = {t["id"]: t for t in out}
    assert abs(by_id[10]["account_before_rub"] - 99492.739) < 1e-6
    assert abs(by_id[10]["account_delta_rub"] - (99487.13 - 99492.739)) < 1e-6
    assert abs(by_id[11]["account_before_rub"] - 99966.94) < 1e-6
    assert abs(by_id[11]["account_delta_rub"] - 300.86) < 1e-6


def test_attach_account_deltas_prefers_balance():
    from live.closed_metrics import attach_account_deltas

    trades = [
        {
            "id": 7,
            "entry_time": "2026-07-26 15:45",
            "exit_time": "2026-07-26 18:45",
            "pnl_rub": -353.0,
            "account_after_rub": 99756.46,
        },
        {
            "id": 8,
            "entry_time": "2026-07-27 07:03",
            "exit_time": "2026-07-27 11:53",
            "pnl_rub": 49.18,
            "account_after_rub": 99752.64,
        },
    ]
    out = attach_account_deltas(trades)
    by_id = {t["id"]: t for t in out}
    assert by_id[7].get("account_delta_rub") is None
    assert abs(by_id[7]["pnl_rub"] - (-353.0)) < 1e-6
    assert abs(by_id[8]["spread_pnl_rub"] - 49.18) < 1e-6
    assert abs(by_id[8]["account_delta_rub"] - (99752.64 - 99756.46)) < 1e-6
    assert abs(by_id[8]["pnl_rub"] - (99752.64 - 99756.46)) < 1e-6
    # «плюсовая» по спреду, но минус на счёте
    assert by_id[8]["pnl_rub"] < 0


def test_attach_account_deltas_prefers_same_trade_before():
    """Чист. = after − before этой сделки, не previous trade after."""
    from live.closed_metrics import attach_account_deltas

    trades = [
        {
            "id": 10,
            "entry_time": "2026-07-30 09:10",
            "exit_time": "2026-07-31 23:45",
            "pnl_rub": -27.0,
            "gross_rub": -15.0,
            "commission_rub": 9.0,
            "overnight_rub": 3.0,
            "account_after_rub": 99487.13,
        },
        {
            "id": 11,
            "entry_time": "2026-08-03 07:22",
            "exit_time": "2026-08-04 17:59",
            "pnl_rub": 636.0,  # model; will be replaced by account delta
            "gross_rub": 712.0,
            "commission_rub": 56.0,
            "overnight_rub": 20.0,
            "account_before_rub": 99966.94,
            "account_after_rub": 100267.8,
        },
    ]
    out = attach_account_deltas(trades)
    by_id = {t["id"]: t for t in out}
    # same-trade before/after — not chain from #10 (would be ~+781)
    assert abs(by_id[11]["account_delta_rub"] - (100267.8 - 99966.94)) < 1e-6
    assert abs(by_id[11]["pnl_rub"] - (100267.8 - 99966.94)) < 1e-6
    assert abs(by_id[11]["account_delta_rub"] - 300.86) < 1e-6
    # model from gross−comm−ovn
    assert abs(by_id[11]["spread_pnl_rub"] - (712.0 - 56.0 - 20.0)) < 1e-6
    assert abs(by_id[11]["account_delta_rub"] - (100267.8 - 99487.13)) > 100


def test_attach_account_deltas_keeps_explicit_delta():
    from live.closed_metrics import attach_account_deltas

    trades = [
        {
            "id": 11,
            "entry_time": "2026-08-03 07:22",
            "exit_time": "2026-08-04 17:59",
            "pnl_rub": 999.0,
            "gross_rub": 712.0,
            "commission_rub": 56.0,
            "overnight_rub": 20.0,
            "account_after_rub": 100267.8,
            "account_before_rub": 99966.94,
            "account_delta_rub": 300.86,
        }
    ]
    out = attach_account_deltas(trades)
    assert abs(out[0]["account_delta_rub"] - 300.86) < 1e-6
    assert abs(out[0]["pnl_rub"] - 300.86) < 1e-6
    assert abs(out[0]["spread_pnl_rub"] - 636.0) < 1e-6


def test_account_after_from_legs_prefers_total():
    from live.closed_metrics import account_after_from_legs

    legs = [
        {"portfolio_total_rub": 100100.0, "portfolio_cash_rub": 50000.0},
        {"portfolio_total_rub": 100267.8, "portfolio_cash_rub": 100267.8},
    ]
    assert abs(account_after_from_legs(legs) - 100267.8) < 1e-6


def test_closed_breakdown_long_profit():
    m = compute_closed_breakdown(
        {
            "direction": "LONG",
            "entry_spread": 4.0,
            "exit_spread": 4.2,
            "entry_time": "2026-07-20 16:45",
            "exit_time": "2026-07-20 17:00",
        },
        deposit_rub=10_000,
        leverage=7,
    )
    # 0.2 п.п. × 70000 / 100 = 140 gross; comm 56; ovn 0 (same day)
    assert abs(m["gross_rub"] - 140.0) < 1e-6
    assert abs(m["commission_rub"] - 56.0) < 1e-6
    assert abs(m["pnl_rub"] - 84.0) < 1e-6


def test_closed_breakdown_overnight_premium_tier():
    """Без цен ног: непокрытая ≈ eff/2=35k → Премиум 35₽/день (не deposit×(L−1)×0.033%)."""
    m = compute_closed_breakdown(
        {
            "direction": "LONG",
            "entry_spread": 4.0,
            "exit_spread": 4.0,
            "entry_time": "2026-08-05 11:00",
            "exit_time": "2026-08-07 11:00",
            "execution_notional_rub": 70_000,
        },
        deposit_rub=10_000,
        leverage=7,
    )
    # 2 календарных дня × 35
    assert abs(m["overnight_rub"] - 70.0) < 1e-6
    assert abs(m["commission_rub"] - 56.0) < 1e-6
    assert abs(m["pnl_rub"] - (0.0 - 56.0 - 70.0)) < 1e-6
    # старая формула дала бы ~39.6
    old = 10_000 * 6 * 0.00033 * 2
    assert abs(old - 39.6) < 0.01
    assert m["overnight_rub"] > old


def test_closed_breakdown_overnight_short_leg_fills():
    """С лотами/ценами: короткая нога TATNP 68×504.1 → 35₽/день."""
    m = compute_closed_breakdown(
        {
            "direction": "LONG",
            "quantity_lots": 68,
            "entry_tatn": 520.6,
            "entry_tatnp": 504.1,
            "entry_spread": 3.2,
            "exit_spread": 3.2,
            "entry_time": "2026-08-05 11:00",
            "exit_time": "2026-08-06 11:00",
            "execution_notional_rub": 69_876.8,
        },
        deposit_rub=10_000,
        leverage=7,
    )
    assert abs(m["overnight_rub"] - 35.0) < 1e-6


def test_path_min_max_and_hit():
    bars = [
        {"timestampMs": 1_000, "tradeDate": "2026-07-20 16:45", "spreadPercent": 4.0},
        {"timestampMs": 2_000, "tradeDate": "2026-07-20 17:00", "spreadPercent": 4.3},
        {"timestampMs": 3_000, "tradeDate": "2026-07-20 17:15", "spreadPercent": 4.1},
    ]
    # fake ms via parse — use real-looking timestamps by embedding in tradeDate only;
    # compute_path_metrics falls back to parse of tradeDate when timestampMs set.
    trade = {
        "direction": "LONG",
        "entry_spread": 4.0,
        "exit_spread": 4.1,
        "entry_time": "2026-07-20 16:45",
        "exit_time": "2026-07-20 17:15",
    }
    # Build bars with parseable dates only (timestampMs from dates)
    from live.closed_metrics import _parse_ms

    bars2 = []
    for b in bars:
        bars2.append(
            {
                **b,
                "timestampMs": _parse_ms(b["tradeDate"]),
            }
        )
    m = compute_path_metrics(trade, bars2, deposit_rub=10_000, leverage=7)
    assert m["pnl_min_rub"] is not None
    assert m["pnl_max_rub"] is not None
    assert m["pnl_max_rub"] >= m["pnl_min_rub"]
    assert m["hit1_time"] is not None
    assert m["pnl_min_time"] is not None
    assert m["pnl_max_time"] is not None


def test_open_path_minmax_tip1m_shape():
    from live.closed_metrics import compute_open_path_minmax

    open_t = {
        "direction": "LONG",
        "entry_spread": 4.0,
        "entry_time": "2026-07-20 16:45",
        "execution_notional_rub": 70_000,
    }
    bars = [
        {"time": "2026-07-20 16:45", "timestampMs": None, "spread": 4.0},
        {"time": "2026-07-20 17:00", "timestampMs": None, "spread": 3.8},
        {"time": "2026-07-20 17:15", "timestampMs": None, "spread": 4.2},
    ]
    from live.closed_metrics import _parse_ms

    for b in bars:
        b["timestampMs"] = _parse_ms(b["time"])
    mm = compute_open_path_minmax(
        open_t, bars, deposit_rub=10_000, leverage=7, asof_time="2026-07-20 17:15"
    )
    assert mm["pnl_min_rub"] is not None
    assert mm["pnl_max_rub"] is not None
    assert mm["pnl_max_rub"] >= mm["pnl_min_rub"]
    # Long: worst at 3.8, best at 4.2
    assert mm["pnl_min_rub"] < 0
    assert mm["pnl_max_rub"] > 0
    assert mm["pnl_min_time"] == "2026-07-20 17:00"
    assert mm["pnl_max_time"] == "2026-07-20 17:15"


def test_coerce_exit_after_entry():
    from live.closed_metrics import coerce_exit_after_entry

    assert coerce_exit_after_entry("2026-07-29 15:00", "2026-07-29 14:47") == "2026-07-29 15:00"
    assert coerce_exit_after_entry("2026-07-29 14:47", "2026-07-29 15:03") == "2026-07-29 15:03"


def test_broker_adopt_entry_time_picks_earlier_tip():
    from live.closed_metrics import broker_adopt_entry_time

    et = broker_adopt_entry_time(
        {
            "trade_date": "2026-07-29 15:00",
            "tip_trade_date": "2026-07-29 14:47",
        }
    )
    assert et.startswith("2026-07-29 14:47")


def test_exit_time_from_broker_legs():
    from live.closed_metrics import exit_time_from_broker_legs

    legs = [
        {
            "order": {
                "responseMetadata": {
                    "serverTime": "2026-07-29T12:03:19.315981036Z",
                }
            }
        }
    ]
    assert exit_time_from_broker_legs(legs) == "2026-07-29 15:03"


def test_closed_exit_prefers_signal_bar_not_broker_fill():
    """History exit_time = signal bar; broker fill is exit_fill_time (trade #11)."""
    from live.closed_metrics import coerce_exit_after_entry, exit_time_from_broker_legs

    signal_bar = "2026-08-04 17:43"
    legs = [
        {
            "order": {
                "responseMetadata": {
                    # ~17:59 MSK
                    "serverTime": "2026-08-04T14:59:12.000000000Z",
                }
            }
        }
    ]
    fill = exit_time_from_broker_legs(legs)
    assert fill == "2026-08-04 17:59"
    exit_time = coerce_exit_after_entry("2026-08-03 07:22", signal_bar)
    assert exit_time.startswith("2026-08-04 17:43")
    # Fill must not replace signal bar for History/parity alignment.
    assert exit_time[:16] != fill
    exit_fill_time = fill  # stored separately on close
    assert exit_fill_time == "2026-08-04 17:59"


def test_fix_prod_times_keeps_fill():
    from live.parity import _fix_prod_times_from_sim

    prod = {
        "entry_time": "2026-08-03 07:22",
        "exit_time": "2026-08-04 17:59",
        "entry_z": -1.9,
        "exit_z": -0.7,
    }
    sim = {
        "entry_time": "2026-08-03 07:22",
        "exit_time": "2026-08-04 17:43",
        "entry_z": -1.95,
        "exit_z": -0.74,
    }
    patch = _fix_prod_times_from_sim(prod, sim)
    assert patch["exit_time"] == "2026-08-04 17:43"
    assert patch["exit_fill_time"] == "2026-08-04 17:59"
