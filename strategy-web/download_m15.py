#!/usr/bin/env python3
"""
Скачать 255д 15м TATN/TATNP с MOEX ISS → data/m15_tatn_255d.csv

Запуск из папки strategy-web (без папки scripts):

  ..\\.venv\\Scripts\\python.exe download_m15.py

или двойной клик download_m15.bat
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

try:
    from m15_iss_loader import DEFAULT_LOOKBACK_DAYS, download_m15_csv
except ImportError as e:
    print("Нет файла m15_iss_loader.py в этой папке.")
    print("Скопируйте из репозитория strategy-web/ или выполните git pull.")
    raise SystemExit(1) from e


def main() -> None:
    p = argparse.ArgumentParser(description="MOEX ISS → 15m spread+Z CSV")
    p.add_argument("--days", type=int, default=DEFAULT_LOOKBACK_DAYS)
    p.add_argument("--out", type=str, default="data/m15_tatn_255d.csv")
    args = p.parse_args()

    out = ROOT / args.out
    print(f"Выгрузка {args.days} дн. → {out}")
    print("Подождите 2–5 минут…\n")

    summary = download_m15_csv(out, days=args.days, on_progress=print)

    print(f"\nГотово: {summary['bar_count']} строк")
    print(f"  {summary['first_ts']} … {summary['last_ts']}")
    print(f"  {summary['path']}")


if __name__ == "__main__":
    main()
