"""Тест: пол–потолок по каузальной широкой полке. Добавка к симу Теста.

Вход касанием 1м (как AUTO: переход prev→cur за 60с): потолок → Short, пол → Long.
Выход что раньше: противоположная кромка текущей as_of полки, ТП % депозита,
«нет хода» N календарных дней (как прод: MTM<0 и спред не ушёл к выходу ≥0,2 п.п.),
вынос (фаза не formed) — закрыть, не держать в выносе.

В одной позиции с ногой AUTO не держится: если база открыта в момент
сигнала — сначала выход базы, затем вход полки с кошелька базы.
Добор и экстра свои кошельки не отдаёт.
"""
from __future__ import annotations

import math
from typing import Any

import numpy as np

from replay.tip_touch import (
    COMM_PCT,
    DEFAULT_NOTIONAL,
    DEFAULT_SLIP,
    LEVERAGE,
    OVERNIGHT_FEE_MODEL,
    PreparedTips,
    _edges_from,
    _edges_tip1m_settled,
    _fees_for_sized_open,
    _lot_trade_fields,
    _prep_leg_prices,
    size_test_spread_lots,
)

SHELF_FF_TP_PCT = 2.0
SHELF_FF_HOLD_DAYS = 5.0
SHELF_FF_TREND_MIN_PP = 0.2

# Кэш дневных крайностей / каузальной полки на объект PreparedTips.
_PREP_DAILY_CACHE: dict[int, tuple[int, list, list, list]] = {}
_PREP_CAUSAL_CACHE: dict[int, tuple[int, dict[str, Any]]] = {}
_PREP_CACHE_MAX = 6


def clear_prep_shelf_caches() -> None:
    _PREP_DAILY_CACHE.clear()
    _PREP_CAUSAL_CACHE.clear()


def _cache_put_prep(cache: dict, key: int, value: tuple) -> None:
    cache[key] = value
    while len(cache) > _PREP_CACHE_MAX:
        cache.pop(next(iter(cache)), None)


def parse_shelf_floor_ceiling_mode(raw: Any) -> bool:
    """Тест «пол–потолок (полка)». По умолчанию выкл."""
    if raw is None:
        return False
    if isinstance(raw, str):
        return raw.strip().lower() in (
            "1",
            "true",
            "yes",
            "on",
            "shelf",
            "ff",
            "floor_ceiling",
            "floor-ceiling",
            "shelf_ff",
            "пол-потолок",
            "пол–потолок",
            "полка",
        )
    return bool(raw)


def daily_extremes_from_prep(prep: PreparedTips) -> tuple[list, list, list]:
    """Дневные min/med/max с 1м ряда — тот же компакт, что график широкой полки.

    Считается один раз на ряд (не обход всех минуток на каждый бар / запрос).
    """
    from live.spread_corridor import daily_spread_extremes_from_bars

    n = int(getattr(prep, "n", 0) or 0)
    hid = id(prep)
    hit = _PREP_DAILY_CACHE.get(hid)
    if hit and hit[0] == n:
        return hit[1], hit[2], hit[3]

    dates = prep.trade_dates
    spreads = np.asarray(prep.spread, dtype=np.float64)
    compact: list[dict[str, Any]] = []
    if n > 0:
        day_ord = np.asarray(prep.day_ord, dtype=np.int32)
        change = np.empty(n, dtype=bool)
        change[0] = True
        if n > 1:
            change[1:] = day_ord[1:] != day_ord[:-1]
        starts = np.flatnonzero(change)
        n_starts = int(len(starts))
        for k, s0 in enumerate(starts):
            s = int(s0)
            e = int(starts[k + 1]) if k + 1 < n_starts else n
            d = str(dates[s])[:10]
            if len(d) < 10:
                continue
            xs = spreads[s:e]
            finite = xs[np.isfinite(xs)]
            if finite.size == 0:
                continue
            compact.append({"time": f"{d} 10:00", "spread": float(np.min(finite))})
            compact.append({"time": f"{d} 12:00", "spread": float(finite[len(finite) // 2])})
            compact.append({"time": f"{d} 16:00", "spread": float(np.max(finite))})
    if not compact:
        empty: tuple[list, list, list] = ([], [], [])
        _cache_put_prep(_PREP_DAILY_CACHE, hid, (n, empty[0], empty[1], empty[2]))
        return empty
    daily, mins, maxs = daily_spread_extremes_from_bars(compact)
    _cache_put_prep(_PREP_DAILY_CACHE, hid, (n, daily, mins, maxs))
    return daily, mins, maxs


def causal_pack_from_prep(
    prep: PreparedTips,
    *,
    deadline_mono: float | None = None,
) -> dict[str, Any]:
    """Полный каузальный пакет полки по дневному кэшу. Не с 1м с нуля."""
    from live.spread_corridor_wide import causal_wide_pack_from_extremes

    n = int(getattr(prep, "n", 0) or 0)
    hid = id(prep)
    hit = _PREP_CAUSAL_CACHE.get(hid)
    if hit and hit[0] == n:
        return hit[1]
    daily, mins, maxs = daily_extremes_from_prep(prep)
    if not daily:
        empty = {"kind": "wide", "causal": True, "daily": [], "by_date": {}}
        _cache_put_prep(_PREP_CAUSAL_CACHE, hid, (n, empty))
        return empty
    pack = causal_wide_pack_from_extremes(
        daily, touch_mins=mins, touch_maxs=maxs, deadline_mono=deadline_mono
    )
    if not pack.get("partial"):
        _cache_put_prep(_PREP_CAUSAL_CACHE, hid, (n, pack))
    return pack


def causal_by_date_from_prep(prep: PreparedTips) -> dict[str, dict[str, Any]]:
    pack = causal_pack_from_prep(prep)
    return dict(pack.get("by_date") or {})


def _day_key(dates: list[str], i: int) -> str:
    return str(dates[i])[:10]


def _finite_pair(lo: Any, hi: Any) -> tuple[float, float] | None:
    try:
        a = float(lo)
        b = float(hi)
    except (TypeError, ValueError):
        return None
    if not (math.isfinite(a) and math.isfinite(b)):
        return None
    if b <= a:
        return None
    return a, b


def _shelf_formed(entry: dict[str, Any] | None) -> tuple[bool, float | None, float | None]:
    if not entry:
        return False, None, None
    if str(entry.get("phase") or "") != "formed":
        pair = _finite_pair(entry.get("lo"), entry.get("hi"))
        return False, (pair[0] if pair else None), (pair[1] if pair else None)
    pair = _finite_pair(entry.get("lo"), entry.get("hi"))
    if pair is None:
        return False, None, None
    return True, pair[0], pair[1]


def gap_open_edge_indices(
    prep: PreparedTips,
    *,
    window_start_ms: int = 0,
    window_end_ms: int = 0,
) -> np.ndarray:
    """Первый бар после разрыва >60с или начала сессии (не в edge_i)."""
    n = int(getattr(prep, "n", 0) or 0)
    if n < 2:
        return np.empty(0, dtype=np.int32)
    ts = np.asarray(prep.ts_ms, dtype=np.int64)
    session = np.asarray(prep.session, dtype=np.bool_)
    sp = np.asarray(prep.spread, dtype=np.float64)
    dt = ts[1:] - ts[:-1]
    ok = (dt > 60_000) | (session[1:] & ~session[:-1])
    ok &= np.isfinite(sp[1:]) & np.isfinite(sp[:-1])
    idx = (np.flatnonzero(ok) + 1).astype(np.int32)
    if len(idx) == 0:
        return idx
    if window_start_ms <= 0 and window_end_ms <= 0:
        return idx
    ts_i = ts[idx]
    lo = 0
    if window_start_ms > 0:
        lo = int(np.searchsorted(ts_i, window_start_ms, side="left"))
    hi = len(idx)
    if window_end_ms > 0:
        hi = int(np.searchsorted(ts_i, window_end_ms, side="right"))
    if lo <= 0 and hi >= len(idx):
        return idx
    return idx[lo:hi]


def extend_shelf_bar_indices(
    prep: PreparedTips,
    edges: np.ndarray,
    *,
    window_start_ms: int = 0,
    window_end_ms: int = 0,
    now_ms: int | None = None,
    settle_sec: float | None = None,
) -> np.ndarray:
    """edge_i + gap-open бары (для пол–потолок и выходов после разрыва)."""
    gap = gap_open_edge_indices(
        prep, window_start_ms=window_start_ms, window_end_ms=window_end_ms
    )
    if len(gap):
        gap = _edges_tip1m_settled(
            prep, gap, now_ms=now_ms, settle_sec=settle_sec
        )
    if len(gap) == 0:
        return edges
    if edges is None or len(edges) == 0:
        return gap
    merged = np.unique(np.concatenate([edges, gap]))
    return merged.astype(np.int32)


def _toward_exit(
    *,
    is_long: bool,
    entry_sp: float,
    cur_sp: float,
    lo: float | None,
    hi: float | None,
    min_pp: float,
) -> bool:
    if is_long:
        if (cur_sp - entry_sp) >= min_pp:
            return True
        if hi is not None and math.isfinite(hi):
            return (hi - cur_sp) <= (hi - entry_sp) - min_pp
        return False
    if (entry_sp - cur_sp) >= min_pp:
        return True
    if lo is not None and math.isfinite(lo):
        return (cur_sp - lo) <= (entry_sp - lo) - min_pp
    return False


def run_shelf_floor_ceiling(
    prep: PreparedTips,
    *,
    slip: float = DEFAULT_SLIP,
    notional: float = DEFAULT_NOTIONAL,
    compound: bool = False,
    window_start_ms: int = 0,
    window_end_ms: int = 0,
    take_profit_pct: float = SHELF_FF_TP_PCT,
    max_hold_days_no_exit_trend: float = SHELF_FF_HOLD_DAYS,
    force_close_3d: bool = False,
    exit_trend_min_pp: float = SHELF_FF_TREND_MIN_PP,
    now_ms: int | None = None,
    settle_sec: float | None = None,
    by_date: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Одна нога: касание пола/потолка каузальной широкой полки."""
    from live.constants import MONITOR_TIP1M_SETTLE_SEC

    settle = MONITOR_TIP1M_SETTLE_SEC if settle_sec is None else float(settle_sec)
    tp_use = max(0.0, float(take_profit_pct or 0.0))
    hold_days = int(max_hold_days_no_exit_trend) if max_hold_days_no_exit_trend > 0 else 0
    use_close_3d = bool(force_close_3d)
    trend_min_pp = max(0.0, float(exit_trend_min_pp))
    causal = by_date if by_date is not None else causal_by_date_from_prep(prep)
    edges = extend_shelf_bar_indices(
        prep,
        _edges_tip1m_settled(
            prep,
            _edges_from(prep, window_start_ms, window_end_ms),
            now_ms=now_ms,
            settle_sec=settle,
        ),
        window_start_ms=window_start_ms,
        window_end_ms=window_end_ms,
        now_ms=now_ms,
        settle_sec=settle,
    )
    sp_arr = prep.spread
    z_arr = prep.z
    dates = prep.trade_dates
    day_ord = prep.day_ord
    base = float(notional)
    closed_trades: list[dict[str, Any]] = []
    peak = max_dd = total_pnl = realized = 0.0
    trade_no = 0

    pos = 0  # 0 flat, 1 long, 2 short
    entry_sp = 0.0
    entry_td = ""
    entry_z = 0.0
    entry_day = 0
    entry_comm = 0.0
    entry_slip_used = 0.0
    eff = comm = ovn_day = 0.0
    pos_notional = base
    pnl_min = float("inf")
    pnl_max = float("-inf")
    hit1_td = hit2_td = hit3_td = None
    open_size: dict[str, Any] | None = None

    def _mtm_at(i: int) -> float:
        sp = float(sp_arr[i])
        is_long = pos == 1
        pnl_pts = (sp - entry_sp) if is_long else (entry_sp - sp)
        gross = eff * (pnl_pts / 100.0)
        ov = ovn_day * max(0, int(day_ord[i]) - entry_day)
        return gross - entry_comm - ov

    def _track(i: int) -> float:
        nonlocal pnl_min, pnl_max, hit1_td, hit2_td, hit3_td
        mtm = _mtm_at(i)
        if mtm < pnl_min:
            pnl_min = mtm
        if mtm > pnl_max:
            pnl_max = mtm
        pct = (mtm / max(1.0, pos_notional)) * 100.0
        td = dates[i]
        if hit1_td is None and pct >= 1.0:
            hit1_td = td
        if hit2_td is None and pct >= 2.0:
            hit2_td = td
        if hit3_td is None and pct >= 3.0:
            hit3_td = td
        return mtm

    def _open(sig: int, i: int) -> None:
        nonlocal pos, entry_sp, entry_td, entry_z, entry_day, entry_comm
        nonlocal eff, comm, ovn_day, pos_notional, trade_no, entry_slip_used
        nonlocal pnl_min, pnl_max, hit1_td, hit2_td, hit3_td, open_size
        if compound:
            dep = max(1.0, base + realized)
        else:
            dep = base
        direction = "LONG" if sig == 1 else "SHORT"
        tn, tp = _prep_leg_prices(prep, i)
        sz = size_test_spread_lots(
            deposit_rub=dep, price_tatn=tn, price_tatnp=tp, direction=direction
        )
        if sz.get("lots") == 0:
            return
        open_size = sz
        pos_notional = float(sz.get("deposit") or dep)
        eff, comm, ovn_day = _fees_for_sized_open(
            deposit=pos_notional, direction=direction, sz=sz
        )
        entry_comm = comm
        tip_sp = float(sp_arr[i])
        if sig == 1:
            entry_sp = tip_sp + slip
        else:
            entry_sp = tip_sp - slip
        entry_slip_used = float(slip)
        entry_td = dates[i]
        entry_z = float(z_arr[i]) if math.isfinite(float(z_arr[i])) else 0.0
        entry_day = int(day_ord[i])
        pos = 1 if sig == 1 else 2
        trade_no += 1
        hit1_td = hit2_td = hit3_td = None
        mtm = _track(i)
        pnl_min = pnl_max = mtm

    def _close(i: int, reason: str) -> None:
        nonlocal pos, realized, total_pnl, peak, max_dd
        nonlocal pnl_min, pnl_max, hit1_td, hit2_td, hit3_td, open_size
        is_long = pos == 1
        tip_sp = float(sp_arr[i])
        exit_sp = tip_sp - slip if is_long else tip_sp + slip
        pnl_pts = (exit_sp - entry_sp) if is_long else (entry_sp - exit_sp)
        gross = eff * (pnl_pts / 100.0)
        ov = ovn_day * max(0, int(day_ord[i]) - entry_day)
        comm_total = entry_comm + comm
        net = gross - comm_total - ov
        total_pnl += net
        realized += net
        if total_pnl > peak:
            peak = total_pnl
        max_dd = max(max_dd, peak - total_pnl)
        pmin = net if not math.isfinite(pnl_min) else min(pnl_min, net)
        pmax = net if not math.isfinite(pnl_max) else max(pnl_max, net)
        closed_trades.append(
            {
                "index": trade_no,
                "direction": "Long" if is_long else "Short",
                "entryDate": entry_td,
                "exitDate": dates[i],
                "entryZ": round(entry_z, 4),
                "exitZ": round(float(z_arr[i]), 4) if math.isfinite(float(z_arr[i])) else None,
                "entrySpread": round(entry_sp, 6),
                "exitSpread": round(exit_sp, 6),
                "entrySlip": round(entry_slip_used, 6),
                "pnlPts": round(pnl_pts, 6),
                "gross": round(float(gross), 4),
                "commission": round(float(comm_total), 4),
                "overnight": round(float(ov), 4),
                "net": round(net, 4),
                "modelNet": round(net, 4),
                "netFromAccount": False,
                "pnlMin": round(pmin, 4),
                "pnlMax": round(pmax, 4),
                "hit1Date": hit1_td,
                "hit2Date": hit2_td,
                "hit3Date": hit3_td,
                "accountBefore": round(base + realized - net, 2),
                "accountAfter": round(base + realized, 2),
                "status": "Закрыта",
                "exitReason": reason,
                "notional": round(pos_notional, 2),
                "tag": "shelf_ff",
                "source": "пол–потолок",
            }
        )
        closed_trades[-1].update(_lot_trade_fields(open_size))
        open_size = None
        pos = 0
        pnl_min = float("inf")
        pnl_max = float("-inf")
        hit1_td = hit2_td = hit3_td = None

    for i in edges:
        i = int(i)
        if i < 1:
            continue
        prev_sp = float(sp_arr[i - 1])
        cur_sp = float(sp_arr[i])
        if not (math.isfinite(prev_sp) and math.isfinite(cur_sp)):
            continue
        day = _day_key(dates, i)
        formed, lo, hi = _shelf_formed(causal.get(day))

        if pos in (1, 2):
            mtm = _track(i)
            reason = None
            if not formed:
                reason = "shelf_break"
            elif tp_use > 0:
                pct = (mtm / max(1.0, pos_notional)) * 100.0
                if pct >= tp_use:
                    reason = "tp"
            if reason is None and lo is not None and hi is not None:
                if pos == 2 and cur_sp <= lo:
                    reason = "shelf_edge"
                elif pos == 1 and cur_sp >= hi:
                    reason = "shelf_edge"
            if (
                reason is None
                and use_close_3d
                and pos in (1, 2)
                and lo is not None
                and hi is not None
            ):
                from replay.tip_touch import _force_close_3d_hit

                held = int(day_ord[i]) - entry_day
                is_long_h = pos == 1
                exit_lv_3d = float(hi if is_long_h else lo)
                if _force_close_3d_hit(
                    enabled=True,
                    held=held,
                    is_long=is_long_h,
                    entry_sp=entry_sp,
                    cur_sp=cur_sp,
                    exit_lv=exit_lv_3d,
                    mtm=mtm,
                ):
                    reason = "force_close_3d"
            if (
                reason is None
                and hold_days > 0
                and (int(day_ord[i]) - entry_day) >= hold_days
                and mtm < 0
            ):
                toward = _toward_exit(
                    is_long=(pos == 1),
                    entry_sp=entry_sp,
                    cur_sp=cur_sp,
                    lo=lo,
                    hi=hi,
                    min_pp=trend_min_pp,
                )
                if not toward:
                    reason = "hold_no_trend"
            if reason:
                _close(i, reason)
                # после закрытия можно зеркально войти на той же кромке (классика)

        if pos != 0:
            continue
        if not formed or lo is None or hi is None:
            continue
        # Short: касание потолка снизу; Long: касание пола сверху. Без пирамиды.
        if prev_sp < hi <= cur_sp:
            _open(2, i)
        elif prev_sp > lo >= cur_sp:
            _open(1, i)

    trades_out = list(closed_trades)
    if pos in (1, 2) and entry_td:
        last_i = int(prep.n) - 1
        mtm = _mtm_at(last_i) if last_i >= 0 else 0.0
        pmin = mtm if not math.isfinite(pnl_min) else min(pnl_min, mtm)
        pmax = mtm if not math.isfinite(pnl_max) else max(pnl_max, mtm)
        trades_out.append(
            {
                "index": trade_no,
                "direction": "Long" if pos == 1 else "Short",
                "entryDate": entry_td,
                "exitDate": "—",
                "entryZ": round(entry_z, 4),
                "exitZ": round(float(z_arr[last_i]), 4) if last_i >= 0 else None,
                "entrySpread": round(entry_sp, 6),
                "exitSpread": None,
                "entrySlip": round(entry_slip_used, 6),
                "pnlPts": None,
                "gross": None,
                "commission": None,
                "overnight": None,
                "net": None,
                "modelNet": None,
                "netFromAccount": False,
                "pnlMin": round(pmin, 4),
                "pnlMax": round(pmax, 4),
                "hit1Date": hit1_td,
                "hit2Date": hit2_td,
                "hit3Date": hit3_td,
                "accountAfter": None,
                "status": "Открыта",
                "exitReason": None,
                "notional": round(pos_notional, 2),
                "tag": "shelf_ff",
                "source": "пол–потолок",
            }
        )
        trades_out[-1].update(_lot_trade_fields(open_size))

    closed_n = len(closed_trades)
    wins = sum(1 for t in closed_trades if float(t.get("net") or 0) > 0)
    reasons: dict[str, int] = {}
    for t in closed_trades:
        k = str(t.get("exitReason") or "")
        reasons[k] = reasons.get(k, 0) + 1
    note = (
        "пол–потолок (широкая полка, каузально): Short на потолке, Long на поле; "
        f"выход кромка / ТП {tp_use:g}% депозита / нет хода {hold_days}д / вынос. "
        "Без добора и без порогов AUTO 3.2/6.1."
    )
    return {
        "trades": trades_out,
        "summary": {
            "trades": closed_n,
            "wins": wins,
            "winratePct": round(100.0 * wins / closed_n, 1) if closed_n else 0.0,
            "pnlRub": round(total_pnl, 2),
            "maxDdRub": round(max_dd, 2),
            "finalEquityRub": round(base + total_pnl, 2),
            "retPct": round(100.0 * total_pnl / base, 2) if base else 0.0,
            "openCount": 1 if pos else 0,
            "exitReasons": reasons,
            "note": note,
        },
        "params": {
            "slip": slip,
            "notional": base,
            "compound": bool(compound),
            "takeProfitPct": tp_use,
            "maxHoldDaysNoExitTrend": hold_days,
            "forceClose3d": bool(use_close_3d),
            "exitTrendMinPp": trend_min_pp,
            "leverage": LEVERAGE,
            "commissionPctPerSide": COMM_PCT,
            "overnightFeeModel": OVERNIGHT_FEE_MODEL,
            "asLive": False,
            "regimeZMode": False,
            "spreadLevelMode": False,
            "shelfFloorCeilingMode": True,
            "tip1mSettleSec": float(settle),
        },
    }


def shelf_cross_entry(
    prev_sp: float,
    cur_sp: float,
    *,
    formed: bool,
    lo: float | None,
    hi: float | None,
) -> str | None:
    """Касание 1м: потолок → Short, пол → Long. Только сформированная полка."""
    if not formed or lo is None or hi is None:
        return None
    try:
        prev_f = float(prev_sp)
        cur_f = float(cur_sp)
        lo_f = float(lo)
        hi_f = float(hi)
    except (TypeError, ValueError):
        return None
    if not all(math.isfinite(x) for x in (prev_f, cur_f, lo_f, hi_f)):
        return None
    if prev_f < hi_f <= cur_f:
        return "SHORT"
    if prev_f > lo_f >= cur_f:
        return "LONG"
    return None


def shelf_live_exit_reason(
    *,
    is_long: bool,
    cur_sp: float,
    formed: bool,
    lo: float | None,
    hi: float | None,
    tp_hit: bool = False,
    hold_no_trend: bool = False,
) -> str | None:
    """Порядок как в симе Теста: вынос → ТП → кромка → нет хода."""
    if not formed:
        return "shelf_break"
    if tp_hit:
        return "tp"
    try:
        cur_f = float(cur_sp)
    except (TypeError, ValueError):
        cur_f = float("nan")
    if math.isfinite(cur_f) and lo is not None and hi is not None:
        try:
            lo_f = float(lo)
            hi_f = float(hi)
        except (TypeError, ValueError):
            lo_f = hi_f = float("nan")
        if math.isfinite(lo_f) and math.isfinite(hi_f):
            if (not is_long) and cur_f <= lo_f:
                return "shelf_edge"
            if is_long and cur_f >= hi_f:
                return "shelf_edge"
    if hold_no_trend:
        return "hold_no_trend"
    return None


def live_shelf_state_from_bars(
    bars: list[dict[str, Any]] | None,
    as_of: str,
    *,
    spread_now: float | None = None,
) -> tuple[bool, float | None, float | None]:
    """Каузальная широкая полка на дату as_of — тот же детектор, что Тест."""
    if not bars or not as_of:
        return False, None, None
    from live.spread_corridor_wide import desk_wide_corridor_payload

    payload = desk_wide_corridor_payload(
        list(bars),
        spread_now=spread_now,
        as_of=str(as_of)[:10],
    )
    return _shelf_formed(payload)
