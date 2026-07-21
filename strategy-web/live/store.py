"""Local SQLite store for live trading credentials, settings, open/closed spreads."""

from __future__ import annotations

import json
import sqlite3
import time
from pathlib import Path
from typing import Any

DATA_DIR = Path(__file__).resolve().parent.parent / "data"
DB_PATH = DATA_DIR / "live_trading.db"


def _connect() -> sqlite3.Connection:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    _init(conn)
    return conn


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
    conn.commit()


def get_setting(key: str, default: str | None = None) -> str | None:
    with _connect() as conn:
        row = conn.execute("SELECT value FROM live_settings WHERE key = ?", (key,)).fetchone()
        return row["value"] if row else default


def set_setting(key: str, value: str | None) -> None:
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
                execution_notional_rub, source, legs_json, created_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ),
        )
        conn.commit()
        return int(cur.lastrowid)


def close_open_trade(
    *,
    exit_time: str,
    exit_z: float | None,
    exit_spread: float | None,
    pnl_rub: float | None,
    legs: list[dict[str, Any]] | None = None,
) -> dict[str, Any] | None:
    open_t = get_open_trade()
    if not open_t:
        return None
    with _connect() as conn:
        conn.execute(
            """
            INSERT INTO live_closed_trades(
                open_id, mode, account_id, direction, quantity_lots,
                entry_time, exit_time, entry_z, exit_z, entry_spread, exit_spread,
                pnl_rub, source, legs_json, closed_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                pnl_rub,
                open_t.get("source", "AUTO"),
                json.dumps(legs or [], ensure_ascii=False),
                int(time.time() * 1000),
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


def clear_open_trades() -> None:
    with _connect() as conn:
        conn.execute("DELETE FROM live_open_trades")
        conn.commit()


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
    return {
        "pending": pending,
        "matched": matched,
        "missing": missing,
        "latest": latest,
        "edges": edges[:10],
        "delay_min": int(get_setting("parity_delay_min", "45") or "45"),
    }
