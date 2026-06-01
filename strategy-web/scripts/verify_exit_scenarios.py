#!/usr/bin/env python3
"""Export sim pack slice and print exit scenario summary via Node (m15_tatn_255d.csv)."""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from api.pack_build import pack_to_dict_fast
from zsim import load_bars_from_csv, run_z_strategy_sim

CSV = ROOT / "data" / "m15_tatn_255d.csv"


def main() -> None:
    if not CSV.is_file():
        print(f"Missing {CSV}", file=sys.stderr)
        raise SystemExit(1)

    bars = load_bars_from_csv(str(CSV))
    result = run_z_strategy_sim(bars, entry=0.8, exit_z=0.7)
    pack = pack_to_dict_fast(
        {"label": "verify", "result": result, "entry": 0.8, "exit_z": 0.7},
        csv_path=str(CSV),
        bars=bars,
    )
    payload = {
        "trades": pack["trades"],
        "zscore": [
            {"time": b["time"], "z_score": b["z_score"], "spread_percent": b["spread_percent"]}
            for b in pack["zscore"]
        ],
        "exit_z": pack["exit_z"],
    }
    proc = subprocess.run(
        "npx tsx scripts/runExitScenarios.ts",
        input=json.dumps(payload),
        text=True,
        capture_output=True,
        cwd=str(ROOT / "frontend"),
        shell=True,
        encoding="utf-8",
    )
    if proc.returncode != 0:
        print(proc.stderr, file=sys.stderr)
        raise SystemExit(proc.returncode)
    sys.stdout.buffer.write(proc.stdout.encode("utf-8"))


if __name__ == "__main__":
    main()
