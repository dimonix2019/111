"""External watchdog for strategy-web live monitor (Windows / local).

Separate process from uvicorn — survives ISS hangs inside the server.

Soft: POST /api/live/monitor/restart when health says stale / thread dead.
Hard: kill :8765 + relaunch replay_app.py if HTTP dead or soft failed twice.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

STRATEGY_WEB = Path(__file__).resolve().parents[1]
REPO_ROOT = STRATEGY_WEB.parent
DATA_DIR = STRATEGY_WEB / "data"
LOG_PATH = DATA_DIR / "watchdog.log"
REPLAY_APP = STRATEGY_WEB / "replay" / "replay_app.py"

BASE_URL = os.environ.get("MOEX_WATCHDOG_URL", "http://127.0.0.1:8765").rstrip("/")
PORT = int(os.environ.get("MOEX_WATCHDOG_PORT", "8765"))
INTERVAL_SEC = float(os.environ.get("MOEX_WATCHDOG_INTERVAL_SEC", "60"))
HTTP_TIMEOUT = float(os.environ.get("MOEX_WATCHDOG_HTTP_TIMEOUT", "5"))
MANAGE_SERVER = os.environ.get("MOEX_WATCHDOG_MANAGE_SERVER", "1").strip().lower() in (
    "1",
    "true",
    "yes",
)

_soft_fail_streak = 0
_server_proc: subprocess.Popen | None = None
_browser_opened_once = False


def _ts() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def log(msg: str) -> None:
    line = f"{_ts()}  {msg}"
    print(line, flush=True)
    try:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        with LOG_PATH.open("a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError:
        pass


def _toast(title: str, body: str) -> None:
    """Best-effort Windows notification (no hard dependency)."""
    if sys.platform != "win32":
        return
    try:
        ps = (
            "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, "
            "ContentType = WindowsRuntime] > $null; "
            "$xml = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent("
            "[Windows.UI.Notifications.ToastTemplateType]::ToastText02); "
            "$text = @($xml.GetElementsByTagName('text')); "
            f"$text[0].AppendChild($xml.CreateTextNode({json.dumps(title)})) | Out-Null; "
            f"$text[1].AppendChild($xml.CreateTextNode({json.dumps(body)})) | Out-Null; "
            "$toast = [Windows.UI.Notifications.ToastNotification]::new($xml); "
            "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('MOEX Live Watchdog')"
            ".Show($toast)"
        )
        subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps],
            check=False,
            capture_output=True,
            timeout=15,
        )
    except Exception:
        try:
            subprocess.run(
                ["msg", "*", f"/TIME:10", f"{title}: {body}"],
                check=False,
                capture_output=True,
                timeout=10,
            )
        except Exception:
            pass


def _http_json(method: str, path: str, timeout: float = HTTP_TIMEOUT) -> dict | None:
    req = urllib.request.Request(
        f"{BASE_URL}{path}",
        method=method,
        headers={"Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return json.loads(raw) if raw else {}
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, json.JSONDecodeError, OSError):
        return None


def _pids_on_port(port: int) -> list[int]:
    if sys.platform != "win32":
        return []
    try:
        ps = (
            f"Get-NetTCPConnection -LocalPort {port} -State Listen -ErrorAction SilentlyContinue "
            f"| Select-Object -ExpandProperty OwningProcess -Unique"
        )
        r = subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps],
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
        pids: list[int] = []
        for line in (r.stdout or "").splitlines():
            line = line.strip()
            if line.isdigit():
                pids.append(int(line))
        return pids
    except Exception:
        return []


def kill_port(port: int) -> None:
    global _server_proc
    pids = _pids_on_port(port)
    for pid in pids:
        log(f"hard: stopping PID {pid} on :{port}")
        try:
            if sys.platform == "win32":
                subprocess.run(
                    ["taskkill", "/PID", str(pid), "/F", "/T"],
                    check=False,
                    capture_output=True,
                    timeout=15,
                )
            else:
                os.kill(pid, 9)
        except Exception as exc:
            log(f"hard: kill {pid} failed: {exc}")
    if _server_proc is not None and _server_proc.poll() is None:
        try:
            _server_proc.terminate()
            _server_proc.wait(timeout=5)
        except Exception:
            try:
                _server_proc.kill()
            except Exception:
                pass
    _server_proc = None
    time.sleep(1.0)


def start_server() -> bool:
    global _server_proc, _browser_opened_once
    if not REPLAY_APP.is_file():
        log(f"hard: missing {REPLAY_APP}")
        return False
    env = os.environ.copy()
    # Браузер только при первом старте сессии (как старый bat), не на каждом hard restart.
    want_browser = env.get("MOEX_REPLAY_OPEN_BROWSER", "").strip().lower() in (
        "1",
        "true",
        "yes",
    )
    if want_browser and not _browser_opened_once:
        env["MOEX_REPLAY_OPEN_BROWSER"] = "1"
        _browser_opened_once = True
    else:
        env["MOEX_REPLAY_OPEN_BROWSER"] = "0"
    log(f"hard: starting {REPLAY_APP} (browser={env['MOEX_REPLAY_OPEN_BROWSER']})")
    try:
        creationflags = 0
        if sys.platform == "win32":
            creationflags = subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
        _server_proc = subprocess.Popen(
            [sys.executable, str(REPLAY_APP)],
            cwd=str(STRATEGY_WEB),
            env=env,
            creationflags=creationflags,
        )
    except Exception as exc:
        log(f"hard: start failed: {exc}")
        _server_proc = None
        return False
    # Wait until health answers
    for _ in range(40):
        time.sleep(0.5)
        if _http_json("GET", "/api/health", timeout=2) is not None:
            log("hard: server up")
            return True
        if _server_proc.poll() is not None:
            log(f"hard: server exited early code={_server_proc.returncode}")
            return False
    log("hard: server start timeout")
    return False


def soft_recover() -> bool:
    log("soft: POST /api/live/monitor/restart")
    data = _http_json("POST", "/api/live/monitor/restart", timeout=30)
    if data is None:
        log("soft: restart request failed")
        return False
    time.sleep(2.0)
    h = _http_json("GET", "/api/health/live")
    if h and not h.get("stale") and h.get("monitor_alive"):
        log("soft: recovered")
        return True
    log(f"soft: still unhealthy after restart: {h}")
    return False


def hard_recover(reason: str) -> None:
    global _soft_fail_streak
    log(f"hard: recover ({reason})")
    _toast("MOEX Live Watchdog", f"Hard restart: {reason}")
    kill_port(PORT)
    if not MANAGE_SERVER:
        log("hard: MOEX_WATCHDOG_MANAGE_SERVER=0 — только kill, без автостарта")
        _soft_fail_streak = 0
        return
    ok = start_server()
    _soft_fail_streak = 0
    if ok:
        # lifespan starts monitor; give one tick room
        time.sleep(2.0)
        h = _http_json("GET", "/api/health/live")
        log(f"hard: health after start: {h}")
    else:
        _toast("MOEX Live Watchdog", "Не удалось поднять сервер")


def ensure_server_if_needed() -> bool:
    """If managing server and nothing listens — start once."""
    if not MANAGE_SERVER:
        return _http_json("GET", "/api/health", timeout=2) is not None
    h = _http_json("GET", "/api/health", timeout=2)
    if h is not None:
        return True
    log("boot: no server — starting")
    return start_server()


def cycle() -> None:
    global _soft_fail_streak
    live = _http_json("GET", "/api/health/live")
    if live is None:
        _soft_fail_streak += 1
        log(f"probe: HTTP dead (streak={_soft_fail_streak})")
        if _soft_fail_streak >= 2 or not MANAGE_SERVER:
            hard_recover("HTTP /api/health/live unavailable")
        else:
            # One soft wait: maybe process restarting
            time.sleep(3.0)
            if _http_json("GET", "/api/health/live") is None:
                hard_recover("HTTP still down")
        return

    wanted = bool(live.get("monitor_wanted", True))
    alive = bool(live.get("monitor_alive"))
    stale = bool(live.get("stale"))
    age = live.get("last_tick_age_sec")
    log(
        f"probe: wanted={wanted} alive={alive} stale={stale} "
        f"age={age} bar={live.get('last_bar')} z={live.get('last_z')}"
    )

    if not wanted:
        _soft_fail_streak = 0
        return

    if not alive or stale:
        _soft_fail_streak += 1
        log(f"probe: unhealthy (soft_streak={_soft_fail_streak})")
        if soft_recover():
            _soft_fail_streak = 0
            return
        if _soft_fail_streak >= 2:
            hard_recover("monitor stale after soft restart")
        return

    _soft_fail_streak = 0


def main() -> int:
    log(
        f"watchdog start url={BASE_URL} interval={INTERVAL_SEC}s "
        f"manage_server={int(MANAGE_SERVER)} log={LOG_PATH}"
    )
    if not ensure_server_if_needed():
        log("boot: failed to reach/start server — will keep retrying")
    try:
        while True:
            try:
                cycle()
            except Exception as exc:
                log(f"cycle error: {exc}")
            time.sleep(INTERVAL_SEC)
    except KeyboardInterrupt:
        log("watchdog stop (KeyboardInterrupt)")
        if MANAGE_SERVER:
            kill_port(PORT)
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
