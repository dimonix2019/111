"""SQLite offline store + MOEX tail sync for Bar Replay."""

from __future__ import annotations

import logging
import sqlite3
import sys
import threading
import time
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

_sync_lock = threading.Lock()
_seed_lock = threading.Lock()
_sync_running = False
_last_sync_ok_ms = 0
_MIN_SYNC_GAP_SEC = 20.0
_SCHEMA_READY = False


def _is_db_locked(exc: BaseException) -> bool:
    return isinstance(exc, sqlite3.OperationalError) and "locked" in str(exc).lower()


def _connect(*, busy_timeout_ms: int = 5000) -> sqlite3.Connection:
    global _SCHEMA_READY
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=max(1.0, busy_timeout_ms / 1000.0), check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute(f"PRAGMA busy_timeout={int(busy_timeout_ms)}")
    conn.execute("PRAGMA synchronous=NORMAL")
    if not _SCHEMA_READY:
        _init_schema(conn)
        _SCHEMA_READY = True
    return conn


def db_retry(fn, *, retries: int = 8, delay_sec: float = 0.03):
    wait = delay_sec
    last: BaseException | None = None
    for attempt in range(retries):
        try:
            return fn()
        except sqlite3.OperationalError as exc:
            last = exc
            if not _is_db_locked(exc) or attempt >= retries - 1:
                raise
            time.sleep(wait)
            wait = min(wait * 1.7, 0.6)
    assert last is not None
    raise last


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
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
        """
    )
    conn.commit()


def _meta_get(conn: sqlite3.Connection, key: str) -> str | None:
    row = conn.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
    return str(row["value"]) if row else None


def _meta_set(conn: sqlite3.Connection, key: str, value: str) -> None:
    conn.execute(
        "INSERT INTO meta(key, value) VALUES(?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        (key, value),
    )
    conn.commit()


def apply_rolling_z_to_bars(bars: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Пересчитать zScore rolling 30д (parity Android / zsim)."""
    if not bars:
        return bars
    from zsim import apply_z_scores_rolling

    spreads = [float(b["spreadPercent"]) for b in bars]
    timestamps = [str(b["tradeDate"]) for b in bars]
    zs = apply_z_scores_rolling(spreads, timestamps)
    for bar, z in zip(bars, zs):
        bar["zScore"] = float(z)
    return bars


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
    """Single-flight upsert — параллельные desk-polls не должны долбить один CSV."""
    if not _seed_lock.acquire(blocking=False):
        log.info("seed_from_csv skipped (already running): %s", source_name)
        return 0
    try:
        bars = apply_rolling_z_to_bars(load_bars_from_csv(csv_path))
        conn = _connect(busy_timeout_ms=15000)
        try:
            n = _upsert_bars(conn, bars, source_name)
            _meta_set(conn, "z_mode", "rolling30")
            conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            return n
        finally:
            conn.close()
    finally:
        _seed_lock.release()


def _migrate_rolling_z_if_needed() -> None:
    """Один раз пересчитать Z в SQLite (старый seed был global)."""
    with _connect() as conn:
        if _meta_get(conn, "z_mode") == "rolling30":
            return
        cur = conn.execute(
            """
            SELECT timestamp_ms, trade_date, z_score, spread_percent,
                   tatn_close, tatnp_close
            FROM m15_bars
            ORDER BY timestamp_ms
            """
        )
        bars = [bar_row_to_dict(tuple(r)) for r in cur.fetchall()]
    if not bars:
        with _connect() as conn:
            _meta_set(conn, "z_mode", "rolling30")
        return
    bars = apply_rolling_z_to_bars(bars)
    with _connect() as conn:
        _upsert_bars(conn, bars, "rolling30-migrate")
        _meta_set(conn, "z_mode", "rolling30")
    log.info("SQLite Z migrated to rolling30 (%s bars)", len(bars))


def load_last_bars(n: int = 2) -> list[dict[str, Any]]:
    """Хвост ряда без полной выгрузки (desk/summary)."""
    n = max(1, min(int(n), 50))

    def _op() -> list[dict[str, Any]]:
        with _connect() as conn:
            cur = conn.execute(
                """
                SELECT timestamp_ms, trade_date, z_score, spread_percent,
                       tatn_close, tatnp_close
                FROM m15_bars
                ORDER BY timestamp_ms DESC
                LIMIT ?
                """,
                (n,),
            )
            rows = [bar_row_to_dict(tuple(r)) for r in cur.fetchall()]
        rows.reverse()
        return rows

    return db_retry(_op)


def load_bars_from_db(start_date: str | None = None) -> list[dict[str, Any]]:
    def _op() -> list[dict[str, Any]]:
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

    return db_retry(_op)


def db_bar_count() -> int:
    with _connect() as conn:
        row = conn.execute("SELECT COUNT(*) AS c FROM m15_bars").fetchone()
        return int(row["c"]) if row else 0


def _db_last_trade_date() -> str | None:
    with _connect() as conn:
        row = conn.execute(
            "SELECT trade_date FROM m15_bars ORDER BY timestamp_ms DESC LIMIT 1"
        ).fetchone()
    return str(row["trade_date"]) if row else None


def _db_first_trade_date() -> str | None:
    with _connect() as conn:
        row = conn.execute(
            "SELECT trade_date FROM m15_bars ORDER BY timestamp_ms ASC LIMIT 1"
        ).fetchone()
    return str(row["trade_date"]) if row else None


def _csv_first_trade_date(csv_path: Path) -> str | None:
    try:
        with csv_path.open("r", encoding="utf-8") as f:
            f.readline()
            first = f.readline().strip()
        if not first:
            return None
        return first.split(",", 1)[0].strip()
    except OSError:
        return None


def _csv_last_trade_date(csv_path: Path) -> str | None:
    try:
        from m15_iss_loader import _csv_last_timestamp

        return _csv_last_timestamp(csv_path)
    except Exception:
        return None


def _seed_if_csv_ahead(csv_path: Path, source_name: str) -> bool:
    """Seed SQLite when CSV is newer, longer, or has earlier history than DB."""
    csv_last = _csv_last_trade_date(csv_path)
    if not csv_last or not csv_path.is_file():
        return False

    db_last = _db_last_trade_date()
    db_first = _db_first_trade_date()
    csv_first = _csv_first_trade_date(csv_path)
    count = db_bar_count()

    need = count < 100
    if db_last and csv_last[:16] > db_last[:16]:
        need = True
    if csv_first and db_first and csv_first[:16] < db_first[:16]:
        need = True
    try:
        from m15_iss_loader import _csv_row_count

        if _csv_row_count(csv_path) > count + 100:
            need = True
    except Exception:
        pass

    if not need:
        return False

    seed_from_csv(csv_path, source_name)
    log.info(
        "SQLite seeded from CSV (csv=%s…%s db_was=%s…%s)",
        csv_first,
        csv_last,
        db_first,
        db_last,
    )
    return True


def _filter_bars_to_csv_lookback(
    bars: list[dict[str, Any]], csv_path: Path
) -> list[dict[str, Any]]:
    """Обрезать общую SQLite-кэш до lookback выбранного CSV (255/365/1095)."""
    if not bars:
        return bars
    try:
        from m15_iss_loader import lookback_days_for_path
    except Exception:
        return bars

    from datetime import datetime, timedelta

    days = lookback_days_for_path(csv_path)
    last = str(bars[-1].get("tradeDate") or "")
    try:
        last_dt = datetime.strptime(last[:19], "%Y-%m-%d %H:%M:%S")
    except ValueError:
        try:
            last_dt = datetime.strptime(last[:16], "%Y-%m-%d %H:%M")
        except ValueError:
            return bars
    cut = last_dt - timedelta(days=days)
    cut_key = cut.strftime("%Y-%m-%d %H:%M:%S")
    out = [b for b in bars if str(b.get("tradeDate") or "") >= cut_key]
    return out or bars


def _moex_tail_sync_inner(csv_path: Path) -> bool:
    from m15_iss_loader import apply_live_last_overlay, ensure_m15_data, lookback_days_for_path

    days = lookback_days_for_path(csv_path)
    _, refreshed = ensure_m15_data(csv_path, days=days, moex_live=True)
    # Даже если свечи не сдвинулись (утро до первой 10м) — LAST из marketdata.
    tip = apply_live_last_overlay(csv_path)
    return bool(refreshed or tip)

def _try_moex_tail_sync(csv_path: Path, *, timeout_sec: float = 25.0) -> bool:
    """Догрузка хвоста MOEX — с таймаутом, чтобы не блокировать UI минутами.

    Важно: не использовать ``with ThreadPoolExecutor`` — при TimeoutError
    ``shutdown(wait=True)`` всё равно ждёт ISS и подвешивает desk/markets.
    """
    import concurrent.futures

    pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    fut = pool.submit(_moex_tail_sync_inner, csv_path)
    try:
        return bool(fut.result(timeout=timeout_sec))
    except concurrent.futures.TimeoutError:
        log.warning("MOEX tail sync timed out after %.0fs — отдаём кэш", timeout_sec)
        return False
    except Exception as exc:
        log.warning("MOEX tail sync skipped: %s", exc)
        return False
    finally:
        # orphaned download may finish later; do not block the request thread
        pool.shutdown(wait=False, cancel_futures=True)


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
        bars = _filter_bars_to_csv_lookback(bars, csv_path)
        if start_date:
            bars = [b for b in bars if b["tradeDate"] >= start_date]
        return bars

    if not csv_path.is_file():
        return []

    bars = load_bars_from_csv(csv_path)
    if start_date:
        bars = [b for b in bars if b["tradeDate"] >= start_date]
    if bars:
        seed_from_csv(csv_path, source_name)
        bars = load_bars_from_db(start_date) or bars
        bars = _filter_bars_to_csv_lookback(bars, csv_path)
        if start_date:
            bars = [b for b in bars if b["tradeDate"] >= start_date]
    return bars


def _default_sync_timeout(csv_path: Path) -> float:
    try:
        from m15_iss_loader import lookback_days_for_path

        days = lookback_days_for_path(csv_path)
        if days >= 900:
            return 90.0
        if days >= 300:
            return 45.0
    except Exception:
        pass
    return 25.0


def sync_moex_tail(
    csv_path: Path,
    source_name: str,
    *,
    timeout_sec: float | None = None,
    force: bool = False,
) -> bool:
    """
    Single-flight MOEX tail → CSV → SQLite.
    Всегда пытается досеять SQLite, если CSV ушёл вперёд (в т.ч. после timeout).
    """
    global _sync_running, _last_sync_ok_ms

    if not csv_path.is_file():
        return False

    if timeout_sec is None:
        timeout_sec = _default_sync_timeout(csv_path)

    # force (monitor): подождать чужой sync, затем при необходимости запустить свой.
    if force:
        deadline = time.time() + timeout_sec
        while True:
            with _sync_lock:
                busy = _sync_running
            if not busy:
                break
            if time.time() >= deadline:
                _seed_if_csv_ahead(csv_path, source_name)
                return False
            time.sleep(0.2)

    now = time.time()
    with _sync_lock:
        if _sync_running:
            _seed_if_csv_ahead(csv_path, source_name)
            return False
        if not force and _last_sync_ok_ms and (now - _last_sync_ok_ms / 1000.0) < _MIN_SYNC_GAP_SEC:
            _seed_if_csv_ahead(csv_path, source_name)
            return False
        _sync_running = True

    refreshed = False
    try:
        refreshed = _try_moex_tail_sync(csv_path, timeout_sec=timeout_sec)
        if refreshed:
            seed_from_csv(csv_path, source_name)
            _last_sync_ok_ms = int(time.time() * 1000)
            log.info("MOEX tail synced → %s", source_name)
        else:
            # Timeout/skip: CSV мог всё же обновиться в worker до shutdown — досеять.
            if _seed_if_csv_ahead(csv_path, source_name):
                _last_sync_ok_ms = int(time.time() * 1000)
                refreshed = True
    finally:
        with _sync_lock:
            _sync_running = False
    return refreshed


def _background_moex_sync(csv_path: Path, source_name: str) -> None:
    try:
        sync_moex_tail(csv_path, source_name)
    except Exception as exc:
        log.warning("Background MOEX sync failed: %s", exc)


def ensure_replay_bars(
    csv_path: Path,
    source_name: str,
    *,
    online: bool = True,
    start_date: str | None = None,
    wait_sync: bool = False,
    sync_timeout_sec: float | None = None,
) -> dict[str, Any]:
    """
    Load 15м bars for replay: SQLite cache + optional MOEX tail when online.
    По умолчанию отдаём кэш и синхроним в фоне.
    wait_sync=True — дождаться sync (live monitor / desk), затем перечитать SQLite.
    """
    # Подтянуть CSV→SQLite, если прошлый sync обновил файл, но seed сорвался.
    if csv_path.is_file():
        _seed_if_csv_ahead(csv_path, source_name)
    _migrate_rolling_z_if_needed()

    bars = _load_cached_bars(csv_path, source_name, start_date)
    refreshed = False

    if online and csv_path.is_file():
        if wait_sync:
            refreshed = sync_moex_tail(
                csv_path,
                source_name,
                timeout_sec=sync_timeout_sec,
                force=True,
            )
            bars = _load_cached_bars(csv_path, source_name, start_date)
        else:
            threading.Thread(
                target=_background_moex_sync,
                args=(csv_path, source_name),
                daemon=True,
            ).start()

    return {
        "bars": bars,
        "source": "sqlite" if db_bar_count() > 0 else "csv",
        "db_count": db_bar_count(),
        "refreshed": refreshed,
        "online": online,
    }
