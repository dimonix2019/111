"""Тексты entry_comment / close_comment для журнала Прода (колонка «Коммент.»)."""

from __future__ import annotations

import json
from typing import Any

from live.constants import (
    DEFAULT_SPREAD_ENTER_NARROW,
    DEFAULT_SPREAD_ENTER_WIDE,
    DEFAULT_SPREAD_EXIT_NARROW,
    DEFAULT_SPREAD_EXIT_WIDE,
)
from live.signals import is_moex_equity_session_bar


def _fmt_lvl(v: Any, nd: int = 1) -> str:
    try:
        n = float(v)
    except (TypeError, ValueError):
        return str(v or "")
    s = f"{n:.{nd}f}".rstrip("0").rstrip(".")
    return s or "0"


def lots_pair_label(lots: Any) -> str:
    try:
        n = int(lots or 0)
    except (TypeError, ValueError):
        n = 0
    n = max(0, n)
    return f"{n}+{n}"


def _direction_label(direction: Any) -> str:
    d = str(direction or "").upper()
    if d == "SHORT":
        return "SHORT"
    if d == "LONG":
        return "LONG"
    return d or "LONG"


def manual_entry_comment(
    *,
    direction: Any,
    quantity_lots: Any,
    adopted: bool = False,
) -> str:
    body = f"MANUAL {_direction_label(direction)} {lots_pair_label(quantity_lots)}"
    if adopted:
        return f"подхват {body}"
    return body


def auto_entry_comment(
    *,
    direction: Any = "LONG",
    settings: dict[str, Any] | None = None,
) -> str:
    s = settings or {}
    bits = ["AUTO"]
    d = _direction_label(direction)
    try:
        enter_n = float(s.get("spread_enter_narrow") or DEFAULT_SPREAD_ENTER_NARROW)
        exit_n = float(s.get("spread_exit_narrow") or DEFAULT_SPREAD_EXIT_NARROW)
        enter_w = float(s.get("spread_enter_wide") or DEFAULT_SPREAD_ENTER_WIDE)
        exit_w = float(s.get("spread_exit_wide") or DEFAULT_SPREAD_EXIT_WIDE)
    except (TypeError, ValueError):
        enter_n, exit_n = DEFAULT_SPREAD_ENTER_NARROW, DEFAULT_SPREAD_EXIT_NARROW
        enter_w, exit_w = DEFAULT_SPREAD_ENTER_WIDE, DEFAULT_SPREAD_EXIT_WIDE
    if d == "SHORT":
        bits.append(f"S {_fmt_lvl(enter_w)}→{_fmt_lvl(exit_w)}")
    else:
        bits.append(f"L {_fmt_lvl(enter_n)}→{_fmt_lvl(exit_n)}")
    try:
        tp = float(s.get("take_profit_pct") or 0)
    except (TypeError, ValueError):
        tp = 0.0
    if tp > 0:
        bits.append(f"ТП {_fmt_lvl(tp, 2)}%")
    return " · ".join(bits)


def entry_comment_for_open(
    *,
    source: str,
    direction: Any,
    quantity_lots: Any,
    settings: dict[str, Any] | None = None,
    adopted: bool = False,
) -> str:
    src = str(source or "").upper()
    if adopted or src in ("BROKER", "RECONCILE", "SYNC"):
        return manual_entry_comment(
            direction=direction, quantity_lots=quantity_lots, adopted=True
        )
    if src == "MANUAL":
        return manual_entry_comment(
            direction=direction, quantity_lots=quantity_lots, adopted=False
        )
    if src.startswith("AUTO"):
        return auto_entry_comment(direction=direction, settings=settings)
    return manual_entry_comment(
        direction=direction, quantity_lots=quantity_lots, adopted=False
    )


def close_comment_for_source(
    source: str,
    *,
    settings: dict[str, Any] | None = None,
    signal_bar: dict[str, Any] | None = None,
    ghost: bool = False,
) -> str:
    if ghost:
        return "сверка ghost"
    src = str(source or "").upper()
    if src in ("RECONCILE", "BROKER", "SYNC") or "GHOST" in src:
        return "сверка ghost"
    if "AUTO_TP" in src or src.endswith("_TP"):
        off_session = False
        try:
            from live.dealer_quotes import want_dealer_quotes

            off_session = bool(want_dealer_quotes())
        except Exception:
            off_session = False
        if isinstance(signal_bar, dict):
            td = str(signal_bar.get("tradeDate") or "")
            if td and not is_moex_equity_session_bar(td):
                off_session = True
        if off_session:
            return "AUTO_TP вне сессии"
        try:
            tp = float((settings or {}).get("take_profit_pct") or 0)
        except (TypeError, ValueError):
            tp = 0.0
        if tp > 0:
            return f"ТП {_fmt_lvl(tp, 2)}%"
        return "AUTO_TP"
    if src == "MANUAL":
        return "MANUAL"
    if src.startswith("AUTO"):
        return src.replace("_", " ")
    return src or "MANUAL"


def _legs_note(row: dict[str, Any]) -> str:
    legs = row.get("legs")
    if not isinstance(legs, list):
        raw = row.get("legs_json")
        if isinstance(raw, str) and raw.strip():
            try:
                legs = json.loads(raw)
            except json.JSONDecodeError:
                legs = []
        else:
            legs = []
    if legs and isinstance(legs[0], dict):
        return str(legs[0].get("note") or "")
    return ""


def is_ghost_close_row(row: dict[str, Any]) -> bool:
    return "broker flat" in _legs_note(row).lower()


def _is_blank(v: Any) -> bool:
    return v is None or str(v).strip() == ""


def infer_missing_closed_comments(row: dict[str, Any]) -> dict[str, str]:
    """Только пустые поля. Не перетирает уже записанный текст."""
    patch: dict[str, str] = {}
    ghost = is_ghost_close_row(row)
    src = str(row.get("source") or "")
    if _is_blank(row.get("entry_comment")):
        if ghost or src.upper() in ("MANUAL", "BROKER", "RECONCILE", "SYNC"):
            adopted = ghost or src.upper() in ("BROKER", "RECONCILE", "SYNC")
            # Фантом сверки — это бывший локальный open (часто ручной/подхват).
            if ghost:
                adopted = False
            patch["entry_comment"] = manual_entry_comment(
                direction=row.get("direction"),
                quantity_lots=row.get("quantity_lots"),
                adopted=adopted,
            )
        elif src.upper().startswith("AUTO"):
            patch["entry_comment"] = auto_entry_comment(
                direction=row.get("direction")
            )
    if _is_blank(row.get("close_comment")):
        if ghost:
            patch["close_comment"] = "сверка ghost"
        elif "AUTO_TP" in src.upper():
            exit_td = str(row.get("exit_time") or row.get("exit_fill_time") or "")
            patch["close_comment"] = close_comment_for_source(
                src, signal_bar={"tradeDate": exit_td} if exit_td else None
            )
    return patch


# Журнал 05.09: фантом сверки (#20) и закрытие AUTO_TP после подхвата (#21).
_KNOWN_EMPTY_CLOSE_IDS = {
    20: {"close_comment": "сверка ghost"},
    21: {"close_comment": "AUTO_TP вне сессии"},
}


def known_id_comment_patch(row: dict[str, Any]) -> dict[str, str]:
    """Точечный бэкфилл #20/#21, только пустые поля."""
    try:
        tid = int(row.get("id") or 0)
    except (TypeError, ValueError):
        return {}
    extra = dict(_KNOWN_EMPTY_CLOSE_IDS.get(tid) or {})
    out: dict[str, str] = {}
    for k, v in extra.items():
        if _is_blank(row.get(k)):
            out[k] = v
    if tid in _KNOWN_EMPTY_CLOSE_IDS and _is_blank(row.get("entry_comment")):
        out["entry_comment"] = manual_entry_comment(
            direction=row.get("direction"),
            quantity_lots=row.get("quantity_lots"),
            adopted=(tid == 21),
        )
    return out
