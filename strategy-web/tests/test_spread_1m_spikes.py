"""One-bar 1m spread needles: chart/sim must ignore dealer garbage, keep real 4.5%."""

from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

import numpy as np

from live.dealer_quotes import build_dealer_1m_spread_bars, spread_percent
from live.spread_1m_spikes import (
    SPREAD_1M_LEG_JUMP_RUB,
    is_isolated_spread_spike,
    sanitize_dealer_spread_bars,
    sanitize_spread_arrays,
    spread_spike_indices,
)
from replay.tip_touch import PreparedTips, _prep_without_spread_spikes, run_touch_1m_trades


MSK = ZoneInfo("Europe/Moscow")


def _ms(s: str) -> int:
    return int(datetime.strptime(s, "%Y-%m-%d %H:%M:%S").replace(tzinfo=MSK).timestamp() * 1000)


def _series(spreads: list[float], start: str = "2026-09-05 18:58:00") -> tuple[list[float], list[int]]:
    t0 = _ms(start)
    ts = [t0 + i * 60_000 for i in range(len(spreads))]
    return spreads, ts


def test_keeps_smooth_weekday_grind_32_to_45():
    """Long exit 4.0 / grind 3.2→4.5 is real — do not clip every bar near 4.5%."""
    spreads, ts = _series([3.20, 3.45, 3.70, 3.95, 4.20, 4.50], start="2026-09-04 12:00:00")
    assert spread_spike_indices(spreads, ts) == []
    for i in range(len(spreads)):
        assert is_isolated_spread_spike(spreads, ts, i) is False


def test_cuts_isolated_473_vs_neighbors_31():
    """05.09 19:01 TATNP 566.6 → 4.73% with neighbors ~3.1 is a needle."""
    # ~3.11% at TATN 575 / TATNP 557.6? Real: TATNP ~575, TATN ~593 → ~3.13%.
    spreads, ts = _series([3.10, 3.11, 4.73, 3.12, 3.11], start="2026-09-05 18:59:00")
    idx = spread_spike_indices(spreads, ts)
    assert 2 in idx
    cleaned = sanitize_spread_arrays(spreads, ts)
    assert abs(float(cleaned["spread"][2]) - 3.11) < 1e-9
    assert cleaned["n_spikes"] >= 1


def test_cuts_two_minute_needle_plateau():
    spreads, ts = _series([3.10, 4.73, 4.73, 3.11], start="2026-09-05 19:00:00")
    idx = spread_spike_indices(spreads, ts)
    assert 1 in idx and 2 in idx


def test_night_154_percent_is_needle():
    spreads, ts = _series([3.10, 1.54, 3.09], start="2026-09-06 04:06:00")
    assert 1 in spread_spike_indices(spreads, ts)


def test_dealer_bars_flatten_tatnp_8rub_print():
    """TATNP 575 → 566.6 (~8.4₽) on Saturday 19:01 — no wick, close restored."""
    # 19:00 / 19:01 / 19:02 MSK = 16:00 / 16:01 / 16:02 UTC
    c_n = [
        {"time": "2026-09-05T16:00:00Z", "open": 593.0, "high": 593.2, "low": 592.8, "close": 593.0},
        {"time": "2026-09-05T16:01:00Z", "open": 593.0, "high": 593.1, "low": 592.9, "close": 593.0},
        {"time": "2026-09-05T16:02:00Z", "open": 593.0, "high": 593.2, "low": 592.8, "close": 593.1},
    ]
    c_p = [
        {"time": "2026-09-05T16:00:00Z", "open": 575.0, "high": 575.2, "low": 574.8, "close": 575.0},
        {"time": "2026-09-05T16:01:00Z", "open": 566.6, "high": 566.8, "low": 566.4, "close": 566.6},
        {"time": "2026-09-05T16:02:00Z", "open": 575.0, "high": 575.1, "low": 574.9, "close": 575.0},
    ]
    bars = build_dealer_1m_spread_bars(c_n, c_p)
    assert len(bars) == 3
    spike_sp = spread_percent(593.0, 566.6)
    assert spike_sp is not None and spike_sp > 4.5
    # Needle must not remain as close or as wick.
    assert abs(bars[1]["spread"] - bars[0]["spread"]) < 0.15
    assert bars[1]["spread"] < 3.5
    assert bars[1]["spread_high"] <= bars[1]["spread"] + 1e-9
    assert bars[1].get("spread_spike") is True
    assert abs(float(bars[1]["tatnp"]) - 575.0) < 1e-9


def test_crossed_leg_high_low_do_not_inflate_wick():
    """TATN.high / TATNP.low is not a real simultaneous print."""
    c_n = [
        {"time": "2026-07-26T07:00:00Z", "open": 510.0, "high": 520.0, "low": 505.0, "close": 510.0},
    ]
    c_p = [
        {"time": "2026-07-26T07:00:00Z", "open": 480.0, "high": 481.0, "low": 470.0, "close": 480.0},
    ]
    bars = build_dealer_1m_spread_bars(c_n, c_p)
    assert len(bars) == 1
    crossed = spread_percent(520.0, 470.0)
    assert crossed is not None and crossed > 8.0
    body_hi = spread_percent(510.0, 480.0) or 0.0
    assert bars[0]["spread_high"] < 8.0
    assert abs(bars[0]["spread_high"] - body_hi) < 1e-9


def test_sanitize_dealer_bars_idempotent_when_clean():
    bars = [
        {"timestampMs": 1, "spread": 3.10, "tatn": 593.0, "tatnp": 575.0},
        {"timestampMs": 61_000, "spread": 3.12, "tatn": 593.1, "tatnp": 575.0},
        {"timestampMs": 121_000, "spread": 3.11, "tatn": 593.0, "tatnp": 575.1},
    ]
    out = sanitize_dealer_spread_bars(bars)
    assert [b["spread"] for b in out] == [3.10, 3.12, 3.11]


def _weekend_tp_prep(*, spike: bool) -> PreparedTips:
    # Saturday 10:00–10:05 MSK. Long enter ≤3.2 then spike to 4.73 (would be TP ~10%).
    dates = [
        "2026-09-05 10:00:00",
        "2026-09-05 10:01:00",
        "2026-09-05 10:02:00",
        "2026-09-05 10:03:00",
        "2026-09-05 10:04:00",
        "2026-09-05 10:05:00",
    ]
    spreads = [3.40, 3.18, 3.10, 4.73 if spike else 3.11, 3.10, 3.09]
    tatn = np.full(len(dates), 593.0)
    tatnp = np.asarray([575.0, 575.0, 575.0, 566.6 if spike else 575.0, 575.0, 575.1])
    ts_ms = np.asarray([_ms(x) for x in dates], dtype=np.int64)
    session = np.array([False, True, True, True, True, True])
    dt = ts_ms[1:] - ts_ms[:-1]
    ok = session[1:] & (dt == 60_000)
    return PreparedTips(
        ts_ms=ts_ms,
        z=np.zeros(len(dates), dtype=np.float64),
        spread=np.asarray(spreads, dtype=np.float64),
        day_ord=np.full(len(dates), 20_336, dtype=np.int32),
        session=session,
        trade_dates=dates,
        edge_i=(np.flatnonzero(ok) + 1).astype(np.int32),
        n=len(dates),
        tatn=tatn,
        tatnp=tatnp,
    )


def _run_long_tp(prep: PreparedTips) -> dict:
    return run_touch_1m_trades(
        prep,
        entry=1.6,
        exit_z=1.3,
        spread_level_mode=True,
        spread_levels={
            "enter_wide": 6.2,
            "exit_wide": 5.8,
            "enter_narrow": 3.2,
            "exit_narrow": 4.0,
        },
        take_profit_pct=10.0,
        now_ms=int(prep.ts_ms[-1] + 60_000),
        settle_sec=0,
    )


def test_sim_tp_does_not_close_on_synthetic_473_spike(monkeypatch):
    import replay.tip_touch as tip_touch

    monkeypatch.setattr(tip_touch, "_prod_fill_maps", lambda: {})
    raw = _weekend_tp_prep(spike=True)
    # Unfiltered: 3.18 → 4.73 is ~10.8% of deposit at lev 7 — TP would fire.
    dirty = _run_long_tp(raw)
    dirty_exits = [t.get("exitReason") or t.get("reason") or "" for t in dirty.get("trades") or []]
    assert any("tp" in str(r).lower() or str(t.get("status")) != "Открыта" for t, r in zip(dirty.get("trades") or [], dirty_exits)) or (
        dirty["summary"].get("trades", 0) >= 1 and all(str(t.get("status")) != "Открыта" for t in dirty["trades"])
    )

    clean = _prep_without_spread_spikes(raw, dealer_legs=True)
    assert abs(float(clean.spread[3]) - 3.10) < 0.05
    result = _run_long_tp(clean)
    closed = [t for t in result.get("trades") or [] if str(t.get("status") or "") != "Открыта"]
    assert not any(
        "tp" in str(t.get("exitReason") or t.get("reason") or "").lower() for t in closed
    )
    # Long exit 4.0 must not fire either — spike was flattened.
    assert all(float(t.get("exitSpread") or t.get("exit_spread") or 0) < 4.0 for t in closed) or not closed


def test_leg_jump_constant_matches_user_8rub():
    assert SPREAD_1M_LEG_JUMP_RUB == 8.0


def test_spike_prefilter_matches_full_scan():
    """Candidate prefilter must not drop needles vs a full 1..n scan."""
    rng = np.random.default_rng(0)
    n = 2500
    spreads = (3.2 + rng.normal(0, 0.04, n)).tolist()
    spreads[80] = 4.73
    spreads[400] = 4.73
    spreads[401] = 4.73
    spreads[900] = 1.54
    tatn = (593.0 + rng.normal(0, 0.15, n)).tolist()
    tatnp = (575.0 + rng.normal(0, 0.15, n)).tolist()
    tatnp[1200] = 566.5
    t0 = _ms("2026-06-01 10:00:00")
    ts = [t0 + i * 60_000 for i in range(n)]
    fast = spread_spike_indices(
        spreads, ts, tatn=tatn, tatnp=tatnp, dealer_legs=True
    )
    brute: list[int] = []
    for i in range(n):
        if is_isolated_spread_spike(spreads, ts, i):
            brute.append(i)
            continue
        if i <= 0:
            continue
        dt = int(ts[i]) - int(ts[i - 1])
        if dt <= 0 or dt > 3 * 60_000:
            continue
        nxt_n = tatn[i + 1] if i + 1 < n else None
        nxt_p = tatnp[i + 1] if i + 1 < n else None
        from live.spread_1m_spikes import is_unrealistic_leg_jump

        if is_unrealistic_leg_jump(tatn[i - 1], tatn[i], nxt_n) or (
            is_unrealistic_leg_jump(tatnp[i - 1], tatnp[i], nxt_p)
        ):
            brute.append(i)
    assert fast == brute
