"""Closed-trade PnL / Min-Max / hit% — parity with Testing (replay-sim)."""

from __future__ import annotations

import json
from datetime import datetime
from typing import Any

from live.open_mark import overnight_days
from live.overnight_fee import overnight_fee_per_day_rub, short_leg_uncovered_rub

COMMISSION_PCT_PER_SIDE = 0.04
# Совместимость импортов; расчёт overnight — ступени Премиум (см. overnight_fee).
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


def _format_bar_ts(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000.0).strftime("%Y-%m-%d %H:%M")


def coerce_exit_after_entry(entry_time: str | None, exit_time: str | None) -> str:
    """Exit bar must not precede entry (BROKER adopt vs signal-bar close)."""
    et = str(exit_time or "").strip()
    ent = str(entry_time or "").strip()
    if not et:
        return ent or et
    if not ent:
        return et
    e_ms = _parse_ms(ent)
    x_ms = _parse_ms(et)
    if e_ms is not None and x_ms is not None and x_ms < e_ms:
        return ent[:19] if len(ent) >= 16 else ent
    return et


def broker_adopt_entry_time(market: dict[str, Any] | None) -> str:
    """Conservative entry timestamp for BROKER adopt (no future M15/tip label)."""
    from zoneinfo import ZoneInfo

    MSK = ZoneInfo("Europe/Moscow")
    now = datetime.now(MSK).replace(second=0, microsecond=0)
    now_ms = int(now.timestamp() * 1000)
    candidates: list[str] = []
    if market:
        for key in ("tip_trade_date", "trade_date"):
            v = market.get(key)
            if v:
                candidates.append(str(v))
        bar = market.get("bar")
        if isinstance(bar, dict) and bar.get("tradeDate"):
            candidates.append(str(bar["tradeDate"]))
    best_ms: int | None = None
    best_s = ""
    for c in candidates:
        ms = _parse_ms(c)
        if ms is None:
            continue
        if ms > now_ms + 60_000:
            ms = now_ms
            c = _format_bar_ts(ms)
        if best_ms is None or ms < best_ms:
            best_ms, best_s = ms, c
    if best_s:
        return best_s[:16] if len(best_s) >= 16 else best_s
    return now.strftime("%Y-%m-%d %H:%M")


def exit_time_from_broker_legs(legs: list[dict[str, Any]] | None) -> str | None:
    """Last broker fill serverTime (UTC) → MSK «YYYY-MM-DD HH:MM»."""
    if not legs:
        return None
    from zoneinfo import ZoneInfo

    MSK = ZoneInfo("Europe/Moscow")
    best: datetime | None = None
    for leg in legs:
        if not isinstance(leg, dict):
            continue
        order = leg.get("order") or {}
        meta = order.get("responseMetadata") or {}
        st = meta.get("serverTime")
        if not st:
            continue
        try:
            dt = datetime.fromisoformat(str(st).replace("Z", "+00:00"))
            if dt.tzinfo is None:
                from datetime import timezone

                dt = dt.replace(tzinfo=timezone.utc)
            local = dt.astimezone(MSK).replace(second=0, microsecond=0)
            if best is None or local > best:
                best = local
        except (ValueError, TypeError):
            continue
    if best is None:
        return None
    return best.strftime("%Y-%m-%d %H:%M")


def _pnl_constants(
    *,
    execution_notional_rub: float | None,
    deposit_rub: float,
    leverage: float,
    direction: str | None = None,
    lots: int | float | None = None,
    fill_tatn: float | None = None,
    fill_tatnp: float | None = None,
) -> dict[str, float]:
    lev = max(1.0, float(leverage or 7))
    deposit = max(1.0, float(deposit_rub or 10_000))
    if execution_notional_rub is not None and float(execution_notional_rub) > 0:
        eff = float(execution_notional_rub)
        deposit = eff / lev
    else:
        eff = deposit * lev
    comm = eff * (COMMISSION_PCT_PER_SIDE / 100.0)
    # T‑Invest Премиум: ступени на короткую ногу (не deposit×(L−1)×0.033%).
    uncovered = short_leg_uncovered_rub(
        direction=direction,
        lots=lots,
        fill_tatn=fill_tatn,
        fill_tatnp=fill_tatnp,
        notional_rub=eff,
    )
    ovn_day = overnight_fee_per_day_rub(uncovered)
    return {
        "eff_notional": eff,
        "deposit": deposit,
        "leverage": lev,
        "comm_per_side": comm,
        "overnight_per_day": ovn_day,
        "uncovered_rub": uncovered,
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

    def _fopt(key: str) -> float | None:
        v = trade.get(key)
        if v is None:
            return None
        try:
            return float(v)
        except (TypeError, ValueError):
            return None

    lots_raw = trade.get("quantity_lots")
    try:
        lots_v: int | float | None = float(lots_raw) if lots_raw is not None else None
    except (TypeError, ValueError):
        lots_v = None

    constants = _pnl_constants(
        execution_notional_rub=float(notional) if notional is not None else None,
        deposit_rub=deposit_rub,
        leverage=leverage,
        direction=direction,
        lots=lots_v,
        fill_tatn=_fopt("entry_tatn") or _fopt("fill_tatn"),
        fill_tatnp=_fopt("entry_tatnp") or _fopt("fill_tatnp"),
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
        "pnl_min_time": None,
        "pnl_max_time": None,
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
    mn_td: str | None = None
    mx_td: str | None = None
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
        td = str(b.get("tradeDate") or "")
        net = _mtm_net(
            direction=direction,
            entry_spread=entry_spread,
            spread_now=float(sp),
            entry_time=entry_time,
            bar_time=td or exit_time,
            constants=constants,
            include_exit_comm=include_exit,
        )
        found = True
        if net < mn:
            mn = net
            mn_td = td or None
        if net > mx:
            mx = net
            mx_td = td or None
        pct = (net / deposit) * 100.0
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
        out["pnl_min_time"] = mn_td
        out["pnl_max_time"] = mx_td
    return out


def _normalize_path_bars(bars: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Desk tip1m/dealer bars use time/spread; closed path expects tradeDate/spreadPercent."""
    out: list[dict[str, Any]] = []
    for b in bars or []:
        if not isinstance(b, dict):
            continue
        sp = b.get("spreadPercent")
        if sp is None:
            sp = b.get("spread")
        if sp is None:
            continue
        td = b.get("tradeDate") or b.get("time") or ""
        ms = b.get("timestampMs")
        if ms is None:
            ms = _parse_ms(str(td))
        if ms is None:
            continue
        try:
            sp_f = float(sp)
        except (TypeError, ValueError):
            continue
        out.append(
            {
                "timestampMs": int(ms),
                "tradeDate": str(td)[:19] if td else _format_bar_ts(int(ms)),
                "spreadPercent": sp_f,
            }
        )
    return out


def compute_open_path_minmax(
    open_trade: dict[str, Any],
    bars: list[dict[str, Any]],
    *,
    deposit_rub: float = 10_000,
    leverage: float = 7,
    asof_time: str | None = None,
) -> dict[str, float | str | None]:
    """Path Min/Max MTM for an open trade (entry→now, no exit commission)."""
    empty: dict[str, float | str | None] = {
        "pnl_min_rub": None,
        "pnl_max_rub": None,
        "pnl_min_time": None,
        "pnl_max_time": None,
    }
    if not open_trade:
        return empty
    entry_spread = open_trade.get("entry_spread")
    mark = open_trade.get("mark") if isinstance(open_trade.get("mark"), dict) else {}
    if entry_spread is None and mark:
        entry_spread = mark.get("fill_spread")
    if entry_spread is None:
        return empty
    asof = str(
        asof_time
        or (mark.get("asof") if mark else None)
        or open_trade.get("entry_time")
        or ""
    )
    if not asof:
        return empty
    # Prefer last bar time if asof is coarse / missing seconds.
    norm = _normalize_path_bars(bars)
    if not norm:
        return empty
    last_td = str(norm[-1].get("tradeDate") or "")
    if last_td and (_parse_ms(last_td) or 0) >= (_parse_ms(asof) or 0):
        asof = last_td
    trade = {
        "direction": open_trade.get("direction") or "LONG",
        "entry_spread": entry_spread,
        "exit_time": asof,
        "exit_spread": float(norm[-1]["spreadPercent"]),
        "entry_time": open_trade.get("entry_time"),
        "execution_notional_rub": (
            open_trade.get("execution_notional_rub")
            or (mark.get("notional_rub") if mark else None)
            or open_trade.get("notional_rub")
        ),
        "quantity_lots": open_trade.get("quantity_lots")
        or (mark.get("lots") if mark else None),
        "entry_tatn": (
            open_trade.get("entry_tatn")
            or (mark.get("fill_tatn") if mark else None)
            or (mark.get("snap_tatn") if mark else None)
        ),
        "entry_tatnp": (
            open_trade.get("entry_tatnp")
            or (mark.get("fill_tatnp") if mark else None)
            or (mark.get("snap_tatnp") if mark else None)
        ),
    }
    # Prefer fill_spread for path when mark has broker fills (parity with trade.js).
    fill_sp = mark.get("fill_spread") if mark else None
    if fill_sp is not None:
        try:
            trade["entry_spread"] = float(fill_sp)
        except (TypeError, ValueError):
            pass
    metrics = compute_path_metrics(
        trade, norm, deposit_rub=deposit_rub, leverage=leverage
    )
    return {
        "pnl_min_rub": metrics.get("pnl_min_rub"),
        "pnl_max_rub": metrics.get("pnl_max_rub"),
        "pnl_min_time": metrics.get("pnl_min_time"),
        "pnl_max_time": metrics.get("pnl_max_time"),
    }


def account_after_from_legs(legs: Any) -> float | None:
    """Последний portfolio_total (иначе cash) из ног — после полного fill."""
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


def _fnum(v: Any) -> float | None:
    if v is None:
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def model_net_from_trade(t: dict[str, Any]) -> float | None:
    """Оценка по спреду: gross − ком. − overnight (если поля есть)."""
    g = _fnum(t.get("gross_rub"))
    c = _fnum(t.get("commission_rub"))
    o = _fnum(t.get("overnight_rub")) or 0.0
    if g is None or c is None:
        return None
    return g - c - o


def attach_account_deltas(trades: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """
    Для Prod-истории: «Чист.» = Δ денег на счёте (комиссии уже внутри),
    а не оценка по спреду.

    Приоритет delta:
      1) явный account_delta_rub
      2) account_after − account_before (снимок этой сделки: после входа → после выхода)
      3) legacy: account_after − предыдущий account_after (ломается при паузе/пополнении)

    Явно заданный account_delta_rub не перетираем.
    """
    if not trades:
        return trades

    def _sort_key(t: dict[str, Any]) -> tuple[int, int, int]:
        exit_ms = _parse_ms(str(t.get("exit_time") or "")) or 0
        entry_ms = _parse_ms(str(t.get("entry_time") or "")) or 0
        try:
            tid = int(t.get("id") or 0)
        except (TypeError, ValueError):
            tid = 0
        return (exit_ms, entry_ms, tid)

    ordered = sorted(trades, key=_sort_key)
    prev_after: float | None = None
    for t in ordered:
        after = _fnum(t.get("account_after_rub"))
        before = _fnum(t.get("account_before_rub"))
        existing = _fnum(t.get("account_delta_rub"))
        # «До»: явный снимок → after−delta → предыдущий «После» (legacy-цепь)
        if before is None and after is not None and existing is not None:
            before = after - existing
            t["account_before_rub"] = before
        if existing is not None:
            delta = existing
        elif after is not None and before is not None:
            delta = after - before
        elif after is not None and prev_after is not None:
            before = prev_after
            t["account_before_rub"] = before
            delta = after - before
        else:
            delta = None

        # Модель по спреду (вал−ком−овн); не путать с Δ кошелька
        model = model_net_from_trade(t)
        if model is None:
            model = _fnum(t.get("spread_pnl_rub"))
        if model is None:
            raw_pnl = _fnum(t.get("pnl_rub"))
            if raw_pnl is not None and (delta is None or abs(raw_pnl - delta) > 0.05):
                model = raw_pnl
        if model is not None:
            t["spread_pnl_rub"] = model

        if delta is not None:
            t["account_delta_rub"] = delta
            # Чист. / win / summary — по деньгам на счёте
            t["pnl_rub"] = delta
        if after is not None:
            prev_after = after
    return trades


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
