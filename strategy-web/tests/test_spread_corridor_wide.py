"""Широкая полка + структурный вынос (не Prod AUTO, не стол)."""

from __future__ import annotations

import csv
from collections import defaultdict
from datetime import date
from pathlib import Path

import pytest

from live.spread_corridor import daily_spread_extremes_from_bars
from live.spread_corridor_wide import (
    WIDE_HARD_MAX_DAYS,
    WIDE_MAX_DAYS,
    WIDE_MIN_DAYS,
    causal_wide_pack_from_extremes,
    desk_wide_corridor_payload,
    detect_spread_corridor_wide,
    segment_wide_shelves,
    segment_wide_shelves_from_bars,
    wide_payload_from_extremes,
)

CSV_1095 = Path(__file__).resolve().parents[1] / "data" / "m15_tatn_1095d.csv"

EYE = (
    ("2023-11-15", "2024-01-03"),
    ("2024-01-04", "2024-02-28"),
    ("2024-03-20", "2024-05-15"),
)
WIN_LO, WIN_HI = "2023-11-01", "2024-05-15"


def _days(vals: list[float], start: str = "2026-01-01") -> list[tuple[str, float]]:
    y, m, d = (int(x) for x in start.split("-"))
    cur = date(y, m, d)
    out: list[tuple[str, float]] = []
    for v in vals:
        while cur.weekday() >= 5:
            cur = date.fromordinal(cur.toordinal() + 1)
        out.append((cur.isoformat(), v))
        cur = date.fromordinal(cur.toordinal() + 1)
    return out


def _ping_pong(n: int, lo: float, hi: float) -> list[float]:
    vals: list[float] = []
    for i in range(n):
        vals.append(lo if i % 2 == 0 else hi)
    return vals


def _cal_overlap(a0: str, a1: str, b0: str, b1: str) -> int:
    lo, hi = max(a0, b0), min(a1, b1)
    if lo > hi:
        return 0
    d0 = date.fromisoformat(lo)
    d1 = date.fromisoformat(hi)
    return (d1 - d0).days + 1


def test_wide_window_constants():
    assert WIDE_MIN_DAYS == 15
    assert WIDE_MAX_DAYS == 40
    assert WIDE_HARD_MAX_DAYS >= 40


def test_two_shelves_structural_peak_not_a_shelf():
    """Боковик → вынос ≥2д → сход → новый боковик. Пик не полка."""
    low = _ping_pong(24, -0.20, 0.55)
    peak = [1.55, 1.70, 1.85, 1.60, 1.75, 1.40, 1.55, 1.20]
    low2 = _ping_pong(22, -0.25, 0.40)
    meds = _days(low + peak + low2)
    mins = [(d, v - 0.08) for d, v in meds]
    maxs = [(d, v + 0.08) for d, v in meds]
    # пик: дневной max ещё выше
    peak_dates = {d for d, _ in meds[24:32]}
    maxs = [(d, (v + 0.08 if d not in peak_dates else v + 0.25)) for d, v in meds]

    seg = segment_wide_shelves(meds, touch_mins=mins, touch_maxs=maxs)
    shelves = seg["shelves"]
    assert len(shelves) == 2, shelves
    peak_from, peak_to = meds[24][0], meds[31][0]
    for s in shelves:
        assert not (s["from"] <= peak_from and s["to"] >= peak_to)
        # пиковые дни не внутри полки
        assert s["to"] < peak_from or s["from"] > peak_to
    assert any(e["from"] <= peak_from <= e["to"] for e in seg["excursions"])


def test_single_day_spike_does_not_break_shelf():
    """Один дневной шип (не удержание) не режет полку."""
    vals = _ping_pong(28, -0.15, 0.50)
    meds = _days(vals)
    mins = [(d, v - 0.08) for d, v in meds]
    maxs = [(d, v + 0.08) for d, v in meds]
    spike_d = meds[17][0]
    maxs = [(d, 1.45 if d == spike_d else mx) for d, mx in maxs]
    seg = segment_wide_shelves(meds, touch_mins=mins, touch_maxs=maxs)
    assert len(seg["shelves"]) == 1
    s = seg["shelves"][0]
    assert s["from"] == meds[0][0]
    assert s["to"] == meds[-1][0]
    assert not any(e["from"] == spike_d and e["to"] == spike_d for e in seg["excursions"])


def test_floor_can_go_negative_no_03_clip():
    vals = _ping_pong(20, -0.28, 0.55)
    meds = _days(vals)
    mins = [(d, v - 0.05) for d, v in meds]
    maxs = [(d, v + 0.05) for d, v in meds]
    out = detect_spread_corridor_wide(meds, touch_mins=mins, touch_maxs=maxs)
    assert out["phase"] == "formed"
    assert out["lo"] is not None and float(out["lo"]) < 0.0
    assert float(out["lo"]) < 0.29  # нет отсечки 0,3


def test_no_inner_1_2_day_replacement():
    """Узкие 1–2 дня внутри широкой полки не подменяют границы."""
    vals = _ping_pong(22, -0.20, 0.70)
    meds = _days(vals)
    mins = [(d, v - 0.06) for d, v in meds]
    maxs = [(d, v + 0.06) for d, v in meds]
    # последние два дня почти плоские
    mins[-1] = (mins[-1][0], 0.10)
    maxs[-1] = (maxs[-1][0], 0.22)
    mins[-2] = (mins[-2][0], 0.08)
    maxs[-2] = (maxs[-2][0], 0.20)
    out = detect_spread_corridor_wide(
        meds, spread_now=0.15, touch_mins=mins, touch_maxs=maxs,
    )
    assert out["phase"] == "formed"
    assert float(out["hi"]) - float(out["lo"]) >= 0.55
    assert float(out["hi"]) >= 0.55


def test_detect_broken_during_peak():
    low = _ping_pong(20, -0.10, 0.45)
    peak = [1.40, 1.65, 1.80, 1.55, 1.70]
    meds = _days(low + peak)
    mins = [(d, v - 0.08) for d, v in meds]
    maxs = [(d, v + 0.20) for d, v in meds]
    out = detect_spread_corridor_wide(meds, touch_mins=mins, touch_maxs=maxs)
    assert out["phase"] == "broken"
    assert out["broken_since"] is not None


def _load_m15_window() -> list[dict]:
    by: dict[str, list[float]] = defaultdict(list)
    with CSV_1095.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            d = str(row["timestamp"])[:10]
            if d < "2023-09-01" or d > "2024-05-15":
                continue
            by[d].append(float(row["spread_percent"]))
    bars: list[dict] = []
    for d in sorted(by):
        xs = by[d]
        bars.append({"time": f"{d} 10:00", "spread": min(xs)})
        bars.append({"time": f"{d} 12:00", "spread": sorted(xs)[len(xs) // 2]})
        bars.append({"time": f"{d} 16:00", "spread": max(xs)})
    return bars


def test_three_shelves_nov2023_may2024_march_peak_not_shelf():
    if not CSV_1095.is_file():
        pytest.skip("нет m15_tatn_1095d.csv")
    bars = _load_m15_window()
    daily, mins, maxs = daily_spread_extremes_from_bars(bars)
    seg = segment_wide_shelves(daily, touch_mins=mins, touch_maxs=maxs)
    shelves = [
        s for s in seg["shelves"]
        if not (s["to"] < WIN_LO or s["from"] > WIN_HI)
    ]
    assert len(shelves) == 3, shelves

    # 1–6 марта — вынос, не полка
    march_full = [
        s for s in shelves
        if s["from"] <= "2024-03-01" and s["to"] >= "2024-03-06"
    ]
    assert march_full == []
    mar_exc = [
        e for e in seg["excursions"]
        if e["from"] <= "2024-03-01" <= e["to"]
        or e["from"] <= "2024-03-07" <= e["to"]
    ]
    assert mar_exc, seg["excursions"]

    # перекрытие с глазом: каждый ящик бьётся в свой отрезок
    used: set[int] = set()
    for eye_from, eye_to in EYE:
        eye_len = _cal_overlap(eye_from, eye_to, eye_from, eye_to)
        best_i = None
        best_ov = -1
        for i, s in enumerate(shelves):
            ov = _cal_overlap(s["from"], s["to"], eye_from, eye_to)
            if ov > best_ov:
                best_ov = ov
                best_i = i
        assert best_i is not None
        assert best_i not in used
        used.add(best_i)
        assert best_ov / eye_len >= 0.50, (eye_from, eye_to, shelves[best_i], best_ov)

    # пол без отсечки 0,3 на этом кадре
    assert any(float(s["lo"]) < 0.0 for s in shelves)

    # 1 марта: каузально это вынос, не сформированная полка
    chunk = [(d, v) for d, v in daily if d <= "2024-03-01"]
    cm = [(d, v) for d, v in mins if d <= "2024-03-01"]
    cx = [(d, v) for d, v in maxs if d <= "2024-03-01"]
    now = detect_spread_corridor_wide(
        chunk, spread_now=chunk[-1][1], touch_mins=cm, touch_maxs=cx,
    )
    assert now["phase"] == "broken"
    assert now.get("broken_since") is not None


def test_from_bars_matches_daily():
    if not CSV_1095.is_file():
        pytest.skip("нет m15_tatn_1095d.csv")
    bars = _load_m15_window()
    a = segment_wide_shelves_from_bars(bars)
    daily, mins, maxs = daily_spread_extremes_from_bars(bars)
    b = segment_wide_shelves(daily, touch_mins=mins, touch_maxs=maxs)
    assert [s["from"] for s in a["shelves"]] == [s["from"] for s in b["shelves"]]
    assert [s["to"] for s in a["shelves"]] == [s["to"] for s in b["shelves"]]


def test_desk_wide_payload_history_one_row_per_day():
    """Проводка на график Теста: история по дням, kind=wide, не две точки на день."""
    vals = _ping_pong(22, -0.18, 0.52)
    meds = _days(vals)
    bars = []
    for d, v in meds:
        bars.append({"time": f"{d} 10:00", "spread": v - 0.06})
        bars.append({"time": f"{d} 12:00", "spread": v})
        bars.append({"time": f"{d} 16:00", "spread": v + 0.06})
    payload = desk_wide_corridor_payload(bars, spread_now=vals[-1])
    assert payload["kind"] == "wide"
    hist = payload.get("history") or []
    assert hist
    dates = [str(h["date"]) for h in hist]
    assert dates == sorted(dates)
    assert len(dates) == len(set(dates))
    assert all("segment_id" in h for h in hist)
    assert all(h.get("phase") in ("forming", "formed") for h in hist)


def test_bars1m_chart_exposes_corridor_wide():
    from replay.tip_touch import CACHE_1M, bars1m_chart

    if not CACHE_1M.is_file():
        pytest.skip("нет cache_1m_tatn_spread.parquet")
    data = bars1m_chart(
        "m15_tatn_1095d.csv",
        start="2023-11-01",
        end="2024-05-15",
        chart_days=0,
    )
    cw = data.get("corridor_wide")
    assert cw is not None
    assert cw.get("kind") == "wide"
    assert cw.get("causal") is True
    assert isinstance(cw.get("by_date"), dict) and cw["by_date"]
    assert cw.get("daily")
    last = (cw.get("by_date") or {}).get("2024-05-15") or {}
    shelves = last.get("shelves") or []
    assert len(shelves) == 3, shelves
    assert shelves[0]["from"] <= "2023-11-20"
    assert not any(s["from"] <= "2024-03-01" <= s["to"] for s in shelves)
    assert any(s["from"] >= "2024-03-15" for s in shelves)
    # узкий боевой коридор в этом ответе не подменяем широким
    narrow = data.get("corridor")
    if narrow is not None:
        assert narrow.get("kind") != "wide"


def test_causal_as_of_no_shelf_on_21_mar_2024():
    """До 21.03 после схода нет 15 спокойных дней — полки с 18.03 ещё нет."""
    if not CSV_1095.is_file():
        pytest.skip("нет m15_tatn_1095d.csv")
    from live.spread_corridor_wide import desk_wide_corridor_as_of

    bars = _load_m15_window()
    daily, mins, maxs = daily_spread_extremes_from_bars(bars)
    out = desk_wide_corridor_as_of(
        daily, touch_mins=mins, touch_maxs=maxs, as_of="2024-03-21",
    )
    assert out["as_of"] == "2024-03-21"
    shelves = out.get("shelves") or []
    assert not any(s["from"] >= "2024-03-15" for s in shelves), shelves
    assert not any(s["from"] <= "2024-03-18" <= s["to"] for s in shelves), shelves


def test_causal_as_of_shelf_by_5_apr_2024():
    """Около 05.04 набирается ~15 торговых дней спокойствия — полка есть."""
    if not CSV_1095.is_file():
        pytest.skip("нет m15_tatn_1095d.csv")
    from live.spread_corridor_wide import desk_wide_corridor_as_of

    bars = _load_m15_window()
    daily, mins, maxs = daily_spread_extremes_from_bars(bars)
    out = desk_wide_corridor_as_of(
        daily, touch_mins=mins, touch_maxs=maxs, as_of="2024-04-05",
    )
    shelves = out.get("shelves") or []
    born = [s for s in shelves if s["from"] >= "2024-03-16"]
    assert born, shelves
    assert born[-1]["from"] <= "2024-03-20"
    assert born[-1]["to"] >= "2024-04-05"
    assert out["phase"] == "formed"


def test_causal_levels_at_d_ignore_future_bars():
    """Уровни на дате D не зависят от баров после D."""
    if not CSV_1095.is_file():
        pytest.skip("нет m15_tatn_1095d.csv")
    from live.spread_corridor_wide import desk_wide_corridor_as_of

    bars = _load_m15_window()
    daily, mins, maxs = daily_spread_extremes_from_bars(bars)
    d = "2024-04-05"
    full = desk_wide_corridor_as_of(
        daily, touch_mins=mins, touch_maxs=maxs, as_of=d,
    )
    cut_d = [(a, b) for a, b in daily if a <= d]
    cut_mn = [(a, b) for a, b in mins if a <= d]
    cut_mx = [(a, b) for a, b in maxs if a <= d]
    truncated = desk_wide_corridor_as_of(
        cut_d, touch_mins=cut_mn, touch_maxs=cut_mx, as_of=d,
    )
    assert full["phase"] == truncated["phase"]
    assert full.get("lo") == truncated.get("lo")
    assert full.get("hi") == truncated.get("hi")
    assert [(s["from"], s["to"], s["lo"], s["hi"]) for s in (full.get("shelves") or [])] == [
        (s["from"], s["to"], s["lo"], s["hi"]) for s in (truncated.get("shelves") or [])
    ]
    # полный ряд после D меняет полку, если считать без as_of
    later = desk_wide_corridor_as_of(
        daily, touch_mins=mins, touch_maxs=maxs, as_of="2024-05-15",
    )
    spring = [s for s in (later.get("shelves") or []) if s["from"] >= "2024-03-16"]
    born = [s for s in (full.get("shelves") or []) if s["from"] >= "2024-03-16"]
    assert spring and born
    # майский потолок не должен совпадать с апрельским срезом (будущее утекло бы сюда)
    assert (born[-1]["hi"], born[-1]["lo"]) != (spring[-1]["hi"], spring[-1]["lo"]) or born[-1]["to"] != spring[-1]["to"]


def test_causal_pack_matches_per_day_detector():
    """Один проход даёт те же lo/hi/phase, что детектор на каждом префиксе."""
    start = date(2024, 1, 2)
    daily = []
    mins = []
    maxs = []
    for i in range(WIDE_MIN_DAYS + 12):
        d = date.fromordinal(start.toordinal() + i).isoformat()
        lo, hi = (-0.18, 0.52) if i % 2 == 0 else (-0.10, 0.48)
        daily.append((d, (lo + hi) / 2.0))
        mins.append((d, lo))
        maxs.append((d, hi))
    spike = date.fromordinal(start.toordinal() + WIDE_MIN_DAYS + 20).isoformat()
    daily.append((spike, 8.0))
    mins.append((spike, 7.5))
    maxs.append((spike, 8.5))
    pack = causal_wide_pack_from_extremes(daily, touch_mins=mins, touch_maxs=maxs)
    assert pack.get("causal") is True
    assert not pack.get("partial")
    for i, (d, med) in enumerate(daily):
        got = pack["by_date"][d]
        ref = wide_payload_from_extremes(
            daily[: i + 1],
            touch_mins=mins[: i + 1],
            touch_maxs=maxs[: i + 1],
            spread_now=med,
            with_history=False,
        )
        assert got["phase"] == ref["phase"], d
        assert got.get("lo") == ref.get("lo"), d
        assert got.get("hi") == ref.get("hi"), d


def test_visible_from_is_recognition_not_window_start():
    """Полка известна с 15-го дня; visible_from ≠ начало спокойного окна."""
    start = date(2024, 1, 2)
    daily: list[tuple[str, float]] = []
    mins: list[tuple[str, float]] = []
    maxs: list[tuple[str, float]] = []
    for i in range(WIDE_MIN_DAYS + 5):
        d = date.fromordinal(start.toordinal() + i).isoformat()
        lo, hi = (-0.18, 0.52) if i % 2 == 0 else (-0.10, 0.48)
        daily.append((d, (lo + hi) / 2.0))
        mins.append((d, lo))
        maxs.append((d, hi))
    pack = causal_wide_pack_from_extremes(daily, touch_mins=mins, touch_maxs=maxs)
    first_formed = None
    for d in sorted(pack["by_date"]):
        e = pack["by_date"][d]
        if e.get("phase") == "formed" and e.get("shelves"):
            first_formed = (d, e)
            break
    assert first_formed, pack["by_date"]
    day, entry = first_formed
    s = entry["shelves"][0]
    assert s["from"] == daily[0][0]
    assert s.get("visible_from") == day
    assert s["visible_from"] > s["from"]
    prev = date.fromisoformat(day).toordinal() - 1
    prev_d = date.fromordinal(prev).isoformat()
    prev_e = pack["by_date"].get(prev_d) or {}
    assert not (prev_e.get("shelves") or [])


def test_bars1m_dec4_2023_window_ok():
    from replay.tip_touch import CACHE_1M, bars1m_chart, sim_tip1m

    if not CACHE_1M.is_file():
        pytest.skip("нет cache_1m_tatn_spread.parquet")
    data = bars1m_chart(
        "m15_tatn_1095d.csv",
        start="2023-12-04",
        end="2024-06-05",
        chart_days=0,
    )
    assert data.get("ok") is True
    assert data.get("bars")
    cw = data.get("corridor_wide") or {}
    by = cw.get("by_date") or {}
    formed = [
        (d, e) for d, e in sorted(by.items())
        if e.get("phase") == "formed" and e.get("shelves")
    ]
    if formed:
        day, entry = formed[0]
        s0 = entry["shelves"][0]
        vis = str(s0.get("visible_from") or "")
        assert vis
        assert vis >= s0["from"]
    sim = sim_tip1m(
        csv="m15_tatn_1095d.csv",
        entry=1.6,
        exit_z=1.3,
        start="2023-12-04",
        end="2024-06-05",
        spread_level_mode=True,
    )
    assert "trades" in sim
    assert isinstance(sim["trades"], list)


def test_parse_td_accepts_date_only():
    from replay.tip_touch import _parse_td

    a = _parse_td("2026-03-03")
    b = _parse_td("2026-03-03 00:00:00")
    assert a == b
    assert a.year == 2026 and a.month == 3 and a.day == 3
