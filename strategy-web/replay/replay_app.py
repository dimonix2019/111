"""Bar Replay web — FastAPI: SQLite/CSV + MOEX tail + статика TradingView."""

from __future__ import annotations

import os
import socket
import sys
import threading
import webbrowser
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

import uvicorn
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

ROOT = Path(__file__).resolve().parent
STRATEGY_WEB = ROOT.parent
DATA_DIR = STRATEGY_WEB / "data"
STATIC = ROOT / "static"

if str(STRATEGY_WEB) not in sys.path:
    sys.path.insert(0, str(STRATEGY_WEB))

from replay.replay_db import ensure_replay_bars  # noqa: E402
from live import engine as live_engine  # noqa: E402
from live.routes import router as live_router  # noqa: E402
from live.markets_api import router as markets_router  # noqa: E402
from live.portfolio_api import router as portfolio_router  # noqa: E402
from live.trade_api import router as trade_router  # noqa: E402


@asynccontextmanager
async def lifespan(_app: FastAPI):
    """Always-on live monitor (daemon thread), matching APK intent."""
    live_engine.start_monitor()
    try:
        yield
    finally:
        # Не сбрасываем monitor_running — иначе после рестарта сервиса/watchdog
        # флаг «выкл» и внешний watchdog не поднимает монитор.
        live_engine.stop_monitor(clear_wanted=False)


app = FastAPI(title="MOEX Bar Replay", version="1.4.0", lifespan=lifespan)
app.include_router(live_router)
app.include_router(markets_router)
app.include_router(portfolio_router)
app.include_router(trade_router)
app.mount("/static", StaticFiles(directory=STATIC), name="static")

ALLOWED_CSV = {
    "m15_tatn_255d.csv",
    "m15_tatn_365d.csv",
    "m15_tatn_1095d.csv",
    "m15_test_3d.csv",
}


_online_cache: tuple[float, bool] | None = None
_online_lock = threading.Lock()


def _probe_online() -> bool:
    try:
        socket.create_connection(("iss.moex.com", 443), timeout=1.0).close()
        return True
    except OSError:
        return False


def _refresh_online_cache() -> None:
    global _online_cache
    import time

    ok = _probe_online()
    with _online_lock:
        _online_cache = (time.time(), ok)


def _is_online() -> bool:
    """Не блокируем /api/bars: кэш или optimistic True + фоновый probe."""
    global _online_cache
    import time

    now = time.time()
    with _online_lock:
        cached = _online_cache
    if cached and now - cached[0] < 60:
        return cached[1]
    # Первый запрос / протухший кэш — не ждём TCP
    threading.Thread(target=_refresh_online_cache, daemon=True).start()
    return cached[1] if cached else True


@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC / "index.html")


@app.get("/api/bars")
def api_bars(
    csv: str = Query("m15_tatn_255d.csv", description="CSV в strategy-web/data"),
    start: str | None = Query(None, description="Старт replay YYYY-MM-DD или YYYY-MM-DD HH:MM"),
) -> dict[str, Any]:
    name = Path(csv).name
    if name not in ALLOWED_CSV:
        raise HTTPException(400, f"CSV не разрешён: {name}")
    path = DATA_DIR / name
    if not path.is_file():
        raise HTTPException(404, f"Файл не найден: {path}")

    start_key = start.strip() if start else None
    online = _is_online()
    payload = ensure_replay_bars(path, name, online=online, start_date=start_key)
    bars = payload["bars"]
    return {
        "csv": name,
        "count": len(bars),
        "bars": bars,
        "first": bars[0]["tradeDate"] if bars else None,
        "last": bars[-1]["tradeDate"] if bars else None,
        "source": payload["source"],
        "dbCount": payload["db_count"],
        "online": payload["online"],
        "refreshed": payload["refreshed"],
        "start": start_key,
    }


@app.get("/api/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/health/live")
def health_live() -> dict[str, Any]:
    """Process + monitor probe for external watchdog."""
    return live_engine.health_live()


def _open_browser_when_ready(url: str = "http://127.0.0.1:8765") -> None:
    import time

    for _ in range(40):
        time.sleep(0.25)
        try:
            import urllib.request

            with urllib.request.urlopen(f"{url}/api/health", timeout=0.5) as resp:
                if resp.status == 200:
                    webbrowser.open(url)
                    return
        except OSError:
            continue
    webbrowser.open(url)


def main() -> None:
    frame = STATIC / "chart-frame.html"
    if frame.is_file():
        tpl = frame.read_text(encoding="utf-8")
        js_path = STATIC / "lightweight-charts.standalone.production.js"
        marker = "<!-- INJECT_LIGHTWEIGHT_CHARTS -->"
        # Не перезаписывать огромный бандл на каждый старт, если уже инжектнут
        if marker in tpl and js_path.is_file():
            js = js_path.read_text(encoding="utf-8")
            frame.write_text(tpl.replace(marker, f"<script>\n{js}\n</script>"), encoding="utf-8")
    host = (os.environ.get("MOEX_REPLAY_HOST") or "127.0.0.1").strip() or "127.0.0.1"
    port = int(os.environ.get("MOEX_REPLAY_PORT") or "8765")
    print(f"MOEX Bar Replay: http://{host}:{port}")
    if host in ("0.0.0.0", "::"):
        print("  (слушает все интерфейсы — Tailscale/LAN: http://<tailscale-ip>:8765)")
    if os.environ.get("MOEX_REPLAY_OPEN_BROWSER", "").strip().lower() in ("1", "true", "yes"):
        browse = f"http://127.0.0.1:{port}"
        threading.Thread(
            target=lambda: _open_browser_when_ready(browse),
            daemon=True,
        ).start()
    uvicorn.run(app, host=host, port=port, log_level="info")


if __name__ == "__main__":
    main()
