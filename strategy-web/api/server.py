from zsim import LOOKBACK_DAYS, history_quality, param_sweep, run_z_strategy_sim"""FastAPI — бэкенд для Vite UI."""

from __future__ import annotations

from pathlib import Path
from typing import Optional

import pandas as pd
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from api.serialize import pack_to_dict, sweep_row_to_dict, _sanitize
from config import DEFAULT_M15, PRESETS, UPLOADED_CSV
from m15_iss_loader import ensure_m15_data, fetch_m15_from_iss, m15_data_status, save_m15_csv
from api.bars_cache import cache_info, get_bars, invalidate
from api.basis import scan_basis
from api.llm import llm_chat, llm_status
from zsim import LOOKBACK_DAYS, history_quality, param_sweep, run_z_strategy_sim

ROOT = Path(__file__).resolve().parent.parent

app = FastAPI(title="Z-Strategy API", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class SimParams(BaseModel):
    csv_path: Optional[str] = None
    auto_download: bool = True
    """Онлайн-режим: догрузка MOEX при возрасте последнего бара > LIVE_STALE_HOURS (~20 мин)."""
    moex_live: bool = False
    compare_mode: bool = False
    entry: float = 0.8
    exit_z: float = 0.7
    notional: float = 100_000.0
    entry_a: float = 0.8
    exit_a: float = 0.7
    notional_a: float = 100_000.0
    entry_b: float = 1.0
    exit_b: float = 0.7
    notional_b: float = 100_000.0
    leverage: float = 7.0
    commission: float = 0.04
    compound: bool = False


class SweepParams(BaseModel):
    csv_path: str
    entry_min: float = 0.5
    entry_max: float = 1.2
    entry_step: float = 0.1
    exit_min: float = 0.3
    exit_max: float = 0.9
    exit_step: float = 0.1
    notional_rub: float = 100_000.0
    leverage: float = 7.0
    commission_pct_per_side: float = 0.04
    compound_returns: bool = False


def _resolve_csv(path_str: Optional[str], auto_download: bool) -> Path:
    path = Path(path_str) if path_str else DEFAULT_M15
    if path.is_file():
        return path
    if auto_download:
        ensure_m15_data(path, days=255)
        if path.is_file():
            return path
    raise HTTPException(status_code=404, detail=f"CSV not found: {path}")


def _run_packs(bars, params: SimParams) -> tuple[list[dict], dict, dict]:
    sim_kw = {
        "leverage": params.leverage,
        "commission_pct_per_side": params.commission,
        "compound_returns": params.compound,
    }
    packs: list[dict] = []
    if params.compare_mode:
        for label, entry, exit_z, notional in (
            ("A", params.entry_a, params.exit_a, params.notional_a),
            ("B", params.entry_b, params.exit_b, params.notional_b),
        ):
            result = run_z_strategy_sim(
                bars, entry=entry, exit_z=exit_z, notional_rub=notional, **sim_kw
            )
            packs.append({"label": f"Стратегия {label}", "result": result, "entry": entry, "exit_z": exit_z})
        notional_rub = params.notional_a
    else:
        result = run_z_strategy_sim(
            bars,
            entry=params.entry,
            exit_z=params.exit_z,
            notional_rub=params.notional,
            **sim_kw,
        )
        packs.append(
            {
                "label": "Стратегия",
                "result": result,
                "entry": params.entry,
                "exit_z": params.exit_z,
            }
        )
        notional_rub = params.notional

    hq = history_quality(bars, z_mode=params.z_mode)  # type: ignore[arg-type]
    sim_kw_store = {**sim_kw, "notional_rub": notional_rub}
    return packs, hq, sim_kw_store


@app.get("/api/health")
def health():
    return {
        "ok": True,
        "lookback_days": LOOKBACK_DAYS,
        "bars_cache": cache_info(),
        "features": {"llm": True},
    }


@app.get("/api/presets")
def presets():
    return PRESETS


@app.get("/api/data/status")
def data_status(path: str = ""):
    p = Path(path) if path else DEFAULT_M15
    try:
        status = m15_data_status(p)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


class LlmMessage(BaseModel):
    role: str = Field(..., pattern="^(system|user|assistant)$")
    content: str


class LlmChatRequest(BaseModel):
    messages: list[LlmMessage]
    temperature: float = 0.4
    max_tokens: int = Field(default=800, ge=64, le=4096)
    model: Optional[str] = None


@app.get("/api/llm/status")
def llm_status_route():
    return llm_status()


@app.post("/api/llm/chat")
def llm_chat_route(req: LlmChatRequest):
    try:
        messages = [{"role": m.role, "content": m.content} for m in req.messages]
        return llm_chat(
            messages,
            temperature=req.temperature,
            max_tokens=req.max_tokens,
            model=req.model,
        )
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return {"path": str(p), **status}


class DownloadRequest(BaseModel):
    path: str = ""


@app.post("/api/data/download")
def download_moex(req: DownloadRequest):
    p = Path(req.path) if req.path else DEFAULT_M15
    try:
        save_m15_csv(fetch_m15_from_iss(days=255), p)
        invalidate(p)
        return {"ok": True, "path": str(p), **m15_data_status(p)}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/api/data/upload")
async def upload_csv(file: UploadFile = File(...)):
    UPLOADED_CSV.parent.mkdir(parents=True, exist_ok=True)
    content = await file.read()
    UPLOADED_CSV.write_bytes(content)
    invalidate(UPLOADED_CSV)
    return {"ok": True, "path": str(UPLOADED_CSV), **m15_data_status(UPLOADED_CSV)}


@app.post("/api/simulate")
def simulate(params: SimParams):
    try:
        path = _resolve_csv(params.csv_path, params.auto_download, moex_live=params.moex_live)
        data_meta = m15_data_status(path)
        z_mode = params.z_mode if params.z_mode in ("global", "rolling30") else "rolling30"
        bars = get_bars(path, recalc_z=False, z_mode=z_mode)  # type: ignore[arg-type]
        packs, hq, sim_kw = _run_packs(bars, params)
        z_diag = _sanitize(z_diagnostics(bars))
        z_compare = None
        if z_mode == "rolling30" and not params.compare_mode:
            z_compare = _sanitize(
                compare_z_modes(
                    str(path),
                    entry=params.entry,
                    exit_z=params.exit_z,
                    notional_rub=params.notional,
                    leverage=params.leverage,
                    commission_pct_per_side=params.commission,
                    compound_returns=params.compound,
                ).to_dict(orient="records")
            )
        return {
            "path": str(path),
            "hq": hq,
            "sim_kw": sim_kw,
            "compare_mode": params.compare_mode,
            "packs": [pack_to_dict(p) for p in packs],
        }
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/api/sweep")
def sweep(params: SweepParams):
    import numpy as np

    try:
        path = _resolve_csv(params.csv_path, True)
        bars = get_bars(path, recalc_z=False, z_mode="rolling30")
        entries = [
            round(float(x), 2)
            for x in np.arange(params.entry_min, params.entry_max + params.entry_step * 0.5, params.entry_step)
        ]
        exits = [
            round(float(x), 2)
            for x in np.arange(params.exit_min, params.exit_max + params.exit_step * 0.5, params.exit_step)
        ]
        if len(entries) * len(exits) > 100:
            raise HTTPException(status_code=400, detail="Too many combinations (max 100)")
        sim_kw = {
            "notional_rub": params.notional_rub,
            "leverage": params.leverage,
            "commission_pct_per_side": params.commission_pct_per_side,
            "compound_returns": params.compound_returns,
        }
        df = param_sweep(bars, entries, exits, **sim_kw)
        best = df.loc[df["total_pnl_rub"].idxmax()]
        return {
            "rows": [sweep_row_to_dict(r) for _, r in df.iterrows()],
            "best": sweep_row_to_dict(best),
        }
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


class LlmMessage(BaseModel):
    role: str = Field(..., pattern="^(system|user|assistant)$")
    content: str


class LlmChatRequest(BaseModel):
    messages: list[LlmMessage]
    temperature: float = 0.4
    max_tokens: int = Field(default=800, ge=64, le=4096)
    model: Optional[str] = None


@app.get("/api/llm/status")
def llm_status_route():
    return llm_status()


@app.post("/api/llm/chat")
def llm_chat_route(req: LlmChatRequest):
    try:
        messages = [{"role": m.role, "content": m.content} for m in req.messages]
        return llm_chat(
            messages,
            temperature=req.temperature,
            max_tokens=req.max_tokens,
            model=req.model,
        )
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
