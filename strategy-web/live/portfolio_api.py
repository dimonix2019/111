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
    entry_deposit_rub: float | None = None
    take_profit_pct: float | None = None
    regime_z_mode: bool | None = None
    spread_level_mode: bool | None = None
    spread_enter_wide: float | None = None
    spread_exit_wide: float | None = None
    spread_enter_narrow: float | None = None
    spread_exit_narrow: float | None = None
    depth_days: int | None = Field(None, description="1|3|7|30")


def _normalize_take_profit_pct(v: float | None) -> float | None:
    if v is None:
        return None
    try:
        n = float(v)
    except (TypeError, ValueError):
        return None
    allowed = (0.0, 1.0, 2.0, 3.0)
    return min(allowed, key=lambda x: abs(x - n))


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


def _within_date_range(
    trade_time: str | None,
    date_from: datetime | None,
    date_to: datetime | None,
) -> bool:
    """Inclusive calendar-day filter by exit (fallback entry)."""
    if date_from is None and date_to is None:
        return True
    dt = _parse_dt(trade_time)
    if dt is None:
        return True
    d = dt.date()
    if date_from is not None and d < date_from.date():
        return False
    if date_to is not None and d > date_to.date():
        return False
    return True


def _needs_metric_backfill(trade: dict[str, Any]) -> bool:
    """True if path/slip metrics still missing — then load bars."""
    if trade.get("pnl_rub") is None:
        return True
    if trade.get("pnl_min_rub") is None or trade.get("pnl_max_rub") is None:
        return True
    if trade.get("entry_slip_pts") is None:
        return True
    return False


def _patch_missing_metrics(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    """Only write fields that were empty and are now filled."""
    keys = (
        "entry_slip_pts",
        "entry_spread_iss",
        "pnl_rub",
        "gross_rub",
        "commission_rub",
        "overnight_rub",
        "pnl_min_rub",
        "pnl_max_rub",
        "execution_notional_rub",
        "hit1_time",
        "hit2_time",
        "hit3_time",
        "account_after_rub",
        "account_before_rub",
        "account_delta_rub",
    )
    patch: dict[str, Any] = {}
    for k in keys:
        if before.get(k) is None and after.get(k) is not None:
            patch[k] = after[k]
    return patch


@router.get("")
def portfolio_summary(
    days: int = Query(7, description="1 / 3 / 7 / 30 / 0=all (игнор. если заданы date_*)"),
    date_from: str | None = Query(None, description="YYYY-MM-DD inclusive"),
    date_to: str | None = Query(None, description="YYYY-MM-DD inclusive"),
    lite: bool = Query(
        False,
        description="История UI: без брокера и тяжёлого market snapshot",
    ),
) -> dict[str, Any]:
    try:
        df = _parse_dt(date_from) if date_from else None
        dt_to = _parse_dt(date_to) if date_to else None
        use_range = df is not None or dt_to is not None
        if not use_range:
            if days not in (0, 1, 3, 7, 30):
                days = 7
        else:
            days = 0

        settings = store.get_settings_bundle()
        open_t = store.get_open_trade()
        closed = store.get_closed_trades(10_000)
        snap: dict[str, Any] | None
        if lite:
            snap = {}
        else:
            try:
                snap = engine.market_snapshot()
            except Exception as exc:
                snap = {"error": str(exc)}

        ref = None
        if isinstance(snap, dict) and snap.get("trade_date"):
            ref = _parse_dt(snap.get("trade_date"))
        if ref is None:
            ref = datetime.now()

        from live.closed_metrics import (
            account_after_from_legs,
            attach_account_deltas,
            enrich_closed_trade,
            load_bars_for_window,
        )

        # Δ счёта по полной истории (до фильтра периода), иначе «сегодня» без #7
        # ломает Чист. у #8.
        all_rows = [dict(t) for t in closed]
        for t in all_rows:
            if t.get("account_after_rub") is None:
                aa = account_after_from_legs(t.get("legs") or t.get("legs_json"))
                if aa is not None:
                    t["account_after_rub"] = aa
        attach_account_deltas(all_rows)

        if use_range:
            closed_f = [
                t
                for t in all_rows
                if _within_date_range(
                    t.get("exit_time") or t.get("entry_time"), df, dt_to
                )
            ]
        else:
            closed_f = [
                t
                for t in all_rows
                if _within_depth(t.get("exit_time") or t.get("entry_time"), days, ref)
            ]

        deposit = float(settings.get("entry_deposit_rub") or 10_000)
        leverage = float(settings.get("leverage") or 7)
        need_bars = [t for t in closed_f if _needs_metric_backfill(t)]
        if need_bars:
            entries = [str(t.get("entry_time") or "") for t in need_bars]
            exits = [str(t.get("exit_time") or t.get("entry_time") or "") for t in need_bars]
            bars_all = load_bars_for_window(min(entries), max(exits))
        else:
            bars_all = []

        enriched: list[dict[str, Any]] = []
        for t in closed_f:
            before = dict(t)
            # enrich не должен затирать Чист.=Δ счёта
            keep_delta = t.get("account_delta_rub")
            keep_before = t.get("account_before_rub")
            keep_after = t.get("account_after_rub")
            keep_spread = t.get("spread_pnl_rub")
            keep_pnl = t.get("pnl_rub") if keep_delta is not None else None
            bars = bars_all if (_needs_metric_backfill(t) and bars_all) else None
            out = enrich_closed_trade(
                t, deposit_rub=deposit, leverage=leverage, bars=bars
            )
            if keep_delta is not None:
                out["account_delta_rub"] = keep_delta
                if keep_spread is not None:
                    out["spread_pnl_rub"] = keep_spread
                if keep_pnl is not None:
                    out["pnl_rub"] = keep_pnl
            if keep_before is not None:
                out["account_before_rub"] = keep_before
            if keep_after is not None:
                out["account_after_rub"] = keep_after
            enriched.append(out)
            tid = out.get("id")
            if tid is None:
                continue
            patch = _patch_missing_metrics(before, out)
            if patch:
                try:
                    store.update_closed_trade(int(tid), patch)
                except Exception:
                    pass
        closed_f = enriched

        broker = None
        if not lite:
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

        if lite:
            open_e = open_t
        else:
            open_e = enrich_open_trade(
                open_t,
                z_now=snap.get("z") if isinstance(snap, dict) else None,
                spread_now=snap.get("spread") if isinstance(snap, dict) else None,
                trade_date=snap.get("trade_date") if isinstance(snap, dict) else None,
                entry_threshold=float(settings.get("entry_z") or 1.3),
                tatn_now=snap.get("tatn") if isinstance(snap, dict) else None,
                tatnp_now=snap.get("tatnp") if isinstance(snap, dict) else None,
            )

        return {
            "ok": True,
            "days": days,
            "date_from": date_from or None,
            "date_to": date_to or None,
            "lite": bool(lite),
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
    if body.entry_deposit_rub is not None:
        store.set_setting(
            "entry_deposit_rub",
            str(max(1000.0, min(10_000_000.0, float(body.entry_deposit_rub)))),
        )
    if body.auto_execute is not None:
        store.set_setting("auto_execute", "1" if body.auto_execute else "0")
    if body.regime_z_mode is not None:
        store.set_setting("regime_z_mode", "1" if body.regime_z_mode else "0")
    if body.spread_level_mode is not None:
        store.set_setting("spread_level_mode", "1" if body.spread_level_mode else "0")
    for key, val in (
        ("spread_enter_wide", body.spread_enter_wide),
        ("spread_exit_wide", body.spread_exit_wide),
        ("spread_enter_narrow", body.spread_enter_narrow),
        ("spread_exit_narrow", body.spread_exit_narrow),
    ):
        if val is not None:
            try:
                store.set_setting(key, str(max(0.1, min(20.0, float(val)))))
            except (TypeError, ValueError):
                pass
    tp = _normalize_take_profit_pct(body.take_profit_pct)
    if tp is not None:
        store.set_setting("take_profit_pct", str(tp))
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
