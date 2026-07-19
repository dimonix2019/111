"""FastAPI routes for live arbitrage trading."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from live import engine, store
from live.signals import Position
from live.tinvest import TInvestClient, normalize_token

router = APIRouter(prefix="/api/live", tags=["live"])


class CredentialsBody(BaseModel):
    mode: str = Field("sandbox", description="sandbox | prod")
    token: str | None = None
    account_id: str | None = None


class SettingsBody(BaseModel):
    mode: str | None = None
    auto_execute: bool | None = None
    entry_z: float | None = None
    exit_z: float | None = None
    leverage: float | None = None


class ManualTradeBody(BaseModel):
    side: str = Field(..., description="LONG | SHORT | CLOSE")


@router.get("/status")
def live_status() -> dict[str, Any]:
    try:
        market = None
        try:
            market = engine.market_snapshot()
            market = {
                "trade_date": market.get("trade_date"),
                "z": market.get("z"),
                "spread": market.get("spread"),
                "tatn": market.get("tatn"),
                "tatnp": market.get("tatnp"),
                "count": market.get("count"),
                "source": market.get("source"),
                "online": market.get("online"),
            }
        except Exception as exc:
            market = {"error": str(exc)}
        return {
            "settings": store.get_settings_bundle(),
            "monitor": engine.monitor_status(),
            "open": store.get_open_trade(),
            "market": market,
            "events": store.list_events(30),
        }
    except Exception as exc:
        raise HTTPException(500, str(exc)) from exc


@router.post("/credentials")
def save_credentials(body: CredentialsBody) -> dict[str, Any]:
    mode = "prod" if body.mode == "prod" else "sandbox"
    token = normalize_token(body.token) if body.token else None
    if body.token is not None and token == "":
        raise HTTPException(400, "Пустой токен")
    store.save_credentials(mode=mode, token=token, account_id=body.account_id)
    store.log_event(f"Сохранены credentials · mode={mode}", "info")
    return {"ok": True, "settings": store.get_settings_bundle()}


@router.post("/settings")
def save_settings(body: SettingsBody) -> dict[str, Any]:
    if body.mode is not None:
        store.set_setting("execution_mode", "prod" if body.mode == "prod" else "sandbox")
    if body.auto_execute is not None:
        store.set_setting("auto_execute", "1" if body.auto_execute else "0")
    if body.entry_z is not None:
        store.set_setting("entry_z", str(body.entry_z))
    if body.exit_z is not None:
        store.set_setting("exit_z", str(body.exit_z))
    if body.leverage is not None:
        store.set_setting("leverage", str(max(1.0, min(30.0, body.leverage))))
    return {"ok": True, "settings": store.get_settings_bundle()}


@router.get("/accounts")
def list_accounts(mode: str | None = None) -> dict[str, Any]:
    """List accounts for current (or explicitly requested) execution mode."""
    try:
        stored_mode, stored_token, _ = store.get_credentials()
        use_mode = mode if mode in ("sandbox", "prod") else stored_mode
        use_mode = "prod" if use_mode == "prod" else "sandbox"
        token = (store.get_setting(f"token_{use_mode}", "") or "").strip() or stored_token
        if use_mode != stored_mode:
            store.set_setting("execution_mode", use_mode)
            token = (store.get_setting(f"token_{use_mode}", "") or "").strip()
        if not token:
            raise RuntimeError(
                f"Нет токена для режима {use_mode}. Вставьте токен и нажмите «Сохранить»."
            )
        client = TInvestClient(use_mode, token)
        rows = client.get_accounts()
        return {"ok": True, "accounts": rows, "mode": client.mode}
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc


@router.get("/portfolio")
def portfolio() -> dict[str, Any]:
    try:
        mode, token, account = store.get_credentials()
        if not token or not account:
            raise RuntimeError("Нужны токен и accountId")
        client = TInvestClient(mode, token)
        pf = client.get_portfolio(account)
        return {
            "ok": True,
            "mode": mode,
            "account_id": account,
            "cash_rub": client.portfolio_cash_rub(pf),
            "total_rub": client.portfolio_total_rub(pf),
            "margin": client.get_margin_attributes(account),
            "sizing": None,
        }
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc


@router.get("/sizing")
def sizing() -> dict[str, Any]:
    try:
        mode, token, account = store.get_credentials()
        if not token or not account:
            raise RuntimeError("Нужны токен и accountId")
        client = TInvestClient(mode, token)
        return {"ok": True, "sizing": engine.resolve_lots(client, account)}
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc


@router.get("/trades")
def trades() -> dict[str, Any]:
    return {
        "open": store.get_open_trade(),
        "closed": store.get_closed_trades(50),
    }


@router.post("/trade")
def manual_trade(body: ManualTradeBody) -> dict[str, Any]:
    side = body.side.strip().upper()
    try:
        if side == "LONG":
            return engine.open_position(Position.LONG, source="MANUAL")
        if side == "SHORT":
            return engine.open_position(Position.SHORT, source="MANUAL")
        if side == "CLOSE":
            return engine.close_position(source="MANUAL")
        raise HTTPException(400, "side: LONG | SHORT | CLOSE")
    except HTTPException:
        raise
    except Exception as exc:
        store.log_event(f"Trade fail: {exc}", "error")
        raise HTTPException(400, str(exc)) from exc


@router.post("/monitor/start")
def monitor_start() -> dict[str, Any]:
    return engine.start_monitor()


@router.post("/monitor/stop")
def monitor_stop() -> dict[str, Any]:
    return engine.stop_monitor()


@router.post("/monitor/tick")
def monitor_tick() -> dict[str, Any]:
    try:
        return {"ok": True, "result": engine.monitor_tick(), "status": engine.monitor_status()}
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc
