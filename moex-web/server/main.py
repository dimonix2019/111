"""MOEX MVP Web API — паритет с Android для отладки."""

from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import data_service
from .constants import DEFAULT_COMMISSION, DEFAULT_ENTRY, DEFAULT_EXIT, DEFAULT_LEVERAGE

STATIC = Path(__file__).resolve().parents[1] / "static"

app = FastAPI(title="MOEX MVP Web", version="0.1.0")


class SimulateBody(BaseModel):
    entry: float = Field(default=DEFAULT_ENTRY, ge=0, le=8)
    exit_z: float = Field(default=DEFAULT_EXIT, ge=0, le=8)
    leverage: float = Field(default=DEFAULT_LEVERAGE, ge=1, le=20)
    commission: float = Field(default=DEFAULT_COMMISSION, ge=0, le=1)
    compound: bool = False


@app.get("/api/health")
def health():
    return {"ok": True}


@app.get("/api/info")
def info():
    return data_service.app_info()


@app.post("/api/data/refresh")
def refresh_data():
    data_service.ensure_data(force=True)
    return {"ok": True}


@app.get("/api/markets/chart")
def markets_chart(period: str = Query("1D", pattern="^(1D|1W|1M|3M)$")):
    return data_service.markets_chart_payload(period)


@app.post("/api/strategy-test/simulate")
def strategy_simulate(body: SimulateBody):
    return data_service.strategy_test_payload(
        entry=body.entry,
        exit_z=body.exit_z,
        leverage=body.leverage,
        commission=body.commission,
        compound=body.compound,
    )


@app.get("/")
def index():
    return FileResponse(STATIC / "index.html")


app.mount("/static", StaticFiles(directory=STATIC), name="static")
