"""Open-trade mark-to-market + simple risk flags for Trade desk."""

from __future__ import annotations

import json
from datetime import datetime
from typing import Any

from live.overnight_fee import (
    OVERNIGHT_FEE_PERCENT_PER_DAY,
    overnight_fee_from_open,
    overnight_fee_per_day_rub,
    short_leg_uncovered_rub,
)

# Re-export for open_stats / close_forecast importers.
__all__ = [
    "OVERNIGHT_FEE_PERCENT_PER_DAY",
    "overnight_days",
    "overnight_fee_from_open",
    "overnight_fee_per_day_rub",
    "short_leg_uncovered_rub",
]


def _parse_dt(s: str | None) -> datetime | None:
    if not s:
        return None
    for n, fmt in ((19, "%Y-%m-%d %H:%M:%S"), (16, "%Y-%m-%d %H:%M"), (10, "%Y-%m-%d")):
        try:
            return datetime.strptime(str(s).strip()[:n], fmt)
        except ValueError:
            continue
    return None


def overnight_days(entry: str | None, now: str | None) -> int:
    a = _parse_dt(entry)
    b = _parse_dt(now)
    if a is None or b is None:
        return 0
    return max(0, (b.date() - a.date()).days)


def _quotation_to_float(o: Any) -> float | None:
    if o is None:
        return None
    if isinstance(o, (int, float)):
        return float(o)
    if not isinstance(o, dict):
        return None
    nano = float(o.get("nano") or o.get("Nano") or 0)
    raw = o.get("units", o.get("Units"))
    if raw is None:
        units = 0.0
    elif isinstance(raw, (int, float)):
        units = float(raw)
    else:
        try:
            units = float(str(raw).strip())
        except ValueError:
            return None
    return units + nano / 1_000_000_000.0


def fill_prices_from_legs(open_t: dict[str, Any]) -> tuple[float | None, float | None]:
    """Средняя цена исполнения TATN / TATNP из legs_json (ордера брокера)."""
    raw = open_t.get("legs_json")
    if not raw:
        return None, None
    try:
        legs = json.loads(raw) if isinstance(raw, str) else raw
    except (TypeError, json.JSONDecodeError):
        return None, None
    if not isinstance(legs, list):
        return None, None
    tatn = tatnp = None
    for leg in legs:
        if not isinstance(leg, dict):
            continue
        ticker = str(leg.get("ticker") or "").upper()
        order = leg.get("order") if isinstance(leg.get("order"), dict) else {}
        px = _quotation_to_float(order.get("executedOrderPrice"))
        if px is None or px <= 0:
            px = _quotation_to_float(order.get("initialSecurityPrice"))
        if px is None or px <= 0:
            continue
        if ticker == "TATN":
            tatn = px
        elif ticker == "TATNP":
            tatnp = px
    return tatn, tatnp


def adverse_entry_slip_pts(
    direction: str | None,
    iss_spread: float | None,
    fill_spread: float | None,
) -> float | None:
    """Adverse slip на входе (п.п. спреда): Long = fill−ISS, Short = ISS−fill.

    Те же единицы, что SIM_SLIPPAGE_SPREAD_PTS в Тестировании (0.12 и т.п.).
    """
    if iss_spread is None or fill_spread is None:
        return None
    try:
        iss = float(iss_spread)
        fill = float(fill_spread)
    except (TypeError, ValueError):
        return None
    d = (direction or "").upper()
    if d == "LONG":
        return fill - iss
    if d == "SHORT":
        return iss - fill
    return None


def pick_iss_spread_for_slip(
    *,
    snap_spread: float | None,
    snap_tatn: float | None,
    snap_tatnp: float | None,
    prev_spread: float | None = None,
    prev_tatn: float | None = None,
    prev_tatnp: float | None = None,
    fill_tatn: float | None = None,
    fill_tatnp: float | None = None,
) -> float | None:
    """
    ISS mid для расчёта slip: бар, ближе к ценам fill (snap или prev).
    Утро TQBR: last-бар может уже уйти, а fill ближе к prev/открытию.
    """
    cands: list[tuple[float, float]] = []  # (distance, spread)
    if (
        snap_spread is not None
        and snap_tatn is not None
        and snap_tatnp is not None
        and fill_tatn
        and fill_tatnp
    ):
        dist = abs(float(snap_tatn) - float(fill_tatn)) + abs(
            float(snap_tatnp) - float(fill_tatnp)
        )
        cands.append((dist, float(snap_spread)))
    elif snap_spread is not None:
        cands.append((0.0, float(snap_spread)))
    if (
        prev_spread is not None
        and prev_tatn is not None
        and prev_tatnp is not None
        and fill_tatn
        and fill_tatnp
    ):
        dist = abs(float(prev_tatn) - float(fill_tatn)) + abs(
            float(prev_tatnp) - float(fill_tatnp)
        )
        cands.append((dist, float(prev_spread)))
    if not cands:
        return float(snap_spread) if snap_spread is not None else None
    cands.sort(key=lambda x: x[0])
    return cands[0][1]


def pick_iss_spread_near_entry_spread(
    *,
    entry_spread: float,
    entry_bar: dict[str, Any] | None,
    prev_bar: dict[str, Any] | None,
) -> float | None:
    """ISS mid ближе к entry_spread (fill mid) — среди entry/prev бара."""
    cands: list[tuple[float, float]] = []
    for b in (prev_bar, entry_bar):
        if not b:
            continue
        sp = b.get("spreadPercent")
        if sp is None:
            sp = b.get("spread")
        if sp is None:
            continue
        try:
            iss = float(sp)
            cands.append((abs(iss - float(entry_spread)), iss))
        except (TypeError, ValueError):
            continue
    if not cands:
        return None
    cands.sort(key=lambda x: x[0])
    return cands[0][1]


def resolve_entry_slip_pts(
    trade: dict[str, Any],
    *,
    bars: list[dict[str, Any]] | None = None,
) -> tuple[float | None, float | None]:
    """
    (slip_pts, iss_spread) для истории/закрытия.
    1) уже сохранённые поля;
    2) adverse(entry_spread_iss, entry_spread);
    3) ISS entry/prev из M15 ближе к entry_spread.
    """
    stored_slip = trade.get("entry_slip_pts")
    stored_iss = trade.get("entry_spread_iss")
    try:
        if stored_slip is not None and float(stored_slip) == float(stored_slip):
            slip_v = float(stored_slip)
            iss_v = float(stored_iss) if stored_iss is not None else None
            return slip_v, iss_v
    except (TypeError, ValueError):
        pass

    entry_spread = trade.get("entry_spread")
    if entry_spread is None:
        return None, None
    try:
        e_s = float(entry_spread)
    except (TypeError, ValueError):
        return None, None

    direction = str(trade.get("direction") or "")
    iss = None
    try:
        if stored_iss is not None:
            iss = float(stored_iss)
    except (TypeError, ValueError):
        iss = None

    if iss is None and bars:
        entry_ms = None
        raw_et = trade.get("entry_time")
        if raw_et:
            from datetime import datetime

            s = str(raw_et).strip().replace("T", " ")
            for n, fmt in ((19, "%Y-%m-%d %H:%M:%S"), (16, "%Y-%m-%d %H:%M")):
                try:
                    entry_ms = int(datetime.strptime(s[:n], fmt).timestamp() * 1000)
                    break
                except ValueError:
                    continue
        if entry_ms is not None:
            sorted_bars = sorted(
                (b for b in bars if b.get("timestampMs") is not None),
                key=lambda b: int(b["timestampMs"]),
            )
            entry_bar = prev_bar = None
            for b in sorted_bars:
                if int(b["timestampMs"]) <= entry_ms + 60_000:
                    prev_bar = entry_bar
                    entry_bar = b
            iss = pick_iss_spread_near_entry_spread(
                entry_spread=e_s, entry_bar=entry_bar, prev_bar=prev_bar
            )

    slip = adverse_entry_slip_pts(direction, iss, e_s)
    return slip, iss


def legs_unrealized_rub(
    *,
    direction: str,
    lots: int,
    fill_tatn: float,
    fill_tatnp: float,
    now_tatn: float,
    now_tatnp: float,
) -> float | None:
    """
    PnL ног как у брокера: Long = long TATN + short TATNP;
    Short = short TATN + long TATNP.
    """
    if lots <= 0 or min(fill_tatn, fill_tatnp, now_tatn, now_tatnp) <= 0:
        return None
    d = (direction or "").upper()
    if d == "LONG":
        return lots * ((now_tatn - fill_tatn) + (fill_tatnp - now_tatnp))
    if d == "SHORT":
        return lots * ((fill_tatn - now_tatn) + (now_tatnp - fill_tatnp))
    return None


def enrich_open_trade(
    open_t: dict[str, Any] | None,
    *,
    z_now: float | None,
    spread_now: float | None,
    trade_date: str | None,
    entry_threshold: float = 1.3,
    tatn_now: float | None = None,
    tatnp_now: float | None = None,
    spread_level_mode: bool | None = None,
    settings: dict[str, Any] | None = None,
) -> dict[str, Any] | None:
    if not open_t:
        return None
    out = dict(open_t)
    direction = (open_t.get("direction") or "").upper()
    entry_z = open_t.get("entry_z")
    entry_spread = open_t.get("entry_spread")
    notional = float(open_t.get("execution_notional_rub") or 0) or 0.0
    lots = int(open_t.get("quantity_lots") or 1)

    z_n = float(z_now) if z_now is not None else None
    s_n = float(spread_now) if spread_now is not None else None
    e_s = float(entry_spread) if entry_spread is not None else None
    e_z = float(entry_z) if entry_z is not None else None

    fill_tatn, fill_tatnp = fill_prices_from_legs(open_t)
    tn_now = float(tatn_now) if tatn_now is not None else None
    tp_now = float(tatnp_now) if tatnp_now is not None else None
    # fallback to stored snap entry prices only for display, not for broker PnL
    snap_tatn = float(open_t["entry_tatn"]) if open_t.get("entry_tatn") is not None else None
    snap_tatnp = float(open_t["entry_tatnp"]) if open_t.get("entry_tatnp") is not None else None
    iss_spread = (
        float(open_t["entry_spread_iss"])
        if open_t.get("entry_spread_iss") is not None
        else None
    )

    pnl_pts = None
    unrealized = None
    pnl_source = "spread_snap"
    fill_spread = None
    if fill_tatn and fill_tatnp and fill_tatnp > 0:
        fill_spread = (fill_tatn - fill_tatnp) / fill_tatnp * 100.0
    entry_slip = (
        float(open_t["entry_slip_pts"])
        if open_t.get("entry_slip_pts") is not None
        else adverse_entry_slip_pts(direction, iss_spread, fill_spread)
    )

    if (
        fill_tatn
        and fill_tatnp
        and tn_now
        and tp_now
        and tn_now > 0
        and tp_now > 0
    ):
        legs_pnl = legs_unrealized_rub(
            direction=direction,
            lots=lots,
            fill_tatn=fill_tatn,
            fill_tatnp=fill_tatnp,
            now_tatn=tn_now,
            now_tatnp=tp_now,
        )
        if legs_pnl is not None:
            unrealized = legs_pnl
            pnl_source = "broker_fills"
            if fill_spread is not None and s_n is not None:
                if direction == "LONG":
                    pnl_pts = s_n - fill_spread
                elif direction == "SHORT":
                    pnl_pts = fill_spread - s_n

    if unrealized is None and e_s is not None and s_n is not None:
        if direction == "LONG":
            pnl_pts = s_n - e_s
        elif direction == "SHORT":
            pnl_pts = e_s - s_n
        if pnl_pts is not None and notional > 0:
            unrealized = notional * (pnl_pts / 100.0)
            pnl_source = "spread_snap"

    ovn_days = overnight_days(open_t.get("entry_time"), trade_date)
    # T‑Invest: ступени на непокрытую (короткая нога), не notional×0.033%/день.
    overnight_rub = overnight_fee_from_open(
        open_t,
        fill_tatn=fill_tatn or snap_tatn,
        fill_tatnp=fill_tatnp or snap_tatnp,
        days=ovn_days,
    )
    uncovered = short_leg_uncovered_rub(
        direction=direction,
        lots=lots,
        fill_tatn=fill_tatn or snap_tatn,
        fill_tatnp=fill_tatnp or snap_tatnp,
        notional_rub=notional,
    )
    overnight_per_day = overnight_fee_per_day_rub(uncovered)
    net_approx = (unrealized - overnight_rub) if unrealized is not None else None

    flags: list[str] = []
    score = 0
    entry_dt = _parse_dt(open_t.get("entry_time"))
    now_dt = _parse_dt(trade_date) or datetime.now()
    hold_h = None
    if entry_dt is not None:
        hold_h = (now_dt - entry_dt).total_seconds() / 3600.0
        if hold_h >= 48:
            flags.append("hold≥48ч")
            score += 3
        elif hold_h >= 24:
            flags.append("hold≥24ч")
            score += 2
        elif hold_h >= 8:
            flags.append("hold≥8ч")
            score += 1
    from live.spread_levels import parse_spread_level_mode
    from live.spread_risk import levels_for_trade, risk_level_from_score, spread_risk_flags

    use_spread = (
        bool(spread_level_mode)
        if spread_level_mode is not None
        else parse_spread_level_mode(settings)
    )
    if use_spread:
        geo_flags, geo_pts = spread_risk_flags(
            direction=direction,
            entry_spread=fill_spread if fill_spread is not None else e_s,
            spread_now=s_n,
            hold_hours=hold_h,
            levels=levels_for_trade(open_t, settings),
        )
        flags.extend(geo_flags)
        score += geo_pts
    else:
        if e_z is not None and abs(e_z) < 1.0:
            flags.append("Zвх<1")
            score += 2
        if z_n is not None and abs(z_n) < 1.0 and direction in ("LONG", "SHORT"):
            flags.append("|Z|<1")
            score += 1
    if overnight_rub > 50:
        flags.append(f"овн≈{overnight_rub:.0f}₽")
        score += 1
    level = risk_level_from_score(score)

    out["mark"] = {
        "z_now": z_n,
        "spread_now": s_n,
        "delta_z": (z_n - e_z) if (z_n is not None and e_z is not None) else None,
        "pnl_spread_pts": pnl_pts,
        "unrealized_pnl_rub": unrealized,
        "overnight_rub": overnight_rub,
        "overnight_days": ovn_days,
        "overnight_per_day_rub": overnight_per_day,
        "uncovered_rub": uncovered,
        "net_approx_rub": net_approx,
        "notional_rub": notional,
        "lots": lots,
        "hold_hours": hold_h,
        "risk_score": score,
        "risk_level": level,
        "risk_flags": flags,
        "risk_red": score >= 4,
        "spread_level_mode": use_spread,
        "entry_threshold": entry_threshold,
        "pnl_source": pnl_source,
        "fill_tatn": fill_tatn,
        "fill_tatnp": fill_tatnp,
        "fill_spread": fill_spread,
        "snap_tatn": snap_tatn,
        "snap_tatnp": snap_tatnp,
        "entry_spread_iss": iss_spread,
        "entry_slip_pts": entry_slip,
        "tatn_now": tn_now,
        "tatnp_now": tp_now,
    }
    return out
