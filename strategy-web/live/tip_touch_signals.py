"""Prod tip1m Mode B — same tip-Z geometry as Testing ``replay.tip_touch``.

Tip Z = rolling μ/σ on **completed M15** + current 1m spread.
Edges on consecutive 1m tips (Δt == 60s). No M15 close / settle wait.
"""

from __future__ import annotations

import logging
import math
import threading
import time
from typing import Any

import pandas as pd

from live.signals import Position, Signal, determine_z_signal, is_moex_equity_session_bar
from replay.tip_touch import TipPoint, build_tip_series

log = logging.getLogger(__name__)

M1_MS = 60_000
M15_MS = 15 * 60 * 1000

_1m_lock = threading.Lock()
_1m_cache: dict[str, Any] = {
    "df": None,
    "fetched_at": 0.0,
    "key": "",
}


def completed_m15_bars(bars: list[dict[str, Any]], now_ms: int) -> list[dict[str, Any]]:
    """Drop forming M15 slot — tip-Z μ/σ only uses closed 15m bars."""
    out: list[dict[str, Any]] = []
    for b in bars:
        ms = int(b.get("timestampMs") or 0)
        if ms <= 0:
            continue
        if now_ms >= ms + M15_MS:
            out.append(b)
    return out


def tip_point_to_bar(tip: TipPoint, *, tatn: float | None = None, tatnp: float | None = None) -> dict[str, Any]:
    return {
        "tradeDate": tip.trade_date,
        "timestampMs": int(tip.ts_ms),
        "zScore": float(tip.z),
        "spreadPercent": float(tip.spread),
        "tatnClose": tatn,
        "tatnpClose": tatnp,
        "session": bool(tip.session),
        "signalMode": "tip1m",
    }


def build_tip_bars_from_m1(
    m15_bars: list[dict[str, Any]],
    m1: pd.DataFrame,
    *,
    now_ms: int | None = None,
) -> list[dict[str, Any]]:
    """Build tip bar dicts for monitor/parity from M15 + 1m spread frame."""
    if not m15_bars or m1 is None or m1.empty:
        return []
    tips = build_tip_series(m15_bars, m1)
    # Map timestamp → legs for decision_bars / fills.
    by_ms: dict[int, tuple[float | None, float | None]] = {}
    if "tatn" in m1.columns and "tatnp" in m1.columns:
        for _, row in m1.iterrows():
            raw = row["timestamp"]
            if hasattr(raw, "timestamp"):
                ms = int(pd.Timestamp(raw).timestamp() * 1000)
            else:
                ms = int(pd.Timestamp(raw).timestamp() * 1000)
            try:
                tatn = float(row["tatn"]) if row["tatn"] == row["tatn"] else None
            except (TypeError, ValueError):
                tatn = None
            try:
                tatnp = float(row["tatnp"]) if row["tatnp"] == row["tatnp"] else None
            except (TypeError, ValueError):
                tatnp = None
            by_ms[ms] = (tatn, tatnp)

    out: list[dict[str, Any]] = []
    for tip in tips:
        # Minute [T, T+1m) is actionable only after close (or next minute exists).
        if now_ms is not None and tip.ts_ms + M1_MS > now_ms:
            continue
        tatn, tatnp = by_ms.get(int(tip.ts_ms), (None, None))
        out.append(tip_point_to_bar(tip, tatn=tatn, tatnp=tatnp))
    return out


def is_consecutive_1m(prev_ms: int, cur_ms: int) -> bool:
    if prev_ms <= 0 or cur_ms <= 0:
        return False
    return (cur_ms - prev_ms) == M1_MS


def plan_tip1m_catchup(
    tips: list[dict[str, Any]],
    last_proc_ms: int,
    *,
    max_edges: int = 90,
) -> tuple[str, list[tuple[dict, dict]]]:
    """Catchup modes mirror M15 plan_monitor_catchup, but on 1m consecutive tips.

    Returns:
      bootstrap | up_to_date | live | skip_gap
    """
    if not tips or len(tips) < 2:
        return "bootstrap", []
    if last_proc_ms <= 0:
        return "bootstrap", []

    start_i: int | None = None
    for i, b in enumerate(tips):
        ms = int(b.get("timestampMs") or 0)
        if ms > last_proc_ms:
            start_i = i
            break
    if start_i is None:
        return "up_to_date", []
    if start_i == 0:
        return "skip_gap", []

    pending: list[tuple[dict, dict]] = []
    expected_prev_ms = last_proc_ms
    for i in range(start_i, len(tips)):
        prev, cur = tips[i - 1], tips[i]
        prev_ms = int(prev.get("timestampMs") or 0)
        cur_ms = int(cur.get("timestampMs") or 0)
        if prev_ms != expected_prev_ms or not is_consecutive_1m(prev_ms, cur_ms):
            if not pending:
                return "skip_gap", [(prev, cur)]
            break
        pending.append((prev, cur))
        expected_prev_ms = cur_ms
        if len(pending) >= max_edges:
            break

    if not pending:
        return "up_to_date", []
    return "live", pending


def tip1m_mtm_pct_of_deposit(
    *,
    direction: str,
    entry_spread: float,
    cur_spread: float,
    deposit_rub: float,
    leverage: float,
) -> float:
    """Testing tip1m TP semantics: MTM = (deposit×lev)×Δspread%/100; pct of deposit."""
    dep = max(1.0, float(deposit_rub))
    lev = max(1.0, float(leverage))
    d = (direction or "").upper()
    if d == "LONG":
        pnl_pts = float(cur_spread) - float(entry_spread)
    elif d == "SHORT":
        pnl_pts = float(entry_spread) - float(cur_spread)
    else:
        return 0.0
    mtm = dep * lev * (pnl_pts / 100.0)
    return (mtm / dep) * 100.0


def should_exit_take_profit(
    *,
    position: Position,
    entry_spread: float | None,
    cur_spread: float | None,
    take_profit_pct: float,
    deposit_rub: float,
    leverage: float,
) -> bool:
    if take_profit_pct <= 0:
        return False
    if position not in (Position.LONG, Position.SHORT):
        return False
    if entry_spread is None or cur_spread is None:
        return False
    try:
        es = float(entry_spread)
        cs = float(cur_spread)
    except (TypeError, ValueError):
        return False
    if not (math.isfinite(es) and math.isfinite(cs)):
        return False
    direction = "LONG" if position == Position.LONG else "SHORT"
    pct = tip1m_mtm_pct_of_deposit(
        direction=direction,
        entry_spread=es,
        cur_spread=cs,
        deposit_rub=deposit_rub,
        leverage=leverage,
    )
    return pct >= float(take_profit_pct)


def signal_for_tip_edge(
    prev_z: float,
    cur_z: float,
    position: Position,
    entry: float,
    exit_z: float,
) -> Signal:
    """Same Z geometry as Prod determine_z_signal (shared with Testing tip1m)."""
    return determine_z_signal(prev_z, cur_z, position, entry, exit_z)


def collect_tip1m_sim_edges(
    tips: list[dict[str, Any]],
    entry: float,
    exit_z: float,
    *,
    respect_live_signal: bool = True,
) -> list[dict[str, Any]]:
    """Parity Test path: consecutive 1m tip edges (optional liveSignal NONE skip)."""
    pos = Position.FLAT
    edges: list[dict[str, Any]] = []
    for i in range(1, len(tips)):
        prev, cur = tips[i - 1], tips[i]
        prev_ms = int(prev.get("timestampMs") or 0)
        cur_ms = int(cur.get("timestampMs") or 0)
        if not is_consecutive_1m(prev_ms, cur_ms):
            continue
        cur_td = str(cur.get("tradeDate") or "")
        if not is_moex_equity_session_bar(cur_td):
            continue
        try:
            pz = float(prev.get("zScore") or 0)
            cz = float(cur.get("zScore") or 0)
        except (TypeError, ValueError):
            continue
        if not (math.isfinite(pz) and math.isfinite(cz)):
            continue
        sig = determine_z_signal(pz, cz, pos, entry, exit_z)
        if respect_live_signal:
            live_sig = str(cur.get("liveSignal") or "").upper()
            if live_sig == Signal.NONE.value and sig in (
                Signal.ENTER_LONG,
                Signal.ENTER_SHORT,
            ):
                continue
        if sig == Signal.NONE:
            continue
        edges.append(
            {
                "bar_ts": cur_td[:16] if len(cur_td) >= 16 else cur_td,
                "bar_ms": cur_ms,
                "signal": sig.value,
                "z": cz,
            }
        )
        if sig == Signal.ENTER_LONG:
            pos = Position.LONG
        elif sig == Signal.ENTER_SHORT:
            pos = Position.SHORT
        elif sig in (Signal.EXIT_LONG, Signal.EXIT_SHORT):
            pos = Position.FLAT
    return edges


def fetch_live_1m_spread(
    *,
    hours: float = 6.0,
    max_age_sec: float = 25.0,
) -> pd.DataFrame:
    """ISS 1m TATN/TATNP → timestamp/tatn/tatnp/spread (cached briefly)."""
    from api.moex_time import moex_now
    from m15_iss_loader import fetch_1m_spread_frame

    now = moex_now()
    key = f"{hours:.1f}|{now.strftime('%Y-%m-%d %H:%M')}"
    with _1m_lock:
        age = time.time() - float(_1m_cache.get("fetched_at") or 0)
        cached = _1m_cache.get("df")
        if (
            cached is not None
            and not cached.empty
            and _1m_cache.get("key") == key
            and age < max_age_sec
        ):
            return cached

    df = fetch_1m_spread_frame(hours=hours)
    with _1m_lock:
        _1m_cache["df"] = df
        _1m_cache["fetched_at"] = time.time()
        _1m_cache["key"] = key
    return df


def load_tip_bars_for_live(
    m15_bars: list[dict[str, Any]],
    *,
    now_ms: int,
    hours: float = 6.0,
) -> list[dict[str, Any]]:
    """Completed-M15 + live 1m → tip bars ready for monitor catchup."""
    m15 = completed_m15_bars(m15_bars, now_ms)
    if len(m15) < 48:
        # Still try — rolling Z needs history; incomplete → weak Z, edges rare.
        m15 = list(m15_bars)
    try:
        m1 = fetch_live_1m_spread(hours=hours)
    except Exception as exc:
        log.warning("tip1m 1m fetch failed: %s", exc)
        return []
    return build_tip_bars_from_m1(m15, m1, now_ms=now_ms)


def _parse_bar_dt(td: str):
    from datetime import datetime
    from zoneinfo import ZoneInfo

    MSK = ZoneInfo("Europe/Moscow")
    s = str(td or "").replace("T", " ").strip()
    if len(s) == 16:
        s += ":00"
    s = s[:19]
    return datetime.strptime(s, "%Y-%m-%d %H:%M:%S").replace(tzinfo=MSK)


def load_tip_bars_for_parity(
    m15_bars: list[dict[str, Any]],
    *,
    now_ms: int | None = None,
) -> list[dict[str, Any]]:
    """Parity: parquet 1m cache (+ live ISS fallback)."""
    from datetime import timedelta

    from replay.tip_touch import load_1m_from_cache

    m15 = completed_m15_bars(m15_bars, now_ms or int(time.time() * 1000))
    if len(m15) < 2:
        m15 = list(m15_bars)
    if not m15:
        return []
    start = end = None
    try:
        start = _parse_bar_dt(str(m15[0].get("tradeDate") or "")) - timedelta(days=2)
        end = _parse_bar_dt(str(m15[-1].get("tradeDate") or ""))
    except ValueError:
        start = end = None
    try:
        m1 = load_1m_from_cache(start, end)
    except Exception as exc:
        log.warning("parity tip1m cache load failed: %s — trying live 1m", exc)
        try:
            m1 = fetch_live_1m_spread(hours=72.0, max_age_sec=60.0)
        except Exception as exc2:
            log.warning("parity tip1m live fallback failed: %s", exc2)
            return []
    return build_tip_bars_from_m1(m15, m1, now_ms=now_ms)
