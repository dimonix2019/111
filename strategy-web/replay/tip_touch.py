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
# Мета для UI; расчёт — ступени Премиум на ~половину номинала пары.
OVERNIGHT_FEE_MODEL = "premium_short_leg_tiers"

_EPOCH = date(1970, 1, 1)
_SIM_CACHE_MAX = 48
_HM_CACHE_MAX = 12
_PREP_CACHE_VERSION = 2
_EXTEND_MIN_INTERVAL_SEC = 90.0

_lock = threading.Lock()
_tip_build_lock = threading.Lock()
_tip_cache: dict[str, Any] = {
    "key": None,
    "prep": None,
    "built_at": 0.0,
    "meta": {},
    "csv": None,
    "mtime": None,
}
_sim_cache: dict[str, dict[str, Any]] = {}
_hm_cache: dict[str, dict[str, Any]] = {}
_parquet_frame_cache: dict[str, Any] = {"mtime": None, "df": None}
_extend_last_attempt = 0.0
_extend_bg_started = False


def _kick_extend_1m_background(*, until: datetime | None = None) -> None:
    """Non-blocking parquet tail fill (watchdog never called extend_1m_cache)."""
    global _extend_bg_started

    def _run() -> None:
        global _extend_bg_started
        try:
            extend_1m_cache(until=until or datetime.now(tz=MSK))
        except Exception:
            pass
        finally:
            _extend_bg_started = False

    if _extend_bg_started:
        return
    _extend_bg_started = True
    threading.Thread(target=_run, name="extend-1m-bg", daemon=True).start()


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


def _fees(
    notional: float,
    *,
    direction: str | None = None,
    lots: int | float | None = None,
    fill_tatn: float | None = None,
    fill_tatnp: float | None = None,
    execution_notional_rub: float | None = None,
) -> tuple[float, float, float]:
    """Комиссия на eff; overnight = ступень Премиум (короткая нога, иначе ≈eff/2).

    ``notional`` — депозит (кап. теста); если есть ``execution_notional_rub`` Prod —
    eff берётся с исполнения (как панель Сделка Min/Max).
    """
    from live.overnight_fee import overnight_fee_per_day_rub, short_leg_uncovered_rub

    if execution_notional_rub is not None and float(execution_notional_rub) > 0:
        eff = float(execution_notional_rub)
    else:
        eff = float(notional) * LEVERAGE
    comm = eff * (COMM_PCT / 100.0)
    uncovered = short_leg_uncovered_rub(
        direction=direction,
        lots=lots,
        fill_tatn=fill_tatn,
        fill_tatnp=fill_tatnp,
        notional_rub=eff,
    )
    ovn = overnight_fee_per_day_rub(uncovered)
    return eff, comm, ovn


def _try_numba_pnl_kernel():
    """Compile numba kernel once; return None if numba unavailable."""
    try:
        from numba import njit
    except Exception:
        return None

    lev = float(LEVERAGE)
    comm_pct = float(COMM_PCT)

    @njit(cache=True)
    def _premium_ovn_day(uncovered: float) -> float:
        u = uncovered if uncovered > 0.0 else 0.0
        if u <= 0.0:
            return 0.0
        if u <= 5000.0:
            return 0.0
        if u <= 50000.0:
            return 35.0
        if u <= 100000.0:
            return 70.0
        if u <= 250000.0:
            return 175.0
        if u <= 500000.0:
            return 340.0
        if u <= 1000000.0:
            return 680.0
        if u <= 2500000.0:
            return 1700.0
        if u <= 5000000.0:
            return 3400.0
        if u <= 10000000.0:
            return 6800.0
        if u <= 25000000.0:
            return u * 0.00066
        if u <= 50000000.0:
            return u * 0.00063
        return u * 0.00055

    @njit(cache=True)
    def _kernel(
        z,
        sp,
        day,
        edges,
        entry,
        exit_z,
        slip,
        notional,
        compound,
        tp,
    ):
        pos = 0
        entry_sp = 0.0
        entry_day = 0
        entry_comm = 0.0
        pos_notional = 0.0
        eff = 0.0
        comm = 0.0
        ovn_day = 0.0
        base = notional
        realized = 0.0
        closed = 0
        total = 0.0
        neg_entry = -entry
        neg_exit = -exit_z
        use_tp = tp > 0.0
        n_e = edges.shape[0]
        for k in range(n_e):
            i = int(edges[k])
            prev_z = z[i - 1]
            cur_z = z[i]
            if pos != 0:
                if use_tp:
                    s = sp[i]
                    is_long = pos == 1
                    pnl_pts = (s - entry_sp) if is_long else (entry_sp - s)
                    mtm = eff * (pnl_pts / 100.0) - entry_comm - (
                        ovn_day * max(0, int(day[i]) - entry_day)
                    )
                    if (mtm / max(1.0, pos_notional)) * 100.0 >= tp:
                        exit_sp = s - slip if is_long else s + slip
                        pnl_pts2 = (
                            (exit_sp - entry_sp) if is_long else (entry_sp - exit_sp)
                        )
                        net = (
                            eff * (pnl_pts2 / 100.0)
                            - (entry_comm + comm)
                            - ovn_day * max(0, int(day[i]) - entry_day)
                        )
                        total += net
                        realized += net
                        closed += 1
                        pos = 0
                        continue
                if pos == 1:
                    if prev_z < neg_exit and cur_z >= neg_exit:
                        s = sp[i]
                        exit_sp = s - slip
                        net = (
                            eff * ((exit_sp - entry_sp) / 100.0)
                            - (entry_comm + comm)
                            - ovn_day * max(0, int(day[i]) - entry_day)
                        )
                        total += net
                        realized += net
                        closed += 1
                        pos = 0
                    continue
                if prev_z > exit_z and cur_z <= exit_z:
                    s = sp[i]
                    exit_sp = s + slip
                    net = (
                        eff * ((entry_sp - exit_sp) / 100.0)
                        - (entry_comm + comm)
                        - ovn_day * max(0, int(day[i]) - entry_day)
                    )
                    total += net
                    realized += net
                    closed += 1
                    pos = 0
                continue
            if prev_z > neg_entry and cur_z <= neg_entry:
                sig = 1
            elif prev_z < entry and cur_z >= entry:
                sig = 2
            else:
                continue
            if compound:
                pos_notional = max(1.0, base + realized)
            else:
                pos_notional = base
            eff = pos_notional * lev
            comm = eff * (comm_pct / 100.0)
            ovn_day = _premium_ovn_day(eff * 0.5)
            entry_comm = comm
            s = sp[i]
            entry_sp = s + slip if sig == 1 else s - slip
            entry_day = int(day[i])
            pos = sig
        return total, closed

    return _kernel


_pnl_numba_kernel = None
_pnl_numba_ready = False


def _get_pnl_numba_kernel():
    global _pnl_numba_kernel, _pnl_numba_ready
    if _pnl_numba_ready:
        return _pnl_numba_kernel
    _pnl_numba_ready = True
    _pnl_numba_kernel = _try_numba_pnl_kernel()
    return _pnl_numba_kernel


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
    """1m tip Z using rolling window of completed M15 + tip as last observation.

    Prefer ``build_prepared_tips`` for hot paths (avoids TipPoint allocation).
    """
    prep = build_prepared_tips(m15, m1)
    out: list[TipPoint] = []
    for i in range(prep.n):
        out.append(
            TipPoint(
                trade_date=prep.trade_dates[i],
                ts_ms=int(prep.ts_ms[i]),
                z=float(prep.z[i]),
                spread=float(prep.spread[i]),
                session=bool(prep.session[i]),
                slot_ms=0,
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


def build_prepared_tips(m15: list[dict], m1: pd.DataFrame) -> PreparedTips:
    """Vectorized tip-Z + PreparedTips in one pass (no TipPoint list)."""
    m15_n = len(m15)
    m15_ms = np.empty(m15_n, dtype=np.int64)
    m15_sp = np.empty(m15_n, dtype=np.float64)
    for i, b in enumerate(m15):
        dt = _parse_td(b["tradeDate"])
        m15_ms[i] = int(dt.timestamp() * 1000)
        m15_sp[i] = float(b["spreadPercent"])

    ts_raw = pd.to_datetime(m1["timestamp"])
    if getattr(ts_raw.dt, "tz", None) is not None:
        ts_naive = ts_raw.dt.tz_convert(MSK).dt.tz_localize(None)
    else:
        ts_naive = ts_raw
    ts_msk = ts_naive.dt.tz_localize(MSK, ambiguous="infer", nonexistent="shift_forward")
    # Robust ms epoch (dtype may be [us] or [ns]; avoid unit footguns).
    tip_ms = (
        (
            pd.DatetimeIndex(ts_msk).tz_convert("UTC").tz_localize(None)
            - pd.Timestamp("1970-01-01")
        )
        / pd.Timedelta(milliseconds=1)
    ).to_numpy(dtype=np.int64)
    tip_sp = m1["spread"].to_numpy(dtype=np.float64, copy=False)
    n = int(len(tip_ms))
    if n == 0:
        return PreparedTips(
            ts_ms=np.empty(0, dtype=np.int64),
            z=np.empty(0, dtype=np.float64),
            spread=np.empty(0, dtype=np.float64),
            day_ord=np.empty(0, dtype=np.int32),
            session=np.empty(0, dtype=np.bool_),
            trade_dates=[],
            edge_i=np.empty(0, dtype=np.int32),
            n=0,
        )

    # Floor to 15m slot in MSK wall time (same as floor_15m).
    # tip_ms is UTC epoch ms of the MSK-local instant.
    slot_ms = tip_ms - ((tip_ms + 3 * 3_600_000) % 900_000)

    # Trade-date strings + calendar day + session from MSK components.
    # Format once via pandas (faster than per-row strftime in Python).
    trade_dates = ts_naive.dt.strftime("%Y-%m-%d %H:%M:%S").tolist()
    day_ord = (
        (ts_naive.dt.normalize() - pd.Timestamp("1970-01-01"))
        .dt.days.astype(np.int32)
        .to_numpy()
    )
    dow = ts_naive.dt.dayofweek.to_numpy()  # Mon=0 … Sun=6
    minutes = (ts_naive.dt.hour * 60 + ts_naive.dt.minute).to_numpy()
    session = (dow < 5) & (minutes >= 7 * 60) & (minutes < 23 * 60 + 50)

    lookback_days = int(Z_SCORE_ROLLING_LOOKBACK_DAYS)
    min_bars = max(int(Z_SCORE_ROLLING_MIN_BARS), 2)
    z_out = np.empty(n, dtype=np.float64)

    completed_end = 0
    win_start = 0
    total = 0.0
    total_sq = 0.0
    m15_len = len(m15_ms)

    # Midnights in MSK as epoch ms for lookback window.
    # day_ord * 86400000 is UTC midnight; MSK midnight = that - 3h in epoch terms
    # for civil dates… safer: compute from_ms via search on day boundaries.
    # Use tip calendar day midnight MSK = tip_ms floored to MSK midnight.
    msk_offset = 3 * 3_600_000
    tip_msk_mid_ms = tip_ms - ((tip_ms + msk_offset) % 86_400_000)

    for i in range(n):
        sm = int(slot_ms[i])
        while completed_end < m15_len and m15_ms[completed_end] < sm:
            s = float(m15_sp[completed_end])
            total += s
            total_sq += s * s
            completed_end += 1

        from_ms = int(tip_msk_mid_ms[i]) - lookback_days * 86_400_000
        while win_start < completed_end and m15_ms[win_start] < from_ms:
            s = float(m15_sp[win_start])
            total -= s
            total_sq -= s * s
            win_start += 1

        count = completed_end - win_start
        nn = count + 1
        tip = float(tip_sp[i])
        t = total + tip
        tsq = total_sq + tip * tip
        if nn < min_bars:
            z_out[i] = 0.0
        else:
            mean = t / nn
            var = (tsq / nn) - mean * mean
            std = math.sqrt(max(var, 0.0))
            if std <= 1e-12:
                std = 1.0
            z_out[i] = (tip - mean) / std

    # Edges: consecutive 1m session bars with finite Z.
    dt = tip_ms[1:] - tip_ms[:-1]
    ok = (
        session[1:]
        & (dt == 60_000)
        & np.isfinite(z_out[1:])
        & np.isfinite(z_out[:-1])
    )
    edge_i = (np.flatnonzero(ok) + 1).astype(np.int32)

    return PreparedTips(
        ts_ms=tip_ms,
        z=z_out,
        spread=np.asarray(tip_sp, dtype=np.float64),
        day_ord=day_ord,
        session=np.asarray(session, dtype=np.bool_),
        trade_dates=trade_dates,
        edge_i=edge_i,
        n=n,
    )


def _naive_ts(ts: Any) -> pd.Timestamp:
    t = pd.Timestamp(ts)
    if t.tzinfo is not None:
        t = t.tz_convert(MSK).tz_localize(None)
    return t


def _m1_frame_from_decision_bars(
    *,
    after_ts: pd.Timestamp | None = None,
) -> pd.DataFrame:
    """Fill 1m legs from Prod decision_bars (tatn/tatnp) after parquet gap."""
    try:
        from live import store as live_store
    except Exception:
        return pd.DataFrame(columns=["timestamp", "tatn", "tatnp", "spread"])
    from_s = None
    if after_ts is not None:
        # include same-minute overlaps; dedupe keeps last
        from_s = (after_ts - timedelta(minutes=1)).strftime("%Y-%m-%d %H:%M:%S")
    rows = live_store.get_decision_bars(from_ts=from_s)
    out: list[dict[str, Any]] = []
    for r in rows:
        tatn = r.get("tatn_close")
        tatnp = r.get("tatnp_close")
        if tatn is None or tatnp is None:
            continue
        try:
            a = float(tatn)
            b = float(tatnp)
        except (TypeError, ValueError):
            continue
        if not (math.isfinite(a) and math.isfinite(b) and b != 0):
            continue
        td = str(r.get("bar_ts") or "").replace("T", " ").strip()
        if len(td) == 16:
            td += ":00"
        td = td[:19]
        if len(td) < 19:
            continue
        try:
            ts = pd.Timestamp(td)
        except Exception:
            continue
        if after_ts is not None and ts <= after_ts:
            continue
        out.append(
            {
                "timestamp": ts,
                "tatn": a,
                "tatnp": b,
                "spread": (a / b - 1.0) * 100.0,
            }
        )
    if not out:
        return pd.DataFrame(columns=["timestamp", "tatn", "tatnp", "spread"])
    return pd.DataFrame(out)


def _m1_frame_from_iss(*, after_ts: pd.Timestamp, until: pd.Timestamp) -> pd.DataFrame:
    """ISS 1m TATN/TATNP from after_ts → until (inclusive calendar days).

    Hard wall timeout: hung ISS must not block tip1m / health for minutes.
    """
    from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeout

    from m15_iss_loader import fetch_1m_spread_frame

    hours = max(6.0, (until - after_ts).total_seconds() / 3600.0 + 6.0)
    hours = min(hours, 24.0 * 14)  # hard cap ~2 weeks
    till_s = until.strftime("%Y-%m-%d")

    def _fetch() -> pd.DataFrame:
        return fetch_1m_spread_frame(hours=hours, till=till_s)

    try:
        with ThreadPoolExecutor(max_workers=1) as pool:
            fut = pool.submit(_fetch)
            try:
                df = fut.result(timeout=12.0)
            except FuturesTimeout:
                fut.cancel()
                return pd.DataFrame(columns=["timestamp", "tatn", "tatnp", "spread"])
    except Exception:
        return pd.DataFrame(columns=["timestamp", "tatn", "tatnp", "spread"])
    if df is None or df.empty:
        return pd.DataFrame(columns=["timestamp", "tatn", "tatnp", "spread"])
    out = df.copy()
    out["timestamp"] = out["timestamp"].map(_naive_ts)
    out = out.loc[out["timestamp"] > after_ts]
    if out.empty:
        return pd.DataFrame(columns=["timestamp", "tatn", "tatnp", "spread"])
    return out[["timestamp", "tatn", "tatnp", "spread"]].reset_index(drop=True)


def extend_1m_cache(
    *,
    until: datetime | None = None,
    min_lag_min: float = 3.0,
    force: bool = False,
) -> dict[str, Any]:
    """Append missing 1m rows after parquet max (ISS + decision_bars).

    Root-cause guard: month-chunk loaders that skip a whole month once any day
    exists left Testing tip1m stuck at Fri 2026-07-24 while Prod traded Mon.

    Throttled: at most one ISS/decision attempt per ``_EXTEND_MIN_INTERVAL_SEC``
    unless ``force=True`` (avoids ~1s+ no-op cost on every tip rebuild).
    """
    global _extend_last_attempt
    meta: dict[str, Any] = {
        "extended": False,
        "added": 0,
        "from_iss": 0,
        "from_decision": 0,
        "cache_to": None,
    }
    if not CACHE_1M.is_file():
        meta["error"] = "no_cache"
        return meta

    now_mono = time.monotonic()
    if (
        not force
        and _extend_last_attempt > 0
        and (now_mono - _extend_last_attempt) < _EXTEND_MIN_INTERVAL_SEC
    ):
        meta["throttled"] = True
        meta["note"] = "extend_throttled"
        return meta
    _extend_last_attempt = now_mono

    end = until or datetime.now(tz=MSK)
    if end.tzinfo is None:
        end = end.replace(tzinfo=MSK)
    end_naive = end.replace(tzinfo=None)

    # Never call _read_parquet_cached() while holding _lock (non-reentrant Lock → deadlock).
    # Also keep ISS / decision I/O outside the lock so tip1m/bars1m stay responsive.
    cached = _read_parquet_cached()
    if cached is None or cached.empty:
        meta["error"] = "empty_cache"
        return meta
    cmax = _naive_ts(cached["timestamp"].max())
    meta["cache_to"] = str(cmax)
    lag_min = (end_naive - cmax).total_seconds() / 60.0
    if not force and lag_min < float(min_lag_min):
        meta["lag_min"] = round(lag_min, 2)
        return meta

    parts: list[pd.DataFrame] = []
    iss = _m1_frame_from_iss(after_ts=cmax, until=end_naive)
    if not iss.empty:
        parts.append(iss)
        meta["from_iss"] = int(len(iss))
    dec = _m1_frame_from_decision_bars(after_ts=cmax)
    if not dec.empty:
        parts.append(dec)
        meta["from_decision"] = int(len(dec))
    if not parts:
        meta["lag_min"] = round(lag_min, 2)
        meta["note"] = "no_new_rows"
        return meta

    add = pd.concat(parts, ignore_index=True)
    add["timestamp"] = add["timestamp"].map(_naive_ts)
    add = add.dropna(subset=["timestamp", "tatn", "tatnp"])
    add = add.loc[add["timestamp"] > cmax]
    if add.empty:
        meta["note"] = "no_new_after_filter"
        return meta
    out = pd.concat([cached, add], ignore_index=True)
    out = out.drop_duplicates(subset=["timestamp"], keep="last").sort_values(
        "timestamp"
    )
    CACHE_1M.parent.mkdir(parents=True, exist_ok=True)
    out.to_parquet(CACHE_1M, index=False)
    with _lock:
        _parquet_frame_cache["mtime"] = _parquet_mtime()
        _parquet_frame_cache["df"] = out
        # Bust in-process tip/sim caches — parquet mtime changed.
        _tip_cache["key"] = None
        _tip_cache["prep"] = None
        _tip_cache["mtime"] = None
        _sim_cache.clear()
        _hm_cache.clear()
        _invalidate_prep_disk_caches()
    meta["extended"] = True
    meta["added"] = int(len(add))
    meta["cache_to"] = str(out["timestamp"].max())
    meta["rows"] = int(len(out))
    return meta


def _read_parquet_cached() -> pd.DataFrame | None:
    if not CACHE_1M.is_file():
        return None
    mtime = _parquet_mtime()
    with _lock:
        if (
            _parquet_frame_cache.get("df") is not None
            and _parquet_frame_cache.get("mtime") == mtime
        ):
            return _parquet_frame_cache["df"]
    df = pd.read_parquet(CACHE_1M)
    df["timestamp"] = pd.to_datetime(df["timestamp"]).map(_naive_ts)
    with _lock:
        # Another thread may have refreshed while we read disk.
        if (
            _parquet_frame_cache.get("df") is not None
            and _parquet_frame_cache.get("mtime") == _parquet_mtime()
        ):
            return _parquet_frame_cache["df"]
        _parquet_frame_cache["mtime"] = mtime
        _parquet_frame_cache["df"] = df
    return df


def load_1m_from_cache(
    start_dt: datetime | None = None,
    end_dt: datetime | None = None,
    *,
    extend: bool = True,
) -> pd.DataFrame:
    """Load 1m TATN/TATNP spread from parquet (auto-extends stale tail)."""
    if not CACHE_1M.is_file():
        raise FileNotFoundError(
            f"Нет кэша 1м: {CACHE_1M.name}. "
            "Сначала: python scripts/backtest_intrabar_touch_1y.py"
        )
    if extend:
        try:
            extend_1m_cache(until=end_dt or datetime.now(tz=MSK))
        except Exception:
            # Best-effort: still serve whatever parquet has.
            pass
    cached = _read_parquet_cached()
    if cached is None or cached.empty:
        raise ValueError("1m cache empty for requested window")
    out = cached
    if start_dt is not None:
        s = start_dt.replace(tzinfo=None) if start_dt.tzinfo else start_dt
        out = out.loc[out["timestamp"] >= s]
    if end_dt is not None:
        e = end_dt.replace(tzinfo=None) if end_dt.tzinfo else end_dt
        out = out.loc[out["timestamp"] <= e + timedelta(days=1)]
    if out.empty:
        raise ValueError("1m cache empty for requested window")
    return out.reset_index(drop=True)


def _parquet_mtime() -> float:
    return CACHE_1M.stat().st_mtime if CACHE_1M.is_file() else 0.0


def _prep_disk_path(csv_name: str) -> Path:
    stem = Path(csv_name).stem
    return DATA_DIR / f"cache_tip1m_prep_{stem}.v{_PREP_CACHE_VERSION}.npz"


def _invalidate_prep_disk_caches() -> None:
    for p in DATA_DIR.glob("cache_tip1m_prep_*.npz"):
        try:
            p.unlink(missing_ok=True)
        except Exception:
            pass


def _save_prep_disk(csv_name: str, key: str, prep: PreparedTips) -> None:
    path = _prep_disk_path(csv_name)
    try:
        # trade_dates as UTF-8 bytes fixed width for fast round-trip
        td = np.asarray(prep.trade_dates, dtype="U19")
        np.savez_compressed(
            path,
            key=np.asarray([key]),
            ts_ms=prep.ts_ms,
            z=prep.z,
            spread=prep.spread,
            day_ord=prep.day_ord,
            session=prep.session,
            trade_dates=td,
            edge_i=prep.edge_i,
        )
    except Exception:
        pass


def _load_prep_disk(csv_name: str, key: str) -> PreparedTips | None:
    path = _prep_disk_path(csv_name)
    if not path.is_file():
        return None
    try:
        data = np.load(path, allow_pickle=False)
        if str(data["key"][0]) != key:
            return None
        td = [str(x) for x in data["trade_dates"].tolist()]
        return PreparedTips(
            ts_ms=np.asarray(data["ts_ms"], dtype=np.int64),
            z=np.asarray(data["z"], dtype=np.float64),
            spread=np.asarray(data["spread"], dtype=np.float64),
            day_ord=np.asarray(data["day_ord"], dtype=np.int32),
            session=np.asarray(data["session"], dtype=np.bool_),
            trade_dates=td,
            edge_i=np.asarray(data["edge_i"], dtype=np.int32),
            n=int(len(data["ts_ms"])),
        )
    except Exception:
        return None


def _light_tip_key(csv_name: str, m15: list[dict]) -> str:
    """Key without loading 1m: csv + M15 window + parquet mtime."""
    mtime = _parquet_mtime()
    first = m15[0]["tradeDate"] if m15 else ""
    last = m15[-1]["tradeDate"] if m15 else ""
    return (
        f"v{_PREP_CACHE_VERSION}|{Path(csv_name).name}|{len(m15)}|{first}|{last}|{mtime:.0f}"
    )


def ensure_tip_series(csv_name: str) -> tuple[PreparedTips, dict[str, Any]]:
    """Build or reuse prepared tip series for CSV lookback (mem → disk → build)."""
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
            meta["cacheTier"] = "mem"
            return _tip_cache["prep"], meta

    # Single-flight build so concurrent /sim + /bars1m don't double-work.
    with _tip_build_lock:
        mtime = _parquet_mtime()
        with _lock:
            if (
                _tip_cache.get("csv") == name
                and _tip_cache.get("mtime") == mtime
                and _tip_cache["prep"] is not None
            ):
                meta = dict(_tip_cache["meta"] or {})
                meta["cacheHit"] = True
                meta["cacheTier"] = "mem"
                return _tip_cache["prep"], meta

        m15, src = load_m15_ui(name)
        key = _light_tip_key(name, m15)

        with _lock:
            if _tip_cache["key"] == key and _tip_cache["prep"] is not None:
                meta = dict(_tip_cache["meta"] or {})
                meta["cacheHit"] = True
                meta["cacheTier"] = "mem"
                _tip_cache["csv"] = name
                _tip_cache["mtime"] = mtime
                return _tip_cache["prep"], meta

        disk = _load_prep_disk(name, key)
        if disk is not None:
            meta = {
                "csv": name,
                "dataSourceM15": src,
                "dataSource1m": CACHE_1M.name,
                "m15Bars": len(m15),
                "m1Rows": disk.n,
                "tipPoints": disk.n,
                "edgeCount": int(len(disk.edge_i)),
                "m15From": m15[0]["tradeDate"] if m15 else None,
                "m15To": m15[-1]["tradeDate"] if m15 else None,
                "m1From": disk.trade_dates[0] if disk.n else None,
                "m1To": disk.trade_dates[-1] if disk.n else None,
                "buildSec": 0.0,
                "cacheHit": True,
                "cacheTier": "disk",
                "mode": "tip1m",
                "logicRu": "касание порога на 1м tip-Z (не ждём close M15)",
            }
            with _lock:
                _tip_cache["key"] = key
                _tip_cache["csv"] = name
                _tip_cache["mtime"] = mtime
                _tip_cache["prep"] = disk
                _tip_cache["built_at"] = time.time()
                _tip_cache["meta"] = meta
            return disk, meta

        end_dt = _parse_td(m15[-1]["tradeDate"])
        start_dt = _parse_td(m15[0]["tradeDate"]) - timedelta(days=40)
        # Do not block tip rebuild on ISS (can hang minutes). Kick background
        # extend when parquet lags M15/wall — nothing else was filling the cache.
        try:
            cached = _read_parquet_cached()
            if cached is not None and not cached.empty:
                cmax = _naive_ts(cached["timestamp"].max())
                end_naive = end_dt.replace(tzinfo=None) if end_dt.tzinfo else end_dt
                lag_min = (end_naive - cmax).total_seconds() / 60.0
                if lag_min >= 45.0:
                    _kick_extend_1m_background(until=end_dt)
        except Exception:
            pass
        m1 = load_1m_from_cache(start_dt, end_dt, extend=False)

        t0 = time.time()
        prep = build_prepared_tips(m15, m1)
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
            "cacheTier": "build",
            "mode": "tip1m",
            "logicRu": "касание порога на 1м tip-Z (не ждём close M15)",
        }
        _save_prep_disk(name, key, prep)
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


def _edges_tip1m_settled(
    prep: PreparedTips,
    edges: np.ndarray,
    *,
    now_ms: int | None = None,
    settle_sec: float | None = None,
) -> np.ndarray:
    """Drop edges whose cur tip is still forming (Prod tip1m settle gate).

    Same helper as live: ``is_tip1m_settled`` / ``MONITOR_TIP1M_SETTLE_SEC``.
    Historical tips with a next bar stay actionable; only the live tip waits.
    """
    if edges is None or len(edges) == 0:
        return edges if edges is not None else np.asarray([], dtype=np.int32)
    if now_ms is None:
        now_ms = int(time.time() * 1000)
    from live.constants import MONITOR_TIP1M_SETTLE_SEC
    from live.tip_touch_signals import is_tip1m_settled

    settle = MONITOR_TIP1M_SETTLE_SEC if settle_sec is None else float(settle_sec)
    n = int(prep.n)
    keep: list[int] = []
    for i in edges:
        i = int(i)
        if is_tip1m_settled(
            int(prep.ts_ms[i]),
            int(now_ms),
            has_next_bar=(i + 1 < n),
            settle_sec=settle,
        ):
            keep.append(i)
    if len(keep) == len(edges):
        return edges
    return np.asarray(keep, dtype=np.int32)


def _norm_bar_ts(ts: str) -> str:
    s = str(ts or "").replace("T", " ").strip()
    if len(s) == 16:
        s += ":00"
    return s[:19]


def _tip_index_at(prep: PreparedTips, bar_ts: str) -> int | None:
    """Nearest tip index at/after bar_ts (same minute preferred)."""
    s = _norm_bar_ts(bar_ts)
    if len(s) < 16 or prep.n < 1:
        return None
    try:
        ms = int(_parse_td(s).timestamp() * 1000)
    except ValueError:
        return None
    i = int(np.searchsorted(prep.ts_ms, ms, side="left"))
    if i >= prep.n:
        i = prep.n - 1
    # Prefer exact minute match within ±2m
    best = i
    best_abs = abs(int(prep.ts_ms[i]) - ms)
    for j in (i - 1, i, i + 1):
        if 0 <= j < prep.n:
            d = abs(int(prep.ts_ms[j]) - ms)
            if d < best_abs:
                best_abs = d
                best = j
    if best_abs > 120_000:
        return None
    return best if best >= 1 else (1 if prep.n > 1 else None)


def _fmeta(v: Any) -> float | None:
    if v is None:
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _entry_fill_meta_from_trade(t: dict[str, Any]) -> dict[str, Any] | None:
    """Prod entry fill (+ notional/lots) for tip path Min/Max / S%вх overlay."""
    direction = str(t.get("direction") or "").upper()
    et = _norm_bar_ts(str(t.get("entry_time") or ""))
    entry_sp = _fmeta(t.get("entry_spread"))
    if not et or entry_sp is None:
        return None
    meta: dict[str, Any] = {
        "entry_spread": entry_sp,
        "prod_id": t.get("id"),
    }
    nom = _fmeta(t.get("execution_notional_rub"))
    if nom is None:
        nom = _fmeta(t.get("notional_rub"))
    if nom is not None and nom > 0:
        meta["execution_notional_rub"] = nom
    lots = t.get("quantity_lots")
    try:
        if lots is not None and int(lots) > 0:
            meta["quantity_lots"] = int(lots)
    except (TypeError, ValueError):
        pass
    for k in ("entry_tatn", "entry_tatnp"):
        v = _fmeta(t.get(k))
        if v is not None and v > 0:
            meta[k] = v
    meta["_enter_sig"] = "ENTER_LONG" if direction.startswith("L") else "ENTER_SHORT"
    meta["_entry_ts"] = et[:16]
    return meta


def _closed_fill_maps(closed: list[dict[str, Any]]) -> dict[tuple[str, str], dict[str, Any]]:
    """Minute+signal → Prod fill / account Δ for «как Прод» Чист."""
    try:
        from live.closed_metrics import attach_account_deltas

        attach_account_deltas(closed)
    except Exception:
        pass

    out: dict[tuple[str, str], dict[str, Any]] = {}
    for t in closed:
        direction = str(t.get("direction") or "").upper()
        et = _norm_bar_ts(str(t.get("entry_time") or ""))
        xt = _norm_bar_ts(str(t.get("exit_time") or ""))
        enter_sig = "ENTER_LONG" if direction == "LONG" else "ENTER_SHORT"
        exit_sig = "EXIT_LONG" if direction == "LONG" else "EXIT_SHORT"
        entry_sp = _fmeta(t.get("entry_spread"))
        exit_sp = _fmeta(t.get("exit_spread"))
        delta = _fmeta(t.get("account_delta_rub"))
        model_net = _fmeta(t.get("spread_pnl_rub"))
        if model_net is None:
            # after attach_account_deltas pnl_rub may already be Δ счёта
            model_net = _fmeta(t.get("pnl_rub")) if delta is None else None
        gross = _fmeta(t.get("gross_rub"))
        comm = _fmeta(t.get("commission_rub"))
        ovn = _fmeta(t.get("overnight_rub"))
        before = _fmeta(t.get("account_before_rub"))
        after = _fmeta(t.get("account_after_rub"))
        tid = t.get("id")
        em = _entry_fill_meta_from_trade(t)
        if em is not None:
            key_ts = str(em.pop("_entry_ts"))
            key_sig = str(em.pop("_enter_sig"))
            out[(key_ts, key_sig)] = em
        elif et and entry_sp is not None:
            out[(et[:16], enter_sig)] = {
                "entry_spread": entry_sp,
                "prod_id": tid,
            }
        if xt:
            meta: dict[str, Any] = {"prod_id": tid}
            if exit_sp is not None:
                meta["exit_spread"] = exit_sp
            if entry_sp is not None:
                meta["entry_spread"] = entry_sp
            if delta is not None:
                meta["account_delta_rub"] = delta
            if before is not None:
                meta["account_before_rub"] = before
            if after is not None:
                meta["account_after_rub"] = after
            if model_net is not None:
                meta["model_net_rub"] = model_net
            if gross is not None:
                meta["gross_rub"] = gross
            if comm is not None:
                meta["commission_rub"] = comm
            if ovn is not None:
                meta["overnight_rub"] = ovn
            out[(xt[:16], exit_sig)] = meta
    return out


def _prod_fill_maps() -> dict[tuple[str, str], dict[str, Any]]:
    """Closed + open Prod fills (open ENTER needed for Min/Max while Long/Short still open)."""
    from live import store as live_store

    try:
        closed = live_store.get_closed_trades(limit=200)
    except Exception:
        closed = []
    out = _closed_fill_maps(list(closed or []))
    try:
        open_t = live_store.get_open_trade()
    except Exception:
        open_t = None
    if isinstance(open_t, dict):
        em = _entry_fill_meta_from_trade(open_t)
        if em is not None:
            key_ts = str(em.pop("_entry_ts"))
            key_sig = str(em.pop("_enter_sig"))
            # Open fill wins over a stale closed row at the same minute.
            out[(key_ts, key_sig)] = em
    return out


def build_as_live_tip_actions(
    prep: PreparedTips,
    *,
    window_start_ms: int = 0,
) -> list[tuple[int, int] | tuple[int, int, dict[str, Any]]]:
    """Prod-authoritative tip actions for «как Прод».

    Sources: decision_bars non-NONE + live_closed_trades enter/exit + open trade.
    If Prod ENTER arrives while sim still in a position (missing EXIT freeze),
    force-close first so Monday 07:03 ENTER is not swallowed by a Friday phantom.
    Signals: 1 Long enter, 2 Short enter, 3 Long exit, 4 Short exit.

    Optional 3rd tuple element: Prod fill meta (spreads + account_delta_rub)
    so Testing «Чист.» = Δ счёта, not optimistic tip±slip.
    """
    from live import store as live_store

    raw: list[tuple[str, str, int]] = []  # (ts, signal, priority)

    for d in live_store.get_decision_bars():
        sig = str(d.get("signal") or "").upper()
        if sig in ("", "NONE"):
            continue
        ts = _norm_bar_ts(str(d.get("bar_ts") or ""))
        if not ts:
            continue
        raw.append((ts, sig, 1))

    # Closed Prod trades fill missing EXIT/ENTER freezes (e.g. Sat 18:45 broker flat).
    try:
        closed = live_store.get_closed_trades(limit=200)
    except Exception:
        closed = []
    fill_map = _prod_fill_maps()
    for t in closed or []:
        direction = str(t.get("direction") or "").upper()
        et = _norm_bar_ts(str(t.get("entry_time") or ""))
        xt = _norm_bar_ts(str(t.get("exit_time") or ""))
        if et:
            raw.append(
                (et, "ENTER_LONG" if direction == "LONG" else "ENTER_SHORT", 0)
            )
        if xt:
            raw.append(
                (xt, "EXIT_LONG" if direction == "LONG" else "EXIT_SHORT", 0)
            )

    # Still-open Prod trade: ensure ENTER edge exists even if decision_bars lagged.
    try:
        open_t = live_store.get_open_trade()
    except Exception:
        open_t = None
    if isinstance(open_t, dict):
        direction = str(open_t.get("direction") or "").upper()
        et = _norm_bar_ts(str(open_t.get("entry_time") or ""))
        if et and direction.startswith(("L", "S")):
            raw.append(
                (et, "ENTER_LONG" if direction.startswith("L") else "ENTER_SHORT", 0)
            )

    # Dedupe by (ts, signal); prefer decision_bars (priority 1).
    by_key: dict[tuple[str, str], tuple[str, str, int]] = {}
    for ts, sig, pri in raw:
        key = (ts[:16], sig)  # minute + signal
        prev = by_key.get(key)
        if prev is None or pri >= prev[2]:
            by_key[key] = (ts, sig, pri)

    ordered = sorted(by_key.values(), key=lambda x: (x[0], -x[2]))
    actions: list[tuple[int, int] | tuple[int, int, dict[str, Any]]] = []
    pos = 0  # 0 flat 1 long 2 short

    sig_map = {
        "ENTER_LONG": 1,
        "ENTER_SHORT": 2,
        "EXIT_LONG": 3,
        "EXIT_SHORT": 4,
    }

    def _emit(idx: int, code: int, sig_name: str, ts: str) -> None:
        meta = fill_map.get((ts[:16], sig_name))
        if meta:
            actions.append((idx, code, meta))
        else:
            actions.append((idx, code))

    for ts, sig, _pri in ordered:
        try:
            ms = int(_parse_td(ts).timestamp() * 1000)
        except ValueError:
            continue
        if window_start_ms > 0 and ms < window_start_ms:
            continue
        idx = _tip_index_at(prep, ts)
        if idx is None:
            continue
        code = sig_map.get(sig)
        if code is None:
            continue

        if code in (1, 2):
            if pos in (1, 2):
                # Missing Prod EXIT before next ENTER — force flat at this bar.
                force_sig = "EXIT_LONG" if pos == 1 else "EXIT_SHORT"
                _emit(idx, 3 if pos == 1 else 4, force_sig, ts)
                pos = 0
            _emit(idx, code, sig, ts)
            pos = code
        elif code == 3:
            if pos != 1:
                continue
            _emit(idx, 3, sig, ts)
            pos = 0
        elif code == 4:
            if pos != 2:
                continue
            _emit(idx, 4, sig, ts)
            pos = 0

    return actions


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
    max_hold_days_if_losing: float = 0.0,
    max_hold_days_no_exit_trend: float = 0.0,
    exit_trend_min_pp: float = 0.2,
    as_live_actions: list[tuple[int, int] | tuple[int, int, dict[str, Any]]] | None = None,
    regime_z_mode: bool = False,
    spread_level_mode: bool = False,
    spread_levels: dict[str, float] | None = None,
    now_ms: int | None = None,
    settle_sec: float | None = None,
) -> dict[str, Any]:
    """Mode B with optional TP%; returns trades + summary for Testing UI.

    ``spread_level_mode``: absolute spread-% levels (primary Prod AUTO) — no Z.
    ``regime_z_mode``: legacy spread-regime Z (узкий ±1.0/±0.7, …).
    Classic ``entry``/``exit_z`` when both off.
    ``max_hold_days_if_losing``: calendar days in trade; if still MTM&lt;0 → close
    (geometric path only; reason ``hold_losing``).
    ``max_hold_days_no_exit_trend``: after N calendar days, if MTM&lt;0 and S has not
    moved toward the spread exit level by ``exit_trend_min_pp`` → close
    (reason ``hold_no_trend``). Long: toward ``exit_narrow``; Short: toward ``exit_wide``.

    Geometric path applies Prod tip1m settle (``MONITOR_TIP1M_SETTLE_SEC``) so the
    forming last tip is not actionable until close+settle (or next tip present).
    """
    from live.constants import MONITOR_TIP1M_SETTLE_SEC
    from live.signals import Position as _Pos
    from live.spread_levels import (
        SpreadLevels,
        determine_spread_level_signal,
        levels_from_settings,
    )
    from live.spread_regime import (
        classify_spread_pct,
        resolve_thresholds,
        z_for_regime,
    )

    use_spread = bool(spread_level_mode)
    lv = levels_from_settings(spread_levels) if spread_levels else SpreadLevels()
    use_regime = bool(regime_z_mode) and not use_spread
    settle = MONITOR_TIP1M_SETTLE_SEC if settle_sec is None else float(settle_sec)

    z = prep.z
    sp_arr = prep.spread
    day_ord = prep.day_ord
    dates = prep.trade_dates
    edges = _edges_from(prep, window_start_ms)
    if as_live_actions is None:
        edges = _edges_tip1m_settled(
            prep, edges, now_ms=now_ms, settle_sec=settle
        )

    pos = 0  # 0 flat 1 long 2 short
    entry_sp = 0.0
    entry_td = ""
    entry_z = 0.0
    entry_day = 0
    entry_comm = 0.0
    entry_slip_used = float(slip)
    open_meta: dict[str, Any] | None = None
    locked_exit = float(exit_z)
    open_lock: dict[str, Any] | None = None
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
    hold_lose_days = int(max_hold_days_if_losing) if max_hold_days_if_losing > 0 else 0
    hold_no_trend_days = (
        int(max_hold_days_no_exit_trend) if max_hold_days_no_exit_trend > 0 else 0
    )
    trend_min_pp = max(0.0, float(exit_trend_min_pp))
    # Prod fill overlay (same trade minute) — geometric Testing + as_live.
    try:
        prod_fills = _prod_fill_maps()
    except Exception:
        prod_fills = {}

    def _mtm_at(i: int) -> float:
        is_long = pos == 1
        sp = sp_arr[i]
        pnl_pts = (sp - entry_sp) if is_long else (entry_sp - sp)
        gross = eff * (pnl_pts / 100.0)
        ovn = ovn_day * max(0, int(day_ord[i]) - entry_day)
        return gross - entry_comm - ovn

    def _lookup_prod_enter_meta(sig: int, i: int) -> dict[str, Any] | None:
        sig_name = "ENTER_LONG" if sig == 1 else "ENTER_SHORT"
        td = str(dates[i])[:16]
        m = prod_fills.get((td, sig_name))
        return dict(m) if isinstance(m, dict) else None

    def _open(sig: int, i: int, meta: dict[str, Any] | None = None) -> None:
        nonlocal pos, entry_sp, entry_td, entry_z, entry_day, entry_comm, eff, comm, ovn_day
        nonlocal pos_notional, trade_no, pnl_min, pnl_max, hit1_td, hit2_td, hit3_td
        nonlocal entry_slip_used, open_meta, locked_exit, open_lock
        if compound:
            pos_notional = max(1.0, base + realized)
        else:
            pos_notional = base
        merged_meta: dict[str, Any] = {}
        looked = _lookup_prod_enter_meta(sig, i)
        if looked:
            merged_meta.update(looked)
        if meta:
            merged_meta.update(meta)
        tip_sp = float(sp_arr[i])
        fill = _fmeta(merged_meta.get("entry_spread"))
        eff_nom = _fmeta(merged_meta.get("execution_notional_rub"))
        lots_m = merged_meta.get("quantity_lots")
        try:
            lots_v: int | float | None = int(lots_m) if lots_m is not None else None
        except (TypeError, ValueError):
            lots_v = None
        direction = "LONG" if sig == 1 else "SHORT"
        eff, comm, ovn_day = _fees(
            pos_notional,
            direction=direction,
            lots=lots_v,
            fill_tatn=_fmeta(merged_meta.get("entry_tatn")),
            fill_tatnp=_fmeta(merged_meta.get("entry_tatnp")),
            execution_notional_rub=eff_nom,
        )
        entry_comm = comm
        if fill is not None:
            entry_sp = fill
            # Adverse slip vs tip mid (display); keep UI slip column separate from Чист.
            if sig == 1:
                entry_slip_used = max(0.0, entry_sp - tip_sp)
            else:
                entry_slip_used = max(0.0, tip_sp - entry_sp)
        else:
            entry_sp = tip_sp + slip if sig == 1 else tip_sp - slip
            entry_slip_used = float(slip)
        open_meta = merged_meta or None
        entry_td = dates[i]
        entry_z = float(z[i])
        entry_day = int(day_ord[i])
        pos = 1 if sig == 1 else 2
        if use_regime:
            reg = classify_spread_pct(tip_sp)
            pair = z_for_regime(reg)
            if pair:
                locked_exit = pair[1]
                open_lock = {
                    "entry_regime": reg,
                    "locked_entry_z": pair[0],
                    "locked_exit_z": pair[1],
                    "entry_spread": tip_sp,
                }
            else:
                locked_exit = float(exit_z)
                open_lock = {
                    "entry_regime": reg,
                    "locked_entry_z": entry,
                    "locked_exit_z": exit_z,
                    "entry_spread": tip_sp,
                }
        else:
            locked_exit = float(exit_z)
            open_lock = (
                {
                    "entry_regime": classify_spread_pct(tip_sp),
                    "entry_spread": tip_sp,
                }
                if use_spread
                else None
            )
        trade_no += 1
        mtm = _mtm_at(i)
        pnl_min = mtm
        pnl_max = mtm
        hit1_td = hit2_td = hit3_td = None

    def _close(i: int, reason: str, meta: dict[str, Any] | None = None) -> None:
        nonlocal pos, realized, total_pnl, peak, max_dd, pnl_min, pnl_max
        nonlocal hit1_td, hit2_td, hit3_td, open_meta, open_lock
        is_long = pos == 1
        tip_sp = float(sp_arr[i])
        merged: dict[str, Any] = {}
        if open_meta:
            merged.update(open_meta)
        if meta:
            merged.update(meta)
        fill_exit = _fmeta(merged.get("exit_spread"))
        if fill_exit is not None:
            exit_sp = fill_exit
        else:
            exit_sp = tip_sp - slip if is_long else tip_sp + slip
        fill_entry = _fmeta(merged.get("entry_spread"))
        use_entry = fill_entry if fill_entry is not None else entry_sp
        pnl_pts = (exit_sp - use_entry) if is_long else (use_entry - exit_sp)
        model_gross = _fmeta(merged.get("gross_rub"))
        if model_gross is None:
            model_gross = eff * (pnl_pts / 100.0)
        ovn_model = ovn_day * max(0, int(day_ord[i]) - entry_day)
        ovn = _fmeta(merged.get("overnight_rub"))
        if ovn is None:
            ovn = ovn_model
        comm_total = _fmeta(merged.get("commission_rub"))
        if comm_total is None:
            comm_total = entry_comm + comm
        model_net = _fmeta(merged.get("model_net_rub"))
        if model_net is None:
            model_net = float(model_gross) - float(comm_total) - float(ovn)
        account_delta = _fmeta(merged.get("account_delta_rub"))
        # Чист. = Δ счёта Prod (как History), иначе model net after fees.
        if account_delta is not None:
            net = float(account_delta)
            net_from_account = True
        else:
            net = float(model_net)
            net_from_account = False
        total_pnl += net
        realized += net
        if total_pnl > peak:
            peak = total_pnl
        max_dd = max(max_dd, peak - total_pnl)
        # Path min/max stay on tip MTM; clamp with close net for display sanity.
        pmin = net if not math.isfinite(pnl_min) else min(pnl_min, net)
        pmax = net if not math.isfinite(pnl_max) else max(pnl_max, net)
        acc_before = _fmeta(merged.get("account_before_rub"))
        acc_after = _fmeta(merged.get("account_after_rub"))
        sim_after = round(base + realized, 2)
        if acc_after is None and net_from_account and acc_before is not None:
            acc_after = float(acc_before) + float(net)
        if acc_after is None:
            acc_after = sim_after
        # «До»: снимок Prod на входе, иначе после − чист. (кривая теста / Δ счёта)
        if acc_before is None and acc_after is not None:
            try:
                acc_before = float(acc_after) - float(net)
            except (TypeError, ValueError):
                acc_before = None
        closed_trades.append(
            {
                "index": trade_no,
                "direction": "Long" if is_long else "Short",
                "entryDate": entry_td,
                "exitDate": dates[i],
                "entryZ": round(entry_z, 4),
                "exitZ": round(float(z[i]), 4),
                "entrySpread": round(use_entry, 6),
                "exitSpread": round(exit_sp, 6),
                "entrySlip": round(entry_slip_used, 6),
                "pnlPts": round(pnl_pts, 6),
                "gross": round(float(model_gross), 4),
                "commission": round(float(comm_total), 4),
                "overnight": round(float(ovn), 4),
                "net": round(net, 4),
                "modelNet": round(float(model_net), 4),
                "netFromAccount": net_from_account,
                "pnlMin": round(pmin, 4),
                "pnlMax": round(pmax, 4),
                "hit1Date": hit1_td,
                "hit2Date": hit2_td,
                "hit3Date": hit3_td,
                "accountBefore": round(float(acc_before), 2) if acc_before is not None else None,
                "accountAfter": round(float(acc_after), 2) if acc_after is not None else None,
                "status": "Закрыта",
                "exitReason": reason,
                "notional": round(pos_notional, 2),
                "prodId": merged.get("prod_id"),
            }
        )
        pos = 0
        open_meta = None
        open_lock = None
        locked_exit = float(exit_z)
        pnl_min = float("inf")
        pnl_max = float("-inf")
        hit1_td = hit2_td = hit3_td = None

    def _unpack_action(act: tuple[int, int] | tuple[int, int, dict[str, Any]]) -> tuple[int, int, dict[str, Any] | None]:
        if len(act) >= 3:
            return int(act[0]), int(act[1]), act[2] if isinstance(act[2], dict) else None
        return int(act[0]), int(act[1]), None

    if as_live_actions is not None:
        for act in as_live_actions:
            i, sig, act_meta = _unpack_action(act)
            if i < 1 or i >= prep.n:
                continue
            if window_start_ms > 0 and int(prep.ts_ms[i]) < window_start_ms:
                continue
            if pos in (1, 2) and use_tp:
                mtm = _mtm_at(i)
                if mtm < pnl_min:
                    pnl_min = mtm
                if mtm > pnl_max:
                    pnl_max = mtm
                pct = (mtm / max(1.0, pos_notional)) * 100.0
                td = dates[i]
                if hit1_td is None and pct >= 1.0:
                    hit1_td = td
                if hit2_td is None and pct >= 2.0:
                    hit2_td = td
                if hit3_td is None and pct >= 3.0:
                    hit3_td = td
                if pct >= take_profit_pct:
                    _close(i, "tp")
                    if sig in (1, 2):
                        _open(sig, i, act_meta)
                    continue
            if sig in (1, 2):
                if pos != 0:
                    _close(i, "as_live_force_flat")
                _open(sig, i, act_meta)
            elif sig == 3 and pos == 1:
                _close(i, "as_live_exit", act_meta)
            elif sig == 4 and pos == 2:
                _close(i, "as_live_exit", act_meta)
    else:
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
                if hold_lose_days > 0:
                    held = int(day_ord[i]) - entry_day
                    if held >= hold_lose_days and mtm < 0:
                        _close(i, "hold_losing")
                        continue
                if hold_no_trend_days > 0:
                    held = int(day_ord[i]) - entry_day
                    if held >= hold_no_trend_days and mtm < 0:
                        cur_sp_h = float(sp_arr[i])
                        if pos == 1:
                            # Long: exit when S rises to exit_narrow
                            toward = (cur_sp_h - entry_sp) >= trend_min_pp
                        else:
                            # Short: exit when S falls to exit_wide
                            toward = (entry_sp - cur_sp_h) >= trend_min_pp
                        if use_spread and not toward:
                            # Also accept being nearer the exit level than at entry
                            if pos == 1:
                                exit_lv = float(lv.exit_narrow)
                                toward = (exit_lv - cur_sp_h) <= (
                                    exit_lv - entry_sp
                                ) - trend_min_pp
                            else:
                                exit_lv = float(lv.exit_wide)
                                toward = (cur_sp_h - exit_lv) <= (
                                    entry_sp - exit_lv
                                ) - trend_min_pp
                        if not toward:
                            _close(i, "hold_no_trend")
                            continue

            sig = 0
            use_entry = float(entry)
            use_exit = float(exit_z)
            prev_sp = float(sp_arr[i - 1])
            cur_sp = float(sp_arr[i])
            if use_spread:
                pos_enum = (
                    _Pos.LONG if pos == 1 else _Pos.SHORT if pos == 2 else _Pos.FLAT
                )
                s = determine_spread_level_signal(prev_sp, cur_sp, pos_enum, lv)
                if s.value == "ENTER_LONG":
                    sig = 1
                elif s.value == "ENTER_SHORT":
                    sig = 2
                elif s.value == "EXIT_LONG":
                    sig = 3
                elif s.value == "EXIT_SHORT":
                    sig = 4
            elif use_regime:
                pos_enum = (
                    _Pos.LONG if pos == 1 else _Pos.SHORT if pos == 2 else _Pos.FLAT
                )
                th = resolve_thresholds(
                    regime_z_mode=True,
                    classic_entry=entry,
                    classic_exit=exit_z,
                    spread=cur_sp,
                    position=pos_enum,
                    open_trade=open_lock,
                )
                use_entry, use_exit = th.entry, th.exit
                if pos == 0 and not th.allow_entry:
                    continue
                if pos == 0:
                    if prev_z > -use_entry and cur_z <= -use_entry:
                        sig = 1
                    elif prev_z < use_entry and cur_z >= use_entry:
                        sig = 2
                elif pos == 1:
                    x = locked_exit
                    if prev_z < -x and cur_z >= -x:
                        sig = 3
                elif pos == 2:
                    x = locked_exit
                    if prev_z > x and cur_z <= x:
                        sig = 4
            else:
                if pos == 0:
                    if prev_z > -use_entry and cur_z <= -use_entry:
                        sig = 1
                    elif prev_z < use_entry and cur_z >= use_entry:
                        sig = 2
                elif pos == 1:
                    if prev_z < -use_exit and cur_z >= -use_exit:
                        sig = 3
                elif pos == 2:
                    if prev_z > use_exit and cur_z <= use_exit:
                        sig = 4
            if not sig:
                continue

            if sig in (1, 2):
                _open(sig, i)
            else:
                _close(i, "spread_exit" if use_spread else "z_exit")

    wins = sum(1 for t in closed_trades if t["net"] > 0)
    closed_n = len(closed_trades)
    trades_out = list(closed_trades)
    # Still-open position must appear in trades (M15 local sim does); otherwise
    # Testing СДЕЛКИ hides today's Long while summary.openCount=1.
    if pos in (1, 2) and entry_td:
        last_i = prep.n - 1
        mtm = _mtm_at(last_i) if last_i >= 0 else 0.0
        pmin = mtm if not math.isfinite(pnl_min) else min(pnl_min, mtm)
        pmax = mtm if not math.isfinite(pnl_max) else max(pnl_max, mtm)
        trades_out.append(
            {
                "index": trade_no,
                "direction": "Long" if pos == 1 else "Short",
                "entryDate": entry_td,
                "exitDate": "—",
                "entryZ": round(entry_z, 4),
                "exitZ": round(float(z[last_i]), 4) if last_i >= 0 else None,
                "entrySpread": round(entry_sp, 6),
                "exitSpread": None,
                "entrySlip": round(entry_slip_used, 6),
                "pnlPts": None,
                "gross": None,
                "commission": None,
                "overnight": None,
                "net": None,
                "modelNet": None,
                "netFromAccount": False,
                "pnlMin": round(pmin, 4),
                "pnlMax": round(pmax, 4),
                "hit1Date": hit1_td,
                "hit2Date": hit2_td,
                "hit3Date": hit3_td,
                "accountAfter": None,
                "status": "Открыта",
                "exitReason": None,
                "notional": round(pos_notional, 2),
                "prodId": (open_meta or {}).get("prod_id") if open_meta else None,
            }
        )
    return {
        "trades": trades_out,
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
            "maxHoldDaysIfLosing": hold_lose_days,
            "maxHoldDaysNoExitTrend": hold_no_trend_days,
            "exitTrendMinPp": trend_min_pp,
            "leverage": LEVERAGE,
            "commissionPctPerSide": COMM_PCT,
            "overnightFeeModel": OVERNIGHT_FEE_MODEL,
            "overnightFeeNoteRu": "Премиум: ступени ₽/день на короткой ноге (lots×цена; иначе ≈eff/2)",
            "asLive": as_live_actions is not None,
            "regimeZMode": bool(use_regime),
            "spreadLevelMode": bool(use_spread),
            "spreadLevels": lv.as_dict() if use_spread else None,
            "tip1mSettleSec": float(settle) if as_live_actions is None else None,
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
    """Fast path for heatmap: (total_pnl, closed_count) — no trade list / MAE.

    Supports TP% inline (same MTM rule as ``run_touch_1m_trades``) so the E×X
    grid does not allocate per-trade dicts on every cell. Prefer numba kernel
    when available (~100× on dense tip series).
    """
    if edges is None:
        edges = _edges_tip1m_settled(prep, _edges_from(prep, window_start_ms))
    kernel = _get_pnl_numba_kernel()
    if kernel is not None:
        try:
            total, closed = kernel(
                prep.z,
                prep.spread,
                prep.day_ord,
                np.asarray(edges, dtype=np.int64),
                float(entry),
                float(exit_z),
                float(slip),
                float(notional),
                bool(compound),
                float(take_profit_pct),
            )
            return float(total), int(closed)
        except Exception:
            pass

    z = prep.z
    sp_arr = prep.spread
    day_ord = prep.day_ord

    pos = 0
    entry_sp = 0.0
    entry_day = 0
    entry_comm = 0.0
    pos_notional = 0.0
    eff = comm = ovn_day = 0.0
    base = float(notional)
    realized = 0.0
    closed = 0
    total_pnl = 0.0
    neg_entry = -entry
    neg_exit = -exit_z
    use_tp = take_profit_pct > 0

    def _close_at(i: int, is_long: bool) -> None:
        nonlocal pos, realized, total_pnl, closed
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

    for i in edges:
        i = int(i)
        prev_z = z[i - 1]
        cur_z = z[i]

        if pos != 0:
            if use_tp:
                # MTM without exit-slip / exit-comm (parity with _mtm_at).
                sp = sp_arr[i]
                is_long = pos == 1
                pnl_pts = (sp - entry_sp) if is_long else (entry_sp - sp)
                mtm = eff * (pnl_pts / 100.0) - entry_comm - (
                    ovn_day * max(0, int(day_ord[i]) - entry_day)
                )
                if (mtm / max(1.0, pos_notional)) * 100.0 >= take_profit_pct:
                    _close_at(i, is_long)
                    continue
            if pos == 1:
                if prev_z < neg_exit and cur_z >= neg_exit:
                    _close_at(i, True)
                continue
            if prev_z > exit_z and cur_z <= exit_z:
                _close_at(i, False)
            continue

        if prev_z > neg_entry and cur_z <= neg_entry:
            sig = 1
        elif prev_z < entry and cur_z >= entry:
            sig = 2
        else:
            continue
        pos_notional = max(1.0, base + realized) if compound else base
        eff, comm, ovn_day = _fees(pos_notional)
        entry_comm = comm
        sp = sp_arr[i]
        entry_sp = sp + slip if sig == 1 else sp - slip
        entry_day = int(day_ord[i])
        pos = sig

    return total_pnl, closed


def run_touch_1m_spread_pnl_only(
    prep: PreparedTips,
    *,
    enter_wide: float,
    exit_wide: float,
    enter_narrow: float,
    exit_narrow: float,
    window_start_ms: int = 0,
    compound: bool = False,
    slip: float = DEFAULT_SLIP,
    notional: float = DEFAULT_NOTIONAL,
    take_profit_pct: float = 0.0,
    edges: np.ndarray | None = None,
) -> tuple[float, int]:
    """Fast heatmap path for spread-level mode (absolute S%, no Z).

    Same rules as ``run_touch_1m_trades(..., spread_level_mode=True)`` + optional TP,
    without allocating trade dicts. Caller should share ``prep`` / ``edges`` across cells.
    """
    from live.signals import Position as _Pos
    from live.spread_levels import SpreadLevels, determine_spread_level_signal

    sp_arr = prep.spread
    day_ord = prep.day_ord
    if edges is None:
        edges = _edges_tip1m_settled(prep, _edges_from(prep, window_start_ms))
    lv = SpreadLevels(
        enter_wide=float(enter_wide),
        exit_wide=float(exit_wide),
        enter_narrow=float(enter_narrow),
        exit_narrow=float(exit_narrow),
    )

    pos = 0
    entry_sp = 0.0
    entry_day = 0
    entry_comm = 0.0
    pos_notional = 0.0
    eff = comm = ovn_day = 0.0
    base = float(notional)
    realized = 0.0
    closed = 0
    total_pnl = 0.0
    use_tp = take_profit_pct > 0

    def _close_at(i: int, is_long: bool) -> None:
        nonlocal pos, realized, total_pnl, closed
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

    for i in edges:
        i = int(i)
        prev_sp = float(sp_arr[i - 1])
        cur_sp = float(sp_arr[i])

        if pos != 0:
            if use_tp:
                is_long = pos == 1
                pnl_pts = (cur_sp - entry_sp) if is_long else (entry_sp - cur_sp)
                mtm = eff * (pnl_pts / 100.0) - entry_comm - (
                    ovn_day * max(0, int(day_ord[i]) - entry_day)
                )
                if (mtm / max(1.0, pos_notional)) * 100.0 >= take_profit_pct:
                    _close_at(i, is_long)
                    continue
            pos_enum = _Pos.LONG if pos == 1 else _Pos.SHORT
            s = determine_spread_level_signal(prev_sp, cur_sp, pos_enum, lv)
            if s.value == "EXIT_LONG":
                _close_at(i, True)
            elif s.value == "EXIT_SHORT":
                _close_at(i, False)
            continue

        s = determine_spread_level_signal(prev_sp, cur_sp, _Pos.FLAT, lv)
        if s.value == "ENTER_LONG":
            sig = 1
        elif s.value == "ENTER_SHORT":
            sig = 2
        else:
            continue
        pos_notional = max(1.0, base + realized) if compound else base
        eff, comm, ovn_day = _fees(pos_notional)
        entry_comm = comm
        entry_sp = cur_sp + slip if sig == 1 else cur_sp - slip
        entry_day = int(day_ord[i])
        pos = sig

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
    *,
    as_live: bool = False,
    replay_prod: bool = False,
    regime_z_mode: bool = False,
    spread_level_mode: bool = False,
) -> str:
    # v11: Prod open/closed fill overlay on entry (S%вх + Min/Max path = desk model).
    return (
        f"{tip_key}|v11|as={int(as_live)}|rp={int(replay_prod)}|"
        f"sl={int(spread_level_mode)}|rz={int(regime_z_mode)}|"
        f"e={entry:.4f}|x={exit_z:.4f}|s={slip:.4f}|"
        f"n={notional:.2f}|c={int(compound)}|tp={take_profit_pct:.4f}|start={start or ''}"
    )


def _fmt_ddmm(td: str | None) -> str:
    s = str(td or "").replace("T", " ").strip()
    if len(s) >= 10 and s[4] == "-" and s[7] == "-":
        return f"{s[8:10]}.{s[5:7]}"
    return s[:10] if s else "—"


def _enrich_as_live_sparse_meta(
    meta: dict[str, Any],
    trades: list[dict[str, Any]],
    *,
    use_replay: bool,
    start: str | None,
) -> dict[str, Any]:
    """Mark sparse Prod coverage vs selected start→now window (UI hint)."""
    out = dict(meta)
    closed = [t for t in (trades or []) if str(t.get("status") or "") != "Открыта"]
    n = len(closed)
    first = str(closed[0].get("entryDate") or "") if closed else ""
    last = str(closed[-1].get("entryDate") or closed[-1].get("exitDate") or "") if closed else ""
    out["tradesSpanFrom"] = first or None
    out["tradesSpanTo"] = last or None
    out["tradesClosed"] = n
    out["prodSparse"] = False
    out["prodSparseHintRu"] = None
    if not use_replay:
        return out

    window_days = 0
    start_s = str(start or "").strip()
    if len(start_s) >= 10:
        try:
            start_d = _parse_td(start_s[:10] + " 00:00:00").date()
            end_d = datetime.now(MSK).date()
            window_days = max(0, (end_d - start_d).days)
        except Exception:
            window_days = 0
    out["windowDays"] = window_days

    span_days = 0
    if first and last:
        try:
            a = _parse_td(first.replace("T", " ")[:19]).date()
            b = _parse_td(last.replace("T", " ")[:19]).date()
            span_days = max(0, (b - a).days)
        except Exception:
            span_days = 0
    out["tradesSpanDays"] = span_days

    # Sparse if Prod edges cover << selected window (typical: freeze from ~1 week).
    sparse = False
    if window_days >= 20 and (n == 0 or span_days < max(5, int(window_days * 0.45))):
        sparse = True
    elif window_days >= 6 and n > 0 and span_days <= 8 and window_days >= 25:
        sparse = True

    out["prodSparse"] = sparse
    from_lbl = _fmt_ddmm(first) if first else "—"
    if sparse:
        if n <= 0:
            out["prodSparseHintRu"] = (
                f"Prod: нет сделок в окне с {start_s[:10] or '—'} "
                f"(decision_bars/заморозка короче окна). "
                f"Для полного периода — «финальный CSV»."
            )
        else:
            out["prodSparseHintRu"] = (
                f"Prod: {n} сд. с {from_lbl} · заморозка/decision_bars; "
                f"окно {window_days}д >> покрытие. "
                f"Для полного месяца/геометрии — «финальный CSV»."
            )
    elif n > 0:
        out["prodSparseHintRu"] = (
            f"Prod: {n} сд. с {from_lbl} (decision_bars + закрытые)."
        )
    else:
        out["prodSparseHintRu"] = (
            "Prod: нет закрытых сделок в выбранном окне (decision_bars)."
        )
    return out


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
    as_live: bool = False,
    replay_prod: bool = False,
    regime_z_mode: bool = False,
    spread_level_mode: bool = False,
    spread_levels: dict[str, float] | None = None,
) -> dict[str, Any]:
    """Tip1m Testing sim.

    ``as_live`` / ``replay_prod``: Prod-authoritative edges from decision_bars +
    closed trades (parity with History) — respects ``start`` window filter.

    Off («финальный CSV»): geometric path over ``start``→end.

    ``spread_level_mode``: geometric absolute S levels (same as Prod AUTO).
    ``regime_z_mode``: legacy Z-by-regime (ignored when spread levels ON).
    ``spread_levels``: optional override of enter/exit wide+narrow (heatmap click).
    """
    prep, meta = ensure_tip_series(csv)
    tip_key = str(_tip_cache.get("key") or "")
    # «как Прод» must replay Prod actions (Test ↔ History). Geometric only when off.
    use_replay = bool(replay_prod) or bool(as_live)
    # Spread levels / regime only on geometric path (as_live ignores thresholds).
    use_spread = bool(spread_level_mode) and not use_replay
    use_regime = bool(regime_z_mode) and not use_replay and not use_spread
    sl_part = ""
    if use_spread and spread_levels:
        sl_part = (
            f"|lv={float(spread_levels.get('spread_enter_wide', spread_levels.get('enter_wide', 6.2))):.2f}"
            f"/{float(spread_levels.get('spread_exit_wide', spread_levels.get('exit_wide', 5.8))):.2f}"
            f"/{float(spread_levels.get('spread_enter_narrow', spread_levels.get('enter_narrow', 3.2))):.2f}"
            f"/{float(spread_levels.get('spread_exit_narrow', spread_levels.get('exit_narrow', 4.0))):.2f}"
        )
    skey = _sim_key(
        csv,
        entry,
        exit_z,
        slip,
        notional,
        compound,
        take_profit_pct,
        start,
        tip_key,
        as_live=as_live,
        replay_prod=use_replay,
        regime_z_mode=use_regime,
        spread_level_mode=use_spread,
    ) + sl_part
    note_geo = (
        "касание 1м: геометрический симулятор за выбранный период start→end "
        "(пороги Z/TP; источник «финальный CSV»; settle +10с как Prod tip1m AUTO)."
    )
    if use_spread:
        note_geo = (
            "касание 1м + спред-уровни: Short enter≥6.2 exit≤5.8 · "
            "Long enter≤3.2 exit≥4.0 · переход без входа · без Z. "
            "Те же правила и settle +10с, что Prod tip1m AUTO."
        )
    elif use_regime:
        note_geo = (
            "касание 1м + режим спреда (legacy Z): узкий ±1.0/±0.7 · широкий ±1.6/±1.3 · "
            "переход без входа; выход зафиксирован на режиме входа. "
            "Settle +10с как Prod. Heatmap остаётся классической сеткой E×X."
        )
    note_prod = (
        "как Прод: входы/выходы из decision_bars + закрытых Prod-сделок "
        "(как вкладка История; пороги Z в симе не двигают edges)."
    )
    note_plain = (
        "Risk exit группы в режиме касания 1м пока не учитываются (только edge + TP)."
    )
    from live.constants import MONITOR_TIP1M_SETTLE_SEC

    risk_note = note_prod if use_replay else note_geo if not as_live else note_plain
    settle_meta = None if use_replay else float(MONITOR_TIP1M_SETTLE_SEC)
    with _lock:
        hit = _sim_cache.get(skey)
    if hit is not None:
        base_meta = {
            **meta,
            "start": start,
            "asLive": as_live,
            "replayProd": use_replay,
            "asLiveActions": hit.get("asLiveActions", 0),
            "windowStartMs": hit.get("windowStartMs", 0),
            "simSec": 0.0,
            "simCacheHit": True,
            "riskExitNoteRu": risk_note,
            "tip1mSettleSec": settle_meta,
        }
        return {
            "trades": hit["trades"],
            "summary": dict(hit["summary"]),
            "params": dict(hit["params"]),
            "meta": _enrich_as_live_sparse_meta(
                base_meta, hit["trades"], use_replay=use_replay, start=start
            ),
        }

    wms = _window_start_ms(prep, start)
    actions = None
    if use_replay:
        actions = build_as_live_tip_actions(prep, window_start_ms=wms)
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
        as_live_actions=actions,
        regime_z_mode=use_regime,
        spread_level_mode=use_spread,
        spread_levels=spread_levels if use_spread else None,
    )
    base_meta = {
        **meta,
        "start": start,
        "asLive": as_live,
        "replayProd": use_replay,
        "asLiveActions": len(actions or []),
        "windowStartMs": wms,
        "simSec": round(time.time() - t0, 3),
        "simCacheHit": False,
        "riskExitNoteRu": risk_note,
        "tip1mSettleSec": settle_meta,
    }
    result["meta"] = _enrich_as_live_sparse_meta(
        base_meta, result.get("trades") or [], use_replay=use_replay, start=start
    )
    with _lock:
        _cache_put(
            _sim_cache,
            skey,
            {
                "trades": result["trades"],
                "summary": result["summary"],
                "params": result["params"],
                "windowStartMs": wms,
                "asLiveActions": len(actions or []),
            },
            _SIM_CACHE_MAX,
        )
    return result


def _hm_axis_values(lo: float, hi: float, step: float) -> list[float]:
    out: list[float] = []
    v = round(float(lo), 10)
    hi_r = float(hi)
    st = max(1e-9, float(step))
    while v <= hi_r + 1e-9:
        out.append(round(v, 1))
        v = round(v + st, 10)
    return out


def heatmap_tip1m(
    *,
    csv: str,
    entry_min: float = 0.5,
    entry_max: float = 2.7,
    exit_min: float = 0.5,
    exit_max: float | None = None,
    step: float = 0.1,
    slip: float = DEFAULT_SLIP,
    notional: float = DEFAULT_NOTIONAL,
    compound: bool = False,
    take_profit_pct: float = 0.0,
    start: str | None = None,
    spread_level_mode: bool = False,
    band: str = "wide",
    enter_wide: float | None = None,
    exit_wide: float | None = None,
    enter_narrow: float | None = None,
    exit_narrow: float | None = None,
) -> dict[str, Any]:
    """E×X tip1m heatmap.

    Classic Z grid when ``spread_level_mode`` is off.
    With spread levels: S% enter×exit for ``band`` (wide Short / narrow Long);
    the other band stays fixed. Shared tip series + edges across all cells.
    """
    from live.constants import (
        DEFAULT_SPREAD_ENTER_NARROW,
        DEFAULT_SPREAD_ENTER_WIDE,
        DEFAULT_SPREAD_EXIT_NARROW,
        DEFAULT_SPREAD_EXIT_WIDE,
    )

    use_spread = bool(spread_level_mode)
    band_s = str(band or "wide").strip().lower()
    if band_s not in ("wide", "narrow"):
        band_s = "wide"
    ew = float(enter_wide if enter_wide is not None else DEFAULT_SPREAD_ENTER_WIDE)
    xw = float(exit_wide if exit_wide is not None else DEFAULT_SPREAD_EXIT_WIDE)
    en = float(enter_narrow if enter_narrow is not None else DEFAULT_SPREAD_ENTER_NARROW)
    xn = float(exit_narrow if exit_narrow is not None else DEFAULT_SPREAD_EXIT_NARROW)
    x_max = float(exit_max) if exit_max is not None else float(entry_max)

    prep, meta = ensure_tip_series(csv)
    tip_key = str(_tip_cache.get("key") or "")
    hkey = (
        f"{tip_key}|hm|v2|sl={int(use_spread)}|b={band_s}|"
        f"{entry_min:.2f}|{entry_max:.2f}|{exit_min:.2f}|{x_max:.2f}|{step:.2f}|"
        f"ew={ew:.2f}|xw={xw:.2f}|en={en:.2f}|xn={xn:.2f}|"
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
    edges = _edges_tip1m_settled(prep, _edges_from(prep, wms))
    cells: list[dict[str, Any]] = []
    t0 = time.time()

    if use_spread:
        entries = _hm_axis_values(entry_min, entry_max, step)
        exits = _hm_axis_values(exit_min, x_max, step)
        for e in entries:
            for x in exits:
                if band_s == "wide":
                    if e <= x + 1e-9:
                        continue
                    pnl, n_tr = run_touch_1m_spread_pnl_only(
                        prep,
                        enter_wide=e,
                        exit_wide=x,
                        enter_narrow=en,
                        exit_narrow=xn,
                        window_start_ms=wms,
                        compound=compound,
                        slip=slip,
                        notional=notional,
                        take_profit_pct=take_profit_pct,
                        edges=edges,
                    )
                else:
                    if e >= x - 1e-9:
                        continue
                    pnl, n_tr = run_touch_1m_spread_pnl_only(
                        prep,
                        enter_wide=ew,
                        exit_wide=xw,
                        enter_narrow=e,
                        exit_narrow=x,
                        window_start_ms=wms,
                        compound=compound,
                        slip=slip,
                        notional=notional,
                        take_profit_pct=take_profit_pct,
                        edges=edges,
                    )
                cells.append(
                    {
                        "entry": float(e),
                        "exit": float(x),
                        "pnl": float(round(pnl, 2)),
                        "n": int(n_tr),
                    }
                )
        prod_mark = (
            {"entry": ew, "exit": xw}
            if band_s == "wide"
            else {"entry": en, "exit": xn}
        )
        risk_note = (
            "PNL · S% вх×вых (спред-уровни): "
            + (
                "широкий Short — enter>exit; узкий Long зафиксирован."
                if band_s == "wide"
                else "узкий Long — enter<exit; широкий Short зафиксирован."
            )
            + " Cuts 3.5/5.5 не свипаются. Risk exit не учитываются."
        )
        engine = "spread_pnl_only_v1"
    else:
        # Warm numba once (compile) before the grid; reuse int64 edges.
        edges_i64 = np.asarray(edges, dtype=np.int64)
        kern = _get_pnl_numba_kernel()
        if kern is not None and len(edges_i64):
            try:
                kern(
                    prep.z,
                    prep.spread,
                    prep.day_ord,
                    edges_i64,
                    float(round(entry_min, 1)),
                    float(round(exit_min, 1)),
                    float(slip),
                    float(notional),
                    bool(compound),
                    float(take_profit_pct),
                )
            except Exception:
                kern = None
        edges = edges_i64
        engine = "numba_pnl_tp_v1" if kern is not None else "pnl_only_tp_v1"
        t0 = time.time()  # exclude numba compile from heatmapSec
        e = round(entry_min, 10)
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
        prod_mark = None
        risk_note = "Risk exit в heatmap касания 1м не учитываются."

    meta_extra = {
        "start": start,
        "windowStartMs": wms,
        "spreadLevelMode": use_spread,
        "band": band_s if use_spread else None,
        "prodMark": prod_mark,
        "fixedLevels": (
            {
                "enter_wide": ew,
                "exit_wide": xw,
                "enter_narrow": en,
                "exit_narrow": xn,
            }
            if use_spread
            else None
        ),
        "grid": {
            "entryMin": entry_min,
            "entryMax": entry_max,
            "exitMin": exit_min,
            "exitMax": x_max if use_spread else None,
            "step": step,
        },
        "slip": slip,
        "notional": notional,
        "compound": compound,
        "takeProfitPct": take_profit_pct,
        "heatmapSec": round(time.time() - t0, 2),
        "heatmapCacheHit": False,
        "heatmapEngine": engine,
        "riskExitNoteRu": risk_note,
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
    *,
    as_live: bool = False,
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
    live_meta: dict[str, Any] = {
        "as_live": False,
        "locked_count": 0,
        "bars_count": len(out_bars),
        "coverage": 0.0,
        "locked_from": None,
        "locked_to": None,
    }
    if as_live and out_bars:
        from live import store as live_store

        live_meta = live_store.overlay_decision_bars_on_series(out_bars)
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
        **live_meta,
    }
