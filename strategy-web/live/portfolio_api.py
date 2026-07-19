"""Portfolio tab API — open/closed trades, depth, Z/leverage params (Android Портфель)."""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel, Field

from live import engine, store
from live.open_mark import enrich_open_trade
from live.tinvest import TInvestClient

router = APIRouter(prefix="/api/portfolio", tags=["portfolio"])


class PortfolioParamsBody(BaseModel):
    entry_z: float | None = None
    exit_z: float | None = None
    leverage: float | None = None
    auto_execute: bool | None = None
    depth_days: int | None = Field(None, description="1|3|7|30")


def _parse_dt(s: str | None) -> datetime | None:
    if not s:
        return None
    for fmt in ("%Y-%m-%d %H:%M", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d"):
        try:
            return datetime.strptime(s[:19], fmt)
        except ValueError:
            continue
    return None


def _within_depth(trade_time: str | None, days: int, ref: datetime | None) -> bool:
    if days <= 0:
        return True
    dt = _parse_dt(trade_time)
    if dt is None or ref is None:
        return True
    return dt >= ref - timedelta(days=days)


@router.get("")
def portfolio_summary(days: int = Query(7, description="1 / 3 / 7 / 30")) -> dict[str, Any]:
    try:
        if days not in (1, 3, 7, 30):
            days = 7
        settings = store.get_settings_bundle()
        open_t = store.get_open_trade()
        closed = store.get_closed_trades(200)
        snap = None
        try:
            snap = engine.market_snapshot()
        except Exception as exc:
            snap = {"error": str(exc)}

        ref = None
        if isinstance(snap, dict) and snap.get("trade_date"):
            ref = _parse_dt(snap.get("trade_date"))
        if ref is None:
            ref = datetime.now()

        closed_f = [
            t
            for t in closed
            if _within_depth(t.get("exit_time") or t.get("entry_time"), days, ref)
        ]

        broker = None
        mode, token, account = store.get_credentials()
        if token and account:
            try:
                client = TInvestClient(mode, token)
                pf = client.get_portfolio(account)
                broker = {
                    "mode": mode,
                    "account_id": account,
                    "cash_rub": client.portfolio_cash_rub(pf),
                    "total_rub": client.portfolio_total_rub(pf),
                    "margin": client.get_margin_attributes(account),
                }
            except Exception as exc:
                broker = {"error": str(exc), "mode": mode}

        open_e = enrich_open_trade(
            open_t,
            z_now=snap.get("z") if isinstance(snap, dict) else None,
            spread_now=snap.get("spread") if isinstance(snap, dict) else None,
            trade_date=snap.get("trade_date") if isinstance(snap, dict) else None,
            entry_threshold=float(settings.get("entry_z") or 1.3),
        )

        return {
            "ok": True,
            "days": days,
            "settings": settings,
            "position": engine.current_position().value,
            "market": {
                "z": snap.get("z") if isinstance(snap, dict) else None,
                "spread": snap.get("spread") if isinstance(snap, dict) else None,
                "trade_date": snap.get("trade_date") if isinstance(snap, dict) else None,
                "tatn": snap.get("tatn") if isinstance(snap, dict) else None,
                "tatnp": snap.get("tatnp") if isinstance(snap, dict) else None,
                "error": snap.get("error") if isinstance(snap, dict) else None,
            },
            "broker": broker,
            "open": open_e,
            "closed": closed_f,
            "closed_total": len(closed),
        }
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc


@router.post("/params")
def save_portfolio_params(body: PortfolioParamsBody) -> dict[str, Any]:
    if body.entry_z is not None:
        store.set_setting("entry_z", str(max(0.05, min(8.0, body.entry_z))))
    if body.exit_z is not None:
        store.set_setting("exit_z", str(max(0.05, min(8.0, body.exit_z))))
    if body.leverage is not None:
        store.set_setting("leverage", str(max(1.0, min(30.0, body.leverage))))
    if body.auto_execute is not None:
        store.set_setting("auto_execute", "1" if body.auto_execute else "0")
    if body.depth_days is not None and body.depth_days in (1, 3, 7, 30):
        store.set_setting("portfolio_depth_days", str(body.depth_days))
    store.log_event("Портфель: параметры обновлены", "info")
    return {"ok": True, "settings": store.get_settings_bundle()}


@router.post("/close")
def portfolio_close() -> dict[str, Any]:
    try:
        return engine.close_position(source="PORTFOLIO")
    except Exception as exc:
        store.log_event(f"Портфель close fail: {exc}", "error")
        raise HTTPException(400, str(exc)) from exc
