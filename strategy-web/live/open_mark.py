"""Open-trade mark-to-market + simple risk flags for Trade desk."""

from __future__ import annotations

from datetime import datetime
from typing import Any


OVERNIGHT_FEE_PERCENT_PER_DAY = 0.033  # parity MoexConstants


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


def enrich_open_trade(
    open_t: dict[str, Any] | None,
    *,
    z_now: float | None,
    spread_now: float | None,
    trade_date: str | None,
    entry_threshold: float = 1.3,
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

    pnl_pts = None
    unrealized = None
    if e_s is not None and s_n is not None:
        if direction == "LONG":
            pnl_pts = s_n - e_s
        elif direction == "SHORT":
            pnl_pts = e_s - s_n
        if pnl_pts is not None and notional > 0:
            unrealized = notional * (pnl_pts / 100.0)

    ovn_days = overnight_days(open_t.get("entry_time"), trade_date)
    overnight_rub = notional * (OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0) * ovn_days if notional else 0.0
    # rough round-trip commission buffer already in sizing; show overnight only
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
    if e_z is not None and abs(e_z) < 1.0:
        flags.append("Zвх<1")
        score += 2
    if z_n is not None and abs(z_n) < 1.0 and direction in ("LONG", "SHORT"):
        flags.append("|Z|<1")
        score += 1
    if overnight_rub > 50:
        flags.append(f"овн≈{overnight_rub:.0f}₽")
        score += 1
    if score >= 6:
        level = "Critical"
    elif score >= 4:
        level = "High"
    elif score >= 3:
        level = "Elevated"
    else:
        level = "Ok"

    out["mark"] = {
        "z_now": z_n,
        "spread_now": s_n,
        "delta_z": (z_n - e_z) if (z_n is not None and e_z is not None) else None,
        "pnl_spread_pts": pnl_pts,
        "unrealized_pnl_rub": unrealized,
        "overnight_rub": overnight_rub,
        "overnight_days": ovn_days,
        "net_approx_rub": net_approx,
        "notional_rub": notional,
        "lots": lots,
        "hold_hours": hold_h,
        "risk_score": score,
        "risk_level": level,
        "risk_flags": flags,
        "risk_red": score >= 4,
        "entry_threshold": entry_threshold,
    }
    return out
