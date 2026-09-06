"""Open trade MTM: broker fills vs ISS snap spread."""

from __future__ import annotations

import json

from live.open_mark import (
    adverse_entry_slip_pts,
    enrich_open_trade,
    fill_prices_from_legs,
    legs_unrealized_rub,
)


def test_adverse_entry_slip_long():
    # fill wider than ISS → adverse for Long
    assert abs(adverse_entry_slip_pts("LONG", 3.84, 4.38) - 0.54) < 1e-9


def test_adverse_entry_slip_short():
    # fill tighter than ISS → adverse for Short
    assert abs(adverse_entry_slip_pts("SHORT", 4.38, 3.84) - 0.54) < 1e-9


def test_pick_iss_near_fill_prefers_prev():
    from live.open_mark import pick_iss_spread_for_slip

    # snap already moved away; prev matches fill → use prev spread
    iss = pick_iss_spread_for_slip(
        snap_spread=3.84,
        snap_tatn=454.2,
        snap_tatnp=437.4,
        prev_spread=4.3627,
        prev_tatn=456.9,
        prev_tatnp=437.8,
        fill_tatn=457.6,
        fill_tatnp=438.4,
    )
    assert iss is not None and abs(iss - 4.3627) < 1e-6
    slip = adverse_entry_slip_pts("LONG", iss, (457.6 - 438.4) / 438.4 * 100.0)
    assert slip is not None and abs(slip - 0.0169) < 0.002


def test_fill_prices_from_legs():
    open_t = {
        "legs_json": json.dumps(
            [
                {
                    "ticker": "TATN",
                    "order": {
                        "executedOrderPrice": {"units": "457", "nano": 600000000},
                    },
                },
                {
                    "ticker": "TATNP",
                    "order": {
                        "executedOrderPrice": {"units": "438", "nano": 400000000},
                    },
                },
            ]
        )
    }
    tatn, tatnp = fill_prices_from_legs(open_t)
    assert tatn == 457.6
    assert tatnp == 438.4


def test_legs_pnl_long_matches_broker_style():
    # buy TATN 457.6, sell TATNP 438.4; now 456.9 / 438.7 → loss
    pnl = legs_unrealized_rub(
        direction="LONG",
        lots=80,
        fill_tatn=457.6,
        fill_tatnp=438.4,
        now_tatn=456.9,
        now_tatnp=438.7,
    )
    assert pnl == 80 * ((456.9 - 457.6) + (438.4 - 438.7))
    assert pnl < 0


def test_enrich_prefers_fills_over_snap_spread():
    open_t = {
        "direction": "LONG",
        "quantity_lots": 80,
        "entry_time": "2026-07-22 07:15",
        "entry_z": -1.65,
        "entry_spread": 3.94,  # ISS snap — too optimistic
        "entry_tatn": 445.4,
        "entry_tatnp": 428.5,
        "execution_notional_rub": 69912,
        "legs_json": json.dumps(
            [
                {
                    "ticker": "TATN",
                    "order": {"executedOrderPrice": {"units": "457", "nano": 600000000}},
                },
                {
                    "ticker": "TATNP",
                    "order": {"executedOrderPrice": {"units": "438", "nano": 400000000}},
                },
            ]
        ),
    }
    out = enrich_open_trade(
        open_t,
        z_now=-1.45,
        spread_now=4.15,  # higher than snap entry → old UI would show +
        trade_date="2026-07-22 07:30",
        tatn_now=456.9,
        tatnp_now=438.7,
    )
    assert out is not None
    m = out["mark"]
    assert m["pnl_source"] == "broker_fills"
    assert m["unrealized_pnl_rub"] < 0
    # old spread_snap path would be positive:
    snap_pnl = 69912 * ((4.15 - 3.94) / 100.0)
    assert snap_pnl > 0


def test_overnight_premium_tier_matches_broker_long_pair():
    """Long 68+68, short TATNP ~34.3k → Премиум 35₽/день; 2д → 70 (не notional×0.033%)."""
    from live.overnight_fee import overnight_fee_rub, overnight_fee_per_day_rub

    uncovered = 68 * 504.1
    assert 5000 < uncovered <= 50000
    assert overnight_fee_per_day_rub(uncovered) == 35.0
    assert (
        overnight_fee_rub(
            direction="LONG",
            lots=68,
            fill_tatn=520.6,
            fill_tatnp=504.1,
            notional_rub=69876.8,
            days=2,
        )
        == 70.0
    )
    # same-day open → 0
    open_t = {
        "direction": "LONG",
        "quantity_lots": 68,
        "entry_time": "2026-08-05 11:09",
        "entry_tatn": 520.6,
        "entry_tatnp": 504.1,
        "execution_notional_rub": 69876.8,
    }
    out = enrich_open_trade(
        open_t,
        z_now=-1.5,
        spread_now=3.2,
        trade_date="2026-08-07 07:00",
        tatn_now=520.0,
        tatnp_now=504.0,
    )
    assert out is not None
    m = out["mark"]
    assert m["overnight_days"] == 2
    assert m["overnight_per_day_rub"] == 35.0
    assert m["overnight_rub"] == 70.0
    assert abs(m["uncovered_rub"] - uncovered) < 0.01
    # old model would be ~46
    old = 69876.8 * 0.00033 * 2
    assert abs(old - 46.12) < 0.1
    assert m["overnight_rub"] > old


def test_spread_mode_skips_z_risk_flags():
    """Prod AUTO is spread-levels: low |Z| must not inflate risk (user: Zвх<1, |Z|<1)."""
    open_t = {
        "direction": "LONG",
        "quantity_lots": 80,
        "source": "MANUAL",
        "entry_time": "2026-09-05 14:30",
        "entry_z": -0.4,
        "entry_spread": 3.13,
        "execution_notional_rub": 60000,
    }
    out = enrich_open_trade(
        open_t,
        z_now=-0.2,
        spread_now=3.11,
        trade_date="2026-09-05 19:52",
        spread_level_mode=True,
        settings={"spread_level_mode": True},
    )
    assert out is not None
    m = out["mark"]
    flags = " ".join(m["risk_flags"])
    assert "Zвх" not in flags
    assert "|Z|" not in flags
    assert m["spread_level_mode"] is True
    # hold 5.4h < 8h, same calendar day → no overnight/hold Z leftovers
    assert m["risk_score"] < 3
    assert m["risk_level"] == "Ok"


def test_enrich_defaults_to_spread_mode_not_z():
    open_t = {
        "direction": "LONG",
        "quantity_lots": 80,
        "entry_time": "2026-09-05 14:30",
        "entry_z": -0.4,
        "entry_spread": 3.13,
        "execution_notional_rub": 60000,
    }
    out = enrich_open_trade(
        open_t,
        z_now=-0.2,
        spread_now=3.11,
        trade_date="2026-09-05 19:52",
    )
    flags = " ".join(out["mark"]["risk_flags"])
    assert "Zвх" not in flags
    assert "|Z|" not in flags


def test_z_mode_keeps_z_risk_flags():
    open_t = {
        "direction": "LONG",
        "quantity_lots": 80,
        "entry_time": "2026-09-05 14:30",
        "entry_z": -0.4,
        "entry_spread": 3.13,
        "execution_notional_rub": 60000,
    }
    out = enrich_open_trade(
        open_t,
        z_now=-0.2,
        spread_now=3.11,
        trade_date="2026-09-05 19:52",
        spread_level_mode=False,
        settings={"spread_level_mode": False},
    )
    assert out is not None
    flags = out["mark"]["risk_flags"]
    assert "Zвх<1" in flags
    assert "|Z|<1" in flags
    assert out["mark"]["risk_score"] == 3
    assert out["mark"]["risk_level"] == "Elevated"


def test_spread_mode_weak_entry_flag():
    open_t = {
        "direction": "LONG",
        "quantity_lots": 80,
        "entry_time": "2026-09-05 10:00",
        "entry_z": -0.5,
        "entry_spread": 3.12,
        "execution_notional_rub": 60000,
    }
    out = enrich_open_trade(
        open_t,
        z_now=0.0,
        spread_now=3.20,
        trade_date="2026-09-05 17:00",
        spread_level_mode=True,
    )
    flags = out["mark"]["risk_flags"]
    assert "S≈вход" in flags
    assert "Zвх<1" not in flags
    assert "|Z|<1" not in flags
