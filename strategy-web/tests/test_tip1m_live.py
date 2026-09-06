"""Prod tip1m Mode B: consecutive 1m edges + TP exit semantics."""
from __future__ import annotations

from live.constants import MONITOR_TIP1M_SETTLE_SEC
from live.signals import Position, Signal, determine_z_signal
from live.tip_touch_signals import (
    M1_MS,
    collect_tip1m_sim_edges,
    is_consecutive_1m,
    is_tip1m_settled,
    plan_tip1m_catchup,
    should_exit_take_profit,
    tip1m_mtm_pct_of_deposit,
)


def _tip(td: str, ms: int, z: float, sp: float = 4.0) -> dict:
    return {
        "tradeDate": td,
        "timestampMs": ms,
        "zScore": z,
        "spreadPercent": sp,
    }


def test_consecutive_1m():
    assert is_consecutive_1m(1_000_000, 1_060_000)
    assert not is_consecutive_1m(1_000_000, 1_120_000)
    assert not is_consecutive_1m(0, 60_000)


def test_tip1m_settle_is_short_not_90():
    t0 = 1_700_000_000_000
    settle_ms = int(MONITOR_TIP1M_SETTLE_SEC * 1000)
    assert settle_ms == 10_000
    # Mid-minute: not settled
    assert is_tip1m_settled(t0, t0 + 30_000, settle_sec=MONITOR_TIP1M_SETTLE_SEC) is False
    # Just after close, before tip1m settle
    assert is_tip1m_settled(t0, t0 + M1_MS + 5_000, settle_sec=MONITOR_TIP1M_SETTLE_SEC) is False
    # After close + tip1m settle
    assert is_tip1m_settled(t0, t0 + M1_MS + settle_ms, settle_sec=MONITOR_TIP1M_SETTLE_SEC) is True
    # Next tip present → settled without waiting settle window
    assert is_tip1m_settled(t0, t0 + 30_000, has_next_bar=True) is True
    # Must never require M15-style 90s
    assert is_tip1m_settled(t0, t0 + M1_MS + 15_000, settle_sec=MONITOR_TIP1M_SETTLE_SEC) is True


def test_collect_tip1m_sim_edges_waits_settle_like_prod():
    """Test geometric edges shift with settle like Prod: last tip waits close+10s."""
    from live.tip_touch_signals import filter_settled_tips

    base = 1_700_000_000_000
    tips = [
        _tip("2026-07-24 11:00:00", base, 1.0),
        _tip("2026-07-24 11:01:00", base + M1_MS, 1.7),  # ENTER_SHORT if settled
    ]
    settle_ms = int(MONITOR_TIP1M_SETTLE_SEC * 1000)
    last_ms = tips[1]["timestampMs"]
    # After last tip close, before settle: forming tip dropped → no edge
    now_early = last_ms + M1_MS + 5_000
    assert filter_settled_tips(tips, now_early) == [tips[0]]
    assert (
        collect_tip1m_sim_edges(
            tips, entry=1.6, exit_z=1.3, respect_live_signal=False, now_ms=now_early
        )
        == []
    )
    # After close+settle: edge appears (decision available only then)
    now_ok = last_ms + M1_MS + settle_ms
    edges = collect_tip1m_sim_edges(
        tips, entry=1.6, exit_z=1.3, respect_live_signal=False, now_ms=now_ok
    )
    assert len(edges) == 1
    assert edges[0]["signal"] == Signal.ENTER_SHORT.value
    assert edges[0]["bar_ms"] == last_ms
    # With next tip present, settle wait is skipped (Prod has_next_bar shortcut)
    tips3 = tips + [_tip("2026-07-24 11:02:00", base + 2 * M1_MS, 1.8)]
    edges_next = collect_tip1m_sim_edges(
        tips3, entry=1.6, exit_z=1.3, respect_live_signal=False, now_ms=now_early
    )
    assert len(edges_next) >= 1
    assert edges_next[0]["bar_ms"] == last_ms


def test_plan_tip1m_catchup_live_and_bootstrap():
    base = 1_700_000_000_000
    tips = [
        _tip(f"2026-07-25 10:{i:02d}:00", base + i * 60_000, 0.1 * i)
        for i in range(5)
    ]
    mode, edges = plan_tip1m_catchup(tips, 0)
    assert mode == "bootstrap"
    assert edges == []

    last = tips[1]["timestampMs"]
    mode, edges = plan_tip1m_catchup(tips, last, max_edges=10)
    assert mode == "live"
    assert len(edges) == 3
    assert edges[0][0]["timestampMs"] == last
    assert edges[0][1]["timestampMs"] == tips[2]["timestampMs"]

    mode, edges = plan_tip1m_catchup(tips, tips[-1]["timestampMs"])
    assert mode == "up_to_date"


def test_plan_tip1m_catchup_gap_recovers_first_cross():
    """After a feed hole, do not skip_gap to the tail — catch up consecutive tips.

    Repro trade #11 morning: last_proc before open, tips reappear with 07:00…;
    old skip_gap jumped to 07:21 and missed ENTER on 07:01 cross.
    """
    base = 1_785_730_800_000  # 2026-08-03 07:00 MSK-ish synthetic
    # Window starts after last_proc (outside window) — classic morning recover.
    tips = [
        _tip("2026-08-03 07:00:00", base, 0.0, 3.50),
        _tip("2026-08-03 07:01:00", base + M1_MS, 0.0, 3.09),  # first cross
        _tip("2026-08-03 07:02:00", base + 2 * M1_MS, 0.0, 3.05),
        _tip("2026-08-03 07:21:00", base + 21 * M1_MS, 0.0, 3.00),  # hole then resume
        _tip("2026-08-03 07:22:00", base + 22 * M1_MS, 0.0, 2.95),
    ]
    last_proc_before_window = base - 12 * 60 * 60 * 1000  # previous evening
    mode, edges = plan_tip1m_catchup(tips, last_proc_before_window, max_edges=90)
    assert mode == "live"
    assert edges[0][0]["timestampMs"] == tips[0]["timestampMs"]
    assert edges[0][1]["timestampMs"] == tips[1]["timestampMs"]
    # Chain stops at the mid-window hole (07:02 → 07:21), not jump to 07:22.
    assert edges[-1][1]["timestampMs"] == tips[2]["timestampMs"]
    assert len(edges) == 2

    # Mid-stream hole relative to last_proc: resume on first consecutive pair after gap.
    mode2, edges2 = plan_tip1m_catchup(tips, tips[2]["timestampMs"], max_edges=90)
    assert mode2 == "live"
    assert edges2[0][0]["timestampMs"] == tips[3]["timestampMs"]
    assert edges2[0][1]["timestampMs"] == tips[4]["timestampMs"]


def test_plan_tip1m_catchup_skip_gap_only_without_consecutive():
    tips = [
        _tip("2026-08-03 07:00:00", 1_000_000, 0.0),
        _tip("2026-08-03 07:05:00", 1_000_000 + 5 * M1_MS, 0.0),  # not consecutive
    ]
    mode, edges = plan_tip1m_catchup(tips, 500_000, max_edges=10)
    assert mode == "skip_gap"
    assert edges == []


def test_tip1m_edge_enter_short_like_testing():
    # Flat: prev Z < +entry and cur >= +entry → ENTER_SHORT (weekday session)
    tips = [
        _tip("2026-07-24 11:00:00", 1_000_000, 1.0),
        _tip("2026-07-24 11:01:00", 1_060_000, 1.7),
    ]
    edges = collect_tip1m_sim_edges(tips, entry=1.6, exit_z=1.3, respect_live_signal=False)
    assert len(edges) == 1
    assert edges[0]["signal"] == Signal.ENTER_SHORT.value


def test_determine_z_same_as_tip_geometry():
    sig = determine_z_signal(1.0, 1.7, Position.FLAT, 1.6, 1.3)
    assert sig == Signal.ENTER_SHORT
    sig = determine_z_signal(-1.0, -1.7, Position.FLAT, 1.6, 1.3)
    assert sig == Signal.ENTER_LONG


def test_tp_pct_matches_testing_leverage_semantics():
    # deposit 10k, lev 7, long, +0.2 pp → mtm = 10k*7*0.002 = 140; pct = 1.4%
    pct = tip1m_mtm_pct_of_deposit(
        direction="LONG",
        entry_spread=4.0,
        cur_spread=4.2,
        deposit_rub=10_000,
        leverage=7,
    )
    assert abs(pct - 1.4) < 1e-9
    assert should_exit_take_profit(
        position=Position.LONG,
        entry_spread=4.0,
        cur_spread=4.2,
        take_profit_pct=1.0,
        deposit_rub=10_000,
        leverage=7,
    )
    assert not should_exit_take_profit(
        position=Position.LONG,
        entry_spread=4.0,
        cur_spread=4.2,
        take_profit_pct=2.0,
        deposit_rub=10_000,
        leverage=7,
    )
    assert not should_exit_take_profit(
        position=Position.LONG,
        entry_spread=4.0,
        cur_spread=4.2,
        take_profit_pct=0,
        deposit_rub=10_000,
        leverage=7,
    )


def test_desk_tip1m_filter_and_1m_cache_days():
    """Period chips need multi-day parquet — live 1m cache is 1Д only."""
    from live.tip_touch_signals import (
        _filter_chart_by_days,
        _merge_tip_charts,
        try_desk_tip1m_from_1m_cache,
    )

    base_ms = 1_700_000_000_000
    day = 86_400_000
    bars = [
        {"timestampMs": base_ms, "time": "a", "z": 0.1, "source": "tip1m"},
        {"timestampMs": base_ms + day, "time": "b", "z": 0.2, "source": "tip1m"},
        {"timestampMs": base_ms + 3 * day, "time": "c", "z": 0.3, "source": "tip1m"},
    ]
    got = _filter_chart_by_days(bars, 2)
    assert len(got) == 2
    assert got[0]["timestampMs"] == base_ms + day

    merged = _merge_tip_charts(
        bars[:2],
        [{"timestampMs": base_ms + day, "time": "b2", "z": 9.0, "source": "tip1m"}],
    )
    assert len(merged) == 2
    assert merged[1]["z"] == 9.0

    assert try_desk_tip1m_from_1m_cache([], days=7) == []
    assert try_desk_tip1m_from_1m_cache([], days=30) == []


def test_peek_desk_tip1m_rejects_stale_and_pq_when_need_live():
    """Stale parquet peek → None; need_live skips :pq so full never eats lite cache."""
    import time as _time

    from live import tip_touch_signals as tts

    now_ms = int(_time.time() * 1000)
    fresh = [
        {
            "timestampMs": now_ms - 60_000,
            "time": "2026-07-30 15:00:00",
            "z": 0.1,
            "spread": 3.0,
            "source": "tip1m",
        }
    ]
    stale = [
        {
            "timestampMs": now_ms - 2 * 86_400_000,
            "time": "2026-07-28 21:52:00",
            "z": 0.1,
            "spread": 4.9,
            "source": "tip1m",
        }
    ]
    with tts._DESK_TIP_LOCK:
        tts._DESK_TIP_CACHE["key"] = "7:pq"
        tts._DESK_TIP_CACHE["ts"] = _time.time()
        tts._DESK_TIP_CACHE["bars"] = list(stale)
    assert tts.peek_desk_tip1m_chart_bars(7, need_live=True) is None
    assert tts.peek_desk_tip1m_chart_bars(7, need_live=False) is None  # stale tail
    assert tts.desk_tip1m_tail_stale(stale, now_ms=now_ms) is True
    assert tts.desk_tip1m_tail_stale(fresh, now_ms=now_ms) is False
    # Fresh :pq ok for lite; still blocked for full need_live
    with tts._DESK_TIP_LOCK:
        tts._DESK_TIP_CACHE["key"] = "7:pq"
        tts._DESK_TIP_CACHE["ts"] = _time.time()
        tts._DESK_TIP_CACHE["bars"] = list(fresh)
    assert tts.peek_desk_tip1m_chart_bars(7, need_live=False) is not None
    assert tts.peek_desk_tip1m_chart_bars(7, need_live=True) is None
    with tts._DESK_TIP_LOCK:
        tts._DESK_TIP_CACHE["key"] = "7:pq+live"
        tts._DESK_TIP_CACHE["ts"] = _time.time()
        tts._DESK_TIP_CACHE["bars"] = list(fresh)
    assert tts.peek_desk_tip1m_chart_bars(7, need_live=True) is not None
    # ref_ms: tip matching M15 is not stale even if wall-clock is later
    assert (
        tts.desk_tip1m_tail_stale(
            stale, now_ms=now_ms, ref_ms=stale[0]["timestampMs"] + 60_000
        )
        is False
    )
    assert (
        tts.desk_tip1m_tail_stale(
            stale, now_ms=now_ms, ref_ms=stale[0]["timestampMs"] + 2 * 86_400_000
        )
        is True
    )


def test_tp_short_side():
    # Short profits when spread falls
    assert should_exit_take_profit(
        position=Position.SHORT,
        entry_spread=4.2,
        cur_spread=4.0,
        take_profit_pct=1.0,
        deposit_rub=10_000,
        leverage=7,
    )


def test_tinvest_candles_to_1m_frame_skips_incomplete_and_aligns():
    from live.tip_touch_signals import tinvest_candles_to_1m_frame

    base = "2026-08-04T20:00:00Z"
    tatn = [
        {"time": base, "close": 520.0, "is_complete": True},
        {"time": "2026-08-04T20:01:00Z", "close": 521.0, "is_complete": False},
    ]
    tatnp = [
        {"time": base, "close": 500.0, "is_complete": True},
        {"time": "2026-08-04T20:01:00Z", "close": 501.0, "is_complete": True},
    ]
    df = tinvest_candles_to_1m_frame(tatn, tatnp)
    assert len(df) == 1
    assert abs(float(df.iloc[0]["tatn"]) - 520.0) < 1e-9
    assert abs(float(df.iloc[0]["spread"]) - 4.0) < 1e-9


def test_merge_1m_frames_prefers_overlay_tail():
    import pandas as pd
    from zoneinfo import ZoneInfo

    from live.tip_touch_signals import _merge_1m_frames, _m1_tail_lag_sec

    msk = ZoneInfo("Europe/Moscow")
    t0 = pd.Timestamp("2026-08-04 20:00:00", tz=msk)
    t1 = pd.Timestamp("2026-08-04 20:01:00", tz=msk)
    t2 = pd.Timestamp("2026-08-04 20:16:00", tz=msk)
    iss = pd.DataFrame(
        [
            {"timestamp": t0, "tatn": 1.0, "tatnp": 1.0, "spread": 0.0},
            {"timestamp": t1, "tatn": 1.0, "tatnp": 1.0, "spread": 1.0},
        ]
    )
    ti = pd.DataFrame(
        [
            {"timestamp": t1, "tatn": 2.0, "tatnp": 1.0, "spread": 100.0},
            {"timestamp": t2, "tatn": 2.0, "tatnp": 1.0, "spread": 100.0},
        ]
    )
    merged = _merge_1m_frames(iss, ti)
    assert len(merged) == 3
    # same minute → TI wins
    mid = merged[merged["timestamp"] == t1].iloc[0]
    assert float(mid["spread"]) == 100.0
    now_ms = int(t2.timestamp() * 1000) + 60_000
    assert _m1_tail_lag_sec(merged, now_ms) == 60.0

