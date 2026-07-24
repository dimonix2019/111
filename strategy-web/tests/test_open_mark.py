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
