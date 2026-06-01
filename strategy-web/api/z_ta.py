"""Технический анализ ряда Z-score как ценового ряда (предвестники простоя)."""

from __future__ import annotations

from datetime import date, timedelta

import numpy as np

BARS_PER_DAY = 26  # ~6.5 ч × 4 (15м) — оценка для MOEX

# Публичные даты TATN/TATNP (дивиденды, СД) — окно CSV + ближайший контекст
CORP_EVENTS: tuple[tuple[str, str, str], ...] = (
    ("2025-08-18", "div_announce", "див. 6м2025 — рекомендация СД"),
    ("2025-10-14", "div_registry", "див. 6м2025 — закрытие реестра / отсечка"),
    ("2025-11-17", "div_announce", "див. 9м2025 — рекомендация СД"),
    ("2026-01-11", "div_registry", "див. 9м2025 — закрытие реестра"),
    ("2026-04-28", "div_announce", "див. 2025 финал — рекомендация СД"),
    ("2026-06-25", "agm", "ГОСА (план)"),
    ("2026-07-15", "div_registry", "див. 2025 финал — реестр (после конца CSV)"),
)


def _pct_le(arr: np.ndarray, x: float) -> float:
    if len(arr) == 0:
        return 50.0
    return 100.0 * float(np.mean(arr <= x))


def _lin_slope(y: np.ndarray) -> float:
    """Наклон линейной регрессии по индексу (нормирован на длину окна)."""
    n = len(y)
    if n < 3:
        return 0.0
    x = np.arange(n, dtype=float)
    x = x - x.mean()
    y = y - y.mean()
    den = float(np.dot(x, x))
    if den <= 1e-12:
        return 0.0
    return float(np.dot(x, y) / den)


def _bb_width(z: np.ndarray) -> float:
    if len(z) < 5:
        return 0.0
    mid = float(np.mean(z))
    std = float(np.std(z))
    if abs(mid) < 1e-9:
        return std * 4
    return (4.0 * std) / max(0.01, abs(mid))


def _atr_z(z: np.ndarray) -> float:
    if len(z) < 2:
        return 0.0
    return float(np.mean(np.abs(np.diff(z))))


def z_ta_features(z: np.ndarray, entry: float, exit_z: float) -> dict[str, float]:
    """Снимок TA по Z на конце ряда z (включая последний бар)."""
    z = np.asarray(z, dtype=float)
    n = len(z)
    if n < 10:
        return {}

    cur = float(z[-1])
    entry = float(entry)
    exit_z = float(exit_z)

    w_s = min(32, n)
    w_m = min(96, n)
    w_l = min(192, n)

    tail_s = z[-w_s:]
    tail_m = z[-w_m:]
    tail_l = z[-w_l:]

    slope_s = _lin_slope(tail_s)
    slope_m = _lin_slope(tail_m)
    slope_l = _lin_slope(tail_l)

    atr_s = _atr_z(tail_s)
    atr_m = _atr_z(tail_m)
    atr_l = _atr_z(tail_l)

    bb_s = _bb_width(tail_s)
    bb_m = _bb_width(tail_m)

    std_s = float(np.std(tail_s))
    std_l = float(np.std(tail_l)) if len(tail_l) > 1 else std_s
    squeeze = std_s / std_l if std_l > 1e-9 else 1.0

    abs_z = np.abs(z)
    extreme = entry
    # серия: в нейтрали?
    neutral = (abs_z < entry).astype(float)
    # длина текущей серии нейтрали с конца
    neutral_streak = 0
    for v in neutral[::-1]:
        if v > 0.5:
            neutral_streak += 1
        else:
            break

    # баров с последнего |Z|>=entry
    bars_since_extreme = 0
    for v in abs_z[::-1]:
        if v < entry:
            bars_since_extreme += 1
        else:
            break

    # был сильный тренд |Z| в длинном окне, сейчас в нейтрали
    max_abs_l = float(np.max(np.abs(tail_l)))
    min_abs_tail = float(np.min(np.abs(tail_s)))
    trend_then_flat = max_abs_l >= entry and min_abs_tail < exit_z and abs(cur) < entry

    # «пила» vs тренд: отношение чистого сдвига к сумме |ΔZ|
    dz = np.abs(np.diff(tail_m))
    net_move = abs(float(tail_m[-1] - tail_m[0]))
    path = float(np.sum(dz)) if len(dz) else 1.0
    trend_efficiency = net_move / path if path > 1e-9 else 0.0

    return {
        "z": cur,
        "slope_short": slope_s,
        "slope_med": slope_m,
        "slope_long": slope_l,
        "atr_short": atr_s,
        "atr_med": atr_m,
        "atr_long": atr_l,
        "bb_width_short": bb_s,
        "bb_width_med": bb_m,
        "squeeze": squeeze,
        "neutral_streak_bars": float(neutral_streak),
        "neutral_streak_days": neutral_streak / BARS_PER_DAY,
        "bars_since_extreme": float(bars_since_extreme),
        "days_since_extreme": bars_since_extreme / BARS_PER_DAY,
        "max_abs_long": max_abs_l,
        "trend_then_flat": float(trend_then_flat),
        "trend_efficiency": trend_efficiency,
        "in_neutral": float(abs(cur) < entry),
    }


def z_ta_percentiles(z: np.ndarray, entry: float, exit_z: float) -> dict[str, float]:
    """Перцентили текущих TA-метрик по скользящим окнам всей истории Z."""
    z = np.asarray(z, dtype=float)
    n = len(z)
    if n < 200:
        return {}

    w_m = 96
    feats_hist: dict[str, list[float]] = {
        "atr_med": [],
        "bb_width_med": [],
        "squeeze": [],
        "trend_efficiency": [],
        "neutral_streak_bars": [],
    }

    step = max(12, w_m // 8)
    if n > 8_000:
        step = max(step, n // 180)
    for i in range(w_m, n, step):
        snap = z_ta_features(z[i - w_m : i + 1], entry, exit_z)
        for k in feats_hist:
            if k in snap:
                feats_hist[k].append(snap[k])

    cur = z_ta_features(z, entry, exit_z)
    out: dict[str, float] = {}
    for k, arr in feats_hist.items():
        if k in cur and arr:
            out[f"{k}_pct"] = _pct_le(np.asarray(arr), cur[k])
    return out


def idle_risk_score(
    feat: dict[str, float],
    pct: dict[str, float],
    entry: float,
    *,
    idle_days: float = 0.0,
    in_position: bool = False,
) -> tuple[int, list[dict]]:
    """
    Сводный риск затяжного простоя (0–100) и список предвестников.
    Эвристики подобраны под mean-reversion Z (вход на экстремумах).
    """
    signs: list[dict] = []
    score = 0.0

    def add(sig_id: str, title: str, weight: int, fired: bool, label: str, level: str = "med") -> None:
        nonlocal score
        if fired:
            score += weight
        signs.append(
            {
                "id": sig_id,
                "title": title,
                "active": fired,
                "weight": weight,
                "label": label,
                "level": "high" if fired and weight >= 18 else ("med" if fired else "low"),
            }
        )

    ns = feat.get("neutral_streak_bars", 0)
    ns_days = feat.get("neutral_streak_days", 0)
    add(
        "neutral_streak",
        "Флэт в нейтрали |Z|",
        22,
        ns >= 48,
        f"{int(ns)} баров (~{ns_days:.1f} дн.) |Z| < Entry — входов нет",
    )

    squeeze = feat.get("squeeze", 1.0)
    sq_pct = pct.get("squeeze_pct", 50)
    add(
        "squeeze",
        "Сжатие волатильности Z",
        18,
        squeeze < 0.45 or sq_pct < 20,
        f"Squeeze {squeeze:.2f} (коротк/длинн σ), перц. {sq_pct:.0f}% — «пружина» сжата",
    )

    atr_pct = pct.get("atr_med_pct", 50)
    add(
        "low_atr",
        "Низкая «ATR» Z",
        16,
        atr_pct < 25,
        f"ATR(Z) на {atr_pct:.0f}-м перцентиле — мало движения",
    )

    bb_pct = pct.get("bb_width_med_pct", 50)
    add(
        "narrow_bb",
        "Узкие полосы Боллинджера",
        14,
        bb_pct < 20,
        f"Ширина BB(Z) — {bb_pct:.0f}-й перцентиль",
    )

    ttf = feat.get("trend_then_flat", 0) > 0.5
    bse = feat.get("bars_since_extreme", 0)
    add(
        "trend_flat",
        "Тренд Z → флэт",
        20,
        ttf and bse >= 16,
        f"Был экстремум |Z|, затем {int(bse)} баров (~{bse / BARS_PER_DAY:.1f} дн.) откат в нейтраль",
    )

    te = feat.get("trend_efficiency", 0)
    te_pct = pct.get("trend_efficiency_pct", 50)
    slope = abs(feat.get("slope_med", 0))
    add(
        "weak_trend",
        "Слабый тренд (пила)",
        12,
        te < 0.12 and te_pct < 30 and slope < 0.02,
        f"Эффективность тренда {te:.2f} ({te_pct:.0f}-й перц.) — больше шум, чем направление",
    )

    z = feat.get("z", 0)
    in_neutral = feat.get("in_neutral", 0) > 0.5
    dist_entry = entry_threshold_distance(z, float(entry))
    add(
        "far_from_entry",
        "Далеко от порога входа",
        15,
        in_neutral and dist_entry > 0.35,
        f"Z={z:+.2f}, до ±Entry ещё ~{dist_entry:.2f} Z",
    )

    if idle_days >= 7:
        score += 12
        signs.append(
            {
                "id": "already_long_idle",
                "title": "Пауза уже длинная",
                "active": True,
                "weight": 12,
                "label": f"Без сделок {int(idle_days)} дн. — режим затишья",
                "level": "high",
            }
        )

    if in_position:
        score *= 0.55  # в сделке простой «впереди» не актуален так же

    risk = int(max(0, min(100, score)))
    return risk, signs


def entry_threshold_distance(z: float, entry: float) -> float:
    """Мин. расстояние до порога входа в пунктах Z."""
    if z <= -entry:
        return 0.0
    if z >= entry:
        return 0.0
    return min(z + entry, entry - z)


def _percentile(arr: np.ndarray, q: float) -> float:
    if len(arr) == 0:
        return 0.0
    return float(np.percentile(arr, q))


def idle_duration_forecast(
    gaps: list[dict],
    *,
    current_idle_days: float = 0.0,
    in_position: bool = False,
    risk_score: int = 0,
) -> dict:
    """
    Прогноз длительности простоя по историческим паузам между сделками.
    Если пауза уже идёт: медиана оставшихся дней среди прошлых пауз не короче текущей.
    """
    durations = sorted(int(g.get("days", 0)) for g in gaps if int(g.get("days", 0)) > 0)

    if in_position:
        return {
            "applicable": False,
            "reason": "in_position",
            "label": "Сейчас в сделке — прогноз простоя после выхода из позиции",
        }

    if len(durations) < 2:
        return {
            "applicable": False,
            "reason": "insufficient_history",
            "label": "Мало исторических пауз для прогноза (нужно ≥2 сделки)",
            "historical_gaps_count": len(durations),
        }

    cur = max(0, int(round(current_idle_days)))
    arr = np.asarray(durations, dtype=float)

    # При высоком риске ориентируемся на более длинные паузы в истории
    sample = arr
    if risk_score >= 40:
        long = arr[arr >= 4]
        if len(long) >= 2:
            sample = long

    if cur > 0:
        survived = sample[sample >= cur]
        if len(survived) == 0:
            max_d = int(np.max(sample))
            return {
                "applicable": True,
                "current_idle_days": cur,
                "median_remaining_days": 0,
                "median_total_days": cur,
                "p25_remaining_days": 0,
                "p75_remaining_days": 0,
                "historical_gaps_count": int(len(durations)),
                "similar_gaps_count": 0,
                "percentile_vs_history": round(_pct_le(arr, cur), 1),
                "label": (
                    f"Уже {cur} дн. — дольше любой паузы в выборке (макс. {max_d} дн. по {len(durations)} паузам). "
                    "Сигнал может появиться скоро."
                ),
                "display_short": "скоро сигнал?",
            }

        remainders = survived - cur
        med_rem = _percentile(remainders, 50)
        p25_rem = _percentile(remainders, 25)
        p75_rem = _percentile(remainders, 75)
        med_total = cur + med_rem
        pct_hist = _pct_le(arr, cur)

        label = (
            f"Уже {cur} дн. без сделок · по {len(survived)} похожим паузам в истории: "
            f"ещё ~{med_rem:.0f} дн. (медиана), итого ~{med_total:.0f} дн. "
            f"(разброс остатка {p25_rem:.0f}–{p75_rem:.0f} дн.). "
            f"Текущая пауза длиннее {pct_hist:.0f}% прошлых."
        )
        return {
            "applicable": True,
            "current_idle_days": cur,
            "median_remaining_days": round(med_rem),
            "median_total_days": round(med_total),
            "p25_remaining_days": round(p25_rem),
            "p75_remaining_days": round(p75_rem),
            "historical_gaps_count": int(len(durations)),
            "similar_gaps_count": int(len(survived)),
            "percentile_vs_history": round(pct_hist, 1),
            "label": label,
            "display_short": f"ещё ~{med_rem:.0f} дн.",
        }

    med = _percentile(sample, 50)
    p25 = _percentile(sample, 25)
    p75 = _percentile(sample, 75)
    if risk_score >= 40:
        label = (
            f"Если затишье продолжится: типичная пауза ~{med:.0f} дн. "
            f"(медиана по {len(sample)} длинным паузам, P25–P75: {p25:.0f}–{p75:.0f} дн.)"
        )
    else:
        label = (
            f"Типичная пауза между сделками: ~{med:.0f} дн. "
            f"(медиана по {len(durations)} паузам, P25–P75: {p25:.0f}–{p75:.0f} дн.)"
        )
    return {
        "applicable": True,
        "current_idle_days": 0,
        "median_remaining_days": None,
        "median_total_days": round(med),
        "p25_remaining_days": round(p25),
        "p75_remaining_days": round(p75),
        "historical_gaps_count": int(len(durations)),
        "similar_gaps_count": int(len(sample)),
        "percentile_vs_history": None,
        "label": label,
        "display_short": f"~{med:.0f} дн.",
    }


def _parse_day(ts: str | None) -> date | None:
    if not ts:
        return None
    try:
        return date.fromisoformat(str(ts)[:10])
    except ValueError:
        return None


def _days_in_range(a: date, b: date) -> list[date]:
    out: list[date] = []
    cur = a
    while cur <= b:
        out.append(cur)
        cur += timedelta(days=1)
    return out


def _month_end(d: date) -> date:
    if d.month == 12:
        return date(d.year, 12, 31)
    return date(d.year, d.month + 1, 1) - timedelta(days=1)


def _quarter_end(d: date) -> date:
    qm = ((d.month - 1) // 3 + 1) * 3
    return _month_end(date(d.year, qm, 1))


def _near_calendar_anchor(day: date, anchor: date, window: int) -> bool:
    return abs((day - anchor).days) <= window


def _gap_hits_window(g_from: date, g_to: date, event: date, window: int) -> bool:
    return g_from - timedelta(days=window) <= event <= g_to + timedelta(days=window)


def _label_share(days_list: list[date], predicate) -> float:
    if not days_list:
        return 0.0
    return sum(1 for d in days_list if predicate(d)) / len(days_list)


def idle_event_correlation(
    gaps: list[dict],
    *,
    first_ts: str | None,
    last_ts: str | None,
    window_days: int = 5,
) -> dict:
    """Связь пауз между сделками с концом месяца/квартала и корп. событиями TATN."""
    first = _parse_day(first_ts)
    last = _parse_day(last_ts)
    if not first or not last or not gaps:
        return {
            "window_days": window_days,
            "long_gaps_count": 0,
            "matches_count": 0,
            "strength": "none",
            "summary": "Недостаточно данных для календарной корреляции.",
            "events": [],
            "rows": [],
        }

    window = window_days
    all_days = _days_in_range(first, last)
    base_me = _label_share(all_days, lambda d: _near_calendar_anchor(d, _month_end(d), window))
    base_qe = _label_share(all_days, lambda d: _near_calendar_anchor(d, _quarter_end(d), window))

    idle_days: set[date] = set()
    for g in gaps:
        gf, gt = _parse_day(g.get("from")), _parse_day(g.get("to"))
        if gf and gt:
            idle_days.update(_days_in_range(gf, gt))
    idle_list = sorted(idle_days)
    idle_me = _label_share(idle_list, lambda d: _near_calendar_anchor(d, _month_end(d), window))
    idle_qe = _label_share(idle_list, lambda d: _near_calendar_anchor(d, _quarter_end(d), window))

    long_gaps = [g for g in gaps if int(g.get("days", 0)) >= 7]
    events_in_range = [
        (t, k, lbl)
        for t, k, lbl in CORP_EVENTS
        if first <= (_parse_day(t) or first) <= last + timedelta(days=14)
    ]

    rows: list[dict] = []
    long_with_corp = 0
    for g in sorted(long_gaps, key=lambda x: -int(x.get("days", 0))):
        gf, gt = _parse_day(g.get("from")), _parse_day(g.get("to"))
        if not gf or not gt:
            continue
        tags: list[str] = []
        if _near_calendar_anchor(gf, _month_end(gf), window) or _near_calendar_anchor(gt, _month_end(gt), window):
            tags.append("конец_месяца")
        if _near_calendar_anchor(gf, _quarter_end(gf), window) or _near_calendar_anchor(gt, _quarter_end(gt), window):
            tags.append("конец_квартала")
        hit_corp = False
        for t, k, _lbl in events_in_range:
            ev = _parse_day(t)
            if ev and _gap_hits_window(gf, gt, ev, window):
                if k not in tags:
                    tags.append(k)
                hit_corp = True
        if hit_corp:
            long_with_corp += 1
        rows.append(
            {
                "days": int(g.get("days", 0)),
                "from": str(g.get("from", "")),
                "to": str(g.get("to", "")),
                "tags": tags,
            }
        )

    if not long_gaps:
        return {
            "window_days": window,
            "long_gaps_count": 0,
            "matches_count": 0,
            "strength": "none",
            "summary": "Длинных пауз ≥7 дн. нет — корреляцию с дивидендами/календарём оценить нельзя.",
            "events": [{"date": t, "kind": k, "label": lbl} for t, k, lbl in events_in_range],
            "rows": [],
        }

    parts: list[str] = []
    if abs(idle_me - base_me) < 0.02 and abs(idle_qe - base_qe) < 0.02:
        parts.append(
            f"Конец месяца/квартала (±{window} дн.): слабая связь — доли в паузах близки к фону "
            f"({idle_me:.0%} vs {base_me:.0%} по месяцу)."
        )
    elif idle_me > base_me + 0.05 or idle_qe > base_qe + 0.05:
        parts.append("Паузы чаще попадают на конец месяца/квартала, чем случайный день.")
    else:
        parts.append("Явной концентрации пауз у конца месяца/квартала нет.")

    if long_with_corp >= len(long_gaps) * 0.5 and len(long_gaps) >= 3:
        parts.append(
            f"Корп. события: {long_with_corp} из {len(long_gaps)} длинных пауз в окне ±{window} дн. "
            f"от дивидендов/СД — возможен режимный фактор."
        )
        strength = "moderate"
    elif long_with_corp >= 2:
        parts.append(
            f"Корп. события: частичное совпадение ({long_with_corp}/{len(long_gaps)} длинных пауз рядом с дивидендами/СД)."
        )
        strength = "weak"
    else:
        parts.append(
            "Дивиденды и СД: слабая связь; паузы в основном от Z в нейтрали (mean-reversion)."
        )
        strength = "none"

    return {
        "window_days": window,
        "long_gaps_count": len(long_gaps),
        "matches_count": long_with_corp,
        "strength": strength,
        "summary": " ".join(parts),
        "events": [{"date": t, "kind": k, "label": lbl} for t, k, lbl in events_in_range],
        "rows": rows[:12],
    }
