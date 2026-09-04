"""tip1m sim must populate hit1/2/3Date like M15 Testing milestones."""
from __future__ import annotations

import numpy as np

from replay.tip_touch import PreparedTips, run_touch_1m_pnl_only, run_touch_1m_trades


def _prep(dates, z, spread) -> PreparedTips:
    n = len(dates)
    return PreparedTips(
        ts_ms=np.arange(n, dtype=np.int64) * 60_000,
        z=np.asarray(z, dtype=np.float64),
        spread=np.asarray(spread, dtype=np.float64),
        day_ord=np.zeros(n, dtype=np.int32),
        session=np.ones(n, dtype=bool),
        trade_dates=list(dates),
        edge_i=np.arange(1, n, dtype=np.int32),
        n=n,
    )


def test_hit_milestones_even_when_tp_off():
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
    ]
    # Enter long i=1 (0 → -1.1 crosses -1); exit i=5 (-0.6 → -0.2 crosses -0.3).
    z = [0.0, -1.1, -1.0, -0.8, -0.6, -0.2]
    # Favorable long move on tip spreads after entry at ~10.02.
    spread = [10.0, 10.0, 10.20, 10.40, 10.50, 10.10]
    r = run_touch_1m_trades(
        _prep(dates, z, spread),
        entry=1.0,
        exit_z=0.3,
        slip=0.02,
        notional=10_000.0,
        take_profit_pct=0.0,
    )
    assert r["summary"]["trades"] == 1
    t = r["trades"][0]
    assert t["hit1Date"] is not None, t
    assert "hit2Date" in t and "hit3Date" in t


def test_tp_close_still_records_hits():
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
    ]
    z = [0.0, -1.1, -1.0, -0.9]
    spread = [10.0, 10.0, 10.50, 10.60]
    r = run_touch_1m_trades(
        _prep(dates, z, spread),
        entry=1.0,
        exit_z=0.3,
        slip=0.02,
        notional=10_000.0,
        take_profit_pct=1.0,
    )
    assert r["summary"]["trades"] >= 1
    t = r["trades"][0]
    assert t["exitReason"] == "tp"
    assert t["hit1Date"] is not None


def test_pnl_only_matches_trades_with_tp():
    """Heatmap fast path must match full trades PnL when TP is on."""
    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
    ]
    z = [0.0, -1.1, -1.0, -0.9, -0.2]
    spread = [10.0, 10.0, 10.50, 10.60, 10.10]
    prep = _prep(dates, z, spread)
    full = run_touch_1m_trades(
        prep, entry=1.0, exit_z=0.3, slip=0.02, notional=10_000.0, take_profit_pct=1.0
    )
    pnl, n = run_touch_1m_pnl_only(
        prep, entry=1.0, exit_z=0.3, slip=0.02, notional=10_000.0, take_profit_pct=1.0
    )
    assert n == int(full["summary"]["trades"])
    assert abs(pnl - float(full["summary"]["pnlRub"])) < 0.02


def test_spread_pnl_only_matches_full_trades():
    """S% heatmap fast path must match run_touch_1m_trades(spread_level_mode)."""
    from replay.tip_touch import run_touch_1m_spread_pnl_only

    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
    ]
    # Short: enter wide cross 5.9→6.3; exit cross 6.0→5.7
    z = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0]
    spread = [5.5, 5.9, 6.3, 6.1, 6.0, 5.7]
    prep = _prep(dates, z, spread)
    levels = {
        "enter_wide": 6.2,
        "exit_wide": 5.8,
        "enter_narrow": 3.2,
        "exit_narrow": 4.0,
    }
    full = run_touch_1m_trades(
        prep,
        entry=1.6,
        exit_z=1.3,
        slip=0.02,
        notional=10_000.0,
        spread_level_mode=True,
        spread_levels=levels,
    )
    pnl, n = run_touch_1m_spread_pnl_only(
        prep,
        enter_wide=6.2,
        exit_wide=5.8,
        enter_narrow=3.2,
        exit_narrow=4.0,
        slip=0.02,
        notional=10_000.0,
    )
    assert n == int(full["summary"]["trades"])
    assert abs(pnl - float(full["summary"]["pnlRub"])) < 0.02
    assert n >= 1


def test_heatmap_tip1m_spread_wide_grid_shared_bars(monkeypatch):
    """S% heatmap returns wide triangle cells; shared prep (no per-cell rebuild)."""
    from replay import tip_touch

    dates = [f"2024-01-02 10:{i:02d}" for i in range(8)]
    z = [0.0] * 8
    spread = [5.4, 5.8, 6.3, 6.1, 5.9, 5.7, 5.6, 5.5]
    prep = _prep(dates, z, spread)

    def _fake_ensure(_csv):
        return prep, {"bars": len(dates), "source": "test"}

    monkeypatch.setattr(tip_touch, "ensure_tip_series", _fake_ensure)
    # Clear hm cache so we don't hit a stale key.
    with tip_touch._lock:
        tip_touch._hm_cache.clear()
        tip_touch._tip_cache["key"] = "test-hm-spread"

    out = tip_touch.heatmap_tip1m(
        csv="m15_tatn_255d.csv",
        entry_min=6.0,
        entry_max=6.4,
        exit_min=5.6,
        exit_max=6.2,
        step=0.2,
        spread_level_mode=True,
        band="wide",
        enter_narrow=3.2,
        exit_narrow=4.0,
    )
    assert out["cellCount"] == len(out["cells"])
    assert out["cellCount"] >= 1
    assert out["meta"]["spreadLevelMode"] is True
    assert out["meta"]["band"] == "wide"
    assert out["meta"]["heatmapEngine"] == "spread_pnl_only_v1"
    for c in out["cells"]:
        assert c["entry"] > c["exit"]
    mark = out["meta"]["prodMark"]
    assert abs(mark["entry"] - 6.2) < 1e-9
    assert abs(mark["exit"] - 5.8) < 1e-9


def test_sim_tip1m_as_live_uses_prod_actions(monkeypatch):
    """«как Прод» tip1m must call build_as_live_tip_actions (History parity)."""
    from datetime import datetime
    from zoneinfo import ZoneInfo

    from replay import tip_touch

    msk = ZoneInfo("Europe/Moscow")
    dates = [
        "2026-07-20 10:00",
        "2026-07-20 10:01",
        "2026-07-20 10:02",
        "2026-07-20 10:03",
    ]
    z = [0.0, -1.2, -1.0, -0.2]
    spread = [10.0, 10.0, 10.1, 10.2]
    prep = _prep(dates, z, spread)
    prep.ts_ms = np.asarray(
        [
            int(datetime.strptime(d, "%Y-%m-%d %H:%M").replace(tzinfo=msk).timestamp() * 1000)
            for d in dates
        ],
        dtype=np.int64,
    )

    def _fake_ensure(_csv):
        return prep, {"mode": "tip1m"}

    called = {"n": 0}

    def _fake_actions(prep_arg, *, window_start_ms=0):
        called["n"] += 1
        return [(1, 1), (3, 3)]  # enter long, exit long

    monkeypatch.setattr(tip_touch, "ensure_tip_series", _fake_ensure)
    monkeypatch.setattr(tip_touch, "build_as_live_tip_actions", _fake_actions)
    tip_touch._tip_cache["key"] = "test-as-live"
    tip_touch._sim_cache.clear()

    live = tip_touch.sim_tip1m(
        csv="m15_tatn_255d.csv",
        entry=1.6,
        exit_z=1.3,
        start="2026-07-20",
        as_live=True,
        replay_prod=False,
    )
    assert called["n"] == 1
    assert live["meta"]["replayProd"] is True
    assert live["meta"]["asLiveActions"] == 2
    assert live["summary"]["trades"] == 1

    tip_touch._sim_cache.clear()
    called["n"] = 0
    geo = tip_touch.sim_tip1m(
        csv="m15_tatn_255d.csv",
        entry=1.0,
        exit_z=0.3,
        start="2026-07-20",
        as_live=False,
        replay_prod=False,
    )
    assert called["n"] == 0
    assert geo["meta"]["replayProd"] is False
    assert geo["summary"]["trades"] >= 1


def test_as_live_chist_uses_account_delta_not_tip_slip():
    """«как Прод»: Чист. = Δ счёта; вал/ком from Prod fills — not optimistic tip±slip."""
    dates = [
        "2026-07-27 07:02",
        "2026-07-27 07:03",
        "2026-07-27 11:52",
        "2026-07-27 11:53",
    ]
    z = [1.5, 1.8, 1.4, 1.25]
    # Tip mid would give a large green if used with slip; Prod fills are tighter.
    spread = [6.56, 6.56, 6.30, 6.30]
    actions = [
        (1, 2, {"entry_spread": 6.396588486140725, "prod_id": 8}),
        (
            3,
            4,
            {
                "exit_spread": 6.245975531229875,
                "gross_rub": 104.90614,
                "commission_rub": 55.72224,
                "overnight_rub": 0.0,
                "model_net_rub": 49.1839,
                "account_delta_rub": -3.82,
                "prod_id": 8,
            },
        ),
    ]
    r = run_touch_1m_trades(
        _prep(dates, z, spread),
        entry=1.6,
        exit_z=1.3,
        slip=0.04,
        notional=10_000.0,
        as_live_actions=actions,
    )
    assert r["summary"]["trades"] == 1
    t = r["trades"][0]
    assert t["netFromAccount"] is True
    assert abs(t["net"] - (-3.82)) < 1e-6
    assert abs(t["gross"] - 104.90614) < 1e-4
    assert abs(t["commission"] - 55.72224) < 1e-4
    assert t["net"] < 0
    assert r["summary"]["wins"] == 0
    assert abs(t["accountAfter"] - (10_000 - 3.82)) < 1e-2
