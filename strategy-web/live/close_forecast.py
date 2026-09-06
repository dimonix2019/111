"""Forecast broker equity/cash after closing the open pair now (bid/ask, exit fee, overnight)."""

from __future__ import annotations

import math
import time
from typing import Any

from live.closed_metrics import COMMISSION_PCT_PER_SIDE, _pnl_constants
from live.open_mark import fill_prices_from_legs, legs_unrealized_rub, overnight_days
from live.overnight_fee import (
    overnight_fee_from_open,
    overnight_fee_per_day_rub,
    short_leg_uncovered_rub,
)

# Half-tick fallback when ISS BID/OFFER empty (after hours / thin book).
_FALLBACK_HALF_SPREAD_RUB = 0.05
_QUOTES_TTL_SEC = 12.0
_QUOTES_CACHE: dict[str, Any] = {"ts": 0.0, "payload": None}


def _f(v: Any) -> float | None:
    if v is None:
        return None
    try:
        x = float(v)
    except (TypeError, ValueError):
        return None
    return x if x == x else None  # NaN guard


def _pick_px(*vals: Any) -> float | None:
    for v in vals:
        x = _f(v)
        if x is not None and x > 0:
            return x
    return None


def quotes_from_dealer(dealer: dict[str, Any] | None) -> dict[str, Any] | None:
    """Map desk dealer payload → pair quotes (weekend / OTC book)."""
    if not isinstance(dealer, dict) or not dealer.get("ok"):
        return None
    tatn_last = _pick_px(dealer.get("tatn"), dealer.get("tatn_last"), dealer.get("tatn_mid"))
    tatnp_last = _pick_px(dealer.get("tatnp"), dealer.get("tatnp_last"), dealer.get("tatnp_mid"))
    tatn_bid = _pick_px(dealer.get("tatn_bid"))
    tatn_ask = _pick_px(dealer.get("tatn_ask"))
    tatnp_bid = _pick_px(dealer.get("tatnp_bid"))
    tatnp_ask = _pick_px(dealer.get("tatnp_ask"))
    if not (
        (tatn_last or tatn_bid or tatn_ask)
        and (tatnp_last or tatnp_bid or tatnp_ask)
    ):
        return None
    return {
        "ok": True,
        "source": "dealer",
        "error": None,
        "tatn": {
            "last": tatn_last,
            "bid": tatn_bid,
            "ask": tatn_ask,
            "has_book": tatn_bid is not None and tatn_ask is not None,
        },
        "tatnp": {
            "last": tatnp_last,
            "bid": tatnp_bid,
            "ask": tatnp_ask,
            "has_book": tatnp_bid is not None and tatnp_ask is not None,
        },
    }


def _merge_quote_leg(primary: dict[str, Any], fallback: dict[str, Any]) -> dict[str, Any]:
    last = _pick_px(primary.get("last"), fallback.get("last"))
    bid = _pick_px(primary.get("bid"), fallback.get("bid"))
    ask = _pick_px(primary.get("ask"), fallback.get("ask"))
    return {
        "last": last,
        "bid": bid,
        "ask": ask,
        "has_book": bid is not None and ask is not None,
    }


def fetch_pair_quotes(
    *,
    force: bool = False,
    dealer: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """ISS BID/OFFER/LAST for TATN+TATNP (cached), optionally merged with dealer book."""
    dealer_q = quotes_from_dealer(dealer)
    now = time.time()
    if (
        not force
        and _QUOTES_CACHE["payload"] is not None
        and (now - float(_QUOTES_CACHE["ts"])) < _QUOTES_TTL_SEC
    ):
        out = dict(_QUOTES_CACHE["payload"])
    else:
        out = {
            "ok": False,
            "tatn": {},
            "tatnp": {},
            "error": None,
            "source": "iss",
        }
        try:
            from m15_iss_loader import fetch_live_quote_row

            for secid, key in (("TATN", "tatn"), ("TATNP", "tatnp")):
                row = fetch_live_quote_row(secid) or {}
                last = _pick_px(row.get("LAST"), row.get("LCURRENTPRICE"), row.get("MARKETPRICE"))
                bid = _pick_px(row.get("BID"), row.get("LASTBID"))
                ask = _pick_px(row.get("OFFER"), row.get("LASTOFFER"))
                out[key] = {
                    "last": last,
                    "bid": bid,
                    "ask": ask,
                    "has_book": bid is not None and ask is not None,
                }
            out["ok"] = bool(
                (out["tatn"].get("last") or out["tatn"].get("bid") or out["tatn"].get("ask"))
                and (out["tatnp"].get("last") or out["tatnp"].get("bid") or out["tatnp"].get("ask"))
            )
        except Exception as exc:
            out["error"] = str(exc)

        _QUOTES_CACHE["ts"] = now
        _QUOTES_CACHE["payload"] = out
        out = dict(out)

    # Prefer dealer book on weekends / when ISS book empty.
    if dealer_q:
        iss_book = bool(
            (out.get("tatn") or {}).get("has_book") and (out.get("tatnp") or {}).get("has_book")
        )
        dealer_book = bool(
            (dealer_q.get("tatn") or {}).get("has_book")
            and (dealer_q.get("tatnp") or {}).get("has_book")
        )
        if dealer_book or not iss_book:
            if iss_book:
                # Keep ISS mid/last if present; fill gaps from dealer.
                out = {
                    "ok": True,
                    "source": "iss+dealer",
                    "error": out.get("error"),
                    "tatn": _merge_quote_leg(out.get("tatn") or {}, dealer_q.get("tatn") or {}),
                    "tatnp": _merge_quote_leg(out.get("tatnp") or {}, dealer_q.get("tatnp") or {}),
                }
            else:
                # Dealer primary (weekend OTC), ISS LAST as fill.
                out = {
                    "ok": True,
                    "source": "dealer",
                    "error": out.get("error"),
                    "tatn": _merge_quote_leg(dealer_q.get("tatn") or {}, out.get("tatn") or {}),
                    "tatnp": _merge_quote_leg(dealer_q.get("tatnp") or {}, out.get("tatnp") or {}),
                }
        elif not out.get("ok"):
            out = dealer_q

    return out


def _synth_book(last: float | None, bid: float | None, ask: float | None) -> tuple[float | None, float | None, str]:
    """Return (bid, ask, mode) with adverse-capable prices."""
    if bid is not None and ask is not None and bid > 0 and ask > 0:
        return bid, ask, "book"
    mid = last
    if mid is None or mid <= 0:
        if bid is not None and ask is not None:
            return bid, ask, "book"
        if bid is not None:
            return bid, bid + _FALLBACK_HALF_SPREAD_RUB * 2, "partial"
        if ask is not None:
            return max(0.01, ask - _FALLBACK_HALF_SPREAD_RUB * 2), ask, "partial"
        return None, None, "none"
    half = _FALLBACK_HALF_SPREAD_RUB
    b = bid if bid is not None and bid > 0 else mid - half
    a = ask if ask is not None and ask > 0 else mid + half
    if b <= 0:
        b = mid * 0.9999
    if a <= 0:
        a = mid * 1.0001
    if a < b:
        a = b
    mode = "book" if (bid is not None and ask is not None) else "last_fallback"
    return b, a, mode


def adverse_close_leg_prices(
    *,
    direction: str,
    quotes: dict[str, Any],
    mid_tatn: float | None = None,
    mid_tatnp: float | None = None,
) -> dict[str, Any]:
    """
    Prices for closing both legs now (adverse side).
    Long: sell TATN @ bid, cover TATNP @ ask.
    Short: cover TATN @ ask, sell TATNP @ bid.
    """
    tn = quotes.get("tatn") or {}
    tp = quotes.get("tatnp") or {}
    tn_bid, tn_ask, tn_mode = _synth_book(
        _pick_px(tn.get("last"), mid_tatn),
        _f(tn.get("bid")),
        _f(tn.get("ask")),
    )
    tp_bid, tp_ask, tp_mode = _synth_book(
        _pick_px(tp.get("last"), mid_tatnp),
        _f(tp.get("bid")),
        _f(tp.get("ask")),
    )
    d = (direction or "").upper()
    if d.startswith("L"):
        close_tatn, close_tatnp = tn_bid, tp_ask
        side_note = "Long: TATN BID · TATNP ASK"
    elif d.startswith("S"):
        close_tatn, close_tatnp = tn_ask, tp_bid
        side_note = "Short: TATN ASK · TATNP BID"
    else:
        close_tatn = close_tatnp = None
        side_note = "—"

    book_ok = tn_mode == "book" and tp_mode == "book"
    src = str(quotes.get("source") or "")
    if book_ok:
        qmode = "book"
    elif tn_mode != "none" and tp_mode != "none":
        qmode = "last_fallback"
    else:
        qmode = "none"
    if book_ok and src.startswith("dealer"):
        qmode = "dealer_book"
    return {
        "close_tatn": close_tatn,
        "close_tatnp": close_tatnp,
        "mid_tatn": _pick_px(tn.get("last"), mid_tatn, tn_bid, tn_ask),
        "mid_tatnp": _pick_px(tp.get("last"), mid_tatnp, tp_bid, tp_ask),
        "tatn_bid": tn_bid,
        "tatn_ask": tn_ask,
        "tatnp_bid": tp_bid,
        "tatnp_ask": tp_ask,
        "quotes_mode": qmode,
        "side_note": side_note,
    }


def compute_close_forecast(
    open_t: dict[str, Any] | None,
    *,
    broker: dict[str, Any] | None = None,
    mark: dict[str, Any] | None = None,
    settings: dict[str, Any] | None = None,
    trade_date: str | None = None,
    tatn_now: float | None = None,
    tatnp_now: float | None = None,
    quotes: dict[str, Any] | None = None,
    dealer: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """
    Прогноз средств после закрытия пары сейчас:
      equity_after ≈ equity_now + (pnl_adverse − pnl_mid) − exit_comm − overnight
    """
    out: dict[str, Any] = {
        "ok": False,
        "has_position": False,
        "forecast_total_rub": None,
        "equity_now_rub": None,
        "equity_at_open_rub": None,
        "cash_now_rub": None,
        "mid_pnl_rub": None,
        "adverse_pnl_rub": None,
        "vs_mid_rub": None,
        "exit_commission_rub": None,
        "overnight_rub": None,
        "overnight_days": 0,
        "quotes_mode": None,
        "note": None,
        "exit_level_spread": None,
        "exit_level_pnl_rub": None,
        "exit_level_pnl_pct": None,
        "exit_level_entry_spread": None,
        "exit_level_deposit_rub": None,
        "overnight_per_day_rub": None,
        "exit_level_ovn_days_to_red": None,
    }
    if not open_t:
        out["ok"] = True
        out["note"] = "нет позиции"
        return out

    out["has_position"] = True
    settings = settings or {}
    mark = mark or (open_t.get("mark") if isinstance(open_t.get("mark"), dict) else {}) or {}
    direction = str(open_t.get("direction") or "")
    lots = int(open_t.get("quantity_lots") or 0)
    notional = _f(mark.get("notional_rub")) or _f(open_t.get("execution_notional_rub")) or 0.0
    deposit = _f(settings.get("entry_deposit_rub")) or 10_000.0
    lev = _f(settings.get("leverage")) or 7.0

    equity = None
    cash = None
    if isinstance(broker, dict) and not broker.get("error"):
        equity = _f(broker.get("total_rub"))
        cash = _f(broker.get("cash_rub"))
    out["equity_now_rub"] = equity
    out["cash_now_rub"] = cash

    broker_yield = None
    if isinstance(broker, dict) and not broker.get("error"):
        broker_yield = _f(broker.get("expected_yield_rub"))
        if broker_yield is None and isinstance(broker.get("leg_yield"), dict):
            broker_yield = _f(broker["leg_yield"].get("net_gross_rub")) or _f(
                broker["leg_yield"].get("expected_yield_rub")
            )
    if broker_yield is None and str(mark.get("pnl_source") or "") == "tinkoff_expected_yield":
        broker_yield = _f(mark.get("expected_yield_rub")) or _f(mark.get("unrealized_pnl_rub"))
    out["expected_yield_rub"] = round(broker_yield, 2) if broker_yield is not None else None

    equity_open = None
    if broker_yield is not None and equity is not None:
        # Align «До» with broker: current total − expectedYield (not stale legs snapshot).
        equity_open = float(equity) - float(broker_yield)
    else:
        try:
            from live.closed_metrics import account_after_from_legs

            equity_open = account_after_from_legs(
                open_t.get("legs") or open_t.get("legs_json")
            )
        except Exception:
            equity_open = None
        if equity_open is None:
            equity_open = _f(open_t.get("equity_at_open_rub")) or _f(
                open_t.get("account_after_rub")
            )
        if equity_open is None and equity is not None:
            mtm = _f(mark.get("unrealized_pnl_rub"))
            if mtm is None:
                mtm = _f(mark.get("net_approx_rub"))
            if mtm is not None:
                equity_open = float(equity) - float(mtm)
    out["equity_at_open_rub"] = (
        round(float(equity_open), 2) if equity_open is not None else None
    )

    constants = _pnl_constants(
        execution_notional_rub=notional if notional > 0 else None,
        deposit_rub=deposit,
        leverage=lev,
    )
    exit_comm = float(constants["comm_per_side"])  # exit of pair ≈ both legs
    out["exit_commission_rub"] = round(exit_comm, 2)
    eff_notional = float(constants["eff_notional"])
    deposit_base = float(constants["deposit"])

    mid_pnl = _f(mark.get("unrealized_pnl_rub"))
    fill_tatn, fill_tatnp = fill_prices_from_legs(open_t)
    # MANUAL / tip fills may live on open row when legs_json thin
    if fill_tatn is None:
        fill_tatn = _pick_px(open_t.get("entry_tatn"), open_t.get("fill_tatn"))
    if fill_tatnp is None:
        fill_tatnp = _pick_px(open_t.get("entry_tatnp"), open_t.get("fill_tatnp"))
    if fill_tatn is None:
        fill_tatn = _f(mark.get("fill_tatn"))
    if fill_tatnp is None:
        fill_tatnp = _f(mark.get("fill_tatnp"))

    # Prefer desk mark overnight (T‑Invest ступени на короткую ногу; не % от номинала).
    ovn_days = int(mark.get("overnight_days") or 0)
    if not ovn_days:
        ovn_days = overnight_days(open_t.get("entry_time"), trade_date)
    overnight = _f(mark.get("overnight_rub"))
    if overnight is None:
        overnight = overnight_fee_from_open(
            open_t,
            fill_tatn=fill_tatn,
            fill_tatnp=fill_tatnp,
            days=ovn_days,
        )
    overnight_rub = float(overnight or 0.0)
    out["overnight_rub"] = round(overnight_rub, 2)
    out["overnight_days"] = ovn_days

    ovn_per_day = _f(mark.get("overnight_per_day_rub"))
    if ovn_per_day is None:
        uncovered = short_leg_uncovered_rub(
            direction=direction,
            lots=lots,
            fill_tatn=fill_tatn,
            fill_tatnp=fill_tatnp,
            notional_rub=notional if notional > 0 else None,
        )
        ovn_per_day = overnight_fee_per_day_rub(uncovered)
    out["overnight_per_day_rub"] = round(float(ovn_per_day or 0.0), 4)

    # Потенциал при выходе на уровне спреда (L вых / S вых): ΔS×номинал − выходная комиссия − overnight.
    entry_spread = _f(mark.get("fill_spread")) or _f(open_t.get("entry_spread"))
    try:
        from live.spread_levels import levels_from_settings

        lv = levels_from_settings(settings)
    except Exception:
        lv = None
    d_up = (direction or "").upper()
    exit_level = None
    if lv is not None:
        if d_up.startswith("L"):
            exit_level = float(lv.exit_narrow)
        elif d_up.startswith("S"):
            exit_level = float(lv.exit_wide)
    if entry_spread is not None and exit_level is not None and eff_notional > 0:
        if d_up.startswith("L"):
            pnl_pts = exit_level - float(entry_spread)
        else:
            pnl_pts = float(entry_spread) - exit_level
        gross = eff_notional * (pnl_pts / 100.0)
        # Как в прогнозе закрытия: осталась только комиссия выхода + overnight на сейчас.
        level_pnl = gross - exit_comm - overnight_rub
        out["exit_level_spread"] = round(exit_level, 4)
        out["exit_level_entry_spread"] = round(float(entry_spread), 4)
        out["exit_level_pnl_rub"] = round(level_pnl, 2)
        out["exit_level_deposit_rub"] = round(deposit_base, 2)
        if deposit_base > 0:
            out["exit_level_pnl_pct"] = round((level_pnl / deposit_base) * 100.0, 4)
        # Подушка при выходе / ₽·день → через сколько полуночей плюс ≤ 0.
        rate = float(ovn_per_day or 0.0)
        if level_pnl <= 0:
            out["exit_level_ovn_days_to_red"] = 0
        elif rate > 0:
            out["exit_level_ovn_days_to_red"] = int(math.ceil(level_pnl / rate))
        else:
            out["exit_level_ovn_days_to_red"] = None

    # Broker expectedYield already in total_rub. Do not hit ISS BID/OFFER (15s×2)
    # or local fill MTM — that froze /trade/desk and invented −805 vs +584.
    if broker_yield is not None and equity is not None:
        out["mid_pnl_rub"] = round(broker_yield, 2)
        out["adverse_pnl_rub"] = round(broker_yield, 2)
        out["vs_mid_rub"] = 0.0
        out["quotes_mode"] = "broker_expected_yield"
        out["side_note"] = "GetPortfolio.expectedYield"
        out["forecast_total_rub"] = round(float(equity) - exit_comm, 2)
        out["ok"] = True
        out["note"] = "PnL = GetPortfolio.expectedYield"
        out["commission_pct_per_side"] = COMMISSION_PCT_PER_SIDE
        return out

    mid_tatn = _pick_px(tatn_now, mark.get("tatn_now"))
    mid_tatnp = _pick_px(tatnp_now, mark.get("tatnp_now"))

    if quotes is not None:
        q = quotes
    else:
        q = fetch_pair_quotes(dealer=dealer)
    # Last-resort mid synth when ISS/dealer empty but desk has mark prices
    if not q.get("ok"):
        synth = {
            "ok": bool(mid_tatn and mid_tatnp),
            "source": "mark_mid",
            "error": q.get("error"),
            "tatn": {"last": mid_tatn, "bid": None, "ask": None, "has_book": False},
            "tatnp": {"last": mid_tatnp, "bid": None, "ask": None, "has_book": False},
        }
        if synth["ok"]:
            q = synth
    legs = adverse_close_leg_prices(
        direction=direction,
        quotes=q,
        mid_tatn=mid_tatn,
        mid_tatnp=mid_tatnp,
    )
    out["quotes_mode"] = legs.get("quotes_mode")
    out["close_tatn"] = legs.get("close_tatn")
    out["close_tatnp"] = legs.get("close_tatnp")
    out["side_note"] = legs.get("side_note")

    adverse_pnl = None
    if (
        fill_tatn
        and fill_tatnp
        and lots > 0
        and legs.get("close_tatn")
        and legs.get("close_tatnp")
    ):
        adverse_pnl = legs_unrealized_rub(
            direction=direction,
            lots=lots,
            fill_tatn=float(fill_tatn),
            fill_tatnp=float(fill_tatnp),
            now_tatn=float(legs["close_tatn"]),
            now_tatnp=float(legs["close_tatnp"]),
        )

    # Mid from same fills @ LAST when mark mid missing
    if mid_pnl is None and fill_tatn and fill_tatnp and lots > 0:
        mt = legs.get("mid_tatn")
        mp = legs.get("mid_tatnp")
        if mt and mp:
            mid_pnl = legs_unrealized_rub(
                direction=direction,
                lots=lots,
                fill_tatn=float(fill_tatn),
                fill_tatnp=float(fill_tatnp),
                now_tatn=float(mt),
                now_tatnp=float(mp),
            )

    out["mid_pnl_rub"] = round(mid_pnl, 2) if mid_pnl is not None else None
    out["adverse_pnl_rub"] = round(adverse_pnl, 2) if adverse_pnl is not None else None
    vs_mid = None
    if adverse_pnl is not None and mid_pnl is not None:
        vs_mid = adverse_pnl - mid_pnl
    elif adverse_pnl is not None:
        vs_mid = 0.0
    out["vs_mid_rub"] = round(vs_mid, 2) if vs_mid is not None else None

    if equity is None or adverse_pnl is None or mid_pnl is None:
        out["note"] = (
            "недостаточно данных для прогноза"
            if equity is None or adverse_pnl is None
            else None
        )
        # Still allow forecast from equity + costs if we only lack vs_mid
        if equity is not None and mid_pnl is not None and adverse_pnl is None:
            # no quotes — subtract fees only from equity (conservative: keep mid)
            forecast = equity - exit_comm - float(overnight or 0.0)
            out["forecast_total_rub"] = round(forecast, 2)
            out["ok"] = True
            out["note"] = "без bid/ask — только комиссии и overnight"
            return out
        if equity is not None and mid_pnl is not None and adverse_pnl is not None:
            pass
        else:
            return out

    forecast = (
        float(equity)
        + float(vs_mid or 0.0)
        - exit_comm
        - float(overnight or 0.0)
    )
    out["forecast_total_rub"] = round(forecast, 2)
    out["ok"] = True
    if out["quotes_mode"] == "last_fallback":
        out["note"] = "BID/OFFER нет — оценка от LAST ±0.05₽"
    elif out["quotes_mode"] == "dealer_book":
        out["note"] = "по дилерскому стакану"
    elif out["quotes_mode"] == "book":
        out["note"] = "по BID/OFFER ISS"
    out["commission_pct_per_side"] = COMMISSION_PCT_PER_SIDE
    return out
