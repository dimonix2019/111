"""Local SQLite store for live trading credentials, settings, open/closed spreads."""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

DATA_DIR = Path(__file__).resolve().parent.parent / "data"
DB_PATH = DATA_DIR / "live_trading.db"

_SCHEMA_READY = False
_DB_LOCK = threading.RLock()


def _is_db_locked(exc: BaseException) -> bool:
    return isinstance(exc, sqlite3.OperationalError) and "locked" in str(exc).lower()


def _open_conn() -> sqlite3.Connection:
    global _SCHEMA_READY
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=60.0, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=60000")
    conn.execute("PRAGMA synchronous=NORMAL")
    if not _SCHEMA_READY:
        _init(conn)
        _SCHEMA_READY = True
    return conn


@contextmanager
def _connect() -> Iterator[sqlite3.Connection]:
    """Serialize in-process access; wait up to 60s on cross-process locks."""
    with _DB_LOCK:
        conn = _open_conn()
        try:
            yield conn
        finally:
            conn.close()


def db_retry(fn, *, retries: int = 8, delay_sec: float = 0.03):
    """Retry fn() on sqlite 'database is locked' (UI + monitor race at bar close)."""
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


def _init(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS live_settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS live_open_trades (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            mode TEXT NOT NULL,
            account_id TEXT NOT NULL,
            direction TEXT NOT NULL,
            entry_signal TEXT NOT NULL,
            quantity_lots INTEGER NOT NULL,
            entry_time TEXT NOT NULL,
            entry_z REAL,
            entry_spread REAL,
            entry_tatn REAL,
            entry_tatnp REAL,
            execution_notional_rub REAL,
            source TEXT NOT NULL DEFAULT 'AUTO',
            legs_json TEXT,
            created_ms INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS live_closed_trades (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            open_id INTEGER,
            mode TEXT NOT NULL,
            account_id TEXT NOT NULL,
            direction TEXT NOT NULL,
            quantity_lots INTEGER NOT NULL,
            entry_time TEXT NOT NULL,
            exit_time TEXT NOT NULL,
            entry_z REAL,
            exit_z REAL,
            entry_spread REAL,
            exit_spread REAL,
            pnl_rub REAL,
            source TEXT NOT NULL DEFAULT 'AUTO',
            legs_json TEXT,
            closed_ms INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS live_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts_ms INTEGER NOT NULL,
            level TEXT NOT NULL,
            message TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS live_parity_edges (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            created_ms INTEGER NOT NULL,
            bar_ts TEXT NOT NULL,
            bar_ms INTEGER NOT NULL,
            signal TEXT NOT NULL,
            z_score REAL,
            entry_z REAL NOT NULL,
            exit_z REAL NOT NULL,
            trade_id INTEGER,
            check_after_ms INTEGER NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            result_json TEXT
        );
        """
    )
    # Default: monitor ON (APK always-on intent); only seed if unset
    row = conn.execute(
        "SELECT 1 FROM live_settings WHERE key = 'monitor_running'"
    ).fetchone()
    if row is None:
        conn.execute(
            "INSERT INTO live_settings(key, value) VALUES('monitor_running', '1')"
        )
    _ensure_column(conn, "live_open_trades", "entry_spread_iss", "REAL")
    _ensure_column(conn, "live_open_trades", "entry_slip_pts", "REAL")
    _ensure_column(conn, "live_closed_trades", "entry_spread_iss", "REAL")
    _ensure_column(conn, "live_closed_trades", "entry_slip_pts", "REAL")
    _ensure_column(conn, "live_closed_trades", "execution_notional_rub", "REAL")
    _ensure_column(conn, "live_closed_trades", "gross_rub", "REAL")
    _ensure_column(conn, "live_closed_trades", "commission_rub", "REAL")
    _ensure_column(conn, "live_closed_trades", "overnight_rub", "REAL")
    _ensure_column(conn, "live_closed_trades", "pnl_min_rub", "REAL")
    _ensure_column(conn, "live_closed_trades", "pnl_max_rub", "REAL")
    _ensure_column(conn, "live_closed_trades", "hit1_time", "TEXT")
    _ensure_column(conn, "live_closed_trades", "hit2_time", "TEXT")
    _ensure_column(conn, "live_closed_trades", "hit3_time", "TEXT")
    _ensure_column(conn, "live_closed_trades", "account_after_rub", "REAL")
    conn.commit()


def _ensure_column(conn: sqlite3.Connection, table: str, col: str, typedef: str) -> None:
    cols = {str(r[1]) for r in conn.execute(f"PRAGMA table_info({table})")}
    if col not in cols:
        conn.execute(f"ALTER TABLE {table} ADD COLUMN {col} {typedef}")


def get_setting(key: str, default: str | None = None) -> str | None:
    def _op() -> str | None:
        with _connect() as conn:
            row = conn.execute("SELECT value FROM live_settings WHERE key = ?", (key,)).fetchone()
            return row["value"] if row else default

    return db_retry(_op)


def set_setting(key: str, value: str | None) -> None:
    def _op() -> None:
        with _connect() as conn:
            if value is None or value == "":
                conn.execute("DELETE FROM live_settings WHERE key = ?", (key,))
            else:
                conn.execute(
                    "INSERT INTO live_settings(key, value) VALUES(?, ?) "
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                    (key, value),
                )
            conn.commit()

    db_retry(_op)


def get_settings_bundle() -> dict[str, Any]:
    mode = get_setting("execution_mode", "sandbox") or "sandbox"
    token = get_setting(f"token_{mode}", "") or ""
    account = get_setting(f"account_{mode}", "") or ""
    return {
        "mode": mode,
        "has_token": bool(token.strip()),
        "token_preview": (token[:4] + "…" + token[-4:]) if len(token) >= 12 else ("***" if token else ""),
        "account_id": account,
        "auto_execute": (get_setting("auto_execute", "0") or "0") == "1",
        "monitor_running": (get_setting("monitor_running", "1") or "1") == "1",
        "entry_z": float(get_setting("entry_z", "1.3") or "1.3"),
        "exit_z": float(get_setting("exit_z", "1.2") or "1.2"),
        "leverage": float(get_setting("leverage", "7") or "7"),
        "entry_deposit_rub": float(get_setting("entry_deposit_rub", "10000") or "10000"),
        "last_processed_bar_ms": int(get_setting("last_processed_bar_ms", "0") or "0"),
    }


def save_credentials(*, mode: str, token: str | None = None, account_id: str | None = None) -> None:
    mode = "prod" if mode == "prod" else "sandbox"
    set_setting("execution_mode", mode)
    if token is not None:
        set_setting(f"token_{mode}", token.strip())
    if account_id is not None:
        set_setting(f"account_{mode}", account_id.strip())


def get_credentials() -> tuple[str, str, str]:
    """Returns (mode, token, account_id)."""
    mode = get_setting("execution_mode", "sandbox") or "sandbox"
    if mode not in ("sandbox", "prod"):
        mode = "sandbox"
    token = (get_setting(f"token_{mode}", "") or "").strip()
    account = (get_setting(f"account_{mode}", "") or "").strip()
    return mode, token, account


def log_event(message: str, level: str = "info") -> None:
    def _op() -> None:
        with _connect() as conn:
            conn.execute(
                "INSERT INTO live_events(ts_ms, level, message) VALUES(?, ?, ?)",
                (int(time.time() * 1000), level, message),
            )
            conn.execute(
                "DELETE FROM live_events WHERE id NOT IN "
                "(SELECT id FROM live_events ORDER BY id DESC LIMIT 200)"
            )
            conn.commit()

    try:
        db_retry(_op)
    except sqlite3.OperationalError:
        # Never fail the monitor because the event log could not write.
        pass


def list_events(limit: int = 40) -> list[dict[str, Any]]:
    with _connect() as conn:
        rows = conn.execute(
            "SELECT id, ts_ms, level, message FROM live_events ORDER BY id DESC LIMIT ?",
            (limit,),
        ).fetchall()
    return [dict(r) for r in rows]


def get_open_trade() -> dict[str, Any] | None:
    with _connect() as conn:
        row = conn.execute(
            "SELECT * FROM live_open_trades ORDER BY id DESC LIMIT 1"
        ).fetchone()
    return dict(row) if row else None


def insert_open_trade(trade: dict[str, Any]) -> int:
    with _connect() as conn:
        cur = conn.execute(
            """
            INSERT INTO live_open_trades(
                mode, account_id, direction, entry_signal, quantity_lots,
                entry_time, entry_z, entry_spread, entry_tatn, entry_tatnp,
                execution_notional_rub, source, legs_json, created_ms,
                entry_spread_iss, entry_slip_pts
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                trade["mode"],
                trade["account_id"],
                trade["direction"],
                trade["entry_signal"],
                trade["quantity_lots"],
                trade["entry_time"],
                trade.get("entry_z"),
                trade.get("entry_spread"),
                trade.get("entry_tatn"),
                trade.get("entry_tatnp"),
                trade.get("execution_notional_rub"),
                trade.get("source", "AUTO"),
                json.dumps(trade.get("legs") or [], ensure_ascii=False),
                int(time.time() * 1000),
                trade.get("entry_spread_iss"),
                trade.get("entry_slip_pts"),
            ),
        )
        conn.commit()
        return int(cur.lastrowid)


def update_open_trade_fields(trade_id: int, fields: dict[str, Any]) -> None:
    """Частичное обновление открытой сделки (например entry_* после fill)."""
    allowed = {
        "entry_spread",
        "entry_tatn",
        "entry_tatnp",
        "entry_z",
        "entry_time",
        "execution_notional_rub",
        "entry_spread_iss",
        "entry_slip_pts",
    }
    cols = []
    vals: list[Any] = []
    for k, v in fields.items():
        if k not in allowed:
            continue
        cols.append(f"{k} = ?")
        vals.append(v)
    if not cols:
        return
    vals.append(trade_id)
    with _connect() as conn:
        conn.execute(
            f"UPDATE live_open_trades SET {', '.join(cols)} WHERE id = ?",
            vals,
        )
        conn.commit()


def close_open_trade(
    *,
    exit_time: str,
    exit_z: float | None,
    exit_spread: float | None,
    pnl_rub: float | None,
    legs: list[dict[str, Any]] | None = None,
    metrics: dict[str, Any] | None = None,
) -> dict[str, Any] | None:
    open_t = get_open_trade()
    if not open_t:
        return None
    m = metrics or {}
    with _connect() as conn:
        conn.execute(
            """
            INSERT INTO live_closed_trades(
                open_id, mode, account_id, direction, quantity_lots,
                entry_time, exit_time, entry_z, exit_z, entry_spread, exit_spread,
                pnl_rub, source, legs_json, closed_ms,
                entry_spread_iss, entry_slip_pts,
                execution_notional_rub, gross_rub, commission_rub, overnight_rub,
                pnl_min_rub, pnl_max_rub, hit1_time, hit2_time, hit3_time,
                account_after_rub
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                open_t["id"],
                open_t["mode"],
                open_t["account_id"],
                open_t["direction"],
                open_t["quantity_lots"],
                open_t["entry_time"],
                exit_time,
                open_t.get("entry_z"),
                exit_z,
                open_t.get("entry_spread"),
                exit_spread,
                pnl_rub if pnl_rub is not None else m.get("pnl_rub"),
                open_t.get("source", "AUTO"),
                json.dumps(legs or [], ensure_ascii=False),
                int(time.time() * 1000),
                open_t.get("entry_spread_iss"),
                open_t.get("entry_slip_pts"),
                m.get("execution_notional_rub", open_t.get("execution_notional_rub")),
                m.get("gross_rub"),
                m.get("commission_rub"),
                m.get("overnight_rub"),
                m.get("pnl_min_rub"),
                m.get("pnl_max_rub"),
                m.get("hit1_time"),
                m.get("hit2_time"),
                m.get("hit3_time"),
                m.get("account_after_rub"),
            ),
        )
        conn.execute("DELETE FROM live_open_trades WHERE id = ?", (open_t["id"],))
        conn.commit()
    return get_closed_trades(limit=1)[0] if True else None


def get_closed_trades(limit: int = 50) -> list[dict[str, Any]]:
    with _connect() as conn:
        rows = conn.execute(
            "SELECT * FROM live_closed_trades ORDER BY id DESC LIMIT ?",
            (limit,),
        ).fetchall()
    out = []
    for r in rows:
        d = dict(r)
        if d.get("legs_json"):
            try:
                d["legs"] = json.loads(d["legs_json"])
            except json.JSONDecodeError:
                d["legs"] = []
        out.append(d)
    return out


def update_closed_trade(trade_id: int, fields: dict[str, Any]) -> None:
    """Patch closed-trade columns (backfill slip / PnL metrics)."""
    allowed = {
        "entry_spread_iss",
        "entry_slip_pts",
        "execution_notional_rub",
        "gross_rub",
        "commission_rub",
        "overnight_rub",
        "pnl_rub",
        "pnl_min_rub",
        "pnl_max_rub",
        "hit1_time",
        "hit2_time",
        "hit3_time",
        "account_after_rub",
        "entry_z",
        "exit_z",
    }
    cols: list[str] = []
    vals: list[Any] = []
    for k, v in fields.items():
        if k not in allowed:
            continue
        cols.append(f"{k} = ?")
        vals.append(v)
    if not cols:
        return
    vals.append(trade_id)

    def _op() -> None:
        with _connect() as conn:
            conn.execute(
                f"UPDATE live_closed_trades SET {', '.join(cols)} WHERE id = ?",
                vals,
            )
            conn.commit()

    db_retry(_op)


def insert_parity_edge(edge: dict[str, Any]) -> int:
    with _connect() as conn:
        cur = conn.execute(
            """
            INSERT INTO live_parity_edges(
                created_ms, bar_ts, bar_ms, signal, z_score, entry_z, exit_z,
                trade_id, check_after_ms, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending')
            """,
            (
                int(time.time() * 1000),
                edge["bar_ts"],
                int(edge["bar_ms"]),
                edge["signal"],
                edge.get("z_score"),
                float(edge["entry_z"]),
                float(edge["exit_z"]),
                edge.get("trade_id"),
                int(edge["check_after_ms"]),
            ),
        )
        conn.commit()
        return int(cur.lastrowid)


def list_due_parity_edges() -> list[dict[str, Any]]:
    now = int(time.time() * 1000)
    with _connect() as conn:
        rows = conn.execute(
            """
            SELECT * FROM live_parity_edges
            WHERE status = 'pending' AND check_after_ms <= ?
            ORDER BY id ASC
            LIMIT 20
            """,
            (now,),
        ).fetchall()
    return [dict(r) for r in rows]


def update_parity_edge(edge_id: int, result: dict[str, Any]) -> None:
    status = str(result.get("status") or ("matched" if result.get("ok") else "missing"))
    with _connect() as conn:
        conn.execute(
            """
            UPDATE live_parity_edges
            SET status = ?, result_json = ?
            WHERE id = ?
            """,
            (status, json.dumps(result, ensure_ascii=False), edge_id),
        )
        conn.commit()


def list_parity_edges(limit: int = 20) -> list[dict[str, Any]]:
    with _connect() as conn:
        rows = conn.execute(
            """
            SELECT * FROM live_parity_edges
            ORDER BY id DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
    out = []
    for r in rows:
        d = dict(r)
        if d.get("result_json"):
            try:
                d["result"] = json.loads(d["result_json"])
            except json.JSONDecodeError:
                d["result"] = None
        out.append(d)
    return out


def force_parity_due() -> int:
    """Сделать все pending edges due (ручной /parity/check)."""
    with _connect() as conn:
        cur = conn.execute(
            "UPDATE live_parity_edges SET check_after_ms = 0 WHERE status = 'pending'"
        )
        conn.commit()
        return int(cur.rowcount or 0)


def parity_summary() -> dict[str, Any]:
    edges = list_parity_edges(30)
    pending = sum(1 for e in edges if e.get("status") == "pending")
    matched = sum(1 for e in edges if e.get("status") == "matched")
    missing = sum(1 for e in edges if e.get("status") == "missing")
    latest = edges[0] if edges else None
    open_pnl = None
    raw = get_setting("parity_open_pnl_json", "") or ""
    if raw:
        try:
            open_pnl = json.loads(raw)
        except json.JSONDecodeError:
            open_pnl = None
    trades = None
    raw_t = get_setting("parity_trades_json", "") or ""
    if raw_t:
        try:
            trades = json.loads(raw_t)
        except json.JSONDecodeError:
            trades = None
    trades_hard = int((trades or {}).get("hard_mismatches") or 0) if isinstance(trades, dict) else 0
    trades_ok = bool((trades or {}).get("ok")) if isinstance(trades, dict) else True
    return {
        "pending": pending,
        "matched": matched,
        "missing": missing,
        "latest": latest,
        "edges": edges[:10],
        "delay_min": int(get_setting("parity_delay_min", "15") or "15"),
        "open_pnl": open_pnl,
        "trades": trades,
        "trades_hard_mismatches": trades_hard,
        "trades_ok": trades_ok,
    }
