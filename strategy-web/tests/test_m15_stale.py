"""Проверка порога устаревания MOEX CSV (без pytest)."""

from __future__ import annotations

import sys
from datetime import datetime, timedelta
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from m15_iss_loader import STALE_HOURS, m15_data_status


def main() -> int:
    import tempfile

    with tempfile.TemporaryDirectory() as d:
        csv = Path(d) / "test.csv"
        old_ts = (datetime.now() - timedelta(hours=STALE_HOURS + 1)).strftime("%Y-%m-%d %H:%M:%S")
        pd.DataFrame({"timestamp": [old_ts], "spread_percent": [5.0], "z_score": [0.0]}).to_csv(csv, index=False)
        assert m15_data_status(csv)["is_stale"] is True

        fresh_ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        pd.DataFrame({"timestamp": [fresh_ts], "spread_percent": [5.0], "z_score": [0.0]}).to_csv(csv, index=False)
        assert m15_data_status(csv)["is_stale"] is False

    print(f"OK stale_hours={STALE_HOURS}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
