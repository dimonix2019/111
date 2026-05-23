#!/usr/bin/env python3
"""CLI-обёртка над m15_iss_loader.download_m15_csv."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from m15_iss_loader import DEFAULT_LOOKBACK_DAYS, download_m15_csv


def main() -> None:
    p = argparse.ArgumentParser(description="Export TATN/TATNP 15m spread+Z from MOEX ISS")
    p.add_argument("--days", type=int, default=DEFAULT_LOOKBACK_DAYS)
    p.add_argument("--out", type=str, default="data/m15_tatn_255d.csv")
    args = p.parse_args()

    summary = download_m15_csv(Path(args.out), days=args.days, on_progress=print)
    print(f"Wrote {summary['bar_count']} rows → {summary['path']}")
    print(f"  {summary['first_ts']} … {summary['last_ts']}")


if __name__ == "__main__":
    main()
