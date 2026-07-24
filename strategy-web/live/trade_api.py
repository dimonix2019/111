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
        pf = client.get_portfolio(account)
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
        with _DESK_LOCK:
            if (
                _DESK_CACHE["key"] == cache_key
                and _DESK_CACHE["payload"] is not None
                and (now - float(_DESK_CACHE["ts"])) < _DESK_CACHE_TTL
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
            # Bars from SQLite; TInvest in parallel with short broker cache.
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                fut_mkt = pool.submit(
                    lambda d=days: build_markets_snapshot(d, live_tip=False)
                )
                fut_br = pool.submit(_load_portfolio)
                try:
                    mkt = fut_mkt.result()
                except Exception as mkt_exc:
                    market_error = str(mkt_exc)
                    mkt = {"ok": False, "summary": {}, "bars": []}
                try:
                    broker, broker_spread, pf = fut_br.result()
                except Exception as br_exc:
                    broker = {"error": str(br_exc)}
                    broker_spread = None
                    pf = None

            snap_early = mkt.get("summary") or {}
            # FLAT ⇒ broker_spread is None — only reconcile once per cache window
            if pf is not None and not _spread_already_done():
                broker_spread = _reconcile_spread(pf, snap_early)

        snap = mkt.get("summary") or {}

        open_raw = store.get_open_trade()
        open_e = enrich_open_trade(
            open_raw,
            z_now=snap.get("z"),
            spread_now=snap.get("spread"),
            trade_date=snap.get("trade_date"),
            entry_threshold=float(settings.get("entry_z") or 1.3),
            tatn_now=snap.get("tatn"),
            tatnp_now=snap.get("tatnp"),
        )

        open_stats = None
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

        out: dict[str, Any] = {
            "ok": True,
            "days": days,
            "lite": lite,
            "summary": snap,
            "bars": mkt.get("bars") or [],
            "settings": settings,
            "monitor": {
                "running": mon.get("running"),
                "last_message": mon.get("last_message"),
                "last_z": mon.get("last_z"),
            },
            "position": engine.current_position().value,
            "open": open_e,
            "open_stats": open_stats,
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
            "parity": store.parity_summary(),
        }
        if market_error:
            out["market_error"] = market_error
        with _DESK_LOCK:
            _DESK_CACHE["key"] = cache_key
            _DESK_CACHE["ts"] = time.time()
            _DESK_CACHE["payload"] = out
        return out
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc
