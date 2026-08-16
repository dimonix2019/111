"""Dealer weekend quotes — monitoring/manual only (no Z feed)."""

from __future__ import annotations

from live.dealer_quotes import (
    apply_dealer_to_sizing_snap,
    build_pair_quote,
    is_dealer_normal,
    mid_price,
    spread_percent,
    want_dealer_quotes,
)


def test_spread_and_mid():
    assert abs(spread_percent(104.0, 100.0) - 4.0) < 1e-9
    assert spread_percent(100.0, 0) is None
    assert mid_price(10.0, 12.0) == 11.0
    assert mid_price(None, None, 9.5) == 9.5


def test_dealer_normal_status_names():
    assert is_dealer_normal("SECURITY_TRADING_STATUS_DEALER_NORMAL_TRADING")
    assert is_dealer_normal("DEALER_NORMAL_TRADING")
    assert is_dealer_normal(18)
    assert not is_dealer_normal("SECURITY_TRADING_STATUS_NORMAL_TRADING")
    assert not is_dealer_normal("SECURITY_TRADING_STATUS_DEALER_NOT_AVAILABLE_FOR_TRADING")


def test_build_pair_quote_manual_ok():
    q = build_pair_quote(
        status_tatn="SECURITY_TRADING_STATUS_DEALER_NORMAL_TRADING",
        status_tatnp="SECURITY_TRADING_STATUS_DEALER_NORMAL_TRADING",
        last_tatn=700.0,
        last_tatnp=670.0,
        book_tatn={"bid": 699.5, "ask": 700.5, "last": None, "mid": 700.0},
        book_tatnp={"bid": 669.5, "ask": 670.5, "last": None, "mid": 670.0},
    )
    assert q["ok"] is True
    assert q["manual_ok"] is True
    assert q["trading_ok"] is True
    assert q["for_z"] is False
    assert q["for_auto"] is False
    assert "1м" in q["label"]
    assert q["label"].startswith("дилер")
    assert abs(q["spread_last"] - ((700 - 670) / 670 * 100)) < 1e-9


def test_build_pair_quote_manual_ok_with_quotes_even_if_not_dealer_normal():
    """Weekend often reports NORMAL_TRADING — still allow manual when quotes exist."""
    q = build_pair_quote(
        status_tatn="SECURITY_TRADING_STATUS_NORMAL_TRADING",
        status_tatnp="SECURITY_TRADING_STATUS_NORMAL_TRADING",
        last_tatn=700.0,
        last_tatnp=670.0,
    )
    assert q["ok"] is True
    assert q["quotes_ok"] is True
    assert q["manual_ok"] is True
    assert q["trading_ok"] is False  # not DEALER_NORMAL — informational only


def test_build_pair_quote_blocks_without_quotes():
    q = build_pair_quote(
        status_tatn="SECURITY_TRADING_STATUS_NOT_AVAILABLE_FOR_TRADING",
        status_tatnp="SECURITY_TRADING_STATUS_NOT_AVAILABLE_FOR_TRADING",
        last_tatn=None,
        last_tatnp=None,
    )
    assert q["ok"] is False
    assert q["manual_ok"] is False
    assert q["trading_ok"] is False


def test_sizing_overlay_does_not_touch_z():
    snap = {
        "z": 1.37,
        "spread": 3.9,
        "tatn": 680.0,
        "tatnp": 650.0,
        "trade_date": "2026-07-25 18:45",
    }
    dealer = {
        "manual_ok": True,
        "tatn": 701.0,
        "tatnp": 671.0,
        "spread": 4.47,
        "label": "дилер / выходные",
    }
    out = apply_dealer_to_sizing_snap(snap, dealer)
    assert out["tatn"] == 701.0
    assert out["tatnp"] == 671.0
    assert out["z"] == 1.37  # Z unchanged
    assert out["dealer_overlay"] is True
    assert snap["tatn"] == 680.0  # original untouched

    # quotes_ok alone (no manual_ok) still overlays sizing for weekend OTC
    out2 = apply_dealer_to_sizing_snap(
        snap,
        {"quotes_ok": True, "tatn": 702.0, "tatnp": 672.0, "spread": 4.46},
    )
    assert out2["tatn"] == 702.0
    assert out2["z"] == 1.37


def test_build_dealer_1m_spread_bars():
    from datetime import datetime, timezone, timedelta

    from live import dealer_quotes as dq
    from live.dealer_quotes import build_dealer_1m_spread_bars

    # 10:00 and 10:01 UTC → aligned OHLC
    c_n = [
        {"time": "2026-07-26T07:00:00Z", "open": 509.0, "high": 512.0, "low": 508.0, "close": 510.0},
        {"time": "2026-07-26T07:01:00Z", "open": 510.0, "high": 513.0, "low": 509.0, "close": 511.0},
    ]
    c_p = [
        {"time": "2026-07-26T07:00:00Z", "open": 479.0, "high": 481.0, "low": 478.0, "close": 480.0},
        {"time": "2026-07-26T07:01:00Z", "open": 480.0, "high": 482.0, "low": 479.0, "close": 481.0},
    ]
    bars = build_dealer_1m_spread_bars(c_n, c_p)
    assert len(bars) == 2
    assert bars[0]["interval"] == "1m"
    assert bars[0]["z"] is None
    assert bars[0]["for_z"] is False
    assert abs(bars[0]["spread"] - ((510 - 480) / 480 * 100)) < 1e-9
    assert "spread_open" in bars[0]
    assert "spread_high" in bars[0]
    assert "spread_low" in bars[0]
    assert bars[0]["spread_high"] >= bars[0]["spread"]
    assert bars[0]["spread_low"] <= bars[0]["spread"]
    # live tip replaces/appends current minute (pin now inside spread-live window)
    MSK = timezone(timedelta(hours=3))
    orig_now = dq.now_msk
    try:
        dq.now_msk = lambda: datetime(2026, 7, 26, 10, 1, tzinfo=MSK)  # type: ignore[assignment]
        tip = build_dealer_1m_spread_bars(
            c_n, c_p, live_tatn=512.0, live_tatnp=482.0, live_asof_ms=bars[-1]["timestampMs"]
        )
    finally:
        dq.now_msk = orig_now
    assert tip[-1]["tatn"] == 512.0
    assert tip[-1]["source"] == "tinvest_dealer_1m_tip"
    assert tip[-1].get("spread_open") is not None


def test_spread_live_cutoff_rejects_after_2345_tip():
    """После 23:45 МСК tip не мержится; уже попавшие tip ≥23:45 выкидываются."""
    from datetime import datetime, timezone, timedelta

    from live import dealer_quotes as dq

    MSK = timezone(timedelta(hours=3))
    # Пн 2026-07-27 23:44 / 23:45 / 23:55 МСК
    ms_2344 = int(datetime(2026, 7, 27, 23, 44, tzinfo=MSK).timestamp() * 1000)
    ms_2345 = int(datetime(2026, 7, 27, 23, 45, tzinfo=MSK).timestamp() * 1000)
    ms_2355 = int(datetime(2026, 7, 27, 23, 55, tzinfo=MSK).timestamp() * 1000)

    assert dq.is_msk_spread_live(datetime(2026, 7, 27, 23, 44, tzinfo=MSK)) is True
    assert dq.is_msk_spread_live(datetime(2026, 7, 27, 23, 45, tzinfo=MSK)) is False
    assert dq.is_msk_spread_live(datetime(2026, 7, 27, 23, 55, tzinfo=MSK)) is False
    # выходные — tip OK
    assert dq.is_msk_spread_live(datetime(2026, 7, 25, 23, 55, tzinfo=MSK)) is True

    c_n = [
        {"time": "2026-07-27T20:44:00Z", "open": 560.0, "high": 560.0, "low": 560.0, "close": 560.0},
    ]
    c_p = [
        {"time": "2026-07-27T20:44:00Z", "open": 542.7, "high": 542.7, "low": 542.7, "close": 542.7},
    ]
    # Tip @ 23:55 — отклонён (и по now, и по времени tip); hist остаётся
    frozen = datetime(2026, 7, 27, 23, 55, tzinfo=MSK)
    orig_now = dq.now_msk
    try:
        dq.now_msk = lambda: frozen  # type: ignore[assignment]
        bars = dq.build_dealer_1m_spread_bars(
            c_n, c_p, live_tatn=542.0, live_tatnp=533.9, live_asof_ms=ms_2355
        )
    finally:
        dq.now_msk = orig_now
    assert len(bars) == 1
    assert bars[0]["source"] == "tinvest_dealer_1m"
    assert abs(bars[0]["spread"] - ((560 - 542.7) / 542.7 * 100)) < 1e-6
    assert all(b.get("source") != "tinvest_dealer_1m_tip" for b in bars)

    # Strip contaminated tip bars from a mixed series
    mixed = [
        {
            "time": "2026-07-27 23:44:00",
            "timestampMs": ms_2344,
            "spread": 3.19,
            "source": "tinvest_dealer_1m",
        },
        {
            "time": "2026-07-27 23:45:00",
            "timestampMs": ms_2345,
            "spread": 1.52,
            "tatn": 542.0,
            "tatnp": 533.9,
            "source": "tinvest_dealer_1m_tip",
        },
    ]
    cleaned = dq.strip_after_hours_tip_bars(mixed)
    assert len(cleaned) == 1
    assert cleaned[0]["timestampMs"] == ms_2344

    # Tip во время сессии (23:44) — ок, если now тоже в окне
    live_ok = datetime(2026, 7, 27, 23, 44, 30, tzinfo=MSK)
    try:
        dq.now_msk = lambda: live_ok  # type: ignore[assignment]
        tip_ok = dq.build_dealer_1m_spread_bars(
            c_n, c_p, live_tatn=561.0, live_tatnp=543.0, live_asof_ms=ms_2344
        )
    finally:
        dq.now_msk = orig_now
    assert tip_ok[-1]["source"] == "tinvest_dealer_1m_tip"
    assert tip_ok[-1]["tatn"] == 561.0

    status = None
    orig_live = dq.is_msk_spread_live
    try:
        dq.is_msk_spread_live = lambda dt=None: False if dt is None else orig_live(dt)  # type: ignore
        status = dq.attach_spread_live_status(
            {"ok": True, "spread": 1.52, "tatn": 542.0, "tatnp": 533.9},
            mixed,
        )
    finally:
        dq.is_msk_spread_live = orig_live
    assert status["spread_live"] is False
    assert status["spread_cutoff"] == "23:45"
    assert "23:45" in (status.get("spread_frozen_reason") or "")
    assert abs(float(status["spread"]) - 3.19) < 1e-9
    assert len(status["bars"]) == 1


def test_attach_dealer_monitor_z_ohlc():
    from live.dealer_quotes import attach_dealer_monitor_z, build_dealer_1m_spread_bars

    c_n = [
        {"time": "2026-07-26T07:00:00Z", "open": 509.0, "high": 520.0, "low": 505.0, "close": 510.0},
        {"time": "2026-07-26T07:01:00Z", "open": 510.0, "high": 515.0, "low": 508.0, "close": 511.0},
    ]
    c_p = [
        {"time": "2026-07-26T07:00:00Z", "open": 479.0, "high": 481.0, "low": 470.0, "close": 480.0},
        {"time": "2026-07-26T07:01:00Z", "open": 480.0, "high": 482.0, "low": 478.0, "close": 481.0},
    ]
    bars = build_dealer_1m_spread_bars(c_n, c_p)
    # Minimal M15 series so μ/σ path runs
    m15 = []
    base_ms = bars[0]["timestampMs"] - 15 * 60_000 * 40
    for i in range(40):
        m15.append({
            "timestampMs": base_ms + i * 15 * 60_000,
            "time": f"2026-07-25 {10 + i // 4:02d}:{(i * 15) % 60:02d}:00",
            "spread": 6.0 + (i % 5) * 0.1,
        })
    out = attach_dealer_monitor_z(bars, m15)
    assert out[0]["z"] is not None
    assert out[0]["for_z"] is False
    assert out[0]["z_kind"] == "dealer_monitor"
    assert out[0].get("z_open") is not None
    assert out[0]["z_high"] >= max(out[0]["z_open"], out[0]["z"])
    assert out[0]["z_low"] <= min(out[0]["z_open"], out[0]["z"])


def test_dealer_label_is_1m():
    from live.dealer_quotes import DEALER_LABEL, DEALER_BAR_INTERVAL

    assert "1м" in DEALER_LABEL
    assert DEALER_BAR_INTERVAL == "1m"


def test_dealer_period_days_respects_chip_and_cap():
    from live.dealer_quotes import (
        DEALER_1M_MAX_LOOKBACK_DAYS,
        dealer_lookback_hours,
        dealer_period_days,
        filter_dealer_bars_by_days,
    )

    assert dealer_period_days(1) == 1
    assert dealer_period_days(7) == 7
    assert dealer_period_days(30) == DEALER_1M_MAX_LOOKBACK_DAYS
    assert dealer_period_days(90) == DEALER_1M_MAX_LOOKBACK_DAYS
    assert dealer_period_days(180) == DEALER_1M_MAX_LOOKBACK_DAYS
    assert dealer_lookback_hours(7) == 7 * 24.0
    # Was hardcoded 6h — chip 1Д must be a full day of GetCandles window.
    assert dealer_lookback_hours(1) == 24.0

    last = 1_750_000_000_000
    bars = [
        {"timestampMs": last - 10 * 86_400_000, "spread": 1.0},
        {"timestampMs": last - 2 * 86_400_000, "spread": 2.0},
        {"timestampMs": last, "spread": 3.0},
    ]
    week = filter_dealer_bars_by_days(bars, 7)
    assert len(week) == 2
    assert week[0]["spread"] == 2.0
    day = filter_dealer_bars_by_days(bars, 1)
    assert len(day) == 1
    assert day[0]["spread"] == 3.0


def test_want_dealer_on_weekend():
    from datetime import datetime
    from live.dealer_quotes import MSK

    sat = datetime(2026, 7, 25, 12, 0, tzinfo=MSK)  # Saturday
    mon = datetime(2026, 7, 27, 12, 0, tzinfo=MSK)  # Monday noon TQBR
    mon_night = datetime(2026, 7, 27, 3, 0, tzinfo=MSK)
    assert want_dealer_quotes(sat) is True
    assert want_dealer_quotes(mon) is False
    assert want_dealer_quotes(mon_night) is True


def test_attach_dealer_monitor_z_tip_style():
    """Display Z from ISS M15 window + dealer tip; for_z stays False."""
    from live.dealer_quotes import DEALER_Z_KIND, attach_dealer_monitor_z

    # Enough M15 history for min_bars window
    m15 = []
    base_ms = 1_750_000_000_000  # arbitrary
    for i in range(60):
        ms = base_ms + i * 900_000
        m15.append({
            "time": f"2026-07-20 {7 + (i * 15) // 60:02d}:{(i * 15) % 60:02d}:00",
            "timestampMs": ms,
            "spread": 5.0 + (i % 7) * 0.1,
        })
    tip_ms = m15[-1]["timestampMs"] + 60_000
    dealer = [
        {
            "time": "2026-07-20 22:00:00",
            "timestampMs": tip_ms,
            "spread": 6.5,
            "z": None,
            "source": "tinvest_dealer_1m",
            "interval": "1m",
            "for_z": False,
        }
    ]
    out = attach_dealer_monitor_z(dealer, m15)
    assert len(out) == 1
    assert out[0]["z"] is not None
    assert out[0]["for_z"] is False
    assert out[0]["z_kind"] == DEALER_Z_KIND
    # Must not equal raw spread (Z is standardized)
    assert abs(float(out[0]["z"]) - 6.5) > 0.5


def test_attach_dealer_monitor_z_fallback_without_m15():
    from live.dealer_quotes import attach_dealer_monitor_z

    dealer = []
    for i in range(60):
        dealer.append({
            "time": f"2026-07-26 {6 + i // 60:02d}:{i % 60:02d}:00",
            "timestampMs": 1_784_000_000_000 + i * 60_000,
            "spread": 6.0 + (i % 5) * 0.05,
            "z": None,
            "source": "tinvest_dealer_1m",
            "interval": "1m",
            "for_z": False,
        })
    out = attach_dealer_monitor_z(dealer, [])
    assert all(r.get("for_z") is False for r in out)
    assert any(r.get("z") is not None for r in out)
    assert out[-1]["z_kind"] == "dealer_monitor"


def test_peek_and_kick_warm_are_nonblocking():
    """Lite desk helpers must not raise / hang when cache empty."""
    from live import dealer_quotes as dq

    with dq._LOCK:
        dq._CACHE["payload"] = None
        dq._CACHE["ts"] = 0.0
        dq._CANDLES_CACHE["bars"] = None
        dq._CANDLES_CACHE["ts"] = 0.0
    peeked = dq.peek_cached_dealer_quotes()
    assert peeked is None or isinstance(peeked, dict)
    started = dq.kick_dealer_cache_warm()
    assert started in (True, False)


def test_stale_payload_merges_candles_cache():
    from live import dealer_quotes as dq

    bars = [
        {
            "time": "2026-07-26 12:00:00",
            "timestampMs": 1785067200000,
            "spread": 6.1,
            "tatn": 510.0,
            "tatnp": 480.0,
            "source": "tinvest_dealer_1m",
            "interval": "1m",
        }
    ]
    with dq._LOCK:
        dq._CACHE["payload"] = {
            "ok": True,
            "label": dq.DEALER_LABEL,
            "tatn": 511.0,
            "tatnp": 481.0,
            "spread": 6.2,
            "bars": [{"source": "tinvest_dealer_1m_tip", "spread": 6.2}],
            "for_z": False,
        }
        dq._CACHE["ts"] = 1.0
        dq._CANDLES_CACHE["bars"] = bars
        dq._CANDLES_CACHE["err"] = None
        out = dq._stale_payload_unlocked()
    assert out is not None
    assert any(b.get("source") == "tinvest_dealer_1m" for b in (out.get("bars") or []))
