"""MTLR/MTLRP Phase‑3: manual desk + soft broker reconcile + TP (shared %).

TATN tip1m AUTO stays in ``live.engine``. Mechel uses m15 levels and
``live_mtlr_*`` tables so the two positions never share one open row.
"""

from __future__ import annotations

import logging
from typing import Any

from live import store
from live.constants import (
    BASKET_MAX_OPEN,
    DEFAULT_MTLR_DEPOSIT_RUB,
    MTLR_CATCHUP_MAX_EDGES,
    MTLR_FALLBACK_ID,
    MTLR_ORD,
    MTLR_PAIR_ID,
    MTLR_PREF,
    MTLRP_FALLBACK_ID,
    SPREAD_LOT_MIN_LOTS,
    SPREAD_LOT_PROD_DEFAULT_LEVERAGE,
)
from live.lot_sizing import compute_spread_quantity_lots
from live.mtlr_shadow import (
    _bar_ms,
    _drop_unsettled_tail,
    determine_mtlr_signal,
    get_mtlr_shadow_status,
    load_mtlr_m15_frame,
    mtlr_auto_execute,
    mtlr_enabled,
    mtlr_levels_from_settings,
)
from live.signals import Position, Signal
from live.tip_touch_signals import should_exit_take_profit
from live.tinvest import TInvestClient

log = logging.getLogger("live.mtlr_engine")


def mtlr_deposit_rub(settings: dict[str, Any] | None = None) -> float:
    s = settings if settings is not None else store.get_settings_bundle()
    try:
        dep = float(s.get("mtlr_deposit_rub") or DEFAULT_MTLR_DEPOSIT_RUB)
    except (TypeError, ValueError):
        dep = float(DEFAULT_MTLR_DEPOSIT_RUB)
    return max(1000.0, min(10_000_000.0, dep))


def current_mtlr_position() -> Position:
    open_t = store.get_mtlr_open_trade()
    if not open_t:
        return Position.FLAT
    d = (open_t.get("direction") or "").upper()
    if d == "LONG":
        return Position.LONG
    if d == "SHORT":
        return Position.SHORT
    return Position.FLAT


def basket_open_count() -> int:
    """How many of the live basket pairs are open (TATN + MTLR today)."""
    n = 0
    if store.get_open_trade():
        n += 1
    if store.get_mtlr_open_trade():
        n += 1
    return n


def can_open_mtlr(*, settings: dict[str, Any] | None = None) -> tuple[bool, str]:
    """Basket / isolation gate for a new Mechel entry."""
    _ = settings
    if store.get_mtlr_open_trade():
        return False, "уже есть открытый Мечел"
    n = basket_open_count()
    if n >= BASKET_MAX_OPEN:
        return False, f"корзина: уже {n} из {BASKET_MAX_OPEN} позиций"
    return True, ""


def _prices_from_frame_or_broker(
    client: TInvestClient,
    account_id: str,
) -> tuple[float, float, dict[str, Any]]:
    """Prefer last settled m15 closes; fall back to broker last prices."""
    meta: dict[str, Any] = {"source": "m15"}
    price_ord = price_pref = 0.0
    try:
        df, _ = load_mtlr_m15_frame(force_refresh=False)
        settled = _drop_unsettled_tail(df)
        if settled is not None and not settled.empty:
            last = settled.iloc[-1]
            if "ord_close" in settled.columns:
                price_ord = float(last["ord_close"] or 0)
            if "pref_close" in settled.columns:
                price_pref = float(last["pref_close"] or 0)
            meta["bar"] = str(last.get("timestamp") or "")
    except Exception as exc:
        meta["m15_error"] = str(exc)

    if price_ord <= 0 or price_pref <= 0:
        meta["source"] = "broker"
        try:
            ord_id = client.resolve_instrument_id(MTLR_ORD)
        except Exception:
            ord_id = MTLR_FALLBACK_ID
        try:
            pref_id = client.resolve_instrument_id(MTLR_PREF)
        except Exception:
            pref_id = MTLRP_FALLBACK_ID
        try:
            prices = client.get_last_prices([ord_id, pref_id], last_price_type="LAST_PRICE")
        except Exception:
            prices = client.get_last_prices([ord_id, pref_id])
        if isinstance(prices, dict):
            price_ord = float(prices.get(ord_id) or price_ord or 0)
            price_pref = float(prices.get(pref_id) or price_pref or 0)

    if price_ord <= 0 or price_pref <= 0:
        raise RuntimeError("Нет цен MTLR/MTLRP для расчёта лотов")
    return price_ord, price_pref, meta


def resolve_mtlr_lots(client: TInvestClient, account_id: str) -> dict[str, Any]:
    settings = store.get_settings_bundle()
    deposit = mtlr_deposit_rub(settings)
    price_ord, price_pref, px_meta = _prices_from_frame_or_broker(client, account_id)
    pf = client.get_portfolio(account_id)
    cash = client.portfolio_cash_rub(pf)
    if cash is None:
        raise RuntimeError("Не удалось прочитать деньги на счёте (totalAmountCurrencies)")
    cash_for_entry = min(float(cash), deposit)
    margin = client.get_margin_attributes(account_id) if client.mode == "prod" else None
    leverage = None
    liquid = margin["liquid_portfolio_rub"] if margin else None
    if client.mode == "prod":
        leverage = float(
            settings.get("leverage") or SPREAD_LOT_PROD_DEFAULT_LEVERAGE
        )
        if liquid is not None and liquid > 0:
            liquid = min(float(liquid), deposit)
    sizing = compute_spread_quantity_lots(
        cash_rub=cash_for_entry,
        price_tatn=price_ord,
        price_tatnp=price_pref,
        liquid_portfolio_rub=liquid,
        corrected_margin_rub=margin["corrected_margin_rub"] if margin else None,
        leverage_for_notional=leverage,
    )
    if sizing.quantity_lots < SPREAD_LOT_MIN_LOTS:
        raise RuntimeError(
            f"Мечел: мало средств — cash={cash:.0f} ₽, депозит {deposit:.0f} ₽ "
            f"(в расчёте {cash_for_entry:.0f} ₽), нужно ≈{sizing.go_per_lot_rub:.0f} ₽ на 1 лот"
        )
    return {
        "quantity_lots": sizing.quantity_lots,
        "cash_rub": sizing.cash_rub,
        "available_rub": sizing.available_rub,
        "go_per_lot_rub": sizing.go_per_lot_rub,
        "execution_notional_rub": sizing.execution_notional_rub,
        "price_ord": price_ord,
        "price_pref": price_pref,
        "deposit_rub": deposit,
        "prices_meta": px_meta,
        "spread": (price_ord - price_pref) / price_pref * 100.0 if price_pref else None,
    }


def _require_mtlr_session(action: str = "вход") -> None:
    from live.dealer_quotes import is_msk_auto_session, msk_session_block_reason

    if is_msk_auto_session():
        return
    reason = msk_session_block_reason() or "сессии нет"
    raise RuntimeError(f"Мечел {action} вне сессии TQBR запрещён ({reason})")


def open_mtlr_position(
    position: Position,
    *,
    source: str = "AUTO",
    signal_bar: dict[str, Any] | None = None,
) -> dict[str, Any]:
    if position not in (Position.LONG, Position.SHORT):
        raise RuntimeError("position must be LONG or SHORT")
    _require_mtlr_session("вход")
    ok, reason = can_open_mtlr()
    if not ok:
        raise RuntimeError(reason)
    mode, token, account = store.get_credentials()
    if not token or not account:
        raise RuntimeError("Нужны токен и accountId")
    client = TInvestClient(mode, token)
    sizing = resolve_mtlr_lots(client, account)
    signal = Signal.ENTER_LONG if position == Position.LONG else Signal.ENTER_SHORT
    legs = client.execute_spread_entry(
        account,
        signal.value,
        sizing["quantity_lots"],
        ord_ticker=MTLR_ORD,
        pref_ticker=MTLR_PREF,
        ord_fallback=MTLR_FALLBACK_ID,
        pref_fallback=MTLRP_FALLBACK_ID,
    )
    from live.closed_metrics import account_after_from_legs
    from live.open_mark import adverse_entry_slip_pts, fill_prices_from_legs

    sig = signal_bar if isinstance(signal_bar, dict) else {}
    entry_time = str(sig.get("tradeDate") or sig.get("bar") or "")
    entry_ord = sizing["price_ord"]
    entry_pref = sizing["price_pref"]
    entry_spread_iss = sizing.get("spread")
    entry_spread = entry_spread_iss
    entry_slip_pts = None
    fill_ord, fill_pref = fill_prices_from_legs(
        {"legs": legs},
        ord_ticker=MTLR_ORD,
        pref_ticker=MTLR_PREF,
    )
    if fill_ord and fill_pref and fill_pref > 0:
        entry_ord = fill_ord
        entry_pref = fill_pref
        entry_spread = (fill_ord - fill_pref) / fill_pref * 100.0
        entry_slip_pts = adverse_entry_slip_pts(
            position.value, entry_spread_iss, entry_spread
        )

    account_after = account_after_from_legs(legs)
    if account_after is None:
        try:
            pf = client.get_portfolio(account)
            account_after = client.portfolio_total_rub(pf) or client.portfolio_cash_rub(pf)
        except Exception:
            account_after = None

    entry_z = sig.get("zScore")
    try:
        entry_z = float(entry_z) if entry_z is not None else None
    except (TypeError, ValueError):
        entry_z = None

    trade_id = store.insert_mtlr_open_trade(
        {
            "pair_id": MTLR_PAIR_ID,
            "mode": mode,
            "account_id": account,
            "direction": position.value,
            "entry_signal": signal.value,
            "quantity_lots": sizing["quantity_lots"],
            "entry_time": entry_time,
            "entry_z": entry_z,
            "entry_spread": entry_spread,
            "entry_ord": entry_ord,
            "entry_pref": entry_pref,
            "execution_notional_rub": sizing["execution_notional_rub"],
            "source": source,
            "legs": legs,
            "entry_spread_iss": entry_spread_iss,
            "entry_slip_pts": entry_slip_pts,
            "account_after_rub": account_after,
        }
    )
    store.log_event(
        f"{source} Мечел вход {position.value} · {sizing['quantity_lots']}+{sizing['quantity_lots']} лот · "
        f"S={entry_spread} · бар {entry_time[:16] if entry_time else '—'}",
        "info",
    )
    return {"ok": True, "trade_id": trade_id, "sizing": sizing, "legs": legs, "pair_id": MTLR_PAIR_ID}


def close_mtlr_position(
    *,
    source: str = "AUTO",
    signal_bar: dict[str, Any] | None = None,
) -> dict[str, Any]:
    _require_mtlr_session("закрытие")
    mode, token, account = store.get_credentials()
    if not token or not account:
        raise RuntimeError("Нужны токен и accountId")
    open_t = store.get_mtlr_open_trade()
    if not open_t:
        raise RuntimeError("Нет открытой позиции Мечел")
    client = TInvestClient(mode, token)
    legs = client.execute_spread_exit(
        account,
        open_t["entry_signal"] or open_t["direction"],
        int(open_t["quantity_lots"]),
        ord_ticker=MTLR_ORD,
        pref_ticker=MTLR_PREF,
        ord_fallback=MTLR_FALLBACK_ID,
        pref_fallback=MTLRP_FALLBACK_ID,
    )
    from live.closed_metrics import (
        account_after_from_legs,
        exit_time_from_broker_legs,
        now_msk_bar_ts,
        resolve_chist_rub,
        resolve_close_exit_time,
    )

    sig = signal_bar if isinstance(signal_bar, dict) else {}
    signal_exit = str(sig.get("tradeDate") or sig.get("bar") or "")
    leg_exit = exit_time_from_broker_legs(legs)
    exit_time = resolve_close_exit_time(
        entry_time=open_t.get("entry_time"),
        source=source,
        signal_exit=signal_exit,
        broker_fill=leg_exit,
    )
    exit_spread = sig.get("spreadPercent")
    if exit_spread is None:
        exit_spread = sig.get("spread")
    if exit_spread is None:
        try:
            po, pp, _ = _prices_from_frame_or_broker(client, account)
            exit_spread = (po - pp) / pp * 100.0 if pp else None
        except Exception:
            exit_spread = open_t.get("entry_spread")

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
            account_after = client.portfolio_total_rub(pf) or client.portfolio_cash_rub(pf)
        except Exception:
            pass

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

    metrics: dict[str, Any] = {}
    if leg_exit:
        metrics["exit_fill_time"] = leg_exit
    elif str(source or "").upper() in ("MANUAL", "BROKER", "RECONCILE", "SYNC"):
        metrics["exit_fill_time"] = exit_time or now_msk_bar_ts()
    if account_before is not None:
        metrics["account_before_rub"] = float(account_before)
    if account_after is not None:
        metrics["account_after_rub"] = float(account_after)
    if account_before is not None and account_after is not None:
        delta = float(account_after) - float(account_before)
        metrics["account_delta_rub"] = delta
        try:
            dep = float(mtlr_deposit_rub())
        except Exception:
            dep = 12_000.0
        chist, used_model = resolve_chist_rub(delta, None, deposit_rub=dep)
        # Мечел: без модели спреда — оставляем Δ, но флаг пополнения не ставим без model
        metrics["pnl_rub"] = chist if chist is not None else delta
        if used_model:
            metrics["chist_from_model"] = True

    closed = store.close_mtlr_open_trade(
        exit_time=exit_time,
        exit_z=None,
        exit_spread=float(exit_spread) if exit_spread is not None else None,
        pnl_rub=metrics.get("pnl_rub"),
        legs=legs,
        metrics=metrics,
    )
    store.log_event(
        f"{source} Мечел выход {open_t['direction']} · выход {exit_time[:16] if exit_time else '—'}",
        "info",
    )
    return {"ok": True, "closed": closed, "legs": legs, "pair_id": MTLR_PAIR_ID}


def reconcile_broker_mtlr_open_trade(
    *,
    portfolio: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """
    Мягкая сверка локального open Мечела с брокером (как у Татнефть):
    - локально open, на брокере нет спреда MTLR/MTLRP → закрыть локальную «висячую»;
    - на брокере есть, локально пусто → подхват open (source=MANUAL);
    - оба есть, разные стороны → предупреждение.
    """
    mode, token, account = store.get_credentials()
    if not token or not account:
        return {"ok": False, "reason": "no_credentials"}
    client = TInvestClient(mode, token)
    try:
        pf = portfolio if portfolio is not None else client.get_portfolio(account)
    except Exception as exc:
        return {"ok": False, "error": str(exc)}

    spread = client.detect_spread_position(
        pf,
        ord_ticker=MTLR_ORD,
        pref_ticker=MTLR_PREF,
        ord_fallback=MTLR_FALLBACK_ID,
        pref_fallback=MTLRP_FALLBACK_ID,
    )
    local = store.get_mtlr_open_trade()

    if local and not spread:
        exit_spread = local.get("entry_spread")
        exit_time = str(local.get("entry_time") or "")
        try:
            po, pp, px_meta = _prices_from_frame_or_broker(client, account)
            if pp and pp > 0:
                exit_spread = (po - pp) / pp * 100.0
            bar = px_meta.get("bar")
            if bar:
                from live.closed_metrics import coerce_exit_after_entry

                exit_time = coerce_exit_after_entry(local.get("entry_time"), str(bar))
        except Exception:
            pass
        closed = store.close_mtlr_open_trade(
            exit_time=exit_time or str(local.get("entry_time") or ""),
            exit_z=None,
            exit_spread=float(exit_spread) if exit_spread is not None else None,
            pnl_rub=None,
            legs=[{"note": "broker flat — local mtlr open cleared"}],
            metrics={},
        )
        store.log_event(
            f"BROKER sync Мечел: закрыт локальный {local.get('direction')} "
            f"(на брокере спреда MTLR/MTLRP нет)",
            "info",
        )
        return {
            "ok": True,
            "adopted": False,
            "closed_local": True,
            "closed": closed,
            "broker": None,
            "pair_id": MTLR_PAIR_ID,
        }

    if spread and not local:
        from datetime import datetime
        from zoneinfo import ZoneInfo

        from live.closed_metrics import broker_adopt_entry_time

        entry_ord = None
        entry_pref = None
        entry_spread = None
        bar_hint = None
        try:
            po, pp, px_meta = _prices_from_frame_or_broker(client, account)
            entry_ord, entry_pref = po, pp
            if pp and pp > 0:
                entry_spread = (po - pp) / pp * 100.0
            bar_hint = px_meta.get("bar")
        except Exception:
            pass
        entry_time = broker_adopt_entry_time(
            {"trade_date": bar_hint} if bar_hint else None
        )
        if not entry_time:
            entry_time = datetime.now(ZoneInfo("Europe/Moscow")).strftime("%Y-%m-%d %H:%M")
        lots = int(spread.get("quantity_lots") or 1)
        direction = str(spread.get("direction") or "LONG").upper()
        signal = str(spread.get("entry_signal") or (
            "ENTER_SHORT" if direction == "SHORT" else "ENTER_LONG"
        ))
        trade_id = store.insert_mtlr_open_trade(
            {
                "pair_id": MTLR_PAIR_ID,
                "mode": mode,
                "account_id": account,
                "direction": direction,
                "entry_signal": signal,
                "quantity_lots": max(1, lots),
                "entry_time": entry_time,
                "entry_z": None,
                "entry_spread": entry_spread,
                "entry_ord": entry_ord,
                "entry_pref": entry_pref,
                "execution_notional_rub": None,
                "source": "MANUAL",
                "legs": [{"note": "broker adopt mtlr"}],
            }
        )
        store.log_event(
            f"подхват с брокера Мечел · MANUAL · {direction} {lots}+{lots} лот · #{trade_id}",
            "info",
        )
        return {
            "ok": True,
            "adopted": True,
            "trade_id": trade_id,
            "broker": spread,
            "local": True,
            "pair_id": MTLR_PAIR_ID,
        }

    if local and spread:
        loc_dir = (local.get("direction") or "").upper()
        br_dir = (spread.get("direction") or "").upper()
        if loc_dir and br_dir and loc_dir != br_dir:
            store.log_event(
                f"BROKER sync Мечел: конфликт local={loc_dir} broker={br_dir}",
                "warn",
            )
            return {
                "ok": True,
                "adopted": False,
                "conflict": True,
                "broker": spread,
                "local_direction": loc_dir,
                "pair_id": MTLR_PAIR_ID,
            }

    return {
        "ok": True,
        "adopted": False,
        "broker": spread,
        "local": bool(local),
        "local_direction": (local or {}).get("direction"),
        "pair_id": MTLR_PAIR_ID,
    }


def _mtlr_tp_deposit_leverage(settings: dict[str, Any]) -> tuple[float, float]:
    dep = mtlr_deposit_rub(settings)
    try:
        lev = float(settings.get("leverage") or SPREAD_LOT_PROD_DEFAULT_LEVERAGE)
    except (TypeError, ValueError):
        lev = SPREAD_LOT_PROD_DEFAULT_LEVERAGE
    return max(1.0, dep), max(1.0, lev)


def _maybe_mtlr_tp_exit(
    *,
    settings: dict[str, Any],
    settled: Any,
) -> tuple[str, bool]:
    """If Mechel open and MTM% ≥ take_profit_pct — close (same TP % as Tatneft)."""
    try:
        tp = float(settings.get("take_profit_pct") or 0)
    except (TypeError, ValueError):
        tp = 0.0
    if tp <= 0:
        return "", False
    open_t = store.get_mtlr_open_trade()
    pos = current_mtlr_position()
    if not open_t or pos not in (Position.LONG, Position.SHORT):
        return "", False
    if settled is None or settled.empty:
        return "", False
    last = settled.iloc[-1]
    try:
        cur_sp = float(last["spread_percent"])
    except (TypeError, ValueError, KeyError):
        return "", False
    if cur_sp != cur_sp:
        return "", False
    dep, lev = _mtlr_tp_deposit_leverage(settings)
    try:
        nom = float(open_t.get("execution_notional_rub") or 0)
    except (TypeError, ValueError):
        nom = 0.0
    entry_comm = exit_comm = 0.0
    if nom > 0:
        from live.closed_metrics import COMMISSION_PCT_PER_SIDE

        entry_comm = nom * (float(COMMISSION_PCT_PER_SIDE) / 100.0)
        exit_comm = entry_comm
    if not should_exit_take_profit(
        position=pos,
        entry_spread=open_t.get("entry_spread"),
        cur_spread=cur_sp,
        take_profit_pct=tp,
        deposit_rub=dep,
        leverage=lev,
        execution_notional_rub=nom if nom > 0 else None,
        entry_comm_rub=entry_comm,
        exit_comm_rub=exit_comm,
    ):
        return "", False

    bar = {
        "tradeDate": pd_ts_str(last["timestamp"]),
        "bar": pd_ts_str(last["timestamp"]),
        "spreadPercent": cur_sp,
        "spread": cur_sp,
        "timestampMs": _bar_ms(last["timestamp"]),
    }
    signal = Signal.EXIT_LONG if pos == Position.LONG else Signal.EXIT_SHORT
    store.log_event(
        f"Сигнал Мечел {signal.value} @ {bar['tradeDate'][:16]} S={cur_sp:.2f}% (TP {tp:g}%)",
        "signal",
    )
    try:
        close_mtlr_position(source="AUTO_MTLR_TP", signal_bar=bar)
        return f" · AUTO Мечел {signal.value} TP{tp:g}%", True
    except Exception as exc:
        store.log_event(f"AUTO Мечел fail TP {signal.value}: {exc}", "error")
        return f" · AUTO Мечел TP err: {exc}", False


def _auto_execute_mtlr_signal(
    signal: Signal,
    *,
    bar: dict[str, Any],
) -> str:
    if signal == Signal.ENTER_LONG:
        ok, reason = can_open_mtlr()
        if not ok:
            store.log_event(f"AUTO Мечел пропуск ENTER_LONG: {reason}", "warn")
            return f" · skip MTLR ENTER_LONG ({reason})"
        opened = open_mtlr_position(Position.LONG, source="AUTO_MTLR", signal_bar=bar)
        return f" · AUTO Мечел ENTER_LONG #{opened.get('trade_id')}"
    if signal == Signal.ENTER_SHORT:
        ok, reason = can_open_mtlr()
        if not ok:
            store.log_event(f"AUTO Мечел пропуск ENTER_SHORT: {reason}", "warn")
            return f" · skip MTLR ENTER_SHORT ({reason})"
        opened = open_mtlr_position(Position.SHORT, source="AUTO_MTLR", signal_bar=bar)
        return f" · AUTO Мечел ENTER_SHORT #{opened.get('trade_id')}"
    if signal in (Signal.EXIT_LONG, Signal.EXIT_SHORT):
        close_mtlr_position(source="AUTO_MTLR", signal_bar=bar)
        return f" · AUTO Мечел {signal.value}"
    return ""


def maybe_mtlr_auto_tick(settings: dict[str, Any] | None = None) -> dict[str, Any]:
    """Process new settled m15 edges for Mechel when ``mtlr_auto_execute`` is on."""
    s = settings or store.get_settings_bundle()
    out: dict[str, Any] = {"ok": True, "acted": False, "skipped": None}
    if not mtlr_enabled(s):
        out["skipped"] = "disabled"
        return out
    if not mtlr_auto_execute(s):
        out["skipped"] = "auto_off"
        return out

    try:
        df, meta = load_mtlr_m15_frame(force_refresh=False)
    except Exception as exc:
        out["ok"] = False
        out["error"] = str(exc)
        return out
    settled = _drop_unsettled_tail(df)
    if settled is None or len(settled) < 2:
        out["skipped"] = "no_bars"
        out["frame"] = meta
        return out

    levels = mtlr_levels_from_settings(s)
    last_row = settled.iloc[-1]
    latest_ms = _bar_ms(last_row["timestamp"])
    last_proc = int(s.get("mtlr_last_processed_bar_ms") or 0)
    if last_proc <= 0:
        # Seed: do not replay history when AUTO is first turned on.
        store.set_setting("mtlr_last_processed_bar_ms", str(latest_ms))
        out["skipped"] = "seeded"
        out["seeded_bar_ms"] = latest_ms
        store.log_event(
            f"Мечел AUTO: старт с бара {str(last_row['timestamp'])[:16]} (история не исполняется)",
            "info",
        )
        return out

    messages: list[str] = []
    # TP on latest settled bar (same take_profit_pct as Tatneft), even if no new edges.
    tp_msg, tp_fired = _maybe_mtlr_tp_exit(settings=s, settled=settled)
    if tp_fired:
        messages.append(tp_msg)
        out["acted"] = True
        out["messages"] = messages
        out["tp"] = True
        return out

    if latest_ms <= last_proc:
        out["skipped"] = "caught_up"
        return out

    # Bars strictly after last_proc.
    pending_idx: list[int] = []
    for i in range(len(settled)):
        ms = _bar_ms(settled.iloc[i]["timestamp"])
        if ms > last_proc:
            pending_idx.append(i)
    if not pending_idx:
        out["skipped"] = "caught_up"
        return out

    pos = current_mtlr_position()
    edges = 0
    new_last = last_proc
    for i in pending_idx:
        if edges >= MTLR_CATCHUP_MAX_EDGES:
            break
        row = settled.iloc[i]
        ms = _bar_ms(row["timestamp"])
        try:
            cur = float(row["spread_percent"])
        except (TypeError, ValueError):
            new_last = ms
            continue
        if cur != cur:
            new_last = ms
            continue
        prev_s: float | None = None
        if i > 0:
            try:
                prev_s = float(settled.iloc[i - 1]["spread_percent"])
            except (TypeError, ValueError):
                prev_s = None
        bar = {
            "tradeDate": pd_ts_str(row["timestamp"]),
            "bar": pd_ts_str(row["timestamp"]),
            "spreadPercent": cur,
            "spread": cur,
            "timestampMs": ms,
        }
        sig = determine_mtlr_signal(prev_s, cur, pos, levels)
        new_last = ms
        if sig == Signal.NONE:
            continue
        store.log_event(
            f"Сигнал Мечел {sig.value} @ {bar['tradeDate'][:16]} S={cur:.2f}% (m15)",
            "signal",
        )
        try:
            msg = _auto_execute_mtlr_signal(sig, bar=bar)
            messages.append(msg)
            out["acted"] = True
            edges += 1
            if sig == Signal.ENTER_LONG:
                pos = Position.LONG
            elif sig == Signal.ENTER_SHORT:
                pos = Position.SHORT
            elif sig in (Signal.EXIT_LONG, Signal.EXIT_SHORT):
                pos = Position.FLAT
        except Exception as exc:
            store.log_event(f"AUTO Мечел fail {sig.value}: {exc}", "error")
            messages.append(f" · AUTO Мечел err: {exc}")
            break

    if new_last > last_proc:
        store.set_setting("mtlr_last_processed_bar_ms", str(new_last))
    out["edges"] = edges
    out["messages"] = messages
    out["last_processed_bar_ms"] = new_last
    return out


def pd_ts_str(ts: Any) -> str:
    import pandas as pd

    return pd.Timestamp(ts).strftime("%Y-%m-%d %H:%M:%S")


def enrich_mtlr_status_with_live(payload: dict[str, Any]) -> dict[str, Any]:
    """Attach live open/closed trades (pair-isolated) to shadow status payload."""
    out = dict(payload)
    open_t = store.get_mtlr_open_trade()
    live_pos = current_mtlr_position().value
    out["live_position"] = live_pos
    out["open"] = open_t
    try:
        out["closed"] = store.get_mtlr_closed_trades(80)
    except Exception:
        out["closed"] = out.get("closed") or []
    out["paper_position"] = out.get("position")
    # Desk primary position: live if open, else paper shadow walk.
    if open_t:
        out["position"] = live_pos
    out["basket_open"] = basket_open_count()
    out["basket_max"] = BASKET_MAX_OPEN
    out["deposit_rub"] = mtlr_deposit_rub()
    out["phase"] = 3
    out["auto_execute_available"] = True
    out["manual_available"] = True
    try:
        tp = float(store.get_settings_bundle().get("take_profit_pct") or 0)
    except (TypeError, ValueError):
        tp = 0.0
    out["take_profit_pct"] = tp
    if out.get("auto_execute"):
        out["badge_ru"] = "AUTO · m15"
        out["note_ru"] = (
            "Мечел: m15 уровни + ордера при Авто; ручные Long/Short/Закрыть на карточке. "
            "ТП общий с Татнефть. Корзина ≤2."
        )
    else:
        out["badge_ru"] = "тень · m15"
        out["note_ru"] = (
            "Мечел: сигналы по 15м; ордера — «Авто Мечел» или ручные кнопки. "
            "Татнефть tip1m не затрагивается."
        )
    # Invalidate stale would_auto from Phase‑1.
    out["would_auto"] = bool(out.get("auto_execute")) and bool(out.get("last_signal"))
    return out


def get_mtlr_live_status(
    settings: dict[str, Any] | None = None,
    *,
    force: bool = False,
    days: int | None = None,
) -> dict[str, Any]:
    base = get_mtlr_shadow_status(settings, force=force, days=days)
    return enrich_mtlr_status_with_live(base)
