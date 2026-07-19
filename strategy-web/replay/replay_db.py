"""SQLite offline store + MOEX tail sync for Bar Replay."""

from __future__ import annotations

import logging
import sqlite3
import sys
import threading
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
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=30000")
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


def _moex_tail_sync_inner(csv_path: Path) -> bool:
    from m15_iss_loader import ensure_m15_data

    _, refreshed = ensure_m15_data(csv_path, days=255, moex_live=True)
    return refreshed


def _try_moex_tail_sync(csv_path: Path, *, timeout_sec: float = 12.0) -> bool:
    """Догрузка хвоста MOEX — с таймаутом, чтобы не блокировать UI минутами."""
    import concurrent.futures

    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            fut = pool.submit(_moex_tail_sync_inner, csv_path)
            return bool(fut.result(timeout=timeout_sec))
    except concurrent.futures.TimeoutError:
        log.warning("MOEX tail sync timed out after %.0fs — отдаём кэш", timeout_sec)
        return False
    except Exception as exc:
        log.warning("MOEX tail sync skipped: %s", exc)
        return False


def _load_cached_bars(
    csv_path: Path,
    source_name: str,
    start_date: str | None,
) -> list[dict[str, Any]]:
    count = db_bar_count()
    if count < 100 and csv_path.is_file():
        seed_from_csv(csv_path, source_name)

    bars = load_bars_from_db(start_date)
    if bars:
        return bars

    if not csv_path.is_file():
        return []

    bars = load_bars_from_csv(csv_path)
    if start_date:
        bars = [b for b in bars if b["tradeDate"] >= start_date]
    if bars:
        seed_from_csv(csv_path, source_name)
        bars = load_bars_from_db(start_date) or bars
    return bars


def _background_moex_sync(csv_path: Path, source_name: str) -> None:
    if not _try_moex_tail_sync(csv_path):
        return
    try:
        seed_from_csv(csv_path, source_name)
        log.info("MOEX tail synced in background → %s", source_name)
    except Exception as exc:
        log.warning("Background MOEX seed failed: %s", exc)


def ensure_replay_bars(
    csv_path: Path,
    source_name: str,
    *,
    online: bool = True,
    start_date: str | None = None,
) -> dict[str, Any]:
    """
    Load 15м bars for replay: SQLite cache + optional MOEX tail when online.
    Сначала отдаём кэш (CSV/SQLite), затем пробуем догрузку MOEX с таймаутом.
    """
    bars = _load_cached_bars(csv_path, source_name, start_date)

    if online and csv_path.is_file() and bars:
        threading.Thread(
            target=_background_moex_sync,
            args=(csv_path, source_name),
            daemon=True,
        ).start()

    return {
        "bars": bars,
        "source": "sqlite" if db_bar_count() > 0 else "csv",
        "db_count": db_bar_count(),
        "refreshed": False,
        "online": online,
    }
