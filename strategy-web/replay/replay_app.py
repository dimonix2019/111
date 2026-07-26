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
from fastapi import Body, FastAPI, HTTPException, Query
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
    as_live: int = Query(0, description="1 = overlay decision_bars (как Прод)"),
    source: str | None = Query(None, description="decision = то же что as_live=1"),
) -> dict[str, Any]:
    name = Path(csv).name
    if name not in ALLOWED_CSV:
        raise HTTPException(400, f"CSV не разрешён: {name}")
    path = DATA_DIR / name
    if not path.is_file():
        raise HTTPException(404, f"Файл не найден: {path}")

    start_key = start.strip() if start else None
    # Testing historical load (esp. 3y): never block on MOEX ISS / full CSV rewrite.
    # Live tip stays on monitor + 255d; explicit Refresh uses /api/markets/refresh.
    online_hint = _is_online()
    payload = ensure_replay_bars(path, name, online=False, start_date=start_key)
    bars = list(payload["bars"] or [])
    want_live = bool(as_live) or (str(source or "").strip().lower() in ("decision", "live", "as_live"))
    live_meta: dict[str, Any] = {
        "as_live": False,
        "locked_count": 0,
        "bars_count": len(bars),
        "coverage": 0.0,
        "locked_from": None,
        "locked_to": None,
    }
    if want_live:
        from live import store as live_store

        live_meta = live_store.overlay_decision_bars_on_series(bars)
    return {
        "csv": name,
        "count": len(bars),
        "bars": bars,
        "first": bars[0]["tradeDate"] if bars else None,
        "last": bars[-1]["tradeDate"] if bars else None,
        "source": payload["source"],
        "dbCount": payload["db_count"],
        "online": bool(online_hint),
        "refreshed": payload["refreshed"],
        "start": start_key,
        **live_meta,
    }


@app.get("/api/bars1m")
def api_bars1m(
    csv: str | None = Query(None, description="CSV lookback; if set — return tip-Z bars for chart"),
    start: str | None = Query(None, description="Старт YYYY-MM-DD"),
    chartDays: int | None = Query(
        None,
        description="Окно графика (календ. дни с хвоста). По умолчанию 90. 0 = без усечения",
    ),
    metaOnly: int = Query(0, description="1 = только meta parquet, без баров"),
) -> dict[str, Any]:
    """1m tip-Z bars for Testing chart («касание 1м»), or parquet meta if metaOnly/no csv."""
    from replay import tip_touch

    if metaOnly or not csv:
        return tip_touch.bars1m_meta()
    name = Path(str(csv)).name
    if name not in ALLOWED_CSV:
        raise HTTPException(400, f"CSV не разрешён: {name}")
    days = tip_touch.DEFAULT_CHART_DAYS if chartDays is None else int(chartDays)
    try:
        return tip_touch.bars1m_chart(csv=name, start=start, chart_days=days)
    except FileNotFoundError as e:
        raise HTTPException(404, str(e)) from e
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    except Exception as e:
        raise HTTPException(500, f"bars1m failed: {e}") from e


@app.post("/api/sim/tip1m")
def api_sim_tip1m(body: dict[str, Any] = Body(default_factory=dict)) -> dict[str, Any]:
    """Server-side Mode B: enter/exit on first 1m tip-Z touch."""
    from replay import tip_touch

    csv = Path(str(body.get("csv") or "m15_tatn_255d.csv")).name
    if csv not in ALLOWED_CSV:
        raise HTTPException(400, f"CSV не разрешён: {csv}")
    try:
        entry = float(body.get("entry", 1.6))
        exit_z = float(body.get("exit", body.get("exitZ", 1.3)))
        slip = float(body.get("slip", tip_touch.DEFAULT_SLIP))
        notional = float(body.get("notional", body.get("capital", tip_touch.DEFAULT_NOTIONAL)))
        compound = bool(body.get("compound", False))
        tp = float(body.get("takeProfitPct", body.get("tp", 0)) or 0)
        start = body.get("start")
        start_s = str(start).strip() if start else None
        return tip_touch.sim_tip1m(
            csv=csv,
            entry=entry,
            exit_z=exit_z,
            slip=slip,
            notional=notional,
            compound=compound,
            take_profit_pct=tp,
            start=start_s,
        )
    except FileNotFoundError as e:
        raise HTTPException(404, str(e)) from e
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    except Exception as e:
        raise HTTPException(500, f"tip1m sim failed: {e}") from e


@app.post("/api/sim/tip1m/heatmap")
def api_sim_tip1m_heatmap(body: dict[str, Any] = Body(default_factory=dict)) -> dict[str, Any]:
    """Server-side E/X grid for 1m tip-touch (Testing heatmap)."""
    from replay import tip_touch

    csv = Path(str(body.get("csv") or "m15_tatn_255d.csv")).name
    if csv not in ALLOWED_CSV:
        raise HTTPException(400, f"CSV не разрешён: {csv}")
    try:
        slip = float(body.get("slip", tip_touch.DEFAULT_SLIP))
        notional = float(body.get("notional", body.get("capital", tip_touch.DEFAULT_NOTIONAL)))
        compound = bool(body.get("compound", False))
        tp = float(body.get("takeProfitPct", body.get("tp", 0)) or 0)
        start = body.get("start")
        start_s = str(start).strip() if start else None
        return tip_touch.heatmap_tip1m(
            csv=csv,
            entry_min=float(body.get("entryMin", 0.5)),
            entry_max=float(body.get("entryMax", 2.7)),
            exit_min=float(body.get("exitMin", 0.5)),
            step=float(body.get("step", 0.1)),
            slip=slip,
            notional=notional,
            compound=compound,
            take_profit_pct=tp,
            start=start_s,
        )
    except FileNotFoundError as e:
        raise HTTPException(404, str(e)) from e
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    except Exception as e:
        raise HTTPException(500, f"tip1m heatmap failed: {e}") from e


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
