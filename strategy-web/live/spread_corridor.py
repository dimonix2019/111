"""Адаптивный коридор спреда TATN — фильтр внимания (не меняет пороги Prod AUTO)."""

from __future__ import annotations

import statistics
import threading
import time
from datetime import datetime, timedelta
from typing import Any

CORRIDOR_TOUCH_EPS_PP = 0.25
CORRIDOR_WIDE_MAX_WIDTH_PP = 4.0
CORRIDOR_FORMED_MIN_DAYS = 5
CORRIDOR_FORMING_MIN_DAYS = 2
CORRIDOR_BREAK_EPS_PP = 0.20
CORRIDOR_ZONE_FRAC = 0.25
CORRIDOR_MIN_DAYS = 7
# Границы p20/p62 по дневным min/max — с 4 дней (короткая новая полка).
CORRIDOR_BOUNDS_MIN_DAYS = 4
CORRIDOR_LOOKBACK_DAYS = 30
CORRIDOR_LO_SANITY = 0.3
CORRIDOR_HI_SANITY = 8.0
# Кластер касаний: не тащить пол/потолок к чужой полке и редким хвостам.
CORRIDOR_CLUSTER_GAP_PP = 0.55
CORRIDOR_RECENT_DAYS = 4
CORRIDOR_CLUSTER_MIN_RECENT = 2
CORRIDOR_SHELF_MEMBER_EPS_PP = 0.45
CORRIDOR_TOUCH_NEAR_PP = 0.60
CORRIDOR_CALC_MIN_WIDTH_PP = 0.50
CORRIDOR_SEGMENT_JUMP_PP = 0.55
# Узкая текущая полка внутри более широкого недавнего кластера (m15, не 3 свечи).
CORRIDOR_INNER_MIN_BARS = 16
CORRIDOR_INNER_MAX_DAYS = 2
CORRIDOR_INNER_MIN_WIDTH_PP = 0.22
CORRIDOR_INNER_WIDTH_FRAC = 0.72
CORRIDOR_INNER_WIDTH_GAP_PP = 0.22
CORRIDOR_INNER_MIN_EDGE_TOUCHES = 2
CORRIDOR_INNER_NEIGH_PP = 0.22
CORRIDOR_INNER_MIN_NEIGHBORS = 3
CORRIDOR_INNER_TAIL_BARS = 16
CORRIDOR_INNER_EDGE_BAND_PP = 0.08


def _percentile(sorted_vals: list[float], pct: float) -> float:
    if not sorted_vals:
        return 0.0
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    k = (len(sorted_vals) - 1) * (pct / 100.0)
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    if f == c:
        return sorted_vals[f]
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


def _trailing_run(mask: list[bool]) -> int:
    n = 0
    for v in reversed(mask):
        if not v:
            break
        n += 1
    return n


def _value_clusters(vals: list[float], gap: float) -> list[list[float]]:
    if not vals:
        return []
    ordered = sorted(vals)
    clusters = [[ordered[0]]]
    for v in ordered[1:]:
        if v - clusters[-1][-1] <= gap:
            clusters[-1].append(v)
        else:
            clusters.append([v])
    return clusters


def _recent_tail(pairs: list[tuple[str, float]], n: int) -> list[float]:
    if not pairs:
        return []
    take = max(1, int(n))
    return [float(v) for _, v in pairs[-take:]]


def _pick_bound_cluster(
    clusters: list[list[float]],
    recent: list[float],
    *,
    side: str,
    gap: float,
    min_recent: int,
) -> list[float]:
    if not clusters:
        return []
    if len(clusters) == 1:
        return clusters[0]

    def n_recent(c: list[float]) -> int:
        return sum(1 for r in recent if any(abs(r - v) <= gap for v in c))

    scored = [(c, n_recent(c)) for c in clusters]
    ok = [c for c, n in scored if n >= min_recent]
    if side == "lo":
        if ok:
            return ok[0]
        return max(scored, key=lambda x: (x[1], -statistics.median(x[0])))[0]
    if ok:
        return ok[-1]
    return max(scored, key=lambda x: (x[1], statistics.median(x[0])))[0]


def _cluster_percentile(
    vals: list[float],
    pct: float,
    recent: list[float],
    *,
    side: str,
    gap: float = CORRIDOR_CLUSTER_GAP_PP,
    min_recent: int = CORRIDOR_CLUSTER_MIN_RECENT,
) -> float:
    if not vals:
        return 0.0
    if len(vals) == 1:
        return vals[0]
    clusters = _value_clusters(vals, gap)
    use = _pick_bound_cluster(
        clusters,
        recent or vals,
        side=side,
        gap=gap,
        min_recent=min_recent,
    )
    return _percentile(sorted(use or vals), pct)


def _bar_trade_day(b: dict[str, Any]) -> str | None:
    if not isinstance(b, dict):
        return None
    for key in ("trade_date", "tradeDate", "time", "timestamp"):
        raw = b.get(key)
        if raw is None:
            continue
        s = str(raw).strip().replace("T", " ")
        if len(s) >= 10 and s[4] == "-" and s[7] == "-":
            return s[:10]
    ms = b.get("timestampMs")
    if ms is not None:
        try:
            from datetime import datetime, timezone

            dt = datetime.fromtimestamp(int(ms) / 1000.0, tz=timezone.utc)
            return dt.strftime("%Y-%m-%d")
        except (TypeError, ValueError, OSError):
            pass
    return None


def _bar_spread(b: dict[str, Any]) -> float | None:
    if not isinstance(b, dict):
        return None
    raw = b.get("spread")
    if raw is None:
        raw = b.get("spreadPercent")
    if raw is None and b.get("close") is not None and b.get("z") is None:
        raw = b.get("close")
    try:
        v = float(raw)
    except (TypeError, ValueError):
        return None
    return v if v == v else None


def daily_spread_extremes_from_bars(
    bars: list[dict[str, Any]],
) -> tuple[list[tuple[str, float]], list[tuple[str, float]], list[tuple[str, float]]]:
    """Дневная медиана и min/max спреда из m15/1m."""
    by_day: dict[str, list[float]] = {}
    for b in bars or []:
        td = _bar_trade_day(b)
        sp = _bar_spread(b)
        if not td or sp is None:
            continue
        by_day.setdefault(td, []).append(sp)
    medians: list[tuple[str, float]] = []
    mins: list[tuple[str, float]] = []
    maxs: list[tuple[str, float]] = []
    for td in sorted(by_day.keys()):
        vals = by_day[td]
        medians.append((td, float(statistics.median(vals))))
        mins.append((td, float(min(vals))))
        maxs.append((td, float(max(vals))))
    return medians, mins, maxs


def spreads_from_bars(bars: list[dict[str, Any]]) -> list[float]:
    out: list[float] = []
    for b in bars or []:
        sp = _bar_spread(b)
        if sp is not None:
            out.append(sp)
    return out


def daily_spreads_from_bars(bars: list[dict[str, Any]]) -> list[tuple[str, float]]:
    medians, _, _ = daily_spread_extremes_from_bars(bars)
    return medians


def _count_touches_at_bounds(
    day_mins: list[tuple[str, float]],
    day_maxs: list[tuple[str, float]],
    daily_values: list[float],
    lo: float,
    hi: float,
    *,
    eps: float = CORRIDOR_TOUCH_EPS_PP,
) -> tuple[int, int]:
    """Касания рассчитанных границ: день с min ≤ lo+ε или max ≥ hi−ε."""
    by_min = {d: v for d, v in day_mins}
    by_max = {d: v for d, v in day_maxs}
    days = sorted(set(by_min) | set(by_max))
    near = CORRIDOR_TOUCH_NEAR_PP
    touches_lo = sum(
        1
        for d in days
        if lo - near <= by_min.get(d, 999.0) <= lo + eps
    )
    touches_hi = sum(
        1
        for d in days
        if hi - eps <= by_max.get(d, -999.0) <= hi + near
    )
    if not days and daily_values:
        touches_lo = sum(1 for v in daily_values if lo - near <= v <= lo + eps)
        touches_hi = sum(1 for v in daily_values if hi - eps <= v <= hi + near)
    return touches_lo, touches_hi


def _calculate_bounds_from_data(
    *,
    bar_spreads: list[float],
    day_mins: list[tuple[str, float]],
    day_maxs: list[tuple[str, float]],
    daily_values: list[float],
    lo_sanity: float = CORRIDOR_LO_SANITY,
    hi_sanity: float = CORRIDOR_HI_SANITY,
) -> tuple[float, float, int, int, str]:
    """
    Границы коридора из данных (без подстановки L1/1%):
    - низ  = p20 активного кластера дневных минимумов (пол текущей полки);
    - верх = p62 активного кластера дневных максимумов (потолок);
    - старые дни и редкие хвосты в чужом кластере не двигают границы;
    - если размах < 0,5 п.п. — перцентильная полоса по дневным медианам.
    """
    mins = [v for _, v in day_mins]
    maxs = [v for _, v in day_maxs]
    meds = list(daily_values)

    if len(mins) >= CORRIDOR_BOUNDS_MIN_DAYS and len(maxs) >= CORRIDOR_BOUNDS_MIN_DAYS:
        recent_mins = _recent_tail(day_mins, CORRIDOR_RECENT_DAYS)
        recent_maxs = _recent_tail(day_maxs, CORRIDOR_RECENT_DAYS)
        lo = _cluster_percentile(mins, 20, recent_mins, side="lo")
        hi = _cluster_percentile(maxs, 62, recent_maxs, side="hi")
        lo = max(lo_sanity, lo)
        hi = min(hi_sanity, hi)
        if hi > lo + CORRIDOR_CALC_MIN_WIDTH_PP:
            t_lo, t_hi = _count_touches_at_bounds(day_mins, day_maxs, meds, lo, hi)
            return lo, hi, t_lo, t_hi, "calculated"

    if len(meds) < CORRIDOR_MIN_DAYS:
        ordered = sorted(meds)
        lo = _percentile(ordered, 15)
        hi = _percentile(ordered, 85)
        if hi <= lo:
            hi = lo + 0.5
        t_lo, t_hi = _count_touches_at_bounds(day_mins, day_maxs, meds, lo, hi)
        return lo, hi, t_lo, t_hi, "adaptive"

    ordered = sorted(meds)
    lo = max(lo_sanity, _percentile(ordered, 12))
    hi = min(hi_sanity, _percentile(ordered, 88))
    if hi <= lo + 0.3:
        hi = lo + 0.5
    t_lo, t_hi = _count_touches_at_bounds(day_mins, day_maxs, meds, lo, hi)
    return lo, hi, t_lo, t_hi, "adaptive"


def _dense_core_vals(
    vals: list[float],
    *,
    neigh: float = CORRIDOR_INNER_NEIGH_PP,
    min_neighbors: int = CORRIDOR_INNER_MIN_NEIGHBORS,
) -> list[float]:
    """Убирает одиночный шип/хвост; плотные полюса качалки (1.x и 3.x) остаются."""
    if len(vals) < min_neighbors:
        return list(vals)
    core = [
        v for v in vals
        if sum(1 for x in vals if abs(x - v) <= neigh) >= min_neighbors
    ]
    if len(core) < max(8, min_neighbors * 2):
        return list(vals)
    return core


def _inner_touch_eps(width: float) -> float:
    w = max(float(width), 1e-9)
    eps = min(CORRIDOR_TOUCH_EPS_PP, max(0.08, 0.20 * w))
    if 2.0 * eps >= 0.70 * w:
        eps = 0.22 * w
    return eps


def _count_bar_bound_touches(
    vals: list[float],
    lo: float,
    hi: float,
    eps: float,
) -> tuple[int, int]:
    near = min(0.22, max(eps, 0.10))
    t_lo = sum(1 for v in vals if lo - near <= v <= lo + eps)
    t_hi = sum(1 for v in vals if hi - eps <= v <= hi + near)
    return t_lo, t_hi


def _edge_with_repeats(vals: list[float], *, side: str) -> float:
    """Край полки — плотная группа с повторными касаниями, не одиночный шип."""
    ordered = sorted(vals)
    if not ordered:
        return 0.0
    band = CORRIDOR_INNER_EDGE_BAND_PP
    need = CORRIDOR_INNER_MIN_EDGE_TOUCHES
    seq = ordered if side == "lo" else reversed(ordered)
    for cand in seq:
        group = [v for v in vals if abs(v - cand) <= band]
        if len(group) >= need:
            return min(group) if side == "lo" else max(group)
    return ordered[0] if side == "lo" else ordered[-1]


def _bar_points_until(
    bars: list[dict[str, Any]] | None,
    last_day: str | None,
) -> list[tuple[str, float]]:
    out: list[tuple[str, float]] = []
    for b in bars or []:
        td = _bar_trade_day(b)
        sp = _bar_spread(b)
        if not td or sp is None:
            continue
        if last_day and td > last_day:
            continue
        out.append((td, sp))
    return out


def _inner_candidate_windows(
    points: list[tuple[str, float]],
) -> list[list[tuple[str, float]]]:
    if len(points) < CORRIDOR_INNER_MIN_BARS:
        return []
    days: list[str] = []
    seen: set[str] = set()
    for d, _ in reversed(points):
        if d not in seen:
            seen.add(d)
            days.append(d)
        if len(days) >= CORRIDOR_INNER_MAX_DAYS:
            break
    days.reverse()
    out: list[list[tuple[str, float]]] = []
    seen_n: set[int] = set()
    for n_days in range(len(days), 0, -1):
        want = set(days[-n_days:])
        win = [(d, v) for d, v in points if d in want]
        if len(win) >= CORRIDOR_INNER_MIN_BARS:
            out.append(win)
            seen_n.add(len(win))
    for n in (48, 32, 24, 16):
        if n <= len(points) and n not in seen_n:
            out.append(points[-n:])
            seen_n.add(n)
    return out


def _tail_uses_both_edges(
    vals: list[float],
    lo: float,
    hi: float,
    eps: float,
) -> bool:
    """Последние бары всё ещё бьют оба края — иначе это уже другая, более узкая полка."""
    width = max(hi - lo, 1e-9)
    bot = lo + CORRIDOR_ZONE_FRAC * width
    top = hi - CORRIDOR_ZONE_FRAC * width
    if len(vals) <= 24:
        tail_n = len(vals) if len(vals) < 16 else max(8, len(vals) // 2)
    else:
        tail_n = min(len(vals), max(12, CORRIDOR_INNER_TAIL_BARS))
    tail = vals[-tail_n:]
    in_band = [v for v in tail if lo - eps <= v <= hi + eps]
    if len(in_band) < 4:
        return False
    return (
        sum(1 for v in in_band if v <= bot) >= 2
        and sum(1 for v in in_band if v >= top) >= 2
    )


def _try_inner_current_shelf(
    *,
    bars: list[dict[str, Any]] | None,
    last_day: str | None,
    s_now: float,
    outer_lo: float,
    outer_hi: float,
) -> dict[str, Any] | None:
    """
    Если за 1–2 дня / последние часы спред живёт в заметно более узкой полосе,
    чем дневной кластер, и повторно бьёт оба края — текущая полка = эта полоса.
    """
    outer_w = float(outer_hi) - float(outer_lo)
    if outer_w < CORRIDOR_INNER_MIN_WIDTH_PP + CORRIDOR_INNER_WIDTH_GAP_PP:
        return None
    points = _bar_points_until(bars, last_day)
    best: dict[str, Any] | None = None
    best_score: float | None = None
    member_cap = CORRIDOR_SHELF_MEMBER_EPS_PP
    for win in _inner_candidate_windows(points):
        vals = [v for _, v in win]
        core = _dense_core_vals(vals)
        if len(core) < max(8, CORRIDOR_INNER_MIN_BARS // 2):
            continue
        lo = _edge_with_repeats(core, side="lo")
        hi = _edge_with_repeats(core, side="hi")
        width = hi - lo
        if width < CORRIDOR_INNER_MIN_WIDTH_PP:
            continue
        if width > outer_w * CORRIDOR_INNER_WIDTH_FRAC:
            continue
        if outer_w - width < CORRIDOR_INNER_WIDTH_GAP_PP:
            continue
        member = min(member_cap, max(0.12, 0.35 * width))
        if not (lo - member <= s_now <= hi + member):
            continue
        eps = _inner_touch_eps(width)
        t_lo, t_hi = _count_bar_bound_touches(core, lo, hi, eps)
        if t_lo < CORRIDOR_INNER_MIN_EDGE_TOUCHES or t_hi < CORRIDOR_INNER_MIN_EDGE_TOUCHES:
            continue
        # Хвост проверяем только у длинных окон: иначе внутридневная качалка
        # в последней половине сессии не обязана бить утренний край.
        if len(win) >= 40 and not _tail_uses_both_edges(core, lo, hi, eps):
            continue
        bounces = _count_bounces(core, lo, hi)
        if bounces < 2 and (t_lo + t_hi) < 6:
            continue
        score = width - 0.003 * min(len(win), 48)
        if best_score is None or score < best_score:
            best_score = score
            best = {
                "lo": lo,
                "hi": hi,
                "touches_lo": t_lo,
                "touches_hi": t_hi,
                "bounces": bounces,
                "since_date": win[0][0],
                "n_bars": len(win),
            }
    return best


def _count_bounces(vals: list[float], lo: float, hi: float) -> int:
    width = max(hi - lo, 1e-9)
    bot = lo + CORRIDOR_ZONE_FRAC * width
    top = hi - CORRIDOR_ZONE_FRAC * width
    touches = 0
    prev_zone = None
    for v in vals:
        if v <= bot:
            zone = "lo"
        elif v >= top:
            zone = "hi"
        else:
            zone = "mid"
        if zone in ("lo", "hi") and zone != prev_zone:
            touches += 1
        if zone != "mid":
            prev_zone = zone
    return touches


def _clip_daily(
    pairs: list[tuple[str, float]],
    mins: list[tuple[str, float]],
    maxs: list[tuple[str, float]],
    since: str | None,
) -> tuple[list[tuple[str, float]], list[tuple[str, float]], list[tuple[str, float]]]:
    if not since:
        return pairs, mins, maxs
    return (
        [(d, v) for d, v in pairs if d >= since],
        [(d, v) for d, v in mins if d >= since],
        [(d, v) for d, v in maxs if d >= since],
    )


def _reform_window_start(
    pairs: list[tuple[str, float]],
    broken_since: str | None,
    min_days: int,
) -> str | None:
    """Новая полоса: со следующего дня после слома; если дней мало — со дня слома."""
    if not broken_since or not pairs:
        return None
    after = [d for d, _ in pairs if d > broken_since]
    if len(after) >= min_days:
        return after[0]
    on = [d for d, _ in pairs if d >= broken_since]
    if len(on) >= min_days:
        return on[0]
    return None


def _day_extreme_on(
    day: str | None,
    day_mins: list[tuple[str, float]],
    day_maxs: list[tuple[str, float]],
) -> tuple[float | None, float | None]:
    if not day:
        return None, None
    by_min = {d: float(v) for d, v in day_mins}
    by_max = {d: float(v) for d, v in day_maxs}
    return by_min.get(day), by_max.get(day)


def _exited_corridor_band(
    *,
    s_now: float,
    lo: float,
    hi: float,
    day_min: float | None,
    day_max: float | None,
    eps: float,
) -> bool:
    """Вынос: живой S или дневной min/max за пределы полосы (не только медиана)."""
    if s_now > hi + eps or s_now < lo - eps:
        return True
    if day_max is not None and day_max > hi + eps:
        return True
    if day_min is not None and day_min < lo - eps:
        return True
    return False


def detect_spread_corridor(
    daily: list[tuple[str, float]] | list[float],
    *,
    spread_now: float | None = None,
    bar_spreads: list[float] | None = None,
    touch_mins: list[tuple[str, float]] | None = None,
    touch_maxs: list[tuple[str, float]] | None = None,
    bars: list[dict[str, Any]] | None = None,
    allow_inner: bool = True,
    lo_sanity: float = CORRIDOR_LO_SANITY,
    hi_sanity: float = CORRIDOR_HI_SANITY,
    min_days: int = CORRIDOR_MIN_DAYS,
) -> dict[str, Any]:
    """Коридор спреда: границы только из расчёта по касаниям/кластерам в данных."""
    empty: dict[str, Any] = {
        "phase": "none",
        "label_ru": "нет коридора",
        "lo": None,
        "hi": None,
        "width": None,
        "spread": spread_now,
        "pct_in_band": None,
        "dwell_days": 0,
        "bounces": 0,
        "touches_lo": 0,
        "touches_hi": 0,
        "bounds_mode": "none",
        "shrink_days": 0,
        "n_days": 0,
        "since_date": None,
        "broken_since": None,
        "inner_shelf": False,
        "outer_lo": None,
        "outer_hi": None,
        "inner": None,
        "title": "Мало дневных точек для коридора",
    }

    day_mins = list(touch_mins or [])
    day_maxs = list(touch_maxs or [])

    full_pairs: list[tuple[str, float]] = []
    if isinstance(daily, list) and daily and isinstance(daily[0], tuple):
        full_pairs = [(str(d), float(v)) for d, v in daily]  # type: ignore[arg-type]
        values = [v for _, v in full_pairs]
        dates = [d for d, _ in full_pairs]
    else:
        values = [float(v) for v in daily]  # type: ignore[arg-type]
        dates = []
        if day_mins and len(day_mins) == len(values):
            dates = [str(d) for d, _ in day_mins]
            full_pairs = list(zip(dates, values))

    if len(values) < min_days:
        empty["n_days"] = len(values)
        empty["last_date"] = dates[-1] if dates else None
        return empty

    s_now = float(spread_now) if spread_now is not None else values[-1]
    last_day = dates[-1] if dates else None
    last_min, last_max = _day_extreme_on(last_day, day_mins, day_maxs)
    eps = CORRIDOR_BREAK_EPS_PP

    episode_pairs = list(full_pairs)
    episode_values = list(values)
    episode_mins = list(day_mins)
    episode_maxs = list(day_maxs)
    since_date = dates[0] if dates else None
    broken = False
    freeze_lo: float | None = None
    freeze_hi: float | None = None
    broken_since: str | None = None
    prev_since: str | None = None

    # Липкий слом: не даём перцентилю «съесть» шип.
    # После слома не ждём возврата в старые границы — ищем новую полосу
    # с первого дня, чья середина уже вне сломанной полосы.
    if len(values) > min_days:
        if full_pairs and len(full_pairs) > 1:
            prev_daily: list[tuple[str, float]] | list[float] = full_pairs[:-1]
            prev_spread = float(full_pairs[-2][1])
            cutoff = str(full_pairs[-2][0])
            prev_mins = [(d, v) for d, v in day_mins if d <= cutoff]
            prev_maxs = [(d, v) for d, v in day_maxs if d <= cutoff]
        else:
            prev_daily = values[:-1]
            prev_spread = float(values[-2])
            prev_mins = day_mins[:-1] if len(day_mins) == len(values) else day_mins
            prev_maxs = day_maxs[:-1] if len(day_maxs) == len(values) else day_maxs
        prev = detect_spread_corridor(
            prev_daily,
            spread_now=prev_spread,
            bar_spreads=bar_spreads,
            touch_mins=prev_mins,
            touch_maxs=prev_maxs,
            bars=bars,
            allow_inner=False,
            lo_sanity=lo_sanity,
            hi_sanity=hi_sanity,
            min_days=min_days,
        )
        prev_phase = str(prev.get("phase") or "")
        plo = prev.get("lo")
        phi = prev.get("hi")
        prev_since = prev.get("since_date")
        if prev.get("broken_since"):
            broken_since = prev.get("broken_since")
        if (
            prev_phase in ("formed", "forming")
            and plo is not None
            and phi is not None
            and _exited_corridor_band(
                s_now=s_now,
                lo=float(plo),
                hi=float(phi),
                day_min=last_min,
                day_max=last_max,
                eps=eps,
            )
        ):
            broken = True
            freeze_lo, freeze_hi = float(plo), float(phi)
            broken_since = last_day
        elif prev_phase in ("formed", "forming") and plo is not None and phi is not None:
            since_date = prev_since or since_date
            if since_date and full_pairs:
                episode_pairs, episode_mins, episode_maxs = _clip_daily(
                    full_pairs, day_mins, day_maxs, str(since_date),
                )
                # День слома часто шип: если после него уже полная полка — не держать его в окне.
                if (
                    len(episode_pairs) > CORRIDOR_BOUNDS_MIN_DAYS
                    and episode_mins
                    and episode_pairs
                ):
                    first_d = episode_pairs[0][0]
                    first_min = next((v for d, v in episode_mins if d == first_d), None)
                    rest_mins = [v for d, v in episode_mins if d > first_d]
                    if (
                        first_min is not None
                        and rest_mins
                        and first_min < min(rest_mins) - eps
                    ):
                        since_date = episode_pairs[1][0]
                        episode_pairs, episode_mins, episode_maxs = _clip_daily(
                            full_pairs, day_mins, day_maxs, str(since_date),
                        )
                episode_values = [v for _, v in episode_pairs] or episode_values
        elif prev_phase == "broken" and plo is not None and phi is not None:
            flo, fhi = float(plo), float(phi)
            if not broken_since:
                broken_since = prev.get("last_date")
            reform_from = (
                _reform_window_start(
                    full_pairs, broken_since, CORRIDOR_BOUNDS_MIN_DAYS
                )
                if full_pairs
                else None
            )
            if reform_from:
                post_pairs = [(d, v) for d, v in full_pairs if d >= reform_from]
                same_series = bool(dates) and str(reform_from) <= str(dates[0])
                if len(post_pairs) >= CORRIDOR_BOUNDS_MIN_DAYS and not same_series:
                    post_end = post_pairs[-1][0]
                    post_mins = [
                        (d, v) for d, v in day_mins if reform_from <= d <= post_end
                    ]
                    post_maxs = [
                        (d, v) for d, v in day_maxs if reform_from <= d <= post_end
                    ]
                    cand = detect_spread_corridor(
                        post_pairs,
                        spread_now=s_now,
                        bar_spreads=bar_spreads,
                        touch_mins=post_mins,
                        touch_maxs=post_maxs,
                        bars=bars,
                        allow_inner=allow_inner,
                        lo_sanity=lo_sanity,
                        hi_sanity=hi_sanity,
                        min_days=CORRIDOR_BOUNDS_MIN_DAYS,
                    )
                    if str(cand.get("phase") or "") in ("forming", "formed"):
                        return cand
            broken = True
            freeze_lo, freeze_hi = flo, fhi
            since_date = prev_since or since_date

    lo, hi, t_lo, t_hi, bounds_mode = _calculate_bounds_from_data(
        bar_spreads=bar_spreads or [],
        day_mins=episode_mins,
        day_maxs=episode_maxs,
        daily_values=episode_values,
        lo_sanity=lo_sanity,
        hi_sanity=hi_sanity,
    )
    # Две полки на одном участке: оставляем ту, где сейчас живёт S.
    if (
        not broken
        and episode_pairs
        and lo is not None
        and hi is not None
    ):
        member_eps = CORRIDOR_SHELF_MEMBER_EPS_PP
        in_shelf = [
            (lo - member_eps) <= v <= (hi + member_eps) for _, v in episode_pairs
        ]
        trail = _trailing_run(in_shelf)
        if trail >= CORRIDOR_BOUNDS_MIN_DAYS and trail < len(episode_pairs):
            episode_pairs = episode_pairs[-trail:]
            since_date = episode_pairs[0][0]
            cut = str(since_date)
            episode_mins = [(d, v) for d, v in episode_mins if d >= cut]
            episode_maxs = [(d, v) for d, v in episode_maxs if d >= cut]
            episode_values = [v for _, v in episode_pairs]
            lo, hi, t_lo, t_hi, bounds_mode = _calculate_bounds_from_data(
                bar_spreads=bar_spreads or [],
                day_mins=episode_mins,
                day_maxs=episode_maxs,
                daily_values=episode_values,
                lo_sanity=lo_sanity,
                hi_sanity=hi_sanity,
            )
    inner_shelf = False
    inner_n_bars = 0
    inner_payload: dict[str, Any] | None = None
    outer_lo, outer_hi = lo, hi
    if (
        allow_inner
        and not broken
        and lo is not None
        and hi is not None
        and bars
    ):
        inner = _try_inner_current_shelf(
            bars=bars,
            last_day=last_day,
            s_now=s_now,
            outer_lo=float(lo),
            outer_hi=float(hi),
        )
        if inner is not None:
            cand_lo = max(lo_sanity, float(inner["lo"]))
            cand_hi = min(hi_sanity, float(inner["hi"]))
            if cand_hi > cand_lo + 1e-6:
                lo, hi = cand_lo, cand_hi
                t_lo = int(inner["touches_lo"])
                t_hi = int(inner["touches_hi"])
                inner_shelf = True
                inner_n_bars = int(inner["n_bars"])
                inner_payload = {
                    "lo": round(cand_lo, 3),
                    "hi": round(cand_hi, 3),
                    "touches_lo": int(t_lo),
                    "touches_hi": int(t_hi),
                    "since_date": inner.get("since_date"),
                    "n_bars": inner_n_bars,
                }
                since_date = inner["since_date"] or since_date
                cut = str(since_date) if since_date else None
                if cut and episode_pairs:
                    clipped, episode_mins, episode_maxs = _clip_daily(
                        episode_pairs, episode_mins, episode_maxs, cut,
                    )
                    if clipped:
                        episode_pairs = clipped
                        episode_values = [v for _, v in episode_pairs]
                bounds_mode = "calculated"
    width = max(0.0, hi - lo)

    inside_mask = [(lo - eps) <= v <= (hi + eps) for v in episode_values]
    dwell = _trailing_run(inside_mask)
    bounces = _count_bounces(episode_values, lo, hi)
    if inner_shelf:
        inner_pts = [v for _, v in _bar_points_until(bars, last_day)]
        if inner_pts:
            bounces = max(
                bounces,
                _count_bounces(inner_pts[-inner_n_bars:] or inner_pts, lo, hi),
            )

    span = max(width, 1e-9)
    pct_in = max(0.0, min(100.0, ((s_now - lo) / span) * 100.0))

    wide_ok = bounds_mode == "calculated" and width <= CORRIDOR_WIDE_MAX_WIDTH_PP
    adaptive_ok = bounds_mode == "adaptive" and width <= 1.8
    bounds_ok = wide_ok or adaptive_ok
    formed = (
        not broken
        and bounds_ok
        and t_lo >= 2
        and t_hi >= 2
        and dwell >= CORRIDOR_FORMED_MIN_DAYS
        and (bounds_mode == "calculated" or bounces >= 2)
        and not inner_shelf
    )
    inner_forming_ok = (
        inner_shelf
        and t_lo >= 2
        and t_hi >= 2
        and inner_n_bars >= CORRIDOR_INNER_MIN_BARS
    )
    forming = (
        not broken
        and not formed
        and bounds_ok
        and (t_lo >= 1 or t_hi >= 1)
        and (t_lo + t_hi >= 3 or bounces >= 2)
        and (dwell >= CORRIDOR_FORMING_MIN_DAYS or inner_forming_ok)
    )

    if broken and freeze_lo is not None and freeze_hi is not None:
        phase, label = "broken", "сломан"
        lo, hi = freeze_lo, freeze_hi
        width = max(0.0, hi - lo)
        span = max(width, 1e-9)
        pct_in = max(0.0, min(100.0, ((s_now - lo) / span) * 100.0))
        bounds_mode = "frozen"
    elif formed:
        phase, label = "formed", "сформирован"
    elif forming:
        phase, label = "forming", "формируется"
    else:
        phase, label = "none", "нет коридора"

    title = (
        f"Коридор {lo:.2f}…{hi:.2f}% (расчёт: {bounds_mode}; "
        f"{'текущая узкая полка' if inner_shelf else 'низ=кластер пола, верх=кластер потолка'}) · "
        f"касания низ {t_lo} · верх {t_hi} · отскоки {bounces} · "
        f"удержание {dwell}д"
    )
    if broken:
        title = (
            f"Коридор сломан · была полоса {lo:.2f}…{hi:.2f}% · "
            f"S сейчас {s_now:.2f}%"
            + (f" · день max {last_max:.2f}%" if last_max is not None else "")
        )

    pub_outer_lo = freeze_lo if broken and freeze_lo is not None else outer_lo
    pub_outer_hi = freeze_hi if broken and freeze_hi is not None else outer_hi
    if pub_outer_lo is None:
        pub_outer_lo = lo
    if pub_outer_hi is None:
        pub_outer_hi = hi

    return {
        "phase": phase,
        "label_ru": label,
        "lo": round(lo, 3),
        "hi": round(hi, 3),
        "width": round(width, 3),
        "spread": round(s_now, 3),
        "pct_in_band": round(pct_in, 1),
        "dwell_days": int(dwell),
        "bounces": int(bounces),
        "touches_lo": int(t_lo),
        "touches_hi": int(t_hi),
        "bounds_mode": bounds_mode,
        "shrink_days": 0,
        "n_days": len(episode_values),
        "last_date": last_day,
        "since_date": since_date,
        "broken_since": broken_since if broken else None,
        "inner_shelf": bool(inner_shelf),
        "outer_lo": round(pub_outer_lo, 3) if pub_outer_lo is not None else None,
        "outer_hi": round(pub_outer_hi, 3) if pub_outer_hi is not None else None,
        "inner": inner_payload,
        "title": title,
    }


_LOOKBACK_BARS_LOCK = threading.Lock()
_LOOKBACK_BARS: dict[str, Any] = {"ts": 0.0, "days": 0, "bars": []}
_LOOKBACK_BARS_TTL_SEC = 45.0

_DESK_CORR_LOCK = threading.Lock()
_DESK_CORR: dict[str, Any] = {
    "ts": 0.0,
    "n_days": 0,
    "last_date": None,
    "payload": None,
}
_DESK_CORR_TTL_SEC = 60.0
_DESK_CORR_WARMING = False


def _m15_bars_from_replay_db(need_days: int) -> list[dict[str, Any]]:
    """30д M15 из SQLite по дате — без второго снимка рынка и без полного индекса."""
    start = (datetime.now() - timedelta(days=int(need_days) + 2)).strftime("%Y-%m-%d")
    try:
        from replay.replay_db import load_bars_from_db

        raw = load_bars_from_db(start)
    except Exception:
        return []
    out: list[dict[str, Any]] = []
    for b in raw or []:
        if not isinstance(b, dict):
            continue
        sp = b.get("spreadPercent")
        if sp is None:
            sp = b.get("spread")
        out.append(
            {
                "time": b.get("tradeDate") or b.get("time"),
                "timestampMs": b.get("timestampMs"),
                "spread": sp,
                "z": b.get("zScore") if b.get("zScore") is not None else b.get("z"),
            }
        )
    return out


def corridor_m15_bars_for_desk(mkt: dict[str, Any], *, chart_days: int) -> list[dict[str, Any]]:
    """Ряд для коридора стола: всегда окно lookback (30д), не чип графика 7д."""
    need_days = max(CORRIDOR_LOOKBACK_DAYS, int(chart_days or 7))
    now = time.time()
    with _LOOKBACK_BARS_LOCK:
        cached = _LOOKBACK_BARS
        if (
            cached["bars"]
            and int(cached["days"] or 0) >= need_days
            and (now - float(cached["ts"] or 0)) < _LOOKBACK_BARS_TTL_SEC
        ):
            return list(cached["bars"])
    bars = _m15_bars_from_replay_db(need_days)
    n = len(daily_spreads_from_bars(bars))
    if n >= CORRIDOR_MIN_DAYS:
        with _LOOKBACK_BARS_LOCK:
            _LOOKBACK_BARS["ts"] = time.time()
            _LOOKBACK_BARS["days"] = need_days
            _LOOKBACK_BARS["bars"] = bars
        return bars
    fallback = list(mkt.get("bars") or [])
    if len(daily_spreads_from_bars(fallback)) > n:
        return fallback
    return bars or fallback


def corridor_history(
    daily: list[tuple[str, float]],
    *,
    bars: list[dict[str, Any]] | None = None,
    lo_sanity: float = CORRIDOR_LO_SANITY,
    hi_sanity: float = CORRIDOR_HI_SANITY,
    allow_inner: bool = True,
) -> list[dict[str, Any]]:
    _, day_mins, day_maxs = daily_spread_extremes_from_bars(bars or [])
    bar_spreads = spreads_from_bars(bars or [])
    hist: list[dict[str, Any]] = []
    hist_min = CORRIDOR_FORMING_MIN_DAYS
    segment_id = 0
    prev_since: str | None = None
    prev_lo: float | None = None
    prev_hi: float | None = None
    for i in range(hist_min, len(daily) + 1):
        chunk = daily[:i]
        vals = [v for _, v in chunk]
        chunk_mins = [(d, v) for d, v in day_mins if d <= chunk[-1][0]]
        chunk_maxs = [(d, v) for d, v in day_maxs if d <= chunk[-1][0]]
        c = detect_spread_corridor(
            chunk,
            spread_now=vals[-1],
            bar_spreads=bar_spreads,
            touch_mins=chunk_mins,
            touch_maxs=chunk_maxs,
            bars=bars,
            allow_inner=allow_inner,
            lo_sanity=lo_sanity,
            hi_sanity=hi_sanity,
            min_days=hist_min,
        )
        phase = str(c.get("phase") or "")
        if phase not in ("forming", "formed"):
            continue
        lo, hi = c.get("lo"), c.get("hi")
        if lo is None or hi is None:
            continue
        since = c.get("since_date")
        jumped = (
            prev_lo is None
            or (since and prev_since and str(since) != str(prev_since))
            or abs(float(lo) - float(prev_lo)) > CORRIDOR_SEGMENT_JUMP_PP
            or abs(float(hi) - float(prev_hi or hi)) > CORRIDOR_SEGMENT_JUMP_PP
        )
        if jumped:
            segment_id += 1
        prev_since = str(since) if since else prev_since
        prev_lo, prev_hi = float(lo), float(hi)
        hist.append({
            "date": chunk[-1][0],
            "lo": lo,
            "hi": hi,
            "phase": phase,
            "segment_id": segment_id,
            "since_date": since,
        })
    return hist


def rolling_corridor_history(
    bars: list[dict[str, Any]],
    *,
    lookback_days: int = CORRIDOR_LOOKBACK_DAYS,
    lo_sanity: float = CORRIDOR_LO_SANITY,
    hi_sanity: float = CORRIDOR_HI_SANITY,
) -> list[dict[str, Any]]:
    """История всех коридоров для Теста, а не только последнего низкого режима.

    На каждый торговый день границы рассчитываются без знания будущего по
    скользящему окну. ``segment_id`` не даёт графику соединять разные эпизоды.
    """
    daily, day_mins, day_maxs = daily_spread_extremes_from_bars(bars)
    if not daily:
        return []
    by_min = dict(day_mins)
    by_max = dict(day_maxs)
    window = max(CORRIDOR_MIN_DAYS, int(lookback_days or CORRIDOR_LOOKBACK_DAYS))
    hist: list[dict[str, Any]] = []
    segment_id = 0
    in_segment = False

    for i, (day, spread) in enumerate(daily):
        start = max(0, i - window + 1)
        chunk = daily[start : i + 1]
        chunk_mins = [(d, by_min[d]) for d, _ in chunk if d in by_min]
        chunk_maxs = [(d, by_max[d]) for d, _ in chunk if d in by_max]
        c = detect_spread_corridor(
            [float(v) for _, v in chunk],
            spread_now=float(spread),
            touch_mins=chunk_mins,
            touch_maxs=chunk_maxs,
            bars=bars,
            lo_sanity=lo_sanity,
            hi_sanity=hi_sanity,
            min_days=CORRIDOR_FORMING_MIN_DAYS,
        )
        phase = str(c.get("phase") or "")
        lo, hi = c.get("lo"), c.get("hi")
        active = phase in ("forming", "formed") and lo is not None and hi is not None
        if not active:
            in_segment = False
            continue
        if not in_segment:
            segment_id += 1
            in_segment = True
        elif hist:
            prev_lo = float(hist[-1]["lo"])
            prev_hi = float(hist[-1]["hi"])
            if (
                abs(float(lo) - prev_lo) > CORRIDOR_SEGMENT_JUMP_PP
                or abs(float(hi) - prev_hi) > CORRIDOR_SEGMENT_JUMP_PP
            ):
                segment_id += 1
        hist.append({
            "date": day,
            "lo": lo,
            "hi": hi,
            "phase": phase,
            "segment_id": segment_id,
        })
    return hist


def desk_corridor_payload(
    bars: list[dict[str, Any]],
    *,
    spread_now: float | None = None,
    lo_sanity: float = CORRIDOR_LO_SANITY,
    hi_sanity: float = CORRIDOR_HI_SANITY,
    allow_inner: bool = True,
) -> dict[str, Any]:
    daily, day_mins, day_maxs = daily_spread_extremes_from_bars(bars)
    bar_spreads = spreads_from_bars(bars)
    payload = detect_spread_corridor(
        daily,
        spread_now=spread_now,
        bar_spreads=bar_spreads,
        touch_mins=day_mins,
        touch_maxs=day_maxs,
        bars=bars,
        allow_inner=allow_inner,
        lo_sanity=lo_sanity,
        hi_sanity=hi_sanity,
    )
    payload["history"] = corridor_history(
        daily,
        bars=bars,
        lo_sanity=lo_sanity,
        hi_sanity=hi_sanity,
        allow_inner=allow_inner,
    )
    return payload


def _with_live_spread(payload: dict[str, Any], spread_now: float | None) -> dict[str, Any]:
    if spread_now is None:
        return payload
    out = dict(payload)
    s = float(spread_now)
    out["spread"] = round(s, 3)
    lo, hi = out.get("lo"), out.get("hi")
    if lo is not None and hi is not None:
        span = max(float(hi) - float(lo), 1e-9)
        out["pct_in_band"] = round(
            max(0.0, min(100.0, ((s - float(lo)) / span) * 100.0)), 1
        )
    return out


def kick_desk_corridor_warm(
    mkt: dict[str, Any],
    *,
    chart_days: int,
    spread_now: float | None = None,
) -> None:
    """Фон: полный канон 30д, чтобы lite не считал полку в запросе."""
    global _DESK_CORR_WARMING
    with _DESK_CORR_LOCK:
        if _DESK_CORR_WARMING:
            return
        _DESK_CORR_WARMING = True

    def _run() -> None:
        global _DESK_CORR_WARMING
        try:
            desk_corridor_payload_for_desk(
                mkt,
                chart_days=max(CORRIDOR_LOOKBACK_DAYS, int(chart_days or 7)),
                spread_now=spread_now,
                lite=False,
            )
        except Exception:
            pass
        finally:
            with _DESK_CORR_LOCK:
                _DESK_CORR_WARMING = False

    threading.Thread(target=_run, name="desk-corr-warm", daemon=True).start()


def desk_corridor_payload_for_desk(
    mkt: dict[str, Any],
    *,
    chart_days: int,
    spread_now: float | None = None,
    lo_sanity: float = CORRIDOR_LO_SANITY,
    hi_sanity: float = CORRIDOR_HI_SANITY,
    lite: bool = False,
) -> dict[str, Any]:
    """Канон Ситуации/шапки: 30д без узкой полки и без урезанного чипа 7д."""
    now = time.time()
    with _DESK_CORR_LOCK:
        cached = _DESK_CORR.get("payload")
        cache_n = int(_DESK_CORR.get("n_days") or 0)
        cache_age = now - float(_DESK_CORR.get("ts") or 0.0)
    if lite:
        if isinstance(cached, dict) and cache_n >= CORRIDOR_MIN_DAYS:
            return _with_live_spread(cached, spread_now)
        kick_desk_corridor_warm(mkt, chart_days=chart_days, spread_now=spread_now)
        return {"phase": None, "warming": True, "ok": True}

    bars = corridor_m15_bars_for_desk(mkt, chart_days=chart_days)
    daily = daily_spreads_from_bars(bars)
    n = len(daily)
    last_date = daily[-1][0] if daily else None
    with _DESK_CORR_LOCK:
        cached = _DESK_CORR.get("payload")
        cache_n = int(_DESK_CORR.get("n_days") or 0)
        cache_age = now - float(_DESK_CORR.get("ts") or 0.0)
        if cached and cache_n >= CORRIDOR_LOOKBACK_DAYS and n < CORRIDOR_LOOKBACK_DAYS:
            return _with_live_spread(cached, spread_now)
        if (
            cached
            and cache_age < _DESK_CORR_TTL_SEC
            and _DESK_CORR.get("last_date") == last_date
            and cache_n == n
        ):
            return _with_live_spread(cached, spread_now)
    payload = desk_corridor_payload(
        bars,
        spread_now=spread_now,
        lo_sanity=lo_sanity,
        hi_sanity=hi_sanity,
        allow_inner=False,
    )
    if n >= CORRIDOR_MIN_DAYS:
        with _DESK_CORR_LOCK:
            _DESK_CORR["ts"] = time.time()
            _DESK_CORR["n_days"] = n
            _DESK_CORR["last_date"] = last_date
            _DESK_CORR["payload"] = payload
    return payload
