"""Парсинг 15м баров (CSV / SQLite)."""

from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import pandas as pd

MSK = ZoneInfo("Europe/Moscow")


def parse_ts_ms(raw: str) -> int:
    s = str(raw).strip().replace("T", " ")
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            dt = datetime.strptime(s[:19], fmt).replace(tzinfo=MSK)
            return int(dt.timestamp() * 1000)
        except ValueError:
            continue
    if len(s) >= 10 and s[4] == "-" and s[7] == "-":
        try:
            dt = datetime.strptime(s[:10], "%Y-%m-%d").replace(tzinfo=MSK)
            return int(dt.timestamp() * 1000)
        except ValueError:
            return 0
    return 0


def label_15m(raw: str) -> str:
    s = str(raw).strip().replace("T", " ")
    return s[:16] if len(s) >= 16 else s


def load_bars_from_csv(path: Path) -> list[dict[str, Any]]:
    df = pd.read_csv(path)
    cols = {c.lower(): c for c in df.columns}
    ts_col = cols.get("timestamp", "timestamp")
    z_col = cols.get("z_score", "z_score")
    spread_col = cols.get("spread_percent", "spread_percent")
    tatn_col = cols.get("tatn_close", "tatn_close")
    tatnp_col = cols.get("tatnp_close", "tatnp_close")

    out: list[dict[str, Any]] = []
    for _, row in df.iterrows():
        ts_raw = row[ts_col]
        label = label_15m(ts_raw)
        ms = parse_ts_ms(ts_raw)
        out.append(
            {
                "timestampMs": ms,
                "tradeDate": label,
                "zScore": float(row[z_col]) if pd.notna(row[z_col]) else 0.0,
                "spreadPercent": float(row[spread_col]) if pd.notna(row[spread_col]) else 0.0,
                "tatnClose": float(row[tatn_col]) if pd.notna(row[tatn_col]) else 0.0,
                "tatnpClose": float(row[tatnp_col]) if pd.notna(row[tatnp_col]) else 0.0,
            }
        )
    out.sort(key=lambda b: b["timestampMs"])
    return out


def bar_row_to_dict(row: tuple) -> dict[str, Any]:
    try:
        ms = int(row[0])
    except (TypeError, ValueError):
        ms = 0
    if ms <= 0:
        ms = parse_ts_ms(str(row[1] or ""))
    return {
        "timestampMs": ms,
        "tradeDate": row[1],
        "zScore": float(row[2]),
        "spreadPercent": float(row[3]),
        "tatnClose": float(row[4]),
        "tatnpClose": float(row[5]),
    }
