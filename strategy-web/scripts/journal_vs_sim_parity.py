#!/usr/bin/env python3
"""
Сравнение журнала live-сигналов (экспорт из приложения) с replay симуляции.

  cd strategy-web
  python3 scripts/journal_vs_sim_parity.py --journal data/parity_journal.json
  python3 scripts/journal_vs_sim_parity.py --journal data/parity_journal.json --csv data/m15_tatn_255d.csv

Формат журнала (экспорт «Журнал → Экспорт»):
  { "version": 1, "thresholds": {"entry": 0.7, "exit": 0.5}, "events": [...] }
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from config import SESSION_DEFAULTS
from zsim import load_bars_from_csv, run_z_strategy_sim

MSK = ZoneInfo("Europe/Moscow")

SIGNAL_MAP = {
    "enter_long": "EnterLong",
    "enter_short": "EnterShort",
    "exit_long": "ExitLong",
    "exit_short": "ExitShort",
}


@dataclass
class EdgeEvent:
    bar_ts: str
    signal_type: str
    z_score: float
    spread: float
    source: str


def _ts_key(ts: str) -> str:
    """Нормализация timestamp до минуты (МСК-строка из CSV/журнала)."""
    return ts[:16] if len(ts) >= 16 else ts


def _millis_to_bar_ts(ms: int) -> str:
    dt = datetime.fromtimestamp(ms / 1000.0, tz=MSK)
    return dt.strftime("%Y-%m-%d %H:%M")


def load_journal(path: Path) -> tuple[float, float, list[dict]]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(raw, list):
        events = raw
        entry = 0.7
        exit_z = 0.5
    else:
        th = raw.get("thresholds") or {}
        entry = float(th.get("entry", 0.7))
        exit_z = float(th.get("exit", 0.5))
        events = raw.get("events") or []
    return entry, exit_z, events


def sim_edge_events(bars, entry: float, exit_z: float) -> list[EdgeEvent]:
    sim_kw = {
        "notional_rub": float(SESSION_DEFAULTS.get("inp_notional", 100_000)),
        "leverage": float(SESSION_DEFAULTS.get("inp_leverage", 7.0)),
        "commission_pct_per_side": float(SESSION_DEFAULTS.get("inp_commission", 0.04)),
        "compound_returns": bool(SESSION_DEFAULTS.get("inp_compound", False)),
    }
    result = run_z_strategy_sim(bars, entry=entry, exit_z=exit_z, **sim_kw)
    out: list[EdgeEvent] = []
    for _, row in result.signals.iterrows():
        sig = str(row.get("signal") or "")
        if sig not in SIGNAL_MAP:
            continue
        out.append(
            EdgeEvent(
                bar_ts=_ts_key(str(row["timestamp"])),
                signal_type=SIGNAL_MAP[sig],
                z_score=float(row["z_score"]),
                spread=float(row["spread_percent"]),
                source="sim",
            )
        )
    return out


def journal_edge_events(events: list[dict]) -> list[EdgeEvent]:
    out: list[EdgeEvent] = []
    for ev in events:
        st = ev.get("signalType") or ev.get("signal_type")
        if not st:
            continue
        ms = int(ev.get("timestampMillis") or ev.get("timestamp_millis") or 0)
        out.append(
            EdgeEvent(
                bar_ts=_millis_to_bar_ts(ms),
                signal_type=str(st),
                z_score=float(ev.get("zScore") or ev.get("z_score") or 0.0),
                spread=0.0,
                source="journal",
            )
        )
    return sorted(out, key=lambda e: (e.bar_ts, e.signal_type))


def compare_events(journal: list[EdgeEvent], sim: list[EdgeEvent], tolerance_bars: int = 1) -> dict:
    """Сопоставление по (bar_ts, signal_type) с допуском ±N баров (15м)."""
    sim_by_type: dict[str, list[EdgeEvent]] = {}
    for e in sim:
        sim_by_type.setdefault(e.signal_type, []).append(e)

    matched = []
    missed_in_journal = []
    extra_in_journal = list(journal)

    for sim_ev in sim:
        candidates = [
            j
            for j in extra_in_journal
            if j.signal_type == sim_ev.signal_type and abs(_bar_delta_min(j.bar_ts, sim_ev.bar_ts)) <= 15 * tolerance_bars
        ]
        if candidates:
            best = min(candidates, key=lambda j: abs(_bar_delta_min(j.bar_ts, sim_ev.bar_ts)))
            matched.append((best, sim_ev))
            extra_in_journal.remove(best)
        else:
            missed_in_journal.append(sim_ev)

    return {
        "matched": matched,
        "missed_in_journal": missed_in_journal,
        "extra_in_journal": extra_in_journal,
        "journal_count": len(journal),
        "sim_count": len(sim),
    }


def _bar_delta_min(a: str, b: str) -> int:
    fmt = "%Y-%m-%d %H:%M"
    da = datetime.strptime(a, fmt).replace(tzinfo=MSK)
    db = datetime.strptime(b, fmt).replace(tzinfo=MSK)
    return int((da - db).total_seconds() // 60)


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--journal", type=str, required=True, help="JSON экспорт журнала")
    p.add_argument("--csv", type=str, default="", help="15м CSV (default: data/m15_tatn_255d.csv)")
    p.add_argument("--entry", type=float, default=None)
    p.add_argument("--exit", type=float, default=None, dest="exit_z")
    p.add_argument("--out", type=str, default="", help="CSV отчёт")
    args = p.parse_args()

    journal_path = Path(args.journal)
    csv_path = Path(args.csv) if args.csv else ROOT / "data" / "m15_tatn_255d.csv"
    entry_j, exit_j, raw_events = load_journal(journal_path)
    entry = args.entry if args.entry is not None else entry_j
    exit_z = args.exit_z if args.exit_z is not None else exit_j

    print(f"=== PARITY: journal vs sim ===")
    print(f"Journal: {journal_path} ({len(raw_events)} events)")
    print(f"Thresholds: ±{entry:.2f}/±{exit_z:.2f}")
    print(f"CSV: {csv_path}")

    bars = load_bars_from_csv(str(csv_path), recalc_z=True, z_mode="rolling30")
    print(f"Bars: {len(bars)} ({bars[0].timestamp} … {bars[-1].timestamp})")

    j_events = journal_edge_events(raw_events)
    s_events = sim_edge_events(bars, entry, exit_z)

    # Фильтр sim по диапазону журнала
    if j_events:
        j_min, j_max = j_events[0].bar_ts, j_events[-1].bar_ts
        s_events = [e for e in s_events if j_min <= e.bar_ts <= j_max]

    cmp = compare_events(j_events, s_events)
    match_n = len(cmp["matched"])
    j_n = cmp["journal_count"]
    s_n = cmp["sim_count"]
    rate = 100.0 * match_n / max(1, s_n)

    sim_kw = {
        "notional_rub": float(SESSION_DEFAULTS.get("inp_notional", 100_000)),
        "leverage": float(SESSION_DEFAULTS.get("inp_leverage", 7.0)),
        "commission_pct_per_side": float(SESSION_DEFAULTS.get("inp_commission", 0.04)),
        "compound_returns": False,
    }
    full = run_z_strategy_sim(bars, entry=entry, exit_z=exit_z, **sim_kw)
    st = full.stats()

    print()
    print(f"SIM full period: PnL={st['total_pnl_rub']:,.0f} ₽ trades={st['trade_count']} DD={st['max_drawdown_rub']:,.0f}")
    print(f"Signals in journal range: sim={s_n} journal={j_n}")
    print(f"Matched: {match_n}/{s_n} ({rate:.1f}%)")
    print(f"Missed in journal (sim had, live didn't): {len(cmp['missed_in_journal'])}")
    print(f"Extra in journal (live had, sim didn't): {len(cmp['extra_in_journal'])}")

    if cmp["missed_in_journal"]:
        print("\n--- Missed (first 10) ---")
        for e in cmp["missed_in_journal"][:10]:
            print(f"  {e.bar_ts} {e.signal_type} Z={e.z_score:.3f} spread={e.spread:.3f}%")

    if cmp["extra_in_journal"]:
        print("\n--- Extra (first 10) ---")
        for e in cmp["extra_in_journal"][:10]:
            print(f"  {e.bar_ts} {e.signal_type} Z={e.z_score:.3f}")

    if cmp["matched"]:
        print("\n--- Matched ΔZ (first 10) ---")
        for j, s in cmp["matched"][:10]:
            dz = j.z_score - s.z_score
            print(f"  {j.bar_ts} {j.signal_type}: journal Z={j.z_score:.3f} sim Z={s.z_score:.3f} Δ={dz:+.3f}")

    out_path = Path(args.out) if args.out else journal_path.with_suffix(".parity.csv")
    rows = []
    for j, s in cmp["matched"]:
        rows.append(
            {
                "status": "matched",
                "bar_ts": j.bar_ts,
                "signal_type": j.signal_type,
                "journal_z": j.z_score,
                "sim_z": s.z_score,
                "dz": j.z_score - s.z_score,
                "sim_spread": s.spread,
            }
        )
    for e in cmp["missed_in_journal"]:
        rows.append({"status": "missed_journal", "bar_ts": e.bar_ts, "signal_type": e.signal_type, "sim_z": e.z_score, "sim_spread": e.spread})
    for e in cmp["extra_in_journal"]:
        rows.append({"status": "extra_journal", "bar_ts": e.bar_ts, "signal_type": e.signal_type, "journal_z": e.z_score})
    pd.DataFrame(rows).to_csv(out_path, index=False)
    print(f"\nReport: {out_path}")


if __name__ == "__main__":
    main()
