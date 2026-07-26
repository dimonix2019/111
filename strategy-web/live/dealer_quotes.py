"""Weekend / off-TQBR T‑Invest dealer quotes — monitoring + manual sizing only.

Never feed these prices into tip1m signal path / apply_live_last_overlay / AUTO.
Display-only Z for the desk chart is allowed (for_z=False, z_kind=dealer_monitor).
"""

from __future__ import annotations

import concurrent.futures
import math
import threading
import time
from datetime import datetime, timezone, timedelta
from typing import Any

from live.constants import TATN_FALLBACK_ID, TATNP_FALLBACK_ID
from live.tinvest import TInvestClient, quotation_to_float
from zsim import Z_SCORE_ROLLING_LOOKBACK_DAYS, Z_SCORE_ROLLING_MIN_BARS

MSK = timezone(timedelta(hours=3))
DEALER_LABEL = "дилер / выходные · 1м"
DEALER_NORMAL = "SECURITY_TRADING_STATUS_DEALER_NORMAL_TRADING"
DEALER_BAR_INTERVAL = "1m"
DEALER_CANDLE_INTERVAL = "CANDLE_INTERVAL_1_MIN"
# Хвост 1м дилерских свечей для монитора (спред + display-only Z).
DEALER_1M_LOOKBACK_HOURS = 6.0
DEALER_Z_KIND = "dealer_monitor"
# Desk must never wait minutes on GetCandles, but 5s was too aggressive on weekend.
# Keep wall budget short: hung GetCandles must not starve uvicorn / watchdog health.
DEALER_CANDLE_HTTP_TIMEOUT = 6.0
DEALER_CANDLE_MAX_ATTEMPTS = 1
DEALER_CANDLES_BUDGET_SEC = 8.0
DEALER_CANDLES_CACHE_TTL = 180.0
DEALER_CANDLES_STALE_TTL = 900.0
DEALER_QUOTES_CACHE_TTL = 12.0
DEALER_QUOTE_HTTP_TIMEOUT = 3.0
DEALER_QUOTE_BOOK_ATTEMPTS = 1

_CACHE: dict[str, Any] = {"ts": 0.0, "payload": None}
_CANDLES_CACHE: dict[str, Any] = {"ts": 0.0, "bars": None, "err": None}
_LOCK = threading.Lock()
_FETCH_LOCK = threading.Lock()
_CANDLES_FETCH_LOCK = threading.Lock()
_WARM_LOCK = threading.Lock()
_CACHE_TTL = DEALER_QUOTES_CACHE_TTL
# Lite/status must never pile up behind a hung quotes round-trip.
_FETCH_LOCK_WAIT_SEC = 0.05


def now_msk() -> datetime:
    return datetime.now(MSK)


def is_msk_weekend(dt: datetime | None = None) -> bool:
    d = dt or now_msk()
    return d.weekday() >= 5


def is_msk_tqbr_session(dt: datetime | None = None) -> bool:
    """Пн–пт 07:00–23:50 МСК — как UI/signals TQBR."""
    d = dt or now_msk()
    if d.weekday() >= 5:
        return False
    mins = d.hour * 60 + d.minute
    return (7 * 60) <= mins < (23 * 60 + 50)


def want_dealer_quotes(dt: datetime | None = None) -> bool:
    """Запрашивать дилера вне сессии TQBR (выходные и ночь будней)."""
    return not is_msk_tqbr_session(dt)


def _status_name(raw: Any) -> str:
    if raw is None:
        return ""
    s = str(raw).strip().upper()
    if s.isdigit():
        # protobuf int fallback — DEALER_NORMAL_TRADING = 18 in current contract
        if int(s) == 18:
            return DEALER_NORMAL
    return s


def is_dealer_normal(status: Any) -> bool:
    s = _status_name(status)
    return s.endswith("DEALER_NORMAL_TRADING") or s == DEALER_NORMAL


def spread_percent(tatn: float | None, tatnp: float | None) -> float | None:
    if tatn is None or tatnp is None or tatnp <= 0 or tatn <= 0:
        return None
    return (float(tatn) - float(tatnp)) / float(tatnp) * 100.0


def mid_price(bid: float | None, ask: float | None, last: float | None = None) -> float | None:
    if bid is not None and ask is not None and bid > 0 and ask > 0 and float(bid) <= float(ask):
        return (float(bid) + float(ask)) / 2.0
    if last is not None and last > 0:
        return float(last)
    if bid is not None and bid > 0 and ask is not None and ask > 0:
        # inverted dealer book — still average, but last preferred above
        return (float(bid) + float(ask)) / 2.0
    if bid is not None and bid > 0:
        return float(bid)
    if ask is not None and ask > 0:
        return float(ask)
    return None


def _best_level_price(levels: Any) -> float | None:
    if not isinstance(levels, list) or not levels:
        return None
    top = levels[0]
    if not isinstance(top, dict):
        return None
    px = top.get("price") or top.get("Price")
    return quotation_to_float(px if isinstance(px, dict) else None)


def _parse_order_book(raw: dict[str, Any]) -> dict[str, float | None]:
    env = raw
    for key in (
        "getOrderBookResponse",
        "get_order_book_response",
        "orderBook",
        "order_book",
    ):
        if isinstance(raw.get(key), dict):
            env = raw[key]
            break
    bids = env.get("bids") or env.get("Bids") or []
    asks = env.get("asks") or env.get("Asks") or []
    bid = _best_level_price(bids)
    ask = _best_level_price(asks)
    last = quotation_to_float(env.get("lastPrice") or env.get("last_price"))
    return {
        "bid": bid,
        "ask": ask,
        "last": last,
        "mid": mid_price(bid, ask, last),
    }


def build_pair_quote(
    *,
    status_tatn: str,
    status_tatnp: str,
    last_tatn: float | None,
    last_tatnp: float | None,
    book_tatn: dict[str, float | None] | None = None,
    book_tatnp: dict[str, float | None] | None = None,
    instrument_tatn: str = TATN_FALLBACK_ID,
    instrument_tatnp: str = TATNP_FALLBACK_ID,
) -> dict[str, Any]:
    """Pure builder — used by fetch + tests."""
    bt = book_tatn or {}
    bp = book_tatnp or {}
    mid_n = mid_price(bt.get("bid"), bt.get("ask"), last_tatn or bt.get("last"))
    mid_p = mid_price(bp.get("bid"), bp.get("ask"), last_tatnp or bp.get("last"))
    tatn = last_tatn if last_tatn and last_tatn > 0 else mid_n
    tatnp = last_tatnp if last_tatnp and last_tatnp > 0 else mid_p
    # Strict DEALER_NORMAL — informational only (badge / checklist).
    trading_ok = is_dealer_normal(status_tatn) and is_dealer_normal(status_tatnp)
    sp_last = spread_percent(tatn, tatnp)
    sp_mid = spread_percent(mid_n, mid_p)
    quotes_ok = bool(tatn and tatnp)
    return {
        "ok": quotes_ok,
        "label": DEALER_LABEL,
        "source": "tinvest_dealer",
        "for_z": False,
        "for_auto": False,
        "price_type": "LAST_PRICE_DEALER",
        "trading_ok": trading_ok,
        "quotes_ok": quotes_ok,
        # Ручной Long/Short при наличии дилерских цен (выходные/OTC).
        # Не ждём строго DEALER_NORMAL: статус часто NORMAL_TRADING / аукцион;
        # отказ брокера всплывает с POST /api/live/trade. AUTO не трогаем.
        "manual_ok": quotes_ok,
        "status_tatn": status_tatn,
        "status_tatnp": status_tatnp,
        "instrument_tatn": instrument_tatn,
        "instrument_tatnp": instrument_tatnp,
        "tatn": tatn,
        "tatnp": tatnp,
        "tatn_last": last_tatn,
        "tatnp_last": last_tatnp,
        "tatn_bid": bt.get("bid"),
        "tatn_ask": bt.get("ask"),
        "tatnp_bid": bp.get("bid"),
        "tatnp_ask": bp.get("ask"),
        "tatn_mid": mid_n,
        "tatnp_mid": mid_p,
        "spread": sp_mid if sp_mid is not None else sp_last,
        "spread_last": sp_last,
        "spread_mid": sp_mid,
        "interval": DEALER_BAR_INTERVAL,
        "asof_msk": now_msk().strftime("%Y-%m-%d %H:%M:%S"),
        "bars": [],
    }


def _candle_time_ms(raw: Any) -> int | None:
    """Parse T‑Invest candle time → epoch ms."""
    if raw is None:
        return None
    if isinstance(raw, (int, float)):
        v = float(raw)
        # seconds vs ms
        return int(v * 1000) if v < 1e12 else int(v)
    if isinstance(raw, dict):
        sec = raw.get("seconds") or raw.get("Seconds")
        if sec is not None:
            try:
                nano = float(raw.get("nanos") or raw.get("Nanos") or 0)
                return int(float(sec) * 1000 + nano / 1_000_000.0)
            except (TypeError, ValueError):
                return None
    s = str(raw).strip()
    if not s:
        return None
    try:
        if s.endswith("Z"):
            dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
        else:
            dt = datetime.fromisoformat(s)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return int(dt.timestamp() * 1000)
    except ValueError:
        return None


def _ms_to_msk_label(ms: int) -> str:
    dt = datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc).astimezone(MSK)
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def _candle_ohlc(c: dict[str, Any]) -> tuple[float, float, float, float] | None:
    """Return (open, high, low, close); missing O/H/L fall back to close."""
    close = c.get("close")
    if close is None:
        return None
    try:
        c_f = float(close)
    except (TypeError, ValueError):
        return None
    if c_f <= 0:
        return None

    def _px(key: str) -> float:
        v = c.get(key)
        if v is None:
            return c_f
        try:
            f = float(v)
        except (TypeError, ValueError):
            return c_f
        return f if f > 0 else c_f

    o = _px("open")
    h = _px("high")
    l = _px("low")
    # Keep wick extremes coherent even if feed is noisy.
    hi = max(o, h, l, c_f)
    lo = min(o, h, l, c_f)
    return o, hi, lo, c_f


def _spread_ohlc_from_legs(
    o_n: float, h_n: float, l_n: float, c_n: float,
    o_p: float, h_p: float, l_p: float, c_p: float,
) -> tuple[float, float, float, float] | None:
    """Spread % OHLC from TATN/TATNP OHLC; high/low = min/max of corner Z-points."""
    pts: list[float] = []
    for tn, tp in (
        (o_n, o_p),
        (h_n, h_p),
        (l_n, l_p),
        (c_n, c_p),
        (h_n, l_p),  # max-spread corner
        (l_n, h_p),  # min-spread corner
    ):
        sp = spread_percent(tn, tp)
        if sp is not None:
            pts.append(sp)
    sp_o = spread_percent(o_n, o_p)
    sp_c = spread_percent(c_n, c_p)
    if sp_o is None or sp_c is None or not pts:
        return None
    return sp_o, max(pts), min(pts), sp_c


def build_dealer_1m_spread_bars(
    candles_tatn: list[dict[str, Any]],
    candles_tatnp: list[dict[str, Any]],
    *,
    live_tatn: float | None = None,
    live_tatnp: float | None = None,
    live_asof_ms: int | None = None,
) -> list[dict[str, Any]]:
    """Align TATN/TATNP 1m OHLC → spread bars (+ spread_open/high/low). z always None (not AUTO)."""
    by_n: dict[int, tuple[float, float, float, float]] = {}
    by_p: dict[int, tuple[float, float, float, float]] = {}
    for c in candles_tatn or []:
        ms = _candle_time_ms(c.get("time"))
        ohlc = _candle_ohlc(c) if isinstance(c, dict) else None
        if ms is None or ohlc is None:
            continue
        by_n[int(ms)] = ohlc
    for c in candles_tatnp or []:
        ms = _candle_time_ms(c.get("time"))
        ohlc = _candle_ohlc(c) if isinstance(c, dict) else None
        if ms is None or ohlc is None:
            continue
        by_p[int(ms)] = ohlc
    keys = sorted(set(by_n) & set(by_p))
    out: list[dict[str, Any]] = []
    for ms in keys:
        o_n, h_n, l_n, c_n = by_n[ms]
        o_p, h_p, l_p, c_p = by_p[ms]
        sp_ohlc = _spread_ohlc_from_legs(o_n, h_n, l_n, c_n, o_p, h_p, l_p, c_p)
        if sp_ohlc is None:
            continue
        sp_o, sp_h, sp_l, sp_c = sp_ohlc
        out.append(
            {
                "time": _ms_to_msk_label(ms),
                "timestampMs": ms,
                "tatn": c_n,
                "tatnp": c_p,
                "tatn_open": o_n,
                "tatn_high": h_n,
                "tatn_low": l_n,
                "tatnp_open": o_p,
                "tatnp_high": h_p,
                "tatnp_low": l_p,
                "spread": sp_c,
                "spread_open": sp_o,
                "spread_high": sp_h,
                "spread_low": sp_l,
                "z": None,
                "interval": DEALER_BAR_INTERVAL,
                "source": "tinvest_dealer_1m",
                "for_z": False,
            }
        )
    # Live tip текущего минутного слота (last/orderbook) — монитор, не Z.
    if live_tatn and live_tatnp and live_tatnp > 0:
        tip_ms = live_asof_ms
        if tip_ms is None:
            tip_ms = int(now_msk().timestamp() * 1000)
        # округлить вниз к минуте
        tip_ms = (int(tip_ms) // 60_000) * 60_000
        sp = spread_percent(float(live_tatn), float(live_tatnp))
        if sp is not None:
            tip = {
                "time": _ms_to_msk_label(tip_ms),
                "timestampMs": tip_ms,
                "tatn": float(live_tatn),
                "tatnp": float(live_tatnp),
                "spread": sp,
                "z": None,
                "interval": DEALER_BAR_INTERVAL,
                "source": "tinvest_dealer_1m_tip",
                "for_z": False,
            }
            if out and out[-1]["timestampMs"] == tip_ms:
                # Keep candle OHLC from the minute bar; refresh close/live tip only.
                prev = out[-1]
                tip = {
                    **prev,
                    **tip,
                    "spread_open": prev.get("spread_open", sp),
                    "spread_high": max(float(prev.get("spread_high", sp)), sp),
                    "spread_low": min(float(prev.get("spread_low", sp)), sp),
                }
                out[-1] = tip
            elif not out or out[-1]["timestampMs"] < tip_ms:
                out.append(tip)
    return out


def fetch_dealer_1m_candles(
    client: TInvestClient,
    instrument_id: str,
    *,
    hours: float = DEALER_1M_LOOKBACK_HOURS,
    timeout: float = DEALER_CANDLE_HTTP_TIMEOUT,
    max_attempts: int = DEALER_CANDLE_MAX_ATTEMPTS,
) -> list[dict[str, Any]]:
    to_dt = datetime.now(timezone.utc)
    from_dt = to_dt - timedelta(hours=max(0.5, float(hours)))
    return client.get_candles(
        instrument_id,
        interval=DEALER_CANDLE_INTERVAL,
        from_dt=from_dt,
        to_dt=to_dt,
        candle_source_type="CANDLE_SOURCE_DEALER_WEEKEND",
        timeout=float(timeout),
        max_attempts=int(max_attempts),
        accept_empty=True,
    )


def _tip_only_bars(out: dict[str, Any]) -> list[dict[str, Any]]:
    return build_dealer_1m_spread_bars(
        [],
        [],
        live_tatn=out.get("tatn"),
        live_tatnp=out.get("tatnp"),
        live_asof_ms=int(now_msk().timestamp() * 1000),
    )


def _merge_tip_onto_hist(
    cached_bars: list[dict[str, Any]],
    out: dict[str, Any],
) -> list[dict[str, Any]]:
    bars = build_dealer_1m_spread_bars(
        [],
        [],
        live_tatn=out.get("tatn"),
        live_tatnp=out.get("tatnp"),
        live_asof_ms=int(now_msk().timestamp() * 1000),
    )
    hist = [
        b for b in cached_bars
        if isinstance(b, dict) and b.get("source") != "tinvest_dealer_1m_tip"
    ]
    if not hist:
        return bars or _tip_only_bars(out)
    tip = bars[-1] if bars else None
    merged = list(hist)
    if tip:
        if merged and merged[-1].get("timestampMs") == tip.get("timestampMs"):
            merged[-1] = tip
        elif not merged or (merged[-1].get("timestampMs") or 0) < (tip.get("timestampMs") or 0):
            merged.append(tip)
    return merged


def _fetch_candles_pair_budgeted(
    client: TInvestClient,
    tatn_id: str,
    tatnp_id: str,
    out: dict[str, Any],
) -> tuple[list[dict[str, Any]], str | None]:
    """GetCandles for TATN+TATNP with wall-clock budget; never block desk forever."""
    now = time.time()
    stale_serve: list[dict[str, Any]] | None = None
    with _LOCK:
        cached_bars = _CANDLES_CACHE.get("bars")
        cached_err = _CANDLES_CACHE.get("err")
        cached_ts = float(_CANDLES_CACHE.get("ts") or 0.0)
        age = now - cached_ts
        if cached_bars is not None and age < DEALER_CANDLES_CACHE_TTL:
            return _merge_tip_onto_hist(cached_bars, out), (
                cached_err if isinstance(cached_err, str) else None
            )
        if (
            cached_bars is not None
            and age < DEALER_CANDLES_STALE_TTL
            and any(
                isinstance(b, dict) and b.get("source") == "tinvest_dealer_1m"
                for b in cached_bars
            )
        ):
            stale_serve = _merge_tip_onto_hist(cached_bars, out)

    candles_err: str | None = None
    bars: list[dict[str, Any]] = []

    def _one(iid: str) -> list[dict[str, Any]]:
        return fetch_dealer_1m_candles(client, iid)

    pool = concurrent.futures.ThreadPoolExecutor(max_workers=2)
    try:
        fut_n = pool.submit(_one, tatn_id)
        fut_p = pool.submit(_one, tatnp_id)
        done, not_done = concurrent.futures.wait(
            {fut_n, fut_p},
            timeout=DEALER_CANDLES_BUDGET_SEC,
        )
        if not_done:
            candles_err = f"GetCandles timeout>{DEALER_CANDLES_BUDGET_SEC:.0f}s"
            for fut in not_done:
                fut.cancel()
        c_n: list[dict[str, Any]] = []
        c_p: list[dict[str, Any]] = []
        if fut_n in done and not fut_n.cancelled():
            try:
                c_n = fut_n.result(timeout=0)
            except Exception as exc:
                candles_err = str(exc)
        if fut_p in done and not fut_p.cancelled():
            try:
                c_p = fut_p.result(timeout=0)
            except Exception as exc:
                candles_err = (candles_err + "; " if candles_err else "") + str(exc)
        bars = build_dealer_1m_spread_bars(
            c_n,
            c_p,
            live_tatn=out.get("tatn"),
            live_tatnp=out.get("tatnp"),
            live_asof_ms=int(now_msk().timestamp() * 1000),
        )
    except Exception as exc:
        candles_err = str(exc)
        bars = _tip_only_bars(out)
    finally:
        # Do NOT wait on hung GetCandles — HTTP timeout will finish in background.
        try:
            pool.shutdown(wait=False, cancel_futures=True)
        except TypeError:
            pool.shutdown(wait=False)

    hist_ok = any(
        isinstance(b, dict) and b.get("source") == "tinvest_dealer_1m" for b in bars
    )
    if not hist_ok and stale_serve:
        return stale_serve, candles_err or "stale_cache"

    if not bars:
        bars = _tip_only_bars(out)

    with _LOCK:
        # Cache history only when we got real candles (not tip-only failure).
        if hist_ok or candles_err is None:
            _CANDLES_CACHE["ts"] = time.time()
            _CANDLES_CACHE["bars"] = list(bars)
            _CANDLES_CACHE["err"] = candles_err
        elif _CANDLES_CACHE.get("bars") is None:
            # Remember failure briefly so we don't stampede.
            _CANDLES_CACHE["ts"] = time.time()
            _CANDLES_CACHE["bars"] = list(bars)
            _CANDLES_CACHE["err"] = candles_err
    return bars, candles_err


def _attach_tip_or_cached_bars(out: dict[str, Any]) -> dict[str, Any]:
    """Non-blocking bars for status / quote-only path (cache tip merge)."""
    with _LOCK:
        cached_bars = _CANDLES_CACHE.get("bars")
        cached_err = _CANDLES_CACHE.get("err")
    if isinstance(cached_bars, list) and cached_bars:
        bars = _merge_tip_onto_hist(cached_bars, out)
        err = cached_err if isinstance(cached_err, str) else None
    else:
        bars = _tip_only_bars(out)
        err = None
    out["bars"] = bars
    out["bar_interval"] = DEALER_BAR_INTERVAL
    out["candle_interval"] = DEALER_CANDLE_INTERVAL
    out["bars_count"] = len(bars)
    if err:
        out["candles_error"] = err
    return out


def _fetch_one_order_book(client: TInvestClient, instrument_id: str) -> dict[str, float | None] | None:
    try:
        return _parse_order_book(
            client.get_order_book(
                instrument_id,
                depth=1,
                order_book_type="ORDERBOOK_TYPE_DEALER",
                timeout=DEALER_QUOTE_HTTP_TIMEOUT,
                max_attempts=DEALER_QUOTE_BOOK_ATTEMPTS,
            )
        )
    except Exception:
        try:
            return _parse_order_book(
                client.get_order_book(
                    instrument_id,
                    depth=1,
                    order_book_type=None,
                    timeout=DEALER_QUOTE_HTTP_TIMEOUT,
                    max_attempts=1,
                )
            )
        except Exception:
            return None


def _fetch_quotes_locked(client: TInvestClient) -> dict[str, Any]:
    """Quotes round-trip only — must stay short; never call GetCandles here."""
    try:
        tatn_id = client.resolve_instrument_id("TATN")
    except Exception:
        tatn_id = TATN_FALLBACK_ID
    try:
        tatnp_id = client.resolve_instrument_id("TATNP")
    except Exception:
        tatnp_id = TATNP_FALLBACK_ID

    # Status + last prices + both books in parallel — sequential books starved health.
    book_n: dict[str, float | None] | None = None
    book_p: dict[str, float | None] | None = None
    st_n: dict[str, Any] = {}
    st_p: dict[str, Any] = {}
    lasts: dict[str, Any] = {}

    def _status(iid: str) -> dict[str, Any]:
        return client.get_trading_status(iid, timeout=DEALER_QUOTE_HTTP_TIMEOUT)

    def _lasts() -> dict[str, Any]:
        return client.get_last_prices(
            [tatn_id, tatnp_id],
            last_price_type="LAST_PRICE_DEALER",
            timeout=DEALER_QUOTE_HTTP_TIMEOUT,
        )

    pool = concurrent.futures.ThreadPoolExecutor(max_workers=5)
    try:
        fut_sn = pool.submit(_status, tatn_id)
        fut_sp = pool.submit(_status, tatnp_id)
        fut_lp = pool.submit(_lasts)
        fut_bn = pool.submit(_fetch_one_order_book, client, tatn_id)
        fut_bp = pool.submit(_fetch_one_order_book, client, tatnp_id)
        done, _pending = concurrent.futures.wait(
            {fut_sn, fut_sp, fut_lp, fut_bn, fut_bp},
            timeout=DEALER_QUOTE_HTTP_TIMEOUT + 2.0,
        )
        if fut_sn in done:
            try:
                st_n = fut_sn.result(timeout=0)
            except Exception:
                st_n = {}
        if fut_sp in done:
            try:
                st_p = fut_sp.result(timeout=0)
            except Exception:
                st_p = {}
        if fut_lp in done:
            try:
                lasts = fut_lp.result(timeout=0) or {}
            except Exception:
                lasts = {}
        if fut_bn in done:
            try:
                book_n = fut_bn.result(timeout=0)
            except Exception:
                book_n = None
        if fut_bp in done:
            try:
                book_p = fut_bp.result(timeout=0)
            except Exception:
                book_p = None
    finally:
        try:
            pool.shutdown(wait=False, cancel_futures=True)
        except TypeError:
            pool.shutdown(wait=False)

    status_n = _status_name(
        st_n.get("tradingStatus") or st_n.get("trading_status") or st_n.get("status")
    )
    status_p = _status_name(
        st_p.get("tradingStatus") or st_p.get("trading_status") or st_p.get("status")
    )
    last_n = lasts.get(tatn_id) or lasts.get(TATN_FALLBACK_ID)
    last_p = lasts.get(tatnp_id) or lasts.get(TATNP_FALLBACK_ID)
    if last_n is None or last_p is None:
        for k, v in lasts.items():
            ku = str(k).upper()
            if last_n is None and "TATN" in ku and "TATNP" not in ku:
                last_n = v
            if last_p is None and "TATNP" in ku:
                last_p = v

    return build_pair_quote(
        status_tatn=status_n,
        status_tatnp=status_p,
        last_tatn=last_n,
        last_tatnp=last_p,
        book_tatn=book_n,
        book_tatnp=book_p,
        instrument_tatn=tatn_id,
        instrument_tatnp=tatnp_id,
    )


def _payload_has_hist_candles(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return False
    bars = payload.get("bars") or []
    return any(
        isinstance(b, dict) and b.get("source") == "tinvest_dealer_1m" for b in bars
    )


def _apply_candles_to_payload(
    out: dict[str, Any],
    bars: list[dict[str, Any]],
    candles_err: str | None,
) -> dict[str, Any]:
    out = dict(out)
    out["bars"] = bars
    out["bar_interval"] = DEALER_BAR_INTERVAL
    out["candle_interval"] = DEALER_CANDLE_INTERVAL
    out["bars_count"] = len(bars)
    if candles_err:
        out["candles_error"] = candles_err
    else:
        out.pop("candles_error", None)
    return out


def _candles_refresh_background(client: TInvestClient, seed: dict[str, Any]) -> None:
    """Warm GetCandles off the request path — holds _CANDLES_FETCH_LOCK until done."""
    try:
        with _LOCK:
            fresh = _CACHE.get("payload")
            if isinstance(fresh, dict) and _payload_has_hist_candles(fresh):
                age = time.time() - float(_CANDLES_CACHE.get("ts") or 0.0)
                if age < DEALER_CANDLES_CACHE_TTL:
                    return
            out = dict(fresh) if isinstance(fresh, dict) else dict(seed)
        tatn_id = str(out.get("instrument_tatn") or TATN_FALLBACK_ID)
        tatnp_id = str(out.get("instrument_tatnp") or TATNP_FALLBACK_ID)
        bars, candles_err = _fetch_candles_pair_budgeted(client, tatn_id, tatnp_id, out)
        out = _apply_candles_to_payload(out, bars, candles_err)
        with _LOCK:
            # Keep newer quotes tip if quotes refreshed while we fetched candles.
            cur = _CACHE.get("payload")
            if isinstance(cur, dict) and cur.get("asof_msk"):
                for k in (
                    "tatn",
                    "tatnp",
                    "spread",
                    "tatn_bid",
                    "tatn_ask",
                    "tatnp_bid",
                    "tatnp_ask",
                    "asof_msk",
                    "trading_ok",
                    "manual_ok",
                    "ok",
                ):
                    if k in cur:
                        out[k] = cur[k]
                out = _apply_candles_to_payload(
                    out,
                    _merge_tip_onto_hist(bars, out) if bars else bars,
                    candles_err,
                )
            _CACHE["ts"] = time.time()
            _CACHE["payload"] = dict(out)
    except Exception:
        pass
    finally:
        try:
            _CANDLES_FETCH_LOCK.release()
        except RuntimeError:
            pass


def _kick_candles_background(client: TInvestClient, seed: dict[str, Any]) -> None:
    """Start at most one GetCandles warm-up; never blocks the caller."""
    if not _CANDLES_FETCH_LOCK.acquire(blocking=False):
        return
    t = threading.Thread(
        target=_candles_refresh_background,
        args=(client, dict(seed)),
        name="dealer-candles-bg",
        daemon=True,
    )
    t.start()


def _stale_payload_unlocked() -> dict[str, Any] | None:
    payload = _CACHE.get("payload")
    if isinstance(payload, dict):
        out = dict(payload)
        cached_bars = _CANDLES_CACHE.get("bars")
        if (
            isinstance(cached_bars, list)
            and cached_bars
            and not _payload_has_hist_candles(out)
        ):
            err = _CANDLES_CACHE.get("err")
            out = _apply_candles_to_payload(
                out,
                _merge_tip_onto_hist(cached_bars, out),
                err if isinstance(err, str) else None,
            )
        return out
    cached_bars = _CANDLES_CACHE.get("bars")
    if isinstance(cached_bars, list) and cached_bars:
        seed = {
            "ok": True,
            "label": DEALER_LABEL,
            "for_z": False,
            "for_auto": False,
            "warming": False,
            "from_cache": True,
        }
        err = _CANDLES_CACHE.get("err")
        return _apply_candles_to_payload(
            seed,
            list(cached_bars),
            err if isinstance(err, str) else None,
        )
    return None


def fetch_dealer_quotes(
    client: TInvestClient,
    *,
    force: bool = False,
    use_cache: bool = True,
    include_candles: bool = True,
) -> dict[str, Any]:
    """GetTradingStatus + GetLastPrices(DEALER) + optional GetOrderBook for TATN/TATNP.

    Candles never block the caller: serve tip/stale bars and refresh GetCandles
    in a daemon thread. Keeps uvicorn workers free for /health and lite desk.
    """
    now = time.time()
    if use_cache and not force:
        with _LOCK:
            if (
                _CACHE["payload"] is not None
                and (now - float(_CACHE["ts"])) < _CACHE_TTL
            ):
                cached = _stale_payload_unlocked() or dict(_CACHE["payload"])
                if (not include_candles) or _payload_has_hist_candles(cached):
                    return cached

    # Single-flight for quotes — never wait behind a hung TInvest round-trip.
    got_lock = _FETCH_LOCK.acquire(
        blocking=True, timeout=0.0 if force else _FETCH_LOCK_WAIT_SEC
    )
    if not got_lock:
        with _LOCK:
            stale = _stale_payload_unlocked()
        if stale is not None:
            out = dict(stale)
            out["from_cache"] = True
            if include_candles and not _payload_has_hist_candles(out):
                _kick_candles_background(client, out)
                if not out.get("candles_error"):
                    out["candles_error"] = "candles warming"
            return out
        # Cold + another fetch in flight: wait briefly, then give up to tip/empty.
        got_lock = _FETCH_LOCK.acquire(blocking=True, timeout=1.0)
        if not got_lock:
            return {
                "ok": False,
                "label": DEALER_LABEL,
                "error": "dealer fetch busy",
                "warming": True,
                "for_z": False,
                "for_auto": False,
                "bars": [],
                "bars_count": 0,
            }

    try:
        now2 = time.time()
        out: dict[str, Any] | None = None
        if use_cache and not force:
            with _LOCK:
                if (
                    _CACHE["payload"] is not None
                    and (now2 - float(_CACHE["ts"])) < _CACHE_TTL
                ):
                    out = _stale_payload_unlocked()
        if out is None:
            out = _attach_tip_or_cached_bars(_fetch_quotes_locked(client))
            with _LOCK:
                _CACHE["ts"] = time.time()
                _CACHE["payload"] = dict(out)
    finally:
        _FETCH_LOCK.release()

    if not include_candles:
        return dict(out)

    if _payload_has_hist_candles(out):
        age = time.time() - float(_CANDLES_CACHE.get("ts") or 0.0)
        if age < DEALER_CANDLES_CACHE_TTL:
            return dict(out)

    # Stale/missing hist: return tip/stale now; warm candles off-thread.
    _kick_candles_background(client, out)
    out = dict(out)
    if not out.get("candles_error"):
        out["candles_error"] = "candles warming"
    return out


def peek_cached_dealer_quotes() -> dict[str, Any] | None:
    """Non-blocking: return warm/stale dealer cache for lite desk first paint."""
    if not want_dealer_quotes():
        return None
    with _LOCK:
        return _stale_payload_unlocked()


def kick_dealer_cache_warm() -> bool:
    """Start background quotes+candles warm-up. Never blocks. True if started."""
    if not want_dealer_quotes():
        return False
    if not _WARM_LOCK.acquire(blocking=False):
        return False

    def _run() -> None:
        try:
            try_fetch_dealer_quotes(include_candles=True)
        except Exception:
            pass
        finally:
            try:
                _WARM_LOCK.release()
            except RuntimeError:
                pass

    threading.Thread(
        target=_run, name="dealer-cache-warm", daemon=True
    ).start()
    return True


def try_fetch_dealer_quotes(
    *,
    force: bool = False,
    only_when_off_tqbr: bool = True,
    include_candles: bool = True,
) -> dict[str, Any] | None:
    """Credentials-aware helper for desk/status. Returns None if skipped/unavailable."""
    if only_when_off_tqbr and not want_dealer_quotes():
        return None
    from live import store

    mode, token, account = store.get_credentials()
    if not token:
        return {"ok": False, "label": DEALER_LABEL, "error": "нет токена", "for_z": False}
    try:
        client = TInvestClient(mode, token)
        return fetch_dealer_quotes(
            client, force=force, include_candles=include_candles
        )
    except Exception as exc:
        return {
            "ok": False,
            "label": DEALER_LABEL,
            "error": str(exc),
            "for_z": False,
            "for_auto": False,
            "trading_ok": False,
            "manual_ok": False,
        }


def apply_dealer_to_sizing_snap(
    snap: dict[str, Any],
    dealer: dict[str, Any] | None,
) -> dict[str, Any]:
    """Copy of market snap with dealer last for lot sizing — does not mutate Z path."""
    if not dealer or not (dealer.get("manual_ok") or dealer.get("quotes_ok")):
        return snap
    tatn = dealer.get("tatn")
    tatnp = dealer.get("tatnp")
    if not tatn or not tatnp:
        return snap
    out = dict(snap)
    out["tatn"] = float(tatn)
    out["tatnp"] = float(tatnp)
    if dealer.get("spread") is not None:
        out["spread"] = float(dealer["spread"])
    out["dealer_overlay"] = True
    out["dealer_label"] = dealer.get("label") or DEALER_LABEL
    # Keep z / trade_date from ISS for audit; sizing only needs prices.
    return out


def _floor_15m_ms(ms: int) -> int:
    return (int(ms) // 900_000) * 900_000


def _m15_spread_series(m15_bars: list[dict[str, Any]]) -> tuple[list[int], list[float]]:
    """Extract completed M15 (ms, spread) for tip-style μ/σ window."""
    ms_list: list[int] = []
    sp_list: list[float] = []
    for b in m15_bars or []:
        if not isinstance(b, dict):
            continue
        sp = b.get("spread")
        if sp is None:
            sp = b.get("spreadPercent")
        if sp is None:
            continue
        ms = b.get("timestampMs")
        if ms is None:
            t = b.get("time") or b.get("tradeDate")
            if t:
                try:
                    s = str(t).replace("T", " ").strip()[:19]
                    if len(s) == 16:
                        s += ":00"
                    dt = datetime.strptime(s, "%Y-%m-%d %H:%M:%S").replace(tzinfo=MSK)
                    ms = int(dt.timestamp() * 1000)
                except ValueError:
                    continue
        if ms is None:
            continue
        try:
            ms_list.append(int(ms))
            sp_list.append(float(sp))
        except (TypeError, ValueError):
            continue
    if not ms_list:
        return [], []
    order = sorted(range(len(ms_list)), key=lambda i: ms_list[i])
    return [ms_list[i] for i in order], [sp_list[i] for i in order]


def _z_from_spread(sp: float, mean: float, std: float) -> float:
    if std <= 1e-12:
        std = 1.0
    return (float(sp) - float(mean)) / float(std)


def _attach_z_ohlc(row: dict[str, Any], mean: float, std: float, tip_sp: float) -> None:
    """Chart-only Z open/high/low from spread OHLC (same μ/σ as close Z).

    Only set when real spread OHLC varies; otherwise leave unset so the UI
    can build visible bodies via prevZ→currZ.
    """
    z_close = row.get("z")
    if z_close is None:
        return
    try:
        z_c = float(z_close)
    except (TypeError, ValueError):
        return

    sp_o = row.get("spread_open")
    sp_h = row.get("spread_high")
    sp_l = row.get("spread_low")
    if sp_o is None or sp_h is None or sp_l is None:
        return
    try:
        sp_o_f = float(sp_o)
        sp_h_f = float(sp_h)
        sp_l_f = float(sp_l)
    except (TypeError, ValueError):
        return
    # Flat OHLC (close-only feed) → skip; client uses prevZ→currZ.
    if abs(sp_h_f - sp_l_f) < 1e-12 and abs(sp_o_f - float(tip_sp)) < 1e-12:
        return

    z_o = _z_from_spread(sp_o_f, mean, std)
    z_h = _z_from_spread(sp_h_f, mean, std)
    z_l = _z_from_spread(sp_l_f, mean, std)
    row["z_open"] = z_o
    row["z_high"] = max(z_o, z_h, z_l, z_c)
    row["z_low"] = min(z_o, z_h, z_l, z_c)


def attach_dealer_monitor_z(
    dealer_bars: list[dict[str, Any]],
    m15_bars: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    """Tip-style display Z on dealer 1m bars: rolling μ/σ on ISS M15 + tip as last obs.

    Sets ``z`` (+ optional ``z_open/high/low``) for the desk chart only.
    Always ``for_z=False`` / ``z_kind=dealer_monitor``.
    Never for AUTO / tip_touch_signals / decision_bars / apply_live_last_overlay.
    """
    if not dealer_bars:
        return dealer_bars
    m15_ms, m15_sp = _m15_spread_series(m15_bars or [])
    lookback_days = Z_SCORE_ROLLING_LOOKBACK_DAYS
    min_bars = max(Z_SCORE_ROLLING_MIN_BARS, 2)
    completed_end = 0
    win_start = 0
    total = 0.0
    total_sq = 0.0
    out: list[dict[str, Any]] = []
    for b in dealer_bars:
        if not isinstance(b, dict):
            continue
        row = dict(b)
        row["for_z"] = False
        row["z_kind"] = DEALER_Z_KIND
        tip_sp = row.get("spread")
        tip_ms = row.get("timestampMs")
        if tip_sp is None or tip_ms is None:
            row["z"] = None
            out.append(row)
            continue
        try:
            tip_sp_f = float(tip_sp)
            tip_ms_i = int(tip_ms)
        except (TypeError, ValueError):
            row["z"] = None
            out.append(row)
            continue

        slot_ms = _floor_15m_ms(tip_ms_i)
        while completed_end < len(m15_ms) and m15_ms[completed_end] < slot_ms:
            s = float(m15_sp[completed_end])
            total += s
            total_sq += s * s
            completed_end += 1

        tip_dt = datetime.fromtimestamp(tip_ms_i / 1000.0, tz=MSK)
        from_date = tip_dt.date() - timedelta(days=lookback_days)
        from_ms = int(
            datetime.combine(from_date, datetime.min.time(), tzinfo=MSK).timestamp() * 1000
        )
        while win_start < completed_end and m15_ms[win_start] < from_ms:
            s = float(m15_sp[win_start])
            total -= s
            total_sq -= s * s
            win_start += 1

        if not m15_ms:
            # No ISS M15 in desk window — fill later via dealer-only rolling fallback.
            row["z"] = None
            out.append(row)
            continue
        count = completed_end - win_start
        n = count + 1
        if n < min_bars:
            mean = tip_sp_f
            std = 1.0
            row["z"] = 0.0
        else:
            t = total + tip_sp_f
            tsq = total_sq + tip_sp_f * tip_sp_f
            mean = t / n
            var = (tsq / n) - mean * mean
            std = math.sqrt(max(var, 0.0))
            if std <= 1e-12:
                std = 1.0
            row["z"] = (tip_sp_f - mean) / std
        _attach_z_ohlc(row, mean, std, tip_sp_f)
        out.append(row)

    # If M15 window was too thin for every bar, fill display Z from dealer spreads only.
    if out and all(r.get("z") is None for r in out):
        spreads = []
        times: list[str] = []
        for r in out:
            sp = r.get("spread")
            if sp is None:
                continue
            spreads.append(float(sp))
            times.append(str(r.get("time") or ""))
        if len(spreads) >= min_bars:
            from zsim import apply_z_scores_rolling

            zs = apply_z_scores_rolling(spreads, times)
            zi = 0
            for r in out:
                if r.get("spread") is None:
                    continue
                r["z"] = float(zs[zi]) if zi < len(zs) else None
                r["z_kind"] = DEALER_Z_KIND
                r["for_z"] = False
                # No μ/σ here — chart falls back to prevZ→currZ bodies.
                zi += 1
    return out
