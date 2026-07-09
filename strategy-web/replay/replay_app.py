"""Bar Replay web — FastAPI: SQLite/CSV + MOEX tail + статика TradingView."""

from __future__ import annotations

import os
import socket
import sys
import threading
import webbrowser
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

app = FastAPI(title="MOEX Bar Replay", version="1.1.0")
app.mount("/static", StaticFiles(directory=STATIC), name="static")

ALLOWED_CSV = {
    "m15_tatn_255d.csv",
    "m15_tatn_365d.csv",
    "m15_tatn_1095d.csv",
    "m15_test_3d.csv",
}


def _is_online() -> bool:
    try:
        socket.create_connection(("iss.moex.com", 443), timeout=2).close()
        return True
    except OSError:
        return False


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
        if marker in tpl and js_path.is_file():
            js = js_path.read_text(encoding="utf-8")
            frame.write_text(tpl.replace(marker, f"<script>\n{js}\n</script>"), encoding="utf-8")
    print("MOEX Bar Replay: http://127.0.0.1:8765")
    if os.environ.get("MOEX_REPLAY_OPEN_BROWSER", "").strip().lower() in ("1", "true", "yes"):
        threading.Thread(target=_open_browser_when_ready, daemon=True).start()
    uvicorn.run(app, host="127.0.0.1", port=8765, log_level="info")


if __name__ == "__main__":
    main()
