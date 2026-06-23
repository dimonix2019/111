"""
Корреляция пауз между сделками (idle gaps) с концом месяца/квартала и корп. событиями TATN.
Запуск: python scripts/idle_calendar_events.py
"""

from __future__ import annotations

from datetime import date, timedelta
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from api.bars_cache import get_bars
from config import DEFAULT_M15
from zsim import run_z_strategy_sim

# Публичные даты TATN/TATNP (дивиденды, СД) — в окне CSV ~2025-09..2026-05 + ближайший контекст
CORP_EVENTS: list[tuple[str, str, str]] = [
    ("2025-08-18", "div_announce", "див. 6м2025 — рекомендация СД"),
    ("2025-10-14", "div_registry", "див. 6м2025 — закрытие реестра / отсечка"),
    ("2025-11-17", "div_announce", "див. 9м2025 — рекомендация СД"),
    ("2026-01-11", "div_registry", "див. 9м2025 — закрытие реестра"),
    ("2026-04-28", "div_announce", "див. 2025 финал — рекомендация СД"),
    ("2026-06-25", "agm", "ГОСА (план)"),
    ("2026-07-15", "div_registry", "див. 2025 финал — реестр (после конца CSV)"),
]


def _parse(d: str) -> date:
    return date.fromisoformat(d[:10])


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


def near_calendar_anchor(day: date, anchor: date, window: int) -> bool:
    return abs((day - anchor).days) <= window


def gap_hits_window(g_from: date, g_to: date, event: date, window: int) -> bool:
    """Событие попадает в [from-window, to+window] по календарю."""
    return g_from - timedelta(days=window) <= event <= g_to + timedelta(days=window)


def main() -> None:
    entry, exit_z = 0.8, 0.7
    bars = get_bars(DEFAULT_M15, recalc_z=False)
    res = run_z_strategy_sim(bars, entry=entry, exit_z=exit_z)
    idle = res.idle_gaps_analysis()
    gaps = idle["gaps"]
    if not gaps:
        print("Нет пауз")
        return

    first = _parse(bars[0].timestamp)
    last = _parse(bars[-1].timestamp)
    all_days = _days_in_range(first, last)

    events_in_range = [(t, k, lbl) for t, k, lbl in CORP_EVENTS if first <= _parse(t) <= last + timedelta(days=14)]

    def label_share(days_list: list[date], predicate) -> float:
        if not days_list:
            return 0.0
        return sum(1 for d in days_list if predicate(d)) / len(days_list)

    # Базовая доля дней «рядом с концом месяца/квартала» на всём ряде
    window = 5
    base_me = label_share(all_days, lambda d: near_calendar_anchor(d, _month_end(d), window))
    base_qe = label_share(all_days, lambda d: near_calendar_anchor(d, _quarter_end(d), window))

    idle_days: set[date] = set()
    for g in gaps:
        gf, gt = _parse(g["from"]), _parse(g["to"])
        for d in _days_in_range(gf, gt):
            idle_days.add(d)

    idle_list = sorted(idle_days)
    idle_me = label_share(idle_list, lambda d: near_calendar_anchor(d, _month_end(d), window))
    idle_qe = label_share(idle_list, lambda d: near_calendar_anchor(d, _quarter_end(d), window))

    long_gaps = [g for g in gaps if g["days"] >= 7]
    long_gap_days: set[date] = set()
    for g in long_gaps:
        gf, gt = _parse(g["from"]), _parse(g["to"])
        for d in _days_in_range(gf, gt):
            long_gap_days.add(d)
    long_list = sorted(long_gap_days)
    long_me = label_share(long_list, lambda d: near_calendar_anchor(d, _month_end(d), window))
    long_qe = label_share(long_list, lambda d: near_calendar_anchor(d, _quarter_end(d), window))

    print(f"Бэктест Entry={entry} Exit={exit_z} · {first} … {last}")
    print(f"Сделок: {len(res.trades)} · пауз (между сделками): {len(gaps)} · дней в паузе: {len(idle_list)}")
    print(f"Длинные паузы >=7 дн.: {len(long_gaps)}")
    print()
    print(f"=== Календарь (окно ±{window} дн.) ===")
    print(f"Конец месяца:  все дни {base_me:.1%}  |  дни в паузе {idle_me:.1%}  |  дни в длинных паузах {long_me:.1%}")
    print(f"Конец квартала: все дни {base_qe:.1%}  |  дни в паузе {idle_qe:.1%}  |  дни в длинных паузах {long_qe:.1%}")
    print()
    if abs(idle_me - base_me) < 0.02 and abs(idle_qe - base_qe) < 0.02:
        print("Вывод по календарю: слабая связь — доли близки к фону (±5 дн. от конца месяца/квартала).")
    elif idle_me > base_me + 0.05 or idle_qe > base_qe + 0.05:
        print("Вывод по календарю: паузы чаще попадают на конец месяца/квартала, чем случайный день.")
    else:
        print("Вывод по календарю: паузы НЕ концентрируются у конца месяца/квартала.")

    print()
    print("=== Корп. события TATN (ручной список) ===")
    for t, k, lbl in events_in_range:
        print(f"  {t}  [{k}]  {lbl}")

    print()
    print(f"=== Паузы >=7 дн. и события (окно +/-{window} дн.) ===")
    hits_event = 0
    for g in sorted(long_gaps, key=lambda x: -x["days"]):
        gf, gt = _parse(g["from"]), _parse(g["to"])
        tags: list[str] = []
        if near_calendar_anchor(gf, _month_end(gf), window) or near_calendar_anchor(gt, _month_end(gt), window):
            tags.append("конец_мес")
        if near_calendar_anchor(gf, _quarter_end(gf), window) or near_calendar_anchor(gt, _quarter_end(gt), window):
            tags.append("конец_кв")
        for t, k, lbl in events_in_range:
            if gap_hits_window(gf, gt, _parse(t), window):
                tags.append(f"{k}({t})")
                hits_event += 1
        tag_s = ", ".join(tags) if tags else "—"
        print(f"  {g['days']:2d} дн. {g['from']} → {g['to']}  |  {tag_s}")

    print()
    print(f"Длинных пауз с попаданием в окно события: {hits_event} из {len(long_gaps)}")
    if len(long_gaps) >= 3:
        if hits_event >= len(long_gaps) * 0.5:
            print("Вывод по событиям: заметная доля длинных пауз рядом с дивидендами/СД — возможен режимный фактор.")
        elif hits_event >= 2:
            print("Вывод по событиям: частичное совпадение — не все паузы объясняются корп. календарём.")
        else:
            print("Вывод по событиям: слабая связь с дивидендами/СД; паузы в основном от Z в нейтрали (mean-reversion).")

    # Сплиты — в ряде нет скачков цены ×2
    tatn = [b.tatn_close for b in bars if b.tatn_close]
    if tatn:
        ratios = [tatn[i] / tatn[i - 1] for i in range(1, len(tatn)) if tatn[i - 1] and tatn[i - 1] > 0]
        big = [r for r in ratios if r > 1.4 or r < 0.7]
        print()
        print(f"=== Сплит/резкий разрыв TATN (|Δ|>40% за 15м) ===")
        print(f"Скачков за 15м: {len(big)} (ожидаемо 0 при отсутствии сплита в окне)")


if __name__ == "__main__":
    main()
