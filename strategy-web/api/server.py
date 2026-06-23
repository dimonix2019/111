"""FastAPI — бэкенд для Vite UI."""

from __future__ import annotations

import logging
import threading
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path
from typing import Optional

import pandas as pd
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from api.bars_cache import cache_info, get_bars, invalidate
from api.basis import scan_basis
from api.llm import llm_chat, llm_status
from api.market_series import LLM_MAX_BARS_CAP, LLM_MAX_BARS_DEFAULT, market_series_for_llm, zscore_series_from_bars
from api.pack_build import pack_to_dict_fast
from api.serialize import _sanitize, _ts_unix_series, grid_search_row_to_dict, latest_quote_from_csv, sweep_row_to_dict
from config import DEFAULT_M15, PRESETS, UPLOADED_CSV
from m15_iss_loader import (
    LIVE_STALE_HOURS,
    LOOKBACK_DAYS,
    STALE_HOURS,
    ensure_m15_data,
    fetch_m15_from_iss,
    m15_data_status,
    merge_live_intraday,
    save_m15_csv,
)
from zsim import (
    compare_z_modes,
    default_parallel_workers,
    history_quality,
    param_sweep,
    run_oos_evaluation,
    run_protection_grid_search,
    run_z_strategy_sim,
    z_diagnostics,
)

log = logging.getLogger(__name__)

ROOT = Path(__file__).resolve().parent.parent

_startup_m15: dict = {"checked": False, "refreshed": False, "message": "", "in_progress": False}
_refresh_lock = threading.Lock()


def _refresh_default_m15_on_startup() -> None:
    """При старте API: дозагрузка MOEX, если CSV старше STALE_HOURS (в фоне)."""
    global _startup_m15
    with _refresh_lock:
        if _startup_m15.get("in_progress"):
            return
        _startup_m15 = {**_startup_m15, "in_progress": True}
    try:
        status = m15_data_status(DEFAULT_M15)
        if not status.get("exists") or status.get("is_stale"):
            reason = "missing" if not status.get("exists") else f"stale ({status.get('age_hours', '?')} h)"
            log.info("MOEX auto-refresh: %s — updating %s", reason, DEFAULT_M15.name)
            _, refreshed = ensure_m15_data(DEFAULT_M15, days=LOOKBACK_DAYS)
            if refreshed:
                invalidate(DEFAULT_M15)
                fresh = m15_data_status(DEFAULT_M15)
                _startup_m15 = {
                    "checked": True,
                    "refreshed": True,
                    "in_progress": False,
                    "message": f"Updated {DEFAULT_M15.name}, last bar {fresh.get('last_ts', '?')}",
                }
                log.info("MOEX auto-refresh done: %s", _startup_m15["message"])
                return
        _startup_m15 = {
            "checked": True,
            "refreshed": False,
            "in_progress": False,
            "message": f"Fresh ({status.get('age_hours', 0)} h), last bar {status.get('last_ts', '?')}",
        }
        log.info("MOEX data OK: %s", _startup_m15["message"])
    except Exception as exc:
        _startup_m15 = {
            "checked": True,
            "refreshed": False,
            "in_progress": False,
            "message": f"Failed: {exc}",
        }
        log.warning("MOEX auto-refresh failed: %s", exc)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    threading.Thread(
        target=_refresh_default_m15_on_startup,
        name="m15-startup-refresh",
        daemon=True,
    ).start()
    yield


app = FastAPI(title="Z-Strategy API", version="1.0.0", lifespan=lifespan)
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
    z_mode: str = "rolling30"
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
    slippage: float = 0.0
    max_loss_spread: float = 0.0
    max_loss_rub: float = 0.0
    min_spread: float = 0.0
    max_spread: float = 0.0
    entry_z_buffer: float = 0.0
    max_dd_halt_rub: float = 0.0
    max_dd_halt_pct: float = 0.0
    oos_enabled: bool = False
    oos_train_ratio: float = 0.7
    include_stress: bool = False
    pyramid_add_notional: float = 0.0
    pyramid_z_depth: float = 1.0


class GridSearchParams(BaseModel):
    csv_path: str
    notional_rub: float = 100_000.0
    leverage: float = 7.0
    commission_pct_per_side: float = 0.04
    compound_returns: bool = False
    oos_train_ratio: float = 0.7
    max_combinations: int = 400
    top_n: int = 25
    only_good: bool = True
    parallel_workers: Optional[int] = None


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


class DownloadRequest(BaseModel):
    path: str = ""


class LlmMessage(BaseModel):
    role: str = Field(..., pattern="^(system|user|assistant)$")
    content: str


class LlmChatRequest(BaseModel):
    messages: list[LlmMessage]
    temperature: float = 0.4
    max_tokens: int = Field(default=800, ge=64, le=4096)
    model: Optional[str] = None


def _resolve_csv(path_str: Optional[str], auto_download: bool, *, moex_live: bool = False) -> Path:
    path = Path(path_str) if path_str else DEFAULT_M15
    if path.is_file() and not moex_live and not auto_download:
        return path
    if auto_download or moex_live:
        ensure_m15_data(path, days=LOOKBACK_DAYS, moex_live=moex_live)
        if path.is_file():
            return path
    if path.is_file():
        return path
    raise HTTPException(status_code=404, detail=f"CSV not found: {path}")


def _protection_kw(params: SimParams) -> dict:
    return {
        "slippage_spread_pts": params.slippage,
        "max_loss_spread_pts": params.max_loss_spread,
        "max_loss_rub": params.max_loss_rub,
        "min_spread_pct": params.min_spread,
        "max_spread_pct": params.max_spread,
        "entry_z_buffer": params.entry_z_buffer,
        "max_drawdown_halt_rub": params.max_dd_halt_rub,
        "max_drawdown_halt_pct": params.max_dd_halt_pct,
        "pyramid_add_notional_rub": params.pyramid_add_notional,
        "pyramid_z_depth": params.pyramid_z_depth,
    }


def _run_packs(bars, params: SimParams) -> tuple[list[dict], dict, dict]:
    prot = _protection_kw(params)
    sim_kw = {
        "leverage": params.leverage,
        "commission_pct_per_side": params.commission,
        "compound_returns": params.compound,
        **prot,
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

    z_mode = params.z_mode if params.z_mode in ("global", "rolling30") else "rolling30"
    hq = history_quality(bars, z_mode=z_mode)  # type: ignore[arg-type]
    sim_kw_store = {**sim_kw, "notional_rub": notional_rub}
    return packs, hq, sim_kw_store


@app.get("/api/health")
def health():
    return {
        "ok": True,
        "api_build": 6,
        "lookback_days": LOOKBACK_DAYS,
        "stale_hours": STALE_HOURS,
        "live_stale_hours": LIVE_STALE_HOURS,
        "data_refresh": _startup_m15,
        "bars_cache": cache_info(),
        "features": {"llm": True, "idle_precursors": True, "basis_carry": True},
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
    return {"path": str(p), **status}


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


@app.post("/api/data/refresh-tail")
def refresh_moex_tail(req: DownloadRequest):
    """Догрузка хвоста MOEX для онлайн-режима (без полной симуляции)."""
    from m15_iss_loader import needs_live_tail_refresh

    p = Path(req.path) if req.path else DEFAULT_M15
    try:
        _, refreshed = ensure_m15_data(p, moex_live=True)
        if refreshed:
            invalidate(p)
        st = m15_data_status(p)
        return {
            "ok": True,
            "refreshed": refreshed,
            "needs_live_tail": needs_live_tail_refresh(p),
            "path": str(p),
            **st,
        }
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.get("/api/market/live")
def market_live(path: str = "", tail_rows: int = 16_000, z_mode: str = "rolling30"):
    """Live-опрос для графика Z/spread (~5 с): MOEX intraday merge + rolling Z."""
    from api.moex_time import moex_now

    p = Path(path) if path else DEFAULT_M15
    mode = z_mode if z_mode in ("global", "rolling30") else "rolling30"
    try:
        refreshed = merge_live_intraday(p)
        if refreshed:
            invalidate(p)
        bars = get_bars(p, recalc_z=False, z_mode=mode)  # type: ignore[arg-type]
        zscore = zscore_series_from_bars(bars, tail_rows=tail_rows)
        if not zscore:
            raise HTTPException(status_code=404, detail=f"No zscore data in {p}")
        st = m15_data_status(p)
        return {
            "ok": True,
            "refreshed": refreshed,
            "tick_at": moex_now().strftime("%Y-%m-%d %H:%M:%S"),
            "zscore": zscore,
            "latest_quote": latest_quote_from_csv(str(p)),
            "data_meta": st,
            "last_ts": st.get("last_ts"),
        }
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.get("/api/basis/scan")
def basis_scan(
    fin_rate: float = 0.18,
    min_yield_ann: float = 15.0,
    z_entry: float = 1.0,
    history_days: int = 90,
):
    """Сканер spot–futures basis (cash-and-carry) по MOEX FORTS."""
    try:
        return _sanitize(
            scan_basis(
                fin_rate=fin_rate,
                min_yield_ann=min_yield_ann,
                z_entry=z_entry,
                history_days=history_days,
            )
        )
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/api/simulate")
def simulate(params: SimParams):
    try:
        path = _resolve_csv(params.csv_path, params.auto_download, moex_live=params.moex_live)
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
        oos = None
        if params.oos_enabled and not params.compare_mode:
            oos = _sanitize(
                run_oos_evaluation(
                    bars,
                    train_ratio=params.oos_train_ratio,
                    entry=params.entry,
                    exit_z=params.exit_z,
                    notional_rub=params.notional,
                    leverage=params.leverage,
                    commission_pct_per_side=params.commission,
                    compound_returns=params.compound,
                    **_protection_kw(params),
                )
            )

        stress = None
        if params.include_stress and packs and not params.compare_mode:
            base_stats = packs[0]["result"].stats()
            slip_stress = max(0.05, params.slippage + 0.05)
            stress_res = run_z_strategy_sim(
                bars,
                entry=params.entry,
                exit_z=params.exit_z,
                notional_rub=params.notional,
                leverage=params.leverage,
                commission_pct_per_side=params.commission * 2.0,
                compound_returns=params.compound,
                slippage_spread_pts=slip_stress,
                **_protection_kw(params),
            )
            st_stats = stress_res.stats()
            stress = _sanitize(
                {
                    "commission_multiplier": 2.0,
                    "slippage_pts": slip_stress,
                    "baseline": {
                        k: base_stats[k]
                        for k in (
                            "total_pnl_rub",
                            "trade_count",
                            "max_drawdown_rub",
                            "win_rate_pct",
                            "profit_factor",
                            "total_commission_rub",
                        )
                    },
                    "stress": {
                        k: st_stats[k]
                        for k in (
                            "total_pnl_rub",
                            "trade_count",
                            "max_drawdown_rub",
                            "win_rate_pct",
                            "profit_factor",
                            "total_commission_rub",
                        )
                    },
                }
            )

        return {
            "path": str(path),
            "hq": hq,
            "sim_kw": sim_kw,
            "compare_mode": params.compare_mode,
            "z_mode": z_mode,
            "z_diagnostics": z_diag,
            "z_compare": z_compare,
            "oos": oos,
            "stress": stress,
            "packs": [
                pack_to_dict_fast(p, csv_path=str(path), bars=bars)
                for p in packs
            ],
        }
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/api/grid-search")
def grid_search(params: GridSearchParams):
    try:
        path = _resolve_csv(params.csv_path, True)
        bars = get_bars(path, recalc_z=False, z_mode="rolling30")
        out = run_protection_grid_search(
            bars,
            csv_path=str(path),
            parallel_workers=params.parallel_workers,
            notional_rub=params.notional_rub,
            leverage=params.leverage,
            commission_pct_per_side=params.commission_pct_per_side,
            compound_returns=params.compound_returns,
            train_ratio=params.oos_train_ratio,
            max_combinations=params.max_combinations,
            top_n=params.top_n,
            only_good=params.only_good,
        )
        return {
            "path": str(path),
            "evaluated": out.get("evaluated", 0),
            "combinations_planned": out.get("combinations_planned", 0),
            "good_count": out.get("good_count", 0),
            "only_good": params.only_good,
            "rows": [grid_search_row_to_dict(r) for r in out.get("rows", [])],
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


@app.get("/api/llm/status")
def llm_status_route():
    return llm_status()


@app.get("/api/llm/market-series")
def llm_market_series_route(path: str = "", max_bars: int = LLM_MAX_BARS_DEFAULT):
    """Полный (до max_bars) ряд 15м для контекста чата: Z, spread, TATN/TATNP OHLC."""
    p = Path(path) if path else DEFAULT_M15
    max_bars = max(100, min(int(max_bars), LLM_MAX_BARS_CAP))
    try:
        return market_series_for_llm(p, max_bars=max_bars)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


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
