"""Local SQLite store for live trading credentials, settings, open/closed spreads."""

from __future__ import annotations

import json
import re
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
        CREATE TABLE IF NOT EXISTS decision_bars (
            bar_ts TEXT PRIMARY KEY,
            open REAL,
            high REAL,
            low REAL,
            close REAL,
            spread_percent REAL,
            z_score REAL,
            signal TEXT,
            decided_at_msk TEXT,
            revised INTEGER NOT NULL DEFAULT 0
        );
        """
    )
    _ensure_column(conn, "decision_bars", "tatn_close", "REAL")
    _ensure_column(conn, "decision_bars", "tatnp_close", "REAL")
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
    try:
        tp = float(get_setting("take_profit_pct", "0") or "0")
    except ValueError:
        tp = 0.0
    if tp not in (0.0, 1.0, 2.0, 3.0):
        # snap to nearest allowed
        tp = min((0.0, 1.0, 2.0, 3.0), key=lambda x: abs(x - tp))
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
        "take_profit_pct": tp,
        "signal_mode": get_setting("signal_mode", "tip1m") or "tip1m",
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


def _msk_now_label() -> str:
    from datetime import datetime
    from zoneinfo import ZoneInfo

    return datetime.now(ZoneInfo("Europe/Moscow")).strftime("%Y-%m-%d %H:%M:%S")


def upsert_decision_bar(
    *,
    bar_ts: str,
    open_: float | None = None,
    high: float | None = None,
    low: float | None = None,
    close: float | None = None,
    spread_percent: float | None = None,
    z_score: float | None = None,
    signal: str | None = None,
    decided_at_msk: str | None = None,
    revised: int = 0,
    tatn_close: float | None = None,
    tatnp_close: float | None = None,
) -> None:
    """Freeze OHLC/Z (+ legs) at decision time; UPSERT by bar_ts."""
    ts = (bar_ts or "").strip()
    if not ts:
        return
    decided = decided_at_msk or _msk_now_label()

    def _op() -> None:
        with _connect() as conn:
            conn.execute(
                """
                INSERT INTO decision_bars(
                    bar_ts, open, high, low, close, spread_percent, z_score,
                    signal, decided_at_msk, revised, tatn_close, tatnp_close
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(bar_ts) DO UPDATE SET
                    open = COALESCE(excluded.open, decision_bars.open),
                    high = COALESCE(excluded.high, decision_bars.high),
                    low = COALESCE(excluded.low, decision_bars.low),
                    close = COALESCE(excluded.close, decision_bars.close),
                    spread_percent = COALESCE(excluded.spread_percent, decision_bars.spread_percent),
                    z_score = COALESCE(excluded.z_score, decision_bars.z_score),
                    signal = COALESCE(excluded.signal, decision_bars.signal),
                    decided_at_msk = excluded.decided_at_msk,
                    revised = MAX(decision_bars.revised, excluded.revised),
                    tatn_close = COALESCE(excluded.tatn_close, decision_bars.tatn_close),
                    tatnp_close = COALESCE(excluded.tatnp_close, decision_bars.tatnp_close)
                """,
                (
                    ts,
                    open_,
                    high,
                    low,
                    close,
                    spread_percent,
                    z_score,
                    signal,
                    decided,
                    int(revised),
                    tatn_close,
                    tatnp_close,
                ),
            )
            conn.commit()

    db_retry(_op)


def upsert_decision_bar_from_series(
    bar: dict[str, Any] | None,
    *,
    z_score: float | None = None,
    signal: str | None = None,
    revised: int = 0,
) -> None:
    """Capture snapshot fields from a live/replay bar dict."""
    if not bar:
        return
    ts = str(bar.get("tradeDate") or "").strip()
    if not ts:
        return
    try:
        spread = float(bar["spreadPercent"]) if bar.get("spreadPercent") is not None else None
    except (TypeError, ValueError):
        spread = None
    try:
        z = float(z_score) if z_score is not None else (
            float(bar["zScore"]) if bar.get("zScore") is not None else None
        )
    except (TypeError, ValueError):
        z = None
    try:
        tatn = float(bar["tatnClose"]) if bar.get("tatnClose") is not None else None
    except (TypeError, ValueError):
        tatn = None
    try:
        tatnp = float(bar["tatnpClose"]) if bar.get("tatnpClose") is not None else None
    except (TypeError, ValueError):
        tatnp = None
    # Point-in-time: OHLC = spread (no intra-bar candle in M15 series).
    ohlc = spread
    upsert_decision_bar(
        bar_ts=ts,
        open_=ohlc,
        high=ohlc,
        low=ohlc,
        close=ohlc,
        spread_percent=spread,
        z_score=z,
        signal=signal,
        revised=revised,
        tatn_close=tatn,
        tatnp_close=tatnp,
    )


def get_decision_bars(
    from_ts: str | None = None,
    to_ts: str | None = None,
) -> list[dict[str, Any]]:
    def _op() -> list[dict[str, Any]]:
        with _connect() as conn:
            sql = "SELECT * FROM decision_bars"
            args: list[Any] = []
            clauses: list[str] = []
            if from_ts:
                clauses.append("bar_ts >= ?")
                args.append(from_ts)
            if to_ts:
                clauses.append("bar_ts <= ?")
                args.append(to_ts)
            if clauses:
                sql += " WHERE " + " AND ".join(clauses)
            sql += " ORDER BY bar_ts"
            rows = conn.execute(sql, args).fetchall()
        return [dict(r) for r in rows]

    try:
        return db_retry(_op)
    except sqlite3.OperationalError:
        return []


def get_decision_bar_timestamps() -> set[str]:
    """Timestamps frozen for signal-path upsert skip."""
    def _op() -> set[str]:
        with _connect() as conn:
            rows = conn.execute("SELECT bar_ts FROM decision_bars").fetchall()
        return {str(r["bar_ts"]) for r in rows if r["bar_ts"]}

    try:
        return db_retry(_op)
    except sqlite3.OperationalError:
        return set()


def backfill_decision_z_from_parity_edges() -> int:
    """Best-effort: fill missing decision_bars Z from live_parity_edges (OHLC may stay null)."""
    def _op() -> int:
        with _connect() as conn:
            edges = conn.execute(
                """
                SELECT bar_ts, z_score, signal
                FROM live_parity_edges
                WHERE z_score IS NOT NULL AND bar_ts IS NOT NULL AND bar_ts != ''
                ORDER BY id ASC
                """
            ).fetchall()
            n = 0
            for e in edges:
                ts = str(e["bar_ts"]).strip()
                if not ts:
                    continue
                existing = conn.execute(
                    "SELECT z_score, open, close FROM decision_bars WHERE bar_ts = ?",
                    (ts,),
                ).fetchone()
                if existing is not None and existing["z_score"] is not None:
                    # Already have a freeze with Z — skip.
                    continue
                try:
                    z = float(e["z_score"])
                except (TypeError, ValueError):
                    continue
                conn.execute(
                    """
                    INSERT INTO decision_bars(
                        bar_ts, open, high, low, close, spread_percent, z_score,
                        signal, decided_at_msk, revised
                    ) VALUES (?, NULL, NULL, NULL, NULL, NULL, ?, ?, ?, 0)
                    ON CONFLICT(bar_ts) DO UPDATE SET
                        z_score = COALESCE(decision_bars.z_score, excluded.z_score),
                        signal = COALESCE(decision_bars.signal, excluded.signal)
                    """,
                    (ts, z, e["signal"], _msk_now_label()),
                )
                n += 1
            conn.commit()
            return n

    try:
        return int(db_retry(_op) or 0)
    except sqlite3.OperationalError:
        return 0


_MONITOR_OK_Z_RE = re.compile(
    r"OK ·\s*(?P<bar>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})\s*·\s*Z=(?P<z>-?\d+(?:\.\d+)?)"
)
_SIGNAL_AT_RE = re.compile(
    r"Сигнал\s+(?P<sig>\S+)\s+@\s+(?P<bar>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})\s+Z=(-?\d+(?:\.\d+)?)"
)


def parse_tip_z_from_live_events(
    events: list[dict[str, Any]] | None = None,
    *,
    limit: int = 500,
) -> dict[str, float]:
    """Last tip Z per bar from «Монитор OK · {bar} · Z=…» heartbeats (closest to settle)."""
    if events is None:
        events = list_events(limit)
    # list_events is newest-first; walk oldest→newest so last write wins.
    chron = list(reversed(events))
    out: dict[str, float] = {}
    for e in chron:
        msg = str(e.get("message") or "")
        m = _MONITOR_OK_Z_RE.search(msg)
        if not m:
            continue
        try:
            out[m.group("bar")] = float(m.group("z"))
        except (TypeError, ValueError):
            continue
    return out


def parse_live_signal_bars_from_events(
    events: list[dict[str, Any]] | None = None,
    *,
    limit: int = 500,
) -> set[str]:
    """Bars where live logged an actionable Сигнал … @ bar (ENTER_*/EXIT_*)."""
    if events is None:
        events = list_events(limit)
    out: set[str] = set()
    for e in events:
        msg = str(e.get("message") or "")
        m = _SIGNAL_AT_RE.search(msg)
        if not m:
            continue
        sig = (m.group("sig") or "").upper()
        if sig and sig != "NONE":
            out.add(m.group("bar"))
    return out


def backfill_decision_z_from_live_events(
    *,
    entry_z: float | None = None,
    exit_z: float | None = None,
    events_limit: int = 500,
) -> int:
    """Fill decision_bars Z from tip heartbeats (pre-freeze phantom killer).

    Uses last tip Z per bar. Real freezes (non-backfill decided_at) are never
    overwritten. Prior tip-backfill rows are refreshed.

    Tip spikes that would ENTER without a live Сигнал event are stored with
    signal=NONE (raw tip Z kept). as_live overlay sets liveSignal so Testing
    sim suppresses geometric crosses Prod never took.
    """
    from live.signals import Position, Signal, determine_z_signal

    events = list_events(events_limit)
    tip_z = parse_tip_z_from_live_events(events)
    if not tip_z:
        return 0
    confirmed = parse_live_signal_bars_from_events(events)
    try:
        entry = float(entry_z) if entry_z is not None else float(
            get_setting("entry_z", "1.6") or "1.6"
        )
    except (TypeError, ValueError):
        entry = 1.6
    try:
        exit_ = float(exit_z) if exit_z is not None else float(
            get_setting("exit_z", "1.3") or "1.3"
        )
    except (TypeError, ValueError):
        exit_ = 1.3

    existing = {
        str(r["bar_ts"]): r
        for r in get_decision_bars()
        if r.get("bar_ts") and r.get("z_score") is not None
    }
    prev_z: float | None = None
    pos = Position.FLAT
    n = 0
    decided = _msk_now_label() + " backfill:live_events"

    for ts in sorted(set(tip_z) | set(existing)):
        row = existing.get(ts)
        is_real = bool(row) and "backfill:live_events" not in str(
            row.get("decided_at_msk") or ""
        )
        if is_real:
            try:
                prev_z = float(row["z_score"])
            except (TypeError, ValueError):
                prev_z = tip_z.get(ts, prev_z)
            raw_sig = str(row.get("signal") or Signal.NONE.value)
            if raw_sig == Signal.ENTER_LONG.value:
                pos = Position.LONG
            elif raw_sig == Signal.ENTER_SHORT.value:
                pos = Position.SHORT
            elif raw_sig in (Signal.EXIT_LONG.value, Signal.EXIT_SHORT.value):
                pos = Position.FLAT
            continue

        if ts not in tip_z:
            continue

        z = float(tip_z[ts])
        sig = Signal.NONE
        if prev_z is not None:
            sig = determine_z_signal(prev_z, z, pos, entry, exit_)
            if sig in (Signal.ENTER_LONG, Signal.ENTER_SHORT) and ts not in confirmed:
                sig = Signal.NONE
        # Force replace of prior tip-backfill (upsert COALESCE would keep old Z).
        _replace_decision_z(ts, z_score=z, signal=sig.value, decided_at_msk=decided)
        n += 1
        if sig == Signal.ENTER_LONG:
            pos = Position.LONG
        elif sig == Signal.ENTER_SHORT:
            pos = Position.SHORT
        elif sig in (Signal.EXIT_LONG, Signal.EXIT_SHORT):
            pos = Position.FLAT
        prev_z = z

    return n


def _replace_decision_z(
    bar_ts: str,
    *,
    z_score: float,
    signal: str,
    decided_at_msk: str,
) -> None:
    """Upsert Z/signal even when a prior backfill row already has z_score."""

    def _op() -> None:
        with _connect() as conn:
            conn.execute(
                """
                INSERT INTO decision_bars(
                    bar_ts, open, high, low, close, spread_percent, z_score,
                    signal, decided_at_msk, revised
                ) VALUES (?, NULL, NULL, NULL, NULL, NULL, ?, ?, ?, 0)
                ON CONFLICT(bar_ts) DO UPDATE SET
                    z_score = excluded.z_score,
                    signal = excluded.signal,
                    decided_at_msk = excluded.decided_at_msk
                """,
                (bar_ts, z_score, signal, decided_at_msk),
            )
            conn.commit()

    db_retry(_op)


def overlay_decision_bars_on_series(bars: list[dict[str, Any]]) -> dict[str, Any]:
    """In-place overlay of frozen OHLC/Z onto replay bars. Returns coverage meta."""
    if not bars:
        return {
            "as_live": True,
            "locked_count": 0,
            "bars_count": 0,
            "coverage": 0.0,
            "locked_from": None,
            "locked_to": None,
        }
    from_ts = str(bars[0].get("tradeDate") or "") or None
    to_ts = str(bars[-1].get("tradeDate") or "") or None
    locked = {d["bar_ts"]: d for d in get_decision_bars(from_ts=from_ts, to_ts=to_ts)}
    locked_count = 0
    locked_from: str | None = None
    locked_to: str | None = None
    for b in bars:
        ts = str(b.get("tradeDate") or "")
        d = locked.get(ts)
        if not d:
            continue
        locked_count += 1
        locked_from = ts if locked_from is None else min(locked_from, ts)
        locked_to = ts if locked_to is None else max(locked_to, ts)
        if d.get("spread_percent") is not None:
            b["spreadPercent"] = float(d["spread_percent"])
        if d.get("z_score") is not None:
            b["zScore"] = float(d["z_score"])
        if d.get("tatn_close") is not None:
            b["tatnClose"] = float(d["tatn_close"])
        if d.get("tatnp_close") is not None:
            b["tatnpClose"] = float(d["tatnp_close"])
        # Prefer explicit close (spread) if present and legs missing.
        if d.get("close") is not None and d.get("spread_percent") is None:
            b["spreadPercent"] = float(d["close"])
        # Frozen Prod decision — Testing sim must not invent ENTER when signal is NONE.
        if d.get("signal") is not None:
            b["liveSignal"] = str(d["signal"])
    n = len(bars)
    return {
        "as_live": True,
        "locked_count": locked_count,
        "bars_count": n,
        "coverage": (locked_count / n) if n else 0.0,
        "locked_from": locked_from,
        "locked_to": locked_to,
    }


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
