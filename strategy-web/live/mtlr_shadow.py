"""MTLR/MTLRP Phase‑2 shadow + status: m15 spread-level signals.

TATN Prod stays tip1m + AUTO. Mechel uses completed 15m bars (ISS 10m→15m),
levels Short 8.9→8.4 / Long 3.2→4.3, cuts 4.52/6.48. Orders only when
``mtlr_auto_execute`` is on (default false) — see ``live.mtlr_engine``.

TODO (Phase later): dedicated Mechel tip1m feed — no live tip1m path yet; stay on m15.
"""

from __future__ import annotations

import threading
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import pandas as pd

from live.constants import (
    DEFAULT_MTLR_AUTO_EXECUTE,
    DEFAULT_MTLR_ENABLED,
    MONITOR_BAR_SETTLE_SEC,
    MTLR_ORD,
    MTLR_PREF,
    MTLR_SPREAD_ENTER_NARROW,
    MTLR_SPREAD_ENTER_WIDE,
    MTLR_SPREAD_EXIT_NARROW,
    MTLR_SPREAD_EXIT_WIDE,
    MTLR_SPREAD_WIDTH_NARROW_MAX,
    MTLR_SPREAD_WIDTH_WIDE_MIN,
    SIGNAL_MODE_MTLR_M15,
)
from live.signals import Position, Signal
from live.spread_levels import (
    SpreadLevels,
    classify_spread_pct_cuts,
    determine_spread_level_signal,
)
from live.spread_regime import REGIME_LABEL_RU

MSK = ZoneInfo("Europe/Moscow")
ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = ROOT / "data"
CSV_CACHE = DATA_DIR / "m15_mtlr_400d.csv"
CSV_LIVE = DATA_DIR / "m15_mtlr_live.csv"

# Desk polls often; 10m ISS already lags — don't freeze the CSV for hours.
_CACHE_TTL_SEC = 60
# Skip ISS only if the last m15 is still the current/previous slot.
_ISS_SKIP_MAX_AGE_SEC = 20 * 60
_FRAME_CACHE: dict[str, Any] = {"ts": 0.0, "df": None, "error": None}
_STATUS_CACHE: dict[str, Any] = {"ts": 0.0, "key": None, "payload": None}
_LOCK = threading.Lock()

MTLR_LEVELS = SpreadLevels(
    enter_wide=MTLR_SPREAD_ENTER_WIDE,
    exit_wide=MTLR_SPREAD_EXIT_WIDE,
    enter_narrow=MTLR_SPREAD_ENTER_NARROW,
    exit_narrow=MTLR_SPREAD_EXIT_NARROW,
)


def _parse_bool(v: Any, default: bool) -> bool:
    if v is None:
        return default
    if isinstance(v, bool):
        return v
    s = str(v).strip().lower()
    if s in ("1", "true", "yes", "on"):
        return True
    if s in ("0", "false", "no", "off"):
        return False
    return default


def mtlr_enabled(settings: dict[str, Any] | None) -> bool:
    s = settings or {}
    return _parse_bool(s.get("mtlr_enabled"), DEFAULT_MTLR_ENABLED)


def mtlr_auto_execute(settings: dict[str, Any] | None) -> bool:
    """True only when settings flag is on (default false — no accidental orders)."""
    s = settings or {}
    return _parse_bool(s.get("mtlr_auto_execute"), DEFAULT_MTLR_AUTO_EXECUTE)


def mtlr_levels_from_settings(settings: dict[str, Any] | None = None) -> SpreadLevels:
    s = settings or {}

    def _f(*keys: str, default: float) -> float:
        for key in keys:
            raw = s.get(key)
            if raw is None:
                continue
            try:
                return float(raw)
            except (TypeError, ValueError):
                continue
        return float(default)

    return SpreadLevels(
        enter_wide=_f("mtlr_enter_wide", default=MTLR_SPREAD_ENTER_WIDE),
        exit_wide=_f("mtlr_exit_wide", default=MTLR_SPREAD_EXIT_WIDE),
        enter_narrow=_f("mtlr_enter_narrow", default=MTLR_SPREAD_ENTER_NARROW),
        exit_narrow=_f("mtlr_exit_narrow", default=MTLR_SPREAD_EXIT_NARROW),
    )


def classify_mtlr_spread(spread: float | None) -> str:
    return classify_spread_pct_cuts(
        spread,
        narrow_max=MTLR_SPREAD_WIDTH_NARROW_MAX,
        wide_min=MTLR_SPREAD_WIDTH_WIDE_MIN,
    )


def determine_mtlr_signal(
    previous_s: float | None,
    current_s: float,
    position: Position,
    levels: SpreadLevels | None = None,
) -> Signal:
    return determine_spread_level_signal(
        previous_s,
        current_s,
        position,
        levels or MTLR_LEVELS,
        narrow_max=MTLR_SPREAD_WIDTH_NARROW_MAX,
        wide_min=MTLR_SPREAD_WIDTH_WIDE_MIN,
    )


def _bar_ms(ts: Any) -> int:
    if isinstance(ts, (int, float)):
        return int(ts)
    t = pd.Timestamp(ts)
    if t.tzinfo is None:
        t = t.tz_localize(MSK)
    else:
        t = t.tz_convert(MSK)
    return int(t.timestamp() * 1000)


def _normalize_frame(df: pd.DataFrame) -> pd.DataFrame:
    if df is None or df.empty:
        return pd.DataFrame(
            columns=["timestamp", "spread_percent", "ord_close", "pref_close"]
        )
    out = df.copy()
    if "ord_close" not in out.columns and "tatn_close" in out.columns:
        out = out.rename(columns={"tatn_close": "ord_close", "tatnp_close": "pref_close"})
    need = ["timestamp", "spread_percent"]
    for c in need:
        if c not in out.columns:
            raise ValueError(f"mtlr frame missing column {c}")
    out["timestamp"] = pd.to_datetime(out["timestamp"], errors="coerce")
    tz = getattr(out["timestamp"].dt, "tz", None)
    if tz is not None:
        out["timestamp"] = out["timestamp"].dt.tz_convert("Europe/Moscow").dt.tz_localize(None)
    out["spread_percent"] = pd.to_numeric(out["spread_percent"], errors="coerce")
    out = out.dropna(subset=["timestamp", "spread_percent"]).sort_values("timestamp")
    out = out.drop_duplicates(subset=["timestamp"], keep="last")
    return out.reset_index(drop=True)


def _frame_age_seconds(df: pd.DataFrame, now: datetime | None = None) -> float | None:
    """Seconds since last m15 timestamp (MSK). None if empty/unparseable."""
    if df is None or df.empty or "timestamp" not in df.columns:
        return None
    try:
        last = pd.Timestamp(df["timestamp"].iloc[-1])
        if last.tzinfo is None:
            last = last.tz_localize(MSK)
        else:
            last = last.tz_convert(MSK)
        now = now or datetime.now(tz=MSK)
        return max(0.0, (now - last.to_pydatetime()).total_seconds())
    except Exception:
        return None


def _should_skip_iss(df: pd.DataFrame, *, force: bool, now: datetime | None = None) -> bool:
    """True = CSV is fresh enough; do not hit ISS."""
    if force or df is None or df.empty:
        return False
    age = _frame_age_seconds(df, now)
    if age is None:
        return False
    return age < float(_ISS_SKIP_MAX_AGE_SEC)


def _overlay_mtlr_1m(frame: pd.DataFrame) -> pd.DataFrame:
    """Catch up the m15 tail from 1m ISS — 10m candles often stall 1–2 slots (or more)."""
    from m15_iss_loader import (
        INTERVAL_1,
        LIVE_TIP_1M_HOURS,
        _build_m15_frame,
        _fetch_candles,
        _recalc_z,
    )

    if frame is None or frame.empty:
        return frame
    end = datetime.now(tz=MSK)
    tip_from = (end - timedelta(hours=LIVE_TIP_1M_HOURS)).strftime("%Y-%m-%d")
    till = end.strftime("%Y-%m-%d")
    try:
        ord1 = _fetch_candles(MTLR_ORD, tip_from, till, interval=INTERVAL_1)
        pref1 = _fetch_candles(MTLR_PREF, tip_from, till, interval=INTERVAL_1)
    except Exception:
        return frame
    if ord1.empty or pref1.empty:
        return frame
    tip = _build_m15_frame(ord1, pref1, include_forming=True)
    if tip is None or tip.empty:
        return frame
    tip = tip.rename(columns={"tatn_close": "ord_close", "tatnp_close": "pref_close"})
    base = _normalize_frame(frame)
    tip2 = _normalize_frame(tip)
    if tip2.empty:
        return base
    try:
        head = base[base["timestamp"] < tip2["timestamp"].iloc[0]]
        merged = _normalize_frame(pd.concat([head, tip2], ignore_index=True))
        if not merged.empty and merged["timestamp"].iloc[-1] < base["timestamp"].iloc[-1]:
            return base
        zsrc = merged.rename(columns={"ord_close": "tatn_close", "pref_close": "tatnp_close"})
        zsrc = _recalc_z(zsrc)
        zsrc = zsrc.rename(columns={"tatn_close": "ord_close", "tatnp_close": "pref_close"})
        return _normalize_frame(zsrc)
    except Exception:
        return base


def _fetch_recent_m15(days: int = 7) -> pd.DataFrame:
    from m15_iss_loader import _build_m15_frame, _fetch_candles

    end = datetime.now(tz=MSK)
    start = (end - timedelta(days=int(days))).strftime("%Y-%m-%d")
    till = end.strftime("%Y-%m-%d")
    ord10 = _fetch_candles(MTLR_ORD, start, till, interval=10)
    pref10 = _fetch_candles(MTLR_PREF, start, till, interval=10)
    if ord10.empty or pref10.empty:
        return pd.DataFrame()
    # Forming slot + 1m overlay: ISS 10m for MTLR/MTLRP often stalls after the
    # morning open (chart looked «stuck at 07:30» while TATN tip1m kept moving).
    frame = _build_m15_frame(ord10, pref10, include_forming=True)
    if frame.empty:
        return pd.DataFrame()
    frame = frame.rename(columns={"tatn_close": "ord_close", "tatnp_close": "pref_close"})
    tmp = _normalize_frame(frame)
    keep = [c for c in ("timestamp", "z_score", "spread_percent", "ord_close", "pref_close") if c in tmp.columns]
    return _overlay_mtlr_1m(tmp[keep])


def _load_base_csv() -> pd.DataFrame:
    for path in (CSV_LIVE, CSV_CACHE):
        if path.exists():
            try:
                return _normalize_frame(pd.read_csv(path))
            except Exception:
                continue
    return pd.DataFrame()


def _merge_frames(base: pd.DataFrame, fresh: pd.DataFrame) -> pd.DataFrame:
    if base is None or base.empty:
        return _normalize_frame(fresh)
    if fresh is None or fresh.empty:
        return _normalize_frame(base)
    return _normalize_frame(pd.concat([base, fresh], ignore_index=True))


def _maybe_persist(df: pd.DataFrame) -> None:
    if df is None or df.empty:
        return
    try:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        out = df.copy()
        out["timestamp"] = pd.to_datetime(out["timestamp"]).dt.strftime("%Y-%m-%d %H:%M:%S")
        # Keep cosmetic TATN column names so existing scripts can reuse the file.
        save = out.rename(columns={"ord_close": "tatn_close", "pref_close": "tatnp_close"})
        cols = [c for c in ("timestamp", "z_score", "spread_percent", "tatn_close", "tatnp_close") if c in save.columns]
        save[cols].to_csv(CSV_LIVE, index=False)
    except Exception:
        pass


def load_mtlr_m15_frame(*, force_refresh: bool = False) -> tuple[pd.DataFrame, dict[str, Any]]:
    """Return (frame, meta). Cached; ISS refresh at most every ``_CACHE_TTL_SEC``."""
    now = time.time()
    with _LOCK:
        cached = _FRAME_CACHE.get("df")
        age = now - float(_FRAME_CACHE.get("ts") or 0.0)
        if (
            not force_refresh
            and cached is not None
            and not getattr(cached, "empty", True)
            and age < _CACHE_TTL_SEC
        ):
            meta = {
                "from_cache": True,
                "age_sec": round(age, 1),
                "rows": int(len(cached)),
                "error": _FRAME_CACHE.get("error"),
            }
            return cached.copy(), meta

    base = _load_base_csv()
    err: str | None = None
    fresh = pd.DataFrame()
    skip_iss = _should_skip_iss(base, force=force_refresh)

    if not skip_iss or force_refresh or base.empty:
        try:
            fresh = _fetch_recent_m15(days=10 if base.empty else 7)
        except Exception as exc:
            err = str(exc)
            fresh = pd.DataFrame()

    merged = _merge_frames(base, fresh)
    if not merged.empty and not fresh.empty:
        _maybe_persist(merged)

    with _LOCK:
        _FRAME_CACHE["ts"] = time.time()
        _FRAME_CACHE["df"] = merged
        _FRAME_CACHE["error"] = err

    meta = {
        "from_cache": False,
        "age_sec": 0.0,
        "rows": int(len(merged)),
        "refreshed": not fresh.empty,
        "error": err,
        "csv": str(CSV_LIVE if CSV_LIVE.exists() else CSV_CACHE),
    }
    return merged.copy(), meta


def _drop_unsettled_tail(df: pd.DataFrame, now: datetime | None = None) -> pd.DataFrame:
    """Skip last m15 bar until settle window elapsed (same idea as M15 path)."""
    if df is None or df.empty:
        return df
    now = now or datetime.now(tz=MSK)
    last_ts = pd.Timestamp(df["timestamp"].iloc[-1])
    if last_ts.tzinfo is None:
        last_ts = last_ts.tz_localize(MSK)
    else:
        last_ts = last_ts.tz_convert(MSK)
    # Bar end = start + 15m; settle after close.
    bar_end = last_ts.to_pydatetime() + timedelta(minutes=15)
    age = (now - bar_end).total_seconds()
    if age < float(MONITOR_BAR_SETTLE_SEC):
        return df.iloc[:-1].copy()
    return df


def _mtlr_chart_max_bars(days: int | None, max_bars: int | None) -> int:
    """m15 ≈ 50–70 баров/день с вечеркой; 6М ≈ 10–13k. Старый потолок 2500 ≈ 45д."""
    if max_bars is not None and int(max_bars) > 0:
        return int(max_bars)
    d = int(days) if days is not None and int(days) > 0 else 30
    return max(4000, d * 80)


def mtlr_bars_for_chart(
    df: pd.DataFrame,
    *,
    days: int | None = None,
    max_bars: int | None = None,
) -> list[dict[str, Any]]:
    """Desk bottom-pane OHLC-ready bars (close-only → client builds prev→close candles)."""
    if df is None or df.empty:
        return []
    settled = _drop_unsettled_tail(df)
    if settled is None or settled.empty:
        return []
    use = settled
    if days is not None and int(days) > 0:
        try:
            last_ts = pd.Timestamp(settled["timestamp"].iloc[-1])
            if last_ts.tzinfo is None:
                last_ts = last_ts.tz_localize(MSK)
            else:
                last_ts = last_ts.tz_convert(MSK)
            cut = last_ts - timedelta(days=int(days))
            ts = pd.to_datetime(settled["timestamp"])
            if ts.dt.tz is None:
                ts = ts.dt.tz_localize(MSK)
            else:
                ts = ts.dt.tz_convert(MSK)
            use = settled.loc[ts >= cut].copy()
        except Exception:
            use = settled
    cap = _mtlr_chart_max_bars(days, max_bars)
    if len(use) > cap:
        use = use.iloc[-cap :].copy()

    out: list[dict[str, Any]] = []
    prev_sp: float | None = None
    for _, row in use.iterrows():
        try:
            sp = float(row["spread_percent"])
        except (TypeError, ValueError):
            continue
        if sp != sp:
            continue
        ts = row["timestamp"]
        ms = _bar_ms(ts)
        bar_time = pd.Timestamp(ts).strftime("%Y-%m-%d %H:%M:%S")
        # Synthetic OHLC from adjacent closes (m15 frame has close-only spread).
        open_sp = float(prev_sp) if prev_sp is not None else sp
        high_sp = max(open_sp, sp)
        low_sp = min(open_sp, sp)
        out.append(
            {
                "time": bar_time,
                "timestampMs": ms,
                "spread": round(sp, 6),
                "spread_open": round(open_sp, 6),
                "spread_high": round(high_sp, 6),
                "spread_low": round(low_sp, 6),
                "source": "mtlr_m15",
            }
        )
        prev_sp = sp
    return out


def walk_mtlr_paper(
    df: pd.DataFrame,
    levels: SpreadLevels | None = None,
) -> dict[str, Any]:
    """Deterministic paper walk from FLAT over completed m15 bars."""
    lv = levels or MTLR_LEVELS
    pos = Position.FLAT
    last_sig = Signal.NONE
    last_sig_bar: str | None = None
    last_sig_ms: int | None = None
    edges: list[dict[str, Any]] = []
    prev_s: float | None = None
    last_bar: str | None = None
    last_ms: int | None = None
    last_s: float | None = None

    settled = _drop_unsettled_tail(df)
    for _, row in settled.iterrows():
        try:
            cur = float(row["spread_percent"])
        except (TypeError, ValueError):
            continue
        if cur != cur:
            continue
        ts = row["timestamp"]
        bar = pd.Timestamp(ts).strftime("%Y-%m-%d %H:%M:%S")
        ms = _bar_ms(ts)
        sig = determine_mtlr_signal(prev_s, cur, pos, lv)
        if sig != Signal.NONE:
            last_sig = sig
            last_sig_bar = bar
            last_sig_ms = ms
            edges.append(
                {
                    "bar": bar,
                    "bar_ms": ms,
                    "signal": sig.value,
                    "spread": round(cur, 4),
                    "position_before": pos.value,
                }
            )
            if sig == Signal.ENTER_LONG:
                pos = Position.LONG
            elif sig == Signal.ENTER_SHORT:
                pos = Position.SHORT
            elif sig in (Signal.EXIT_LONG, Signal.EXIT_SHORT):
                pos = Position.FLAT
        prev_s = cur
        last_bar = bar
        last_ms = ms
        last_s = cur

    regime = classify_mtlr_spread(last_s)
    return {
        "position": pos.value,
        "last_signal": last_sig.value if last_sig != Signal.NONE else None,
        "last_signal_bar": last_sig_bar,
        "last_signal_ms": last_sig_ms,
        "last_bar": last_bar,
        "last_bar_ms": last_ms,
        "spread": round(float(last_s), 4) if last_s is not None else None,
        "regime": regime,
        "regime_label_ru": REGIME_LABEL_RU.get(regime, regime),
        "edges_count": len(edges),
        "recent_edges": edges[-8:],
        "bars_used": int(len(settled)),
    }


def get_mtlr_shadow_status(
    settings: dict[str, Any] | None = None,
    *,
    force: bool = False,
    days: int | None = None,
) -> dict[str, Any]:
    """API/desk payload for Mechel (signals; orders via mtlr_engine when auto on)."""
    s = settings or {}
    enabled = mtlr_enabled(s)
    auto = mtlr_auto_execute(s)
    levels = mtlr_levels_from_settings(s)
    chart_days = int(days) if days is not None and int(days) > 0 else 30
    base = {
        "ok": True,
        "pair": f"{MTLR_ORD}/{MTLR_PREF}",
        "name": "Мечел",
        "enabled": enabled,
        "auto_execute": auto,
        "auto_execute_available": True,
        "phase": 2,
        "signal_mode": SIGNAL_MODE_MTLR_M15,
        "source": "m15",
        "bars_mode": "mtlr_m15",
        "chart_days": chart_days,
        "levels": levels.as_dict(),
        "cuts": {
            "narrow_max": MTLR_SPREAD_WIDTH_NARROW_MAX,
            "wide_min": MTLR_SPREAD_WIDTH_WIDE_MIN,
        },
        "badge_ru": "AUTO · m15" if auto else "тень · m15",
        "note_ru": (
            "Мечел: m15 уровни; ордера только при «Авто Мечел». "
            "Татнефть tip1m AUTO не затрагивается."
        ),
    }
    if not enabled:
        return {
            **base,
            "disabled": True,
            "message_ru": "Мечел выключен (mtlr_enabled=0)",
            "bars": [],
        }

    cache_key = (
        f"{levels.enter_wide}:{levels.exit_wide}:"
        f"{levels.enter_narrow}:{levels.exit_narrow}:{int(auto)}:d{chart_days}"
    )
    now = time.time()
    with _LOCK:
        if (
            not force
            and _STATUS_CACHE.get("payload") is not None
            and _STATUS_CACHE.get("key") == cache_key
            and (now - float(_STATUS_CACHE.get("ts") or 0.0)) < 30.0
        ):
            cached = dict(_STATUS_CACHE["payload"])
            try:
                from live.mtlr_engine import enrich_mtlr_status_with_live

                return enrich_mtlr_status_with_live(cached)
            except Exception:
                return cached

    try:
        df, meta = load_mtlr_m15_frame(force_refresh=force)
    except Exception as exc:
        return {**base, "ok": False, "error": str(exc), "bars": []}

    if df is None or df.empty:
        return {
            **base,
            "ok": False,
            "error": meta.get("error") or "нет m15 баров MTLR",
            "frame": meta,
            "bars": [],
        }

    walk = walk_mtlr_paper(df, levels)
    bars = mtlr_bars_for_chart(df, days=chart_days)
    lag_sec = None
    if walk.get("last_bar_ms"):
        try:
            # Age since m15 close (start + 15m), not since bar label.
            close_unix = float(walk["last_bar_ms"]) / 1000.0 + 15 * 60.0
            lag_sec = round(max(0.0, time.time() - close_unix), 1)
        except (TypeError, ValueError):
            lag_sec = None
    payload = {
        **base,
        **walk,
        "frame": meta,
        "bars": bars,
        "bars_count": len(bars),
        "last_bar_lag_sec": lag_sec,
        "ord_close": None,
        "pref_close": None,
    }
    try:
        settled = _drop_unsettled_tail(df)
        if not settled.empty:
            last = settled.iloc[-1]
            if "ord_close" in settled.columns:
                payload["ord_close"] = float(last["ord_close"])
            if "pref_close" in settled.columns:
                payload["pref_close"] = float(last["pref_close"])
    except Exception:
        pass

    # Orders are placed only from live.mtlr_engine when auto_execute is on.
    payload["orders_placed"] = False
    payload["would_auto"] = bool(auto)

    try:
        from live.mtlr_engine import enrich_mtlr_status_with_live

        payload = enrich_mtlr_status_with_live(payload)
    except Exception:
        pass

    with _LOCK:
        _STATUS_CACHE["ts"] = time.time()
        _STATUS_CACHE["key"] = cache_key
        _STATUS_CACHE["payload"] = payload
    return dict(payload)


def peek_mtlr_shadow_cached() -> dict[str, Any] | None:
    """Non-blocking peek for desk lite (None if cold)."""
    with _LOCK:
        p = _STATUS_CACHE.get("payload")
        return dict(p) if isinstance(p, dict) else None


def kick_mtlr_shadow_warm(
    settings: dict[str, Any] | None = None,
    *,
    days: int | None = None,
) -> None:
    """Background warm so next desk poll hits cache."""

    def _run() -> None:
        try:
            get_mtlr_shadow_status(settings, force=False, days=days)
        except Exception:
            pass

    threading.Thread(target=_run, name="mtlr-shadow-warm", daemon=True).start()
