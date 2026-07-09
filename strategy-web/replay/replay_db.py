"""SQLite offline store + MOEX tail sync for Bar Replay."""

from __future__ import annotations

import logging
import sqlite3
import sys
from pathlib import Path
from typing import Any

from replay.replay_data import bar_row_to_dict, load_bars_from_csv

log = logging.getLogger(__name__)

ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data"
DB_PATH = DATA_DIR / "replay_m15.db"

# m15_iss_loader lives in strategy-web/
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def _connect() -> sqlite3.Connection:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    _init_schema(conn)
    return conn


def _init_schema(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS m15_bars (
            timestamp_ms INTEGER PRIMARY KEY,
            trade_date TEXT NOT NULL,
            z_score REAL NOT NULL,
            spread_percent REAL NOT NULL,
            tatn_close REAL NOT NULL,
            tatnp_close REAL NOT NULL,
            source_csv TEXT NOT NULL DEFAULT ''
        )
        """
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_m15_trade_date ON m15_bars(trade_date)"
    )
    conn.commit()


def _upsert_bars(conn: sqlite3.Connection, bars: list[dict[str, Any]], source_csv: str) -> int:
    if not bars:
        return 0
    rows = [
        (
            int(b["timestampMs"]),
            b["tradeDate"],
            float(b["zScore"]),
            float(b["spreadPercent"]),
            float(b["tatnClose"]),
            float(b["tatnpClose"]),
            source_csv,
        )
        for b in bars
    ]
    conn.executemany(
        """
        INSERT INTO m15_bars (
            timestamp_ms, trade_date, z_score, spread_percent,
            tatn_close, tatnp_close, source_csv
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(timestamp_ms) DO UPDATE SET
            trade_date = excluded.trade_date,
            z_score = excluded.z_score,
            spread_percent = excluded.spread_percent,
            tatn_close = excluded.tatn_close,
            tatnp_close = excluded.tatnp_close,
            source_csv = excluded.source_csv
        """,
        rows,
    )
    conn.commit()
    return len(rows)


def seed_from_csv(csv_path: Path, source_name: str) -> int:
    bars = load_bars_from_csv(csv_path)
    with _connect() as conn:
        return _upsert_bars(conn, bars, source_name)


def load_bars_from_db(start_date: str | None = None) -> list[dict[str, Any]]:
    with _connect() as conn:
        if start_date:
            cur = conn.execute(
                """
                SELECT timestamp_ms, trade_date, z_score, spread_percent,
                       tatn_close, tatnp_close
                FROM m15_bars
                WHERE trade_date >= ?
                ORDER BY timestamp_ms
                """,
                (start_date,),
            )
        else:
            cur = conn.execute(
                """
                SELECT timestamp_ms, trade_date, z_score, spread_percent,
                       tatn_close, tatnp_close
                FROM m15_bars
                ORDER BY timestamp_ms
                """
            )
        return [bar_row_to_dict(tuple(r)) for r in cur.fetchall()]


def db_bar_count() -> int:
    with _connect() as conn:
        row = conn.execute("SELECT COUNT(*) AS c FROM m15_bars").fetchone()
        return int(row["c"]) if row else 0


def _try_moex_tail_sync(csv_path: Path) -> bool:
    try:
        from m15_iss_loader import ensure_m15_data

        _, refreshed = ensure_m15_data(csv_path, days=255, moex_live=True)
        return refreshed
    except Exception as exc:
        log.warning("MOEX tail sync skipped: %s", exc)
        return False


def ensure_replay_bars(
    csv_path: Path,
    source_name: str,
    *,
    online: bool = True,
    start_date: str | None = None,
) -> dict[str, Any]:
    """
    Load 15м bars for replay: SQLite cache + optional MOEX tail when online.
    Seeds DB from CSV when empty; merges CSV rows after online refresh.
    """
    refreshed = False
    if online and csv_path.is_file():
        refreshed = _try_moex_tail_sync(csv_path)

    count = db_bar_count()
    if count < 100 and csv_path.is_file():
        seed_from_csv(csv_path, source_name)
    elif refreshed and csv_path.is_file():
        seed_from_csv(csv_path, source_name)

    bars = load_bars_from_db(start_date)
    if not bars and csv_path.is_file():
        bars = load_bars_from_csv(csv_path)
        if start_date:
            bars = [b for b in bars if b["tradeDate"] >= start_date]
        if bars:
            seed_from_csv(csv_path, source_name)
            bars = load_bars_from_db(start_date) or bars

    return {
        "bars": bars,
        "source": "sqlite" if db_bar_count() > 0 else "csv",
        "db_count": db_bar_count(),
        "refreshed": refreshed,
        "online": online,
    }
