"""Post-open trade stats from M15 Z-sim (same direction / thresholds)."""

from __future__ import annotations

import statistics
import time
from typing import Any

from zsim import (
    Bar,
    Position,
    overnight_days,
    run_z_strategy_sim,
    spread_pnl_to_rub,
)

# горизонты как на «дыхании» спреда
HORIZONS_MIN = (
    ("15м", 15),
    ("1ч", 60),
    ("4ч", 240),
    ("1д", 1440),
    ("5д", 5 * 1440),
    ("14д", 14 * 1440),
)

SLIP_COMPARE = (0.0, 0.12, 0.15)

HOLD_BUCKETS = (
    ("<=4ч", 0.0, 4.0),
    ("4-24ч", 4.0, 24.0),
    ("1-3д", 24.0, 72.0),
    (">3д", 72.0, 1e9),
)

HIT_HOUR_BUCKETS = (
    ("<=15м", 0.0, 0.25),
    ("<=1ч", 0.25, 1.0),
    ("<=4ч", 1.0, 4.0),
    ("<=1д", 4.0, 24.0),
    (">1д", 24.0, 1e9),
)

_CACHE: dict[str, Any] = {"key": None, "ts": 0.0, "payload": None}
_CACHE_TTL_SEC = 180.0


def _parse_ts(ts: str):
    from datetime import datetime
    from zoneinfo import ZoneInfo

    msk = ZoneInfo("Europe/Moscow")
    s = str(ts).strip().replace("T", " ")
    for n, fmt in ((19, "%Y-%m-%d %H:%M:%S"), (16, "%Y-%m-%d %H:%M"), (10, "%Y-%m-%d")):
        try:
            return datetime.strptime(s[:n], fmt).replace(tzinfo=msk)
        except ValueError:
            continue
    return None


def _hold_hours_precise(entry_ts: str, exit_ts: str) -> float:
    """Удержание в часах (с минутами). zsim._hold_hours ломает intraday → 0."""
    e = _parse_ts(entry_ts)
    x = _parse_ts(exit_ts)
    if e is None or x is None:
        return 0.0
    return max(0.0, (x - e).total_seconds() / 3600.0)


def _median(xs: list[float]) -> float | None:
    if not xs:
        return None
    return float(statistics.median(xs))


def _mean(xs: list[float]) -> float | None:
    if not xs:
        return None
    return float(sum(xs) / len(xs))


def _p90(xs: list[float]) -> float | None:
    if not xs:
        return None
    if len(xs) == 1:
        return float(xs[0])
    s = sorted(xs)
    idx = int(round(0.9 * (len(s) - 1)))
    return float(s[idx])


def _dict_bars_to_zsim(bars: list[dict[str, Any]]) -> list[Bar]:
    out: list[Bar] = []
    for b in bars:
        ts = str(b.get("tradeDate") or b.get("timestamp") or "")
        if not ts:
            continue
        try:
            z = float(b.get("zScore") if b.get("zScore") is not None else b.get("z_score") or 0)
            sp = float(
                b.get("spreadPercent")
                if b.get("spreadPercent") is not None
                else b.get("spread_percent")
                or 0
            )
        except (TypeError, ValueError):
            continue
        tatn = b.get("tatnClose", b.get("tatn_close"))
        tatnp = b.get("tatnpClose", b.get("tatnp_close"))
        out.append(
            Bar(
                timestamp=ts[:19],
                z_score=z,
                spread_percent=sp,
                tatn_close=float(tatn) if tatn is not None else None,
                tatnp_close=float(tatnp) if tatnp is not None else None,
            )
        )
    return out


def _format_hold_hours(h: float | None) -> str:
    if h is None:
        return "—"
    if h < 1:
        return f"{h * 60:.0f} мин"
    if h < 48:
        return f"{h:.1f} ч"
    return f"{h / 24:.1f} д"


def _bar_index_by_ms(bars: list[Bar]) -> list[tuple[int, Bar]]:
    indexed: list[tuple[int, Bar]] = []
    for b in bars:
        dt = _parse_ts(b.timestamp)
        if dt is None:
            continue
        indexed.append((int(dt.timestamp() * 1000), b))
    return indexed


def _mtm_pts(direction: Position, entry_spread: float, bar_spread: float) -> float:
    if direction == Position.LONG:
        return float(bar_spread) - float(entry_spread)
    return float(entry_spread) - float(bar_spread)


def _spread_at_horizon(
    indexed: list[tuple[int, Bar]],
    entry_ms: int,
    horizon_min: int,
) -> float | None:
    target = entry_ms + horizon_min * 60_000
    best = None
    best_dist = None
    for ms, b in indexed:
        if ms < entry_ms:
            continue
        if ms > target + 20 * 60_000:
            break
        dist = abs(ms - target)
        if best_dist is None or dist < best_dist:
            best_dist = dist
            best = b.spread_percent
    return best


def _horizon_paths(
    indexed: list[tuple[int, Bar]],
    trades: list[Any],
    direction: Position,
) -> list[dict[str, Any]]:
    if not indexed:
        return []
    same = [t for t in trades if t.direction == direction]
    rows: list[dict[str, Any]] = []
    for label, mins in HORIZONS_MIN:
        abs_deltas: list[float] = []
        fav_deltas: list[float] = []
        for t in same:
            dt = _parse_ts(t.entry_time)
            if dt is None:
                continue
            entry_ms = int(dt.timestamp() * 1000)
            sp = _spread_at_horizon(indexed, entry_ms, mins)
            if sp is None:
                continue
            raw = sp - float(t.entry_spread)
            fav = raw if direction == Position.LONG else -raw
            abs_deltas.append(abs(raw))
            fav_deltas.append(fav)
        n = len(abs_deltas)
        rows.append(
            {
                "label": label,
                "minutes": mins,
                "n": n,
                "median_abs_pp": _median(abs_deltas),
                "p90_abs_pp": _p90(abs_deltas),
                "median_fav_pp": _median(fav_deltas),
                "pct_in_profit": (
                    100.0 * sum(1 for x in fav_deltas if x > 0) / n if n else None
                ),
            }
        )
    return rows


def _mtm_horizon_bars(
    horizon_rows: list[dict[str, Any]],
    *,
    notional: float,
    leverage: float,
) -> list[dict[str, Any]]:
    eff = max(0.0, notional) * max(1.0, leverage)
    out = []
    for h in horizon_rows:
        med = h.get("median_fav_pp")
        rub = None if med is None else eff * (float(med) / 100.0)
        out.append(
            {
                "label": h["label"],
                "median_fav_pp": med,
                "typical_pnl_rub": rub,
                "pct_in_profit": h.get("pct_in_profit"),
                "n": h.get("n"),
            }
        )
    return out


def _path_stats_for_trades(
    indexed: list[tuple[int, Bar]],
    trades: list[Any],
    direction: Position,
    *,
    notional_rub: float,
    leverage: float,
) -> dict[str, Any]:
    """MAE / hit+1%/+2% по пути entry→exit (gross MTM vs депозит)."""
    from live.overnight_fee import overnight_fee_per_day_rub

    eff = max(0.0, notional_rub) * max(1.0, leverage)
    # Как Test/sim: без цен ног ≈ номинал пары / 2 → ступень Премиум.
    overnight_per_day = overnight_fee_per_day_rub(eff / 2.0)
    # prebuild for binary search-ish scan
    mae_rubs: list[float] = []
    hit1_hours: list[float] = []
    hit2_hours: list[float] = []
    hit1_miss = 0
    hit2_miss = 0
    n_path = 0

    for t in trades:
        if t.direction != direction:
            continue
        e_dt = _parse_ts(t.entry_time)
        x_dt = _parse_ts(t.exit_time)
        if e_dt is None or x_dt is None:
            continue
        entry_ms = int(e_dt.timestamp() * 1000)
        exit_ms = int(x_dt.timestamp() * 1000)
        entry_sp = float(t.entry_spread)
        min_mtm = 0.0
        hit1_h = None
        hit2_h = None
        saw = False
        for ms, b in indexed:
            if ms < entry_ms:
                continue
            if ms > exit_ms:
                break
            saw = True
            pts = _mtm_pts(direction, entry_sp, b.spread_percent)
            ovn = overnight_per_day * overnight_days(t.entry_time, b.timestamp)
            # без exit-комиссии (как MTM в Тесте до закрытия)
            net = spread_pnl_to_rub(pts, eff) - ovn
            if net < min_mtm:
                min_mtm = net
            if notional_rub > 0:
                pct = (net / notional_rub) * 100.0
                h = (ms - entry_ms) / 3_600_000.0
                if hit1_h is None and pct >= 1.0:
                    hit1_h = h
                if hit2_h is None and pct >= 2.0:
                    hit2_h = h
            if hit1_h is not None and hit2_h is not None and min_mtm < -1e9:
                pass
        if not saw:
            continue
        n_path += 1
        mae_rubs.append(min_mtm)
        if hit1_h is None:
            hit1_miss += 1
        else:
            hit1_hours.append(hit1_h)
        if hit2_h is None:
            hit2_miss += 1
        else:
            hit2_hours.append(hit2_h)

    def _hit_dist(hours: list[float], miss: int, total: int) -> dict[str, Any]:
        buckets = []
        for label, lo, hi in HIT_HOUR_BUCKETS:
            c = sum(1 for h in hours if lo <= h < hi)
            buckets.append(
                {
                    "label": label,
                    "count": c,
                    "pct": (100.0 * c / total) if total else None,
                }
            )
        return {
            "n": total,
            "hit_count": len(hours),
            "miss_count": miss,
            "hit_rate_pct": (100.0 * len(hours) / total) if total else None,
            "median_hours": _median(hours),
            "median_label": _format_hold_hours(_median(hours)),
            "buckets": buckets,
        }

    return {
        "n": n_path,
        "median_mae_rub": _median(mae_rubs),
        "p10_mae_rub": (
            float(sorted(mae_rubs)[int(round(0.1 * (len(mae_rubs) - 1)))])
            if mae_rubs
            else None
        ),
        "mean_mae_rub": _mean(mae_rubs),
        "hit1": _hit_dist(hit1_hours, hit1_miss, n_path),
        "hit2": _hit_dist(hit2_hours, hit2_miss, n_path),
    }


def _overnight_share_by_hold(trades: list[Any], direction: Position) -> list[dict[str, Any]]:
    rows = []
    same = [t for t in trades if t.direction == direction]
    for label, lo, hi in HOLD_BUCKETS:
        shares: list[float] = []
        for t in same:
            h = _hold_hours_precise(t.entry_time, t.exit_time)
            if not (lo <= h < hi):
                continue
            ovn = float(t.overnight_rub or 0)
            comm = float(t.commission_rub or 0)
            costs = ovn + comm
            if costs <= 0:
                continue
            shares.append(100.0 * ovn / costs)
        rows.append(
            {
                "label": label,
                "n": len(shares),
                "median_overnight_share_pct": _median(shares),
                "mean_overnight_share_pct": _mean(shares),
            }
        )
    return rows


def _slip_sensitivity(
    zbars: list[Bar],
    *,
    direction: Position,
    entry_z: float,
    exit_z: float,
    notional_rub: float,
    leverage: float,
    base_slip: float,
    base_trades: list[Any],
) -> list[dict[str, Any]]:
    out = []

    def _row(slip: float, trades: list[Any]) -> dict[str, Any]:
        same = [t for t in trades if t.direction == direction]
        wins = [t for t in same if t.pnl_rub > 0]
        hold_wins = [_hold_hours_precise(t.entry_time, t.exit_time) for t in wins]
        pnl = [float(t.pnl_rub) for t in same]
        return {
            "slip": float(slip),
            "trade_count": len(same),
            "win_rate_pct": (100.0 * len(wins) / len(same)) if same else None,
            "avg_pnl_rub": _mean(pnl),
            "median_pnl_rub": _median(pnl),
            "avg_win_rub": _mean([float(t.pnl_rub) for t in wins]),
            "median_hold_hours_winners": _median(hold_wins),
            "median_hold_winners_label": _format_hold_hours(_median(hold_wins)),
        }

    for slip in SLIP_COMPARE:
        if abs(float(slip) - float(base_slip)) < 1e-9:
            out.append(_row(slip, base_trades))
            continue
        sim = run_z_strategy_sim(
            zbars,
            entry=float(entry_z),
            exit_z=float(exit_z),
            notional_rub=float(notional_rub),
            leverage=float(leverage),
            slippage_spread_pts=float(slip),
            compound_returns=False,
        )
        out.append(_row(slip, sim.trades))
    return out


def compute_open_trade_stats(
    *,
    direction: str,
    entry_z: float,
    exit_z: float,
    notional_rub: float,
    leverage: float,
    slippage_spread_pts: float = 0.12,
    bars: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """
    Статистика по закрытым сделкам Z-sim (то же направление):
    MTM/|Δ|, P(плюс), MAE, overnight-доля, slip-чувствительность, hit +1%/+2%.
    """
    d_raw = (direction or "").upper()
    pos = Position.SHORT if "SHORT" in d_raw else Position.LONG

    if bars is None:
        from replay.replay_db import load_bars_from_db

        bars = load_bars_from_db() or []

    zbars = _dict_bars_to_zsim(bars)
    last_ts = zbars[-1].timestamp if zbars else ""
    cache_key = (
        f"v2|{pos.value}|{entry_z:.3f}|{exit_z:.3f}|{notional_rub:.0f}|"
        f"{leverage:.2f}|{slippage_spread_pts:.3f}|{last_ts}|{len(zbars)}"
    )
    now = time.time()
    if (
        _CACHE["key"] == cache_key
        and _CACHE["payload"] is not None
        and now - float(_CACHE["ts"]) < _CACHE_TTL_SEC
    ):
        return _CACHE["payload"]

    if len(zbars) < 100:
        payload = {
            "ok": False,
            "error": "мало баров M15 для статистики",
            "direction": pos.value,
        }
        _CACHE.update(key=cache_key, ts=now, payload=payload)
        return payload

    sim = run_z_strategy_sim(
        zbars,
        entry=float(entry_z),
        exit_z=float(exit_z),
        notional_rub=float(notional_rub),
        leverage=float(leverage),
        slippage_spread_pts=float(slippage_spread_pts),
        compound_returns=False,
    )
    same = [t for t in sim.trades if t.direction == pos]
    wins = [t for t in same if t.pnl_rub > 0]
    losses = [t for t in same if t.pnl_rub <= 0]

    hold_all = [_hold_hours_precise(t.entry_time, t.exit_time) for t in same]
    hold_wins = [_hold_hours_precise(t.entry_time, t.exit_time) for t in wins]
    pnl_all = [float(t.pnl_rub) for t in same]
    pnl_wins = [float(t.pnl_rub) for t in wins]
    pnl_loss = [float(t.pnl_rub) for t in losses]

    indexed = _bar_index_by_ms(zbars)
    horizons = _horizon_paths(indexed, same, pos)
    mtm_bars = _mtm_horizon_bars(
        horizons, notional=float(notional_rub), leverage=float(leverage)
    )
    path = _path_stats_for_trades(
        indexed,
        same,
        pos,
        notional_rub=float(notional_rub),
        leverage=float(leverage),
    )

    med_hold_win = _median(hold_wins)

    # P(плюс) по горизонтам (отдельный график)
    p_profit = [
        {
            "label": h["label"],
            "pct_in_profit": h.get("pct_in_profit"),
            "n": h.get("n"),
        }
        for h in horizons
    ]

    payload: dict[str, Any] = {
        "ok": True,
        "source": "zsim_m15",
        "direction": pos.value,
        "params": {
            "entry_z": float(entry_z),
            "exit_z": float(exit_z),
            "notional_rub": float(notional_rub),
            "leverage": float(leverage),
            "slippage_spread_pts": float(slippage_spread_pts),
            "bars": len(zbars),
            "first_ts": zbars[0].timestamp if zbars else None,
            "last_ts": last_ts or None,
        },
        "summary": {
            "trade_count": len(same),
            "win_count": len(wins),
            "loss_count": len(losses),
            "win_rate_pct": (100.0 * len(wins) / len(same)) if same else None,
            "avg_pnl_rub": _mean(pnl_all),
            "avg_win_rub": _mean(pnl_wins),
            "avg_loss_rub": _mean(pnl_loss),
            "median_pnl_rub": _median(pnl_all),
            "median_hold_hours": _median(hold_all),
            "median_hold_hours_winners": med_hold_win,
            "mean_hold_hours_winners": _mean(hold_wins),
            "median_hold_winners_label": _format_hold_hours(med_hold_win),
            "mean_hold_winners_label": _format_hold_hours(_mean(hold_wins)),
            "median_mae_rub": path.get("median_mae_rub"),
            "p10_mae_rub": path.get("p10_mae_rub"),
        },
        "typical_mtm": mtm_bars,
        "spread_move": horizons,
        "p_profit": p_profit,
        "mae": {
            "median_rub": path.get("median_mae_rub"),
            "p10_rub": path.get("p10_mae_rub"),
            "mean_rub": path.get("mean_mae_rub"),
            "n": path.get("n"),
        },
        "overnight_share": _overnight_share_by_hold(same, pos),
        "slip_sensitivity": _slip_sensitivity(
            zbars,
            direction=pos,
            entry_z=float(entry_z),
            exit_z=float(exit_z),
            notional_rub=float(notional_rub),
            leverage=float(leverage),
            base_slip=float(slippage_spread_pts),
            base_trades=sim.trades,
        ),
        "hit1": path.get("hit1"),
        "hit2": path.get("hit2"),
        "hint": (
            "По истории M15 (симуляция с вашими порогами). "
            "Не прогноз текущей сделки — ориентир по прошлым входам того же направления."
        ),
    }
    _CACHE.update(key=cache_key, ts=now, payload=payload)
    return payload
