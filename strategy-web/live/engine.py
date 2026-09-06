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
    MONITOR_CATCHUP_MAX_EDGES,
    MONITOR_HEARTBEAT_SEC,
    MONITOR_INTERVAL_SEC,
    MONITOR_STALE_SEC,
    MONITOR_SYNC_TIMEOUT_SEC,
    MONITOR_TIP1M_HOURS,
    MONITOR_Z_REVISE_MIN_DELTA,
    SIGNAL_MODE_TIP1M,
    SPREAD_LOT_MIN_LOTS,
    SPREAD_LOT_PROD_DEFAULT_LEVERAGE,
)
from live.lot_sizing import compute_spread_quantity_lots
from live.signals import (
    Position,
    Signal,
    determine_z_signal,
    find_bar_index,
    is_moex_equity_session_bar,
    should_revise_none_to_signal,
)
from live.tip_touch_signals import (
    get_tip_feed_meta,
    is_consecutive_1m,
    load_tip_bars_for_live,
    plan_tip1m_catchup,
    should_exit_take_profit,
    tip1m_mtm_pct_of_deposit,
)
from live.spread_regime import (
    desk_regime_payload,
    gate_signal,
    lock_fields_for_entry,
    parse_regime_z_mode,
    resolve_from_settings,
)
from live.spread_levels import (
    desk_spread_levels_payload,
    determine_spread_level_signal,
    levels_from_settings,
    lock_fields_for_spread_entry,
    parse_spread_level_mode,
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

# GetPortfolio on weekend/OTC can flap empty → ghost-close local open.
_broker_flat_streak = 0
_BROKER_FLAT_CLOSE_N = 3
_last_reconcile_warn_ms = 0


def _reconcile_warn(message: str) -> None:
    """Do not flood live_events (sqlite lock + desk «завис»)."""
    global _last_reconcile_warn_ms
    now = int(time.time() * 1000)
    if _last_reconcile_warn_ms and (now - _last_reconcile_warn_ms) < 60_000:
        return
    _last_reconcile_warn_ms = now
    store.log_event(message, "warn")


def _is_auto_source(source: str | None) -> bool:
    return str(source or "").upper().startswith("AUTO")


def _auto_orders_allowed(tip: dict[str, Any] | None = None) -> bool:
    """AUTO/AUTO_TP: будни 07:00–23:50; сб/вс 10:00–18:59 МСК по tip1m."""
    from live.dealer_quotes import is_msk_auto_session

    if not is_msk_auto_session():
        return False
    if isinstance(tip, dict):
        td = str(tip.get("tradeDate") or "")
        if td and not is_moex_equity_session_bar(td):
            return False
    return True


def _require_session_orders(
    *,
    source: str | None = None,
    tip: dict[str, Any] | None = None,
    action: str = "вход",
) -> None:
    """Брокерские ордера (AUTO и ручные) — то же окно, что AUTO tip1m."""
    if _auto_orders_allowed(tip if isinstance(tip, dict) else None):
        return
    from live.dealer_quotes import msk_session_block_reason

    reason = msk_session_block_reason() or "сессии нет"
    kind = "AUTO" if _is_auto_source(source) else "ручной"
    if action == "закрытие":
        raise RuntimeError(f"{kind} закрытие вне сессии TQBR запрещено ({reason})")
    raise RuntimeError(f"{kind} вход вне сессии TQBR запрещён ({reason})")


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
    # Вне TQBR: цены для лотов с дилера (не в Z / tip1m / overlay).
    try:
        from live.dealer_quotes import (
            apply_dealer_to_sizing_snap,
            fetch_dealer_quotes,
            want_dealer_quotes,
        )

        if want_dealer_quotes():
            dealer = fetch_dealer_quotes(client, include_candles=False)
            snap = apply_dealer_to_sizing_snap(snap, dealer)
    except Exception:
        pass
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
    _require_session_orders(
        source=source,
        tip=signal_bar if isinstance(signal_bar, dict) else None,
        action="вход",
    )
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

    # Regime / spread-level lock on open trade.
    lock_spread = sig.get("spreadPercent")
    if lock_spread is None:
        lock_spread = sig.get("spread")
    if lock_spread is None:
        lock_spread = entry_spread
    regime_fields: dict[str, Any] = {}
    settings_bundle = store.get_settings_bundle()
    if parse_spread_level_mode(settings_bundle):
        regime_fields = lock_fields_for_spread_entry(lock_spread)
    elif parse_regime_z_mode(settings_bundle):
        regime_fields = lock_fields_for_entry(lock_spread)

    from live.closed_metrics import account_after_from_legs
    from live.trade_comments import entry_comment_for_open

    account_after_entry = account_after_from_legs(legs)
    if account_after_entry is None:
        try:
            pf = client.get_portfolio(account)
            account_after_entry = client.portfolio_total_rub(pf)
            if account_after_entry is None:
                account_after_entry = client.portfolio_cash_rub(pf)
        except Exception:
            account_after_entry = None

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
            "account_after_rub": account_after_entry,
            "entry_comment": entry_comment_for_open(
                source=source,
                direction=position.value,
                quantity_lots=sizing["quantity_lots"],
                settings=settings_bundle,
            ),
            **regime_fields,
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
    _require_session_orders(
        source=source,
        tip=signal_bar if isinstance(signal_bar, dict) else None,
        action="закрытие",
    )
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
    from live.closed_metrics import coerce_exit_after_entry, exit_time_from_broker_legs

    # History/parity: exit_time = signal bar (Test alignment). Broker fill kept separate.
    signal_exit = str(sig.get("tradeDate") or snap.get("trade_date") or "")
    leg_exit = exit_time_from_broker_legs(legs)
    exit_time = coerce_exit_after_entry(open_t.get("entry_time"), signal_exit)
    exit_z = sig.get("zScore") if sig.get("zScore") is not None else snap.get("z")
    try:
        exit_z = float(exit_z) if exit_z is not None else None
    except (TypeError, ValueError):
        exit_z = snap.get("z")
    # Спред для PnL — с live tip (цены ближе к фактическому fill), время/Z — с бара сигнала
    exit_spread = snap.get("spread")
    metrics = _closed_metrics_for_open(open_t, exit_time=exit_time, exit_spread=exit_spread)
    if leg_exit:
        metrics["exit_fill_time"] = leg_exit
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
    # Чист. = Δ портфеля этой сделки: после входа → после выхода (не previous trade).
    try:
        from live.closed_metrics import account_after_from_legs

        account_before = None
        raw_before = open_t.get("account_after_rub")
        if raw_before is not None:
            try:
                account_before = float(raw_before)
            except (TypeError, ValueError):
                account_before = None
        if account_before is None:
            account_before = account_after_from_legs(
                open_t.get("legs") or open_t.get("legs_json")
            )
    except Exception:
        account_before = None
    if account_before is not None:
        metrics["account_before_rub"] = float(account_before)
    if account_before is not None and account_after is not None:
        delta = float(account_after) - float(account_before)
        if metrics.get("spread_pnl_rub") is None and metrics.get("pnl_rub") is not None:
            metrics["spread_pnl_rub"] = metrics.get("pnl_rub")
        metrics["account_delta_rub"] = delta
        metrics["pnl_rub"] = delta
    from live.trade_comments import close_comment_for_source, entry_comment_for_open

    if not open_t.get("entry_comment"):
        metrics["entry_comment"] = entry_comment_for_open(
            source=str(open_t.get("source") or source),
            direction=open_t.get("direction"),
            quantity_lots=open_t.get("quantity_lots"),
            settings=store.get_settings_bundle(),
        )
    metrics["close_comment"] = close_comment_for_source(
        source,
        settings=store.get_settings_bundle(),
        signal_bar=sig,
    )
    closed = store.close_open_trade(
        exit_time=exit_time,
        exit_z=exit_z,
        exit_spread=exit_spread,
        pnl_rub=metrics.get("pnl_rub"),
        legs=legs,
        metrics=metrics,
    )
    store.log_event(
        f"{source} выход {open_t['direction']} · Z={exit_z} · бар {exit_time[:16] if exit_time else '—'}"
        + (f" · fill {leg_exit}" if leg_exit and leg_exit != (exit_time[:16] if exit_time else "") else ""),
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
    - на брокере есть спред, локально пусто → подхват open (source=MANUAL);
    - локально open, на брокере пусто в сессии TQBR → закрыть «висячую» после N пустых;
      вне сессии/выходные не закрываем с одного пустого GetPortfolio (дилер флапает);
    - оба есть, разные стороны → предупреждение (не перетираем автоматически).
    """
    global _broker_flat_streak
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

    if spread:
        _broker_flat_streak = 0

    if local and not spread:
        from live.dealer_quotes import want_dealer_quotes

        if want_dealer_quotes():
            _reconcile_warn(
                f"BROKER sync: на брокере спреда нет, локальный {local.get('direction')} "
                f"#{local.get('id')} оставлен (выходные/вне TQBR — не ghost-close)"
            )
            return {
                "ok": True,
                "adopted": False,
                "closed_local": False,
                "deferred_flat": True,
                "broker": None,
                "local": True,
            }
        _broker_flat_streak += 1
        if _broker_flat_streak < _BROKER_FLAT_CLOSE_N:
            return {
                "ok": True,
                "adopted": False,
                "closed_local": False,
                "flat_streak": _broker_flat_streak,
                "broker": None,
                "local": True,
            }
        _broker_flat_streak = 0
        if snap is None:
            snap = market_snapshot()
        from live.closed_metrics import coerce_exit_after_entry

        exit_time = coerce_exit_after_entry(
            local.get("entry_time"),
            str(snap.get("trade_date") or ""),
        )
        exit_spread = snap.get("spread")
        metrics = _closed_metrics_for_open(local, exit_time=exit_time, exit_spread=exit_spread)
        from live.trade_comments import close_comment_for_source, entry_comment_for_open

        metrics["close_comment"] = close_comment_for_source("RECONCILE", ghost=True)
        if not local.get("entry_comment"):
            metrics["entry_comment"] = entry_comment_for_open(
                source=str(local.get("source") or "MANUAL"),
                direction=local.get("direction"),
                quantity_lots=local.get("quantity_lots"),
            )
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
        # Do not call market_snapshot here — it can block desk/uvicorn for tens of seconds.
        if not isinstance(snap, dict):
            snap = {}
        from live.closed_metrics import broker_adopt_entry_time

        entry_time = broker_adopt_entry_time(snap if isinstance(snap, dict) else None)
        lots = int(spread.get("quantity_lots") or 1)
        direction = str(spread.get("direction") or "LONG").upper()
        signal = str(
            spread.get("entry_signal")
            or ("ENTER_SHORT" if direction == "SHORT" else "ENTER_LONG")
        )
        snap_d = snap if isinstance(snap, dict) else {}
        adopt_entry_comment = None
        # If we just ghost-closed the same lots (weekend GetPortfolio flap), keep that entry.
        try:
            for prev in store.get_closed_trades(8):
                legs = prev.get("legs") if isinstance(prev.get("legs"), list) else []
                note = ""
                if legs and isinstance(legs[0], dict):
                    note = str(legs[0].get("note") or "")
                if "broker flat" not in note:
                    continue
                if str(prev.get("direction") or "").upper() != direction:
                    continue
                if int(prev.get("quantity_lots") or 0) != lots:
                    continue
                if str(prev.get("source") or "").upper() not in ("MANUAL", "BROKER", "AUTO"):
                    continue
                if prev.get("entry_time"):
                    entry_time = str(prev["entry_time"])
                if prev.get("entry_z") is not None:
                    snap_d["z"] = prev.get("entry_z")
                if prev.get("entry_spread") is not None:
                    snap_d["spread"] = prev.get("entry_spread")
                if prev.get("entry_tatn") is not None:
                    snap_d["tatn"] = prev.get("entry_tatn")
                if prev.get("entry_tatnp") is not None:
                    snap_d["tatnp"] = prev.get("entry_tatnp")
                if prev.get("entry_comment"):
                    adopt_entry_comment = str(prev.get("entry_comment"))
                break
        except Exception:
            pass
        from live.trade_comments import entry_comment_for_open

        if not adopt_entry_comment:
            adopt_entry_comment = entry_comment_for_open(
                source="MANUAL",
                direction=direction,
                quantity_lots=lots,
                adopted=True,
            )
        trade_id = store.insert_open_trade(
            {
                "mode": mode,
                "account_id": account,
                "direction": direction,
                "entry_signal": signal,
                "quantity_lots": max(1, lots),
                "entry_time": entry_time,
                "entry_z": snap_d.get("z"),
                "entry_spread": snap_d.get("spread"),
                "entry_tatn": snap_d.get("tatn"),
                "entry_tatnp": snap_d.get("tatnp"),
                "execution_notional_rub": None,
                "source": "MANUAL",
                "legs": [{"note": "broker adopt"}],
                "entry_comment": adopt_entry_comment,
            }
        )
        store.log_event(
            f"подхват с брокера · MANUAL · {direction} {lots}+{lots} лот · #{trade_id}",
            "info",
        )
        return {
            "ok": True,
            "adopted": True,
            "trade_id": trade_id,
            "broker": spread,
            "local": True,
        }

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

    if not _auto_orders_allowed(bar if isinstance(bar, dict) else None):
        store.log_event(
            f"AUTO пропуск {signal.value} @ {str((bar or {}).get('tradeDate') or '')[:16]}: "
            "вне сессии TQBR",
            "warn",
        )
        return f" · skip {signal.value} (off TQBR)"

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


def _record_edge_snapshot(
    bar_ms: int,
    z: float,
    signal: Signal,
    *,
    bar: dict[str, Any] | None = None,
    revised: int = 0,
) -> None:
    store.set_setting("last_edge_bar_ms", str(bar_ms))
    store.set_setting("last_edge_z", f"{z:.6f}")
    store.set_setting("last_edge_signal", signal.value)
    # Actionable already fired — no late revise. NONE may be upgraded once.
    store.set_setting("last_edge_revised", "0" if signal == Signal.NONE and not revised else "1")
    try:
        store.upsert_decision_bar_from_series(
            bar,
            z_score=z,
            signal=signal.value,
            revised=int(revised),
        )
    except Exception as exc:
        log.warning("decision_bars upsert failed: %s", exc)


def _thresholds_for_edge(
    settings: dict[str, Any],
    bar: dict[str, Any] | None,
    *,
    position: Position | None = None,
    open_trade: dict[str, Any] | None = None,
):
    """Effective entry/exit for one tip edge (regime-aware)."""
    pos = position if position is not None else current_position()
    open_t = open_trade if open_trade is not None else store.get_open_trade()
    return resolve_from_settings(
        settings,
        spread=None,
        position=pos,
        open_trade=open_t,
        bar=bar,
    )


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
    th = _thresholds_for_edge(store.get_settings_bundle(), cur, position=pos)
    new_sig = gate_signal(
        determine_z_signal(float(prev["zScore"]), new_z, pos, th.entry, th.exit),
        th,
    )
    entry, exit_z = th.entry, th.exit

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
    try:
        store.upsert_decision_bar_from_series(
            cur, z_score=new_z, signal=new_sig.value, revised=1
        )
    except Exception as exc:
        log.warning("decision_bars revise upsert failed: %s", exc)
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


def _maybe_profit_deposit_alert(
    *,
    tip: dict[str, Any] | None,
    settings: dict[str, Any],
) -> None:
    """Once per open trade: signal when MTM ≥ 3% of entry deposit (вложение)."""
    open_t = store.get_open_trade()
    pos = current_position()
    if not open_t or pos not in (Position.LONG, Position.SHORT):
        return
    tid = open_t.get("id")
    if tid is None:
        return
    if str(store.get_setting("profit_alert_trade_id", "") or "") == str(tid):
        return
    cur_sp = None
    if tip:
        cur_sp = tip.get("spreadPercent")
        if cur_sp is None:
            cur_sp = tip.get("spread")
    if cur_sp is None:
        return
    dep, lev = _tp_deposit_leverage(settings, open_t)
    direction = "LONG" if pos == Position.LONG else "SHORT"
    try:
        pct = tip1m_mtm_pct_of_deposit(
            direction=direction,
            entry_spread=float(open_t.get("entry_spread")),
            cur_spread=float(cur_sp),
            deposit_rub=dep,
            leverage=lev,
        )
    except (TypeError, ValueError):
        return
    if pct < 3.0:
        return
    mtm = dep * (pct / 100.0)
    store.set_setting("profit_alert_trade_id", str(tid))
    store.log_event(
        f"Прибыль ≥3% от депозита · {pct:.1f}% · ≈{mtm:.0f} ₽ · вложение {dep:.0f} ₽",
        "signal",
    )


def _ensure_tip1m_signal_mode() -> None:
    """One-shot migrate last_proc from M15 settle era → tip1m bootstrap."""
    mode = store.get_setting("signal_mode", "") or ""
    if mode == SIGNAL_MODE_TIP1M:
        return
    store.set_setting("signal_mode", SIGNAL_MODE_TIP1M)
    store.set_setting("last_processed_bar_ms", "0")
    store.log_event(
        "Монитор: режим tip1m (касание 1м) — якорь сброшен, без реплея M15-истории",
        "info",
    )


def _tp_deposit_leverage(settings: dict[str, Any], open_t: dict[str, Any] | None) -> tuple[float, float]:
    try:
        dep = float(
            (open_t or {}).get("entry_deposit_rub")
            or settings.get("entry_deposit_rub")
            or 10_000
        )
    except (TypeError, ValueError):
        dep = 10_000.0
    try:
        lev = float(settings.get("leverage") or SPREAD_LOT_PROD_DEFAULT_LEVERAGE)
    except (TypeError, ValueError):
        lev = SPREAD_LOT_PROD_DEFAULT_LEVERAGE
    return max(1.0, dep), max(1.0, lev)


def _maybe_tp_exit_on_tip(
    *,
    tip: dict[str, Any],
    settings: dict[str, Any],
    auto: bool,
    entry: float,
    exit_z: float,
    msg: str,
    result: dict[str, Any],
) -> tuple[str, bool]:
    """If open and MTM% ≥ TP — EXIT like Testing tip1m. Returns (msg, fired)."""
    try:
        tp = float(settings.get("take_profit_pct") or 0)
    except (TypeError, ValueError):
        tp = 0.0
    if tp <= 0:
        return msg, False
    open_t = store.get_open_trade()
    pos = current_position()
    if not open_t or pos not in (Position.LONG, Position.SHORT):
        return msg, False
    cur_sp = tip.get("spreadPercent")
    if cur_sp is None:
        cur_sp = tip.get("spread")
    dep, lev = _tp_deposit_leverage(settings, open_t)
    if not should_exit_take_profit(
        position=pos,
        entry_spread=open_t.get("entry_spread"),
        cur_spread=cur_sp,
        take_profit_pct=tp,
        deposit_rub=dep,
        leverage=lev,
    ):
        return msg, False

    if auto and not _auto_orders_allowed(tip if isinstance(tip, dict) else None):
        cur_td = str(tip.get("tradeDate") or "")
        store.log_event(
            f"AUTO_TP пропуск {pos.value} @ {cur_td[:16] if cur_td else '—'} "
            f"(вне сессии TQBR — не закрываем)",
            "warn",
        )
        return msg, False

    signal = Signal.EXIT_LONG if pos == Position.LONG else Signal.EXIT_SHORT
    cur_ms = int(tip.get("timestampMs") or 0)
    cur_z = float(tip.get("zScore") or 0)
    cur_td = str(tip.get("tradeDate") or "")
    result["signal"] = signal.value
    result.setdefault("signals", []).append(f"{signal.value}@{cur_td}:tp")
    store.log_event(
        f"Сигнал {signal.value} @ {cur_td} Z={cur_z:.2f} (TP {tp:g}%)",
        "signal",
    )
    if auto:
        try:
            # close via AUTO path; tag source through signal_bar
            close_position(source="AUTO_TP", signal_bar=tip)
            try:
                from live.parity import schedule_parity_for_auto

                schedule_parity_for_auto(
                    bar_ts=cur_td,
                    bar_ms=cur_ms,
                    signal=signal.value,
                    z_score=cur_z,
                    entry_z=entry,
                    exit_z=exit_z,
                    trade_id=None,
                )
            except Exception as parity_exc:
                log.warning("parity schedule failed (TP): %s", parity_exc)
            msg += f" · AUTO {signal.value} TP{tp:g}%"
        except Exception as exc:
            store.log_event(f"AUTO fail TP {signal.value}: {exc}", "error")
            msg += f" · AUTO TP err: {exc}"
    else:
        msg += f" · сигнал {signal.value} TP (auto выкл)"
    return msg, True


def monitor_tick() -> dict[str, Any]:
    """One monitor cycle: tip1m edges (Testing «касание 1м») + optional TP exit."""
    global _last_status
    _ensure_tip1m_signal_mode()
    settings = store.get_settings_bundle()
    auto = bool(settings.get("auto_execute"))
    now_ms = int(time.time() * 1000)
    # Keep M15 SQLite fresh (μ/σ base); signals come from 1m tip-Z.
    snap = market_snapshot(wait_sync=True, sync_timeout_sec=MONITOR_SYNC_TIMEOUT_SEC)

    m15_bars = list(snap.get("bars") or [])
    tip_bars = load_tip_bars_for_live(
        m15_bars, now_ms=now_ms, hours=MONITOR_TIP1M_HOURS
    )
    adopt_market = dict(snap)
    if tip_bars:
        adopt_market["tip_trade_date"] = tip_bars[-1].get("tradeDate")
    try:
        reconcile_broker_open_trade(market=adopt_market)
    except Exception as sync_exc:
        log.warning("broker reconcile failed: %s", sync_exc)
    if tip_bars:
        bar = tip_bars[-1]
        z = float(bar.get("zScore") or 0)
        display_td = bar.get("tradeDate")
    else:
        bar = snap["bar"]
        z = float(bar.get("zScore") or 0)
        display_td = bar.get("tradeDate")

    open_t0 = store.get_open_trade()
    th0 = resolve_from_settings(
        settings,
        spread=None,
        position=current_position(),
        open_trade=open_t0,
        bar=bar if isinstance(bar, dict) else None,
    )
    entry, exit_z = th0.entry, th0.exit
    bar_spread = None
    if isinstance(bar, dict):
        bar_spread = bar.get("spreadPercent")
        if bar_spread is None:
            bar_spread = bar.get("spread")
    if bar_spread is None:
        bar_spread = snap.get("spread")
    use_spread_levels = parse_spread_level_mode(settings)
    lv0 = levels_from_settings(settings) if use_spread_levels else None
    if use_spread_levels and bar_spread is not None:
        try:
            msg = f"{display_td} · tip1m S {float(bar_spread):.2f}%"
        except (TypeError, ValueError):
            msg = f"{display_td} · tip1m Z {z:.2f}"
        msg += (
            f" · уровни Short {lv0.enter_wide:.1f}/{lv0.exit_wide:.1f}"
            f" · Long {lv0.enter_narrow:.1f}/{lv0.exit_narrow:.1f}"
        )
    else:
        msg = f"{display_td} · tip1m Z {z:.2f}"
        if parse_regime_z_mode(settings) and th0.regime != "off":
            msg += f" · {th0.label_ru} ±{entry:.1f}/±{exit_z:.1f}"
    result: dict[str, Any] = {
        "z": z,
        "bar": display_td,
        "signal": Signal.NONE.value,
        "catchup_edges": 0,
        "signals": [],
        "signal_mode": SIGNAL_MODE_TIP1M,
        "spread_level_mode": use_spread_levels,
        "regime": desk_regime_payload(
            settings,
            spread=bar_spread,
            position=current_position(),
            open_trade=open_t0,
        ),
        "spread_levels": desk_spread_levels_payload(
            settings,
            spread=bar_spread,
            position=current_position(),
        ),
    }

    if len(tip_bars) < 2:
        _last_status.update(
            {
                "last_tick_ms": now_ms,
                "last_message": msg + " · нет 1м tip",
                "last_z": z,
                "last_bar": display_td,
            }
        )
        return result

    last_proc = int(store.get_setting("last_processed_bar_ms", "0") or "0")
    mode, edges = plan_tip1m_catchup(
        tip_bars,
        last_proc,
        max_edges=MONITOR_CATCHUP_MAX_EDGES,
    )

    if mode == "bootstrap":
        anchor = tip_bars[-1]
        anchor_ms = int(anchor.get("timestampMs") or 0)
        store.set_setting("last_processed_bar_ms", str(anchor_ms))
        _record_edge_snapshot(
            anchor_ms, float(anchor.get("zScore") or z), Signal.NONE, bar=anchor
        )
        store.log_event(
            f"Монитор tip1m: якорь на {anchor.get('tradeDate')} (без реплея истории)",
            "info",
        )
        # TP still checked on latest tip while holding.
        msg, _ = _maybe_tp_exit_on_tip(
            tip=anchor,
            settings=settings,
            auto=auto,
            entry=entry,
            exit_z=exit_z,
            msg=msg,
            result=result,
        )
        _maybe_monitor_heartbeat(bar, z)
        _last_status.update(
            {
                "last_tick_ms": now_ms,
                "last_message": msg + " · якорь tip1m",
                "last_z": z,
                "last_bar": display_td,
            }
        )
        return result

    if mode == "up_to_date":
        tip = tip_bars[-1]
        msg, _ = _maybe_tp_exit_on_tip(
            tip=tip,
            settings=settings,
            auto=auto,
            entry=entry,
            exit_z=exit_z,
            msg=msg,
            result=result,
        )
        _maybe_monitor_heartbeat(bar, z)
        _last_status.update(
            {
                "last_tick_ms": now_ms,
                "last_message": msg + " · уже обработан",
                "last_z": z,
                "last_bar": display_td,
            }
        )
        return result

    if mode == "skip_gap":
        # No consecutive 1m pair after last_proc (empty/gapped tip series) —
        # advance anchor only. Prefer gap-recover via plan_tip1m_catchup when
        # tips reappear so the first cross in the hole is not skipped.
        tip = tip_bars[-1]
        tip_ms = int(tip.get("timestampMs") or 0)
        n_miss = max(0, len(edges))
        store.set_setting("last_processed_bar_ms", str(tip_ms))
        _record_edge_snapshot(tip_ms, float(tip.get("zScore") or z), Signal.NONE, bar=tip)
        store.log_event(
            f"Монитор tip1m: нет consecutive 1м после дыры → якорь {tip.get('tradeDate')}"
            f" (без AUTO; ждать восстановление tip)",
            "warn",
        )
        msg, _ = _maybe_tp_exit_on_tip(
            tip=tip,
            settings=settings,
            auto=auto,
            entry=entry,
            exit_z=exit_z,
            msg=msg,
            result=result,
        )
        _maybe_monitor_heartbeat(bar, z)
        _last_status.update(
            {
                "last_tick_ms": now_ms,
                "last_message": msg + f" · skip×{n_miss}",
                "last_z": z,
                "last_bar": display_td,
            }
        )
        return result

    # mode == "live": consecutive 1m tip edges after last_proc
    n_edges = 0
    for prev, cur in edges:
        prev_ms = int(prev.get("timestampMs") or 0)
        cur_ms = int(cur.get("timestampMs") or 0)
        cur_z = float(cur["zScore"])
        cur_td = str(cur.get("tradeDate") or "")

        if not is_consecutive_1m(prev_ms, cur_ms):
            store.log_event(
                f"Монитор tip1m: дыра {prev.get('tradeDate')} → {cur_td} (сигнал пропущен)",
                "warn",
            )
            store.set_setting("last_processed_bar_ms", str(cur_ms))
            _record_edge_snapshot(cur_ms, cur_z, Signal.NONE, bar=cur)
            continue

        if not is_moex_equity_session_bar(cur_td):
            store.log_event(
                f"Монитор tip1m: вне сессии TQBR {cur_td} (сигнал пропущен)",
                "info",
            )
            store.set_setting("last_processed_bar_ms", str(cur_ms))
            _record_edge_snapshot(cur_ms, cur_z, Signal.NONE, bar=cur)
            continue

        # TP before Z exit/entry on this tip (Testing order).
        pos = current_position()
        open_t = store.get_open_trade()
        th = _thresholds_for_edge(settings, cur, position=pos, open_trade=open_t)
        entry, exit_z = th.entry, th.exit
        msg, tp_fired = _maybe_tp_exit_on_tip(
            tip=cur,
            settings=settings,
            auto=auto,
            entry=entry,
            exit_z=exit_z,
            msg=msg,
            result=result,
        )
        if tp_fired:
            store.set_setting("last_processed_bar_ms", str(cur_ms))
            tp_sig = (
                Signal.EXIT_LONG if pos == Position.LONG else Signal.EXIT_SHORT
            )
            _record_edge_snapshot(cur_ms, cur_z, tp_sig, bar=cur)
            n_edges += 1
            result["catchup_edges"] = n_edges
            continue

        pos = current_position()
        open_t = store.get_open_trade()
        th = _thresholds_for_edge(settings, cur, position=pos, open_trade=open_t)
        entry, exit_z = th.entry, th.exit
        if use_spread_levels:
            prev_sp = prev.get("spreadPercent")
            if prev_sp is None:
                prev_sp = prev.get("spread")
            cur_sp = cur.get("spreadPercent")
            if cur_sp is None:
                cur_sp = cur.get("spread")
            try:
                ps = float(prev_sp) if prev_sp is not None else None
                cs = float(cur_sp) if cur_sp is not None else float("nan")
            except (TypeError, ValueError):
                ps, cs = None, float("nan")
            lv = lv0 or levels_from_settings(settings)
            signal = determine_spread_level_signal(ps, cs, pos, lv)
        else:
            signal = gate_signal(
                determine_z_signal(float(prev["zScore"]), cur_z, pos, entry, exit_z),
                th,
            )
        store.set_setting("last_processed_bar_ms", str(cur_ms))
        _record_edge_snapshot(cur_ms, cur_z, signal, bar=cur)
        n_edges += 1
        result["catchup_edges"] = n_edges

        if signal == Signal.NONE:
            continue

        result["signal"] = signal.value
        result.setdefault("signals", []).append(f"{signal.value}@{cur_td}")
        if use_spread_levels:
            cur_sp_log = cur.get("spreadPercent")
            if cur_sp_log is None:
                cur_sp_log = cur.get("spread")
            try:
                sp_txt = f"S={float(cur_sp_log):.2f}%"
            except (TypeError, ValueError):
                sp_txt = "S=—"
            store.log_event(
                f"Сигнал {signal.value} @ {cur_td} {sp_txt} (tip1m спред-уровни)",
                "signal",
            )
        else:
            reg_note = f" · {th.label_ru}" if parse_regime_z_mode(settings) else ""
            store.log_event(
                f"Сигнал {signal.value} @ {cur_td} Z={cur_z:.2f} "
                f"(tip1m ±{entry:.2f}/±{exit_z:.2f}{reg_note})",
                "signal",
            )

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

    try:
        from live.parity import maybe_run_hourly_parity_digest, process_due_parity_checks

        process_due_parity_checks()
        maybe_run_hourly_parity_digest(run_checks=False)
    except Exception as parity_exc:
        log.warning("parity process failed: %s", parity_exc)

    try:
        tip_for_alert = tip_bars[-1] if tip_bars else None
        _maybe_profit_deposit_alert(tip=tip_for_alert, settings=settings)
    except Exception as alert_exc:
        log.warning("profit deposit alert failed: %s", alert_exc)

    _maybe_monitor_heartbeat(bar, z)
    meta = get_tip_feed_meta()
    _last_status.update(
        {
            "last_tick_ms": now_ms,
            "last_message": msg,
            "last_z": z,
            "last_bar": display_td,
            **meta,
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
    meta = get_tip_feed_meta()
    return {
        **_last_status,
        **meta,
        "running": alive,
        "settings": store.get_settings_bundle(),
        "open": store.get_open_trade(),
    }


def health_live() -> dict[str, Any]:
    """Liveness + business probe для внешнего watchdog.

    Avoid ``get_settings_bundle()`` (many SQLite round-trips): under tip1m/parity
    load that starves the DB lock and makes watchdog think HTTP is dead.
    """
    now_ms = int(time.time() * 1000)
    wanted = (store.get_setting("monitor_running", "1") or "1") == "1"
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
        **{k: get_tip_feed_meta().get(k) for k in ("tip_lag_sec", "iss_lag_sec", "ti_lag_sec", "tip_feed")},
    }
