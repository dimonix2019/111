"""Partial spread exit: retry remainder with profit guard + force on 6th attempt."""

from __future__ import annotations

import time
from typing import Any

from live import store
from live.tinvest import TInvestClient, spread_legs_fill_issues

PARTIAL_CLOSE_MAX_RETRIES = 5
PARTIAL_CLOSE_PAUSE_SEC = 60


def partial_fill_filled_lots(legs: list[dict[str, Any]], expected_lots: int) -> int:
    """Minimum executed lots across spread legs (paired qty)."""
    qty = max(1, int(expected_lots))
    executed: list[int] = []
    for leg in legs or []:
        if not isinstance(leg, dict):
            continue
        order = leg.get("order") if isinstance(leg.get("order"), dict) else {}
        raw = order.get("lotsExecuted") or order.get("lots_executed")
        try:
            executed.append(max(0, int(str(raw).strip())))
        except (TypeError, ValueError):
            executed.append(0)
    if not executed:
        return 0
    return min(qty, min(executed))


def partial_fill_detail(legs: list[dict[str, Any]], expected_lots: int) -> str:
    issues = spread_legs_fill_issues(legs, expected_lots)
    return "; ".join(issues) if issues else ""


def close_would_reduce_pair_profit(
    open_t: dict[str, Any],
    current_spread: float | None,
) -> bool:
    """True when closing remainder at current spread would worsen pair PnL vs entry."""
    if current_spread is None:
        return True
    entry = open_t.get("entry_spread")
    if entry is None:
        return False
    try:
        es = float(entry)
        cs = float(current_spread)
    except (TypeError, ValueError):
        return False
    direction = str(open_t.get("direction") or "").upper()
    if direction == "LONG":
        return cs < es
    if direction == "SHORT":
        return cs > es
    return False


def _merge_exit_legs(
    prior: list[dict[str, Any]],
    new: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Keep latest leg snapshot per ticker (exit retries)."""
    by_ticker: dict[str, dict[str, Any]] = {}
    for leg in prior or []:
        if isinstance(leg, dict):
            by_ticker[str(leg.get("ticker") or "?")] = leg
    for leg in new or []:
        if isinstance(leg, dict):
            by_ticker[str(leg.get("ticker") or "?")] = leg
    return list(by_ticker.values())


def _set_partial_state(
    open_t: dict[str, Any],
    *,
    status: str,
    attempts: int,
    filled_lots: int,
    detail: str,
) -> None:
    trade_id = int(open_t["id"])
    store.update_open_trade_fields(
        trade_id,
        {
            "partial_close_status": status,
            "partial_close_attempts": attempts,
            "partial_close_filled_lots": filled_lots,
            "partial_close_detail": detail[:500],
        },
    )


def _clear_partial_state(open_t: dict[str, Any]) -> None:
    trade_id = int(open_t["id"])
    store.update_open_trade_fields(
        trade_id,
        {
            "partial_close_status": None,
            "partial_close_attempts": 0,
            "partial_close_filled_lots": None,
            "partial_close_detail": None,
        },
    )


def is_partial_close_in_progress(open_t: dict[str, Any] | None) -> bool:
    if not open_t:
        return False
    return str(open_t.get("partial_close_status") or "") == "in_progress"


def retry_partial_spread_exit(
    client: TInvestClient,
    account: str,
    open_t: dict[str, Any],
    initial_legs: list[dict[str, Any]],
    *,
    source: str,
    market_snapshot_fn,
    sleep_fn=time.sleep,
) -> tuple[list[dict[str, Any]], bool]:
    """
    After a partial exit fill, retry up to 5× (60s pause) if profit ok; 6th forces close.
    Returns (merged_legs, fully_closed).
    """
    qty_lots = int(open_t["quantity_lots"])
    legs = list(initial_legs or [])
    trade_id = int(open_t["id"])

    def _snapshot_state(attempt: int, status: str = "in_progress") -> None:
        filled = partial_fill_filled_lots(legs, qty_lots)
        detail = partial_fill_detail(legs, qty_lots)
        _set_partial_state(
            open_t,
            status=status,
            attempts=attempt,
            filled_lots=filled,
            detail=detail,
        )

    filled0 = partial_fill_filled_lots(legs, qty_lots)
    if filled0 >= qty_lots and not spread_legs_fill_issues(legs, qty_lots):
        _clear_partial_state(open_t)
        return legs, True

    detail0 = partial_fill_detail(legs, qty_lots)
    store.log_event(
        f"ВЫХОД #{trade_id} неполный: {detail0} · retry до {PARTIAL_CLOSE_MAX_RETRIES}× "
        f"(пауза {PARTIAL_CLOSE_PAUSE_SEC}с)",
        "warn",
    )
    _snapshot_state(0)

    for attempt in range(1, PARTIAL_CLOSE_MAX_RETRIES + 2):
        if attempt > 1:
            sleep_fn(PARTIAL_CLOSE_PAUSE_SEC)

        force = attempt > PARTIAL_CLOSE_MAX_RETRIES
        filled = partial_fill_filled_lots(legs, qty_lots)
        issues = spread_legs_fill_issues(legs, qty_lots)
        if filled >= qty_lots and not issues:
            _clear_partial_state(open_t)
            store.log_event(
                f"ВЫХОД #{trade_id} дозакрыт · попытка {attempt}/{PARTIAL_CLOSE_MAX_RETRIES + 1}",
                "info",
            )
            return legs, True

        if not force:
            snap = market_snapshot_fn()
            cur_sp = snap.get("spread") if isinstance(snap, dict) else None
            if close_would_reduce_pair_profit(open_t, cur_sp):
                store.log_event(
                    f"ВЫХОД #{trade_id} partial retry {attempt}/{PARTIAL_CLOSE_MAX_RETRIES}: "
                    f"пропуск — закрытие ухудшит PnL (спред {cur_sp})",
                    "warn",
                )
                _snapshot_state(attempt)
                continue

        try:
            pf = client.get_portfolio(account)
            remaining = client.detect_spread_position(pf)
        except Exception as exc:
            store.log_event(
                f"ВЫХОД #{trade_id} partial retry {attempt}: брокер недоступен — {exc}",
                "error",
            )
            _snapshot_state(attempt, status="in_progress")
            continue

        if not remaining:
            _clear_partial_state(open_t)
            store.log_event(
                f"ВЫХОД #{trade_id} partial: брокер flat после попытки {attempt}",
                "info",
            )
            return legs, True

        rem_qty = max(1, int(remaining.get("quantity_lots") or 1))
        tag = "force" if force else "retry"
        store.log_event(
            f"ВЫХОД #{trade_id} partial {tag} {attempt}/{PARTIAL_CLOSE_MAX_RETRIES + 1}: "
            f"остаток {rem_qty} лот",
            "info",
        )
        try:
            new_legs = client.execute_spread_exit(
                account,
                open_t["entry_signal"] or open_t["direction"],
                rem_qty,
            )
        except Exception as exc:
            store.log_event(
                f"ВЫХОД #{trade_id} partial {tag} {attempt} fail: {exc}",
                "error",
            )
            _snapshot_state(attempt)
            continue

        legs = _merge_exit_legs(legs, new_legs)
        filled = partial_fill_filled_lots(legs, qty_lots)
        issues = spread_legs_fill_issues(legs, qty_lots)
        _snapshot_state(attempt)

        if filled >= qty_lots and not issues:
            _clear_partial_state(open_t)
            store.log_event(
                f"ВЫХОД #{trade_id} дозакрыт · попытка {attempt}/{PARTIAL_CLOSE_MAX_RETRIES + 1}",
                "info",
            )
            return legs, True

        try:
            pf2 = client.get_portfolio(account)
            if not client.detect_spread_position(pf2):
                _clear_partial_state(open_t)
                return legs, True
        except Exception:
            pass

    detail = partial_fill_detail(legs, qty_lots)
    _set_partial_state(
        open_t,
        status="failed",
        attempts=PARTIAL_CLOSE_MAX_RETRIES + 1,
        filled_lots=partial_fill_filled_lots(legs, qty_lots),
        detail=detail,
    )
    store.log_event(
        f"ВЫХОД #{trade_id} НЕ ЗАКРЫТ после {PARTIAL_CLOSE_MAX_RETRIES + 1} попыток: {detail}",
        "error",
    )
    return legs, False
