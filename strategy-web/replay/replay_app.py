"""Bar Replay web — FastAPI: данные CSV + статика TradingView."""

from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import pandas as pd
import uvicorn
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

ROOT = Path(__file__).resolve().parent
STATIC = ROOT / "static"
DATA_DIR = ROOT.parent / "data"
MSK = ZoneInfo("Europe/Moscow")

app = FastAPI(title="MOEX Bar Replay", version="1.0.0")
app.mount("/static", StaticFiles(directory=STATIC), name="static")

ALLOWED_CSV = {
    "m15_tatn_255d.csv",
    "m15_tatn_365d.csv",
    "m15_tatn_1095d.csv",
    "m15_test_3d.csv",
}


def _parse_ts_ms(raw: str) -> int:
    s = str(raw).strip().replace("T", " ")
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            dt = datetime.strptime(s[:19], fmt).replace(tzinfo=MSK)
            return int(dt.timestamp() * 1000)
        except ValueError:
            continue
    return 0


def _label_15m(raw: str) -> str:
    s = str(raw).strip().replace("T", " ")
    if len(s) >= 16:
        return s[:16]
    return s


def load_bars_from_csv(path: Path) -> list[dict[str, Any]]:
    df = pd.read_csv(path)
    cols = {c.lower(): c for c in df.columns}
    ts_col = cols.get("timestamp", "timestamp")
    z_col = cols.get("z_score", "z_score")
    spread_col = cols.get("spread_percent", "spread_percent")
    tatn_col = cols.get("tatn_close", "tatn_close")
    tatnp_col = cols.get("tatnp_close", "tatnp_close")

    out: list[dict[str, Any]] = []
    for _, row in df.iterrows():
        ts_raw = row[ts_col]
        label = _label_15m(ts_raw)
        ms = _parse_ts_ms(ts_raw)
        z = float(row[z_col]) if pd.notna(row[z_col]) else 0.0
        spread = float(row[spread_col]) if pd.notna(row[spread_col]) else 0.0
        tatn = float(row[tatn_col]) if pd.notna(row[tatn_col]) else 0.0
        tatnp = float(row[tatnp_col]) if pd.notna(row[tatnp_col]) else 0.0
        out.append(
            {
                "timestampMs": ms,
                "tradeDate": label,
                "zScore": z,
                "spreadPercent": spread,
                "tatnClose": tatn,
                "tatnpClose": tatnp,
            }
        )
    out.sort(key=lambda b: b["timestampMs"])
    return out


@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC / "index.html")


@app.get("/api/bars")
def api_bars(
    csv: str = Query("m15_tatn_255d.csv", description="CSV в strategy-web/data"),
) -> dict[str, Any]:
    name = Path(csv).name
    if name not in ALLOWED_CSV:
        raise HTTPException(400, f"CSV не разрешён: {name}")
    path = DATA_DIR / name
    if not path.is_file():
        raise HTTPException(404, f"Файл не найден: {path}")
    bars = load_bars_from_csv(path)
    return {
        "csv": name,
        "count": len(bars),
        "bars": bars,
        "first": bars[0]["tradeDate"] if bars else None,
        "last": bars[-1]["tradeDate"] if bars else None,
    }


@app.get("/api/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


def main() -> None:
    # Подставляем lightweight-charts в chart-frame.html при старте
    frame = STATIC / "chart-frame.html"
    if frame.is_file():
        tpl = frame.read_text(encoding="utf-8")
        js_path = STATIC / "lightweight-charts.standalone.production.js"
        if "<!-- INJECT_LIGHTWEIGHT_CHARTS -->" in tpl and js_path.is_file():
            js = js_path.read_text(encoding="utf-8")
            frame.write_text(
                tpl.replace("<!-- INJECT_LIGHTWEIGHT_CHARTS -->", f"<script>\n{js}\n</script>"),
                encoding="utf-8",
            )
    print("MOEX Bar Replay: http://127.0.0.1:8765")
    uvicorn.run(app, host="127.0.0.1", port=8765, log_level="info")


if __name__ == "__main__":
    main()
