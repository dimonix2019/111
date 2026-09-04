"""Prod tip1m Mode B — same tip-Z geometry as Testing ``replay.tip_touch``.

Tip Z = rolling μ/σ on **completed M15** + current 1m spread.
Edges on consecutive 1m tips (Δt == 60s). Short tip1m settle after 1m close
(``MONITOR_TIP1M_SETTLE_SEC``) — never M15's 90s settle.
"""

from __future__ import annotations

import logging
import math
import threading
import time
from typing import Any

import pandas as pd

from live.constants import MONITOR_TIP1M_SETTLE_SEC
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

# Т‑Инвест 1м (биржа) — хвост без ~16‑мин отставания ISS.
_ti_1m_lock = threading.Lock()
_ti_1m_cache: dict[str, Any] = {
    "df": None,
    "fetched_at": 0.0,
    "key": "",
    "ids": None,  # (tatn_id, tatnp_id)
}

# Последняя мета хвоста tip1m (для статус-бара на «Торговле»).
_last_tip_feed_meta: dict[str, Any] = {
    "tip_lag_sec": None,
    "iss_lag_sec": None,
    "ti_lag_sec": None,
    "tip_feed": None,  # iss | tinvest | parquet | mixed
}


def get_tip_feed_meta() -> dict[str, Any]:
    return dict(_last_tip_feed_meta)


def _set_tip_feed_meta(**kwargs: Any) -> None:
    _last_tip_feed_meta.update(kwargs)


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


def is_tip1m_settled(
    bar_ms: int,
    now_ms: int,
    *,
    has_next_bar: bool = False,
    settle_sec: float | None = None,
) -> bool:
    """1m tip bar T = slot [T, T+1m). AUTO after close + short settle, or next tip present."""
    if bar_ms <= 0:
        return False
    if has_next_bar:
        return True
    sec = MONITOR_TIP1M_SETTLE_SEC if settle_sec is None else float(settle_sec)
    return now_ms >= bar_ms + M1_MS + int(sec * 1000)


def filter_settled_tips(
    tips: list[dict[str, Any]],
    now_ms: int,
    *,
    settle_sec: float | None = None,
) -> list[dict[str, Any]]:
    """Drop forming last tip until close+``MONITOR_TIP1M_SETTLE_SEC`` (same as Prod live).

    Historical tips with a next bar stay actionable immediately (``has_next_bar``).
    Shared by live ``build_tip_bars_from_m1`` semantics and Testing geometric sim.
    """
    if now_ms <= 0 or not tips:
        return list(tips)
    settle = MONITOR_TIP1M_SETTLE_SEC if settle_sec is None else float(settle_sec)
    n = len(tips)
    out: list[dict[str, Any]] = []
    for i, tip in enumerate(tips):
        ms = int(tip.get("timestampMs") or 0)
        if not is_tip1m_settled(
            ms,
            now_ms,
            has_next_bar=(i + 1 < n),
            settle_sec=settle,
        ):
            continue
        out.append(tip)
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
    settle_sec: float | None = None,
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
        tatn, tatnp = by_ms.get(int(tip.ts_ms), (None, None))
        out.append(tip_point_to_bar(tip, tatn=tatn, tatnp=tatnp))
    if now_ms is not None:
        out = filter_settled_tips(out, int(now_ms), settle_sec=settle_sec)
    return out


def is_consecutive_1m(prev_ms: int, cur_ms: int) -> bool:
    if prev_ms <= 0 or cur_ms <= 0:
        return False
    return (cur_ms - prev_ms) == M1_MS


def _tip1m_consecutive_chain(
    tips: list[dict[str, Any]],
    start_i: int,
    *,
    max_edges: int,
) -> list[tuple[dict, dict]]:
    """Consecutive 1m edges starting at index start_i (prev = tips[start_i-1])."""
    if start_i < 1 or start_i >= len(tips) or max_edges <= 0:
        return []
    pending: list[tuple[dict, dict]] = []
    expected_prev_ms = int(tips[start_i - 1].get("timestampMs") or 0)
    for i in range(start_i, len(tips)):
        prev, cur = tips[i - 1], tips[i]
        prev_ms = int(prev.get("timestampMs") or 0)
        cur_ms = int(cur.get("timestampMs") or 0)
        if prev_ms != expected_prev_ms or not is_consecutive_1m(prev_ms, cur_ms):
            break
        pending.append((prev, cur))
        expected_prev_ms = cur_ms
        if len(pending) >= max_edges:
            break
    return pending


def plan_tip1m_catchup(
    tips: list[dict[str, Any]],
    last_proc_ms: int,
    *,
    max_edges: int = 90,
) -> tuple[str, list[tuple[dict, dict]]]:
    """Catchup on consecutive 1m tips after last_proc.

    After a feed hole (ISS morning lag / empty tip window), recover consecutive
    edges *inside* the available tip series — do not jump the anchor to the tail
    and skip the first valid spread/Z cross (trade #11 morning miss).

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

    # Continuous path: last_proc is the immediate predecessor of tips[start_i].
    if start_i >= 1:
        prev = tips[start_i - 1]
        cur = tips[start_i]
        prev_ms = int(prev.get("timestampMs") or 0)
        cur_ms = int(cur.get("timestampMs") or 0)
        if prev_ms == last_proc_ms and is_consecutive_1m(prev_ms, cur_ms):
            pending = _tip1m_consecutive_chain(tips, start_i, max_edges=max_edges)
            if pending:
                return "live", pending
            return "up_to_date", []

    # Gap / last_proc older than window: resume on first consecutive pair after
    # last_proc (backfill decisions safely within the tip window we have).
    recover_i: int | None = None
    for j in range(max(1, start_i), len(tips)):
        pms = int(tips[j - 1].get("timestampMs") or 0)
        cms = int(tips[j].get("timestampMs") or 0)
        if cms <= last_proc_ms:
            continue
        if is_consecutive_1m(pms, cms):
            recover_i = j
            break
    if recover_i is None:
        return "skip_gap", []
    pending = _tip1m_consecutive_chain(tips, recover_i, max_edges=max_edges)
    if not pending:
        return "skip_gap", []
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
    regime_z_mode: bool = False,
    spread_level_mode: bool = False,
    spread_levels: dict[str, float] | None = None,
    now_ms: int | None = None,
    settle_sec: float | None = None,
) -> list[dict[str, Any]]:
    """Parity Test path: consecutive 1m tip edges (optional liveSignal NONE skip).

    Priority: ``spread_level_mode`` (absolute S levels) >
    ``regime_z_mode`` (Z by regime) > classic Z ``entry``/``exit_z``.

    When ``now_ms`` is set, apply the same tip1m settle gate as Prod
    (``filter_settled_tips`` / ``MONITOR_TIP1M_SETTLE_SEC``).
    """
    from live.spread_levels import (
        SpreadLevels,
        determine_spread_level_signal,
        levels_from_settings,
    )
    from live.spread_regime import (
        classify_spread_pct,
        gate_signal,
        resolve_thresholds,
        z_for_regime,
    )

    if now_ms is not None:
        tips = filter_settled_tips(tips, int(now_ms), settle_sec=settle_sec)

    lv = (
        levels_from_settings(spread_levels)
        if spread_levels
        else SpreadLevels()
    )
    pos = Position.FLAT
    edges: list[dict[str, Any]] = []
    open_lock: dict[str, Any] | None = None
    for i in range(1, len(tips)):
        prev, cur = tips[i - 1], tips[i]
        prev_ms = int(prev.get("timestampMs") or 0)
        cur_ms = int(cur.get("timestampMs") or 0)
        if not is_consecutive_1m(prev_ms, cur_ms):
            continue
        cur_td = str(cur.get("tradeDate") or "")
        if not is_moex_equity_session_bar(cur_td):
            continue
        prev_sp = prev.get("spreadPercent")
        if prev_sp is None:
            prev_sp = prev.get("spread")
        cur_sp = cur.get("spreadPercent")
        if cur_sp is None:
            cur_sp = cur.get("spread")
        use_entry, use_exit = entry, exit_z
        if spread_level_mode:
            try:
                ps = float(prev_sp) if prev_sp is not None else None
                cs = float(cur_sp) if cur_sp is not None else float("nan")
            except (TypeError, ValueError):
                continue
            if ps is None or not (math.isfinite(ps) and math.isfinite(cs)):
                continue
            sig = determine_spread_level_signal(ps, cs, pos, lv)
            use_entry, use_exit = lv.enter_wide, lv.exit_wide  # display placeholders
        else:
            try:
                pz = float(prev.get("zScore") or 0)
                cz = float(cur.get("zScore") or 0)
            except (TypeError, ValueError):
                continue
            if not (math.isfinite(pz) and math.isfinite(cz)):
                continue
            if regime_z_mode:
                th = resolve_thresholds(
                    regime_z_mode=True,
                    classic_entry=entry,
                    classic_exit=exit_z,
                    spread=cur_sp,
                    position=pos,
                    open_trade=open_lock,
                )
                sig = gate_signal(determine_z_signal(pz, cz, pos, th.entry, th.exit), th)
                use_entry, use_exit = th.entry, th.exit
            else:
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
        edge: dict[str, Any] = {
            "bar_ts": cur_td[:16] if len(cur_td) >= 16 else cur_td,
            "bar_ms": cur_ms,
            "signal": sig.value,
            "z": float(cur.get("zScore") or 0),
            "spread": float(cur_sp) if cur_sp is not None else None,
            "entry_z": use_entry,
            "exit_z": use_exit,
        }
        if spread_level_mode:
            edge["spread_level_mode"] = True
            edge["levels"] = lv.as_dict()
        edges.append(edge)
        if sig == Signal.ENTER_LONG:
            pos = Position.LONG
            if regime_z_mode and not spread_level_mode:
                reg = classify_spread_pct(cur_sp)
                pair = z_for_regime(reg)
                open_lock = {
                    "entry_regime": reg,
                    "locked_entry_z": pair[0] if pair else use_entry,
                    "locked_exit_z": pair[1] if pair else use_exit,
                    "entry_spread": cur_sp,
                }
            else:
                open_lock = {"entry_regime": classify_spread_pct(cur_sp), "entry_spread": cur_sp}
        elif sig == Signal.ENTER_SHORT:
            pos = Position.SHORT
            if regime_z_mode and not spread_level_mode:
                reg = classify_spread_pct(cur_sp)
                pair = z_for_regime(reg)
                open_lock = {
                    "entry_regime": reg,
                    "locked_entry_z": pair[0] if pair else use_entry,
                    "locked_exit_z": pair[1] if pair else use_exit,
                    "entry_spread": cur_sp,
                }
            else:
                open_lock = {"entry_regime": classify_spread_pct(cur_sp), "entry_spread": cur_sp}
        elif sig in (Signal.EXIT_LONG, Signal.EXIT_SHORT):
            pos = Position.FLAT
            open_lock = None
    return edges


def fetch_live_1m_spread(
    *,
    hours: float = 6.0,
    max_age_sec: float = 25.0,
    force: bool = False,
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
            not force
            and cached is not None
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


def _m1_tail_lag_sec(m1: pd.DataFrame | None, now_ms: int) -> float | None:
    """Seconds wall-clock is ahead of the last 1m bar (None if empty)."""
    if m1 is None or m1.empty or "timestamp" not in m1.columns:
        return None
    try:
        last = m1["timestamp"].max()
        last_ms = int(pd.Timestamp(last).timestamp() * 1000)
    except Exception:
        return None
    if last_ms <= 0:
        return None
    return max(0.0, (int(now_ms) - last_ms) / 1000.0)


def _merge_1m_frames(primary: pd.DataFrame | None, extra: pd.DataFrame | None) -> pd.DataFrame:
    frames = [f for f in (primary, extra) if f is not None and not f.empty]
    if not frames:
        return pd.DataFrame(columns=["timestamp", "tatn", "tatnp", "spread"])
    out = pd.concat(frames, ignore_index=True)
    return (
        out.drop_duplicates(subset=["timestamp"], keep="last")
        .sort_values("timestamp")
        .reset_index(drop=True)
    )


def tinvest_candles_to_1m_frame(
    tatn_rows: list[dict[str, Any]] | None,
    tatnp_rows: list[dict[str, Any]] | None,
) -> pd.DataFrame:
    """T‑Invest GetCandles pairs → timestamp/tatn/tatnp/spread (MSK)."""
    from zoneinfo import ZoneInfo

    from live.dealer_quotes import _candle_time_ms

    msk = ZoneInfo("Europe/Moscow")
    empty = pd.DataFrame(columns=["timestamp", "tatn", "tatnp", "spread"])

    def _closes(rows: list[dict[str, Any]] | None) -> dict[int, float]:
        out: dict[int, float] = {}
        for r in rows or []:
            if not isinstance(r, dict):
                continue
            if r.get("is_complete") is False or r.get("isComplete") is False:
                continue
            ms = _candle_time_ms(r.get("time"))
            if ms is None:
                continue
            ms = (int(ms) // M1_MS) * M1_MS
            try:
                close = float(r.get("close"))
            except (TypeError, ValueError):
                continue
            if close <= 0:
                continue
            out[ms] = close
        return out

    by_n = _closes(tatn_rows)
    by_p = _closes(tatnp_rows)
    keys = sorted(set(by_n) & set(by_p))
    if not keys:
        return empty
    rows_out: list[dict[str, Any]] = []
    for ms in keys:
        tatn = by_n[ms]
        tatnp = by_p[ms]
        if tatnp <= 0:
            continue
        ts = pd.Timestamp(ms, unit="ms", tz="UTC").tz_convert(msk)
        rows_out.append(
            {
                "timestamp": ts,
                "tatn": tatn,
                "tatnp": tatnp,
                "spread": (tatn / tatnp - 1.0) * 100.0,
            }
        )
    if not rows_out:
        return empty
    return pd.DataFrame(rows_out)


def fetch_tinvest_1m_spread(
    *,
    hours: float = 6.0,
    max_age_sec: float = 20.0,
    force: bool = False,
) -> pd.DataFrame:
    """Биржевые 1м свечи Т‑Инвест (TATN/TATNP) — живой хвост tip1m без лага ISS."""
    from datetime import datetime, timedelta, timezone

    from live.constants import TATN_FALLBACK_ID, TATNP_FALLBACK_ID
    from live.tinvest import TInvestClient

    empty = pd.DataFrame(columns=["timestamp", "tatn", "tatnp", "spread"])
    now_utc = datetime.now(timezone.utc)
    key = f"{hours:.1f}|{now_utc.strftime('%Y-%m-%d %H:%M')}"
    with _ti_1m_lock:
        age = time.time() - float(_ti_1m_cache.get("fetched_at") or 0)
        cached = _ti_1m_cache.get("df")
        if (
            not force
            and cached is not None
            and not getattr(cached, "empty", True)
            and _ti_1m_cache.get("key") == key
            and age < max_age_sec
        ):
            return cached

    try:
        from live import store

        mode, token, _account = store.get_credentials()
    except Exception as exc:
        log.warning("tip1m Т‑Инвест: нет credentials: %s", exc)
        return empty
    if not token:
        return empty

    try:
        client = TInvestClient(mode, token)
    except Exception as exc:
        log.warning("tip1m Т‑Инвест: клиент: %s", exc)
        return empty

    with _ti_1m_lock:
        ids = _ti_1m_cache.get("ids")
    if not isinstance(ids, tuple) or len(ids) != 2:
        try:
            tatn_id = client.resolve_instrument_id("TATN")
        except Exception:
            tatn_id = TATN_FALLBACK_ID
        try:
            tatnp_id = client.resolve_instrument_id("TATNP")
        except Exception:
            tatnp_id = TATNP_FALLBACK_ID
        ids = (str(tatn_id), str(tatnp_id))
        with _ti_1m_lock:
            _ti_1m_cache["ids"] = ids

    to_dt = now_utc
    from_dt = to_dt - timedelta(hours=max(1.0, float(hours)))
    try:
        # candle_source_type=None → обычные биржевые свечи (не дилер выходных).
        tatn_rows = client.get_candles(
            ids[0],
            interval="CANDLE_INTERVAL_1_MIN",
            from_dt=from_dt,
            to_dt=to_dt,
            candle_source_type=None,
            timeout=20.0,
            max_attempts=3,
            accept_empty=True,
        )
        tatnp_rows = client.get_candles(
            ids[1],
            interval="CANDLE_INTERVAL_1_MIN",
            from_dt=from_dt,
            to_dt=to_dt,
            candle_source_type=None,
            timeout=20.0,
            max_attempts=3,
            accept_empty=True,
        )
    except Exception as exc:
        log.warning("tip1m Т‑Инвест GetCandles: %s", exc)
        return empty

    df = tinvest_candles_to_1m_frame(tatn_rows, tatnp_rows)
    with _ti_1m_lock:
        _ti_1m_cache["df"] = df
        _ti_1m_cache["fetched_at"] = time.time()
        _ti_1m_cache["key"] = key
    return df


def _load_1m_parquet_tail(hours: float = 6.0) -> pd.DataFrame:
    """Testing parquet 1m cache — bridge ISS holes / morning lag for live tip."""
    import concurrent.futures
    from datetime import datetime, timedelta
    from zoneinfo import ZoneInfo

    from replay.tip_touch import load_1m_from_cache

    end = datetime.now(ZoneInfo("Europe/Moscow"))
    start = end - timedelta(hours=max(1.0, float(hours)) + 1.0)
    empty = pd.DataFrame(columns=["timestamp", "tatn", "tatnp", "spread"])
    try:
        df = load_1m_from_cache(start, end, extend=False)
    except Exception as exc:
        log.warning("tip1m parquet tail failed: %s", exc)
        return empty
    if df is None:
        df = empty
    # Bounded extend so live monitor does not stall on a cold parquet refresh.
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            fut = pool.submit(lambda: load_1m_from_cache(start, end, extend=True))
            try:
                ext = fut.result(timeout=8.0)
                if ext is not None and not ext.empty:
                    df = ext
            except concurrent.futures.TimeoutError:
                log.warning("tip1m parquet extend timeout — using cache only")
    except Exception as exc:
        log.warning("tip1m parquet extend failed: %s", exc)
    if df is None or df.empty:
        return empty
    return df


def load_tip_bars_for_live(
    m15_bars: list[dict[str, Any]],
    *,
    now_ms: int,
    hours: float = 6.0,
) -> list[dict[str, Any]]:
    """Completed-M15 + live 1m → tip bars ready for monitor catchup.

    ISS 1m often lags wall-clock ~15–16 min. Prefer overlaying T‑Invest exchange
    1m candles on the tail (same frame shape). Parquet bridge remains fallback
    when both ISS and T‑Invest are empty/stale.
    """
    m15 = completed_m15_bars(m15_bars, now_ms)
    if len(m15) < 48:
        # Still try — rolling Z needs history; incomplete → weak Z, edges rare.
        m15 = list(m15_bars)
    m1: pd.DataFrame | None = None
    feed = "iss"
    iss_lag: float | None = None
    ti_lag: float | None = None
    try:
        m1 = fetch_live_1m_spread(hours=hours)
    except Exception as exc:
        log.warning("tip1m 1m fetch failed: %s", exc)
        m1 = None

    lag = _m1_tail_lag_sec(m1, now_ms)
    iss_lag = lag
    # >2m behind wall-clock during session → force ISS refresh + parquet bridge.
    stale = lag is None or lag > 120.0
    if stale:
        try:
            fresh = fetch_live_1m_spread(hours=hours, force=True, max_age_sec=0.0)
            m1 = _merge_1m_frames(m1, fresh)
            lag = _m1_tail_lag_sec(m1, now_ms)
            iss_lag = lag
            stale = lag is None or lag > 120.0
        except Exception as exc:
            log.warning("tip1m 1m force-refresh failed: %s", exc)
    if stale or m1 is None or m1.empty:
        try:
            cached = _load_1m_parquet_tail(hours=hours)
            if cached is not None and not cached.empty:
                m1 = _merge_1m_frames(m1, cached)
                feed = "parquet" if (m1 is not None and not m1.empty and iss_lag is None) else "mixed"
                lag = _m1_tail_lag_sec(m1, now_ms)
                log.info(
                    "tip1m: bridged ISS with parquet 1m (lag=%s)",
                    f"{iss_lag:.0f}s" if iss_lag is not None else "empty",
                )
        except Exception as exc:
            log.warning("tip1m parquet bridge failed: %s", exc)

    # Т‑Инвест поверх ISS: свежий хвост (обычно лаг ~1–2 мин вместо ~16).
    used_ti = False
    try:
        ti = fetch_tinvest_1m_spread(hours=min(float(hours), 12.0), force=stale)
        ti_lag = _m1_tail_lag_sec(ti, now_ms)
        if ti is not None and not ti.empty:
            cur_lag = _m1_tail_lag_sec(m1, now_ms)
            # Overlay when TI is meaningfully fresher, or ISS empty.
            use_ti = cur_lag is None or (
                ti_lag is not None and ti_lag + 45.0 < float(cur_lag)
            )
            if use_ti:
                m1 = _merge_1m_frames(m1, ti)
                used_ti = True
                feed = "tinvest" if cur_lag is None else "tinvest"
                log.info(
                    "tip1m: хвост Т‑Инвест (ISS lag=%s → TI lag=%s)",
                    f"{iss_lag:.0f}s" if iss_lag is not None else "empty",
                    f"{ti_lag:.0f}s" if ti_lag is not None else "?",
                )
    except Exception as exc:
        log.warning("tip1m Т‑Инвест overlay failed: %s", exc)

    tip_lag = _m1_tail_lag_sec(m1, now_ms)
    if used_ti:
        feed = "tinvest"
    elif feed == "parquet":
        pass
    elif m1 is not None and not m1.empty:
        feed = "iss"
    else:
        feed = None
    _set_tip_feed_meta(
        tip_lag_sec=round(tip_lag, 1) if tip_lag is not None else None,
        iss_lag_sec=round(iss_lag, 1) if iss_lag is not None else None,
        ti_lag_sec=round(ti_lag, 1) if ti_lag is not None else None,
        tip_feed=feed,
    )

    if m1 is None or m1.empty:
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
    """Parity: parquet 1m cache (+ bounded extend / live ISS tail)."""
    import concurrent.futures
    from datetime import timedelta

    import pandas as pd

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
    m1 = pd.DataFrame()
    try:
        m1 = load_1m_from_cache(start, end, extend=False)
    except Exception as exc:
        log.warning("parity tip1m cache load failed: %s", exc)
    if end is not None:
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                # extend= is keyword-only on load_1m_from_cache — positional True TypeErrors.
                fut = pool.submit(
                    lambda: load_1m_from_cache(start, end, extend=True)
                )
                try:
                    m1_ext = fut.result(timeout=12.0)
                    if m1_ext is not None and not m1_ext.empty:
                        m1 = m1_ext
                except concurrent.futures.TimeoutError:
                    log.warning("parity tip1m extend timeout — using cache tail")
        except Exception as exc:
            log.warning("parity tip1m extend failed: %s", exc)
    stale = False
    if end is not None and m1 is not None and not m1.empty:
        last_m1 = m1["timestamp"].max()
        end_naive = end.replace(tzinfo=None) if end.tzinfo else end
        stale = (end_naive - last_m1).total_seconds() > 45 * 60
    elif m1 is None or m1.empty:
        stale = True
    if stale:
        try:
            live = fetch_live_1m_spread(hours=72.0, max_age_sec=120.0)
            if live is not None and not live.empty:
                if m1 is not None and not m1.empty:
                    m1 = (
                        pd.concat([m1, live], ignore_index=True)
                        .drop_duplicates(subset=["timestamp"], keep="last")
                        .sort_values("timestamp")
                        .reset_index(drop=True)
                    )
                else:
                    m1 = live
        except Exception as exc2:
            log.warning("parity tip1m live fallback failed: %s", exc2)
    if m1 is None or m1.empty:
        return []
    return build_tip_bars_from_m1(m15, m1, now_ms=now_ms)


# --- Trade desk tip1m chart (weekday TQBR) ---------------------------------
_DESK_TIP_LOCK = threading.Lock()
_DESK_TIP_CACHE: dict[str, Any] = {
    "key": "",
    "ts": 0.0,
    "bars": None,
    "warming": False,
}
_DESK_TIP_TTL_SEC = 20.0
# Reject parquet/peek tails that lag wall-clock (or M15 last) during session.
_DESK_TIP_STALE_MAX_SEC = 45 * 60


def chart_bars_to_signal_bars(bars: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Desk chart rows (time/z/spread) → tip-signal shape (tradeDate/zScore/…)."""
    out: list[dict[str, Any]] = []
    for b in bars or []:
        if not isinstance(b, dict):
            continue
        td = b.get("tradeDate") or b.get("time")
        ms = b.get("timestampMs")
        z = b.get("zScore") if b.get("zScore") is not None else b.get("z")
        sp = b.get("spreadPercent") if b.get("spreadPercent") is not None else b.get("spread")
        if td is None or z is None:
            continue
        out.append(
            {
                "tradeDate": td,
                "timestampMs": ms,
                "zScore": z,
                "spreadPercent": sp,
                "tatnClose": b.get("tatnClose", b.get("tatn")),
                "tatnpClose": b.get("tatnpClose", b.get("tatnp")),
            }
        )
    return out


def tip_bars_to_chart_bars(tips: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Tip signal bars → Trade desk chart rows (Δt ~60s, source=tip1m)."""
    out: list[dict[str, Any]] = []
    for t in tips or []:
        if not isinstance(t, dict):
            continue
        z = t.get("zScore")
        if z is None:
            continue
        out.append(
            {
                "time": t.get("tradeDate"),
                "timestampMs": t.get("timestampMs"),
                "z": float(z),
                "spread": float(t["spreadPercent"]) if t.get("spreadPercent") is not None else None,
                "tatn": t.get("tatnClose"),
                "tatnp": t.get("tatnpClose"),
                "interval": "1m",
                "source": "tip1m",
                "for_z": True,
            }
        )
    return out


DESK_TIP1M_CSV = "m15_tatn_255d.csv"
# Live ISS tail only — multi-day history comes from parquet (bars1m).
_DESK_TIP_LIVE_HOURS = 8.0


def _desk_tip_median_dt_sec(chart: list[dict[str, Any]]) -> float | None:
    if len(chart) < 3:
        return None
    diffs: list[float] = []
    for i in range(1, min(len(chart), 40)):
        a = int(chart[i - 1].get("timestampMs") or 0)
        b = int(chart[i].get("timestampMs") or 0)
        if a > 0 and b > a:
            diffs.append((b - a) / 1000.0)
    if not diffs:
        return None
    diffs.sort()
    return diffs[len(diffs) // 2]


def _filter_chart_by_days(
    chart: list[dict[str, Any]],
    days: int,
) -> list[dict[str, Any]]:
    """Keep last ``days`` calendar days of tip1m chart bars."""
    days_i = max(1, int(days or 1))
    if len(chart) < 2:
        return list(chart or [])
    last_ms = 0
    for b in reversed(chart):
        last_ms = int(b.get("timestampMs") or 0)
        if last_ms > 0:
            break
    if last_ms <= 0:
        return list(chart)
    cut_ms = last_ms - days_i * 86_400_000
    return [b for b in chart if int(b.get("timestampMs") or 0) >= cut_ms]


def _merge_tip_charts(
    base: list[dict[str, Any]],
    live: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Union by timestampMs; live rows overwrite base on collision.

    Keep base Z when live Z is a cold-start 0.0 (short M15 window) so the
    Trade chart does not flatten morning tips to zero.
    """
    by_ms: dict[int, dict[str, Any]] = {}
    for b in base or []:
        ms = int(b.get("timestampMs") or 0)
        if ms > 0:
            by_ms[ms] = b
    for b in live or []:
        if not isinstance(b, dict):
            continue
        ms = int(b.get("timestampMs") or 0)
        if ms <= 0:
            continue
        row = b
        old = by_ms.get(ms)
        if old is not None:
            try:
                lz = float(b["z"]) if b.get("z") is not None else None
                oz = float(old["z"]) if old.get("z") is not None else None
            except (TypeError, ValueError):
                lz = oz = None
            if (
                lz is not None
                and abs(lz) < 1e-12
                and oz is not None
                and abs(oz) > 1e-9
            ):
                row = dict(b)
                row["z"] = oz
        by_ms[ms] = row
    return [by_ms[k] for k in sorted(by_ms)]


def _desk_m15_for_tip_z(
    fallback: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    """Full M15 history for tip μ/σ — desk days=1 window is too short (Z→0)."""
    try:
        from replay.tip_touch import load_m15_ui

        bars, _ = load_m15_ui(DESK_TIP1M_CSV)
        if bars and len(bars) >= 48:
            return list(bars)
    except Exception as exc:
        log.warning("desk tip M15 load failed: %s", exc)
    return list(fallback or [])


def _desk_tip1m_from_parquet(days: int) -> list[dict[str, Any]]:
    """Multi-day tip1m Z+spread from Testing parquet (same geometry as /api/bars1m)."""
    from replay.tip_touch import bars1m_chart

    days_i = max(1, int(days or 7))
    data = bars1m_chart(DESK_TIP1M_CSV, chart_days=days_i, as_live=False)
    raw = data.get("bars") or []
    return tip_bars_to_chart_bars(chart_bars_to_signal_bars(raw))


def desk_tip1m_tail_stale(
    bars: list[dict[str, Any]] | None,
    *,
    now_ms: int | None = None,
    ref_ms: int | None = None,
    max_age_sec: float = _DESK_TIP_STALE_MAX_SEC,
) -> bool:
    """True when last tip bar lags M15/markets ref (or wall-clock) too far.

    Prefer ``ref_ms`` (M15 / markets summary) so after-hours tips that match
    the last equity bar are not flagged. Wall-clock alone is used when no ref
    is available — catches multi-day-stale parquet during session.
    """
    if not bars:
        return True
    last_ms = 0
    for b in reversed(bars):
        if not isinstance(b, dict):
            continue
        last_ms = int(b.get("timestampMs") or 0)
        if last_ms > 0:
            break
        td = b.get("time") or b.get("tradeDate")
        if td:
            try:
                last_ms = int(_parse_bar_dt(str(td)).timestamp() * 1000)
            except Exception:
                last_ms = 0
        if last_ms > 0:
            break
    if last_ms <= 0:
        return True
    max_age = float(max_age_sec)
    if ref_ms is not None and int(ref_ms) > 0:
        return ((int(ref_ms) - last_ms) / 1000.0) > max_age
    now_i = int(now_ms or int(time.time() * 1000))
    return ((now_i - last_ms) / 1000.0) > max_age


def peek_desk_tip1m_chart_bars(
    days: int = 7,
    *,
    need_live: bool = False,
    now_ms: int | None = None,
    ref_ms: int | None = None,
) -> list[dict[str, Any]] | None:
    """Warm tip1m chart cache only (lite first paint — never block on ISS).

    ``need_live=True`` skips parquet-only cache entries so full desk can merge
    a fresh ISS 1m tail. Stale tip tails (vs now / M15 ref) return None so the
    load path refreshes instead of serving a multi-day-old S%.
    """
    now = time.time()
    with _DESK_TIP_LOCK:
        bars = _DESK_TIP_CACHE.get("bars")
        ts = float(_DESK_TIP_CACHE.get("ts") or 0)
        key = str(_DESK_TIP_CACHE.get("key") or "")
        if not bars or not key.startswith(f"{int(days)}:"):
            return None
        if need_live and key.endswith(":pq"):
            return None
        if (now - ts) >= _DESK_TIP_TTL_SEC:
            return None
        out = list(bars)
    if desk_tip1m_tail_stale(out, now_ms=now_ms, ref_ms=ref_ms):
        return None
    return out


def try_desk_tip1m_from_1m_cache(
    m15_bars: list[dict[str, Any]],
    *,
    days: int = 7,
    now_ms: int | None = None,
    max_age_sec: float = 120.0,
) -> list[dict[str, Any]]:
    """Sync tip1m chart from monitor's live 1m cache — 1Д only (session-sized).

    Multi-day periods must use parquet lookback; do not poison the days cache
    with today's ISS session alone.
    """
    days_i = int(days) if days else 7
    if days_i > 1:
        return []
    now_ms_i = int(now_ms or int(time.time() * 1000))
    with _1m_lock:
        df = _1m_cache.get("df")
        age = time.time() - float(_1m_cache.get("fetched_at") or 0)
    if df is None or getattr(df, "empty", True) or age > max_age_sec:
        return []
    # Full M15 lookback — days=1 desk bars alone produce cold Z=0 until midday.
    raw = chart_bars_to_signal_bars(_desk_m15_for_tip_z(m15_bars))
    tips = build_tip_bars_from_m1(raw, df, now_ms=now_ms_i)
    chart = _filter_chart_by_days(tip_bars_to_chart_bars(tips), days_i)
    if len(chart) < 2:
        return []
    with _DESK_TIP_LOCK:
        _DESK_TIP_CACHE["key"] = f"{days_i}:cache"
        _DESK_TIP_CACHE["ts"] = time.time()
        _DESK_TIP_CACHE["bars"] = list(chart)
    return chart


def kick_desk_tip1m_warm(
    m15_bars: list[dict[str, Any]],
    *,
    days: int = 7,
) -> None:
    """Background warm tip1m desk chart (same idea as dealer cache warm)."""
    with _DESK_TIP_LOCK:
        if _DESK_TIP_CACHE.get("warming"):
            return
        _DESK_TIP_CACHE["warming"] = True

    def _run() -> None:
        try:
            chart = load_desk_tip1m_chart_bars(m15_bars, days=days, lite=False)
            if chart:
                _write_desk_tip1m_sidecar(chart)
        except Exception as exc:
            log.warning("desk tip1m warm failed: %s", exc)
        finally:
            with _DESK_TIP_LOCK:
                _DESK_TIP_CACHE["warming"] = False

    threading.Thread(target=_run, name="desk-tip1m-warm", daemon=True).start()


def _write_desk_tip1m_sidecar(chart: list[dict[str, Any]]) -> None:
    """Best-effort static JSON for Trade UI when desk still races M15."""
    try:
        import json
        from pathlib import Path

        path = Path(__file__).resolve().parents[1] / "replay" / "static" / "desk_tip1m.json"
        med = _desk_tip_median_dt_sec(chart)
        payload = {
            "ok": True,
            "bars_mode": "tip1m",
            "generated_ms": int(time.time() * 1000),
            "median_dt_sec": med,
            "count": len(chart),
            "bars": chart,
        }
        path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    except Exception as exc:
        log.debug("desk tip1m sidecar write skipped: %s", exc)


def load_desk_tip1m_chart_bars(
    m15_bars: list[dict[str, Any]],
    *,
    days: int = 7,
    lite: bool = False,
    now_ms: int | None = None,
    ref_ms: int | None = None,
) -> list[dict[str, Any]]:
    """Weekday Trade chart: tip1m Z+spread for period chips (1/7/30/90/180д).

    History from parquet (/api/bars1m geometry). Full path merges a short live
    ISS 1m tail. Lite: parquet only unless the parquet tail is stale — then
    merge live so S% matches AUTO tip1m (caller may still kick warm).
    Never paints ISS M15.
    """
    days_i = int(days) if days else 7
    now_ms_i = int(now_ms or int(time.time() * 1000))
    want_live = not lite

    cached = peek_desk_tip1m_chart_bars(
        days_i, need_live=want_live, now_ms=now_ms_i, ref_ms=ref_ms
    )
    if cached is not None:
        return cached

    chart: list[dict[str, Any]] = []
    try:
        chart = _desk_tip1m_from_parquet(days_i)
    except Exception as exc:
        log.warning("desk tip1m parquet load failed: %s", exc)
        chart = []

    # Lite normally skips ISS; if parquet tail is stale, merge live so we do
    # not cache/publish a multi-day-old S% as current.
    if lite and chart and desk_tip1m_tail_stale(
        chart, now_ms=now_ms_i, ref_ms=ref_ms
    ):
        want_live = True

    if want_live:
        try:
            # Tip Z needs ~30д M15; desk m15_bars are days-filtered (1Д → Z=0).
            raw = chart_bars_to_signal_bars(_desk_m15_for_tip_z(m15_bars))
            tips = load_tip_bars_for_live(
                raw, now_ms=now_ms_i, hours=_DESK_TIP_LIVE_HOURS
            )
            live_chart = tip_bars_to_chart_bars(tips)
            if live_chart:
                chart = _merge_tip_charts(chart, live_chart)
        except Exception as exc:
            log.warning("desk tip1m live merge failed: %s", exc)

    chart = _filter_chart_by_days(chart, days_i)

    # HARD RULE: reject M15-density series masquerading as tip1m
    med = _desk_tip_median_dt_sec(chart)
    if med is not None and med >= 600:
        log.warning("desk tip1m rejected: median Δt=%.0fs (M15-like)", med)
        chart = []

    # Never cache a stale tip tail — poisons lite/full polls with old S%.
    still_stale = desk_tip1m_tail_stale(chart, now_ms=now_ms_i, ref_ms=ref_ms)
    cache_key = f"{days_i}:pq{'+live' if want_live else ''}"
    if chart and not still_stale:
        with _DESK_TIP_LOCK:
            _DESK_TIP_CACHE["key"] = cache_key
            _DESK_TIP_CACHE["ts"] = time.time()
            _DESK_TIP_CACHE["bars"] = list(chart)
        _write_desk_tip1m_sidecar(chart)
    elif still_stale and chart:
        log.info(
            "desk tip1m stale tail kept uncached (days=%s want_live=%s)",
            days_i,
            want_live,
        )
    return chart
