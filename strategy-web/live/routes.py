"""FastAPI routes for live arbitrage trading."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, HTTPException, Query
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
    take_profit_pct: float | None = None


class ManualTradeBody(BaseModel):
    side: str = Field(..., description="LONG | SHORT | CLOSE")


def _normalize_take_profit_pct(v: float | None) -> float | None:
    if v is None:
        return None
    try:
        n = float(v)
    except (TypeError, ValueError):
        return None
    allowed = (0.0, 1.0, 2.0, 3.0)
    return min(allowed, key=lambda x: abs(x - n))


@router.get("/status")
def live_status(
    lite: bool = Query(
        False,
        description="settings+monitor only — skip market snapshot and TInvest",
    ),
) -> dict[str, Any]:
    try:
        if lite:
            return {
                "settings": store.get_settings_bundle(),
                "monitor": engine.monitor_status(),
                "open": store.get_open_trade(),
                "market": None,
                "broker": None,
                "events": [],
                "parity": store.parity_summary(),
                "lite": True,
            }

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
                }
            except Exception as exc:
                broker = {"error": str(exc), "mode": mode, "account_id": account}

        dealer = None
        try:
            from live.dealer_quotes import (
                kick_dealer_cache_warm,
                peek_cached_dealer_quotes,
                want_dealer_quotes,
            )

            # Status: cache only + background warm — never sync-wait TInvest here.
            if want_dealer_quotes():
                dealer = peek_cached_dealer_quotes()
                kick_dealer_cache_warm()
                if dealer is None:
                    dealer = {
                        "ok": False,
                        "label": "дилер / выходные · 1м",
                        "error": "кэш дилера греется",
                        "warming": True,
                        "for_z": False,
                        "for_auto": False,
                    }
        except Exception as d_exc:
            dealer = {"ok": False, "label": "дилер / выходные", "error": str(d_exc), "for_z": False}

        return {
            "settings": store.get_settings_bundle(),
            "monitor": engine.monitor_status(),
            "open": store.get_open_trade(),
            "market": market,
            "broker": broker,
            "dealer": dealer,
            "events": store.list_events(30),
            "parity": store.parity_summary(),
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
    tp = _normalize_take_profit_pct(body.take_profit_pct)
    if tp is not None:
        store.set_setting("take_profit_pct", str(tp))
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


@router.post("/monitor/restart")
def monitor_restart() -> dict[str, Any]:
    return engine.restart_monitor()


@router.post("/monitor/tick")
def monitor_tick() -> dict[str, Any]:
    try:
        return {"ok": True, "result": engine.monitor_tick(), "status": engine.monitor_status()}
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc


@router.get("/pref-hang")
def pref_hang_screener(
    force: bool = Query(False, description="Игнорировать кэш и пересчитать с ISS"),
) -> dict[str, Any]:
    """Экран «Кто завис»: TATN/SNGS/RTKM — дневной спред обычка/преф."""
    try:
        from live.pref_hang_screener import get_pref_hang_screener

        return get_pref_hang_screener(force=force)
    except Exception as exc:
        raise HTTPException(500, str(exc)) from exc


@router.get("/parity")
def parity_status() -> dict[str, Any]:
    return store.parity_summary()


@router.post("/parity/check")
def parity_check_now() -> dict[str, Any]:
    """Принудительно прогнать pending parity + open PnL + поля закрытых сделок."""
    from live import parity as parity_mod

    n = store.force_parity_due()
    results = parity_mod.process_due_parity_checks(force_trades=True)
    return {
        "ok": True,
        "forced": n,
        "checked": len(results),
        "results": results,
        "summary": store.parity_summary(),
    }


@router.post("/parity/hourly")
def parity_hourly_digest(
    force: bool = Query(True, description="Игнорировать часовой интервал и записать дайджест сейчас"),
) -> dict[str, Any]:
    """Почасовой дайджест Test↔Prod → data/parity-hourly.log (+ latest.json). Независим от Cursor."""
    from live import parity as parity_mod

    try:
        digest = parity_mod.maybe_run_hourly_parity_digest(force=force, run_checks=True)
        return {
            "ok": True,
            "written": digest is not None,
            "digest": digest,
            "summary": store.parity_summary(),
        }
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc


@router.get("/parity/trades")
def parity_trades_status() -> dict[str, Any]:
    """Последний снимок сверки полей закрытых сделок (без пересчёта)."""
    summary = store.parity_summary()
    return {
        "ok": True,
        "trades": summary.get("trades"),
        "trades_ok": summary.get("trades_ok"),
        "trades_hard_mismatches": summary.get("trades_hard_mismatches"),
    }


@router.post("/parity/trades")
def parity_trades_reconcile(
    days: int = Query(7, ge=1, le=90),
    fix: bool = Query(True, description="Автоправка метаданных Z/времён"),
) -> dict[str, Any]:
    """Сверить все поля закрытых Prod ↔ Test и при fix=1 поправить безопасные расхождения."""
    from live import parity as parity_mod

    try:
        result = parity_mod.reconcile_closed_trades_parity(days=days, fix=fix)
        return {"ok": True, "result": result, "summary": store.parity_summary()}
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc
