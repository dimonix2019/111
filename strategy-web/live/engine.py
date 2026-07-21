"""Live trading orchestration: sizing, entry/exit, background Z-monitor."""

from __future__ import annotations

import logging
import sys
import threading
import time
from pathlib import Path
from typing import Any

from live.constants import (
    DEFAULT_Z_ENTRY,
    DEFAULT_Z_EXIT,
    MONITOR_CATCHUP_MAX_EDGES,
    MONITOR_HEARTBEAT_SEC,
    MONITOR_INTERVAL_SEC,
    MONITOR_STALE_SEC,
    MONITOR_SYNC_TIMEOUT_SEC,
    SPREAD_LOT_MIN_LOTS,
    SPREAD_LOT_PROD_DEFAULT_LEVERAGE,
)
from live.lot_sizing import compute_spread_quantity_lots
from live.signals import (
    Position,
    Signal,
    determine_z_signal,
    is_consecutive_m15,
    is_moex_equity_session_bar,
    plan_monitor_catchup,
)
from live import store
from live.tinvest import TInvestClient

log = logging.getLogger(__name__)

_monitor_stop = threading.Event()
_monitor_thread: threading.Thread | None = None
_monitor_lock = threading.Lock()
_last_heartbeat_ms = 0
_monitor_started_ms = 0
_keep_awake_held = False
_last_status: dict[str, Any] = {
    "running": False,
    "last_tick_ms": 0,
    "last_message": "",
    "last_z": None,
    "last_bar": None,
}

# Windows: не уводить ПК в сон, пока крутится монитор.
_ES_CONTINUOUS = 0x80000000
_ES_SYSTEM_REQUIRED = 0x00000001
_ES_AWAYMODE_REQUIRED = 0x00000040


def _set_monitor_keep_awake(enabled: bool) -> None:
    """Prevent system sleep while live monitor is running (Windows)."""
    global _keep_awake_held
    if sys.platform != "win32":
        return
    try:
        import ctypes

        if enabled:
            flags = _ES_CONTINUOUS | _ES_SYSTEM_REQUIRED | _ES_AWAYMODE_REQUIRED
            ctypes.windll.kernel32.SetThreadExecutionState(flags)
            _keep_awake_held = True
        elif _keep_awake_held:
            ctypes.windll.kernel32.SetThreadExecutionState(_ES_CONTINUOUS)
            _keep_awake_held = False
    except Exception as exc:
        log.warning("keep-awake failed: %s", exc)


def client_from_store() -> TInvestClient:
    mode, token, _ = store.get_credentials()
    if not token:
        raise RuntimeError("Токен не сохранён. Введите токен на вкладке Live.")
    return TInvestClient(mode, token)


def market_snapshot(
    *,
    wait_sync: bool = False,
    sync_timeout_sec: float | None = None,
) -> dict[str, Any]:
    """M15 bars from replay SQLite (or CSV seed).

    wait_sync=True — дождаться MOEX tail (monitor tick), иначе фон + кэш.
    После кэша всегда пробуем быстрый LAST-tip (утро без свечей / тик внутри слота).
    """
    from replay.replay_db import ensure_replay_bars, seed_from_csv
    from m15_iss_loader import apply_live_last_overlay

    data_dir = Path(__file__).resolve().parent.parent / "data"
    csv_path = data_dir / "m15_tatn_255d.csv"
    timeout = sync_timeout_sec
    if timeout is None:
        timeout = 30.0 if wait_sync else 25.0
    payload = ensure_replay_bars(
        csv_path,
        "m15_tatn_255d.csv",
        online=True,
        start_date=None,
        wait_sync=wait_sync,
        sync_timeout_sec=timeout,
    )
    # Desk/UI без wait_sync: не ждать полный ISS, но подтянуть LAST (~0.5с).
    if not wait_sync and apply_live_last_overlay(csv_path):
        seed_from_csv(csv_path, "m15_tatn_255d.csv")
        payload = ensure_replay_bars(
            csv_path,
            "m15_tatn_255d.csv",
            online=False,
            start_date=None,
        )
        payload = {**payload, "refreshed": True, "online": True}

    bars = payload.get("bars") or []
    if not bars:
        raise RuntimeError("Нет баров M15 — сначала откройте Replay или скачайте CSV")
    last = bars[-1]
    prev = bars[-2] if len(bars) >= 2 else None
    return {
        "count": len(bars),
        "source": payload.get("source"),
        "online": payload.get("online"),
        "refreshed": payload.get("refreshed"),
        "bars": bars,
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


def reconcile_broker_open_trade(
    *,
    portfolio: dict[str, Any] | None = None,
    market: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """
    Синхронизация локального open с брокером:
    - спред есть, локально пусто → adopt (сделка из APK / Тинькофф);
    - локально open, на брокере пусто → закрыть локальный ghost (ручной выход и т.п.);
    - оба есть, разные стороны → предупреждение (не перетираем автоматически).
    """
    mode, token, account = store.get_credentials()
    if not token or not account:
        return {"ok": False, "reason": "no_credentials"}
    client = TInvestClient(mode, token)
    try:
        pf = portfolio if portfolio is not None else client.get_portfolio(account)
    except Exception as exc:
        return {"ok": False, "error": str(exc)}

    spread = client.detect_spread_position(pf)
    local = store.get_open_trade()
    snap = market if market is not None else None

    if local and not spread:
        if snap is None:
            snap = market_snapshot()
        closed = store.close_open_trade(
            exit_time=str(snap.get("trade_date") or ""),
            exit_z=snap.get("z"),
            exit_spread=snap.get("spread"),
            pnl_rub=None,
            legs=[{"note": "broker flat — local open cleared"}],
        )
        store.log_event(
            f"BROKER sync: закрыт локальный {local.get('direction')} "
            f"(на брокере спреда нет) · бар {snap.get('trade_date')}",
            "info",
        )
        return {
            "ok": True,
            "adopted": False,
            "closed_local": True,
            "closed": closed,
            "broker": None,
        }

    if spread and not local:
        if snap is None:
            snap = market_snapshot()
        trade_id = store.insert_open_trade(
            {
                "mode": mode,
                "account_id": account,
                "direction": spread["direction"],
                "entry_signal": spread["entry_signal"],
                "quantity_lots": spread["quantity_lots"],
                "entry_time": snap.get("trade_date") or "",
                "entry_z": snap.get("z"),
                "entry_spread": snap.get("spread"),
                "entry_tatn": snap.get("tatn"),
                "entry_tatnp": snap.get("tatnp"),
                "execution_notional_rub": None,
                "source": "BROKER",
                "legs": [
                    {
                        "ticker": "TATN",
                        "qty": spread["qty_tatn"],
                        "note": "sync from portfolio",
                    },
                    {
                        "ticker": "TATNP",
                        "qty": spread["qty_tatnp"],
                        "note": "sync from portfolio",
                    },
                ],
            }
        )
        store.log_event(
            f"BROKER sync: открыт {spread['direction']} · "
            f"{spread['quantity_lots']}+{spread['quantity_lots']} лот "
            f"(TATN={spread['qty_tatn']}, TATNP={spread['qty_tatnp']})",
            "info",
        )
        return {"ok": True, "adopted": True, "trade_id": trade_id, "broker": spread}

    if local and spread:
        loc_dir = (local.get("direction") or "").upper()
        br_dir = (spread.get("direction") or "").upper()
        if loc_dir and br_dir and loc_dir != br_dir:
            store.log_event(
                f"BROKER sync: конфликт направления local={loc_dir} broker={br_dir}",
                "warn",
            )
            return {
                "ok": True,
                "adopted": False,
                "conflict": True,
                "broker": spread,
                "local_direction": loc_dir,
            }

    return {
        "ok": True,
        "adopted": False,
        "broker": spread,
        "local": bool(local),
        "local_direction": (local or {}).get("direction"),
    }


def _maybe_monitor_heartbeat(bar: dict[str, Any], z: float) -> None:
    global _last_heartbeat_ms
    now = int(time.time() * 1000)
    if _last_heartbeat_ms and (now - _last_heartbeat_ms) < MONITOR_HEARTBEAT_SEC * 1000:
        return
    _last_heartbeat_ms = now
    store.log_event(
        f"Монитор OK · {bar.get('tradeDate')} · Z={z:.2f}",
        "info",
    )


def _auto_execute_signal(
    signal: Signal,
    *,
    bar: dict[str, Any],
    cur_ms: int,
    z: float,
    entry: float,
    exit_z: float,
) -> str:
    """Execute AUTO trade for one edge; returns status suffix for last_message."""
    trade_id = None
    if signal == Signal.ENTER_LONG:
        opened = open_position(Position.LONG, source="AUTO")
        trade_id = opened.get("trade_id")
    elif signal == Signal.ENTER_SHORT:
        opened = open_position(Position.SHORT, source="AUTO")
        trade_id = opened.get("trade_id")
    elif signal in (Signal.EXIT_LONG, Signal.EXIT_SHORT):
        close_position(source="AUTO")
    else:
        return ""
    try:
        from live.parity import schedule_parity_for_auto

        schedule_parity_for_auto(
            bar_ts=str(bar.get("tradeDate") or ""),
            bar_ms=cur_ms,
            signal=signal.value,
            z_score=z,
            entry_z=entry,
            exit_z=exit_z,
            trade_id=trade_id,
        )
    except Exception as parity_exc:
        log.warning("parity schedule failed: %s", parity_exc)
    return f" · AUTO {signal.value}"


def monitor_tick() -> dict[str, Any]:
    """One monitor cycle: sync tip bar, trade only the next live edge (no replay)."""
    global _last_status
    settings = store.get_settings_bundle()
    entry = float(settings.get("entry_z") or DEFAULT_Z_ENTRY)
    exit_z = float(settings.get("exit_z") or DEFAULT_Z_EXIT)
    # Короткий sync — не зависать на ISS.
    snap = market_snapshot(wait_sync=True, sync_timeout_sec=MONITOR_SYNC_TIMEOUT_SEC)
    try:
        reconcile_broker_open_trade()
    except Exception as sync_exc:
        log.warning("broker reconcile failed: %s", sync_exc)

    bars = list(snap.get("bars") or [])
    bar = snap["bar"]
    z = float(bar["zScore"])
    msg = f"{bar.get('tradeDate')} · Z {z:.2f}"
    result: dict[str, Any] = {
        "z": z,
        "bar": bar.get("tradeDate"),
        "signal": Signal.NONE.value,
        "catchup_edges": 0,
        "signals": [],
    }

    if len(bars) < 2:
        _last_status.update(
            {
                "last_tick_ms": int(time.time() * 1000),
                "last_message": msg + " · нет prev",
                "last_z": z,
                "last_bar": bar.get("tradeDate"),
            }
        )
        return result

    last_proc = int(store.get_setting("last_processed_bar_ms", "0") or "0")
    mode, edges = plan_monitor_catchup(
        bars,
        last_proc,
        max_edges=MONITOR_CATCHUP_MAX_EDGES,
    )

    if mode == "bootstrap":
        anchor_ms = int(bar.get("timestampMs") or 0)
        store.set_setting("last_processed_bar_ms", str(anchor_ms))
        store.log_event(
            f"Монитор: якорь на {bar.get('tradeDate')} (без реплея истории)",
            "info",
        )
        _maybe_monitor_heartbeat(bar, z)
        _last_status.update(
            {
                "last_tick_ms": int(time.time() * 1000),
                "last_message": msg + " · якорь",
                "last_z": z,
                "last_bar": bar.get("tradeDate"),
            }
        )
        return result

    if mode == "up_to_date":
        _maybe_monitor_heartbeat(bar, z)
        _last_status.update(
            {
                "last_tick_ms": int(time.time() * 1000),
                "last_message": msg + " · уже обработан",
                "last_z": z,
                "last_bar": bar.get("tradeDate"),
            }
        )
        return result

    if mode == "skip_gap":
        # Пропуски не догоняем сделками — только якорь на хвост + предупреждение.
        tip_ms = int(bar.get("timestampMs") or 0)
        n_miss = max(0, len(edges))
        store.set_setting("last_processed_bar_ms", str(tip_ms))
        store.log_event(
            f"Монитор: пропуск {n_miss} бар(ов) без AUTO-догона → якорь {bar.get('tradeDate')}",
            "warn",
        )
        _maybe_monitor_heartbeat(bar, z)
        _last_status.update(
            {
                "last_tick_ms": int(time.time() * 1000),
                "last_message": msg + f" · skip×{n_miss}",
                "last_z": z,
                "last_bar": bar.get("tradeDate"),
            }
        )
        return result

    # mode == "live": ровно одно следующее ребро
    auto = bool(settings.get("auto_execute"))
    for prev, cur in edges:
        prev_ms = int(prev.get("timestampMs") or 0)
        cur_ms = int(cur.get("timestampMs") or 0)
        cur_z = float(cur["zScore"])
        cur_td = str(cur.get("tradeDate") or "")

        if not is_consecutive_m15(prev_ms, cur_ms):
            store.log_event(
                f"Монитор: дыра в барах {prev.get('tradeDate')} → {cur_td} (сигнал пропущен)",
                "warn",
            )
            store.set_setting("last_processed_bar_ms", str(cur_ms))
            continue

        if not is_moex_equity_session_bar(cur_td):
            store.log_event(
                f"Монитор: вне сессии TQBR {cur_td} (сигнал пропущен)",
                "info",
            )
            store.set_setting("last_processed_bar_ms", str(cur_ms))
            continue

        pos = current_position()
        signal = determine_z_signal(float(prev["zScore"]), cur_z, pos, entry, exit_z)
        store.set_setting("last_processed_bar_ms", str(cur_ms))
        result["catchup_edges"] = 1

        if signal == Signal.NONE:
            continue

        result["signal"] = signal.value
        result["signals"] = [f"{signal.value}@{cur_td}"]
        store.log_event(f"Сигнал {signal.value} @ {cur_td} Z={cur_z:.2f}", "signal")

        if auto:
            try:
                msg += _auto_execute_signal(
                    signal,
                    bar=cur,
                    cur_ms=cur_ms,
                    z=cur_z,
                    entry=entry,
                    exit_z=exit_z,
                )
            except Exception as exc:
                store.log_event(f"AUTO fail {signal.value}: {exc}", "error")
                msg += f" · AUTO err: {exc}"
        else:
            msg += f" · сигнал {signal.value} (auto выкл)"

    try:
        from live.parity import process_due_parity_checks

        process_due_parity_checks()
    except Exception as parity_exc:
        log.warning("parity process failed: %s", parity_exc)

    _maybe_monitor_heartbeat(bar, z)
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
    _set_monitor_keep_awake(True)
    store.log_event("Монитор: keep-awake ON (ПК не уходит в сон)", "info")
    try:
        while not _monitor_stop.is_set():
            try:
                monitor_tick()
            except Exception as exc:
                store.log_event(f"Монитор: {exc}", "error")
                _last_status["last_message"] = str(exc)
                _last_status["last_tick_ms"] = int(time.time() * 1000)
            # Периодически обновляем keep-awake (Windows сбрасывает через время).
            _set_monitor_keep_awake(True)
            _monitor_stop.wait(MONITOR_INTERVAL_SEC)
    finally:
        _set_monitor_keep_awake(False)
        store.log_event("Монитор остановлен", "info")
        _last_status["running"] = False


def start_monitor() -> dict[str, Any]:
    global _monitor_thread, _monitor_started_ms
    with _monitor_lock:
        if _monitor_thread and _monitor_thread.is_alive():
            return {"ok": True, "running": True, "message": "уже запущен"}
        _monitor_stop.clear()
        store.set_setting("monitor_running", "1")
        _last_status["running"] = True
        _monitor_started_ms = int(time.time() * 1000)
        _monitor_thread = threading.Thread(target=_monitor_loop, daemon=True, name="live-monitor")
        _monitor_thread.start()
    return {"ok": True, "running": True}


def stop_monitor() -> dict[str, Any]:
    with _monitor_lock:
        _monitor_stop.set()
        store.set_setting("monitor_running", "0")
        _last_status["running"] = False
        thread = _monitor_thread
    if thread and thread.is_alive() and thread is not threading.current_thread():
        thread.join(timeout=MONITOR_SYNC_TIMEOUT_SEC + 5.0)
    _set_monitor_keep_awake(False)
    return {"ok": True, "running": False}


def restart_monitor() -> dict[str, Any]:
    """Stop+start — для watchdog, когда поток жив, но last_tick протух."""
    stop_monitor()
    return start_monitor()


def monitor_status() -> dict[str, Any]:
    alive = bool(_monitor_thread and _monitor_thread.is_alive())
    return {
        **_last_status,
        "running": alive,
        "settings": store.get_settings_bundle(),
        "open": store.get_open_trade(),
    }


def health_live() -> dict[str, Any]:
    """Liveness + business probe для внешнего watchdog."""
    now_ms = int(time.time() * 1000)
    wanted = bool(store.get_settings_bundle().get("monitor_running"))
    alive = bool(_monitor_thread and _monitor_thread.is_alive())
    last_tick_ms = int(_last_status.get("last_tick_ms") or 0)
    ref_ms = last_tick_ms if last_tick_ms > 0 else int(_monitor_started_ms or 0)
    age_sec: float | None
    if ref_ms > 0:
        age_sec = max(0.0, (now_ms - ref_ms) / 1000.0)
    else:
        age_sec = None
    stale = bool(
        wanted
        and (
            not alive
            or (age_sec is not None and age_sec > MONITOR_STALE_SEC)
        )
    )
    return {
        "status": "ok",
        "monitor_wanted": wanted,
        "monitor_alive": alive,
        "last_tick_ms": last_tick_ms or None,
        "last_tick_age_sec": round(age_sec, 1) if age_sec is not None else None,
        "stale_after_sec": MONITOR_STALE_SEC,
        "stale": stale,
        "last_bar": _last_status.get("last_bar"),
        "last_z": _last_status.get("last_z"),
        "last_message": _last_status.get("last_message"),
    }
