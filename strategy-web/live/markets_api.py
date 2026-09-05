"""Market tab API — live Z/spread summary + M15 bars for chart periods."""

from __future__ import annotations

import threading
import time
from datetime import datetime, timedelta
from typing import Any

from fastapi import APIRouter, HTTPException, Query

from live import engine, store

router = APIRouter(prefix="/api/markets", tags=["markets"])

PERIOD_DAYS = {1: 1, 7: 7, 30: 30, 90: 90, 180: 180}

# Desk/polls share the same days window — avoid MOEX LAST on every hit.
_MKT_CACHE: dict[str, Any] = {"key": None, "ts": 0.0, "payload": None}
_MKT_CACHE_TTL = 6.0
_MKT_LOCK = threading.Lock()


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


def build_markets_snapshot(
    days: int = 7,
    *,
    live_tip: bool = True,
) -> dict[str, Any]:
    """
    Chart bars + summary for markets/desk.

    live_tip=False — SQLite only (no MOEX LAST / market_snapshot). Fast Trade first paint.
    """
    days = PERIOD_DAYS.get(days, days if days in (1, 7, 30, 90, 180) else 7)
    cache_key = f"{days}:{'tip' if live_tip else 'fast'}"
    now = time.time()
    with _MKT_LOCK:
        if (
            _MKT_CACHE["key"] == cache_key
            and _MKT_CACHE["payload"] is not None
            and (now - float(_MKT_CACHE["ts"])) < _MKT_CACHE_TTL
        ):
            return _MKT_CACHE["payload"]

    from pathlib import Path

    from replay.replay_db import ensure_replay_bars

    data_dir = Path(__file__).resolve().parent.parent / "data"
    csv_path = data_dir / "m15_tatn_255d.csv"
    start_date = None
    if days in (1, 7, 30, 90, 180):
        start_date = (datetime.now() - timedelta(days=days + 2)).strftime("%Y-%m-%d")

    # Chart series from SQLite only — never block on ISS here
    payload = ensure_replay_bars(
        csv_path, "m15_tatn_255d.csv", online=False, start_date=start_date
    )
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

    if live_tip:
        snap = engine.market_snapshot()
        summary = {
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
        }
    else:
        # SQLite-only path (desk speedup). Do NOT hardcode online=False —
        # that made Trade UI show a false "offline" badge while monitor is up.
        # Match engine.market_snapshot(wait_sync=False): sqlite tip ⇒ online.
        last = bars[-1] if bars else {}
        mon = engine.monitor_status()
        summary = {
            "z": last.get("zScore"),
            "spread": last.get("spreadPercent"),
            "tatn": last.get("tatnClose"),
            "tatnp": last.get("tatnpClose"),
            "trade_date": last.get("tradeDate"),
            "position": pos.value,
            "entry_z": settings.get("entry_z"),
            "exit_z": settings.get("exit_z"),
            "source": payload.get("source") or "sqlite",
            "online": bool(mon.get("running")) or bool(bars),
            "count": payload.get("db_count"),
            "window_count": len(bars),
        }

    out = {
        "ok": True,
        "days": days,
        "summary": summary,
        "bars": chart_bars,
        "open": open_t,
    }
    with _MKT_LOCK:
        _MKT_CACHE["key"] = cache_key
        _MKT_CACHE["ts"] = time.time()
        _MKT_CACHE["payload"] = out
    return out


@router.get("")
def markets_snapshot(days: int = Query(7, description="1 / 7 / 30 / 90 / 180")) -> dict[str, Any]:
    try:
        return build_markets_snapshot(days, live_tip=True)
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
            # 3y CSV: sync routes to 255d live series (no 50k rewrite). Short timeout OK.
            from m15_iss_loader import lookback_days_for_path

            days_lookback = lookback_days_for_path(name)
            timeout = 35.0 if days_lookback >= 900 else 45.0
            replay_db.sync_moex_tail(
                csv_path, name, timeout_sec=timeout, force=True
            )
        # Invalidate short cache so tip reflects fresh sync
        with _MKT_LOCK:
            _MKT_CACHE["key"] = None
            _MKT_CACHE["payload"] = None
        try:
            from live.metric_dist import invalidate_desk_metric_dists

            invalidate_desk_metric_dists()
        except Exception:
            pass
        return build_markets_snapshot(days, live_tip=True)
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc
