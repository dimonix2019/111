"""Trade desk API — markets chart + open mark + settings in one call."""

from __future__ import annotations

import concurrent.futures
import threading
import time
from typing import Any

from fastapi import APIRouter, HTTPException, Query

from live import engine, store
from live.markets_api import build_markets_snapshot
from live.open_mark import enrich_open_trade
from live.tinvest import TInvestClient

router = APIRouter(prefix="/api/trade", tags=["trade"])

_DESK_CACHE: dict[str, Any] = {"key": None, "ts": 0.0, "payload": None}
_DESK_CACHE_TTL = 2.0
_DESK_LITE_CACHE_TTL = 8.0
_DESK_LOCK = threading.Lock()

# Broker/TInvest is slow (multi-second); reuse across desk polls.
_BROKER_CACHE: dict[str, Any] = {
    "ts": 0.0,
    "broker": None,
    "broker_spread": None,
    "pf": None,
    "ready": False,  # True after portfolio fetched (+ optional reconcile)
}
_BROKER_CACHE_TTL = 20.0
_BROKER_LOCK = threading.Lock()


def _broker_from_cache() -> tuple[Any, Any, Any] | None:
    """Return (broker, broker_spread, pf) or None if cold/expired."""
    now = time.time()
    with _BROKER_LOCK:
        if (
            _BROKER_CACHE["ready"]
            and (now - float(_BROKER_CACHE["ts"])) < _BROKER_CACHE_TTL
        ):
            return (
                _BROKER_CACHE["broker"],
                _BROKER_CACHE["broker_spread"],
                _BROKER_CACHE.get("pf"),
            )
    return None


def _load_portfolio() -> tuple[Any, Any, Any]:
    """
    Fetch TInvest portfolio (or cache hit).
    Returns (broker_dict_or_none, broker_spread_or_none, portfolio_raw_or_none).
    Spread may be None until reconciled with market snap (or forever if FLAT).
    """
    cached = _broker_from_cache()
    if cached is not None:
        return cached

    broker = None
    pf = None
    mode, token, account = store.get_credentials()
    if not token or not account:
        with _BROKER_LOCK:
            _BROKER_CACHE.update(
                ts=time.time(),
                broker=None,
                broker_spread=None,
                pf=None,
                ready=True,
            )
        return None, None, None
    try:
        client = TInvestClient(mode, token)
        # Cap portfolio HTTP so a hung OperationsService cannot freeze /trade/desk.
        pf = client.get_portfolio(account, timeout=8.0)
        broker = {
            "mode": mode,
            "cash_rub": client.portfolio_cash_rub(pf),
            "total_rub": client.portfolio_total_rub(pf),
        }
    except Exception as exc:
        broker = {"error": str(exc), "mode": mode}
        pf = None

    with _BROKER_LOCK:
        _BROKER_CACHE.update(
            ts=time.time(),
            broker=broker,
            broker_spread=None,
            pf=pf,
            ready=True,
            _spread_done=False,
        )
    return broker, None, pf


def _reconcile_spread(pf: Any, snap: dict[str, Any]) -> Any:
    if pf is None:
        return None
    try:
        sync = engine.reconcile_broker_open_trade(portfolio=pf, market=snap)
        spread = sync.get("broker")
    except Exception as sync_exc:
        spread = {"error": str(sync_exc)}
    with _BROKER_LOCK:
        _BROKER_CACHE["broker_spread"] = spread
        _BROKER_CACHE["ts"] = time.time()
        _BROKER_CACHE["ready"] = True
        # Mark spread as resolved even when FLAT (None) — avoid re-hitting TInvest
        _BROKER_CACHE["_spread_done"] = True
    return spread


def _spread_already_done() -> bool:
    with _BROKER_LOCK:
        return bool(_BROKER_CACHE.get("_spread_done"))


@router.get("/desk")
def trade_desk(
    days: int = Query(7, description="chart window 1/7/30/90"),
    lite: bool = Query(
        False,
        description="skip TInvest/broker + open_stats — fast chart first paint",
    ),
) -> dict[str, Any]:
    """Single poll for Торговля: chart bars + open MTM + monitor/settings."""
    try:
        if days not in (1, 7, 30, 90):
            days = 7
        cache_key = f"desk:{days}:{'lite' if lite else 'full'}"
        now = time.time()
        ttl = _DESK_LITE_CACHE_TTL if lite else _DESK_CACHE_TTL
        with _DESK_LOCK:
            if (
                _DESK_CACHE["key"] == cache_key
                and _DESK_CACHE["payload"] is not None
                and (now - float(_DESK_CACHE["ts"])) < ttl
            ):
                return _DESK_CACHE["payload"]

        settings = store.get_settings_bundle()
        mon = engine.monitor_status()
        mkt: dict[str, Any] = {}
        market_error = None
        broker = None
        broker_spread = None
        pf = None

        if lite:
            # SQLite bars only — no MOEX LAST / TInvest (Trade first paint)
            try:
                mkt = build_markets_snapshot(days, live_tip=False)
            except Exception as mkt_exc:
                market_error = str(mkt_exc)
                mkt = {"ok": False, "summary": {}, "bars": []}
            warm = _broker_from_cache()
            if warm is not None:
                broker, broker_spread, _pf = warm
        else:
            # Desk never blocks on MOEX LAST tip (forceMoex → /markets/refresh).
            # Bars from SQLite; broker from cache first — short wait only on cold miss.
            # Do NOT use Executor as context manager: shutdown(wait=True) would
            # re-block the request after result(timeout=…) on hung GetPortfolio.
            warm = _broker_from_cache()
            pool = concurrent.futures.ThreadPoolExecutor(max_workers=2)
            try:
                fut_mkt = pool.submit(
                    lambda d=days: build_markets_snapshot(d, live_tip=False)
                )
                fut_br = None if warm is not None else pool.submit(_load_portfolio)
                try:
                    mkt = fut_mkt.result(timeout=3.0)
                except Exception as mkt_exc:
                    market_error = str(mkt_exc)
                    mkt = {"ok": False, "summary": {}, "bars": []}
                if warm is not None:
                    broker, broker_spread, pf = warm
                else:
                    try:
                        broker, broker_spread, pf = fut_br.result(timeout=3.0)
                    except Exception as br_exc:
                        broker = {"error": str(br_exc)}
                        broker_spread = None
                        pf = None
                        # Keep loading in background so next poll hits cache.
                        threading.Thread(
                            target=_load_portfolio, name="desk-broker-bg", daemon=True
                        ).start()
            finally:
                try:
                    pool.shutdown(wait=False, cancel_futures=True)
                except TypeError:
                    pool.shutdown(wait=False)

            snap_early = mkt.get("summary") or {}
            # FLAT ⇒ broker_spread is None — only reconcile once per cache window
            if pf is not None and not _spread_already_done():
                broker_spread = _reconcile_spread(pf, snap_early)

        snap = mkt.get("summary") or {}

        # Дилерские котировки вне TQBR — только MTM/монитор/ручной sizing, НЕ в Z.
        dealer = None
        dealer_partial = False
        if lite:
            # First paint: warm/stale cache ONLY — never sync-wait TInvest on lite.
            # Cold cache → partial + background warm; full desk fills candles async.
            try:
                from live.dealer_quotes import (
                    kick_dealer_cache_warm,
                    peek_cached_dealer_quotes,
                    want_dealer_quotes as _want_dealer,
                )

                dealer = peek_cached_dealer_quotes()
                need_warm = _want_dealer() and (
                    dealer is None
                    or not any(
                        isinstance(b, dict) and b.get("source") == "tinvest_dealer_1m"
                        for b in (dealer.get("bars") or [])
                    )
                )
                if need_warm:
                    kick_dealer_cache_warm()
                    dealer_partial = True
                    if dealer is None:
                        dealer = {
                            "ok": False,
                            "label": "дилер / выходные · 1м",
                            "error": "кэш дилера греется",
                            "warming": True,
                            "for_z": False,
                            "for_auto": False,
                            "bars": [],
                            "bars_count": 0,
                        }
                    else:
                        dealer = dict(dealer)
                        dealer["warming"] = True
                        dealer["from_cache"] = True
            except Exception:
                dealer = None
                dealer_partial = True
        else:
            try:
                from live.dealer_quotes import (
                    kick_dealer_cache_warm,
                    peek_cached_dealer_quotes,
                    try_fetch_dealer_quotes,
                )

                # Prefer warm hist cache — skip sync TInvest when candles already ready.
                dealer = peek_cached_dealer_quotes()
                has_hist = bool(
                    dealer
                    and any(
                        isinstance(b, dict) and b.get("source") == "tinvest_dealer_1m"
                        for b in (dealer.get("bars") or [])
                    )
                )
                if has_hist:
                    kick_dealer_cache_warm()
                else:
                    dpool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
                    try:
                        fut_d = dpool.submit(try_fetch_dealer_quotes)
                        try:
                            dealer = fut_d.result(timeout=2.5)
                        except Exception as d_exc:
                            if dealer is None:
                                dealer = {
                                    "ok": False,
                                    "label": "дилер / выходные",
                                    "error": str(d_exc),
                                    "for_z": False,
                                    "for_auto": False,
                                }
                            else:
                                dealer = dict(dealer)
                                dealer["from_cache"] = True
                                dealer["candles_error"] = str(d_exc)
                                dealer_partial = True
                            kick_dealer_cache_warm()
                    finally:
                        try:
                            dpool.shutdown(wait=False, cancel_futures=True)
                        except TypeError:
                            dpool.shutdown(wait=False)
            except Exception as d_exc:
                dealer = {
                    "ok": False,
                    "label": "дилер / выходные",
                    "error": str(d_exc),
                    "for_z": False,
                    "for_auto": False,
                }
                dealer_partial = True

        mark_spread = snap.get("spread")
        mark_tatn = snap.get("tatn")
        mark_tatnp = snap.get("tatnp")
        mark_td = snap.get("trade_date")
        if (
            isinstance(dealer, dict)
            and dealer.get("ok")
            and dealer.get("tatn")
            and dealer.get("tatnp")
        ):
            mark_tatn = dealer.get("tatn")
            mark_tatnp = dealer.get("tatnp")
            mark_spread = dealer.get("spread")
            mark_td = dealer.get("asof_msk") or mark_td

        open_raw = store.get_open_trade()
        open_e = enrich_open_trade(
            open_raw,
            z_now=snap.get("z"),
            spread_now=mark_spread,
            trade_date=mark_td,
            entry_threshold=float(settings.get("entry_z") or 1.3),
            tatn_now=mark_tatn,
            tatnp_now=mark_tatnp,
        )

        open_stats = None
        close_forecast = None
        if open_e and not lite:
            try:
                from live.open_stats import compute_open_trade_stats

                notional = float(
                    (open_e.get("mark") or {}).get("notional_rub")
                    or open_e.get("execution_notional_rub")
                    or settings.get("entry_deposit_rub")
                    or 10000
                )
                # execution_notional already includes leverage sizing; for sim use deposit×leverage path
                deposit = float(settings.get("entry_deposit_rub") or 10000)
                lev = float(settings.get("leverage") or 7)
                open_stats = compute_open_trade_stats(
                    direction=str(open_e.get("direction") or "LONG"),
                    entry_z=float(settings.get("entry_z") or 1.3),
                    exit_z=float(settings.get("exit_z") or 1.2),
                    notional_rub=deposit,
                    leverage=lev,
                    slippage_spread_pts=0.12,
                )
                open_stats["open_notional_rub"] = notional
            except Exception as stats_exc:
                open_stats = {"ok": False, "error": str(stats_exc)}

        # Close forecast on lite too — UI polls lite most ticks; null wiped «Прогноз».
        if open_e:
            try:
                from live.close_forecast import compute_close_forecast

                close_forecast = compute_close_forecast(
                    open_e,
                    broker=broker if isinstance(broker, dict) else None,
                    mark=open_e.get("mark") if isinstance(open_e.get("mark"), dict) else None,
                    settings=settings,
                    trade_date=mark_td,
                    tatn_now=mark_tatn,
                    tatnp_now=mark_tatnp,
                    dealer=dealer if isinstance(dealer, dict) else None,
                )
            except Exception as fc_exc:
                close_forecast = {"ok": False, "has_position": True, "error": str(fc_exc)}
        else:
            close_forecast = {
                "ok": True,
                "has_position": False,
                "forecast_total_rub": None,
                "note": "нет позиции",
            }

        # Закрытые для маркеров на графике (как в Тесте)
        from datetime import datetime, timedelta

        closed_raw = store.get_closed_trades(300)
        cut = datetime.now() - timedelta(days=max(1, int(days)))
        closed_desk: list[dict[str, Any]] = []
        for t in closed_raw:
            et = str(t.get("exit_time") or t.get("entry_time") or "")[:16]
            try:
                dt = datetime.strptime(et, "%Y-%m-%d %H:%M")
            except ValueError:
                closed_desk.append(t)
                continue
            if dt >= cut:
                closed_desk.append(t)
        # хронологический порядок для номеров маркеров
        closed_desk.sort(key=lambda x: str(x.get("entry_time") or ""))

        # Compact ≈3y Z/spread dists for hover tips (cached; not full bars).
        # Lite: never block on cold 3y rebuild — serve cache or degraded stub.
        from live.metric_dist import get_desk_metric_dists

        metric_dists = get_desk_metric_dists(allow_build=not lite)

        out: dict[str, Any] = {
            "ok": True,
            "days": days,
            "lite": lite,
            "summary": snap,
            "bars": mkt.get("bars") or [],
            "bars_iss": mkt.get("bars") or [],
            "bars_mode": "iss_m15",
            "metric_dists": metric_dists,
            "settings": settings,
            "monitor": {
                "running": mon.get("running"),
                "last_message": mon.get("last_message"),
                "last_z": mon.get("last_z"),
            },
            "position": engine.current_position().value,
            "open": open_e,
            "open_stats": open_stats,
            "close_forecast": close_forecast,
            "closed": [
                {
                    "id": t.get("id"),
                    "direction": t.get("direction"),
                    "entry_time": t.get("entry_time"),
                    "exit_time": t.get("exit_time"),
                    "entry_z": t.get("entry_z"),
                    "exit_z": t.get("exit_z"),
                    "source": t.get("source"),
                    "pnl_rub": t.get("pnl_rub"),
                }
                for t in closed_desk
            ],
            "broker": broker,
            "broker_spread": broker_spread,
            "dealer": dealer,
            "parity": store.parity_summary(),
        }
        # Выходные / вне TQBR: bars = только дилер 1м для спреда; bars_iss = ISS M15 (dists).
        # HARD RULE: никогда не оставлять ISS M15 в bars под tip1m-брендингом.
        from live.dealer_quotes import want_dealer_quotes

        if want_dealer_quotes():
            out["weekend_monitor"] = True
            out["bars_iss"] = list(mkt.get("bars") or [])
            dealer_bars = (
                dealer.get("bars")
                if isinstance(dealer, dict) and isinstance(dealer.get("bars"), list)
                else []
            )
            if dealer_bars:
                from live.dealer_quotes import attach_dealer_monitor_z

                raw_bars = [
                    {
                        "time": b.get("time"),
                        "timestampMs": b.get("timestampMs"),
                        "z": None,
                        "spread": b.get("spread"),
                        "tatn": b.get("tatn"),
                        "tatnp": b.get("tatnp"),
                        "interval": "1m",
                        "source": b.get("source") or "tinvest_dealer_1m",
                        "for_z": False,
                    }
                    for b in dealer_bars
                    if isinstance(b, dict)
                ]
                # Display-only tip-style Z (ISS M15 μ/σ + dealer tip). Never for AUTO.
                out["bars"] = attach_dealer_monitor_z(raw_bars, out.get("bars_iss") or [])
                out["bars_mode"] = "dealer_1m"
                if isinstance(out.get("summary"), dict):
                    out["summary"] = dict(out["summary"])
                    out["summary"]["bars_mode"] = "dealer_1m"
                    out["summary"]["dealer_interval"] = "1m"
                    mon_z = next(
                        (
                            float(b["z"])
                            for b in reversed(out["bars"])
                            if isinstance(b, dict) and b.get("z") is not None
                        ),
                        None,
                    )
                    if mon_z is not None:
                        out["summary"]["z_iss"] = out["summary"].get("z")
                        out["summary"]["z"] = mon_z
                        out["summary"]["z_kind"] = "dealer_monitor"
            else:
                # Дилер недоступен / греется — не подставляем ISS M15 в bars.
                # Клиент сохраняет last-good; флаг partial = «кэш / частичные».
                out["bars"] = []
                out["bars_mode"] = "dealer_weekend"
                out["partial"] = True
                out["dealer_warming"] = True
                if isinstance(out.get("summary"), dict):
                    out["summary"] = dict(out["summary"])
                    out["summary"]["bars_mode"] = "dealer_weekend"
                if isinstance(dealer, dict):
                    dealer = dict(dealer)
                    if not dealer.get("error"):
                        dealer["error"] = (
                            dealer.get("candles_error")
                            or "нет 1м свечей дилера · кэш греется"
                        )
                    dealer["warming"] = True
                    out["dealer"] = dealer
                try:
                    from live.dealer_quotes import kick_dealer_cache_warm

                    kick_dealer_cache_warm()
                except Exception:
                    pass
        if dealer_partial:
            out["partial"] = True
            if want_dealer_quotes():
                out["dealer_warming"] = bool(
                    out.get("dealer_warming")
                    or (
                        isinstance(dealer, dict)
                        and (
                            dealer.get("warming")
                            or not any(
                                isinstance(b, dict)
                                and b.get("source") == "tinvest_dealer_1m"
                                for b in (dealer.get("bars") or [])
                            )
                        )
                    )
                )
        if market_error:
            out["market_error"] = market_error
        with _DESK_LOCK:
            _DESK_CACHE["key"] = cache_key
            _DESK_CACHE["ts"] = time.time()
            _DESK_CACHE["payload"] = out
        return out
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc
