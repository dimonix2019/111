"""Quick parity: global vs rolling30 trade counts + API payload keys."""
from __future__ import annotations

import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from api.serialize import pack_to_dict
from zsim import load_bars_from_csv, run_z_strategy_sim

CSV = ROOT / "data" / "m15_tatn_255d.csv"
API = "http://127.0.0.1:8765"


def main() -> int:
    print("=== Z-mode parity (0.8 / 0.7) ===")
    for mode in ("global", "rolling30"):
        bars = load_bars_from_csv(CSV, recalc_z=True, z_mode=mode)
        r = run_z_strategy_sim(bars, 0.8, 0.7, notional_rub=100_000)
        st = r.stats()
        print(f"  {mode:10s} trades={st['trade_count']:4d}  pnl={st['total_pnl_rub']:,.0f} rub")

    bars_csv = load_bars_from_csv(CSV, recalc_z=False)
    r0 = run_z_strategy_sim(bars_csv, 0.8, 0.7, notional_rub=100_000)
    print(f"  csv_z_col  trades={r0.stats()['trade_count']:4d}  (recalc_z=False)")

    r = run_z_strategy_sim(load_bars_from_csv(CSV, recalc_z=True, z_mode="rolling30"), 0.8, 0.7)
    d = pack_to_dict({"label": "T", "result": r, "entry": 0.8, "exit_z": 0.7})
    keys = ["idle_precursors", "idle_gaps", "market_context", "risk", "event_correlation"]
    print("\n=== pack keys ===")
    for k in keys:
        if k == "event_correlation":
            ip = d.get("idle_precursors") or {}
            ec = ip.get("event_correlation")
            print(f"  idle_precursors.event_correlation: {'OK' if ec else 'MISSING'}")
        elif k in d or (k == "idle_precursors" and "idle_precursors" in d):
            print(f"  {k}: OK")
        else:
            print(f"  {k}: MISSING")

    print("\n=== API simulate ===")
    try:
        req = urllib.request.Request(
            f"{API}/api/simulate",
            data=json.dumps(
                {
                    "csv_path": "data/m15_tatn_255d.csv",
                    "auto_download": False,
                    "entry": 0.8,
                    "exit_z": 0.7,
                    "notional": 100_000,
                    "leverage": 7,
                    "z_mode": "rolling30",
                }
            ).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=60) as resp:
            sim = json.loads(resp.read())
        p = sim["packs"][0]
        print(f"  z_mode={sim.get('z_mode')} trades={p['stats']['trade_count']} pnl={p['stats']['total_pnl_rub']:,.0f}")
        ip = p.get("idle_precursors") or {}
        ec = ip.get("event_correlation")
        print(f"  idle_precursors: {'yes' if ip else 'no'}  event_correlation: {'yes' if ec else 'no'}")
    except Exception as e:
        print(f"  API error: {e}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
