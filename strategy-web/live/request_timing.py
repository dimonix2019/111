"""Медленные HTTP-запросы: лог + снимок для /api/health и файла data/."""

from __future__ import annotations

import json
import logging
import threading
import time
from pathlib import Path
from typing import Any

log = logging.getLogger("moex.request_timing")

DATA_DIR = Path(__file__).resolve().parents[1] / "data"
SLOW_PATH = DATA_DIR / "api_slow.json"
SLOW_LOG = DATA_DIR / "api_slow.log"

# Порог «медленно» (секунды) — стол/status >2с = пользователь уже чувствует зависание.
SLOW_SEC = 2.0
_LOCK = threading.Lock()
_LAST: dict[str, Any] = {}
_RECENT: list[dict[str, Any]] = []
_RECENT_MAX = 40


def record_request(path: str, method: str, status: int, elapsed_sec: float) -> None:
    """Записать запрос; при elapsed >= SLOW_SEC — лог + файл."""
    if elapsed_sec < 0:
        return
    entry = {
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "path": path,
        "method": method,
        "status": int(status),
        "sec": round(float(elapsed_sec), 3),
    }
    slow = elapsed_sec >= SLOW_SEC
    with _LOCK:
        if slow:
            _LAST.clear()
            _LAST.update(entry)
            _RECENT.append(entry)
            del _RECENT[:-_RECENT_MAX]
    if not slow:
        return
    log.warning("slow %s %s %.2fs status=%s", method, path, elapsed_sec, status)
    try:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        snap = snapshot()
        SLOW_PATH.write_text(
            json.dumps(snap, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        with SLOW_LOG.open("a", encoding="utf-8") as f:
            f.write(
                f"{entry['ts']}  {entry['sec']:.3f}s  {method} {path}  {status}\n"
            )
    except OSError:
        pass


def snapshot() -> dict[str, Any]:
    with _LOCK:
        return {
            "slow_sec": SLOW_SEC,
            "last_slow": dict(_LAST) if _LAST else None,
            "recent_slow": list(_RECENT),
        }
