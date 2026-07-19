"""Live trading orchestration: sizing, entry/exit, background Z-monitor."""

from __future__ import annotations

import logging
import threading
import time
from pathlib import Path
from typing import Any

from live.constants import (
    DEFAULT_Z_ENTRY,
    DEFAULT_Z_EXIT,
    MONITOR_INTERVAL_SEC,
    SPREAD_LOT_MIN_LOTS,
    SPREAD_LOT_PROD_DEFAULT_LEVERAGE,
)
from live.lot_sizing import compute_spread_quantity_lots
from live.signals import Position, Signal, determine_z_signal, is_consecutive_m15
from live import store
from live.tinvest import TInvestClient

log = logging.getLogger(__name__)

_monitor_stop = threading.Event()
_monitor_thread: threading.Thread | None = None
_monitor_lock = threading.Lock()
_last_status: dict[str, Any] = {
    "running": False,
    "last_tick_ms": 0,
    "last_message": "",
    "last_z": None,
    "last_bar": None,
}


def client_from_store() -> TInvestClient:
    mode, token, _ = store.get_credentials()
    if not token:
        raise RuntimeError("Токен не сохранён. Введите токен на вкладке Live.")
    return TInvestClient(mode, token)


def market_snapshot() -> dict[str, Any]:
    """Last two M15 bars from replay SQLite (or CSV seed)."""
    from replay.replay_db import ensure_replay_bars

    data_dir = Path(__file__).resolve().parent.parent / "data"
    csv_path = data_dir / "m15_tatn_255d.csv"
    payload = ensure_replay_bars(csv_path, "m15_tatn_255d.csv", online=True, start_date=None)
    bars = payload.get("bars") or []
    if not bars:
        raise RuntimeError("Нет баров M15 — сначала откройте Replay или скачайте CSV")
    last = bars[-1]
    prev = bars[-2] if len(bars) >= 2 else None
    return {
        "count": len(bars),
        "source": payload.get("source"),
        "online": payload.get("online"),
        "bar": last,
        "prev": prev,
        "z": last.get("zScore"),
        "spread": last.get("spreadPercent"),
        "tatn": last.get("tatnClose"),
        "tatnp": last.get("tatnpClose"),
        "trade_date": last.get("tradeDate"),
        "timestamp_ms": last.get("timestampMs"),
    }


def resolve_lots(client: TInvestClient, account_id: str) -> dict[str, Any]:
    snap = market_snapshot()
    tatn = float(snap["tatn"] or 0)
    tatnp = float(snap["tatnp"] or 0)
    if tatn <= 0 or tatnp <= 0:
        raise RuntimeError("Нет цен TATN/TATNP для расчёта лотов")
    pf = client.get_portfolio(account_id)
    cash = client.portfolio_cash_rub(pf)
    if cash is None:
        raise RuntimeError("Не удалось прочитать деньги на счёте (totalAmountCurrencies)")
    margin = client.get_margin_attributes(account_id) if client.mode == "prod" else None
    leverage = None
    if client.mode == "prod":
        leverage = float(store.get_setting("leverage", str(SPREAD_LOT_PROD_DEFAULT_LEVERAGE)) or SPREAD_LOT_PROD_DEFAULT_LEVERAGE)
    sizing = compute_spread_quantity_lots(
        cash_rub=cash,
        price_tatn=tatn,
        price_tatnp=tatnp,
        liquid_portfolio_rub=margin["liquid_portfolio_rub"] if margin else None,
        corrected_margin_rub=margin["corrected_margin_rub"] if margin else None,
        leverage_for_notional=leverage,
    )
    if sizing.quantity_lots < SPREAD_LOT_MIN_LOTS:
        raise RuntimeError(
            f"Недостаточно средств: cash={cash:.0f} ₽, после резерва {sizing.available_rub:.0f} ₽, "
            f"нужно ≈{sizing.go_per_lot_rub:.0f} ₽ на 1 лот"
        )
    return {
        "quantity_lots": sizing.quantity_lots,
        "cash_rub": sizing.cash_rub,
        "available_rub": sizing.available_rub,
        "go_per_lot_rub": sizing.go_per_lot_rub,
        "execution_notional_rub": sizing.execution_notional_rub,
        "price_tatn": sizing.price_tatn,
        "price_tatnp": sizing.price_tatnp,
        "market": snap,
    }


def open_position(position: Position, *, source: str = "MANUAL") -> dict[str, Any]:
    if position not in (Position.LONG, Position.SHORT):
        raise RuntimeError("position must be LONG or SHORT")
    mode, token, account = store.get_credentials()
    if not token or not account:
        raise RuntimeError("Нужны токен и accountId")
    open_t = store.get_open_trade()
    if open_t:
        raise RuntimeError(f"Уже есть открытая позиция #{open_t['id']} ({open_t['direction']})")
    client = TInvestClient(mode, token)
    sizing = resolve_lots(client, account)
    signal = Signal.ENTER_LONG if position == Position.LONG else Signal.ENTER_SHORT
    legs = client.execute_spread_entry(account, signal.value, sizing["quantity_lots"])
    snap = sizing["market"]
    trade_id = store.insert_open_trade(
        {
            "mode": mode,
            "account_id": account,
            "direction": position.value,
            "entry_signal": signal.value,
            "quantity_lots": sizing["quantity_lots"],
            "entry_time": snap.get("trade_date") or "",
            "entry_z": snap.get("z"),
            "entry_spread": snap.get("spread"),
            "entry_tatn": snap.get("tatn"),
            "entry_tatnp": snap.get("tatnp"),
            "execution_notional_rub": sizing["execution_notional_rub"],
            "source": source,
            "legs": legs,
        }
    )
    store.log_event(
        f"{source} вход {position.value} · {sizing['quantity_lots']}+{sizing['quantity_lots']} лот · Z={snap.get('z')}",
        "info",
    )
    return {"ok": True, "trade_id": trade_id, "sizing": sizing, "legs": legs}


def close_position(*, source: str = "MANUAL") -> dict[str, Any]:
    mode, token, account = store.get_credentials()
    if not token or not account:
        raise RuntimeError("Нужны токен и accountId")
    open_t = store.get_open_trade()
    if not open_t:
        raise RuntimeError("Нет открытой позиции")
    client = TInvestClient(mode, token)
    legs = client.execute_spread_exit(
        account,
        open_t["entry_signal"] or open_t["direction"],
        int(open_t["quantity_lots"]),
    )
    snap = market_snapshot()
    closed = store.close_open_trade(
        exit_time=snap.get("trade_date") or "",
        exit_z=snap.get("z"),
        exit_spread=snap.get("spread"),
        pnl_rub=None,
        legs=legs,
    )
    store.log_event(f"{source} выход {open_t['direction']} · Z={snap.get('z')}", "info")
    return {"ok": True, "closed": closed, "legs": legs, "market": snap}


def current_position() -> Position:
    open_t = store.get_open_trade()
    if not open_t:
        return Position.FLAT
    d = (open_t.get("direction") or "").upper()
    if d == "LONG":
        return Position.LONG
    if d == "SHORT":
        return Position.SHORT
    return Position.FLAT


def monitor_tick() -> dict[str, Any]:
    """One monitor cycle: refresh bars, detect edge, optionally auto-exec."""
    global _last_status
    settings = store.get_settings_bundle()
    entry = float(settings.get("entry_z") or DEFAULT_Z_ENTRY)
    exit_z = float(settings.get("exit_z") or DEFAULT_Z_EXIT)
    snap = market_snapshot()
    bar = snap["bar"]
    prev = snap["prev"]
    z = float(bar["zScore"])
    msg = f"{bar.get('tradeDate')} · Z {z:.2f}"
    result: dict[str, Any] = {"z": z, "bar": bar.get("tradeDate"), "signal": Signal.NONE.value}

    if not prev:
        _last_status.update(
            {
                "last_tick_ms": int(time.time() * 1000),
                "last_message": msg + " · нет prev",
                "last_z": z,
                "last_bar": bar.get("tradeDate"),
            }
        )
        return result

    prev_ms = int(prev.get("timestampMs") or 0)
    cur_ms = int(bar.get("timestampMs") or 0)
    last_proc = int(store.get_setting("last_processed_bar_ms", "0") or "0")
    if cur_ms <= last_proc:
        _last_status.update(
            {
                "last_tick_ms": int(time.time() * 1000),
                "last_message": msg + " · уже обработан",
                "last_z": z,
                "last_bar": bar.get("tradeDate"),
            }
        )
        return result

    if not is_consecutive_m15(prev_ms, cur_ms):
        store.set_setting("last_processed_bar_ms", str(cur_ms))
        _last_status.update(
            {
                "last_tick_ms": int(time.time() * 1000),
                "last_message": msg + " · пропуск (не consecutive)",
                "last_z": z,
                "last_bar": bar.get("tradeDate"),
            }
        )
        return result

    pos = current_position()
    signal = determine_z_signal(float(prev["zScore"]), z, pos, entry, exit_z)
    result["signal"] = signal.value
    store.set_setting("last_processed_bar_ms", str(cur_ms))

    auto = settings.get("auto_execute")
    if signal != Signal.NONE:
        store.log_event(f"Сигнал {signal.value} @ {bar.get('tradeDate')} Z={z:.2f}", "signal")
        if auto:
            try:
                if signal == Signal.ENTER_LONG:
                    open_position(Position.LONG, source="AUTO")
                elif signal == Signal.ENTER_SHORT:
                    open_position(Position.SHORT, source="AUTO")
                elif signal in (Signal.EXIT_LONG, Signal.EXIT_SHORT):
                    close_position(source="AUTO")
                msg += f" · AUTO {signal.value}"
            except Exception as exc:
                store.log_event(f"AUTO fail {signal.value}: {exc}", "error")
                msg += f" · AUTO err: {exc}"
        else:
            msg += f" · сигнал {signal.value} (auto выкл)"

    _last_status.update(
        {
            "last_tick_ms": int(time.time() * 1000),
            "last_message": msg,
            "last_z": z,
            "last_bar": bar.get("tradeDate"),
        }
    )
    return result


def _monitor_loop() -> None:
    store.log_event("Монитор запущен", "info")
    while not _monitor_stop.is_set():
        try:
            monitor_tick()
        except Exception as exc:
            store.log_event(f"Монитор: {exc}", "error")
            _last_status["last_message"] = str(exc)
            _last_status["last_tick_ms"] = int(time.time() * 1000)
        _monitor_stop.wait(MONITOR_INTERVAL_SEC)
    store.log_event("Монитор остановлен", "info")
    _last_status["running"] = False


def start_monitor() -> dict[str, Any]:
    global _monitor_thread
    with _monitor_lock:
        if _monitor_thread and _monitor_thread.is_alive():
            return {"ok": True, "running": True, "message": "уже запущен"}
        _monitor_stop.clear()
        store.set_setting("monitor_running", "1")
        _last_status["running"] = True
        _monitor_thread = threading.Thread(target=_monitor_loop, daemon=True, name="live-monitor")
        _monitor_thread.start()
    return {"ok": True, "running": True}


def stop_monitor() -> dict[str, Any]:
    with _monitor_lock:
        _monitor_stop.set()
        store.set_setting("monitor_running", "0")
        _last_status["running"] = False
    return {"ok": True, "running": False}


def monitor_status() -> dict[str, Any]:
    alive = bool(_monitor_thread and _monitor_thread.is_alive())
    return {
        **_last_status,
        "running": alive,
        "settings": store.get_settings_bundle(),
        "open": store.get_open_trade(),
    }
