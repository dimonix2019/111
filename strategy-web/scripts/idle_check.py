"""Быстрая проверка признаков долгой паузы без сделок."""
from datetime import datetime
from pathlib import Path

from api.bars_cache import get_bars, invalidate
from zsim import run_z_strategy_sim

ENTRY, EXIT_Z = 0.8, 0.7

p = Path("data/m15_tatn_255d.csv")
invalidate(p)
bars = get_bars(p, recalc_z=False)
r = run_z_strategy_sim(bars, entry=ENTRY, exit_z=EXIT_Z, notional_rub=100_000, leverage=7)
idle = r.idle_gaps_analysis()
ctx = r.market_context(entry=ENTRY, exit_z=EXIT_Z, idle=idle)
last = bars[-1]
cur = idle["current"]
z = last.z_score

print("=== Параметры Entry", ENTRY, "Exit", EXIT_Z, "===")
print("Данные до:", r.last_ts)
print("Сделок:", len(r.trades))
print()
print("СЕЙЧАС:", cur["label"])
print("  idle_days:", cur["idle_days"], "| in_position:", cur["in_position"])
if r.trades:
    t = r.trades[-1]
    print("  Последняя сделка: выход", t.exit_time, "PnL", round(t.pnl_rub), "₽")
print()
print("Рынок сейчас: Z =", round(z, 3), "| spread =", round(last.spread_percent, 3), "%")
print("  До LONG (Z <= -%.1f): ещё %.2f Z" % (ENTRY, z + ENTRY))
print("  До SHORT (Z >= +%.1f): ещё %.2f Z" % (ENTRY, ENTRY - z))
in_entry_zone = z <= -ENTRY or z >= ENTRY
print("  В зоне входа?", in_entry_zone)
print()

for m in ctx["metrics"]:
    print(f"  [{m['id']}] {m.get('current_display', '—')} — {m.get('label', '')}")

hist = {h["bucket"]: h["count"] for h in idle["histogram"]}
print()
print("История пауз (календарные дни между сделками):")
for k in ["0", "1", "2–3", "4–7", "8–14", "15–30", "31+"]:
    if hist.get(k):
        print(f"  {k} дн.: {hist[k]} раз")

gaps = [int(g["days"]) for g in idle["gaps"]]
print("  Макс. пауза в истории:", max(gaps) if gaps else 0, "дн.")
print("  Пауз >= 15 дн.:", sum(1 for d in gaps if d >= 15))

inter = []
for i in range(len(r.trades) - 1):
    e = datetime.strptime(r.trades[i].exit_time[:10], "%Y-%m-%d")
    n = datetime.strptime(r.trades[i + 1].entry_time[:10], "%Y-%m-%d")
    inter.append((n - e).days)
if inter:
    print("  Последние паузы между сделками (дн.):", inter[-8:])

# bars in neutral zone (between entry thresholds)
import numpy as np

zf = r.zscore_frame()
if not zf.empty:
    za = zf["z_score"].to_numpy()
    neutral = np.mean((za > -ENTRY) & (za < ENTRY))
    tail = za[-500:] if len(za) > 500 else za
    neutral_tail = np.mean((tail > -ENTRY) & (tail < ENTRY))
    print()
    print("Доля баров в «нейтрали» (|Z| < entry):", round(100 * neutral, 1), "% за всю историю")
    print("  … последние ~5 дней 15м:", round(100 * neutral_tail, 1), "%")
