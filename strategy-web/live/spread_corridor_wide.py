"""Широкий коридор спреда: полка 15–40д и структурный вынос.

Отдельный расчёт рядом с узким детектором стола. Не меняет пороги AUTO
(Long 3.2/4.0, Short 6.1/5.8). На живой стол не подключать, пока явно
не попросят.
"""

from __future__ import annotations

import statistics
import time
from typing import Any

from live.spread_corridor import (
    _percentile,
    daily_spread_extremes_from_bars,
)

# Окно полки: 5–8 недель торгов; чуть длиннее не крошим, если режим тот же.
WIDE_MIN_DAYS = 15
WIDE_MAX_DAYS = 40
WIDE_HARD_MAX_DAYS = 45
# Вынос ломает режим: не дневной шип 0,2 п.п.
WIDE_BREAK_PP = 0.85
WIDE_BREAK_HOLD_DAYS = 2
WIDE_LEVEL_LOOKBACK = 20
WIDE_REJOIN_FRAC = 0.65
# Границы — перцентили дневных min/max, пол без отсечки 0,3.
WIDE_LO_PERCENTILE = 15
WIDE_HI_PERCENTILE = 75
WIDE_MIN_WIDTH_PP = 0.40
WIDE_MAX_WIDTH_PP = 1.45
WIDE_SPLIT_IDEAL_DAYS = 32
WIDE_TOUCH_EPS_PP = 0.25


def _pct(vals: list[float], pct: float) -> float:
    return _percentile(sorted(vals), pct)


def _aligned_rows(
    daily: list[tuple[str, float]] | list[float],
    touch_mins: list[tuple[str, float]] | None,
    touch_maxs: list[tuple[str, float]] | None,
) -> list[dict[str, Any]]:
    day_mins = list(touch_mins or [])
    day_maxs = list(touch_maxs or [])
    by_med: dict[str, float] = {}
    if daily and isinstance(daily[0], tuple):
        by_med = {str(d): float(v) for d, v in daily}  # type: ignore[misc]
    by_min = {str(d): float(v) for d, v in day_mins}
    by_max = {str(d): float(v) for d, v in day_maxs}
    dates = sorted(set(by_med) | set(by_min) | set(by_max))
    rows: list[dict[str, Any]] = []
    for d in dates:
        mn = by_min.get(d)
        mx = by_max.get(d)
        med = by_med.get(d)
        if mn is None and mx is None and med is None:
            continue
        if med is None:
            parts = [x for x in (mn, mx) if x is not None]
            med = statistics.median(parts) if parts else 0.0
        if mn is None:
            mn = float(med)
        if mx is None:
            mx = float(med)
        rows.append({"date": d, "med": float(med), "mn": float(mn), "mx": float(mx)})
    return rows


def _bounds(
    rows: list[dict[str, Any]],
    *,
    lo_pct: float = WIDE_LO_PERCENTILE,
    hi_pct: float = WIDE_HI_PERCENTILE,
) -> tuple[float, float]:
    mins = [float(r["mn"]) for r in rows]
    maxs = [float(r["mx"]) for r in rows]
    lo = _pct(mins, lo_pct) if mins else 0.0
    hi = _pct(maxs, hi_pct) if maxs else 0.0
    if hi <= lo:
        hi = lo + 0.5
    return lo, hi


def _prior_level(prev: list[dict[str, Any]], n: int = WIDE_LEVEL_LOOKBACK) -> float:
    if not prev:
        return 0.0
    take = prev[-n:]
    return float(statistics.median([float(r["med"]) for r in take]))


def _prior_width(prev: list[dict[str, Any]], n: int = WIDE_LEVEL_LOOKBACK) -> float:
    if len(prev) < 8:
        return WIDE_BREAK_PP
    lo, hi = _bounds(prev[-n:])
    return max(0.55, hi - lo)


def _break_threshold(width: float) -> float:
    """Порог выноса: 0,8–1,0 п.п. или порядка ширины полки."""
    return min(1.00, max(WIDE_BREAK_PP, 0.90 * float(width)))


def find_structural_excursions(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Вынос вверх, который ломает режим и держится не один день."""
    out: list[dict[str, Any]] = []
    i = 0
    n = len(rows)
    look = WIDE_LEVEL_LOOKBACK
    hold_need = WIDE_BREAK_HOLD_DAYS
    while i < n:
        prev = rows[max(0, i - look) : i]
        level = _prior_level(prev, look)
        width = _prior_width(prev, look)
        thr = _break_threshold(width)
        if float(rows[i]["mx"]) >= level + thr:
            j = i + 1
            # Удержание выноса: ещё над полкой, не «обычный» потолок боковика.
            back = level + max(0.55, WIDE_REJOIN_FRAC * thr)
            while j < n and float(rows[j]["mx"]) >= back:
                j += 1
            hold_n = j - i
            peak = max(rows[i:j], key=lambda r: float(r["mx"]))
            if hold_n >= hold_need and float(peak["mx"]) >= level + thr:
                out.append(
                    {
                        "from": rows[i]["date"],
                        "to": rows[j - 1]["date"],
                        "peak_date": peak["date"],
                        "peak": round(float(peak["mx"]), 3),
                        "level": round(level, 3),
                        "threshold": round(thr, 3),
                        "hold_days": hold_n,
                        "i0": i,
                        "i1": j - 1,
                    }
                )
                i = j
                continue
        i += 1
    return out


def _split_score(
    left: list[dict[str, Any]],
    right: list[dict[str, Any]],
) -> tuple[float, float]:
    llo, lhi = _bounds(left)
    rlo, rhi = _bounds(right)
    lmid = (llo + lhi) / 2.0
    rmid = (rlo + rhi) / 2.0
    shift = abs(lmid - rmid) + abs(lhi - rhi) + abs(llo - rlo)
    ideal = WIDE_SPLIT_IDEAL_DAYS
    bal = abs(len(left) - ideal) + 0.35 * abs(
        min(len(right), WIDE_MAX_DAYS) - ideal
    )
    width_pen = 0.0
    lw, rw = lhi - llo, rhi - rlo
    if not (WIDE_MIN_WIDTH_PP <= lw <= WIDE_MAX_WIDTH_PP):
        width_pen += 0.4
    if not (WIDE_MIN_WIDTH_PP <= rw <= WIDE_MAX_WIDTH_PP):
        width_pen += 0.4
    return shift - 0.045 * bal - width_pen, shift


def _split_long_calm(
    rows: list[dict[str, Any]],
    *,
    min_days: int = WIDE_MIN_DAYS,
    hard_max: int = WIDE_HARD_MAX_DAYS,
) -> list[list[dict[str, Any]]]:
    n = len(rows)
    if n < min_days:
        return []
    if n <= hard_max:
        return [rows]
    best_i: int | None = None
    best_score: float | None = None
    for i in range(min_days, n - min_days + 1):
        score, _shift = _split_score(rows[:i], rows[i:])
        if best_score is None or score > best_score:
            best_score = score
            best_i = i
    if best_i is None:
        return [rows[:WIDE_MAX_DAYS]]
    out: list[list[dict[str, Any]]] = []
    left, right = rows[:best_i], rows[best_i:]
    if len(left) > hard_max:
        out.extend(_split_long_calm(left, min_days=min_days, hard_max=hard_max))
    elif len(left) >= min_days:
        out.append(left)
    if len(right) > hard_max:
        out.extend(_split_long_calm(right, min_days=min_days, hard_max=hard_max))
    elif len(right) >= min_days:
        out.append(right)
    return out


def _count_touches(rows: list[dict[str, Any]], lo: float, hi: float) -> tuple[int, int]:
    eps = WIDE_TOUCH_EPS_PP
    t_lo = sum(1 for r in rows if abs(float(r["mn"]) - lo) <= eps or float(r["mn"]) <= lo)
    t_hi = sum(1 for r in rows if abs(float(r["mx"]) - hi) <= eps or float(r["mx"]) >= hi)
    return t_lo, t_hi


def _shelf_dict(chunk: list[dict[str, Any]]) -> dict[str, Any] | None:
    lo, hi = _bounds(chunk)
    width = hi - lo
    if width > WIDE_MAX_WIDTH_PP or width < WIDE_MIN_WIDTH_PP:
        return None
    t_lo, t_hi = _count_touches(chunk, lo, hi)
    return {
        "from": chunk[0]["date"],
        "to": chunk[-1]["date"],
        "n_days": len(chunk),
        "lo": round(lo, 3),
        "hi": round(hi, 3),
        "width": round(width, 3),
        "touches_lo": int(t_lo),
        "touches_hi": int(t_hi),
        "phase": "formed",
        "bounds_mode": "wide",
    }


def _stamp_shelf_visible_from(
    shelves: list[dict[str, Any]],
    seen: dict[str, str],
    as_of: str,
) -> list[dict[str, Any]]:
    """Дата узнавания: первый as_of, когда полка уже есть. Не начало окна 15д."""
    out: list[dict[str, Any]] = []
    day = str(as_of or "")[:10]
    for s in shelves:
        key = str(s.get("from") or "")[:10]
        if key and key not in seen:
            seen[key] = day
        item = dict(s)
        if key and key in seen:
            item["visible_from"] = seen[key]
        out.append(item)
    return out


def segment_wide_shelves(
    daily: list[tuple[str, float]] | list[float],
    *,
    touch_mins: list[tuple[str, float]] | None = None,
    touch_maxs: list[tuple[str, float]] | None = None,
    bars: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Полки и структурные выносы по дневному ряду (пик в полку не входит)."""
    if bars and not (touch_mins and touch_maxs):
        daily_b, mins_b, maxs_b = daily_spread_extremes_from_bars(bars)
        if not daily:
            daily = daily_b
        if not touch_mins:
            touch_mins = mins_b
        if not touch_maxs:
            touch_maxs = maxs_b
    rows = _aligned_rows(daily, touch_mins, touch_maxs)
    excursions = find_structural_excursions(rows)
    cut: set[int] = set()
    for run in excursions:
        for i in range(int(run["i0"]), int(run["i1"]) + 1):
            cut.add(i)

    pieces: list[list[dict[str, Any]]] = []
    buf: list[dict[str, Any]] = []
    for i, row in enumerate(rows):
        if i in cut:
            if buf:
                pieces.append(buf)
                buf = []
            continue
        buf.append(row)
    if buf:
        pieces.append(buf)

    shelves: list[dict[str, Any]] = []
    for piece in pieces:
        for chunk in _split_long_calm(piece):
            item = _shelf_dict(chunk)
            if item is not None:
                shelves.append(item)

    return {
        "shelves": shelves,
        "excursions": [
            {k: v for k, v in e.items() if k not in ("i0", "i1")}
            for e in excursions
        ],
        "n_days": len(rows),
        "first_date": rows[0]["date"] if rows else None,
        "last_date": rows[-1]["date"] if rows else None,
    }


def segment_wide_shelves_from_bars(bars: list[dict[str, Any]]) -> dict[str, Any]:
    return segment_wide_shelves([], bars=bars)


def _empty_detect(spread_now: float | None) -> dict[str, Any]:
    return {
        "phase": "none",
        "label_ru": "нет широкой полки",
        "lo": None,
        "hi": None,
        "width": None,
        "spread": spread_now,
        "n_days": 0,
        "since_date": None,
        "broken_since": None,
        "bounds_mode": "none",
        "touches_lo": 0,
        "touches_hi": 0,
        "title": "Мало дней для широкой полки",
        "kind": "wide",
    }


def _phase_from_seg(
    seg: dict[str, Any],
    *,
    last: str,
    s_now: float,
    n_days: int,
) -> dict[str, Any]:
    """Фаза на последнем дне уже посчитанного разреза. Без заглядывания вперёд."""
    empty = _empty_detect(s_now)
    empty["n_days"] = n_days
    empty["last_date"] = last
    empty["spread"] = round(s_now, 3)
    for s in reversed(seg.get("shelves") or []):
        if str(s["from"]) <= last <= str(s["to"]):
            span = max(float(s["width"]), 1e-9)
            pct_in = max(0.0, min(100.0, ((s_now - float(s["lo"])) / span) * 100.0))
            return {
                "phase": "formed",
                "label_ru": "широкая полка",
                "lo": s["lo"],
                "hi": s["hi"],
                "width": s["width"],
                "spread": round(s_now, 3),
                "pct_in_band": round(pct_in, 1),
                "n_days": s["n_days"],
                "since_date": s["from"],
                "broken_since": None,
                "bounds_mode": "wide",
                "touches_lo": s["touches_lo"],
                "touches_hi": s["touches_hi"],
                "last_date": last,
                "kind": "wide",
                "title": (
                    f"Широкая полка {s['lo']:.2f}…{s['hi']:.2f}% · "
                    f"{s['from']}…{s['to']} · {s['n_days']}д"
                ),
            }

    prev_shelf: dict[str, Any] | None = None
    for s in seg.get("shelves") or []:
        if str(s["to"]) < last:
            prev_shelf = s
    for e in seg.get("excursions") or []:
        if str(e["from"]) <= last <= str(e["to"]):
            lo = prev_shelf["lo"] if prev_shelf else None
            hi = prev_shelf["hi"] if prev_shelf else None
            width = prev_shelf["width"] if prev_shelf else None
            return {
                "phase": "broken",
                "label_ru": "структурный вынос",
                "lo": lo,
                "hi": hi,
                "width": width,
                "spread": round(s_now, 3),
                "n_days": int(e.get("hold_days") or 0),
                "since_date": prev_shelf["from"] if prev_shelf else None,
                "broken_since": e["from"],
                "last_date": last,
                "kind": "wide",
                "bounds_mode": "frozen" if prev_shelf else "none",
                "touches_lo": 0,
                "touches_hi": 0,
                "peak_date": e.get("peak_date"),
                "title": (
                    f"Структурный вынос · пик {e.get('peak_date')} "
                    f"{e.get('peak')}% · с {e['from']}"
                ),
            }
    return empty


def detect_spread_corridor_wide(
    daily: list[tuple[str, float]] | list[float],
    *,
    spread_now: float | None = None,
    touch_mins: list[tuple[str, float]] | None = None,
    touch_maxs: list[tuple[str, float]] | None = None,
    bars: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Точка «сегодня»: широкая полка или вынос. Без узкой подмены 1–2 дня."""
    if bars and not (touch_mins and touch_maxs):
        daily_b, mins_b, maxs_b = daily_spread_extremes_from_bars(bars)
        if not daily:
            daily = daily_b
        if not touch_mins:
            touch_mins = mins_b
        if not touch_maxs:
            touch_maxs = maxs_b
    rows = _aligned_rows(daily, touch_mins, touch_maxs)
    empty = _empty_detect(spread_now)
    if len(rows) < WIDE_MIN_DAYS:
        empty["n_days"] = len(rows)
        empty["last_date"] = rows[-1]["date"] if rows else None
        return empty

    s_now = float(spread_now) if spread_now is not None else float(rows[-1]["med"])
    last = str(rows[-1]["date"])
    seg = segment_wide_shelves(
        daily, touch_mins=touch_mins, touch_maxs=touch_maxs, bars=bars,
    )
    return _phase_from_seg(seg, last=last, s_now=s_now, n_days=len(rows))


def _slice_extremes_as_of(
    daily: list[tuple[str, float]],
    touch_mins: list[tuple[str, float]] | None,
    touch_maxs: list[tuple[str, float]] | None,
    as_of: str,
) -> tuple[list[tuple[str, float]], list[tuple[str, float]], list[tuple[str, float]]]:
    cut = str(as_of or "")[:10]
    d = [(a, b) for a, b in daily if str(a)[:10] <= cut]
    mn = [(a, b) for a, b in (touch_mins or []) if str(a)[:10] <= cut]
    mx = [(a, b) for a, b in (touch_maxs or []) if str(a)[:10] <= cut]
    return d, mn, mx


def wide_corridor_history(
    daily: list[tuple[str, float]],
    *,
    touch_mins: list[tuple[str, float]] | None = None,
    touch_maxs: list[tuple[str, float]] | None = None,
    bars: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    """История широких полок (без скользящих 30д и без узкой 1–2д подмены)."""
    seg = segment_wide_shelves(
        daily, touch_mins=touch_mins, touch_maxs=touch_maxs, bars=bars,
    )
    rows = _aligned_rows(daily, touch_mins, touch_maxs)
    hist: list[dict[str, Any]] = []
    for sid, s in enumerate(seg["shelves"], start=1):
        for row in rows:
            d = str(row["date"])
            if str(s["from"]) <= d <= str(s["to"]):
                hist.append(
                    {
                        "date": d,
                        "lo": s["lo"],
                        "hi": s["hi"],
                        "phase": "formed",
                        "segment_id": sid,
                        "since_date": s["from"],
                    }
                )
    return hist


def _compact_causal_entry(payload: dict[str, Any], as_of: str) -> dict[str, Any]:
    return {
        "as_of": as_of,
        "kind": "wide",
        "phase": payload.get("phase"),
        "lo": payload.get("lo"),
        "hi": payload.get("hi"),
        "width": payload.get("width"),
        "since_date": payload.get("since_date"),
        "broken_since": payload.get("broken_since"),
        "last_date": payload.get("last_date") or as_of,
        "n_days": payload.get("n_days"),
        "label_ru": payload.get("label_ru"),
        "title": payload.get("title"),
        "shelves": payload.get("shelves") or [],
        "excursions": payload.get("excursions") or [],
    }


def wide_payload_from_extremes(
    daily: list[tuple[str, float]],
    *,
    touch_mins: list[tuple[str, float]] | None = None,
    touch_maxs: list[tuple[str, float]] | None = None,
    spread_now: float | None = None,
    with_history: bool = True,
) -> dict[str, Any]:
    """Один разрез: фаза + полки. Только по переданному ряду, без будущего."""
    rows = _aligned_rows(daily, touch_mins, touch_maxs)
    empty = _empty_detect(spread_now)
    empty["history"] = []
    empty["shelves"] = []
    empty["excursions"] = []
    if not rows:
        return empty
    s_now = float(spread_now) if spread_now is not None else float(rows[-1]["med"])
    last = str(rows[-1]["date"])
    if len(rows) < WIDE_MIN_DAYS:
        empty["n_days"] = len(rows)
        empty["last_date"] = last
        empty["spread"] = round(s_now, 3)
        return empty
    d = [(r["date"], r["med"]) for r in rows]
    mn = [(r["date"], r["mn"]) for r in rows]
    mx = [(r["date"], r["mx"]) for r in rows]
    seg = segment_wide_shelves(d, touch_mins=mn, touch_maxs=mx)
    payload = _phase_from_seg(seg, last=last, s_now=s_now, n_days=len(rows))
    payload["shelves"] = seg["shelves"]
    payload["excursions"] = seg["excursions"]
    payload["kind"] = "wide"
    if with_history:
        payload["history"] = wide_corridor_history(d, touch_mins=mn, touch_maxs=mx)
    else:
        payload["history"] = []
    return payload


def desk_wide_corridor_as_of(
    daily: list[tuple[str, float]],
    *,
    touch_mins: list[tuple[str, float]] | None = None,
    touch_maxs: list[tuple[str, float]] | None = None,
    as_of: str,
    spread_now: float | None = None,
    with_history: bool = True,
) -> dict[str, Any]:
    """Детектор на днях ≤ as_of. Уровни не зависят от баров после этой даты."""
    d, mn, mx = _slice_extremes_as_of(daily, touch_mins, touch_maxs, as_of)
    payload = wide_payload_from_extremes(
        d, touch_mins=mn, touch_maxs=mx, spread_now=spread_now, with_history=with_history,
    )
    payload["as_of"] = str(as_of)[:10]
    payload["kind"] = "wide"
    return payload


def _shelves_on_prefix(
    rows: list[dict[str, Any]],
    excursions: list[dict[str, Any]],
    last_i: int,
) -> list[dict[str, Any]]:
    """Полки на днях 0…last_i по уже найденным выносам. Без будущего."""
    if last_i < 0 or not rows:
        return []
    cut: set[int] = set()
    for run in excursions:
        i0 = int(run["i0"])
        i1 = int(run["i1"])
        if i1 < 0 or i0 > last_i:
            continue
        for k in range(i0, min(i1, last_i) + 1):
            cut.add(k)
    pieces: list[list[dict[str, Any]]] = []
    buf: list[dict[str, Any]] = []
    for k in range(0, last_i + 1):
        if k in cut:
            if buf:
                pieces.append(buf)
                buf = []
            continue
        buf.append(rows[k])
    if buf:
        pieces.append(buf)
    shelves: list[dict[str, Any]] = []
    for piece in pieces:
        for chunk in _split_long_calm(piece):
            item = _shelf_dict(chunk)
            if item is not None:
                shelves.append(item)
    return shelves


def causal_wide_pack_from_extremes(
    daily: list[tuple[str, float]],
    *,
    touch_mins: list[tuple[str, float]] | None = None,
    touch_maxs: list[tuple[str, float]] | None = None,
    deadline_mono: float | None = None,
) -> dict[str, Any]:
    """Индекс «на каждый день»: один проход, закрытые куски не пересчитываем.

    Раньше на каждый день заново гоняли детектор по всему префиксу — на 3 годах
    стол переставал отвечать. Закрытые выносом полки замораживаем; открытый
    хвост режем только на текущем куске. deadline_mono — выйти с тем, что успели.
    """
    rows = _aligned_rows(daily, touch_mins, touch_maxs)
    by_date: dict[str, dict[str, Any]] = {}
    frozen_shelves: list[dict[str, Any]] = []
    frozen_excursions: list[dict[str, Any]] = []
    last_closed_end = -1
    timed_out = False
    seen_visible: dict[str, str] = {}
    for i, row in enumerate(rows):
        if deadline_mono is not None and time.monotonic() >= deadline_mono:
            timed_out = True
            break
        as_of = str(row["date"])
        s_now = float(row["med"])
        prefix_n = i + 1
        if prefix_n < WIDE_MIN_DAYS:
            empty = _empty_detect(s_now)
            empty["n_days"] = prefix_n
            empty["last_date"] = as_of
            empty["spread"] = round(s_now, 3)
            empty["shelves"] = []
            empty["excursions"] = []
            by_date[as_of] = _compact_causal_entry(empty, as_of)
            continue
        prefix = rows[:prefix_n]
        if last_closed_end < 0:
            excursions = find_structural_excursions(prefix)
        else:
            look = WIDE_LEVEL_LOOKBACK
            start = last_closed_end + 1
            scan_from = max(0, start - look)
            tail = find_structural_excursions(prefix[scan_from:])
            extra: list[dict[str, Any]] = []
            for e in tail:
                i0 = int(e["i0"]) + scan_from
                i1 = int(e["i1"]) + scan_from
                if i0 >= start:
                    ne = dict(e)
                    ne["i0"] = i0
                    ne["i1"] = i1
                    extra.append(ne)
            excursions = frozen_excursions + extra
        new_closed = last_closed_end
        for run in excursions:
            i1 = int(run["i1"])
            if i1 < i and i1 > new_closed:
                new_closed = i1
        if new_closed > last_closed_end:
            frozen_shelves = _shelves_on_prefix(prefix, excursions, new_closed)
            frozen_excursions = [e for e in excursions if int(e["i1"]) <= new_closed]
            last_closed_end = new_closed
        open_cut: set[int] = set()
        for run in excursions:
            i0 = int(run["i0"])
            i1 = int(run["i1"])
            if i1 <= last_closed_end:
                continue
            for k in range(max(i0, last_closed_end + 1), i1 + 1):
                open_cut.add(k)
        open_calm = [
            rows[k]
            for k in range(last_closed_end + 1, prefix_n)
            if k not in open_cut
        ]
        open_shelves: list[dict[str, Any]] = []
        for chunk in _split_long_calm(open_calm):
            item = _shelf_dict(chunk)
            if item is not None:
                open_shelves.append(item)
        shelves = _stamp_shelf_visible_from(
            frozen_shelves + open_shelves, seen_visible, as_of,
        )
        exc_out = [
            {k: v for k, v in e.items() if k not in ("i0", "i1")}
            for e in excursions
        ]
        seg = {
            "shelves": shelves,
            "excursions": exc_out,
            "n_days": prefix_n,
            "first_date": prefix[0]["date"],
            "last_date": as_of,
        }
        payload = _phase_from_seg(seg, last=as_of, s_now=s_now, n_days=prefix_n)
        payload["shelves"] = shelves
        payload["excursions"] = exc_out
        by_date[as_of] = _compact_causal_entry(payload, as_of)
    out = {
        "kind": "wide",
        "causal": True,
        "daily": [
            {
                "date": r["date"],
                "med": round(float(r["med"]), 4),
                "mn": round(float(r["mn"]), 4),
                "mx": round(float(r["mx"]), 4),
            }
            for r in rows
        ],
        "by_date": by_date,
    }
    if timed_out:
        out["partial"] = True
    return out


def desk_wide_corridor_payload(
    bars: list[dict[str, Any]],
    *,
    spread_now: float | None = None,
    as_of: str | None = None,
    causal: bool = False,
) -> dict[str, Any]:
    """Поле для графика Теста. causal=True — индекс по дням для сима без будущего."""
    daily, day_mins, day_maxs = daily_spread_extremes_from_bars(bars)
    if causal:
        return causal_wide_pack_from_extremes(
            daily, touch_mins=day_mins, touch_maxs=day_maxs,
        )
    if as_of:
        return desk_wide_corridor_as_of(
            daily,
            touch_mins=day_mins,
            touch_maxs=day_maxs,
            as_of=as_of,
            spread_now=spread_now,
        )
    payload = wide_payload_from_extremes(
        daily,
        touch_mins=day_mins,
        touch_maxs=day_maxs,
        spread_now=spread_now,
        with_history=True,
    )
    payload["kind"] = "wide"
    return payload
