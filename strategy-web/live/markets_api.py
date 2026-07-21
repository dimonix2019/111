"""Market tab API — live Z/spread summary + M15 bars for chart periods."""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any

from fastapi import APIRouter, HTTPException, Query

from live import engine, store

router = APIRouter(prefix="/api/markets", tags=["markets"])

PERIOD_DAYS = {1: 1, 7: 7, 30: 30, 90: 90}


def _parse_bar_dt(trade_date: str | None) -> datetime | None:
    if not trade_date:
        return None
    s = str(trade_date).strip()
    for fmt, n in (("%Y-%m-%d %H:%M:%S", 19), ("%Y-%m-%d %H:%M", 16), ("%Y-%m-%d", 10)):
        try:
            return datetime.strptime(s[:n], fmt)
        except ValueError:
            continue
    return None


def _filter_bars(bars: list[dict[str, Any]], days: int) -> list[dict[str, Any]]:
    if days <= 0 or not bars:
        return bars
    last = bars[-1]
    last_dt = _parse_bar_dt(last.get("tradeDate"))
    if last_dt is None:
        # fallback: take last N*26 bars (~26 bars/day)
        n = max(10, days * 26)
        return bars[-n:]
    cut = last_dt - timedelta(days=days)
    out = []
    for b in bars:
        dt = _parse_bar_dt(b.get("tradeDate"))
        if dt is None or dt >= cut:
            out.append(b)
    return out or bars[-max(10, days * 26) :]


@router.get("")
def markets_snapshot(days: int = Query(7, description="1 / 7 / 30 / 90")) -> dict[str, Any]:
    try:
        days = PERIOD_DAYS.get(days, days if days in (1, 7, 30, 90) else 7)
        snap = engine.market_snapshot()
        # Full series for filtering (market_snapshot only returns last bars metadata)
        from pathlib import Path

        from replay.replay_db import ensure_replay_bars

        data_dir = Path(__file__).resolve().parent.parent / "data"
        csv_path = data_dir / "m15_tatn_255d.csv"
        payload = ensure_replay_bars(csv_path, "m15_tatn_255d.csv", online=True, start_date=None)
        all_bars = payload.get("bars") or []
        bars = _filter_bars(all_bars, days)
        settings = store.get_settings_bundle()
        pos = engine.current_position()
        open_t = store.get_open_trade()
        chart_bars = [
            {
                "time": b.get("tradeDate"),
                "timestampMs": b.get("timestampMs"),
                "z": b.get("zScore"),
                "spread": b.get("spreadPercent"),
                "tatn": b.get("tatnClose"),
                "tatnp": b.get("tatnpClose"),
            }
            for b in bars
        ]
        return {
            "ok": True,
            "days": days,
            "summary": {
                "z": snap.get("z"),
                "spread": snap.get("spread"),
                "tatn": snap.get("tatn"),
                "tatnp": snap.get("tatnp"),
                "trade_date": snap.get("trade_date"),
                "position": pos.value,
                "entry_z": settings.get("entry_z"),
                "exit_z": settings.get("exit_z"),
                "source": snap.get("source"),
                "online": snap.get("online"),
                "count": snap.get("count"),
                "window_count": len(bars),
            },
            "bars": chart_bars,
            "open": open_t,
        }
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc


@router.post("/refresh")
def markets_refresh(
    days: int = Query(7),
    csv: str = Query("m15_tatn_255d.csv", description="CSV в strategy-web/data"),
) -> dict[str, Any]:
    """Force MOEX tail sync then return snapshot."""
    try:
        from pathlib import Path

        from replay import replay_db

        data_dir = Path(__file__).resolve().parent.parent / "data"
        name = Path(csv).name
        csv_path = data_dir / name
        if csv_path.is_file():
            # 1095d seed/sync может занять дольше 45с
            from m15_iss_loader import lookback_days_for_path

            days_lookback = lookback_days_for_path(name)
            timeout = 90.0 if days_lookback >= 900 else 45.0
            replay_db.sync_moex_tail(
                csv_path, name, timeout_sec=timeout, force=True
            )
        # markets_snapshot → ensure_replay_bars (фон); данные уже в SQLite после sync.
        return markets_snapshot(days=days)
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc
