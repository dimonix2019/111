"""Closed-trade PnL / Min-Max / hit% — parity with Testing (replay-sim)."""

from __future__ import annotations

import json
from datetime import datetime
from typing import Any

from live.open_mark import overnight_days

COMMISSION_PCT_PER_SIDE = 0.04
OVERNIGHT_FEE_PERCENT_PER_DAY = 0.033


def _parse_ms(s: str | None) -> int | None:
    if not s:
        return None
    raw = str(s).strip().replace("T", " ")
    for n, fmt in ((19, "%Y-%m-%d %H:%M:%S"), (16, "%Y-%m-%d %H:%M"), (10, "%Y-%m-%d")):
        try:
            dt = datetime.strptime(raw[:n], fmt)
            return int(dt.timestamp() * 1000)
        except ValueError:
            continue
    return None


def _pnl_constants(
    *,
    execution_notional_rub: float | None,
    deposit_rub: float,
    leverage: float,
) -> dict[str, float]:
    lev = max(1.0, float(leverage or 7))
    deposit = max(1.0, float(deposit_rub or 10_000))
    if execution_notional_rub is not None and float(execution_notional_rub) > 0:
        eff = float(execution_notional_rub)
        # recover deposit for overnight (deposit × (lev−1) × fee)
        deposit = eff / lev
    else:
        eff = deposit * lev
    comm = eff * (COMMISSION_PCT_PER_SIDE / 100.0)
    ovn_day = deposit * max(0.0, lev - 1.0) * (OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)
    return {
        "eff_notional": eff,
        "deposit": deposit,
        "leverage": lev,
        "comm_per_side": comm,
        "overnight_per_day": ovn_day,
    }


def _mtm_net(
    *,
    direction: str,
    entry_spread: float,
    spread_now: float,
    entry_time: str,
    bar_time: str,
    constants: dict[str, float],
    include_exit_comm: bool,
) -> float:
    d = (direction or "").upper()
    if d.startswith("L"):
        pnl_pts = spread_now - entry_spread
    else:
        pnl_pts = entry_spread - spread_now
    gross = constants["eff_notional"] * (pnl_pts / 100.0)
    ovn = constants["overnight_per_day"] * overnight_days(entry_time, bar_time)
    sides = 2 if include_exit_comm else 1
    return gross - constants["comm_per_side"] * sides - ovn


def compute_closed_breakdown(
    trade: dict[str, Any],
    *,
    deposit_rub: float = 10_000,
    leverage: float = 7,
) -> dict[str, Any]:
    """Gross / commission / overnight / net from entry/exit spreads."""
    direction = str(trade.get("direction") or "LONG")
    entry_spread = trade.get("entry_spread")
    exit_spread = trade.get("exit_spread")
    entry_time = str(trade.get("entry_time") or "")
    exit_time = str(trade.get("exit_time") or "")
    notional = trade.get("execution_notional_rub")
    if notional is None:
        notional = trade.get("notional_rub")

    out: dict[str, Any] = {
        "pnl_pts": None,
        "gross_rub": None,
        "commission_rub": None,
        "overnight_rub": None,
        "pnl_rub": None,
        "execution_notional_rub": None,
    }
    if entry_spread is None or exit_spread is None:
        return out
    try:
        e_s = float(entry_spread)
        x_s = float(exit_spread)
    except (TypeError, ValueError):
        return out

    constants = _pnl_constants(
        execution_notional_rub=float(notional) if notional is not None else None,
        deposit_rub=deposit_rub,
        leverage=leverage,
    )
    d = direction.upper()
    if d.startswith("L"):
        pnl_pts = x_s - e_s
    else:
        pnl_pts = e_s - x_s
    gross = constants["eff_notional"] * (pnl_pts / 100.0)
    ovn = constants["overnight_per_day"] * overnight_days(entry_time, exit_time)
    comm = constants["comm_per_side"] * 2
    out.update(
        {
            "pnl_pts": pnl_pts,
            "gross_rub": gross,
            "commission_rub": comm,
            "overnight_rub": ovn,
            "pnl_rub": gross - comm - ovn,
            "execution_notional_rub": constants["eff_notional"],
            "_constants": constants,
            "_entry_spread": e_s,
        }
    )
    return out


def compute_path_metrics(
    trade: dict[str, Any],
    bars: list[dict[str, Any]],
    *,
    deposit_rub: float = 10_000,
    leverage: float = 7,
) -> dict[str, Any]:
    """Min/Max MTM and first hit 1%/2%/3% along M15 path (Testing parity)."""
    breakdown = compute_closed_breakdown(
        trade, deposit_rub=deposit_rub, leverage=leverage
    )
    constants = breakdown.pop("_constants", None)
    entry_spread = breakdown.pop("_entry_spread", None)
    out = {
        **{k: v for k, v in breakdown.items() if not str(k).startswith("_")},
        "pnl_min_rub": None,
        "pnl_max_rub": None,
        "hit1_time": None,
        "hit2_time": None,
        "hit3_time": None,
    }
    if not constants or entry_spread is None or not bars:
        return out

    direction = str(trade.get("direction") or "LONG")
    entry_time = str(trade.get("entry_time") or "")
    exit_time = str(trade.get("exit_time") or "")
    entry_ms = _parse_ms(entry_time)
    exit_ms = _parse_ms(exit_time)
    if entry_ms is None or exit_ms is None:
        return out

    deposit = max(1.0, constants["deposit"])
    mn = float("inf")
    mx = float("-inf")
    found = False
    hit1 = hit2 = hit3 = False

    for b in bars:
        ms = b.get("timestampMs")
        if ms is None:
            ms = _parse_ms(str(b.get("tradeDate") or ""))
        if ms is None or ms < entry_ms or ms > exit_ms:
            continue
        sp = b.get("spreadPercent")
        if sp is None:
            sp = b.get("spread")
        if sp is None:
            continue
        include_exit = ms == exit_ms
        net = _mtm_net(
            direction=direction,
            entry_spread=entry_spread,
            spread_now=float(sp),
            entry_time=entry_time,
            bar_time=str(b.get("tradeDate") or exit_time),
            constants=constants,
            include_exit_comm=include_exit,
        )
        found = True
        if net < mn:
            mn = net
        if net > mx:
            mx = net
        pct = (net / deposit) * 100.0
        td = str(b.get("tradeDate") or "")
        if not hit1 and pct >= 1.0:
            hit1 = True
            out["hit1_time"] = td
        if not hit2 and pct >= 2.0:
            hit2 = True
            out["hit2_time"] = td
        if not hit3 and pct >= 3.0:
            hit3 = True
            out["hit3_time"] = td

    if found:
        out["pnl_min_rub"] = mn
        out["pnl_max_rub"] = mx
    return out


def account_after_from_legs(legs: Any) -> float | None:
    """Последний portfolio_total / cash из ног выхода."""
    if isinstance(legs, str):
        try:
            legs = json.loads(legs)
        except (TypeError, json.JSONDecodeError):
            return None
    if not isinstance(legs, list):
        return None
    for leg in reversed(legs):
        if not isinstance(leg, dict):
            continue
        for key in ("portfolio_total_rub", "portfolio_cash_rub"):
            v = leg.get(key)
            if v is None:
                continue
            try:
                return float(v)
            except (TypeError, ValueError):
                continue
    return None


def enrich_closed_trade(
    trade: dict[str, Any],
    *,
    deposit_rub: float = 10_000,
    leverage: float = 7,
    bars: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Attach computed metrics; keep stored values when already present."""
    out = dict(trade)
    if bars is not None:
        metrics = compute_path_metrics(
            trade, bars, deposit_rub=deposit_rub, leverage=leverage
        )
    else:
        metrics = compute_closed_breakdown(
            trade, deposit_rub=deposit_rub, leverage=leverage
        )
        metrics.pop("_constants", None)
        metrics.pop("_entry_spread", None)
        metrics.setdefault("pnl_min_rub", trade.get("pnl_min_rub"))
        metrics.setdefault("pnl_max_rub", trade.get("pnl_max_rub"))
        metrics.setdefault("hit1_time", trade.get("hit1_time"))
        metrics.setdefault("hit2_time", trade.get("hit2_time"))
        metrics.setdefault("hit3_time", trade.get("hit3_time"))

    for key, val in metrics.items():
        if str(key).startswith("_"):
            continue
        cur = out.get(key)
        if cur is None and val is not None:
            out[key] = val

    # Slip: backfill from ISS near entry when missing
    if out.get("entry_slip_pts") is None:
        try:
            from live.open_mark import resolve_entry_slip_pts

            slip, iss = resolve_entry_slip_pts(out, bars=bars)
            if slip is not None:
                out["entry_slip_pts"] = slip
            if out.get("entry_spread_iss") is None and iss is not None:
                out["entry_spread_iss"] = iss
        except Exception:
            pass
    if out.get("account_after_rub") is None:
        from_legs = account_after_from_legs(out.get("legs") or out.get("legs_json"))
        if from_legs is not None:
            out["account_after_rub"] = from_legs
    return out


def load_bars_for_window(entry_time: str | None, exit_time: str | None) -> list[dict[str, Any]]:
    """M15 bars covering the trade hold (SQLite cache)."""
    try:
        from pathlib import Path

        from replay.replay_db import ensure_replay_bars
    except Exception:
        return []

    start = None
    if entry_time:
        start = str(entry_time).strip()[:10]
    data_dir = Path(__file__).resolve().parent.parent / "data"
    csv_path = data_dir / "m15_tatn_255d.csv"
    try:
        payload = ensure_replay_bars(
            csv_path, "m15_tatn_255d.csv", online=False, start_date=start
        )
        bars = payload.get("bars") or []
    except Exception:
        return []

    entry_ms = _parse_ms(entry_time)
    exit_ms = _parse_ms(exit_time)
    if entry_ms is None or exit_ms is None:
        return bars
    # pad: prev 15м бар для slip + запас на :00 vs :00:00
    lo, hi = entry_ms - 3 * 60 * 60 * 1000, exit_ms + 60_000
    return [
        b
        for b in bars
        if (b.get("timestampMs") is not None and lo <= int(b["timestampMs"]) <= hi)
    ]
