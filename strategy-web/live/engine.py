"""Live trading orchestration: sizing, entry/exit, background Z-monitor."""

from __future__ import annotations

import json
import logging
import sys
import threading
import time
from pathlib import Path
from typing import Any

from live.constants import (
    DEFAULT_Z_ENTRY,
    DEFAULT_Z_EXIT,
    MONITOR_BAR_SETTLE_SEC,
    MONITOR_CATCHUP_MAX_EDGES,
    MONITOR_HEARTBEAT_SEC,
    MONITOR_INTERVAL_SEC,
    MONITOR_STALE_SEC,
    MONITOR_SYNC_TIMEOUT_SEC,
    MONITOR_Z_REVISE_MIN_DELTA,
    SPREAD_LOT_MIN_LOTS,
    SPREAD_LOT_PROD_DEFAULT_LEVERAGE,
)
from live.lot_sizing import compute_spread_quantity_lots
from live.signals import (
    Position,
    Signal,
    determine_z_signal,
    find_bar_index,
    is_consecutive_m15,
    is_moex_equity_session_bar,
    last_settled_bar_index,
    plan_monitor_catchup,
    should_revise_none_to_signal,
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

    wait_sync=True — дождаться MOEX tail (monitor tick), полный ряд для сигнала.
    UI-путь: только хвост (2 бара) + LAST-tip без полного seed.
    """
    from replay.replay_db import ensure_replay_bars, load_last_bars, seed_from_csv, db_bar_count
    from m15_iss_loader import apply_live_last_overlay

    data_dir = Path(__file__).resolve().parent.parent / "data"
    csv_path = data_dir / "m15_tatn_255d.csv"
    timeout = sync_timeout_sec
    if timeout is None:
        timeout = 30.0 if wait_sync else 25.0

    tip_refreshed = False
    if wait_sync:
        # Сигнальный ряд — только официальные M15 из ISS/кэша.
        # LAST-overlay НЕ сидим в SQLite: утренний mid может дать ложный Z (сегодня 07:00
        # кратко Z≈−4 при spread≈2%, затем пересчёт до Z≈−0.56) → ложный AUTO.
        payload = ensure_replay_bars(
            csv_path,
            "m15_tatn_255d.csv",
            online=True,
            start_date=None,
            wait_sync=True,
            sync_timeout_sec=timeout,
        )
        bars = payload.get("bars") or []
        source = payload.get("source")
        online = payload.get("online")
        refreshed = bool(payload.get("refreshed"))
    else:
        # Фон: лёгкий sync без блокировки ответа
        ensure_replay_bars(
            csv_path,
            "m15_tatn_255d.csv",
            online=True,
            start_date=None,
            wait_sync=False,
        )
        tip_refreshed = bool(apply_live_last_overlay(csv_path))
        bars = load_last_bars(2)
        if len(bars) < 1 and csv_path.is_file():
            # холодный старт — один раз полный кэш
            payload = ensure_replay_bars(
                csv_path, "m15_tatn_255d.csv", online=False, start_date=None
            )
            bars = (payload.get("bars") or [])[-2:]
        source = "sqlite" if db_bar_count() > 0 else "csv"
        online = True
        refreshed = tip_refreshed

    if not bars:
        raise RuntimeError("Нет баров M15 — сначала откройте Replay или скачайте CSV")
    last = dict(bars[-1])
    prev = bars[-2] if len(bars) >= 2 else None
    if tip_refreshed and not wait_sync:
        try:
            from m15_iss_loader import _csv_last_timestamp
            import pandas as pd

            tip_ts = _csv_last_timestamp(csv_path)
            if tip_ts:
                row = pd.read_csv(csv_path).iloc[-1]
                last = {
                    **last,
                    "tradeDate": str(tip_ts),
                    "tatnClose": float(row["tatn_close"]),
                    "tatnpClose": float(row["tatnp_close"]),
                    "spreadPercent": float(
                        row.get("spread_percent") or last.get("spreadPercent") or 0
                    ),
                    "zScore": float(row.get("z_score") or last.get("zScore") or 0),
                }
        except Exception:
            pass
    return {
        "count": db_bar_count() if not wait_sync else len(bars),
        "source": source,
        "online": online,
        "refreshed": refreshed,
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
    try:
        deposit = float(store.get_setting("entry_deposit_rub", "10000") or "10000")
    except ValueError:
        deposit = 10000.0
    deposit = max(1000.0, min(10_000_000.0, deposit))
    cash_for_entry = min(float(cash), deposit)
    margin = client.get_margin_attributes(account_id) if client.mode == "prod" else None
    leverage = None
    liquid = margin["liquid_portfolio_rub"] if margin else None
    if client.mode == "prod":
        leverage = float(store.get_setting("leverage", str(SPREAD_LOT_PROD_DEFAULT_LEVERAGE)) or SPREAD_LOT_PROD_DEFAULT_LEVERAGE)
        # Плечо тоже ограничиваем выбранным депозитом на вход
        if liquid is not None and liquid > 0:
            liquid = min(float(liquid), deposit)
    sizing = compute_spread_quantity_lots(
        cash_rub=cash_for_entry,
        price_tatn=tatn,
        price_tatnp=tatnp,
        liquid_portfolio_rub=liquid,
        corrected_margin_rub=margin["corrected_margin_rub"] if margin else None,
        leverage_for_notional=leverage,
    )
    if sizing.quantity_lots < SPREAD_LOT_MIN_LOTS:
        raise RuntimeError(
            f"Недостаточно средств: cash={cash:.0f} ₽, депозит на вход {deposit:.0f} ₽ "
            f"(в расчёте {cash_for_entry:.0f} ₽), нужно ≈{sizing.go_per_lot_rub:.0f} ₽ на 1 лот"
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


def open_position(
    position: Position,
    *,
    source: str = "MANUAL",
    signal_bar: dict[str, Any] | None = None,
) -> dict[str, Any]:
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
    from live.open_mark import (
        adverse_entry_slip_pts,
        fill_prices_from_legs,
        pick_iss_spread_for_slip,
    )

    # Edge-бар сигнала (не live tip): late settle/revise часто исполняется уже на следующем слоте
    sig = signal_bar if isinstance(signal_bar, dict) else {}
    entry_time = str(sig.get("tradeDate") or snap.get("trade_date") or "")
    entry_z = sig.get("zScore") if sig.get("zScore") is not None else snap.get("z")
    try:
        entry_z = float(entry_z) if entry_z is not None else None
    except (TypeError, ValueError):
        entry_z = snap.get("z")

    entry_tatn = snap.get("tatn")
    entry_tatnp = snap.get("tatnp")
    prev = snap.get("prev") if isinstance(snap.get("prev"), dict) else {}
    entry_spread_iss = snap.get("spread")
    entry_spread = entry_spread_iss
    entry_slip_pts = None
    # Канон для PnL/сверки — цены исполнения, не ISS mid
    fill_tatn, fill_tatnp = fill_prices_from_legs({"legs_json": json.dumps(legs, ensure_ascii=False)})
    if fill_tatn and fill_tatnp and fill_tatnp > 0:
        entry_tatn = fill_tatn
        entry_tatnp = fill_tatnp
        entry_spread = (fill_tatn - fill_tatnp) / fill_tatnp * 100.0
        entry_spread_iss = pick_iss_spread_for_slip(
            snap_spread=snap.get("spread"),
            snap_tatn=snap.get("tatn"),
            snap_tatnp=snap.get("tatnp"),
            prev_spread=prev.get("spreadPercent"),
            prev_tatn=prev.get("tatnClose"),
            prev_tatnp=prev.get("tatnpClose"),
            fill_tatn=fill_tatn,
            fill_tatnp=fill_tatnp,
        )
        entry_slip_pts = adverse_entry_slip_pts(position.value, entry_spread_iss, entry_spread)
    trade_id = store.insert_open_trade(
        {
            "mode": mode,
            "account_id": account,
            "direction": position.value,
            "entry_signal": signal.value,
            "quantity_lots": sizing["quantity_lots"],
            "entry_time": entry_time,
            "entry_z": entry_z,
            "entry_spread": entry_spread,
            "entry_spread_iss": entry_spread_iss,
            "entry_slip_pts": entry_slip_pts,
            "entry_tatn": entry_tatn,
            "entry_tatnp": entry_tatnp,
            "execution_notional_rub": sizing["execution_notional_rub"],
            "source": source,
            "legs": legs,
        }
    )
    store.log_event(
        f"{source} вход {position.value} · {sizing['quantity_lots']}+{sizing['quantity_lots']} лот · "
        f"Z={entry_z} · бар {entry_time[:16] if entry_time else '—'}",
        "info",
    )
    return {"ok": True, "trade_id": trade_id, "sizing": sizing, "legs": legs}


def _closed_metrics_for_open(
    open_t: dict[str, Any],
    *,
    exit_time: str,
    exit_spread: float | None,
) -> dict[str, Any]:
    from live.closed_metrics import enrich_closed_trade, load_bars_for_window

    settings = store.get_settings_bundle()
    draft = {
        **open_t,
        "exit_time": exit_time,
        "exit_spread": exit_spread if exit_spread is not None else open_t.get("entry_spread"),
        "execution_notional_rub": open_t.get("execution_notional_rub"),
    }
    bars = load_bars_for_window(open_t.get("entry_time"), exit_time)
    return enrich_closed_trade(
        draft,
        deposit_rub=float(settings.get("entry_deposit_rub") or 10_000),
        leverage=float(settings.get("leverage") or 7),
        bars=bars,
    )


def close_position(
    *,
    source: str = "MANUAL",
    signal_bar: dict[str, Any] | None = None,
) -> dict[str, Any]:
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
    sig = signal_bar if isinstance(signal_bar, dict) else {}
    exit_time = str(sig.get("tradeDate") or snap.get("trade_date") or "")
    exit_z = sig.get("zScore") if sig.get("zScore") is not None else snap.get("z")
    try:
        exit_z = float(exit_z) if exit_z is not None else None
    except (TypeError, ValueError):
        exit_z = snap.get("z")
    # Спред для PnL — с live tip (цены ближе к фактическому fill), время/Z — с бара сигнала
    exit_spread = snap.get("spread")
    metrics = _closed_metrics_for_open(open_t, exit_time=exit_time, exit_spread=exit_spread)
    # Сумма на счету после выхода (total, иначе cash) — из последней ноги или свежий портфель
    account_after = None
    for leg in reversed(legs or []):
        if not isinstance(leg, dict):
            continue
        for key in ("portfolio_total_rub", "portfolio_cash_rub"):
            v = leg.get(key)
            if v is not None:
                try:
                    account_after = float(v)
                    break
                except (TypeError, ValueError):
                    pass
        if account_after is not None:
            break
    if account_after is None:
        try:
            pf = client.get_portfolio(account)
            account_after = client.portfolio_total_rub(pf)
            if account_after is None:
                account_after = client.portfolio_cash_rub(pf)
        except Exception:
            pass
    if account_after is not None:
        metrics["account_after_rub"] = account_after
    closed = store.close_open_trade(
        exit_time=exit_time,
        exit_z=exit_z,
        exit_spread=exit_spread,
        pnl_rub=metrics.get("pnl_rub"),
        legs=legs,
        metrics=metrics,
    )
    store.log_event(
        f"{source} выход {open_t['direction']} · Z={exit_z} · бар {exit_time[:16] if exit_time else '—'}",
        "info",
    )
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
        exit_time = str(snap.get("trade_date") or "")
        exit_spread = snap.get("spread")
        metrics = _closed_metrics_for_open(local, exit_time=exit_time, exit_spread=exit_spread)
        closed = store.close_open_trade(
            exit_time=exit_time,
            exit_z=snap.get("z"),
            exit_spread=exit_spread,
            pnl_rub=metrics.get("pnl_rub"),
            legs=[{"note": "broker flat — local open cleared"}],
            metrics=metrics,
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
                "entry_spread_iss": snap.get("spread"),
                "entry_slip_pts": 0.0,  # adopt без fill → mid=ISS, slip 0
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
    prev_bar: dict[str, Any] | None = None,
) -> str:
    """Execute AUTO trade for one edge; returns status suffix for last_message."""
    from live.signals import is_implausible_spread_jump

    if signal in (Signal.ENTER_LONG, Signal.ENTER_SHORT) and prev_bar is not None:
        prev_sp = prev_bar.get("spreadPercent")
        if prev_sp is None:
            prev_sp = prev_bar.get("spread")
        cur_sp = bar.get("spreadPercent")
        if cur_sp is None:
            cur_sp = bar.get("spread")
        if is_implausible_spread_jump(prev_sp, cur_sp):
            store.log_event(
                f"AUTO пропуск {signal.value} @ {str(bar.get('tradeDate') or '')[:16]}: "
                f"скачок спреда {prev_sp}→{cur_sp} (подозрение на LAST/tip)",
                "warn",
            )
            return f" · skip {signal.value} (spread jump)"

    trade_id = None
    if signal == Signal.ENTER_LONG:
        opened = open_position(Position.LONG, source="AUTO", signal_bar=bar)
        trade_id = opened.get("trade_id")
    elif signal == Signal.ENTER_SHORT:
        opened = open_position(Position.SHORT, source="AUTO", signal_bar=bar)
        trade_id = opened.get("trade_id")
    elif signal in (Signal.EXIT_LONG, Signal.EXIT_SHORT):
        close_position(source="AUTO", signal_bar=bar)
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


def _record_edge_snapshot(bar_ms: int, z: float, signal: Signal) -> None:
    store.set_setting("last_edge_bar_ms", str(bar_ms))
    store.set_setting("last_edge_z", f"{z:.6f}")
    store.set_setting("last_edge_signal", signal.value)
    # Actionable already fired — no late revise. NONE may be upgraded once.
    store.set_setting("last_edge_revised", "0" if signal == Signal.NONE else "1")


def _maybe_late_z_revise(
    bars: list[dict[str, Any]],
    *,
    entry: float,
    exit_z: float,
    auto: bool,
    msg: str,
    result: dict[str, Any],
) -> str:
    """If last processed bar was NONE and Z later strengthens into an edge — fire once."""
    last_proc = int(store.get_setting("last_processed_bar_ms", "0") or "0")
    edge_ms = int(store.get_setting("last_edge_bar_ms", "0") or "0")
    if last_proc <= 0 or edge_ms != last_proc:
        return msg
    if store.get_setting("last_edge_revised", "0") == "1":
        return msg

    idx = find_bar_index(bars, last_proc)
    if idx is None or idx < 1:
        return msg

    prev = bars[idx - 1]
    cur = bars[idx]
    try:
        old_z = float(store.get_setting("last_edge_z", "nan") or "nan")
    except ValueError:
        return msg
    new_z = float(cur["zScore"])
    old_sig = store.get_setting("last_edge_signal", Signal.NONE.value) or Signal.NONE.value
    pos = current_position()
    new_sig = determine_z_signal(float(prev["zScore"]), new_z, pos, entry, exit_z)

    if not should_revise_none_to_signal(
        old_signal=old_sig,
        new_signal=new_sig,
        old_z=old_z,
        new_z=new_z,
        min_delta=MONITOR_Z_REVISE_MIN_DELTA,
    ):
        # Keep snapshot Z fresh so small drifts don't accumulate forever.
        if new_z == new_z and abs(new_z - old_z) >= 1e-9:
            store.set_setting("last_edge_z", f"{new_z:.6f}")
        return msg

    cur_td = str(cur.get("tradeDate") or "")
    cur_ms = int(cur.get("timestampMs") or 0)
    store.set_setting("last_edge_revised", "1")
    store.set_setting("last_edge_z", f"{new_z:.6f}")
    store.set_setting("last_edge_signal", new_sig.value)
    result["signal"] = new_sig.value
    result.setdefault("signals", []).append(f"{new_sig.value}@{cur_td}:revise")
    store.log_event(
        f"Сигнал {new_sig.value} @ {cur_td} Z={new_z:.2f} "
        f"(Z revise, было {old_z:.2f} / {old_sig})",
        "signal",
    )
    if auto:
        try:
            msg += _auto_execute_signal(
                new_sig,
                bar=cur,
                cur_ms=cur_ms,
                z=new_z,
                entry=entry,
                exit_z=exit_z,
                prev_bar=prev,
            )
            msg += " · revise"
        except Exception as exc:
            store.log_event(f"AUTO fail {new_sig.value} (revise): {exc}", "error")
            msg += f" · AUTO revise err: {exc}"
    else:
        msg += f" · сигнал {new_sig.value} revise (auto выкл)"
    return msg


def monitor_tick() -> dict[str, Any]:
    """One monitor cycle: sync tip bar, AUTO on consecutive edges since last_proc (APK-like catchup)."""
    global _last_status
    settings = store.get_settings_bundle()
    entry = float(settings.get("entry_z") or DEFAULT_Z_ENTRY)
    exit_z = float(settings.get("exit_z") or DEFAULT_Z_EXIT)
    auto = bool(settings.get("auto_execute"))
    now_ms = int(time.time() * 1000)
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
                "last_tick_ms": now_ms,
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
        now_ms=now_ms,
        settle_sec=MONITOR_BAR_SETTLE_SEC,
    )

    if mode == "bootstrap":
        # Якорь на последний settled бар — не на mid-bar tip.
        si = last_settled_bar_index(bars, now_ms, settle_sec=MONITOR_BAR_SETTLE_SEC)
        anchor = bars[si] if si is not None else bar
        anchor_ms = int(anchor.get("timestampMs") or 0)
        store.set_setting("last_processed_bar_ms", str(anchor_ms))
        _record_edge_snapshot(anchor_ms, float(anchor.get("zScore") or z), Signal.NONE)
        store.log_event(
            f"Монитор: якорь на {anchor.get('tradeDate')} (без реплея истории)",
            "info",
        )
        _maybe_monitor_heartbeat(bar, z)
        _last_status.update(
            {
                "last_tick_ms": now_ms,
                "last_message": msg + " · якорь",
                "last_z": z,
                "last_bar": bar.get("tradeDate"),
            }
        )
        return result

    if mode == "up_to_date":
        msg = _maybe_late_z_revise(
            bars, entry=entry, exit_z=exit_z, auto=auto, msg=msg, result=result
        )
        _maybe_monitor_heartbeat(bar, z)
        _last_status.update(
            {
                "last_tick_ms": now_ms,
                "last_message": msg + (" · уже обработан" if "revise" not in msg else ""),
                "last_z": z,
                "last_bar": bar.get("tradeDate"),
            }
        )
        return result

    if mode == "skip_gap":
        # Пропуски не догоняем сделками — только якорь на settled хвост + предупреждение.
        si = last_settled_bar_index(bars, now_ms, settle_sec=MONITOR_BAR_SETTLE_SEC)
        tip = bars[si] if si is not None else bar
        tip_ms = int(tip.get("timestampMs") or 0)
        n_miss = max(0, len(edges))
        store.set_setting("last_processed_bar_ms", str(tip_ms))
        _record_edge_snapshot(tip_ms, float(tip.get("zScore") or z), Signal.NONE)
        store.log_event(
            f"Монитор: пропуск {n_miss} бар(ов) без AUTO-догона → якорь {tip.get('tradeDate')}",
            "warn",
        )
        _maybe_monitor_heartbeat(bar, z)
        _last_status.update(
            {
                "last_tick_ms": now_ms,
                "last_message": msg + f" · skip×{n_miss}",
                "last_z": z,
                "last_bar": bar.get("tradeDate"),
            }
        )
        return result

    # mode == "live": consecutive рёбра после last_proc (догон как APK, до max_edges)
    n_edges = 0
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
            _record_edge_snapshot(cur_ms, cur_z, Signal.NONE)
            continue

        if not is_moex_equity_session_bar(cur_td):
            store.log_event(
                f"Монитор: вне сессии TQBR {cur_td} (сигнал пропущен)",
                "info",
            )
            store.set_setting("last_processed_bar_ms", str(cur_ms))
            _record_edge_snapshot(cur_ms, cur_z, Signal.NONE)
            continue

        pos = current_position()
        signal = determine_z_signal(float(prev["zScore"]), cur_z, pos, entry, exit_z)
        store.set_setting("last_processed_bar_ms", str(cur_ms))
        _record_edge_snapshot(cur_ms, cur_z, signal)
        n_edges += 1
        result["catchup_edges"] = n_edges

        if signal == Signal.NONE:
            continue

        result["signal"] = signal.value
        result.setdefault("signals", []).append(f"{signal.value}@{cur_td}")
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
                    prev_bar=prev,
                )
            except Exception as exc:
                store.log_event(f"AUTO fail {signal.value}: {exc}", "error")
                msg += f" · AUTO err: {exc}"
        else:
            msg += f" · сигнал {signal.value} (auto выкл)"

    # Если после live всё ещё tip на last_proc — дать late revise на том же тике.
    msg = _maybe_late_z_revise(
        bars, entry=entry, exit_z=exit_z, auto=auto, msg=msg, result=result
    )

    try:
        from live.parity import maybe_run_hourly_parity_digest, process_due_parity_checks

        process_due_parity_checks()
        # Durable hourly digest (parity-hourly.log) — survives Cursor chat death.
        maybe_run_hourly_parity_digest(run_checks=False)
    except Exception as parity_exc:
        log.warning("parity process failed: %s", parity_exc)

    _maybe_monitor_heartbeat(bar, z)
    _last_status.update(
        {
            "last_tick_ms": now_ms,
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
                # Bar-close races: ISS sync + UI polls can briefly lock SQLite.
                # Retry before logging — a single lock must not skip the AUTO edge.
                attempts = 4
                for i in range(attempts):
                    try:
                        monitor_tick()
                        break
                    except Exception as exc:
                        locked = "locked" in str(exc).lower()
                        if locked and i < attempts - 1:
                            _monitor_stop.wait(0.15 * (i + 1))
                            continue
                        raise
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
            store.set_setting("monitor_running", "1")
            return {"ok": True, "running": True, "message": "уже запущен"}
        _monitor_stop.clear()
        store.set_setting("monitor_running", "1")
        _last_status["running"] = True
        _monitor_started_ms = int(time.time() * 1000)
        _monitor_thread = threading.Thread(target=_monitor_loop, daemon=True, name="live-monitor")
        _monitor_thread.start()
    return {"ok": True, "running": True}


def stop_monitor(*, clear_wanted: bool = True) -> dict[str, Any]:
    """
    Останавливает поток монитора.
    clear_wanted=True — пользователь/API «Стоп»: не поднимать снова до явного Старт.
    clear_wanted=False — рестарт процесса/watchdog: флаг «нужен монитор» сохраняем.
    """
    with _monitor_lock:
        _monitor_stop.set()
        if clear_wanted:
            store.set_setting("monitor_running", "0")
        _last_status["running"] = False
        thread = _monitor_thread
    if thread and thread.is_alive() and thread is not threading.current_thread():
        thread.join(timeout=MONITOR_SYNC_TIMEOUT_SEC + 5.0)
    _set_monitor_keep_awake(False)
    return {"ok": True, "running": False}


def restart_monitor() -> dict[str, Any]:
    """Stop+start — для watchdog, когда поток жив, но last_tick протух."""
    stop_monitor(clear_wanted=False)
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
