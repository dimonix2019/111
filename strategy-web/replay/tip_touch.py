"""1m tip-touch sim (Mode B) for Testing UI — shared tip-Z builder used by Prod live.

Tip Z = rolling μ/σ on completed M15 + current 1m spread; edge on consecutive 1m;
fill at tip spread ± slip. Prod monitor: ``live.tip_touch_signals`` + ``engine.monitor_tick``.
"""
from __future__ import annotations

import math
import threading
import time
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from zsim import (
    OVERNIGHT_FEE_PERCENT_PER_DAY,
    Z_SCORE_ROLLING_LOOKBACK_DAYS,
    Z_SCORE_ROLLING_MIN_BARS,
)

ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = ROOT / "data"
CACHE_1M = DATA_DIR / "cache_1m_tatn_spread.parquet"
MSK = __import__("zoneinfo").ZoneInfo("Europe/Moscow")

LEVERAGE = 7.0
COMM_PCT = 0.04
DEFAULT_SLIP = 0.02
DEFAULT_NOTIONAL = 10_000.0

_EPOCH = date(1970, 1, 1)
_SIM_CACHE_MAX = 48
_HM_CACHE_MAX = 12

_lock = threading.Lock()
_tip_cache: dict[str, Any] = {
    "key": None,
    "prep": None,
    "built_at": 0.0,
    "meta": {},
}
_sim_cache: dict[str, dict[str, Any]] = {}
_hm_cache: dict[str, dict[str, Any]] = {}


def _parse_td(s: str) -> datetime:
    s = str(s).replace("T", " ").strip()[:19]
    if len(s) == 16:
        s += ":00"
    return datetime.strptime(s, "%Y-%m-%d %H:%M:%S").replace(tzinfo=MSK)


def is_session_bar(trade_date: str) -> bool:
    s = str(trade_date or "").replace("T", " ").strip()
    if len(s) < 16:
        return False
    try:
        y, mo, d = int(s[0:4]), int(s[5:7]), int(s[8:10])
    except ValueError:
        return False
    dow = datetime(y, mo, d, 12, 0, 0).weekday()
    if dow >= 5:
        return False
    hm = s[11:16]
    return "07:00" <= hm < "23:50"


def floor_15m(dt: datetime) -> datetime:
    return dt.replace(minute=(dt.minute // 15) * 15, second=0, microsecond=0)


def load_m15_ui(csv_name: str) -> tuple[list[dict[str, Any]], str]:
    from replay.replay_db import _load_cached_bars

    name = Path(csv_name).name
    csv_path = DATA_DIR / name
    bars = _load_cached_bars(csv_path, name, None)
    if not bars:
        raise ValueError(f"no M15 bars for {name}")
    return bars, "ui_sqlite_lookback"


def _fees(notional: float) -> tuple[float, float, float]:
    eff = notional * LEVERAGE
    comm = eff * (COMM_PCT / 100.0)
    ovn = notional * max(0.0, LEVERAGE - 1.0) * (OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)
    return eff, comm, ovn


@dataclass
class TipPoint:
    trade_date: str
    ts_ms: int
    z: float
    spread: float
    session: bool
    slot_ms: int


@dataclass
class PreparedTips:
    """In-memory tip series + prefiltered edge index for fast sim/heatmap."""

    ts_ms: np.ndarray  # int64
    z: np.ndarray  # float64
    spread: np.ndarray  # float64
    day_ord: np.ndarray  # int32 calendar day ordinal
    session: np.ndarray  # bool
    trade_dates: list[str]
    edge_i: np.ndarray  # int32 indices i where (i-1→i) is a valid 1m edge
    n: int


def build_tip_series(m15: list[dict], m1: pd.DataFrame) -> list[TipPoint]:
    """1m tip Z using rolling window of completed M15 + tip as last observation."""
    m15_ms: list[int] = []
    m15_sp: list[float] = []
    for b in m15:
        dt = _parse_td(b["tradeDate"])
        m15_ms.append(int(dt.timestamp() * 1000))
        m15_sp.append(float(b["spreadPercent"]))

    m15_ms_arr = np.asarray(m15_ms, dtype=np.int64)
    m15_sp_arr = np.asarray(m15_sp, dtype=np.float64)

    lookback_days = Z_SCORE_ROLLING_LOOKBACK_DAYS
    min_bars = max(Z_SCORE_ROLLING_MIN_BARS, 2)

    completed_end = 0
    win_start = 0
    total = 0.0
    total_sq = 0.0

    out: list[TipPoint] = []
    m1_ts = m1["timestamp"].tolist()
    m1_sp = m1["spread"].astype(float).tolist()

    for i in range(len(m1_ts)):
        raw = m1_ts[i]
        if hasattr(raw, "to_pydatetime"):
            dt = raw.to_pydatetime()
        else:
            dt = pd.Timestamp(raw).to_pydatetime()
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=MSK)
        else:
            dt = dt.astimezone(MSK)

        slot = floor_15m(dt)
        slot_ms = int(slot.timestamp() * 1000)
        tip_ms = int(dt.timestamp() * 1000)
        tip_sp = float(m1_sp[i])

        while completed_end < len(m15_ms_arr) and m15_ms_arr[completed_end] < slot_ms:
            s = float(m15_sp_arr[completed_end])
            total += s
            total_sq += s * s
            completed_end += 1

        tip_date = dt.date()
        from_date = tip_date - timedelta(days=lookback_days)
        from_ms = int(
            datetime.combine(from_date, datetime.min.time(), MSK).timestamp() * 1000
        )
        while win_start < completed_end and m15_ms_arr[win_start] < from_ms:
            s = float(m15_sp_arr[win_start])
            total -= s
            total_sq -= s * s
            win_start += 1

        count = completed_end - win_start
        n = count + 1
        t = total + tip_sp
        tsq = total_sq + tip_sp * tip_sp
        if n < min_bars:
            z = 0.0
        else:
            mean = t / n
            var = (tsq / n) - mean * mean
            std = math.sqrt(max(var, 0.0))
            if std <= 1e-12:
                std = 1.0
            z = (tip_sp - mean) / std

        td = dt.strftime("%Y-%m-%d %H:%M:%S")
        out.append(
            TipPoint(
                trade_date=td,
                ts_ms=tip_ms,
                z=z,
                spread=tip_sp,
                session=is_session_bar(td),
                slot_ms=slot_ms,
            )
        )
    return out


def prepare_tips(tips: list[TipPoint]) -> PreparedTips:
    n = len(tips)
    ts_ms = np.empty(n, dtype=np.int64)
    z = np.empty(n, dtype=np.float64)
    spread = np.empty(n, dtype=np.float64)
    day_ord = np.empty(n, dtype=np.int32)
    session = np.empty(n, dtype=np.bool_)
    trade_dates: list[str] = [""] * n
    for i, tip in enumerate(tips):
        ts_ms[i] = tip.ts_ms
        z[i] = tip.z
        spread[i] = tip.spread
        session[i] = tip.session
        trade_dates[i] = tip.trade_date
        # day from trade_date YYYY-MM-DD
        td = tip.trade_date
        try:
            y, mo, d = int(td[0:4]), int(td[5:7]), int(td[8:10])
            day_ord[i] = (date(y, mo, d) - _EPOCH).days
        except Exception:
            day_ord[i] = 0

    edge: list[int] = []
    for i in range(1, n):
        if not session[i]:
            continue
        if ts_ms[i] - ts_ms[i - 1] != 60_000:
            continue
        if not (math.isfinite(z[i - 1]) and math.isfinite(z[i])):
            continue
        edge.append(i)
    return PreparedTips(
        ts_ms=ts_ms,
        z=z,
        spread=spread,
        day_ord=day_ord,
        session=session,
        trade_dates=trade_dates,
        edge_i=np.asarray(edge, dtype=np.int32),
        n=n,
    )


def load_1m_from_cache(
    start_dt: datetime | None = None,
    end_dt: datetime | None = None,
) -> pd.DataFrame:
    """Load 1m TATN/TATNP spread from parquet cache (no ISS fetch in API path)."""
    if not CACHE_1M.is_file():
        raise FileNotFoundError(
            f"Нет кэша 1м: {CACHE_1M.name}. "
            "Сначала: python scripts/backtest_intrabar_touch_1y.py"
        )
    cached = pd.read_parquet(CACHE_1M)
    cached["timestamp"] = pd.to_datetime(cached["timestamp"])
    if start_dt is not None:
        s = start_dt.replace(tzinfo=None) if start_dt.tzinfo else start_dt
        cached = cached.loc[cached["timestamp"] >= s]
    if end_dt is not None:
        e = end_dt.replace(tzinfo=None) if end_dt.tzinfo else end_dt
        cached = cached.loc[cached["timestamp"] <= e + timedelta(days=1)]
    if cached.empty:
        raise ValueError("1m cache empty for requested window")
    return cached.reset_index(drop=True)


def _parquet_mtime() -> float:
    return CACHE_1M.stat().st_mtime if CACHE_1M.is_file() else 0.0


def _light_tip_key(csv_name: str, m15: list[dict]) -> str:
    """Key without loading 1m: csv + M15 window + parquet mtime."""
    mtime = _parquet_mtime()
    first = m15[0]["tradeDate"] if m15 else ""
    last = m15[-1]["tradeDate"] if m15 else ""
    return f"{Path(csv_name).name}|{len(m15)}|{first}|{last}|{mtime:.0f}"


def ensure_tip_series(csv_name: str) -> tuple[PreparedTips, dict[str, Any]]:
    """Build or reuse in-process prepared tip series for CSV lookback."""
    name = Path(csv_name).name
    mtime = _parquet_mtime()

    with _lock:
        if (
            _tip_cache.get("csv") == name
            and _tip_cache.get("mtime") == mtime
            and _tip_cache["prep"] is not None
        ):
            meta = dict(_tip_cache["meta"] or {})
            meta["cacheHit"] = True
            return _tip_cache["prep"], meta

    m15, src = load_m15_ui(name)
    key = _light_tip_key(name, m15)

    with _lock:
        if _tip_cache["key"] == key and _tip_cache["prep"] is not None:
            meta = dict(_tip_cache["meta"] or {})
            meta["cacheHit"] = True
            _tip_cache["csv"] = name
            _tip_cache["mtime"] = mtime
            return _tip_cache["prep"], meta

    end_dt = _parse_td(m15[-1]["tradeDate"])
    start_dt = _parse_td(m15[0]["tradeDate"]) - timedelta(days=40)
    m1 = load_1m_from_cache(start_dt, end_dt)

    t0 = time.time()
    tips = build_tip_series(m15, m1)
    prep = prepare_tips(tips)
    meta = {
        "csv": name,
        "dataSourceM15": src,
        "dataSource1m": CACHE_1M.name,
        "m15Bars": len(m15),
        "m1Rows": len(m1),
        "tipPoints": prep.n,
        "edgeCount": int(len(prep.edge_i)),
        "m15From": m15[0]["tradeDate"] if m15 else None,
        "m15To": m15[-1]["tradeDate"] if m15 else None,
        "m1From": str(m1["timestamp"].iloc[0]) if len(m1) else None,
        "m1To": str(m1["timestamp"].iloc[-1]) if len(m1) else None,
        "buildSec": round(time.time() - t0, 2),
        "cacheHit": False,
        "mode": "tip1m",
        "logicRu": "касание порога на 1м tip-Z (не ждём close M15)",
    }
    with _lock:
        _tip_cache["key"] = key
        _tip_cache["csv"] = name
        _tip_cache["mtime"] = mtime
        _tip_cache["prep"] = prep
        _tip_cache["built_at"] = time.time()
        _tip_cache["meta"] = meta
        _sim_cache.clear()
        _hm_cache.clear()
    return prep, meta


def _window_start_ms(prep: PreparedTips, start: str | None) -> int:
    if not start:
        return 0
    s = str(start).strip()
    if len(s) == 10:
        s = s + " 00:00:00"
    try:
        dt = _parse_td(s)
    except ValueError:
        return 0
    return int(dt.timestamp() * 1000)


def _cache_put(cache: dict[str, dict[str, Any]], key: str, value: dict[str, Any], max_n: int) -> None:
    cache[key] = value
    while len(cache) > max_n:
        # drop oldest insertion (CPython 3.7+ dict order)
        cache.pop(next(iter(cache)), None)


def _edges_from(prep: PreparedTips, window_start_ms: int) -> np.ndarray:
    """Edge indices with tip ts >= window (binary search on tip timestamps)."""
    edges = prep.edge_i
    if window_start_ms <= 0 or len(edges) == 0:
        return edges
    # edges are increasing in ts; searchsorted on ts_ms[edges]
    ts_e = prep.ts_ms[edges]
    lo = int(np.searchsorted(ts_e, window_start_ms, side="left"))
    return edges[lo:] if lo else edges


def run_touch_1m_trades(
    prep: PreparedTips,
    entry: float,
    exit_z: float,
    *,
    window_start_ms: int = 0,
    compound: bool = False,
    slip: float = DEFAULT_SLIP,
    notional: float = DEFAULT_NOTIONAL,
    take_profit_pct: float = 0.0,
) -> dict[str, Any]:
    """Mode B with optional TP%; returns trades + summary for Testing UI."""
    z = prep.z
    sp_arr = prep.spread
    day_ord = prep.day_ord
    dates = prep.trade_dates
    edges = _edges_from(prep, window_start_ms)

    pos = 0  # 0 flat 1 long 2 short
    entry_sp = 0.0
    entry_td = ""
    entry_z = 0.0
    entry_day = 0
    entry_comm = 0.0
    eff = comm = ovn_day = 0.0
    base = float(notional)
    pos_notional = base
    realized = 0.0
    closed_trades: list[dict[str, Any]] = []
    peak = max_dd = 0.0
    total_pnl = 0.0
    trade_no = 0
    pnl_min = float("inf")
    pnl_max = float("-inf")
    hit1_td: str | None = None
    hit2_td: str | None = None
    hit3_td: str | None = None
    use_tp = take_profit_pct > 0

    def _mtm_at(i: int) -> float:
        is_long = pos == 1
        sp = sp_arr[i]
        pnl_pts = (sp - entry_sp) if is_long else (entry_sp - sp)
        gross = eff * (pnl_pts / 100.0)
        ovn = ovn_day * max(0, int(day_ord[i]) - entry_day)
        return gross - entry_comm - ovn

    def _open(sig: int, i: int) -> None:
        nonlocal pos, entry_sp, entry_td, entry_z, entry_day, entry_comm, eff, comm, ovn_day
        nonlocal pos_notional, trade_no, pnl_min, pnl_max, hit1_td, hit2_td, hit3_td
        if compound:
            pos_notional = max(1.0, base + realized)
        else:
            pos_notional = base
        eff, comm, ovn_day = _fees(pos_notional)
        entry_comm = comm
        sp = sp_arr[i]
        entry_sp = sp + slip if sig == 1 else sp - slip
        entry_td = dates[i]
        entry_z = float(z[i])
        entry_day = int(day_ord[i])
        pos = 1 if sig == 1 else 2
        trade_no += 1
        mtm = _mtm_at(i)
        pnl_min = mtm
        pnl_max = mtm
        hit1_td = hit2_td = hit3_td = None

    def _close(i: int, reason: str) -> None:
        nonlocal pos, realized, total_pnl, peak, max_dd, pnl_min, pnl_max
        nonlocal hit1_td, hit2_td, hit3_td
        is_long = pos == 1
        sp = sp_arr[i]
        exit_sp = sp - slip if is_long else sp + slip
        pnl_pts = (exit_sp - entry_sp) if is_long else (entry_sp - exit_sp)
        gross = eff * (pnl_pts / 100.0)
        ovn = ovn_day * max(0, int(day_ord[i]) - entry_day)
        net = gross - (entry_comm + comm) - ovn
        total_pnl += net
        realized += net
        if total_pnl > peak:
            peak = total_pnl
        max_dd = max(max_dd, peak - total_pnl)
        pmin = net if not math.isfinite(pnl_min) else min(pnl_min, net)
        pmax = net if not math.isfinite(pnl_max) else max(pnl_max, net)
        closed_trades.append(
            {
                "index": trade_no,
                "direction": "Long" if is_long else "Short",
                "entryDate": entry_td,
                "exitDate": dates[i],
                "entryZ": round(entry_z, 4),
                "exitZ": round(float(z[i]), 4),
                "entrySpread": round(entry_sp, 6),
                "exitSpread": round(exit_sp, 6),
                "entrySlip": slip,
                "pnlPts": round(pnl_pts, 6),
                "gross": round(gross, 4),
                "commission": round(entry_comm + comm, 4),
                "overnight": round(ovn, 4),
                "net": round(net, 4),
                "pnlMin": round(pmin, 4),
                "pnlMax": round(pmax, 4),
                "hit1Date": hit1_td,
                "hit2Date": hit2_td,
                "hit3Date": hit3_td,
                "accountAfter": round(base + realized, 2),
                "status": "Закрыта",
                "exitReason": reason,
                "notional": round(pos_notional, 2),
            }
        )
        pos = 0
        pnl_min = float("inf")
        pnl_max = float("-inf")
        hit1_td = hit2_td = hit3_td = None

    for i in edges:
        i = int(i)
        prev_z = z[i - 1]
        cur_z = z[i]

        if pos in (1, 2):
            mtm = _mtm_at(i)
            if mtm < pnl_min:
                pnl_min = mtm
            if mtm > pnl_max:
                pnl_max = mtm
            # M15 parity: first bar where MTM PnL ≥ 1%/2%/3% of notional
            # (independent of TP on/off — TP only closes early).
            pct = (mtm / max(1.0, pos_notional)) * 100.0
            td = dates[i]
            if hit1_td is None and pct >= 1.0:
                hit1_td = td
            if hit2_td is None and pct >= 2.0:
                hit2_td = td
            if hit3_td is None and pct >= 3.0:
                hit3_td = td
            if use_tp and pct >= take_profit_pct:
                _close(i, "tp")
                continue

        sig = 0
        if pos == 0:
            if prev_z > -entry and cur_z <= -entry:
                sig = 1
            elif prev_z < entry and cur_z >= entry:
                sig = 2
        elif pos == 1:
            if prev_z < -exit_z and cur_z >= -exit_z:
                sig = 3
        elif pos == 2:
            if prev_z > exit_z and cur_z <= exit_z:
                sig = 4
        if not sig:
            continue

        if sig in (1, 2):
            _open(sig, i)
        else:
            _close(i, "z_exit")

    wins = sum(1 for t in closed_trades if t["net"] > 0)
    closed_n = len(closed_trades)
    return {
        "trades": closed_trades,
        "summary": {
            "trades": closed_n,
            "wins": wins,
            "winratePct": round(100.0 * wins / closed_n, 1) if closed_n else 0.0,
            "pnlRub": round(total_pnl, 2),
            "maxDdRub": round(max_dd, 2),
            "finalEquityRub": round(base + total_pnl, 2),
            "retPct": round(100.0 * total_pnl / base, 2) if base else 0.0,
            "openCount": 1 if pos else 0,
        },
        "params": {
            "entry": entry,
            "exit": exit_z,
            "slip": slip,
            "notional": base,
            "compound": compound,
            "takeProfitPct": take_profit_pct,
            "leverage": LEVERAGE,
            "commissionPctPerSide": COMM_PCT,
            "overnightFeePercentPerDay": OVERNIGHT_FEE_PERCENT_PER_DAY,
        },
    }


def run_touch_1m_pnl_only(
    prep: PreparedTips,
    entry: float,
    exit_z: float,
    *,
    window_start_ms: int = 0,
    compound: bool = False,
    slip: float = DEFAULT_SLIP,
    notional: float = DEFAULT_NOTIONAL,
    take_profit_pct: float = 0.0,
    edges: np.ndarray | None = None,
) -> tuple[float, int]:
    """Fast path for heatmap: (total_pnl, closed_count) — no trade list / MAE."""
    if take_profit_pct > 0:
        r = run_touch_1m_trades(
            prep,
            entry,
            exit_z,
            window_start_ms=window_start_ms,
            compound=compound,
            slip=slip,
            notional=notional,
            take_profit_pct=take_profit_pct,
        )
        return float(r["summary"]["pnlRub"]), int(r["summary"]["trades"])

    z = prep.z
    sp_arr = prep.spread
    day_ord = prep.day_ord
    if edges is None:
        edges = _edges_from(prep, window_start_ms)

    pos = 0
    entry_sp = 0.0
    entry_day = 0
    entry_comm = 0.0
    eff = comm = ovn_day = 0.0
    base = float(notional)
    realized = 0.0
    closed = 0
    total_pnl = 0.0
    neg_entry = -entry
    neg_exit = -exit_z

    for i in edges:
        i = int(i)
        prev_z = z[i - 1]
        cur_z = z[i]

        if pos == 0:
            if prev_z > neg_entry and cur_z <= neg_entry:
                sig = 1
            elif prev_z < entry and cur_z >= entry:
                sig = 2
            else:
                continue
            pos_n = max(1.0, base + realized) if compound else base
            eff, comm, ovn_day = _fees(pos_n)
            entry_comm = comm
            sp = sp_arr[i]
            entry_sp = sp + slip if sig == 1 else sp - slip
            entry_day = int(day_ord[i])
            pos = sig
            continue

        if pos == 1:
            if not (prev_z < neg_exit and cur_z >= neg_exit):
                continue
            is_long = True
        else:
            if not (prev_z > exit_z and cur_z <= exit_z):
                continue
            is_long = False

        sp = sp_arr[i]
        exit_sp = sp - slip if is_long else sp + slip
        pnl_pts = (exit_sp - entry_sp) if is_long else (entry_sp - exit_sp)
        gross = eff * (pnl_pts / 100.0)
        ovn = ovn_day * max(0, int(day_ord[i]) - entry_day)
        net = gross - (entry_comm + comm) - ovn
        total_pnl += net
        realized += net
        closed += 1
        pos = 0

    return total_pnl, closed


def _sim_key(
    csv: str,
    entry: float,
    exit_z: float,
    slip: float,
    notional: float,
    compound: bool,
    take_profit_pct: float,
    start: str | None,
    tip_key: str,
) -> str:
    # v2: include hit1/2/3Date on closed trades (Testing table columns).
    return (
        f"{tip_key}|v2|e={entry:.4f}|x={exit_z:.4f}|s={slip:.4f}|"
        f"n={notional:.2f}|c={int(compound)}|tp={take_profit_pct:.4f}|start={start or ''}"
    )


def sim_tip1m(
    *,
    csv: str,
    entry: float,
    exit_z: float,
    slip: float = DEFAULT_SLIP,
    notional: float = DEFAULT_NOTIONAL,
    compound: bool = False,
    take_profit_pct: float = 0.0,
    start: str | None = None,
) -> dict[str, Any]:
    prep, meta = ensure_tip_series(csv)
    tip_key = str(_tip_cache.get("key") or "")
    skey = _sim_key(
        csv, entry, exit_z, slip, notional, compound, take_profit_pct, start, tip_key
    )
    with _lock:
        hit = _sim_cache.get(skey)
    if hit is not None:
        out = {
            "trades": hit["trades"],
            "summary": dict(hit["summary"]),
            "params": dict(hit["params"]),
            "meta": {
                **meta,
                "start": start,
                "windowStartMs": hit.get("windowStartMs", 0),
                "simSec": 0.0,
                "simCacheHit": True,
                "riskExitNoteRu": "Risk exit группы в режиме касания 1м пока не учитываются (только Z-edge + TP).",
            },
        }
        return out

    wms = _window_start_ms(prep, start)
    t0 = time.time()
    result = run_touch_1m_trades(
        prep,
        entry,
        exit_z,
        window_start_ms=wms,
        compound=compound,
        slip=slip,
        notional=notional,
        take_profit_pct=take_profit_pct,
    )
    result["meta"] = {
        **meta,
        "start": start,
        "windowStartMs": wms,
        "simSec": round(time.time() - t0, 3),
        "simCacheHit": False,
        "riskExitNoteRu": "Risk exit группы в режиме касания 1м пока не учитываются (только Z-edge + TP).",
    }
    with _lock:
        _cache_put(
            _sim_cache,
            skey,
            {
                "trades": result["trades"],
                "summary": result["summary"],
                "params": result["params"],
                "windowStartMs": wms,
            },
            _SIM_CACHE_MAX,
        )
    return result


def heatmap_tip1m(
    *,
    csv: str,
    entry_min: float = 0.5,
    entry_max: float = 2.7,
    exit_min: float = 0.5,
    step: float = 0.1,
    slip: float = DEFAULT_SLIP,
    notional: float = DEFAULT_NOTIONAL,
    compound: bool = False,
    take_profit_pct: float = 0.0,
    start: str | None = None,
) -> dict[str, Any]:
    prep, meta = ensure_tip_series(csv)
    tip_key = str(_tip_cache.get("key") or "")
    hkey = (
        f"{tip_key}|hm|{entry_min:.2f}|{entry_max:.2f}|{exit_min:.2f}|{step:.2f}|"
        f"s={slip:.4f}|n={notional:.2f}|c={int(compound)}|tp={take_profit_pct:.4f}|"
        f"start={start or ''}"
    )
    with _lock:
        hit = _hm_cache.get(hkey)
    if hit is not None:
        return {
            "cells": hit["cells"],
            "cellCount": hit["cellCount"],
            "meta": {
                **meta,
                **hit["meta_extra"],
                "heatmapSec": 0.0,
                "heatmapCacheHit": True,
            },
        }

    wms = _window_start_ms(prep, start)
    edges = _edges_from(prep, wms)
    cells: list[dict[str, Any]] = []
    e = round(entry_min, 10)
    t0 = time.time()
    while e <= entry_max + 1e-9:
        x = round(exit_min, 10)
        while x < e - 1e-9:
            pnl, n_tr = run_touch_1m_pnl_only(
                prep,
                round(e, 1),
                round(x, 1),
                window_start_ms=wms,
                compound=compound,
                slip=slip,
                notional=notional,
                take_profit_pct=take_profit_pct,
                edges=edges,
            )
            cells.append(
                {
                    "entry": round(e, 1),
                    "exit": round(x, 1),
                    "pnl": float(round(pnl, 2)),
                    "n": int(n_tr),
                }
            )
            x = round(x + step, 10)
        e = round(e + step, 10)
    meta_extra = {
        "start": start,
        "windowStartMs": wms,
        "grid": {
            "entryMin": entry_min,
            "entryMax": entry_max,
            "exitMin": exit_min,
            "step": step,
        },
        "slip": slip,
        "notional": notional,
        "compound": compound,
        "takeProfitPct": take_profit_pct,
        "heatmapSec": round(time.time() - t0, 2),
        "heatmapCacheHit": False,
        "riskExitNoteRu": "Risk exit в heatmap касания 1м не учитываются.",
    }
    out = {"cells": cells, "cellCount": len(cells), "meta": {**meta, **meta_extra}}
    with _lock:
        _cache_put(
            _hm_cache,
            hkey,
            {"cells": cells, "cellCount": len(cells), "meta_extra": meta_extra},
            _HM_CACHE_MAX,
        )
    return out


def bars1m_meta() -> dict[str, Any]:
    if not CACHE_1M.is_file():
        return {
            "ok": False,
            "source": None,
            "n": 0,
            "from": None,
            "to": None,
            "path": str(CACHE_1M.name),
            "hintRu": "Нет parquet — запустите scripts/backtest_intrabar_touch_1y.py",
        }
    df = pd.read_parquet(CACHE_1M, columns=["timestamp"])
    ts = pd.to_datetime(df["timestamp"])
    return {
        "ok": True,
        "source": CACHE_1M.name,
        "n": int(len(df)),
        "from": str(ts.min()),
        "to": str(ts.max()),
        "bytes": CACHE_1M.stat().st_size,
        "path": str(CACHE_1M.name),
    }


# Soft cap for Testing chart: full-year 1m (~200k+) freezes lightweight-charts.
DEFAULT_CHART_DAYS = 90
CHART_SOFT_MAX_BARS = 80_000


def bars1m_chart(
    csv: str,
    start: str | None = None,
    chart_days: int | None = DEFAULT_CHART_DAYS,
) -> dict[str, Any]:
    """1m tip-Z bars for Testing chart (same shape as /api/bars).

    Sim stays full-window server-side; chart may be truncated to last ``chart_days``
    calendar days when the tip series is large.
    """
    if not CACHE_1M.is_file():
        raise FileNotFoundError(
            f"Нет кэша 1м: {CACHE_1M.name}. "
            "Сначала: python scripts/backtest_intrabar_touch_1y.py"
        )
    prep, meta = ensure_tip_series(csv)
    wms = _window_start_ms(prep, start)
    lo = 0
    if wms > 0:
        lo = int(np.searchsorted(prep.ts_ms, wms, side="left"))
    hi = int(prep.n)
    full_n = max(0, hi - lo)
    chart_limited = False
    applied_days: int | None = None
    days = None if chart_days is None else int(chart_days)
    if days is not None and days <= 0:
        days = None
    # Auto-cap huge series even if chartDays=0 (full year ~200k+ freezes LC).
    if days is None and full_n > CHART_SOFT_MAX_BARS:
        days = DEFAULT_CHART_DAYS
    if days is not None and full_n > 0 and hi > lo:
        last_ms = int(prep.ts_ms[hi - 1])
        cut_ms = last_ms - int(days) * 86_400_000
        cut_lo = int(np.searchsorted(prep.ts_ms, max(wms, cut_ms), side="left"))
        if cut_lo > lo:
            lo = cut_lo
            chart_limited = True
            applied_days = days

    # tip-Z close; UI builds synthetic OHLC like M15 Z candles.
    out_bars: list[dict[str, Any]] = []
    for i in range(lo, hi):
        out_bars.append(
            {
                "timestampMs": int(prep.ts_ms[i]),
                "tradeDate": prep.trade_dates[i],
                "zScore": float(prep.z[i]),
                "spreadPercent": float(prep.spread[i]),
            }
        )
    hint = None
    if chart_limited:
        hint = (
            f"График 1м: последние {applied_days} календ. дн. "
            f"({len(out_bars)} баров из {full_n}). Симуляция на сервере — полный период."
        )
    return {
        "ok": True,
        "bars": out_bars,
        "count": len(out_bars),
        "first": out_bars[0]["tradeDate"] if out_bars else None,
        "last": out_bars[-1]["tradeDate"] if out_bars else None,
        "fullTipCount": full_n,
        "chartLimited": chart_limited,
        "chartDays": applied_days if chart_limited else days,
        "hintRu": hint,
        "meta": {
            **meta,
            "start": start,
            "windowStartMs": wms,
            "chartLo": lo,
            "chartHi": hi,
        },
    }
