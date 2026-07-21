"""Trade desk API — markets chart + open mark + settings in one call."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, HTTPException, Query

from live import engine, store
from live.markets_api import markets_snapshot
from live.open_mark import enrich_open_trade
from live.tinvest import TInvestClient

router = APIRouter(prefix="/api/trade", tags=["trade"])


@router.get("/desk")
def trade_desk(days: int = Query(7, description="chart window 1/7/30/90")) -> dict[str, Any]:
    """Single poll for Торговля: chart bars + open MTM + monitor/settings."""
    try:
        if days not in (1, 7, 30, 90):
            days = 7
        settings = store.get_settings_bundle()
        mon = engine.monitor_status()
        mkt: dict[str, Any] = {}
        market_error = None
        try:
            mkt = markets_snapshot(days=days)
        except Exception as mkt_exc:
            market_error = str(mkt_exc)
            mkt = {"ok": False, "summary": {}, "bars": []}

        snap = mkt.get("summary") or {}
        broker = None
        broker_spread = None
        mode, token, account = store.get_credentials()
        if token and account:
            try:
                client = TInvestClient(mode, token)
                pf = client.get_portfolio(account)
                broker = {
                    "mode": mode,
                    "cash_rub": client.portfolio_cash_rub(pf),
                    "total_rub": client.portfolio_total_rub(pf),
                }
                try:
                    sync = engine.reconcile_broker_open_trade(portfolio=pf, market=snap)
                    broker_spread = sync.get("broker")
                except Exception as sync_exc:
                    broker_spread = {"error": str(sync_exc)}
            except Exception as exc:
                broker = {"error": str(exc), "mode": mode}

        open_raw = store.get_open_trade()
        open_e = enrich_open_trade(
            open_raw,
            z_now=snap.get("z"),
            spread_now=snap.get("spread"),
            trade_date=snap.get("trade_date"),
            entry_threshold=float(settings.get("entry_z") or 1.3),
        )

        out: dict[str, Any] = {
            "ok": True,
            "days": days,
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
            "broker": broker,
            "broker_spread": broker_spread,
            "parity": store.parity_summary(),
        }
        if market_error:
            out["market_error"] = market_error
        return out
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc
