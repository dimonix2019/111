"""Пол–потолок по каузальной широкой полке (только Тест, не AUTO 3.2/6.2)."""
from __future__ import annotations

from datetime import date, timedelta

import numpy as np

from replay.shelf_floor_ceiling import (
    SHELF_FF_HOLD_DAYS,
    SHELF_FF_TP_PCT,
    gap_open_edge_indices,
    parse_shelf_floor_ceiling_mode,
    run_shelf_floor_ceiling,
)
from replay.tip_touch import PreparedTips, _parse_td

_EPOCH = date(1970, 1, 1)


def _ord(ymd: str) -> int:
    y, m, d = (int(x) for x in ymd[:10].split("-"))
    return (date(y, m, d) - _EPOCH).days


def _prep(rows: list[tuple[str, float]]) -> PreparedTips:
    n = len(rows)
    dates = [r[0] for r in rows]
    spread = [r[1] for r in rows]
    ts = np.arange(n, dtype=np.int64) * 60_000
    day_ord = np.asarray([_ord(d) for d in dates], dtype=np.int32)
    return PreparedTips(
        ts_ms=ts,
        z=np.zeros(n, dtype=np.float64),
        spread=np.asarray(spread, dtype=np.float64),
        day_ord=day_ord,
        session=np.ones(n, dtype=bool),
        trade_dates=dates,
        edge_i=np.arange(1, n, dtype=np.int32),
        n=n,
    )


def _prep_gap(rows: list[tuple[str, float]]) -> PreparedTips:
    """Ряд с реальными метками времени (ночной разрыв, без edge_i на gap)."""
    n = len(rows)
    dates = [r[0] for r in rows]
    spread = [r[1] for r in rows]
    ts = np.asarray(
        [int(_parse_td(d).timestamp() * 1000) for d in dates], dtype=np.int64
    )
    day_ord = np.asarray([_ord(d) for d in dates], dtype=np.int32)
    dt = ts[1:] - ts[:-1]
    sp = np.asarray(spread, dtype=np.float64)
    ok = (dt == 60_000) & np.isfinite(sp[1:]) & np.isfinite(sp[:-1])
    edge_i = (np.flatnonzero(ok) + 1).astype(np.int32)
    return PreparedTips(
        ts_ms=ts,
        z=np.zeros(n, dtype=np.float64),
        spread=sp,
        day_ord=day_ord,
        session=np.ones(n, dtype=bool),
        trade_dates=dates,
        edge_i=edge_i,
        n=n,
    )


def _formed(as_of: str, lo: float = 0.0, hi: float = 1.0) -> dict:
    return {
        "as_of": as_of,
        "phase": "formed",
        "lo": lo,
        "hi": hi,
        "width": hi - lo,
    }


def _none(as_of: str) -> dict:
    return {"as_of": as_of, "phase": "none", "lo": None, "hi": None}


def _broken(as_of: str, lo: float = 0.0, hi: float = 1.0) -> dict:
    return {
        "as_of": as_of,
        "phase": "broken",
        "lo": lo,
        "hi": hi,
        "broken_since": as_of,
    }


def _closed(result):
    return [t for t in result["trades"] if t.get("status") == "Закрыта"]


def _run(prep, by_date, **kw):
    return run_shelf_floor_ceiling(
        prep,
        slip=0.02,
        notional=10_000.0,
        by_date=by_date,
        now_ms=10**15,
        settle_sec=0.0,
        **kw,
    )


def test_parse_off_by_default():
    assert parse_shelf_floor_ceiling_mode(None) is False
    assert parse_shelf_floor_ceiling_mode(0) is False
    assert parse_shelf_floor_ceiling_mode("пол–потолок") is True
    assert parse_shelf_floor_ceiling_mode(1) is True


def test_prod_params_are_2pct_and_5_days():
    assert SHELF_FF_TP_PCT == 2.0
    assert SHELF_FF_HOLD_DAYS == 5.0


def test_short_on_ceiling_after_overnight_gap():
    """Gap 23:48→09:59: prev < hi <= cur на первом баре после разрыва → Short."""
    rows = [
        ("2024-02-08 23:48", 0.50),
        ("2024-02-09 09:59", 0.80),
        ("2024-02-09 10:00", 0.75),
        ("2024-02-09 10:01", 0.00),
    ]
    prep = _prep_gap(rows)
    assert 1 not in prep.edge_i
    gap_idx = gap_open_edge_indices(prep)
    assert list(gap_idx) == [1]
    hi = 0.671
    lo = 0.0
    by = {
        "2024-02-08": _formed("2024-02-08", lo=lo, hi=hi),
        "2024-02-09": _formed("2024-02-09", lo=lo, hi=hi),
    }
    r = _run(prep, by, take_profit_pct=99.0, max_hold_days_no_exit_trend=0)
    closed = _closed(r)
    assert len(closed) == 1
    t = closed[0]
    assert t["direction"] == "Short"
    assert t["entryDate"] == "2024-02-09 09:59"
    assert abs(t["entrySpread"] - (0.80 - 0.02)) < 1e-9


def test_short_on_ceiling_exit_on_floor():
    rows = [
        ("2024-02-01 10:00", 0.50),
        ("2024-02-01 10:01", 1.00),  # касание потолка → Short
        ("2024-02-01 10:02", 0.70),
        ("2024-02-01 10:03", 0.00),  # касание пола → выход
        ("2024-02-01 10:04", 0.00),
    ]
    by = {d[:10]: _formed(d[:10]) for d, _ in rows}
    r = _run(_prep(rows), by, take_profit_pct=99.0, max_hold_days_no_exit_trend=0)
    closed = _closed(r)
    assert len(closed) == 1
    t = closed[0]
    assert t["direction"] == "Short"
    assert t["exitReason"] == "shelf_edge"
    assert abs(t["entrySpread"] - (1.00 - 0.02)) < 1e-9
    assert abs(t["exitSpread"] - (0.00 + 0.02)) < 1e-9


def test_real_detector_no_entry_before_15_days():
    """Пока нет ~15 спокойных дней — детектор не даёт formed, входа нет."""
    from live.spread_corridor_wide import WIDE_MIN_DAYS

    start = date(2024, 1, 2)
    rows = []
    for i in range(WIDE_MIN_DAYS - 3):
        d = (start + timedelta(days=i)).isoformat()
        rows.append((f"{d} 10:00", -0.18))
        rows.append((f"{d} 10:01", 0.52))
    r = run_shelf_floor_ceiling(
        _prep(rows),
        slip=0.02,
        notional=10_000.0,
        now_ms=10**15,
        settle_sec=0.0,
        take_profit_pct=99.0,
        max_hold_days_no_exit_trend=0,
    )
    assert r["summary"]["trades"] == 0
    assert r["summary"]["openCount"] == 0


def test_no_entry_until_shelf_formed():
    rows = [
        ("2024-01-02 10:00", 0.50),
        ("2024-01-02 10:01", 1.00),
        ("2024-01-03 10:00", 0.50),
        ("2024-01-03 10:01", 1.00),
        ("2024-01-03 10:02", 1.00),
    ]
    by = {d[:10]: _none(d[:10]) for d, _ in rows}
    r = _run(_prep(rows), by)
    assert _closed(r) == []
    assert r["summary"]["openCount"] == 0


def test_no_peek_future_does_not_create_shelf():
    """Полка на ранней дате не появляется из будущего: входы одинаковы на префиксе и полном ряде."""
    from live.spread_corridor_wide import WIDE_MIN_DAYS, causal_wide_pack_from_extremes

    start = date(2024, 1, 2)
    ping = []
    daily = []
    mins = []
    maxs = []
    for i in range(WIDE_MIN_DAYS + 4):
        d = (start + timedelta(days=i)).isoformat()
        lo, hi = (-0.18, 0.52) if i % 2 == 0 else (0.52, -0.18)
        # два 1м бара в день, чтобы был edge
        ping.append((f"{d} 10:00", lo))
        ping.append((f"{d} 10:01", hi))
        med = (min(lo, hi) + max(lo, hi)) / 2.0
        daily.append((d, med))
        mins.append((d, min(lo, hi)))
        maxs.append((d, max(lo, hi)))
    cut = daily[-1][0]
    prefix_pack = causal_wide_pack_from_extremes(
        daily, touch_mins=mins, touch_maxs=maxs
    )
    spike_d = (start + timedelta(days=WIDE_MIN_DAYS + 20)).isoformat()
    daily2 = daily + [(spike_d, 8.0)]
    mins2 = mins + [(spike_d, 7.5)]
    maxs2 = maxs + [(spike_d, 8.5)]
    full_pack = causal_wide_pack_from_extremes(
        daily2, touch_mins=mins2, touch_maxs=maxs2
    )
    a = prefix_pack["by_date"][cut]
    b = full_pack["by_date"][cut]
    assert a["phase"] == b["phase"] == "formed"
    assert a.get("lo") == b.get("lo")
    assert a.get("hi") == b.get("hi")
    # снимок дня cut не берёт бары после cut
    assert a["as_of"] == cut
    assert b["as_of"] == cut


def test_take_profit_2pct_of_deposit():
    """Short: сдвиг ~0,4 п.п. при плече 7 ≈ 2,8% депозита — закрытие ТП, не кромка."""
    rows = [
        ("2024-02-01 10:00", 1.00),
        ("2024-02-01 10:01", 1.40),  # потолок 1.40 → Short
        ("2024-02-01 10:02", 1.00),  # ещё выше пола 0; ход ~0,4 п.п. → ТП
        ("2024-02-01 10:03", 1.00),
        ("2024-02-01 10:04", 0.00),
    ]
    by = {d[:10]: _formed(d[:10], lo=0.0, hi=1.40) for d, _ in rows}
    r = _run(
        _prep(rows),
        by,
        take_profit_pct=2.0,
        max_hold_days_no_exit_trend=0,
    )
    closed = _closed(r)
    assert closed
    assert closed[0]["exitReason"] == "tp"
    assert closed[0]["direction"] == "Short"
    pct = (closed[0]["net"] / 10_000.0) * 100.0
    assert pct >= 2.0 or closed[0]["hit2Date"]


def test_hold_no_trend_5_calendar_days():
    rows = []
    start = date(2024, 2, 1)
    # день 0: вход Short на потолке
    rows.append(("2024-02-01 10:00", 0.50))
    rows.append(("2024-02-01 10:01", 1.00))
    for i in range(1, 7):
        d = (start + timedelta(days=i)).isoformat()
        rows.append((f"{d} 10:00", 1.02))
        rows.append((f"{d} 10:01", 1.02))
    by = {d[:10]: _formed(d[:10]) for d, _ in rows}
    r = _run(
        _prep(rows),
        by,
        take_profit_pct=99.0,
        max_hold_days_no_exit_trend=5,
    )
    closed = _closed(r)
    assert closed
    t = closed[0]
    assert t["direction"] == "Short"
    assert t["exitReason"] == "hold_no_trend"
    held = _ord(t["exitDate"]) - _ord(t["entryDate"])
    assert held >= 5


def test_exit_on_shelf_break_while_open():
    rows = [
        ("2024-02-01 10:00", 0.50),
        ("2024-02-01 10:01", 1.00),
        ("2024-02-02 10:00", 1.80),
        ("2024-02-02 10:01", 1.90),
        ("2024-02-02 10:02", 2.00),
    ]
    by = {
        "2024-02-01": _formed("2024-02-01"),
        "2024-02-02": _broken("2024-02-02"),
    }
    r = _run(_prep(rows), by, take_profit_pct=99.0, max_hold_days_no_exit_trend=0)
    closed = _closed(r)
    assert len(closed) == 1
    assert closed[0]["exitReason"] == "shelf_break"
    assert closed[0]["direction"] == "Short"


def test_no_pyramid_one_position():
    rows = [
        ("2024-02-01 10:00", 0.50),
        ("2024-02-01 10:01", 1.00),
        ("2024-02-01 10:02", 0.80),
        ("2024-02-01 10:03", 1.05),  # ещё раз потолок — не вторая нога
        ("2024-02-01 10:04", 1.10),
        ("2024-02-01 10:05", 0.00),
        ("2024-02-01 10:06", 0.00),
    ]
    by = {d[:10]: _formed(d[:10]) for d, _ in rows}
    r = _run(_prep(rows), by, take_profit_pct=99.0, max_hold_days_no_exit_trend=0)
    closed = _closed(r)
    shorts = [t for t in r["trades"] if t["direction"] == "Short"]
    assert len(shorts) == 1
    assert closed[0]["direction"] == "Short"
    assert closed[0]["exitReason"] == "shelf_edge"


def test_long_on_floor_exit_on_ceiling():
    rows = [
        ("2024-02-01 10:00", 0.50),
        ("2024-02-01 10:01", 0.00),  # пол → Long
        ("2024-02-01 10:02", 0.40),
        ("2024-02-01 10:03", 1.00),  # потолок → выход
        ("2024-02-01 10:04", 1.00),
    ]
    by = {d[:10]: _formed(d[:10]) for d, _ in rows}
    r = _run(_prep(rows), by, take_profit_pct=99.0, max_hold_days_no_exit_trend=0)
    closed = _closed(r)
    assert closed
    t = closed[0]
    assert t["direction"] == "Long"
    assert t["exitReason"] == "shelf_edge"


def test_merge_keeps_addon_drops_overlap():
    from replay.tip_touch import merge_base_plus_floor_ceiling

    base = {
        "trades": [
            {
                "entryDate": "2024-01-02 10:00",
                "exitDate": "2024-01-02 12:00",
                "tag": "main",
                "status": "Закрыта",
                "net": 20.0,
                "index": 1,
            },
            {
                "entryDate": "2024-01-02 10:30",
                "exitDate": "2024-01-02 11:00",
                "tag": "addon",
                "status": "Закрыта",
                "net": 8.0,
                "index": 2,
            },
        ],
        "summary": {},
        "params": {"addonMode": True},
    }
    extra = {
        "trades": [
            {
                "entryDate": "2024-01-02 10:45",
                "exitDate": "2024-01-02 11:10",
                "tag": "shelf_ff",
                "status": "Закрыта",
                "net": 99.0,
                "index": 1,
            },
            {
                "entryDate": "2024-01-03 10:00",
                "exitDate": "2024-01-03 11:00",
                "tag": "shelf_ff",
                "status": "Закрыта",
                "net": 5.0,
                "index": 2,
            },
        ],
        "summary": {},
        "params": {},
    }
    r = merge_base_plus_floor_ceiling(base, extra, notional=10_000.0)
    tags = [t["tag"] for t in r["trades"]]
    assert "addon" in tags
    assert "main" in tags
    ff = [t for t in r["trades"] if t["tag"] == "shelf_ff"]
    assert len(ff) == 1
    assert abs(float(ff[0]["net"]) - 5.0) < 1e-9
    assert r["params"]["addonMode"] is True
    assert r["params"]["shelfFloorCeilingMode"] is True
    assert r["params"]["shelfFloorCeilingDroppedOverlap"] == 1


def test_sim_tip1m_shelf_flag_does_not_disable_addon():
    from replay.tip_touch import CACHE_1M, sim_tip1m

    if not CACHE_1M.is_file():
        import pytest

        pytest.skip("нет cache_1m_tatn_spread.parquet")
    r = sim_tip1m(
        csv="m15_tatn_1095d.csv",
        entry=1.6,
        exit_z=1.3,
        start="2024-04-01",
        end="2024-04-15",
        spread_level_mode=True,
        addon_mode=True,
        shelf_floor_ceiling_mode=True,
        take_profit_pct=1.0,
        max_hold_days_no_exit_trend=7.0,
    )
    assert r["meta"]["addonMode"] is True
    assert r["meta"]["shelfFloorCeilingMode"] is True
    assert float(r["params"].get("takeProfitPct") or 0) == 1.0
    assert int(r["params"].get("maxHoldDaysNoExitTrend") or 0) == 7
    assert r["params"].get("addonMode") is True


def test_live_helpers_cross_and_exit():
    from replay.shelf_floor_ceiling import shelf_cross_entry, shelf_live_exit_reason

    assert shelf_cross_entry(0.5, 1.0, formed=True, lo=0.0, hi=0.8) == "SHORT"
    assert shelf_cross_entry(0.5, 0.0, formed=True, lo=0.2, hi=1.0) == "LONG"
    assert shelf_cross_entry(0.5, 1.0, formed=False, lo=0.0, hi=0.8) is None
    assert (
        shelf_live_exit_reason(
            is_long=False, cur_sp=0.0, formed=True, lo=0.0, hi=1.0
        )
        == "shelf_edge"
    )
    assert (
        shelf_live_exit_reason(
            is_long=True, cur_sp=0.5, formed=False, lo=0.0, hi=1.0
        )
        == "shelf_break"
    )


def test_prod_shelf_default_off_and_source_tag():
    from live.constants import DEFAULT_SHELF_FLOOR_CEILING_MODE
    from live.store import is_addon_trade, is_shelf_trade

    assert DEFAULT_SHELF_FLOOR_CEILING_MODE is False
    t = {"source": "AUTO_SHELF"}
    assert is_shelf_trade(t)
    assert not is_addon_trade(t)


def test_engine_shelf_entry_off_does_nothing():
    from live import engine

    msg, fired = engine._maybe_shelf_entry_on_tip(
        tip={"spreadPercent": 1.0, "tradeDate": "2024-01-01"},
        prev={"spreadPercent": 0.5},
        settings={"shelf_floor_ceiling_mode": False},
        auto=False,
        msg="",
        result={},
    )
    assert fired is False
    assert msg == ""


def test_engine_shelf_entry_and_exit_when_on(monkeypatch):
    from live import engine, store

    monkeypatch.setattr(engine, "_shelf_formed_for_tip", lambda _tip: (True, 0.0, 0.8))
    monkeypatch.setattr(store, "get_shelf_open_trade", lambda: None)
    monkeypatch.setattr(store, "get_main_open_trade", lambda: None)
    monkeypatch.setattr(engine, "_entry_blocked_by_situation_3d", lambda *_a, **_k: False)
    monkeypatch.setattr(store, "log_event", lambda *_a, **_k: None)
    msg, fired = engine._maybe_shelf_entry_on_tip(
        tip={"spreadPercent": 1.0, "tradeDate": "2024-01-02 10:00"},
        prev={"spreadPercent": 0.5},
        settings={"shelf_floor_ceiling_mode": True},
        auto=False,
        msg="",
        result={},
    )
    assert fired is True
    assert "полка SHORT" in msg

    open_t = {
        "source": "AUTO_SHELF",
        "direction": "SHORT",
        "entry_spread": 0.8,
        "execution_notional_rub": 0,
        "entry_time": "2024-01-02",
    }
    msg2, fired2 = engine._maybe_shelf_exit_on_tip(
        tip={"spreadPercent": 0.0, "tradeDate": "2024-01-02 10:01", "zScore": 0, "timestampMs": 1},
        settings={"max_hold_days_no_exit_trend": 0, "shelf_exit_trend_min_pp": 0.2},
        auto=False,
        entry=0,
        exit_z=0,
        msg="",
        result={},
        open_trade=open_t,
    )
    assert fired2 is True
    assert "кромка" in msg2
