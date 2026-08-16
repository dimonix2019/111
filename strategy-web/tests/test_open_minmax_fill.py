"""Prod open fill overlays geometric tip entry for Min/Max parity with desk."""

from __future__ import annotations

from replay.tip_touch import run_touch_1m_trades
from tests.test_tip_touch_hits import _prep


def test_geometric_open_uses_prod_fill_for_minmax(monkeypatch):
    """Same-minute Prod open fill → S%вх + path Min/Max (not tip±slip)."""
    dates = [
        "2026-08-05 11:08",
        "2026-08-05 11:09",
        "2026-08-05 11:10",
        "2026-08-06 15:10",
        "2026-08-06 19:20",
    ]
    # Cross into narrow Long: prev S>3.2, cur S<=3.2 (spread_level_mode).
    z = [-1.0, -1.5, -1.4, -2.0, -0.5]
    spread = [3.25, 3.193177, 3.20, 2.502933, 3.598922]
    prep = _prep(dates, z, spread)

    open_t = {
        "id": 12,
        "direction": "LONG",
        "entry_time": "2026-08-05 11:09:00",
        "entry_spread": 3.2731600872842685,
        "execution_notional_rub": 69876.8,
        "quantity_lots": 68,
        "entry_tatn": 520.6,
        "entry_tatnp": 504.1,
    }

    monkeypatch.setattr(
        "replay.tip_touch._prod_fill_maps",
        lambda: {("2026-08-05 11:09", "ENTER_LONG"): {
            "entry_spread": 3.2731600872842685,
            "execution_notional_rub": 69876.8,
            "quantity_lots": 68,
            "entry_tatn": 520.6,
            "entry_tatnp": 504.1,
            "prod_id": 12,
        }},
    )

    r = run_touch_1m_trades(
        prep,
        entry=1.6,
        exit_z=1.3,
        slip=0.02,
        notional=10_000.0,
        spread_level_mode=True,
        spread_levels={
            "enter_wide": 6.2,
            "exit_wide": 5.8,
            "enter_narrow": 3.2,
            "exit_narrow": 4.0,
        },
    )
    opens = [t for t in r["trades"] if str(t.get("status") or "") == "Открыта"]
    assert len(opens) == 1
    t = opens[0]
    assert abs(float(t["entrySpread"]) - 3.2731600872842685) < 1e-6
    # tip 3.193 → fill 3.273 adverse display slip
    assert float(t["entrySlip"]) > 0.05
    # Long path: min at low S, max at high S; fill entry lowers MTM vs tip+slip.
    assert float(t["pnlMin"]) < -500
    assert float(t["pnlMax"]) < 200
    assert float(t["pnlMax"]) > 100
