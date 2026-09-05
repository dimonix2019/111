"""1m tip-touch sim (Mode B) for Testing UI — shared tip-Z builder used by Prod live.

Tip Z = rolling μ/σ on completed M15 + current 1m spread; edge on consecutive 1m;
fill at tip spread ± slip. Prod monitor: ``live.tip_touch_signals`` + ``engine.monitor_tick``.
"""
from __future__ import annotations

import math
import re
import logging
import threading
import time
from dataclasses import dataclass, field
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
log = logging.getLogger(__name__)
CACHE_1M = DATA_DIR / "cache_1m_tatn_spread.parquet"
MSK = __import__("zoneinfo").ZoneInfo("Europe/Moscow")
CSV_255 = "m15_tatn_255d.csv"
CSV_365 = "m15_tatn_365d.csv"
CSV_1095 = "m15_tatn_1095d.csv"
CSV_TEST_3D = "m15_test_3d.csv"
_CSV_LOOKBACK_CANDIDATES = (
    (CSV_255, 255),
    (CSV_365, 365),
    (CSV_1095, 1095),
)
_LOOKBACK_FROM_NAME = re.compile(r"_(\d+)d\.csv$", re.I)
# Допуск: «год» = 365/366д на файле 365d, «3 года» ≈1096д на файле 1095d.
_CSV_COVER_SLACK_DAYS = 7

LEVERAGE = 7.0
COMM_PCT = 0.04
DEFAULT_SLIP = 0.02
# Ориентир Tesтa «как боевой счёт 100 000».
# Прод UI: кошельки 40/30/30. Тест (добор/экстра): динамический пул —
# база ≤60% пула с резервом 40% под добор, экстра = свободный остаток (в т.ч. «толстые»).
REF_ACCOUNT_RUB = 100_000.0
REF_MAIN_DEP_RUB = 40_000.0
REF_ADDON_DEP_RUB = 30_000.0
REF_EXTRA_DEP_RUB = 30_000.0
DEFAULT_NOTIONAL = REF_MAIN_DEP_RUB
# Потолки динамического пула Теста заданы в долях текущего пула:
# при капитализации пул растёт на закрытый PnL, и потолки растут вместе с ним.
DYNAMIC_POOL_MAIN_CAP_FRAC = 0.60
DYNAMIC_POOL_ADDON_RESERVE_FRAC = 0.40
# Рублёвые эквиваленты на эталонном счёте (для совместимости и подписей UI).
DYNAMIC_POOL_MAIN_CAP_RUB = REF_ACCOUNT_RUB * DYNAMIC_POOL_MAIN_CAP_FRAC
DYNAMIC_POOL_ADDON_RESERVE_RUB = REF_ACCOUNT_RUB * DYNAMIC_POOL_ADDON_RESERVE_FRAC
# Мета для UI; расчёт — ступени Премиум на ~половину номинала пары.
OVERNIGHT_FEE_MODEL = "premium_short_leg_tiers"
# Вариант 2 (Тест/Prod): добор при открытой базе. Long вход 2.0 · Short вход 7.0.
# Выход OR: ТП (см. ADDON_EXTRA_OR_TP_PCT) ∨ уровень 3.2 / 6.2 — что раньше.
ADDON_ENTER_NARROW = 2.0
ADDON_EXIT_NARROW = 3.2
ADDON_ENTER_WIDE = 7.0
ADDON_EXIT_WIDE = 6.2
# Экстра на хвостах: Long ≤1 · Short ≥9. Выход OR: ТП ∨ уровень 2.0 / 7.0.
EXTRA_ENTER_NARROW = 1.0
EXTRA_EXIT_NARROW = 2.0
EXTRA_ENTER_WIDE = 9.0
EXTRA_EXIT_WIDE = 7.0
# Если общий ТП в панели выкл (0) — добор/экстра всё равно режутся по 2% ∨ уровню.
ADDON_EXTRA_OR_TP_PCT = 2.0


def effective_addon_extra_tp_pct(take_profit_pct: float, *, tag: str | None = None) -> float:
    """Эффективный ТП % для ноги: добор/экстра всегда OR с уровнем (мин. 2% если ТП выкл)."""
    tp = float(take_profit_pct or 0.0)
    t = str(tag or "").strip().lower()
    if t in ("addon", "extra", "добор", "экстра"):
        return tp if tp > 0 else float(ADDON_EXTRA_OR_TP_PCT)
    return tp

_EPOCH = date(1970, 1, 1)
_SIM_CACHE_MAX = 48
_HM_CACHE_MAX = 12
_PREP_CACHE_VERSION = 3
_EXTEND_MIN_INTERVAL_SEC = 90.0

_lock = threading.Lock()
_tip_build_lock = threading.Lock()
_window_build_lock = threading.Lock()
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
# Короткое окно Tesта (неделя/месяц): свой ряд, без ожидания 3-летнего индекса.
_SHORT_WINDOW_MAX_DAYS = 45
_WINDOW_LOOKBACK_DAYS = 50
_WINDOW_CACHE_TTL_SEC = 45.0
_window_tip_cache: dict[str, Any] = {
    "key": None,
    "prep": None,
    "meta": None,
    "built_at": 0.0,
}
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
    s = str(s or "").replace("T", " ").strip()
    if len(s) >= 10 and s[4] == "-" and s[7] == "-":
        if len(s) == 10:
            s = s + " 00:00:00"
        else:
            s = s[:19]
            if len(s) == 16:
                s += ":00"
    return datetime.strptime(s, "%Y-%m-%d %H:%M:%S").replace(tzinfo=MSK)


def is_session_bar(trade_date: str, *, weekend_trading: bool = False) -> bool:
    s = str(trade_date or "").replace("T", " ").strip()
    if len(s) < 16:
        return False
    try:
        y, mo, d = int(s[0:4]), int(s[5:7]), int(s[8:10])
    except ValueError:
        return False
    dow = datetime(y, mo, d, 12, 0, 0).weekday()
    hm = s[11:16]
    if dow >= 5:
        # Test-only dealer-data window. Prod callers keep the default False.
        return bool(weekend_trading) and "10:00" <= hm < "19:00"
    return "07:00" <= hm < "23:50"


def floor_15m(dt: datetime) -> datetime:
    return dt.replace(minute=(dt.minute // 15) * 15, second=0, microsecond=0)


def load_m15_ui(
    csv_name: str, start_date: str | None = None
) -> tuple[list[dict[str, Any]], str]:
    from replay.replay_db import _load_cached_bars, merge_bars_by_timestamp

    name = Path(csv_name).name
    csv_path = DATA_DIR / name
    bars = _load_cached_bars(csv_path, name, start_date) or []
    # 1095d/365d CSV часто заморожен; живой хвост сидит в 255d + parquet 1м.
    if name in (CSV_365, CSV_1095):
        live_path = DATA_DIR / CSV_255
        if live_path.is_file():
            try:
                tail = _load_cached_bars(live_path, CSV_255, start_date)
                if tail:
                    bars = merge_bars_by_timestamp(bars, tail)
            except Exception:
                pass
    if not bars:
        raise ValueError(f"no M15 bars for {name}")
    bars = [b for b in bars if int(b.get("timestampMs") or 0) > 0] or bars
    bars.sort(key=lambda b: int(b.get("timestampMs") or 0))
    return bars, "ui_sqlite_lookback"


def size_test_spread_lots(
    *,
    deposit_rub: float,
    price_tatn: float | None,
    price_tatnp: float | None,
    leverage: float | None = None,
    direction: str | None = None,
) -> dict[str, Any]:
    """Tesт: целые лоты как живая нога — floor(депозит × плечо / (TATN+TATNP)).

    Так на 10 000 выходило ~66 акций TATN на ногу (плечо 7), без маржин-колла.
    Потолка 80 нет. Гарантийное обеспечение не режет размер: брокер пускает
    плечо. ``direction`` оставлен для совместимости вызовов.
    Без цен ног — legacy: непрерывный номинал (депозит×плечо), lots=None.
    lots=0 — не хватает даже на 1 лот.
    ``deposit`` — капитал ноги (вложения), не номинал пары / плечо.
    """
    from live.constants import SPREAD_LOT_MIN_LOTS

    _ = direction
    dep = max(1.0, float(deposit_rub))
    lev = max(1.0, float(leverage if leverage is not None else LEVERAGE))

    def _legacy() -> dict[str, Any]:
        return {
            "lots": None,
            "execution_notional": dep * lev,
            "deposit": dep,
            "legacy": True,
            "price_tatn": None,
            "price_tatnp": None,
        }

    if price_tatn is None or price_tatnp is None:
        return _legacy()
    try:
        p_n = float(price_tatn)
        p_np = float(price_tatnp)
    except (TypeError, ValueError):
        return _legacy()
    pair = p_n + p_np
    if not (math.isfinite(pair) and pair > 0.0):
        return _legacy()
    lots = int(math.floor(dep * lev / pair))
    if lots < int(SPREAD_LOT_MIN_LOTS):
        return {
            "lots": 0,
            "execution_notional": 0.0,
            "deposit": dep,
            "legacy": False,
            "price_tatn": p_n,
            "price_tatnp": p_np,
        }
    exec_n = float(lots) * pair
    return {
        "lots": lots,
        "execution_notional": exec_n,
        "deposit": dep,
        "legacy": False,
        "price_tatn": p_n,
        "price_tatnp": p_np,
    }


def _prep_leg_prices(prep: PreparedTips, i: int) -> tuple[float | None, float | None]:
    tatn = getattr(prep, "tatn", None)
    tatnp = getattr(prep, "tatnp", None)
    if tatn is None or tatnp is None:
        return None, None
    if i < 0 or i >= int(len(tatn)) or i >= int(len(tatnp)):
        return None, None
    try:
        a = float(tatn[i])
        b = float(tatnp[i])
    except (TypeError, ValueError, IndexError):
        return None, None
    if not (math.isfinite(a) and math.isfinite(b) and a > 0.0 and b > 0.0):
        return None, None
    return a, b


def _m1_leg_price_arrays(m1: pd.DataFrame, n: int) -> tuple[np.ndarray, np.ndarray]:
    empty = np.full(max(0, n), np.nan, dtype=np.float64)
    if n <= 0 or m1 is None or len(m1) == 0:
        return empty, empty

    def _col(name: str) -> np.ndarray:
        if name not in m1.columns:
            return empty.copy()
        arr = pd.to_numeric(m1[name], errors="coerce").to_numpy(dtype=np.float64)
        if len(arr) == n:
            return arr
        out = empty.copy()
        m = min(n, len(arr))
        if m > 0:
            out[:m] = arr[:m]
        return out

    return _col("tatn"), _col("tatnp")


def _lot_trade_fields(sz: dict[str, Any] | None) -> dict[str, Any]:
    if not sz:
        return {}
    lots = sz.get("lots")
    if lots is None:
        return {}
    try:
        n = int(lots)
    except (TypeError, ValueError):
        return {}
    if n <= 0:
        return {}
    out: dict[str, Any] = {
        "lots": n,
        "quantity_lots": n,
        "execution_notional_rub": round(float(sz.get("execution_notional") or 0), 2),
    }
    dep = sz.get("deposit")
    if dep is not None:
        try:
            d = float(dep)
            if math.isfinite(d) and d > 0:
                out["entry_deposit_rub"] = round(d, 2)
        except (TypeError, ValueError):
            pass
    tn = sz.get("price_tatn")
    tp = sz.get("price_tatnp")
    if tn is not None and tp is not None:
        out["entry_tatn"] = round(float(tn), 4)
        out["entry_tatnp"] = round(float(tp), 4)
    return out


def _fees_for_sized_open(
    *,
    deposit: float,
    direction: str,
    sz: dict[str, Any],
) -> tuple[float, float, float]:
    return _fees(
        float(sz.get("deposit") or deposit),
        direction=direction,
        lots=sz.get("lots"),
        fill_tatn=sz.get("price_tatn"),
        fill_tatnp=sz.get("price_tatnp"),
        execution_notional_rub=(
            None if sz.get("legacy") else sz.get("execution_notional")
        ),
    )


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


def _force_close_3d_hit(
    *,
    enabled: bool,
    held: int,
    is_long: bool,
    entry_sp: float,
    cur_sp: float,
    exit_lv: float,
    mtm: float,
    mode: str = "indicator",
) -> bool:
    """True → закрыть по правилу 3D (только режим force)."""
    if not enabled:
        return False
    from live.force_close_3d import should_force_close_3d

    return should_force_close_3d(
        hold_days=int(held),
        side="Long" if is_long else "Short",
        entry_spread=float(entry_sp),
        current_spread=float(cur_sp),
        exit_level=float(exit_lv),
        mtm=float(mtm),
        mode=mode,
    )


def _situation_3d_on_bar(
    sit: dict | None,
    *,
    enabled: bool,
    mode: str,
    flat: bool,
    is_long: bool | None,
    held: int | None,
    entry_sp: float | None,
    cur_sp: float,
    exit_lv: float | None,
    entry_lv: float | None = None,
    mtm: float | None,
    toward_entry_clear: bool = True,
) -> tuple[dict | None, bool]:
    """Обновить ситуацию 3D; вернуть (state, force_close_now)."""
    if not enabled:
        return None, False
    from live.force_close_3d import step_situation

    open_side = None
    if not flat and is_long is not None:
        open_side = "Long" if is_long else "Short"
    return step_situation(
        sit,
        enabled=True,
        mode=mode,
        flat=flat,
        open_side=open_side,
        hold_days=held,
        entry_spread=entry_sp,
        current_spread=cur_sp,
        exit_level=exit_lv,
        entry_level=entry_lv,
        mtm=mtm,
        toward_entry_clear=toward_entry_clear,
    )


def _entry_blocked_3d(sit: dict | None, *, want_long: bool) -> bool:
    if not sit:
        return False
    from live.force_close_3d import entry_blocked_by_situation

    return entry_blocked_by_situation(
        active=bool(sit.get("active")),
        situation_side=sit.get("side"),
        want_side="Long" if want_long else "Short",
    )


def _exit_level_for_pos(is_long: bool, lv: Any) -> float:
    return float(lv.exit_narrow if is_long else lv.exit_wide)


def _entry_level_for_pos(is_long: bool, lv: Any) -> float:
    """Уровень штатного входа: Long enter_narrow (3.2), Short enter_wide (6.2)."""
    return float(lv.enter_narrow if is_long else lv.enter_wide)


def _clear_entry_level_from_sit(sit: dict | None, lv: Any) -> float | None:
    """Уровень снятия блока: из state или по стороне ситуации."""
    if not sit:
        return None
    en = sit.get("entry_level")
    if en is not None:
        try:
            return float(en)
        except (TypeError, ValueError):
            pass
    side_s = str(sit.get("side") or "")
    if side_s.lower().startswith("l"):
        return float(lv.enter_narrow)
    if side_s.lower().startswith("s"):
        return float(lv.enter_wide)
    return None


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
                    if ((mtm - comm) / max(1.0, pos_notional)) * 100.0 >= tp:
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
    tatn: np.ndarray = field(default_factory=lambda: np.empty(0, dtype=np.float64))
    tatnp: np.ndarray = field(default_factory=lambda: np.empty(0, dtype=np.float64))


def with_weekend_trading_session(
    prep: PreparedTips, *, enabled: bool = False
) -> PreparedTips:
    """Return Test-only session/edges with Sat/Sun 10:00–18:59 MSK enabled."""
    if not enabled or prep.n <= 0:
        return prep

    session = np.array(prep.session, dtype=np.bool_, copy=True)
    for i, trade_date in enumerate(prep.trade_dates):
        if is_session_bar(trade_date, weekend_trading=True):
            s = str(trade_date or "").replace("T", " ").strip()
            try:
                y, mo, d = int(s[0:4]), int(s[5:7]), int(s[8:10])
            except (TypeError, ValueError):
                continue
            if datetime(y, mo, d).weekday() >= 5:
                session[i] = True

    dt = prep.ts_ms[1:] - prep.ts_ms[:-1]
    ok = (
        session[1:]
        & (dt == 60_000)
        & np.isfinite(prep.z[1:])
        & np.isfinite(prep.z[:-1])
    )
    edge_i = (np.flatnonzero(ok) + 1).astype(np.int32)
    return PreparedTips(
        ts_ms=prep.ts_ms,
        z=prep.z,
        spread=prep.spread,
        day_ord=prep.day_ord,
        session=session,
        trade_dates=prep.trade_dates,
        edge_i=edge_i,
        n=prep.n,
        tatn=prep.tatn,
        tatnp=prep.tatnp,
    )


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
    tatn_arr, tatnp_arr = _m1_leg_price_arrays(m1, n)
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
        tatn=tatn_arr,
        tatnp=tatnp_arr,
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
        # Хвост в десятки минуток не должен сносить 3-летний ряд в RAM/диске:
        # иначе Tesт «1 неделя» ждёт пересборку и упирается в таймаут 25 с.
        if int(len(add)) > 2000:
            _tip_cache["key"] = None
            _tip_cache["prep"] = None
            _tip_cache["mtime"] = None
            _sim_cache.clear()
            _hm_cache.clear()
            _invalidate_prep_disk_caches()
        try:
            from replay.shelf_floor_ceiling import clear_prep_shelf_caches

            clear_prep_shelf_caches()
        except Exception:
            pass
        _window_tip_cache["key"] = None
        _window_tip_cache["prep"] = None
        _window_tip_cache["meta"] = None
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


def _slice_1m_frame(
    df: pd.DataFrame,
    start_dt: datetime | None,
    end_dt: datetime | None,
) -> pd.DataFrame:
    out = df
    if start_dt is not None:
        s = start_dt.replace(tzinfo=None) if start_dt.tzinfo else start_dt
        out = out.loc[out["timestamp"] >= s]
    if end_dt is not None:
        e = end_dt.replace(tzinfo=None) if end_dt.tzinfo else end_dt
        out = out.loc[out["timestamp"] <= e + timedelta(days=1)]
    if out.empty:
        return out
    return out.reset_index(drop=True)


def _read_parquet_window(
    start_dt: datetime | None,
    end_dt: datetime | None,
) -> pd.DataFrame | None:
    """Окно 1м без загрузки всего 3-летнего parquet в RAM, если кэша ещё нет."""
    if not CACHE_1M.is_file():
        return None
    mtime = _parquet_mtime()
    cached = None
    with _lock:
        if (
            _parquet_frame_cache.get("df") is not None
            and _parquet_frame_cache.get("mtime") == mtime
        ):
            cached = _parquet_frame_cache["df"]
    if cached is not None:
        out = _slice_1m_frame(cached, start_dt, end_dt)
        return None if out.empty else out
    try:
        import pyarrow.parquet as pq

        filters: list[tuple[str, str, Any]] = []
        if start_dt is not None:
            s = start_dt.replace(tzinfo=None) if start_dt.tzinfo else start_dt
            filters.append(("timestamp", ">=", pd.Timestamp(s)))
        if end_dt is not None:
            e = end_dt.replace(tzinfo=None) if end_dt.tzinfo else end_dt
            filters.append(("timestamp", "<=", pd.Timestamp(e) + pd.Timedelta(days=1)))
        table = pq.read_table(str(CACHE_1M), filters=filters or None)
        df = table.to_pandas()
        df["timestamp"] = pd.to_datetime(df["timestamp"]).map(_naive_ts)
        if df.empty:
            return None
        return df.reset_index(drop=True)
    except Exception:
        return None


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
    if start_dt is not None or end_dt is not None:
        win = _read_parquet_window(start_dt, end_dt)
        if win is not None and not win.empty:
            return win
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
            tatn=np.asarray(getattr(prep, "tatn", np.empty(0)), dtype=np.float64),
            tatnp=np.asarray(getattr(prep, "tatnp", np.empty(0)), dtype=np.float64),
        )
    except Exception:
        pass


def _load_prep_disk(csv_name: str, key: str) -> PreparedTips | None:
    path = _prep_disk_path(csv_name)
    if not path.is_file():
        return None
    try:
        data = np.load(path, allow_pickle=False)
        stored = str(data["key"][0])
        td = [str(x) for x in data["trade_dates"].tolist()]
        tatn = (
            np.asarray(data["tatn"], dtype=np.float64)
            if "tatn" in data.files
            else np.empty(0, dtype=np.float64)
        )
        tatnp = (
            np.asarray(data["tatnp"], dtype=np.float64)
            if "tatnp" in data.files
            else np.empty(0, dtype=np.float64)
        )
        prep = PreparedTips(
            ts_ms=np.asarray(data["ts_ms"], dtype=np.int64),
            z=np.asarray(data["z"], dtype=np.float64),
            spread=np.asarray(data["spread"], dtype=np.float64),
            day_ord=np.asarray(data["day_ord"], dtype=np.int32),
            session=np.asarray(data["session"], dtype=np.bool_),
            trade_dates=td,
            edge_i=np.asarray(data["edge_i"], dtype=np.int32),
            n=int(len(data["ts_ms"])),
            tatn=tatn,
            tatnp=tatnp,
        )
        if not _prep_covers_named_lookback(csv_name, prep):
            log.info("tip prep disk truncated %s — rebuild", Path(csv_name).name)
            return None
        if key and stored != key:
            # Отдать вчерашний ряд, не глушить стол пересборкой 3 лет.
            log.info("tip prep disk stale %s — serving disk", Path(csv_name).name)
        return prep
    except Exception:
        return None


def _csv_first_ymd(csv_name: str) -> str | None:
    path = DATA_DIR / Path(csv_name).name
    try:
        with path.open("r", encoding="utf-8") as f:
            f.readline()
            first = f.readline().strip()
        if not first:
            return None
        return first.split(",", 1)[0].strip()[:10]
    except OSError:
        return None


def _prep_covers_named_lookback(csv_name: str, prep: PreparedTips) -> bool:
    """False если 3y npz собран с хвоста (2026-only) вместо CSV с 2023."""
    days = named_csv_lookback_days(csv_name)
    if days < 900 or prep is None or int(getattr(prep, "n", 0) or 0) <= 0:
        return True
    csv_first = _csv_first_ymd(csv_name)
    if not csv_first:
        return True
    prep_first = str(prep.trade_dates[0] if prep.trade_dates else "")[:10]
    if len(prep_first) < 10:
        return True
    try:
        cf = datetime.strptime(csv_first, "%Y-%m-%d").date()
        pf = datetime.strptime(prep_first, "%Y-%m-%d").date()
    except ValueError:
        return True
    return pf <= cf + timedelta(days=60)


def _light_tip_key(csv_name: str, m15: list[dict]) -> str:
    """Ключ без 1м: csv + окно M15. Не parquet mtime — иначе каждый хвост
    сносит npz и стол на 2 минуты собирает 3 года с нуля."""
    name = Path(csv_name).name
    first = m15[0]["tradeDate"] if m15 else ""
    days = named_csv_lookback_days(name)
    # 3y: не сбрасывать npz при каждом хвосте 255d (last/len меняются каждые 15м).
    if days >= 900:
        csv_path = DATA_DIR / name
        csv_mtime = csv_path.stat().st_mtime if csv_path.is_file() else 0.0
        return f"v{_PREP_CACHE_VERSION}|{name}|{days}|{first}|csv{csv_mtime:.0f}"
    last = m15[-1]["tradeDate"] if m15 else ""
    return f"v{_PREP_CACHE_VERSION}|{name}|{len(m15)}|{first}|{last}"


def kick_tip_prep_warm(*csv_names: str) -> bool:
    """Фоновый прогрев npz tip1m (3y cold-build ~2 мин — не на пути UI)."""
    names = [Path(str(n)).name for n in csv_names if str(n or "").strip()]
    if not names:
        return False

    def _run() -> None:
        for name in names:
            try:
                _, meta = ensure_tip_series(name)
                tier = meta.get("cacheTier")
                if tier == "build":
                    log.info("tip prep warm built %s (%.1fs)", name, meta.get("buildSec") or 0)
                else:
                    log.info("tip prep warm %s (%s)", name, tier)
            except Exception as exc:
                log.warning("tip prep warm failed %s: %s", name, exc)

    threading.Thread(target=_run, name="tip-prep-warm", daemon=True).start()
    return True


def _is_short_test_window(start: str | None, end: str | None) -> bool:
    span = window_span_days(start, end)
    return span is not None and 0 < span <= _SHORT_WINDOW_MAX_DAYS


def _peek_mem_tip(name: str) -> tuple[PreparedTips, dict[str, Any]] | None:
    """RAM-ряд без сверки mtime parquet — хвост минуток не должен глушить Tesт."""
    with _lock:
        if _tip_cache.get("csv") == name and _tip_cache.get("prep") is not None:
            meta = dict(_tip_cache["meta"] or {})
            meta["cacheHit"] = True
            meta["cacheTier"] = "mem"
            return _tip_cache["prep"], meta
    return None


def _prep_meta_from_disk(name: str, disk: PreparedTips, *, src: str = "disk") -> dict[str, Any]:
    return {
        "csv": name,
        "dataSourceM15": src,
        "dataSource1m": CACHE_1M.name,
        "m15Bars": 0,
        "m1Rows": disk.n,
        "tipPoints": disk.n,
        "edgeCount": int(len(disk.edge_i)),
        "m15From": None,
        "m15To": None,
        "m1From": disk.trade_dates[0] if disk.n else None,
        "m1To": disk.trade_dates[-1] if disk.n else None,
        "buildSec": 0.0,
        "cacheHit": True,
        "cacheTier": "disk",
        "mode": "tip1m",
        "logicRu": "касание порога на 1м tip-Z (не ждём close M15)",
    }


def _ensure_window_tip_series(
    csv_name: str, start: str | None, end: str | None
) -> tuple[PreparedTips, dict[str, Any]]:
    """Ряд только на окно Tesта + запас полки/Z. Не ждёт 3-летний индекс."""
    name = Path(csv_name).name
    lookback = max(int(Z_SCORE_ROLLING_LOOKBACK_DAYS), int(_WINDOW_LOOKBACK_DAYS))
    load_from = _ymd_shift(start, -lookback) or str(start or "")[:10]
    wkey = f"{name}|{start or ''}|{end or ''}|{lookback}"
    now = time.monotonic()
    with _window_build_lock:
        hit_prep = _window_tip_cache.get("prep")
        hit_key = _window_tip_cache.get("key")
        hit_at = float(_window_tip_cache.get("built_at") or 0.0)
        if (
            hit_prep is not None
            and hit_key == wkey
            and (now - hit_at) < _WINDOW_CACHE_TTL_SEC
        ):
            meta = dict(_window_tip_cache.get("meta") or {})
            meta["cacheHit"] = True
            meta["cacheTier"] = "window-mem"
            return hit_prep, meta

        m15, src = load_m15_ui(name, start_date=load_from or None)
        end_day = str(end or "").strip()[:10]
        if end_day:
            m15 = [b for b in m15 if str(b.get("tradeDate") or "")[:10] <= end_day]
        if not m15:
            raise ValueError(f"no M15 bars in window {start}→{end}")
        start_dt = _parse_td(f"{load_from} 00:00:00").replace(tzinfo=None)
        end_s = end_day or str(m15[-1]["tradeDate"])[:10]
        end_dt = _parse_td(f"{end_s} 23:59:59").replace(tzinfo=None)
        m1 = load_1m_from_cache(start_dt, end_dt, extend=False)
        t0 = time.time()
        prep = build_prepared_tips(m15, m1)
        meta = {
            "csv": name,
            "dataSourceM15": src,
            "dataSource1m": CACHE_1M.name,
            "m15Bars": len(m15),
            "m1Rows": int(len(m1)),
            "tipPoints": prep.n,
            "edgeCount": int(len(prep.edge_i)),
            "m15From": m15[0]["tradeDate"] if m15 else None,
            "m15To": m15[-1]["tradeDate"] if m15 else None,
            "m1From": str(m1["timestamp"].iloc[0]) if len(m1) else None,
            "m1To": str(m1["timestamp"].iloc[-1]) if len(m1) else None,
            "buildSec": round(time.time() - t0, 2),
            "cacheHit": False,
            "cacheTier": "window-build",
            "window": True,
            "windowLookbackDays": lookback,
            "mode": "tip1m",
            "logicRu": "касание порога на 1м tip-Z (не ждём close M15)",
        }
        _window_tip_cache["key"] = wkey
        _window_tip_cache["prep"] = prep
        _window_tip_cache["meta"] = meta
        _window_tip_cache["built_at"] = time.monotonic()
        return prep, meta


def _ymd_shift(ymd: str | None, days: int) -> str | None:
    if not ymd:
        return None
    try:
        d = datetime.strptime(str(ymd).strip()[:10], "%Y-%m-%d").date()
    except ValueError:
        return None
    return (d + timedelta(days=days)).isoformat()


def _ensure_full_tip_series(csv_name: str) -> tuple[PreparedTips, dict[str, Any]]:
    """Полный ряд CSV (3 года): RAM → npz, без обязательной загрузки всех M15."""
    name = Path(csv_name).name
    peeked = _peek_mem_tip(name)
    if peeked is not None:
        return peeked

    with _tip_build_lock:
        peeked = _peek_mem_tip(name)
        if peeked is not None:
            return peeked

        # Сначала диск.v3 — не тащить 3 года M15 из sqlite, пока Tesт ждёт.
        disk = _load_prep_disk(name, "")
        if disk is not None:
            meta = _prep_meta_from_disk(name, disk)
            with _lock:
                _tip_cache["key"] = str(_tip_cache.get("key") or "disk")
                _tip_cache["csv"] = name
                _tip_cache["mtime"] = _parquet_mtime()
                _tip_cache["prep"] = disk
                _tip_cache["built_at"] = time.time()
                _tip_cache["meta"] = meta
            return disk, meta

        m15, src = load_m15_ui(name)
        key = _light_tip_key(name, m15)
        mtime = _parquet_mtime()
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
                **_prep_meta_from_disk(name, disk, src=src),
                "m15Bars": len(m15),
                "m15From": m15[0]["tradeDate"] if m15 else None,
                "m15To": m15[-1]["tradeDate"] if m15 else None,
            }
            with _lock:
                _tip_cache["key"] = key
                _tip_cache["csv"] = name
                _tip_cache["mtime"] = mtime
                _tip_cache["prep"] = disk
                _tip_cache["built_at"] = time.time()
                _tip_cache["meta"] = meta
            return disk, meta

        def _m15_ms(b: dict[str, Any]) -> int:
            try:
                return int(b.get("timestampMs") or 0)
            except (TypeError, ValueError):
                return 0

        ordered = [b for b in m15 if _m15_ms(b) > 0]
        if not ordered:
            ordered = list(m15)
        first_b = min(ordered, key=_m15_ms)
        last_b = max(ordered, key=_m15_ms)
        end_dt = _parse_td(last_b["tradeDate"])
        start_dt = _parse_td(first_b["tradeDate"]) - timedelta(days=40)
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


def ensure_tip_series(
    csv_name: str,
    start: str | None = None,
    end: str | None = None,
) -> tuple[PreparedTips, dict[str, Any]]:
    """Ряд tip1m: короткое окно Tesта — срез parquet; иначе полный CSV (RAM/npz)."""
    name = Path(csv_name).name
    if _is_short_test_window(start, end):
        return _ensure_window_tip_series(name, start, end)
    return _ensure_full_tip_series(name)


def named_csv_lookback_days(csv_name: str, default: int = 255) -> int:
    """Дни истории из имени `m15_tatn_{N}d.csv`."""
    m = _LOOKBACK_FROM_NAME.search(Path(str(csv_name or "")).name)
    if m:
        return max(1, int(m.group(1)))
    return default


def window_span_days(start: str | None, end: str | None) -> int | None:
    """Календарные дни окна start→end (конец включительно). None — нет старта."""
    if not start:
        return None
    try:
        sd = datetime.strptime(str(start).strip()[:10], "%Y-%m-%d").date()
    except ValueError:
        return None
    ed: date
    if end:
        try:
            ed = datetime.strptime(str(end).strip()[:10], "%Y-%m-%d").date()
        except ValueError:
            ed = datetime.now(tz=MSK).date()
    else:
        ed = datetime.now(tz=MSK).date()
    if ed < sd:
        ed = sd
    return (ed - sd).days + 1


def resolve_csv_for_window(
    csv: str,
    start: str | None = None,
    end: str | None = None,
) -> str:
    """Файл M15, который покрывает выбранное окно. Короткий CSV не режет 3 года.

    Не укорачивает: 1095d + окно «год» остаётся 1095d (фильтр start/end).
    Тестовый 3д не трогаем.
    """
    name = Path(str(csv or CSV_255)).name or CSV_255
    if name == CSV_TEST_3D:
        return name
    need = window_span_days(start, end)
    if need is None:
        return name
    have = named_csv_lookback_days(name)
    slack = _CSV_COVER_SLACK_DAYS
    if have >= need - slack:
        return name
    for fname, days in _CSV_LOOKBACK_CANDIDATES:
        if days >= need - slack:
            return fname
    return CSV_1095


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


def _window_end_ms(end: str | None) -> int:
    """Inclusive end-of-day (MSK) for YYYY-MM-DD; 0 = без верхней границы."""
    if not end:
        return 0
    s = str(end).strip()
    if len(s) == 10:
        s = s + " 23:59:59"
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


def _edges_from(
    prep: PreparedTips, window_start_ms: int, window_end_ms: int = 0
) -> np.ndarray:
    """Edge indices with tip ts in [start, end] (end inclusive via end-of-day ms)."""
    edges = prep.edge_i
    if len(edges) == 0:
        return edges
    if window_start_ms <= 0 and window_end_ms <= 0:
        return edges
    ts_e = prep.ts_ms[edges]
    lo = 0
    if window_start_ms > 0:
        lo = int(np.searchsorted(ts_e, window_start_ms, side="left"))
    hi = len(edges)
    if window_end_ms > 0:
        hi = int(np.searchsorted(ts_e, window_end_ms, side="right"))
    if lo <= 0 and hi >= len(edges):
        return edges
    return edges[lo:hi]


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
    window_end_ms: int = 0,
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
        if window_end_ms > 0 and ms > window_end_ms:
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
    window_end_ms: int = 0,
    compound: bool = False,
    slip: float = DEFAULT_SLIP,
    notional: float = DEFAULT_NOTIONAL,
    take_profit_pct: float = 0.0,
    max_hold_days_if_losing: float = 0.0,
    max_hold_days_no_exit_trend: float = 0.0,
    force_close_3d: bool = False,
    force_close_3d_mode: str = "force",
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
    ``force_close_3d``: ситуация 3D (см. ``live.force_close_3d``).
    ``force_close_3d_mode``: ``indicator`` (не резать, блок входа) | ``force`` (срез).

    Geometric path applies Prod tip1m settle (``MONITOR_TIP1M_SETTLE_SEC``) so the
    forming last tip is not actionable until close+settle (or next tip present).
    """
    from live.constants import MONITOR_TIP1M_SETTLE_SEC
    from live.force_close_3d import normalize_mode as _norm_3d_mode
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
    mode_3d = _norm_3d_mode(force_close_3d_mode)

    z = prep.z
    sp_arr = prep.spread
    day_ord = prep.day_ord
    dates = prep.trade_dates
    edges = _edges_from(prep, window_start_ms, window_end_ms)
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
    open_size: dict[str, Any] | None = None
    use_tp = take_profit_pct > 0
    hold_lose_days = int(max_hold_days_if_losing) if max_hold_days_if_losing > 0 else 0
    hold_no_trend_days = (
        int(max_hold_days_no_exit_trend) if max_hold_days_no_exit_trend > 0 else 0
    )
    use_close_3d = bool(force_close_3d)
    sit_3d: dict[str, Any] | None = None
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
        nonlocal entry_slip_used, open_meta, locked_exit, open_lock, open_size
        if _entry_blocked_3d(sit_3d, want_long=(sig == 1)):
            return
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
        tn_ov = _fmeta(merged_meta.get("entry_tatn"))
        tp_ov = _fmeta(merged_meta.get("entry_tatnp"))
        tn_p, tp_p = _prep_leg_prices(prep, i)
        if lots_v is not None and int(lots_v) > 0 and eff_nom is not None and float(eff_nom) > 0:
            sz = {
                "lots": int(lots_v),
                "execution_notional": float(eff_nom),
                "deposit": float(eff_nom) / LEVERAGE,
                "legacy": False,
                "price_tatn": tn_ov if tn_ov else tn_p,
                "price_tatnp": tp_ov if tp_ov else tp_p,
            }
        else:
            sz = size_test_spread_lots(
                deposit_rub=pos_notional,
                price_tatn=tn_p,
                price_tatnp=tp_p,
                direction=direction,
            )
            if sz.get("lots") == 0:
                return
        open_size = sz
        if not sz.get("legacy") and sz.get("deposit"):
            pos_notional = float(sz["deposit"])
        eff, comm, ovn_day = _fees_for_sized_open(
            deposit=pos_notional, direction=direction, sz=sz
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
        chist0 = mtm - comm
        pnl_min = chist0
        pnl_max = chist0
        hit1_td = hit2_td = hit3_td = None

    def _close(i: int, reason: str, meta: dict[str, Any] | None = None) -> None:
        nonlocal pos, realized, total_pnl, peak, max_dd, pnl_min, pnl_max
        nonlocal hit1_td, hit2_td, hit3_td, open_meta, open_lock, open_size
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
        closed_trades[-1].update(_lot_trade_fields(open_size))
        pos = 0
        open_meta = None
        open_size = None
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
                chist = mtm - comm  # как «Чист.»: ещё комиссия выхода
                if chist < pnl_min:
                    pnl_min = chist
                if chist > pnl_max:
                    pnl_max = chist
                pct = (chist / max(1.0, pos_notional)) * 100.0
                td = dates[i]
                if hit1_td is None and pct >= 1.0:
                    hit1_td = td
                if hit2_td is None and pct >= 2.0:
                    hit2_td = td
                if hit3_td is None and pct >= 3.0:
                    hit3_td = td
                # ТП Теста = Чист.% / депозит ≥ ТП%.
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
                chist = mtm - comm  # Hit/Max/ТП — как «Чист.»
                if chist < pnl_min:
                    pnl_min = chist
                if chist > pnl_max:
                    pnl_max = chist
                # Первый бар, где Чист.% ≥ 1%/2%/3% депозита.
                pct = (chist / max(1.0, pos_notional)) * 100.0
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
                held = int(day_ord[i]) - entry_day
                if use_close_3d:
                    is_long_h = pos == 1
                    exit_lv_3d = _exit_level_for_pos(is_long_h, lv)
                    entry_lv_3d = _entry_level_for_pos(is_long_h, lv)
                    sit_3d, force_now = _situation_3d_on_bar(
                        sit_3d,
                        enabled=True,
                        mode=mode_3d,
                        flat=False,
                        is_long=is_long_h,
                        held=held,
                        entry_sp=entry_sp,
                        cur_sp=float(sp_arr[i]),
                        exit_lv=exit_lv_3d,
                        entry_lv=entry_lv_3d,
                        mtm=mtm,
                    )
                    if force_now:
                        _close(i, "force_close_3d")
                        continue
                if hold_lose_days > 0:
                    if held >= hold_lose_days and mtm < 0:
                        _close(i, "hold_losing")
                        continue
                if hold_no_trend_days > 0:
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
            if use_close_3d and pos == 0 and sit_3d is not None:
                # Flat: снятие ситуации по возврату к зоне входа.
                en_clr = _clear_entry_level_from_sit(sit_3d, lv)
                sit_3d, _ = _situation_3d_on_bar(
                    sit_3d,
                    enabled=True,
                    mode=mode_3d,
                    flat=True,
                    is_long=None,
                    held=None,
                    entry_sp=None,
                    cur_sp=cur_sp,
                    exit_lv=None,
                    entry_lv=en_clr,
                    mtm=None,
                )
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
        chist = mtm - comm
        pmin = chist if not math.isfinite(pnl_min) else min(pnl_min, chist)
        pmax = chist if not math.isfinite(pnl_max) else max(pnl_max, chist)
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
                "tag": "main",
                "wallet": WALLET_MAIN,
                "mtm": round(mtm, 4),
                "openMtm": round(mtm, 4),
                "prodId": (open_meta or {}).get("prod_id") if open_meta else None,
            }
        )
        trades_out[-1].update(_lot_trade_fields(open_size))
    return apply_journal_account_chain({
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
            "forceClose3d": bool(use_close_3d),
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
    })


_MS_DAY = 24 * 60 * 60 * 1000
_MS_6H = 6 * 60 * 60 * 1000
_WEAK_ENTRY_PP = 0.15
_AGAINST_PP = 0.20
_NO_PROGRESS_PP = 0.20


def ui_trade_risk_score(
    *,
    direction: str,
    entry_td: str,
    now_td: str,
    entry_spread: float | None,
    now_spread: float | None,
    overnight_rub: float,
    levels: dict[str, float] | None = None,
) -> dict[str, Any]:
    """Баллы риска как в Тесте (replay-sim.js assessTradeRisk), на момент now_td."""
    flags: list[str] = []
    hold_pts = ovn_pts = weak_pts = hour_pts = fri_pts = path_pts = 0
    entry_dt = now_dt = None
    try:
        if entry_td:
            entry_dt = _parse_td(str(entry_td))
    except ValueError:
        entry_dt = None
    try:
        nt = str(now_td or "").strip()
        if nt and nt not in ("—", "-", "None"):
            now_dt = _parse_td(nt)
    except ValueError:
        now_dt = None
    duration_ms = None
    if entry_dt is not None and now_dt is not None:
        duration_ms = int((now_dt - entry_dt).total_seconds() * 1000)
    hold_h = (duration_ms / 3_600_000.0) if duration_ms is not None else 0.0

    if duration_ms is not None and duration_ms > 5 * _MS_DAY:
        flags.append(">5д")
        hold_pts = 4
    elif duration_ms is not None and duration_ms > 2 * _MS_DAY:
        flags.append(">2д")
        hold_pts = 3

    ovn = float(overnight_rub or 0.0)
    if ovn > 100:
        flags.append("Ovn100")
        ovn_pts = 2
    elif ovn > 50 and duration_ms is not None and duration_ms > _MS_DAY:
        flags.append("Ovn50")
        ovn_pts = 2

    d = str(direction or "")
    du = d.upper()
    is_short = "SHORT" in du
    is_long = (not is_short) and ("LONG" in du)
    lv = levels or {}
    enter_wide = float(lv.get("enter_wide") or 6.2)
    enter_narrow = float(lv.get("enter_narrow") or 3.2)
    try:
        e = float(entry_spread) if entry_spread is not None else None
    except (TypeError, ValueError):
        e = None
    try:
        n = float(now_spread) if now_spread is not None else None
    except (TypeError, ValueError):
        n = None
    if e is not None and not math.isfinite(e):
        e = None
    if n is not None and not math.isfinite(n):
        n = None
    depth = None
    progress = None
    if e is not None and (is_long or is_short):
        depth = (enter_narrow - e) if is_long else (e - enter_wide)
    if e is not None and n is not None and (is_long or is_short):
        progress = (n - e) if is_long else (e - n)
    if depth is not None and 0 <= depth < _WEAK_ENTRY_PP and hold_h >= 6:
        flags.append("S≈вход")
        weak_pts = 1
    if progress is not None and progress <= -_AGAINST_PP:
        flags.append("S против")
        path_pts = 2
    elif progress is not None and progress < _NO_PROGRESS_PP and hold_h >= 24:
        flags.append("нет хода")
        path_pts = 1

    if duration_ms is not None and duration_ms > _MS_6H and entry_dt is not None:
        h = int(entry_dt.hour)
        if h == 13:
            flags.append("13ч")
            hour_pts = 1
        elif 12 <= h <= 14:
            flags.append("12–14")
            hour_pts = 1
    if duration_ms is not None and duration_ms > 2 * _MS_DAY and entry_dt is not None:
        if entry_dt.weekday() == 4:
            flags.append("Пт>2д")
            fri_pts = 1

    score = hold_pts + ovn_pts + weak_pts + hour_pts + fri_pts + path_pts
    level = (
        "Critical" if score >= 6 else "High" if score >= 4 else "Elevated" if score >= 3 else "None"
    )
    return {
        "flags": flags,
        "flagsText": " ".join(flags) if flags else "—",
        "score": score,
        "level": level,
        "isRed": score >= 4,
        "holdHours": round(hold_h, 2),
        "progress": None if progress is None else round(progress, 4),
        "depth": None if depth is None else round(depth, 4),
    }


def parse_base_mode(raw: Any) -> bool:
    """Тест «База»: AUTO-ноги 3.2/6.1. По умолчанию ВКЛ.

    Выкл — не открывать новые базовые ноги. Добор/экстра по текущим правилам
    только при открытой базе; полка может жить отдельно (кошелёк базы).
    """
    if raw is None:
        return True
    if isinstance(raw, str):
        s = raw.strip().lower()
        if s in ("0", "false", "no", "off", "none", "выкл", ""):
            return False
        if s in ("1", "true", "yes", "on", "base", "база"):
            return True
        return True
    return bool(raw)


def parse_addon_mode(raw: Any) -> bool:
    """Тест «вариант 2 / добор 2/7». По умолчанию выкл."""
    if raw is None:
        return False
    if isinstance(raw, str):
        return raw.strip().lower() in (
            "1",
            "true",
            "yes",
            "on",
            "addon",
            "v2",
            "variant2",
            "вариант2",
        )
    return bool(raw)


def parse_extreme_addon_mode(raw: Any) -> bool:
    """Тест «экстра 1/9»: добор на хвостах S≤1 / S≥9 при открытой базе."""
    if raw is None:
        return False
    if isinstance(raw, str):
        return raw.strip().lower() in (
            "1",
            "true",
            "yes",
            "on",
            "extra",
            "extreme",
            "extra_addon",
            "экстра",
            "1/9",
        )
    return bool(raw)


def parse_transition_swing_mode(raw: Any) -> bool:
    """Тест «коридор»: ping-pong в коричневой зоне L вых…S вых после выхода базы."""
    if raw is None:
        return False
    if isinstance(raw, str):
        return raw.strip().lower() in (
            "1",
            "true",
            "yes",
            "on",
            "swing",
            "corridor",
            "transition",
            "zone",
            "коридор",
            "переход",
        )
    return bool(raw)


def run_base_plus_addon(
    prep: PreparedTips,
    *,
    slip: float = DEFAULT_SLIP,
    notional: float = DEFAULT_NOTIONAL,
    compound: bool = False,
    window_start_ms: int = 0,
    window_end_ms: int = 0,
    spread_levels: dict[str, float] | None = None,
    take_profit_pct: float = 0.0,
    max_hold_days_if_losing: float = 0.0,
    max_hold_days_no_exit_trend: float = 0.0,
    force_close_3d: bool = False,
    force_close_3d_mode: str = "force",
    exit_trend_min_pp: float = 0.2,
    now_ms: int | None = None,
    settle_sec: float | None = None,
    enable_addon: bool = True,
    enable_extreme: bool = False,
    enable_base: bool = True,
    main_notional: float | None = None,
    addon_notional: float | None = None,
    extra_notional: float | None = None,
    leg_deposit_fn: Any | None = None,
    risk_policy: dict[str, Any] | None = None,
    enable_shelf_ff: bool = False,
    shelf_by_date: dict[str, dict[str, Any]] | None = None,
    levels_by_date: dict[str, dict[str, float]] | None = None,
    trail_arm_pct: float = 0.0,
    trail_stop_pct: float = 0.0,
) -> dict[str, Any]:
    """База tip1m + опционально добор 2/7 и/или экстра 1/9, пока база открыта.

    Main: уровни спреда (по умолчанию Short 6.2/5.8 · Long 3.2/4.0).
    Addon 2/7: Long касание 2% → выход 3.2; Short касание 7% → выход 6.2.
    Extra 1/9: Long в зоне ≤1 → выход 2; Short ≥9 → выход 7.
    Экстра не связана с касанием базы/добора: если уже есть другая нога той же
    стороны, вход по зоне (в том числе на том же баре, что база).
    При ``compound`` размер ноги = кошелёк этой ноги + её закрытый PnL.
    Пол–потолок берёт кошелёк базы; если база открыта — сначала выход базы.
    ``enable_base`` False: не открывать новые AUTO-ноги (main / 3.2/6.1);
    добор и экстра без открытой базы не входят; полка живёт отдельно.
    ``trail_arm_pct`` / ``trail_stop_pct``: эксперимент — вместо фикс. ТП
    (trailing stop = скользящий стоп). Пик = max MTM% с входа; после arm
    выход, если откат от пика ≥ trail п.п. Не Prod AUTO.
    """
    from live.constants import MONITOR_TIP1M_SETTLE_SEC
    from live.force_close_3d import normalize_mode as _norm_3d_mode
    from live.signals import Position as _Pos
    from live.spread_levels import (
        SpreadLevels,
        determine_spread_level_signal,
        levels_from_settings,
    )

    use_addon = bool(enable_addon)
    use_extreme = bool(enable_extreme)
    use_shelf = bool(enable_shelf_ff)
    use_base = bool(enable_base)
    if not use_addon and not use_extreme and not use_shelf:
        use_addon = bool(use_base)

    lv = levels_from_settings(spread_levels) if spread_levels else SpreadLevels()
    mode_3d = _norm_3d_mode(force_close_3d_mode)
    x_enter_l = float(ADDON_ENTER_NARROW)
    x_exit_l = float(ADDON_EXIT_NARROW)
    x_enter_s = float(ADDON_ENTER_WIDE)
    x_exit_s = float(ADDON_EXIT_WIDE)
    e_enter_l = float(EXTRA_ENTER_NARROW)
    e_exit_l = float(EXTRA_EXIT_NARROW)
    e_enter_s = float(EXTRA_ENTER_WIDE)
    e_exit_s = float(EXTRA_EXIT_WIDE)
    settle = MONITOR_TIP1M_SETTLE_SEC if settle_sec is None else float(settle_sec)
    base_edges = _edges_tip1m_settled(
        prep,
        _edges_from(prep, window_start_ms, window_end_ms),
        now_ms=now_ms,
        settle_sec=settle,
    )
    edge_set = {int(i) for i in base_edges}
    if use_shelf:
        from replay.shelf_floor_ceiling import extend_shelf_bar_indices

        edges = extend_shelf_bar_indices(
            prep,
            base_edges,
            window_start_ms=window_start_ms,
            window_end_ms=window_end_ms,
            now_ms=now_ms,
            settle_sec=settle,
        )
    else:
        edges = base_edges
    sp_arr = prep.spread
    z_arr = prep.z
    dates = prep.trade_dates
    day_ord = prep.day_ord
    base = float(notional)
    dep_main = float(main_notional if main_notional is not None else notional)
    dep_addon = float(addon_notional if addon_notional is not None else notional)
    dep_extra = float(extra_notional if extra_notional is not None else notional)
    wallets = {
        WALLET_MAIN: dep_main,
        WALLET_ADDON: dep_addon,
        WALLET_EXTRA: dep_extra,
    }
    realized_by = {WALLET_MAIN: 0.0, WALLET_ADDON: 0.0, WALLET_EXTRA: 0.0}
    use_compound = bool(compound)
    shelf_causal: dict[str, dict[str, Any]] = {}
    if use_shelf:
        from replay.shelf_floor_ceiling import causal_by_date_from_prep

        shelf_causal = (
            dict(shelf_by_date)
            if shelf_by_date is not None
            else causal_by_date_from_prep(prep)
        )
    use_tp = take_profit_pct > 0
    trail_arm = max(0.0, float(trail_arm_pct or 0.0))
    trail_stop = max(0.0, float(trail_stop_pct or 0.0))
    use_trail = trail_arm > 0.0 and trail_stop > 0.0
    hold_lose_days = int(max_hold_days_if_losing) if max_hold_days_if_losing > 0 else 0
    hold_no_trend_days = (
        int(max_hold_days_no_exit_trend) if max_hold_days_no_exit_trend > 0 else 0
    )
    use_close_3d = bool(force_close_3d)
    sit_3d: dict[str, Any] | None = None
    trend_min_pp = max(0.0, float(exit_trend_min_pp))
    pol = dict(risk_policy) if risk_policy else {}
    closed_trades: list[dict[str, Any]] = []
    peak = max_dd = total_pnl = 0.0
    trade_no = 0
    addon_opens = 0
    extra_opens = 0
    peak_legs = 0
    peak_notional = 0.0

    def _wallet_of(tag: str) -> str:
        if tag == "addon":
            return WALLET_ADDON
        if tag == "extra":
            return WALLET_EXTRA
        return WALLET_MAIN

    def _leg_notional(tag: str = "main") -> float:
        if leg_deposit_fn is not None:
            used = 0.0
            if main.pos:
                used += float(main.pos_notional or 0.0)
            if add.pos:
                used += float(add.pos_notional or 0.0)
            if ext.pos:
                used += float(ext.pos_notional or 0.0)
            if shelf.pos:
                used += float(shelf.pos_notional or 0.0)
            raw = leg_deposit_fn(
                tag,
                {
                    "used": used,
                    "main_open": bool(main.pos),
                    "addon_open": bool(add.pos),
                    "extra_open": bool(ext.pos),
                    "shelf_open": bool(shelf.pos),
                    "dep_main": dep_main,
                    "dep_addon": dep_addon,
                    "dep_extra": dep_extra,
                    "realized_by": dict(realized_by),
                    "compound": use_compound,
                },
            )
            try:
                v = float(raw)
            except (TypeError, ValueError):
                return max(1.0, dep_main)
            if not math.isfinite(v) or v <= 0:
                return 0.0
            return v
        key = _wallet_of(tag)
        if key == WALLET_ADDON:
            dep = dep_addon
        elif key == WALLET_EXTRA:
            dep = dep_extra
        else:
            dep = dep_main
        return max(1.0, dep + realized_by[key]) if use_compound else dep

    def _source_for_tag(tag: str) -> str:
        if tag == "addon":
            return "добор"
        if tag == "extra":
            return "экстра"
        if tag == "shelf_ff":
            return "пол–потолок"
        return "база"

    class _Leg:
        __slots__ = (
            "pos",
            "entry_sp",
            "entry_td",
            "entry_z",
            "entry_day",
            "entry_comm",
            "eff",
            "comm",
            "ovn",
            "tag",
            "pos_notional",
            "pnl_min",
            "pnl_max",
            "hit1_td",
            "hit2_td",
            "hit3_td",
            "exit_long",
            "exit_short",
            "index",
            "lots",
            "size",
            "trail_peak_pct",
            "trail_armed",
        )

        def __init__(self) -> None:
            self.pos = 0
            self.entry_sp = 0.0
            self.entry_td = ""
            self.entry_z = 0.0
            self.entry_day = 0
            self.entry_comm = 0.0
            self.eff = 0.0
            self.comm = 0.0
            self.ovn = 0.0
            self.tag = ""
            self.pos_notional = base
            self.pnl_min = float("inf")
            self.pnl_max = float("-inf")
            self.hit1_td = None
            self.hit2_td = None
            self.hit3_td = None
            self.exit_long = float(lv.exit_narrow)
            self.exit_short = float(lv.exit_wide)
            self.index = 0
            self.lots = None
            self.size = None
            self.trail_peak_pct = float("-inf")
            self.trail_armed = False

        def open(self, sig: int, i: int, *, tag: str, exit_long: float, exit_short: float) -> bool:
            nonlocal trade_no, peak_notional
            direction = "LONG" if sig == 1 else "SHORT"
            tip = float(sp_arr[i])
            pos_n = _leg_notional(tag)
            if pos_n <= 0:
                return False
            tn, tp = _prep_leg_prices(prep, i)
            sz = size_test_spread_lots(
                deposit_rub=pos_n, price_tatn=tn, price_tatnp=tp, direction=direction
            )
            if sz.get("lots") == 0:
                return False
            self.eff, self.comm, self.ovn = _fees_for_sized_open(
                deposit=pos_n, direction=direction, sz=sz
            )
            self.entry_comm = self.comm
            self.pos_notional = float(sz.get("deposit") or pos_n)
            self.lots = sz.get("lots")
            self.size = sz
            self.entry_sp = tip + slip if sig == 1 else tip - slip
            self.entry_td = dates[i]
            self.entry_z = float(z_arr[i]) if math.isfinite(float(z_arr[i])) else 0.0
            self.entry_day = int(day_ord[i])
            self.pos = sig
            self.tag = tag
            self.exit_long = float(exit_long)
            self.exit_short = float(exit_short)
            trade_no += 1
            self.index = trade_no
            mtm = self.mtm_at(i)
            self.pnl_min = mtm - self.comm
            self.pnl_max = mtm - self.comm
            self.hit1_td = self.hit2_td = self.hit3_td = None
            self.trail_peak_pct = float("-inf")
            self.trail_armed = False
            open_n = (main.pos_notional if main.pos else 0.0) + (
                add.pos_notional if add.pos else 0.0
            ) + (ext.pos_notional if ext.pos else 0.0) + (
                shelf.pos_notional if shelf.pos else 0.0
            )
            if open_n > peak_notional:
                peak_notional = open_n
            return True

        def mtm_at(self, i: int) -> float:
            is_long = self.pos == 1
            sp = float(sp_arr[i])
            pnl_pts = (sp - self.entry_sp) if is_long else (self.entry_sp - sp)
            gross = self.eff * (pnl_pts / 100.0)
            ovn = self.ovn * max(0, int(day_ord[i]) - self.entry_day)
            return gross - self.entry_comm - ovn

        def track_mtm(self, i: int) -> float:
            mtm = self.mtm_at(i)
            chist = mtm - self.comm  # Hit/Max как «Чист.»
            if chist < self.pnl_min:
                self.pnl_min = chist
            if chist > self.pnl_max:
                self.pnl_max = chist
            pct = (chist / max(1.0, self.pos_notional)) * 100.0
            td = dates[i]
            if self.hit1_td is None and pct >= 1.0:
                self.hit1_td = td
            if self.hit2_td is None and pct >= 2.0:
                self.hit2_td = td
            if self.hit3_td is None and pct >= 3.0:
                self.hit3_td = td
            return mtm

        def toward_exit(self, i: int) -> bool:
            cur_sp = float(sp_arr[i])
            if self.pos == 1:
                toward = (cur_sp - self.entry_sp) >= trend_min_pp
                if not toward:
                    exit_lv = self.exit_long
                    toward = (exit_lv - cur_sp) <= (exit_lv - self.entry_sp) - trend_min_pp
                return toward
            toward = (self.entry_sp - cur_sp) >= trend_min_pp
            if not toward:
                exit_lv = self.exit_short
                toward = (cur_sp - exit_lv) <= (self.entry_sp - exit_lv) - trend_min_pp
            return toward

        def close_at(self, i: int, reason: str) -> dict[str, Any]:
            nonlocal total_pnl, peak, max_dd
            is_long = self.pos == 1
            tip = float(sp_arr[i])
            exit_sp = tip - slip if is_long else tip + slip
            pnl_pts = (exit_sp - self.entry_sp) if is_long else (self.entry_sp - exit_sp)
            gross = self.eff * (pnl_pts / 100.0)
            ovn = self.ovn * max(0, int(day_ord[i]) - self.entry_day)
            comm_total = self.entry_comm + self.comm
            net = gross - comm_total - ovn
            total_pnl += net
            wkey = _wallet_of(self.tag)
            realized_by[wkey] += net
            if total_pnl > peak:
                peak = total_pnl
            max_dd = max(max_dd, peak - total_pnl)
            pmin = net if not math.isfinite(self.pnl_min) else min(self.pnl_min, net)
            pmax = net if not math.isfinite(self.pnl_max) else max(self.pnl_max, net)
            self.trail_peak_pct = float("-inf")
            self.trail_armed = False
            row = {
                "index": self.index,
                "direction": "Long" if is_long else "Short",
                "entryDate": self.entry_td,
                "exitDate": dates[i],
                "entryZ": round(self.entry_z, 4),
                "exitZ": round(float(z_arr[i]), 4) if math.isfinite(float(z_arr[i])) else None,
                "entrySpread": round(self.entry_sp, 6),
                "exitSpread": round(exit_sp, 6),
                "entrySlip": round(float(slip), 6),
                "pnlPts": round(pnl_pts, 6),
                "gross": round(float(gross), 4),
                "commission": round(float(comm_total), 4),
                "overnight": round(float(ovn), 4),
                "net": round(net, 4),
                "modelNet": round(net, 4),
                "netFromAccount": False,
                "pnlMin": round(pmin, 4),
                "pnlMax": round(pmax, 4),
                "hit1Date": self.hit1_td,
                "hit2Date": self.hit2_td,
                "hit3Date": self.hit3_td,
                "accountBefore": None,
                "accountAfter": None,
                "status": "Закрыта",
                "exitReason": reason,
                "notional": round(self.pos_notional, 2),
                "tag": self.tag,
                "source": _source_for_tag(self.tag),
                "wallet": wkey,
            }
            row.update(_lot_trade_fields(self.size))
            self.pos = 0
            self.tag = ""
            self.lots = None
            self.size = None
            self.pnl_min = float("inf")
            self.pnl_max = float("-inf")
            self.hit1_td = self.hit2_td = self.hit3_td = None
            return row

        def try_level_exit(self, i: int, *, reason: str) -> dict[str, Any] | None:
            if self.pos == 0:
                return None
            prev = float(sp_arr[i - 1])
            cur = float(sp_arr[i])
            if self.pos == 1:
                if not (prev < self.exit_long and cur >= self.exit_long):
                    return None
            elif not (prev > self.exit_short and cur <= self.exit_short):
                return None
            return self.close_at(i, reason)

        def snapshot_open(self, last_i: int) -> dict[str, Any]:
            mtm = self.mtm_at(last_i) if last_i >= 0 else 0.0
            chist = mtm - self.comm
            pmin = chist if not math.isfinite(self.pnl_min) else min(self.pnl_min, chist)
            pmax = chist if not math.isfinite(self.pnl_max) else max(self.pnl_max, chist)
            z_last = float(z_arr[last_i]) if last_i >= 0 else 0.0
            row = {
                "index": self.index,
                "direction": "Long" if self.pos == 1 else "Short",
                "entryDate": self.entry_td,
                "exitDate": "—",
                "entryZ": round(self.entry_z, 4),
                "exitZ": round(z_last, 4) if math.isfinite(z_last) else None,
                "entrySpread": round(self.entry_sp, 6),
                "exitSpread": None,
                "entrySlip": round(float(slip), 6),
                "pnlPts": None,
                "gross": None,
                "commission": None,
                "overnight": None,
                "net": None,
                "modelNet": None,
                "netFromAccount": False,
                "pnlMin": round(pmin, 4),
                "pnlMax": round(pmax, 4),
                "hit1Date": self.hit1_td,
                "hit2Date": self.hit2_td,
                "hit3Date": self.hit3_td,
                "accountAfter": None,
                "status": "Открыта",
                "exitReason": None,
                "notional": round(self.pos_notional, 2),
                "tag": self.tag,
                "source": _source_for_tag(self.tag),
                "wallet": _wallet_of(self.tag),
                "mtm": round(mtm, 4),
                "openMtm": round(mtm, 4),
            }
            row.update(_lot_trade_fields(self.size))
            return row

    main = _Leg()
    add = _Leg()
    ext = _Leg()
    shelf = _Leg()

    def _skip_main_open(sig: int, i: int) -> bool:
        if not use_base:
            return True
        if not pol:
            return False
        tip = float(sp_arr[i])
        fill = tip + slip if sig == 1 else tip - slip
        td = dates[i]
        if pol.get("skip_weak_entry"):
            depth = (float(lv.enter_narrow) - fill) if sig == 1 else (fill - float(lv.enter_wide))
            if 0 <= depth < _WEAK_ENTRY_PP:
                return True
        if pol.get("skip_friday") or pol.get("skip_hour_12_14"):
            try:
                dt = _parse_td(td)
            except ValueError:
                dt = None
            if dt is not None:
                if pol.get("skip_friday") and dt.weekday() == 4:
                    return True
                if pol.get("skip_hour_12_14") and 12 <= int(dt.hour) <= 14:
                    return True
        return False

    def _maybe_risk_close(leg: _Leg, i: int) -> bool:
        if not pol or leg.pos == 0:
            return False
        cur_sp = float(sp_arr[i])
        is_long = leg.pos == 1
        progress = (cur_sp - leg.entry_sp) if is_long else (leg.entry_sp - cur_sp)
        held_cal = int(day_ord[i]) - int(leg.entry_day)
        ovn_so_far = float(leg.ovn) * max(0, held_cal)
        if pol.get("exit_spread_against") and progress <= -_AGAINST_PP:
            closed_trades.append(leg.close_at(i, "risk_against"))
            return True
        ovn_lim = pol.get("exit_ovn_gt")
        if ovn_lim is not None and ovn_so_far > float(ovn_lim):
            closed_trades.append(leg.close_at(i, "risk_ovn"))
            return True
        max_hold_h = pol.get("max_hold_hours")
        if max_hold_h is not None:
            hours = None
            try:
                e = _parse_td(leg.entry_td)
                n = _parse_td(dates[i])
                hours = (n - e).total_seconds() / 3600.0
            except ValueError:
                hours = float(held_cal) * 24.0
            if hours is not None and hours > float(max_hold_h):
                closed_trades.append(leg.close_at(i, "risk_hold"))
                return True
        score_ge = pol.get("exit_score_ge")
        if score_ge is not None:
            scored = ui_trade_risk_score(
                direction="Long" if is_long else "Short",
                entry_td=leg.entry_td,
                now_td=dates[i],
                entry_spread=leg.entry_sp,
                now_spread=cur_sp,
                overnight_rub=ovn_so_far,
                levels=lv.as_dict(),
            )
            if int(scored["score"]) >= int(score_ge):
                closed_trades.append(leg.close_at(i, "risk_red"))
                return True
        return False

    def _maybe_stops(leg: _Leg, i: int) -> bool:
        if leg.pos == 0:
            return False
        mtm = leg.track_mtm(i)
        pct = (mtm / max(1.0, leg.pos_notional)) * 100.0
        # Скользящий стоп (эксперимент): вместо фикс. ТП, в т.ч. для добора/экстра.
        # Пик = max MTM% с момента входа; arm при pct≥arm; выход при откате от пика ≥trail.
        if use_trail:
            if pct > leg.trail_peak_pct:
                leg.trail_peak_pct = pct
            if not leg.trail_armed and pct >= trail_arm:
                leg.trail_armed = True
            if leg.trail_armed and (leg.trail_peak_pct - pct) >= trail_stop:
                closed_trades.append(leg.close_at(i, "trail"))
                return True
        else:
            # Добор/экстра: OR — ТП (мин. 2% если общий ТП выкл) ∨ уровень ниже по циклу.
            leg_tp = effective_addon_extra_tp_pct(
                take_profit_pct, tag=getattr(leg, "tag", None)
            )
            if leg_tp > 0:
                # Чист.% = (MTM − комиссия выхода) / депозит ноги.
                if ((mtm - leg.comm) / max(1.0, leg.pos_notional)) * 100.0 >= leg_tp:
                    closed_trades.append(leg.close_at(i, "tp"))
                    return True
        if _maybe_risk_close(leg, i):
            return True
        if hold_lose_days > 0:
            held = int(day_ord[i]) - leg.entry_day
            if held >= hold_lose_days and mtm < 0:
                closed_trades.append(leg.close_at(i, "hold_losing"))
                return True
        if use_close_3d and getattr(leg, "tag", "") in ("main", ""):
            held = int(day_ord[i]) - leg.entry_day
            is_long_h = leg.pos == 1
            exit_lv_3d = float(leg.exit_long if is_long_h else leg.exit_short)
            entry_lv_3d = _entry_level_for_pos(is_long_h, lv)
            nonlocal sit_3d
            sit_3d, force_now = _situation_3d_on_bar(
                sit_3d,
                enabled=True,
                mode=mode_3d,
                flat=False,
                is_long=is_long_h,
                held=held,
                entry_sp=leg.entry_sp,
                cur_sp=float(sp_arr[i]),
                exit_lv=exit_lv_3d,
                entry_lv=entry_lv_3d,
                mtm=mtm,
            )
            if force_now and getattr(leg, "tag", "") in ("main", ""):
                closed_trades.append(leg.close_at(i, "force_close_3d"))
                return True
        if use_close_3d and getattr(leg, "tag", "") not in ("main", ""):
            held = int(day_ord[i]) - leg.entry_day
            is_long_h = leg.pos == 1
            exit_lv_3d = float(leg.exit_long if is_long_h else leg.exit_short)
            if _force_close_3d_hit(
                enabled=True,
                held=held,
                is_long=is_long_h,
                entry_sp=leg.entry_sp,
                cur_sp=float(sp_arr[i]),
                exit_lv=exit_lv_3d,
                mtm=mtm,
                mode=mode_3d,
            ):
                closed_trades.append(leg.close_at(i, "force_close_3d"))
                return True
        if hold_no_trend_days > 0:
            held = int(day_ord[i]) - leg.entry_day
            if held >= hold_no_trend_days and mtm < 0 and not leg.toward_exit(i):
                closed_trades.append(leg.close_at(i, "hold_no_trend"))
                return True
        return False

    def _shelf_state(i: int):
        if not use_shelf:
            return False, None, None
        from replay.shelf_floor_ceiling import _day_key, _shelf_formed

        day = _day_key(dates, i)
        return _shelf_formed(shelf_causal.get(day))

    def _maybe_shelf_exit(i: int) -> bool:
        if not use_shelf or shelf.pos == 0:
            return False
        from replay.shelf_floor_ceiling import _toward_exit

        formed, lo, hi = _shelf_state(i)
        mtm = shelf.track_mtm(i)
        reason = None
        if not formed:
            reason = "shelf_break"
        elif use_tp:
            # Чист.% с комиссией выхода (Hit/Max тоже по Чист.).
            if ((mtm - shelf.comm) / max(1.0, shelf.pos_notional)) * 100.0 >= take_profit_pct:
                reason = "tp"
        if reason is None and lo is not None and hi is not None:
            if shelf.pos == 2 and cur <= lo:
                reason = "shelf_edge"
            elif shelf.pos == 1 and cur >= hi:
                reason = "shelf_edge"
        if (
            reason is None
            and use_close_3d
            and shelf.pos in (1, 2)
        ):
            nonlocal sit_3d
            held = int(day_ord[i]) - shelf.entry_day
            is_long_h = shelf.pos == 1
            exit_lv_3d = float(shelf.exit_long if is_long_h else shelf.exit_short)
            entry_lv_3d = _entry_level_for_pos(is_long_h, lv)
            sit_3d, force_now = _situation_3d_on_bar(
                sit_3d,
                enabled=True,
                mode=mode_3d,
                flat=False,
                is_long=is_long_h,
                held=held,
                entry_sp=shelf.entry_sp,
                cur_sp=cur,
                exit_lv=exit_lv_3d,
                entry_lv=entry_lv_3d,
                mtm=mtm,
            )
            if force_now:
                reason = "force_close_3d"
        if (
            reason is None
            and hold_no_trend_days > 0
            and (int(day_ord[i]) - shelf.entry_day) >= hold_no_trend_days
            and mtm < 0
        ):
            toward = _toward_exit(
                is_long=(shelf.pos == 1),
                entry_sp=shelf.entry_sp,
                cur_sp=cur,
                lo=lo,
                hi=hi,
                min_pp=trend_min_pp,
            )
            if not toward:
                reason = "hold_no_trend"
        if reason:
            closed_trades.append(shelf.close_at(i, reason))
            return True
        return False

    def _try_shelf_entry(i: int) -> None:
        if not use_shelf:
            return
        formed, lo, hi = _shelf_state(i)
        if not formed or lo is None or hi is None:
            return
        sig = 0
        if prev < hi <= cur:
            sig = 2
        elif prev > lo >= cur:
            sig = 1
        if not sig:
            return
        if main.pos:
            closed_trades.append(main.close_at(i, "shelf_displace"))
        if shelf.pos == 0 and main.pos == 0:
            if _entry_blocked_3d(sit_3d, want_long=(sig == 1)):
                return
            shelf.open(
                sig, i, tag="shelf_ff",
                exit_long=float(hi), exit_short=float(lo),
            )

    cur_lv_day = ""
    for i in edges:
        i = int(i)
        if i < 1:
            continue
        if levels_by_date is not None:
            d_key = str(dates[i])[:10]
            if d_key != cur_lv_day:
                cur_lv_day = d_key
                row_lv = levels_by_date.get(d_key)
                if row_lv:
                    lv = levels_from_settings(row_lv)
                    x_enter_l = float(row_lv.get("addon_enter_narrow", ADDON_ENTER_NARROW))
                    x_exit_l = float(row_lv.get("addon_exit_narrow", lv.enter_narrow))
                    x_enter_s = float(row_lv.get("addon_enter_wide", ADDON_ENTER_WIDE))
                    x_exit_s = float(row_lv.get("addon_exit_wide", lv.enter_wide))
                    e_enter_l = float(row_lv.get("extra_enter_narrow", EXTRA_ENTER_NARROW))
                    e_exit_l = float(row_lv.get("extra_exit_narrow", x_enter_l))
                    e_enter_s = float(row_lv.get("extra_enter_wide", EXTRA_ENTER_WIDE))
                    e_exit_s = float(row_lv.get("extra_exit_wide", x_enter_s))
        prev, cur = float(sp_arr[i - 1]), float(sp_arr[i])
        gap_only = i not in edge_set
        _maybe_stops(ext, i)
        _maybe_stops(add, i)
        _maybe_stops(main, i)
        if ext.pos:
            row = ext.try_level_exit(i, reason="extra_exit")
            if row:
                closed_trades.append(row)
        if add.pos:
            row = add.try_level_exit(i, reason="addon_exit")
            if row:
                closed_trades.append(row)
        if main.pos:
            row = main.try_level_exit(i, reason="spread_exit")
            if row:
                closed_trades.append(row)
        if use_shelf:
            _maybe_shelf_exit(i)
            _try_shelf_entry(i)
        if gap_only:
            continue
        if use_close_3d and sit_3d is not None and main.pos == 0:
            # Flat по базе: снятие ситуации при возврате к зоне входа.
            en_clr = _clear_entry_level_from_sit(sit_3d, lv)
            sit_3d, _ = _situation_3d_on_bar(
                sit_3d,
                enabled=True,
                mode=mode_3d,
                flat=True,
                is_long=None,
                held=None,
                entry_sp=None,
                cur_sp=cur,
                exit_lv=None,
                entry_lv=en_clr,
                mtm=None,
            )
        if main.pos == 0 and add.pos == 0 and ext.pos == 0 and shelf.pos == 0:
            pos_enum = _Pos.FLAT
            s = determine_spread_level_signal(prev, cur, pos_enum, lv)
            if s.value == "ENTER_LONG":
                if not _skip_main_open(1, i) and not _entry_blocked_3d(
                    sit_3d, want_long=True
                ):
                    main.open(
                        1, i, tag="main",
                        exit_long=float(lv.exit_narrow), exit_short=float(lv.exit_wide),
                    )
            elif s.value == "ENTER_SHORT":
                if not _skip_main_open(2, i) and not _entry_blocked_3d(
                    sit_3d, want_long=False
                ):
                    main.open(
                        2, i, tag="main",
                        exit_long=float(lv.exit_narrow), exit_short=float(lv.exit_wide),
                    )
        long_host = main.pos == 1 or add.pos == 1
        short_host = main.pos == 2 or add.pos == 2
        if long_host:
            if (
                use_addon
                and add.pos == 0
                and main.pos == 1
                and prev > x_enter_l
                and cur <= x_enter_l
                and not _entry_blocked_3d(sit_3d, want_long=True)
            ):
                if add.open(1, i, tag="addon", exit_long=x_exit_l, exit_short=x_exit_s):
                    addon_opens += 1
            if (
                use_extreme
                and ext.pos == 0
                and cur <= e_enter_l
                and not _entry_blocked_3d(sit_3d, want_long=True)
            ):
                if ext.open(1, i, tag="extra", exit_long=e_exit_l, exit_short=e_exit_s):
                    extra_opens += 1
        elif short_host:
            if (
                use_addon
                and add.pos == 0
                and main.pos == 2
                and prev < x_enter_s
                and cur >= x_enter_s
                and not _entry_blocked_3d(sit_3d, want_long=False)
            ):
                if add.open(2, i, tag="addon", exit_long=x_exit_l, exit_short=x_exit_s):
                    addon_opens += 1
            if (
                use_extreme
                and ext.pos == 0
                and cur >= e_enter_s
                and not _entry_blocked_3d(sit_3d, want_long=False)
            ):
                if ext.open(2, i, tag="extra", exit_long=e_exit_l, exit_short=e_exit_s):
                    extra_opens += 1
        n_open = (
            (1 if main.pos else 0)
            + (1 if add.pos else 0)
            + (1 if ext.pos else 0)
            + (1 if shelf.pos else 0)
        )
        if n_open > peak_legs:
            peak_legs = n_open

    wins = sum(1 for t in closed_trades if t["net"] > 0)
    closed_n = len(closed_trades)
    trades_out = list(closed_trades)
    last_i = prep.n - 1
    if main.pos and main.entry_td:
        trades_out.append(main.snapshot_open(last_i))
    if add.pos and add.entry_td:
        trades_out.append(add.snapshot_open(last_i))
    if ext.pos and ext.entry_td:
        trades_out.append(ext.snapshot_open(last_i))
    if shelf.pos and shelf.entry_td:
        trades_out.append(shelf.snapshot_open(last_i))
    bits = ["база"] if use_base else ["без базы"]
    if use_addon:
        bits.append("добор 2/7")
    if use_extreme:
        bits.append("экстра 1/9")
    if use_shelf:
        bits.append("пол–потолок (кошелёк базы)")
    note = (
        " + ".join(bits)
        + ": добавки добор/экстра при открытой базе; полка закрывает базу "
        + "и входит с депозита базы; "
        + (
            "капит.: лоты = floor((кошелёк+PnL ноги)×плечо / пара), шаг 1 лот"
            if use_compound
            else f"фиксированный номинал ноги; пик ног ≤{max(1, peak_legs)}×"
        )
    )
    if peak_notional <= 0:
        peak_notional = dep_main
    seed = dep_main + dep_addon + dep_extra
    payload = {
        "trades": trades_out,
        "summary": {
            "trades": closed_n,
            "wins": wins,
            "winratePct": round(100.0 * wins / closed_n, 1) if closed_n else 0.0,
            "pnlRub": round(total_pnl, 2),
            "maxDdRub": round(max_dd, 2),
            "finalEquityRub": round(seed + total_pnl, 2),
            "retPct": round(100.0 * total_pnl / seed, 2) if seed else 0.0,
            "openCount": (
                (1 if main.pos else 0)
                + (1 if add.pos else 0)
                + (1 if ext.pos else 0)
                + (1 if shelf.pos else 0)
            ),
            "addonOpens": addon_opens,
            "extraOpens": extra_opens,
            "openMain": main.pos,
            "openAdd": add.pos,
            "openExtra": ext.pos,
            "openShelf": shelf.pos,
            "peakLegs": peak_legs,
            "peakNotionalRub": round(peak_notional, 2),
            "note": note,
        },
        "params": {
            "slip": slip,
            "notional": base,
            "compound": use_compound,
            "takeProfitPct": take_profit_pct,
            "trailArmPct": trail_arm if use_trail else 0.0,
            "trailStopPct": trail_stop if use_trail else 0.0,
            "maxHoldDaysIfLosing": hold_lose_days,
            "maxHoldDaysNoExitTrend": hold_no_trend_days,
            "forceClose3d": bool(use_close_3d),
            "exitTrendMinPp": trend_min_pp,
            "leverage": LEVERAGE,
            "commissionPctPerSide": COMM_PCT,
            "overnightFeeModel": OVERNIGHT_FEE_MODEL,
            "overnightFeeNoteRu": "Премиум: ступени ₽/день на короткой ноге (lots×цена; иначе ≈eff/2)",
            "asLive": False,
            "regimeZMode": False,
            "spreadLevelMode": True,
            "spreadLevels": lv.as_dict(),
            "addonMode": use_addon,
            "extremeAddonMode": use_extreme,
            "baseMode": use_base,
            "enableBase": use_base,
            "shelfFloorCeilingMode": use_shelf,
            "addonExtraExitMode": "tp_or_level",
            "addonExtraOrTpPct": float(ADDON_EXTRA_OR_TP_PCT),
            "addonLevels": {
                "enter_narrow": x_enter_l,
                "exit_narrow": x_exit_l,
                "enter_wide": x_enter_s,
                "exit_wide": x_exit_s,
            },
            "extraLevels": {
                "enter_narrow": e_enter_l,
                "exit_narrow": e_exit_l,
                "enter_wide": e_enter_s,
                "exit_wide": e_exit_s,
            },
            "tip1mSettleSec": float(settle),
            "riskPolicy": pol or None,
        },
    }
    if leg_deposit_fn is not None:
        pool = float(dep_main + dep_addon + dep_extra)
        mc, ar = scale_dynamic_pool_caps(pool)
        return attach_wallet_summary(
            payload,
            wallets,
            wallet_mode="dynamic_pool",
            pool_rub=pool,
            dynamic_main_cap=mc,
            dynamic_addon_reserve=ar,
        )
    return attach_wallet_summary(payload, wallets)


def run_base_plus_transition_swing(
    prep: PreparedTips,
    *,
    slip: float = DEFAULT_SLIP,
    notional: float = DEFAULT_NOTIONAL,
    window_start_ms: int = 0,
    window_end_ms: int = 0,
    spread_levels: dict[str, float] | None = None,
    take_profit_pct: float = 0.0,
    max_hold_days_if_losing: float = 0.0,
    max_hold_days_no_exit_trend: float = 0.0,
    force_close_3d: bool = False,
    force_close_3d_mode: str = "force",
    exit_trend_min_pp: float = 0.2,
    now_ms: int | None = None,
    settle_sec: float | None = None,
) -> dict[str, Any]:
    """База (уровни спреда) + ping-pong в коричневой зоне L вых…S вых.

    Коричневая полоса на графике = ``exit_narrow`` (низ) … ``exit_wide`` (верх).
    После выхода базового Long (касание L вых) → ждём Short на верхней границе (S вых),
    выход Short на нижней (L вых). После выхода базового Short → Long на L вых,
    выход Long на S вых. Дальше ping-pong, пока база снова не войдёт с узкой/широкой
    полосы. Только Тест, не Prod AUTO.
    """
    from live.constants import MONITOR_TIP1M_SETTLE_SEC
    from live.force_close_3d import normalize_mode as _norm_3d_mode
    from live.signals import Position as _Pos
    from live.spread_levels import (
        SpreadLevels,
        determine_spread_level_signal,
        levels_from_settings,
    )

    lv = levels_from_settings(spread_levels) if spread_levels else SpreadLevels()
    mode_3d = _norm_3d_mode(force_close_3d_mode)
    zone_lo = float(lv.exit_narrow)
    zone_hi = float(lv.exit_wide)

    def _left_corridor(sp: float) -> bool:
        return float(sp) < zone_lo or float(sp) >= float(lv.enter_wide)
    settle = MONITOR_TIP1M_SETTLE_SEC if settle_sec is None else float(settle_sec)
    edges = _edges_tip1m_settled(
        prep,
        _edges_from(prep, window_start_ms, window_end_ms),
        now_ms=now_ms,
        settle_sec=settle,
    )
    sp_arr = prep.spread
    z_arr = prep.z
    dates = prep.trade_dates
    day_ord = prep.day_ord
    base = float(notional)
    use_tp = take_profit_pct > 0
    hold_lose_days = int(max_hold_days_if_losing) if max_hold_days_if_losing > 0 else 0
    hold_no_trend_days = (
        int(max_hold_days_no_exit_trend) if max_hold_days_no_exit_trend > 0 else 0
    )
    use_close_3d = bool(force_close_3d)
    sit_3d: dict[str, Any] | None = None
    trend_min_pp = max(0.0, float(exit_trend_min_pp))
    closed_trades: list[dict[str, Any]] = []
    peak = max_dd = total_pnl = 0.0
    trade_no = 0
    swing_opens = 0
    swing_arm: str | None = None  # "short" | "long" after base/swing exit

    class _Leg:
        __slots__ = (
            "pos", "entry_sp", "entry_td", "entry_z", "entry_day", "entry_comm",
            "eff", "comm", "ovn", "tag", "pos_notional", "pnl_min", "pnl_max",
            "hit1_td", "hit2_td", "hit3_td", "exit_long", "exit_short", "index",
            "lots", "size",
        )

        def __init__(self) -> None:
            self.pos = 0
            self.entry_sp = 0.0
            self.entry_td = ""
            self.entry_z = 0.0
            self.entry_day = 0
            self.entry_comm = 0.0
            self.eff = 0.0
            self.comm = 0.0
            self.ovn = 0.0
            self.tag = ""
            self.pos_notional = base
            self.pnl_min = float("inf")
            self.pnl_max = float("-inf")
            self.hit1_td = None
            self.hit2_td = None
            self.hit3_td = None
            self.exit_long = zone_hi
            self.exit_short = zone_lo
            self.index = 0
            self.lots = None
            self.size = None

        def open(self, sig: int, i: int, *, tag: str, exit_long: float, exit_short: float) -> bool:
            nonlocal trade_no
            if _entry_blocked_3d(sit_3d, want_long=(sig == 1)):
                return False
            direction = "LONG" if sig == 1 else "SHORT"
            tip = float(sp_arr[i])
            tn, tp = _prep_leg_prices(prep, i)
            sz = size_test_spread_lots(
                deposit_rub=base, price_tatn=tn, price_tatnp=tp, direction=direction
            )
            if sz.get("lots") == 0:
                return False
            self.eff, self.comm, self.ovn = _fees_for_sized_open(
                deposit=base, direction=direction, sz=sz
            )
            self.entry_comm = self.comm
            self.pos_notional = float(sz.get("deposit") or base)
            self.lots = sz.get("lots")
            self.size = sz
            self.entry_sp = tip + slip if sig == 1 else tip - slip
            self.entry_td = dates[i]
            self.entry_z = float(z_arr[i]) if math.isfinite(float(z_arr[i])) else 0.0
            self.entry_day = int(day_ord[i])
            self.pos = sig
            self.tag = tag
            self.exit_long = float(exit_long)
            self.exit_short = float(exit_short)
            trade_no += 1
            self.index = trade_no
            mtm = self.mtm_at(i)
            self.pnl_min = mtm - self.comm
            self.pnl_max = mtm - self.comm
            self.hit1_td = self.hit2_td = self.hit3_td = None
            return True

        def mtm_at(self, i: int) -> float:
            is_long = self.pos == 1
            sp = float(sp_arr[i])
            pnl_pts = (sp - self.entry_sp) if is_long else (self.entry_sp - sp)
            gross = self.eff * (pnl_pts / 100.0)
            ovn = self.ovn * max(0, int(day_ord[i]) - self.entry_day)
            return gross - self.entry_comm - ovn

        def track_mtm(self, i: int) -> float:
            mtm = self.mtm_at(i)
            chist = mtm - self.comm  # Hit/Max как «Чист.»
            if chist < self.pnl_min:
                self.pnl_min = chist
            if chist > self.pnl_max:
                self.pnl_max = chist
            pct = (chist / max(1.0, self.pos_notional)) * 100.0
            td = dates[i]
            if self.hit1_td is None and pct >= 1.0:
                self.hit1_td = td
            if self.hit2_td is None and pct >= 2.0:
                self.hit2_td = td
            if self.hit3_td is None and pct >= 3.0:
                self.hit3_td = td
            return mtm

        def toward_exit(self, i: int) -> bool:
            cur_sp = float(sp_arr[i])
            if self.pos == 1:
                toward = (cur_sp - self.entry_sp) >= trend_min_pp
                if not toward:
                    exit_lv = self.exit_long
                    toward = (exit_lv - cur_sp) <= (exit_lv - self.entry_sp) - trend_min_pp
                return toward
            toward = (self.entry_sp - cur_sp) >= trend_min_pp
            if not toward:
                exit_lv = self.exit_short
                toward = (cur_sp - exit_lv) <= (self.entry_sp - exit_lv) - trend_min_pp
            return toward

        def close_at(self, i: int, reason: str) -> dict[str, Any]:
            nonlocal total_pnl, peak, max_dd
            is_long = self.pos == 1
            tip = float(sp_arr[i])
            exit_sp = tip - slip if is_long else tip + slip
            pnl_pts = (exit_sp - self.entry_sp) if is_long else (self.entry_sp - exit_sp)
            gross = self.eff * (pnl_pts / 100.0)
            ovn = self.ovn * max(0, int(day_ord[i]) - self.entry_day)
            comm_total = self.entry_comm + self.comm
            net = gross - comm_total - ovn
            total_pnl += net
            if total_pnl > peak:
                peak = total_pnl
            max_dd = max(max_dd, peak - total_pnl)
            pmin = net if not math.isfinite(self.pnl_min) else min(self.pnl_min, net)
            pmax = net if not math.isfinite(self.pnl_max) else max(self.pnl_max, net)
            src = "качалка" if self.tag == "swing" else "база"
            row = {
                "index": self.index,
                "direction": "Long" if is_long else "Short",
                "entryDate": self.entry_td,
                "exitDate": dates[i],
                "entryZ": round(self.entry_z, 4),
                "exitZ": round(float(z_arr[i]), 4) if math.isfinite(float(z_arr[i])) else None,
                "entrySpread": round(self.entry_sp, 6),
                "exitSpread": round(exit_sp, 6),
                "entrySlip": round(float(slip), 6),
                "pnlPts": round(pnl_pts, 6),
                "gross": round(float(gross), 4),
                "commission": round(float(comm_total), 4),
                "overnight": round(float(ovn), 4),
                "net": round(net, 4),
                "modelNet": round(net, 4),
                "netFromAccount": False,
                "pnlMin": round(pmin, 4),
                "pnlMax": round(pmax, 4),
                "hit1Date": self.hit1_td,
                "hit2Date": self.hit2_td,
                "hit3Date": self.hit3_td,
                "accountBefore": None,
                "accountAfter": round(base + total_pnl, 2),
                "status": "Закрыта",
                "exitReason": reason,
                "notional": round(self.pos_notional, 2),
                "tag": self.tag,
                "source": src,
            }
            row.update(_lot_trade_fields(self.size))
            self.pos = 0
            self.tag = ""
            self.lots = None
            self.size = None
            self.pnl_min = float("inf")
            self.pnl_max = float("-inf")
            self.hit1_td = self.hit2_td = self.hit3_td = None
            return row

        def try_level_exit(self, i: int, *, reason: str) -> dict[str, Any] | None:
            if self.pos == 0:
                return None
            prev = float(sp_arr[i - 1])
            cur = float(sp_arr[i])
            if self.pos == 1:
                if not (prev < self.exit_long and cur >= self.exit_long):
                    return None
            elif not (prev > self.exit_short and cur <= self.exit_short):
                return None
            return self.close_at(i, reason)

        def snapshot_open(self, last_i: int) -> dict[str, Any]:
            mtm = self.mtm_at(last_i) if last_i >= 0 else 0.0
            chist = mtm - self.comm
            pmin = chist if not math.isfinite(self.pnl_min) else min(self.pnl_min, chist)
            pmax = chist if not math.isfinite(self.pnl_max) else max(self.pnl_max, chist)
            z_last = float(z_arr[last_i]) if last_i >= 0 else 0.0
            src = "качалка" if self.tag == "swing" else "база"
            row = {
                "index": self.index,
                "direction": "Long" if self.pos == 1 else "Short",
                "entryDate": self.entry_td,
                "exitDate": "—",
                "entryZ": round(self.entry_z, 4),
                "exitZ": round(z_last, 4) if math.isfinite(z_last) else None,
                "entrySpread": round(self.entry_sp, 6),
                "exitSpread": None,
                "entrySlip": round(float(slip), 6),
                "pnlPts": None,
                "gross": None,
                "commission": None,
                "overnight": None,
                "net": None,
                "modelNet": None,
                "netFromAccount": False,
                "pnlMin": round(pmin, 4),
                "pnlMax": round(pmax, 4),
                "hit1Date": self.hit1_td,
                "hit2Date": self.hit2_td,
                "hit3Date": self.hit3_td,
                "accountAfter": None,
                "status": "Открыта",
                "exitReason": None,
                "notional": round(self.pos_notional, 2),
                "tag": self.tag,
                "source": src,
            }
            row.update(_lot_trade_fields(self.size))
            return row

    main = _Leg()
    swing = _Leg()

    def _maybe_stops(leg: _Leg, i: int) -> bool:
        if leg.pos == 0:
            return False
        mtm = leg.track_mtm(i)
        if use_tp:
            pct = (mtm / max(1.0, leg.pos_notional)) * 100.0
            _ = pct  # Hit-like path not tracked here; TP по Чист.%
            if ((mtm - leg.comm) / max(1.0, leg.pos_notional)) * 100.0 >= take_profit_pct:
                closed_trades.append(leg.close_at(i, "tp"))
                return True
        if hold_lose_days > 0:
            held = int(day_ord[i]) - leg.entry_day
            if held >= hold_lose_days and mtm < 0:
                closed_trades.append(leg.close_at(i, "hold_losing"))
                return True
        if use_close_3d:
            held = int(day_ord[i]) - leg.entry_day
            is_long_h = leg.pos == 1
            exit_lv_3d = float(leg.exit_long if is_long_h else leg.exit_short)
            entry_lv_3d = _entry_level_for_pos(is_long_h, lv)
            nonlocal sit_3d
            sit_3d, force_now = _situation_3d_on_bar(
                sit_3d,
                enabled=True,
                mode=mode_3d,
                flat=False,
                is_long=is_long_h,
                held=held,
                entry_sp=leg.entry_sp,
                cur_sp=float(sp_arr[i]),
                exit_lv=exit_lv_3d,
                entry_lv=entry_lv_3d,
                mtm=mtm,
            )
            if force_now:
                closed_trades.append(leg.close_at(i, "force_close_3d"))
                return True
        if hold_no_trend_days > 0:
            held = int(day_ord[i]) - leg.entry_day
            if held >= hold_no_trend_days and mtm < 0 and not leg.toward_exit(i):
                closed_trades.append(leg.close_at(i, "hold_no_trend"))
                return True
        return False

    def _arm_after_swing_exit(was_long: bool) -> None:
        nonlocal swing_arm
        swing_arm = "short" if was_long else "long"

    for i in edges:
        i = int(i)
        if i < 1:
            continue
        prev, cur = float(sp_arr[i - 1]), float(sp_arr[i])
        _maybe_stops(swing, i)
        _maybe_stops(main, i)

        if swing.pos and _left_corridor(cur):
            closed_trades.append(swing.close_at(i, "zone_regime_exit"))
            swing_arm = None

        if swing.pos:
            row = swing.try_level_exit(i, reason="zone_swing_exit")
            if row:
                closed_trades.append(row)
                _arm_after_swing_exit(row["direction"] == "Long")

        if main.pos:
            pos_enum = _Pos.LONG if main.pos == 1 else _Pos.SHORT
            s = determine_spread_level_signal(prev, cur, pos_enum, lv)
            if s.value == "EXIT_LONG":
                row = main.close_at(i, "spread_exit")
                closed_trades.append(row)
                swing_arm = "short"
            elif s.value == "EXIT_SHORT":
                row = main.close_at(i, "spread_exit")
                closed_trades.append(row)
                swing_arm = "long"

        if main.pos == 0 and swing.pos == 0:
            s = determine_spread_level_signal(prev, cur, _Pos.FLAT, lv)
            if s.value == "ENTER_LONG":
                main.open(
                    1, i, tag="main",
                    exit_long=float(lv.exit_narrow), exit_short=float(lv.exit_wide),
                )
                swing_arm = None
            elif s.value == "ENTER_SHORT":
                main.open(
                    2, i, tag="main",
                    exit_long=float(lv.exit_narrow), exit_short=float(lv.exit_wide),
                )
                swing_arm = None
            elif swing_arm == "short" and prev < zone_hi and cur >= zone_hi:
                if swing.open(
                    2, i, tag="swing",
                    exit_long=zone_hi, exit_short=zone_lo,
                ):
                    swing_arm = None
                    swing_opens += 1
            elif swing_arm == "long" and prev < zone_lo and cur >= zone_lo:
                if swing.open(
                    1, i, tag="swing",
                    exit_long=zone_hi, exit_short=zone_lo,
                ):
                    swing_arm = None
                    swing_opens += 1

    wins = sum(1 for t in closed_trades if t["net"] > 0)
    closed_n = len(closed_trades)
    trades_out = list(closed_trades)
    last_i = prep.n - 1
    if main.pos and main.entry_td:
        trades_out.append(main.snapshot_open(last_i))
    if swing.pos and swing.entry_td:
        trades_out.append(swing.snapshot_open(last_i))
    note = (
        f"качалка зоны: база + ping-pong {zone_lo:.1f}…{zone_hi:.1f}% "
        "(коричневая зона L вых…S вых); после выхода базы — Short на верху / "
        "Long на низу; только Тест"
    )
    return apply_journal_account_chain({
        "trades": trades_out,
        "summary": {
            "trades": closed_n,
            "wins": wins,
            "winratePct": round(100.0 * wins / closed_n, 1) if closed_n else 0.0,
            "pnlRub": round(total_pnl, 2),
            "maxDdRub": round(max_dd, 2),
            "finalEquityRub": round(base + total_pnl, 2),
            "retPct": round(100.0 * total_pnl / base, 2) if base else 0.0,
            "openCount": (1 if main.pos else 0) + (1 if swing.pos else 0),
            "swingOpens": swing_opens,
            "openMain": main.pos,
            "openSwing": swing.pos,
            "note": note,
        },
        "params": {
            "slip": slip,
            "notional": base,
            "compound": False,
            "takeProfitPct": take_profit_pct,
            "maxHoldDaysIfLosing": hold_lose_days,
            "maxHoldDaysNoExitTrend": hold_no_trend_days,
            "forceClose3d": bool(use_close_3d),
            "exitTrendMinPp": trend_min_pp,
            "leverage": LEVERAGE,
            "commissionPctPerSide": COMM_PCT,
            "overnightFeeModel": OVERNIGHT_FEE_MODEL,
            "overnightFeeNoteRu": "Премиум: ступени ₽/день на короткой ноге (lots×цена; иначе ≈eff/2)",
            "asLive": False,
            "regimeZMode": False,
            "spreadLevelMode": True,
            "spreadLevels": lv.as_dict(),
            "transitionSwingMode": True,
            "zoneSwing": {"lo": zone_lo, "hi": zone_hi},
            "tip1mSettleSec": float(settle),
        },
    })


# --- Адаптивный коридор (Тест v1): Long в низком formed, не Prod AUTO ---
ADAPT_CORRIDOR_HI_MAX = 3.0
ADAPT_CORRIDOR_WIDTH_MIN = 0.4
ADAPT_CORRIDOR_WIDTH_MAX = 2.5
ADAPT_CORRIDOR_TP_PCT = 1.5
ADAPT_CORRIDOR_NO_TREND_DAYS = 5
ADAPT_CORRIDOR_FORMED_STREAK = 2
ADAPT_CORRIDOR_PROD_LONG = 3.2
ADAPT_CORRIDOR_COOLDOWN_DAYS = 1
ADAPT_CORRIDOR_DONCHIAN_N = 20
ADAPT_CORRIDOR_BREAK_EPS = 0.08
# Дефолт v3: липкая полка −0.25…1 (лучший quietSum на сетке).
ADAPT_CORRIDOR_STICKY_LO = -0.25
ADAPT_CORRIDOR_STICKY_HI = 1.0
ADAPT_CORRIDOR_USE_STICKY_DEFAULT = True


def parse_adaptive_corridor_mode(raw: Any) -> bool:
    """Тест «адапт. коридор»: Long по дневному коридору S (не качалка зоны)."""
    if raw is None:
        return False
    if isinstance(raw, str):
        return raw.strip().lower() in (
            "1",
            "true",
            "yes",
            "on",
            "adapt",
            "adaptive",
            "adaptive_corridor",
            "adapt_corridor",
            "адапт",
            "адаптивный",
            "адапт.коридор",
            "адапт коридор",
        )
    return bool(raw)


def parse_shelf_floor_ceiling_mode(raw: Any) -> bool:
    """Тест «пол–потолок (полка)»: каузальная широкая полка, не AUTO 3.2/6.2."""
    from replay.shelf_floor_ceiling import parse_shelf_floor_ceiling_mode as _parse

    return _parse(raw)


def _adaptive_corridor_day_phases(
    prep: PreparedTips,
    *,
    donchian_n: int | None = None,
    hi_max: float | None = None,
    width_min: float | None = None,
    width_max: float | None = None,
    break_eps: float | None = None,
    sticky_lo: float | None = None,
    sticky_hi: float | None = None,
) -> dict[str, dict[str, Any]]:
    """Дневные полосы — Donchian по медианам или фиксированная липкая полка."""
    import statistics

    n = max(5, int(donchian_n if donchian_n is not None else ADAPT_CORRIDOR_DONCHIAN_N))
    hi_cap = float(hi_max if hi_max is not None else ADAPT_CORRIDOR_HI_MAX)
    w_min = float(width_min if width_min is not None else ADAPT_CORRIDOR_WIDTH_MIN)
    w_max = float(width_max if width_max is not None else ADAPT_CORRIDOR_WIDTH_MAX)
    eps = float(break_eps if break_eps is not None else ADAPT_CORRIDOR_BREAK_EPS)
    use_sticky = sticky_lo is not None and sticky_hi is not None

    by_day: dict[str, list[float]] = {}
    for i in range(int(prep.n)):
        raw = prep.trade_dates[i]
        day = str(raw)[:10] if raw else ""
        if len(day) < 10:
            continue
        sp = float(prep.spread[i])
        if not math.isfinite(sp):
            continue
        by_day.setdefault(day, []).append(sp)
    days = sorted(by_day.keys())
    meds: list[float] = []
    by_min: dict[str, float] = {}
    by_max: dict[str, float] = {}
    for d in days:
        vals = by_day[d]
        meds.append(float(statistics.median(vals)))
        by_min[d] = float(min(vals))
        by_max[d] = float(max(vals))

    out: dict[str, dict[str, Any]] = {}
    prev_lo: float | None = None
    prev_hi: float | None = None
    prev_formed = False

    for i, day in enumerate(days):
        med = meds[i]
        dmin = by_min[day]
        dmax = by_max[day]

        if use_sticky:
            lo = float(sticky_lo)
            hi = float(sticky_hi)
            width = hi - lo
            band_ok = (lo - eps) <= med <= (hi + eps) and hi < hi_cap
            mode_base = "sticky"
        else:
            if i + 1 < n:
                out[day] = {
                    "phase": "none",
                    "lo": None,
                    "hi": None,
                    "width": None,
                    "touches_lo": 0,
                    "touches_hi": 0,
                    "bounds_mode": "donchian",
                    "title": "Donchian: мало дней",
                }
                prev_formed = False
                continue
            window = meds[i - n + 1 : i + 1]
            lo = float(min(window))
            hi = float(max(window))
            width = hi - lo
            band_ok = w_min <= width <= w_max and hi < hi_cap
            mode_base = "donchian"

        exited_prev = False
        if prev_formed and prev_lo is not None and prev_hi is not None:
            exited_prev = (
                med > prev_hi + eps
                or med < prev_lo - eps
                or dmax > prev_hi + eps
                or dmin < prev_lo - eps
            )

        if exited_prev:
            phase = "broken"
            use_lo, use_hi = float(prev_lo), float(prev_hi)
            use_w = max(0.0, use_hi - use_lo)
            mode = f"{mode_base}_frozen"
            prev_formed = False
            prev_lo = prev_hi = None
        elif band_ok:
            phase = "formed"
            use_lo, use_hi, use_w = lo, hi, width
            mode = mode_base
            prev_formed = True
            prev_lo, prev_hi = lo, hi
        else:
            phase = "none"
            use_lo, use_hi, use_w = lo, hi, width
            mode = mode_base
            prev_formed = False
            prev_lo = prev_hi = None

        out[day] = {
            "phase": phase,
            "lo": round(use_lo, 4) if use_lo is not None else None,
            "hi": round(use_hi, 4) if use_hi is not None else None,
            "width": round(use_w, 4) if use_w is not None else None,
            "touches_lo": 2 if phase == "formed" else 0,
            "touches_hi": 2 if phase == "formed" else 0,
            "bounds_mode": mode,
            "title": (
                f"{mode_base} {use_lo:.2f}…{use_hi:.2f}% · {phase}"
                if use_lo is not None and use_hi is not None
                else f"{mode_base} · {phase}"
            ),
        }
    return out


def run_adaptive_corridor(
    prep: PreparedTips,
    *,
    slip: float = DEFAULT_SLIP,
    notional: float = DEFAULT_NOTIONAL,
    window_start_ms: int = 0,
    window_end_ms: int = 0,
    now_ms: int | None = None,
    settle_sec: float | None = None,
    apply_fees: bool = True,
    donchian_n: int | None = None,
    hi_max: float | None = None,
    width_min: float | None = None,
    width_max: float | None = None,
    tp_pct: float | None = None,
    entry_zone_frac: float | None = None,
    exit_mid: bool = False,
    exit_top: bool = True,
    sticky_lo: float | None = None,
    sticky_hi: float | None = None,
    formed_streak_need: int | None = None,
) -> dict[str, Any]:
    """Тест «адапт. коридор»: Long по Donchian/липкой полке; без Short/добора.

    Оверрайды параметров — для сетки улучшений; дефолт = v2 Donchian20.
    """
    from live.constants import MONITOR_TIP1M_SETTLE_SEC
    from live.spread_corridor import CORRIDOR_ZONE_FRAC

    settle = MONITOR_TIP1M_SETTLE_SEC if settle_sec is None else float(settle_sec)
    tp_use = float(tp_pct if tp_pct is not None else ADAPT_CORRIDOR_TP_PCT)
    entry_frac = float(
        entry_zone_frac if entry_zone_frac is not None else CORRIDOR_ZONE_FRAC
    )
    streak_need = int(
        formed_streak_need
        if formed_streak_need is not None
        else ADAPT_CORRIDOR_FORMED_STREAK
    )
    hi_cap = float(hi_max if hi_max is not None else ADAPT_CORRIDOR_HI_MAX)
    w_min = float(width_min if width_min is not None else ADAPT_CORRIDOR_WIDTH_MIN)
    w_max = float(width_max if width_max is not None else ADAPT_CORRIDOR_WIDTH_MAX)

    # Дефолт v3: липкая полка; явный sticky_lo/hi=None + donchian_n → Donchian.
    s_lo = sticky_lo
    s_hi = sticky_hi
    if (
        s_lo is None
        and s_hi is None
        and ADAPT_CORRIDOR_USE_STICKY_DEFAULT
        and donchian_n is None
    ):
        s_lo = float(ADAPT_CORRIDOR_STICKY_LO)
        s_hi = float(ADAPT_CORRIDOR_STICKY_HI)

    edges = _edges_tip1m_settled(
        prep,
        _edges_from(prep, window_start_ms, window_end_ms),
        now_ms=now_ms,
        settle_sec=settle,
    )
    phases = _adaptive_corridor_day_phases(
        prep,
        donchian_n=donchian_n,
        hi_max=hi_cap,
        width_min=w_min,
        width_max=w_max,
        sticky_lo=s_lo,
        sticky_hi=s_hi,
    )
    sp_arr = prep.spread
    z_arr = prep.z
    dates = prep.trade_dates
    day_ord = prep.day_ord
    base = float(notional)
    closed_trades: list[dict[str, Any]] = []
    peak = max_dd = total_pnl = 0.0
    trade_no = 0

    pos = 0
    entry_sp = 0.0
    entry_td = ""
    entry_z = 0.0
    entry_day = 0
    entry_comm = 0.0
    eff = comm = ovn = 0.0
    pos_notional = base
    pnl_min = float("inf")
    pnl_max = float("-inf")
    hit1_td = hit2_td = hit3_td = None
    entry_lo = entry_hi = entry_width = None
    open_size: dict[str, Any] | None = None

    formed_streak = 0
    last_streak_day = ""
    need_leave_low = False
    leave_bot: float | None = None
    cooldown_until_ord: int | None = None

    def _day_key(i: int) -> str:
        return str(dates[i])[:10]

    def _mtm_at(i: int) -> float:
        sp = float(sp_arr[i])
        pnl_pts = sp - entry_sp
        gross = eff * (pnl_pts / 100.0)
        ov = ovn * max(0, int(day_ord[i]) - entry_day)
        if not apply_fees:
            return gross
        return gross - entry_comm - ov

    def _track(i: int) -> float:
        nonlocal pnl_min, pnl_max, hit1_td, hit2_td, hit3_td
        mtm = _mtm_at(i)
        chist = mtm - (comm if apply_fees else 0.0)
        if chist < pnl_min:
            pnl_min = chist
        if chist > pnl_max:
            pnl_max = chist
        pct = (chist / max(1.0, pos_notional)) * 100.0
        td = dates[i]
        if hit1_td is None and pct >= 1.0:
            hit1_td = td
        if hit2_td is None and pct >= 2.0:
            hit2_td = td
        if hit3_td is None and pct >= 3.0:
            hit3_td = td
        return mtm

    def _close(i: int, reason: str) -> None:
        nonlocal pos, total_pnl, peak, max_dd, trade_no
        nonlocal pnl_min, pnl_max, hit1_td, hit2_td, hit3_td
        nonlocal need_leave_low, leave_bot, cooldown_until_ord
        nonlocal entry_lo, entry_hi, entry_width, open_size
        tip = float(sp_arr[i])
        exit_sp = tip - slip
        pnl_pts = exit_sp - entry_sp
        gross = eff * (pnl_pts / 100.0)
        ov = ovn * max(0, int(day_ord[i]) - entry_day)
        if apply_fees:
            comm_total = entry_comm + comm
            net = gross - comm_total - ov
        else:
            comm_total = 0.0
            ov = 0.0
            net = gross
        total_pnl += net
        if total_pnl > peak:
            peak = total_pnl
        max_dd = max(max_dd, peak - total_pnl)
        pmin = net if not math.isfinite(pnl_min) else min(pnl_min, net)
        pmax = net if not math.isfinite(pnl_max) else max(pnl_max, net)
        closed_trades.append({
            "index": trade_no,
            "direction": "Long",
            "entryDate": entry_td,
            "exitDate": dates[i],
            "entryZ": round(entry_z, 4),
            "exitZ": round(float(z_arr[i]), 4) if math.isfinite(float(z_arr[i])) else None,
            "entrySpread": round(entry_sp, 6),
            "exitSpread": round(exit_sp, 6),
            "entrySlip": round(float(slip), 6),
            "pnlPts": round(pnl_pts, 6),
            "gross": round(float(gross), 4),
            "commission": round(float(comm_total), 4),
            "overnight": round(float(ov), 4),
            "net": round(net, 4),
            "modelNet": round(net, 4),
            "netFromAccount": False,
            "pnlMin": round(pmin, 4),
            "pnlMax": round(pmax, 4),
            "hit1Date": hit1_td,
            "hit2Date": hit2_td,
            "hit3Date": hit3_td,
            "accountBefore": None,
            "accountAfter": round(base + total_pnl, 2),
            "status": "Закрыта",
            "exitReason": reason,
            "notional": round(pos_notional, 2),
            "tag": "adapt_corridor",
            "source": "адапт.коридор",
            "corridorLo": entry_lo,
            "corridorHi": entry_hi,
            "corridorWidth": entry_width,
        })
        closed_trades[-1].update(_lot_trade_fields(open_size))
        open_size = None
        bot = None
        if entry_lo is not None and entry_width is not None:
            bot = float(entry_lo) + entry_frac * float(entry_width)
        need_leave_low = True
        leave_bot = bot
        cooldown_until_ord = int(day_ord[i]) + ADAPT_CORRIDOR_COOLDOWN_DAYS
        pos = 0
        pnl_min = float("inf")
        pnl_max = float("-inf")
        hit1_td = hit2_td = hit3_td = None
        entry_lo = entry_hi = entry_width = None

    def _open(i: int, lo: float, hi: float, width: float) -> None:
        nonlocal pos, entry_sp, entry_td, entry_z, entry_day, entry_comm
        nonlocal eff, comm, ovn, pos_notional, trade_no
        nonlocal pnl_min, pnl_max, hit1_td, hit2_td, hit3_td
        nonlocal entry_lo, entry_hi, entry_width, open_size
        tip = float(sp_arr[i])
        tn, tp = _prep_leg_prices(prep, i)
        sz = size_test_spread_lots(
            deposit_rub=base, price_tatn=tn, price_tatnp=tp, direction="LONG"
        )
        if sz.get("lots") == 0:
            return
        open_size = sz
        pos_notional = float(sz.get("deposit") or base)
        if apply_fees:
            eff, comm, ovn = _fees_for_sized_open(deposit=base, direction="LONG", sz=sz)
            entry_comm = comm
        else:
            exec_n = float(sz.get("execution_notional") or (base * LEVERAGE))
            eff = exec_n
            comm = ovn = entry_comm = 0.0
        entry_sp = tip + slip
        entry_td = dates[i]
        entry_z = float(z_arr[i]) if math.isfinite(float(z_arr[i])) else 0.0
        entry_day = int(day_ord[i])
        entry_lo, entry_hi, entry_width = float(lo), float(hi), float(width)
        pos = 1
        trade_no += 1
        hit1_td = hit2_td = hit3_td = None
        mtm = _track(i)
        pnl_min = pnl_max = mtm - (comm if apply_fees else 0.0)

    for i in edges:
        day = _day_key(i)
        ph = phases.get(day) or {}
        phase = str(ph.get("phase") or "none")
        lo = ph.get("lo")
        hi = ph.get("hi")
        width = ph.get("width")
        low_formed = (
            phase == "formed"
            and hi is not None
            and float(hi) < hi_cap
        )
        if day != last_streak_day:
            if low_formed:
                formed_streak = formed_streak + 1 if last_streak_day else 1
            else:
                formed_streak = 0
            last_streak_day = day

        cur = float(sp_arr[i])
        if not math.isfinite(cur):
            continue

        if pos:
            mtm = _track(i)
            reason = None
            if entry_hi is not None and entry_lo is not None and entry_width:
                if exit_mid:
                    mid = (float(entry_lo) + float(entry_hi)) / 2.0
                    if cur >= mid:
                        reason = "середина"
                if reason is None and exit_top:
                    top = float(entry_hi) - entry_frac * float(entry_width)
                    if cur >= top:
                        reason = "верх_коридора"
            if reason is None and tp_use > 0:
                if ((mtm - comm) / max(1.0, pos_notional)) * 100.0 >= tp_use:
                    reason = "тп"
            if reason is None and phase == "broken":
                reason = "слом"
            if (
                reason is None
                and entry_hi is not None
                and entry_lo is not None
                and (
                    cur > float(entry_hi) + ADAPT_CORRIDOR_BREAK_EPS
                    or cur < float(entry_lo) - ADAPT_CORRIDOR_BREAK_EPS
                )
            ):
                reason = "слом"
            if reason is None and cur >= ADAPT_CORRIDOR_PROD_LONG:
                reason = "достиг_3.2"
            held = int(day_ord[i]) - entry_day
            if (
                reason is None
                and held >= ADAPT_CORRIDOR_NO_TREND_DAYS
                and mtm <= 0
            ):
                reason = "нет_хода"
            if reason:
                _close(i, reason)
            continue

        if need_leave_low:
            if leave_bot is not None and cur > float(leave_bot):
                need_leave_low = False
                leave_bot = None
            else:
                continue
        if cooldown_until_ord is not None:
            if int(day_ord[i]) < int(cooldown_until_ord):
                continue
            cooldown_until_ord = None

        if phase in ("none", "broken", "forming") and cur < ADAPT_CORRIDOR_PROD_LONG:
            continue
        if not low_formed or lo is None or width is None:
            continue
        if sticky_lo is None and s_lo is None and not (w_min <= float(width) <= w_max):
            continue
        if int(ph.get("touches_lo") or 0) < 2 or int(ph.get("touches_hi") or 0) < 2:
            continue
        if formed_streak < streak_need:
            continue
        bot = float(lo) + entry_frac * float(width)
        if cur > bot:
            continue
        _open(i, float(lo), float(hi), float(width))

    trades_out = list(closed_trades)
    last_i = prep.n - 1
    if pos and entry_td and last_i >= 0:
        mtm = _mtm_at(last_i)
        chist = mtm - (comm if apply_fees else 0.0)
        pmin = chist if not math.isfinite(pnl_min) else min(pnl_min, chist)
        pmax = chist if not math.isfinite(pnl_max) else max(pnl_max, chist)
        z_last = float(z_arr[last_i])
        trades_out.append({
            "index": trade_no,
            "direction": "Long",
            "entryDate": entry_td,
            "exitDate": "—",
            "entryZ": round(entry_z, 4),
            "exitZ": round(z_last, 4) if math.isfinite(z_last) else None,
            "entrySpread": round(entry_sp, 6),
            "exitSpread": None,
            "entrySlip": round(float(slip), 6),
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
            "tag": "adapt_corridor",
            "source": "адапт.коридор",
            "corridorLo": entry_lo,
            "corridorHi": entry_hi,
            "corridorWidth": entry_width,
        })
        trades_out[-1].update(_lot_trade_fields(open_size))

    wins = sum(1 for t in closed_trades if t["net"] > 0)
    closed_n = len(closed_trades)
    mode_lab = (
        f"sticky {s_lo}…{s_hi}"
        if s_lo is not None
        else f"Donchian({donchian_n or ADAPT_CORRIDOR_DONCHIAN_N})"
    )
    note = (
        f"адапт. коридор: Long {mode_lab}, hi<{hi_cap}, "
        f"вход низ {entry_frac:.0%}, выход "
        f"{'середина/' if exit_mid else ''}"
        f"{'верх/' if exit_top else ''}ТП{tp_use}%/слом/S≥3.2/нет хода; без Short/добора"
    )
    return apply_journal_account_chain({
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
            "note": note,
            "applyFees": bool(apply_fees),
        },
        "params": {
            "slip": slip,
            "notional": base,
            "compound": False,
            "takeProfitPct": tp_use,
            "leverage": LEVERAGE,
            "commissionPctPerSide": COMM_PCT if apply_fees else 0.0,
            "overnightFeeModel": OVERNIGHT_FEE_MODEL if apply_fees else None,
            "asLive": False,
            "regimeZMode": False,
            "spreadLevelMode": False,
            "adaptiveCorridorMode": True,
            "adaptiveCorridorRules": "v3_sticky",
            "donchianN": donchian_n or ADAPT_CORRIDOR_DONCHIAN_N,
            "hiMax": hi_cap,
            "entryZoneFrac": entry_frac,
            "exitMid": bool(exit_mid),
            "exitTop": bool(exit_top),
            "stickyLo": s_lo,
            "stickyHi": s_hi,
            "tip1mSettleSec": float(settle),
            "applyFees": bool(apply_fees),
        },
    })


def _td_span_key(raw: Any) -> str | None:
    s = str(raw or "").replace("T", " ").strip()
    if not s or s.startswith("—"):
        return None
    if len(s) >= 19:
        return s[:19]
    if len(s) >= 16:
        return s[:16] + ":00"
    if len(s) >= 10:
        return s[:10] + " 00:00:00"
    return None


def _trade_time_span(t: dict[str, Any]) -> tuple[str, str] | None:
    a = _td_span_key(t.get("entryDate"))
    if a is None:
        return None
    b = _td_span_key(t.get("exitDate"))
    if b is None:
        b = "9999-12-31 23:59:59"
    if b < a:
        a, b = b, a
    return a, b


def _spans_overlap(a0: str, a1: str, b0: str, b1: str) -> bool:
    return a0 <= b1 and b0 <= a1


def make_dynamic_pool_deposit_fn(
    *,
    pool: float,
    main_cap: float | None = None,
    addon_reserve: float | None = None,
    extra_soft_cap: float | None = None,
    main_cap_frac: float | None = None,
    addon_reserve_frac: float | None = None,
):
    """Пул счёта: база ≤ доля пула, резерв под добор, экстра = остаток (без потолка).

    Потолки хранятся долями текущего пула, поэтому при капитализации растут
    вместе с накопленной прибылью. Рублёвые ``main_cap`` / ``addon_reserve``
    принимаются для совместимости и пересчитываются в доли стартового пула.

    ``extra_soft_cap`` — опциональный потолок экстра в рублях;
    ``None`` = весь остаток после резерва.
    """
    pool_f = max(1.0, float(pool))
    if main_cap_frac is not None:
        main_frac = float(main_cap_frac)
    elif main_cap is not None:
        main_frac = float(main_cap) / pool_f
    else:
        main_frac = float(DYNAMIC_POOL_MAIN_CAP_FRAC)
    if addon_reserve_frac is not None:
        addon_frac = float(addon_reserve_frac)
    elif addon_reserve is not None:
        addon_frac = float(addon_reserve) / pool_f
    else:
        addon_frac = float(DYNAMIC_POOL_ADDON_RESERVE_FRAC)
    main_frac = max(0.0, main_frac)
    addon_frac = max(0.0, addon_frac)

    def fn(tag: str, st: dict[str, Any]) -> float:
        used = float(st.get("used") or 0.0)
        pool_now = pool_f
        if st.get("compound"):
            realized = st.get("realized_by") or {}
            try:
                pool_now += sum(float(v or 0.0) for v in realized.values())
            except (TypeError, ValueError):
                pass
        pool_now = max(1.0, pool_now)
        main_c = main_frac * pool_now
        addon_r = addon_frac * pool_now
        free = max(0.0, pool_now - used)
        addon_open = bool(st.get("addon_open"))
        t = str(tag or "").strip().lower()
        if t in ("main", "shelf_ff", "база", ""):
            reserve = 0.0 if addon_open else addon_r
            return max(0.0, min(main_c, free - reserve))
        if t in ("addon", "добор"):
            return max(0.0, min(addon_r, free))
        # extra / экстра
        reserve = 0.0 if addon_open else addon_r
        room = max(0.0, free - reserve)
        if extra_soft_cap is not None:
            room = min(room, float(extra_soft_cap))
        return room

    return fn


def scale_dynamic_pool_caps(pool: float) -> tuple[float, float]:
    """Масштаб потолков 60/40 относительно пула (эталон 100 000)."""
    p = max(1.0, float(pool))
    return (
        p * float(DYNAMIC_POOL_MAIN_CAP_FRAC),
        p * float(DYNAMIC_POOL_ADDON_RESERVE_FRAC),
    )


WALLET_MAIN = "main"
WALLET_ADDON = "addon"
WALLET_EXTRA = "extra"


def resolve_test_wallets(
    *,
    notional: float,
    main_notional: float | None = None,
    addon_notional: float | None = None,
    extra_notional: float | None = None,
    pool_rub: float | None = None,
    dynamic_pool: bool = False,
) -> dict[str, float]:
    """Три кошелька Теста: база / добор / экстра. Не четвёртый для полки."""
    if dynamic_pool and pool_rub is not None:
        try:
            pool_f = float(pool_rub)
        except (TypeError, ValueError):
            pool_f = 0.0
        if math.isfinite(pool_f) and pool_f > 0:
            return {
                WALLET_MAIN: float(REF_MAIN_DEP_RUB),
                WALLET_ADDON: float(REF_ADDON_DEP_RUB),
                WALLET_EXTRA: float(REF_EXTRA_DEP_RUB),
            }
    n = float(notional)
    if not math.isfinite(n) or n <= 0:
        n = float(DEFAULT_NOTIONAL)
    if main_notional is None and addon_notional is None and extra_notional is None:
        if abs(n - REF_MAIN_DEP_RUB) < 0.51 or abs(n - DEFAULT_NOTIONAL) < 0.51:
            return {
                WALLET_MAIN: float(REF_MAIN_DEP_RUB),
                WALLET_ADDON: float(REF_ADDON_DEP_RUB),
                WALLET_EXTRA: float(REF_EXTRA_DEP_RUB),
            }
        if abs(n - REF_ACCOUNT_RUB) < 0.51:
            return {
                WALLET_MAIN: float(REF_MAIN_DEP_RUB),
                WALLET_ADDON: float(REF_ADDON_DEP_RUB),
                WALLET_EXTRA: float(REF_EXTRA_DEP_RUB),
            }
        # Кастомный «Капитал ₽» Теста без явных кошельков: база = капитал,
        # добор/экстра — эталонные слоты (не утраивать капитал).
        return {
            WALLET_MAIN: n,
            WALLET_ADDON: float(REF_ADDON_DEP_RUB),
            WALLET_EXTRA: float(REF_EXTRA_DEP_RUB),
        }
    main = float(main_notional) if main_notional is not None else n
    addon = (
        float(addon_notional) if addon_notional is not None else float(REF_ADDON_DEP_RUB)
    )
    extra = (
        float(extra_notional) if extra_notional is not None else float(REF_EXTRA_DEP_RUB)
    )
    return {WALLET_MAIN: main, WALLET_ADDON: addon, WALLET_EXTRA: extra}


def wallet_key_for_trade(trade: dict[str, Any] | None) -> str:
    """Кошелёк строки: пол–потолок и база — один; добор/экстра свои."""
    if not trade:
        return WALLET_MAIN
    tag = str(trade.get("tag") or "").strip().lower()
    src = str(trade.get("source") or "").strip().lower()
    explicit = str(trade.get("wallet") or trade.get("walletKey") or "").strip().lower()
    if explicit in (WALLET_MAIN, WALLET_ADDON, WALLET_EXTRA):
        return explicit
    blob = f"{tag} {src}"
    if tag in ("addon", "добор", "swing", "качалка") or "добор" in src or "качалка" in blob:
        return WALLET_ADDON
    if tag in ("extra", "extreme", "экстра") or "экстра" in src:
        return WALLET_EXTRA
    return WALLET_MAIN


def _open_mtm_from_trades(trades: list[dict[str, Any]] | None) -> float:
    total = 0.0
    for t in trades or []:
        if str(t.get("status") or "") != "Открыта":
            continue
        raw = t.get("openMtm")
        if raw is None:
            raw = t.get("mtm")
        try:
            v = float(raw) if raw is not None else 0.0
        except (TypeError, ValueError):
            v = 0.0
        if math.isfinite(v):
            total += v
    return total


def attach_wallet_summary(
    result: dict[str, Any],
    wallets: dict[str, float],
    *,
    wallet_mode: str | None = None,
    pool_rub: float | None = None,
    dynamic_main_cap: float | None = None,
    dynamic_addon_reserve: float | None = None,
) -> dict[str, Any]:
    """Плашка = сумма трёх кошельков + оценка открытых. Журнал — цепь ноги.

    ``wallet_mode=dynamic_pool``: плашка = пул; До/После журнала — одна цепь пула.
    """
    if not isinstance(result, dict):
        return result
    w = {
        WALLET_MAIN: float(wallets.get(WALLET_MAIN) or 0.0),
        WALLET_ADDON: float(wallets.get(WALLET_ADDON) or 0.0),
        WALLET_EXTRA: float(wallets.get(WALLET_EXTRA) or 0.0),
    }
    mode = str(wallet_mode or "").strip().lower() or None
    seed = w[WALLET_MAIN] + w[WALLET_ADDON] + w[WALLET_EXTRA]
    if mode in ("dynamic_pool", "dynamic", "pool") and pool_rub is not None:
        try:
            seed = float(pool_rub)
        except (TypeError, ValueError):
            pass
    summary = dict(result.get("summary") or {})
    try:
        pnl = float(summary.get("pnlRub") or 0.0)
    except (TypeError, ValueError):
        pnl = 0.0
    open_mtm = _open_mtm_from_trades(list(result.get("trades") or []))
    summary["accountSeedRub"] = round(seed, 2)
    summary["walletMainRub"] = round(w[WALLET_MAIN], 2)
    summary["walletAddonRub"] = round(w[WALLET_ADDON], 2)
    summary["walletExtraRub"] = round(w[WALLET_EXTRA], 2)
    summary["openMtmRub"] = round(open_mtm, 2)
    summary["finalEquityRub"] = round(seed + pnl + open_mtm, 2)
    summary["retPct"] = round(100.0 * pnl / seed, 2) if seed else 0.0
    if mode:
        summary["walletMode"] = mode
    result["summary"] = summary
    params = dict(result.get("params") or {})
    params["walletMainRub"] = w[WALLET_MAIN]
    params["walletAddonRub"] = w[WALLET_ADDON]
    params["walletExtraRub"] = w[WALLET_EXTRA]
    params["accountSeedRub"] = seed
    if mode:
        params["walletMode"] = mode
        if pool_rub is not None:
            params["poolRub"] = float(pool_rub)
        if dynamic_main_cap is not None:
            params["dynamicMainCapRub"] = float(dynamic_main_cap)
        if dynamic_addon_reserve is not None:
            params["dynamicAddonReserveRub"] = float(dynamic_addon_reserve)
    result["params"] = params
    return apply_journal_account_chain(result, wallets=w)


def apply_journal_account_chain(
    result: dict[str, Any],
    *,
    notional: float | None = None,
    wallets: dict[str, float] | None = None,
) -> dict[str, Any]:
    """До/После журнала: цепь **кошелька этой ноги**. Плашка — сумма трёх.

    Пол–потолок пишет в кошелёк базы. Снимки боевого счёта не трогаем.
    """
    if not isinstance(result, dict):
        return result
    params = result.get("params") or {}
    if params.get("asLive") or params.get("replayProd") or params.get("as_live"):
        return result
    trades = list(result.get("trades") or [])
    if any(
        bool(t.get("netFromAccount") or t.get("net_from_account"))
        for t in trades
    ):
        return result

    seeds: dict[str, float] | None = None
    dynamic_pool = str(params.get("walletMode") or "").strip().lower() in (
        "dynamic_pool",
        "dynamic",
        "pool",
    )
    if dynamic_pool:
        try:
            pool_seed = float(
                params.get("accountSeedRub")
                or params.get("poolRub")
                or (
                    (wallets or {}).get(WALLET_MAIN, 0)
                    + (wallets or {}).get(WALLET_ADDON, 0)
                    + (wallets or {}).get(WALLET_EXTRA, 0)
                )
                or REF_ACCOUNT_RUB
            )
        except (TypeError, ValueError):
            pool_seed = float(REF_ACCOUNT_RUB)
        if not math.isfinite(pool_seed) or pool_seed <= 0:
            pool_seed = float(REF_ACCOUNT_RUB)
        closed: list[dict[str, Any]] = []
        for t in trades:
            if str(t.get("status") or "") == "Открыта":
                continue
            raw_net = t.get("net")
            try:
                net_f = float(raw_net) if raw_net is not None else None
            except (TypeError, ValueError):
                net_f = None
            if net_f is None or not math.isfinite(net_f):
                continue
            closed.append(t)

        def _key_dyn(t: dict[str, Any]) -> tuple[int, str, str]:
            try:
                idx = int(t.get("index") or 0)
            except (TypeError, ValueError):
                idx = 0
            return (
                idx,
                _td_span_key(t.get("exitDate")) or "",
                _td_span_key(t.get("entryDate")) or "",
            )

        closed.sort(key=_key_dyn)
        running = float(pool_seed)
        for t in closed:
            net_f = float(t["net"])
            t["wallet"] = wallet_key_for_trade(t)
            t["accountBefore"] = round(running, 2)
            running = running + net_f
            t["accountAfter"] = round(running, 2)
        return result

    if wallets:
        seeds = {
            WALLET_MAIN: float(wallets.get(WALLET_MAIN) or 0.0),
            WALLET_ADDON: float(wallets.get(WALLET_ADDON) or 0.0),
            WALLET_EXTRA: float(wallets.get(WALLET_EXTRA) or 0.0),
        }
    elif params.get("walletMainRub") is not None:
        try:
            seeds = {
                WALLET_MAIN: float(params.get("walletMainRub") or 0.0),
                WALLET_ADDON: float(params.get("walletAddonRub") or 0.0),
                WALLET_EXTRA: float(params.get("walletExtraRub") or 0.0),
            }
        except (TypeError, ValueError):
            seeds = None

    if seeds is None:
        base = notional
        if base is None:
            base = params.get("notional")
        if base is None:
            summary = result.get("summary") or {}
            fe = summary.get("finalEquityRub")
            pnl = summary.get("pnlRub")
            if fe is not None and pnl is not None:
                try:
                    base = float(fe) - float(pnl)
                except (TypeError, ValueError):
                    base = None
        try:
            seed = float(base) if base is not None else float(DEFAULT_NOTIONAL)
        except (TypeError, ValueError):
            seed = float(DEFAULT_NOTIONAL)
        if not math.isfinite(seed):
            seed = float(DEFAULT_NOTIONAL)
        seeds = {WALLET_MAIN: seed, WALLET_ADDON: seed, WALLET_EXTRA: seed}

    closed: list[dict[str, Any]] = []
    for t in trades:
        if str(t.get("status") or "") == "Открыта":
            continue
        raw_net = t.get("net")
        try:
            net_f = float(raw_net) if raw_net is not None else None
        except (TypeError, ValueError):
            net_f = None
        if net_f is None or not math.isfinite(net_f):
            continue
        closed.append(t)

    def _key(t: dict[str, Any]) -> tuple[int, str, str]:
        try:
            idx = int(t.get("index") or 0)
        except (TypeError, ValueError):
            idx = 0
        return (
            idx,
            _td_span_key(t.get("exitDate")) or "",
            _td_span_key(t.get("entryDate")) or "",
        )

    closed.sort(key=_key)
    running = dict(seeds)
    for t in closed:
        net_f = float(t["net"])
        key = wallet_key_for_trade(t)
        t["wallet"] = key
        before = float(running.get(key) or 0.0)
        t["accountBefore"] = round(before, 2)
        running[key] = before + net_f
        t["accountAfter"] = round(running[key], 2)
    return result


def merge_base_plus_sticky_shelf(
    base: dict[str, Any],
    shelf: dict[str, Any],
    *,
    notional: float,
) -> dict[str, Any]:
    """База tip1m + липкая полка: полка только в окнах без открытой базы.

    Пересечения по времени отбрасываются у полки (приоритет у обычных уровней).
    """
    base_trades = list(base.get("trades") or [])
    shelf_trades = list(shelf.get("trades") or [])
    base_spans = [
        sp for t in base_trades if (sp := _trade_time_span(t)) is not None
    ]

    def _overlaps_base(t: dict[str, Any]) -> bool:
        sp = _trade_time_span(t)
        if sp is None:
            return True
        a0, a1 = sp
        return any(_spans_overlap(a0, a1, b0, b1) for b0, b1 in base_spans)

    kept_shelf = [t for t in shelf_trades if not _overlaps_base(t)]
    dropped = len(shelf_trades) - len(kept_shelf)

    merged = base_trades + kept_shelf
    merged.sort(
        key=lambda t: (
            _td_span_key(t.get("entryDate")) or "",
            _td_span_key(t.get("exitDate")) or "",
            str(t.get("tag") or ""),
        )
    )
    for i, t in enumerate(merged, start=1):
        t["index"] = i

    closed = [
        t
        for t in merged
        if str(t.get("status") or "") != "Открыта" and t.get("net") is not None
    ]
    wins = sum(1 for t in closed if float(t.get("net") or 0) > 0)
    closed_n = len(closed)
    total_pnl = sum(float(t.get("net") or 0) for t in closed)
    eq = peak = max_dd = 0.0
    for t in sorted(
        closed,
        key=lambda x: _td_span_key(x.get("exitDate")) or _td_span_key(x.get("entryDate")) or "",
    ):
        eq += float(t.get("net") or 0)
        if eq > peak:
            peak = eq
        max_dd = max(max_dd, peak - eq)
    open_n = sum(1 for t in merged if str(t.get("status") or "") == "Открыта")
    base_n = sum(
        1
        for t in closed
        if str(t.get("tag") or "") != "adapt_corridor"
    )
    shelf_n = sum(
        1
        for t in closed
        if str(t.get("tag") or "") == "adapt_corridor"
    )

    base_sum = dict(base.get("summary") or {})
    base_note = str(base_sum.get("note") or "").strip()
    shelf_params = dict(shelf.get("params") or {})
    s_lo = shelf_params.get("stickyLo", ADAPT_CORRIDOR_STICKY_LO)
    s_hi = shelf_params.get("stickyHi", ADAPT_CORRIDOR_STICKY_HI)
    add_note = (
        f" + добавка липкая полка {s_lo}…{s_hi}"
        f" ({shelf_n} сд."
        + (f", отсечено пересечений {dropped}" if dropped else "")
        + ")"
    )
    note = (base_note + add_note) if base_note else (
        f"база + липкая полка {s_lo}…{s_hi}"
    )

    base_params = dict(base.get("params") or {})
    base_params["adaptiveCorridorMode"] = True
    base_params["adaptiveCorridorAddon"] = True
    base_params["adaptiveCorridorRules"] = shelf_params.get(
        "adaptiveCorridorRules", "v3_sticky"
    )
    base_params["stickyLo"] = s_lo
    base_params["stickyHi"] = s_hi
    base_params["stickyShelfClosed"] = shelf_n
    base_params["stickyShelfDroppedOverlap"] = dropped

    capital = float(notional)
    return apply_journal_account_chain(
        {
            "trades": merged,
            "summary": {
                "trades": closed_n,
                "wins": wins,
                "winratePct": round(100.0 * wins / closed_n, 1) if closed_n else 0.0,
                "pnlRub": round(total_pnl, 2),
                "maxDdRub": round(max_dd, 2),
                "finalEquityRub": round(capital + total_pnl, 2),
                "retPct": round(100.0 * total_pnl / capital, 2) if capital else 0.0,
                "openCount": open_n,
                "note": note,
                "applyFees": True,
                "baseClosed": base_n,
                "stickyClosed": shelf_n,
            },
            "params": base_params,
        },
        notional=capital,
    )


def merge_base_plus_floor_ceiling(
    base: dict[str, Any],
    extra: dict[str, Any],
    *,
    notional: float,
) -> dict[str, Any]:
    """AUTO/добор как база; пол–потолок только в окнах без открытой базы.

    Пересечения по времени отбрасываются у пол–потолка (приоритет у базы).
    """
    base_trades = list(base.get("trades") or [])
    extra_trades = list(extra.get("trades") or [])
    base_spans = [
        sp for t in base_trades if (sp := _trade_time_span(t)) is not None
    ]

    def _overlaps_base(t: dict[str, Any]) -> bool:
        sp = _trade_time_span(t)
        if sp is None:
            return True
        a0, a1 = sp
        return any(_spans_overlap(a0, a1, b0, b1) for b0, b1 in base_spans)

    kept = [t for t in extra_trades if not _overlaps_base(t)]
    dropped = len(extra_trades) - len(kept)

    merged = base_trades + kept
    merged.sort(
        key=lambda t: (
            _td_span_key(t.get("entryDate")) or "",
            _td_span_key(t.get("exitDate")) or "",
            str(t.get("tag") or ""),
        )
    )
    for i, t in enumerate(merged, start=1):
        t["index"] = i

    closed = [
        t
        for t in merged
        if str(t.get("status") or "") != "Открыта" and t.get("net") is not None
    ]
    wins = sum(1 for t in closed if float(t.get("net") or 0) > 0)
    closed_n = len(closed)
    total_pnl = sum(float(t.get("net") or 0) for t in closed)
    eq = peak = max_dd = 0.0
    for t in sorted(
        closed,
        key=lambda x: _td_span_key(x.get("exitDate")) or _td_span_key(x.get("entryDate")) or "",
    ):
        eq += float(t.get("net") or 0)
        if eq > peak:
            peak = eq
        max_dd = max(max_dd, peak - eq)
    open_n = sum(1 for t in merged if str(t.get("status") or "") == "Открыта")
    extra_n = sum(1 for t in closed if str(t.get("tag") or "") == "shelf_ff")
    base_n = closed_n - extra_n

    base_sum = dict(base.get("summary") or {})
    base_note = str(base_sum.get("note") or "").strip()
    add_note = (
        f" + пол–потолок ({extra_n} сд."
        + (f", отсечено пересечений {dropped}" if dropped else "")
        + ")"
    )
    note = (base_note + add_note) if base_note else (
        "база + пол–потолок (полка)"
    )

    base_params = dict(base.get("params") or {})
    extra_params = dict(extra.get("params") or {})
    base_params["shelfFloorCeilingMode"] = True
    base_params["shelfFloorCeilingClosed"] = extra_n
    base_params["shelfFloorCeilingDroppedOverlap"] = dropped
    if extra_params.get("takeProfitPct") is not None:
        base_params["shelfTakeProfitPct"] = extra_params.get("takeProfitPct")
    if extra_params.get("maxHoldDaysNoExitTrend") is not None:
        base_params["shelfMaxHoldDaysNoExitTrend"] = extra_params.get(
            "maxHoldDaysNoExitTrend"
        )

    capital = float(notional)
    return apply_journal_account_chain(
        {
            "trades": merged,
            "summary": {
                "trades": closed_n,
                "wins": wins,
                "winratePct": round(100.0 * wins / closed_n, 1) if closed_n else 0.0,
                "pnlRub": round(total_pnl, 2),
                "maxDdRub": round(max_dd, 2),
                "finalEquityRub": round(capital + total_pnl, 2),
                "retPct": round(100.0 * total_pnl / capital, 2) if capital else 0.0,
                "openCount": open_n,
                "note": note,
                "applyFees": True,
                "baseClosed": base_n,
                "shelfFfClosed": extra_n,
                "exitReasons": dict((base_sum.get("exitReasons") or {})),
            },
            "params": base_params,
        },
        notional=capital,
    )


def run_touch_1m_pnl_only(
    prep: PreparedTips,
    entry: float,
    exit_z: float,
    *,
    window_start_ms: int = 0,
    window_end_ms: int = 0,
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
        edges = _edges_tip1m_settled(
            prep, _edges_from(prep, window_start_ms, window_end_ms)
        )
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
                # MTM как _mtm_at; порог ТП по Чист.% (MTM − комиссия выхода).
                sp = sp_arr[i]
                is_long = pos == 1
                pnl_pts = (sp - entry_sp) if is_long else (entry_sp - sp)
                mtm = eff * (pnl_pts / 100.0) - entry_comm - (
                    ovn_day * max(0, int(day_ord[i]) - entry_day)
                )
                # Чист.% = MTM − комиссия выхода.
                if (mtm - comm) / max(1.0, pos_notional) * 100.0 >= take_profit_pct:
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
    window_end_ms: int = 0,
    compound: bool = False,
    slip: float = DEFAULT_SLIP,
    notional: float = DEFAULT_NOTIONAL,
    take_profit_pct: float = 0.0,
    edges: np.ndarray | None = None,
    max_hold_days_if_losing: float = 0.0,
    max_hold_days_no_exit_trend: float = 0.0,
    force_close_3d: bool = False,
    force_close_3d_mode: str = "force",
    exit_trend_min_pp: float = 0.2,
) -> tuple[float, int]:
    """Fast heatmap path for spread-level mode (absolute S%, no Z).

    Same rules as ``run_touch_1m_trades(..., spread_level_mode=True)`` + optional TP,
    without allocating trade dicts. Caller should share ``prep`` / ``edges`` across cells.
    """
    from live.force_close_3d import normalize_mode as _norm_3d_mode
    from live.signals import Position as _Pos
    from live.spread_levels import SpreadLevels, determine_spread_level_signal

    sp_arr = prep.spread
    day_ord = prep.day_ord
    if edges is None:
        edges = _edges_tip1m_settled(
            prep, _edges_from(prep, window_start_ms, window_end_ms)
        )
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
    hold_lose_days = int(max_hold_days_if_losing) if max_hold_days_if_losing > 0 else 0
    hold_no_trend_days = (
        int(max_hold_days_no_exit_trend) if max_hold_days_no_exit_trend > 0 else 0
    )
    use_close_3d = bool(force_close_3d)
    mode_3d = _norm_3d_mode(force_close_3d_mode)
    sit_3d: dict[str, Any] | None = None
    trend_min_pp = max(0.0, float(exit_trend_min_pp))

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
                if (mtm - comm) / max(1.0, pos_notional) * 100.0 >= take_profit_pct:
                    _close_at(i, is_long)
                    continue
            held = int(day_ord[i]) - entry_day
            if use_close_3d:
                is_long = pos == 1
                pnl_pts = (cur_sp - entry_sp) if is_long else (entry_sp - cur_sp)
                mtm = eff * (pnl_pts / 100.0) - entry_comm - (
                    ovn_day * max(0, held)
                )
                sit_3d, force_now = _situation_3d_on_bar(
                    sit_3d,
                    enabled=True,
                    mode=mode_3d,
                    flat=False,
                    is_long=is_long,
                    held=held,
                    entry_sp=entry_sp,
                    cur_sp=cur_sp,
                    exit_lv=_exit_level_for_pos(is_long, lv),
                    entry_lv=_entry_level_for_pos(is_long, lv),
                    mtm=mtm,
                )
                if force_now:
                    _close_at(i, is_long)
                    continue
            if hold_lose_days > 0 and held >= hold_lose_days:
                is_long = pos == 1
                pnl_pts = (cur_sp - entry_sp) if is_long else (entry_sp - cur_sp)
                mtm = eff * (pnl_pts / 100.0) - entry_comm - (
                    ovn_day * max(0, held)
                )
                if mtm < 0:
                    _close_at(i, is_long)
                    continue
            if hold_no_trend_days > 0 and held >= hold_no_trend_days:
                is_long = pos == 1
                pnl_pts = (cur_sp - entry_sp) if is_long else (entry_sp - cur_sp)
                mtm = eff * (pnl_pts / 100.0) - entry_comm - (
                    ovn_day * max(0, held)
                )
                if mtm < 0:
                    toward = (
                        (cur_sp - entry_sp) >= trend_min_pp
                        if is_long
                        else (entry_sp - cur_sp) >= trend_min_pp
                    )
                    if not toward:
                        if is_long:
                            exit_lv = float(lv.exit_narrow)
                            toward = (exit_lv - cur_sp) <= (exit_lv - entry_sp) - trend_min_pp
                        else:
                            exit_lv = float(lv.exit_wide)
                            toward = (cur_sp - exit_lv) <= (entry_sp - exit_lv) - trend_min_pp
                    if not toward:
                        _close_at(i, is_long)
                        continue
            pos_enum = _Pos.LONG if pos == 1 else _Pos.SHORT
            s = determine_spread_level_signal(prev_sp, cur_sp, pos_enum, lv)
            if s.value == "EXIT_LONG":
                _close_at(i, True)
            elif s.value == "EXIT_SHORT":
                _close_at(i, False)
            continue

        if use_close_3d and sit_3d is not None:
            en_clr = _clear_entry_level_from_sit(sit_3d, lv)
            sit_3d, _ = _situation_3d_on_bar(
                sit_3d,
                enabled=True,
                mode=mode_3d,
                flat=True,
                is_long=None,
                held=None,
                entry_sp=None,
                cur_sp=cur_sp,
                exit_lv=None,
                entry_lv=en_clr,
                mtm=None,
            )
        s = determine_spread_level_signal(prev_sp, cur_sp, _Pos.FLAT, lv)
        if s.value == "ENTER_LONG":
            sig = 1
        elif s.value == "ENTER_SHORT":
            sig = 2
        else:
            continue
        if _entry_blocked_3d(sit_3d, want_long=(sig == 1)):
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
    end: str | None = None,
    as_live: bool = False,
    replay_prod: bool = False,
    regime_z_mode: bool = False,
    spread_level_mode: bool = False,
    addon_mode: bool = False,
    extreme_addon_mode: bool = False,
    transition_swing_mode: bool = False,
    adaptive_corridor_mode: bool = False,
    shelf_floor_ceiling_mode: bool = False,
    base_mode: bool = True,
    wallet_main: float | None = None,
    wallet_addon: float | None = None,
    wallet_extra: float | None = None,
    weekend_trading: bool = False,
) -> str:
    # v11: Prod open/closed fill overlay on entry (S%вх + Min/Max path = desk model).
    # v12: Testing addon 2/7 pyramid (вариант 2).
    # v13: Testing corridor swing (коричневая зона / качалка).
    # v14: explicit end date for window.
    # v16: адапт. коридор v3 — липкая полка −0.25…1 по умолчанию.
    # v17: адапт. коридор = добавка к базе (не замена).
    # v19: экстра в зоне ≤1/≥9 при любой другой открытой ноге, в т.ч. на баре базы.
    # v20: netRef100k / retPctFlatOf100k (доли 40/30/30 к счёту 100 000, без роста лота).
    # v24: Tesт лоты = депозит×плечо/пара (как 66 акций на 10 000), без потолка 80 и без ГО.
    # v25: три кошелька 40/30/30; пол–потолок с кошелька базы (сначала выход базы).
    # v26: Тест добор/экстра — динамический пул (база/резерв/остаток).
    # v27: потолки пула в долях (база ≤60%, резерв 40%) — при капитализации растут с пулом.
    # v28: hold + доли dyn-пула в ключе (UI Тест 6.1/5.8 · hold=10 · dyn 0.60/0.40).
    # v32: Hit/Max/ТП = Чист.% (вход+выход+овернайт) / депозит.
    # v33: Тест чип «База» — выкл не открывает новые AUTO-ноги 3.2/6.1.
    return (
        f"{tip_key}|v33|as={int(as_live)}|rp={int(replay_prod)}|"
        f"sl={int(spread_level_mode)}|rz={int(regime_z_mode)}|ad={int(addon_mode)}|"
        f"xe={int(extreme_addon_mode)}|ba={int(base_mode)}|"
        f"zs={int(transition_swing_mode)}|ac={int(adaptive_corridor_mode)}|"
        f"ff={int(shelf_floor_ceiling_mode)}|we={int(weekend_trading)}|"
        f"e={entry:.4f}|x={exit_z:.4f}|s={slip:.4f}|"
        f"n={notional:.2f}|c={int(compound)}|tp={take_profit_pct:.4f}|"
        f"wm={float(wallet_main or 0):.0f}|wa={float(wallet_addon or 0):.0f}|"
        f"we={float(wallet_extra or 0):.0f}|"
        f"start={start or ''}|end={end or ''}"
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
    end: str | None = None,
) -> dict[str, Any]:
    """Mark sparse Prod coverage vs selected start→end window (UI hint)."""
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
    end_s = str(end or "").strip()
    if len(start_s) >= 10:
        try:
            start_d = _parse_td(start_s[:10] + " 00:00:00").date()
            if len(end_s) >= 10:
                end_d = _parse_td(end_s[:10] + " 00:00:00").date()
            else:
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


def _ref_deposit_rub(trade: dict[str, Any]) -> float:
    """Депозит ноги как на боевом счёте 100 000 (40 / 30 / 30)."""
    tag = str(trade.get("tag") or "").strip().lower()
    src = str(trade.get("source") or "").strip().lower()
    blob = f"{tag} {src}"
    if tag in ("addon", "добор") or "добор" in src:
        return REF_ADDON_DEP_RUB
    if tag in ("extra", "extreme", "экстра") or "экстра" in src:
        return REF_EXTRA_DEP_RUB
    if tag in ("swing", "качалка") or "качалка" in blob:
        return REF_ADDON_DEP_RUB
    return REF_MAIN_DEP_RUB


def _fees_direction(trade: dict[str, Any]) -> str | None:
    raw = str(trade.get("direction") or "").strip().lower()
    if raw.startswith("l"):
        return "LONG"
    if raw.startswith("s"):
        return "SHORT"
    return None


def _trade_hold_days(trade: dict[str, Any]) -> int:
    """Календарные дни переноса: из overnight / ступени, иначе по датам."""
    ovn = float(trade.get("overnight") or 0.0)
    nom = float(trade.get("notional") or 0.0)
    if nom > 1.0 and ovn > 0.0:
        _eff, _comm, ovn_day = _fees(nom, direction=_fees_direction(trade))
        if ovn_day > 1e-9:
            return max(0, int(round(ovn / ovn_day)))
    entry = str(trade.get("entryDate") or "")
    exit_d = str(trade.get("exitDate") or "")
    if not entry or not exit_d or exit_d.strip() in ("—", "-", ""):
        return 0
    try:
        a = _parse_td(entry)
        b = _parse_td(exit_d)
        return max(0, (b.date() - a.date()).days)
    except Exception:
        return 0


def apply_ref_100k_metrics(result: dict[str, Any]) -> dict[str, Any]:
    """Те же сделки, размер ног 40/30/30, % от 100 000 — без роста лота от прибыли.

    Входы/выходы как в прогоне Теста; overnight пересчитывается по ступеням
    на боевом депозите ноги (ступени не линейны от номинала).
    """
    trades = list(result.get("trades") or [])
    closed_ref: list[tuple[str, float]] = []
    for t in trades:
        if str(t.get("status") or "") == "Открыта" or t.get("net") is None:
            t["netRef100k"] = None
            continue
        pnl_pts = t.get("pnlPts")
        try:
            pts = float(pnl_pts) if pnl_pts is not None else float("nan")
        except (TypeError, ValueError):
            pts = float("nan")
        if not math.isfinite(pts):
            t["netRef100k"] = None
            continue
        dep = _ref_deposit_rub(t)
        direction = _fees_direction(t)
        days = _trade_hold_days(t)
        orig_n = float(t.get("notional") or 0.0)
        gross = t.get("gross")
        comm = t.get("commission")
        try:
            gross_n = float(gross) if gross is not None else None
            comm_n = float(comm) if comm is not None else None
        except (TypeError, ValueError):
            gross_n = comm_n = None
        if orig_n > 1.0 and gross_n is not None and comm_n is not None:
            scale = dep / orig_n
            gross_ref = gross_n * scale
            comm_ref = comm_n * scale
        else:
            eff, comm_one, _ovn = _fees(dep, direction=direction)
            gross_ref = eff * (pts / 100.0)
            comm_ref = comm_one * 2.0
        _eff, _comm, ovn_day = _fees(dep, direction=direction)
        net_ref = gross_ref - comm_ref - ovn_day * days
        t["netRef100k"] = round(float(net_ref), 4)
        exit_k = _td_span_key(t.get("exitDate")) or _td_span_key(t.get("entryDate")) or ""
        closed_ref.append((exit_k, float(net_ref)))
    closed_ref.sort(key=lambda x: x[0])
    total = peak = max_dd = 0.0
    for _k, net in closed_ref:
        total += net
        if total > peak:
            peak = total
        max_dd = max(max_dd, peak - total)
    summary = dict(result.get("summary") or {})
    summary["refAccountRub"] = REF_ACCOUNT_RUB
    summary["pnlFlatOf100k"] = round(total, 2)
    summary["maxDdFlatOf100k"] = round(max_dd, 2)
    summary["retPctFlatOf100k"] = (
        round(100.0 * total / REF_ACCOUNT_RUB, 2) if REF_ACCOUNT_RUB else 0.0
    )
    result["summary"] = summary
    result["trades"] = trades
    params = dict(result.get("params") or {})
    params["refAccountRub"] = REF_ACCOUNT_RUB
    params["refMainDepRub"] = REF_MAIN_DEP_RUB
    params["refAddonDepRub"] = REF_ADDON_DEP_RUB
    params["refExtraDepRub"] = REF_EXTRA_DEP_RUB
    result["params"] = params
    return result


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
    end: str | None = None,
    as_live: bool = False,
    replay_prod: bool = False,
    regime_z_mode: bool = False,
    spread_level_mode: bool = False,
    spread_levels: dict[str, float] | None = None,
    max_hold_days_if_losing: float = 0.0,
    max_hold_days_no_exit_trend: float = 0.0,
    force_close_3d: bool = False,
    force_close_3d_mode: str = "force",
    addon_mode: bool = False,
    extreme_addon_mode: bool = False,
    transition_swing_mode: bool = False,
    adaptive_corridor_mode: bool = False,
    shelf_floor_ceiling_mode: bool = False,
    base_mode: bool = True,
    main_notional: float | None = None,
    addon_notional: float | None = None,
    extra_notional: float | None = None,
    wallet_mode: str | None = None,
    pool_rub: float | None = None,
    dynamic_main_cap_frac: float | None = None,
    dynamic_addon_reserve_frac: float | None = None,
    weekend_trading: bool = False,
) -> dict[str, Any]:
    """Tip1m Testing sim.

    ``as_live`` / ``replay_prod``: Prod-authoritative edges from decision_bars +
    closed trades (parity with History) — respects ``start``/``end`` window filter.

    Off («финальный CSV»): geometric path over ``start``→``end`` (end empty = до хвоста).

    ``spread_level_mode``: geometric absolute S levels (same as Prod AUTO).
    ``regime_z_mode``: legacy Z-by-regime (ignored when spread levels ON).
    ``spread_levels``: optional override of enter/exit wide+narrow (heatmap click).
    ``addon_mode``: Testing вариант 2 — база + добор 2/7 (не Prod AUTO).
    ``extreme_addon_mode``: Testing экстра 1/9 — Long≤1→2 · Short≥9→7 при открытой базе.
    ``transition_swing_mode``: Testing качалка зоны — ping-pong L вых…S вых после выхода базы.
    ``adaptive_corridor_mode``: Testing — добавка липкой полки −0.25…1 к обычной базе
    (не замена; пересечения по времени у полки отсекаются). Не Prod AUTO.
    ``shelf_floor_ceiling_mode``: Testing добавка пол–потолок по каузальной широкой полке
    (кошелёк базы; если база открыта — сначала выход базы, потом вход полки).
    ``base_mode``: Testing чип «База». По умолчанию ВКЛ. Выкл — не открывать новые
    AUTO-ноги (main / 3.2/6.1). Не Prod AUTO.
    """
    csv = resolve_csv_for_window(csv, start, end)
    prep, meta = ensure_tip_series(csv, start=start, end=end)
    tip_key = str(_tip_cache.get("key") or "")
    # «как Прод» must replay Prod actions (Test ↔ History). Geometric only when off.
    use_replay = bool(replay_prod) or bool(as_live)
    use_weekend = bool(weekend_trading) and not use_replay
    prep = with_weekend_trading_session(prep, enabled=use_weekend)
    # Spread levels / regime only on geometric path (as_live ignores thresholds).
    use_spread = bool(spread_level_mode) and not use_replay
    use_regime = bool(regime_z_mode) and not use_replay and not use_spread
    use_adapt = bool(adaptive_corridor_mode) and not use_replay
    use_swing = (
        bool(transition_swing_mode)
        and not use_replay
        and use_spread
        and not bool(addon_mode)
        and not bool(extreme_addon_mode)
    )
    use_addon = (
        bool(addon_mode)
        and not use_replay
        and use_spread
        and not use_swing
    )
    use_extreme = (
        bool(extreme_addon_mode)
        and not use_replay
        and use_spread
        and not use_swing
    )
    use_pyramid = use_addon or use_extreme
    use_shelf = bool(shelf_floor_ceiling_mode) and not use_replay
    use_base = bool(base_mode) if not use_replay else True
    mode_raw = str(wallet_mode or "").strip().lower()
    use_dyn_pool = use_pyramid and mode_raw in ("dynamic_pool", "dynamic", "pool", "")
    if use_pyramid and mode_raw not in ("", "dynamic_pool", "dynamic", "pool"):
        use_dyn_pool = False
    pool_seed: float | None = None
    if pool_rub is not None:
        try:
            pool_seed = float(pool_rub)
        except (TypeError, ValueError):
            pool_seed = None
        if pool_seed is not None and (not math.isfinite(pool_seed) or pool_seed <= 0):
            pool_seed = None
    wallets = resolve_test_wallets(
        notional=float(notional),
        main_notional=main_notional,
        addon_notional=addon_notional,
        extra_notional=extra_notional,
        pool_rub=pool_seed,
        dynamic_pool=use_dyn_pool and pool_seed is not None,
    )
    dep_main = wallets[WALLET_MAIN]
    dep_addon = wallets[WALLET_ADDON]
    dep_extra = wallets[WALLET_EXTRA]
    sl_part = ""
    if use_spread and spread_levels:
        sl_part = (
            f"|lv={float(spread_levels.get('spread_enter_wide', spread_levels.get('enter_wide', 6.2))):.2f}"
            f"/{float(spread_levels.get('spread_exit_wide', spread_levels.get('exit_wide', 5.8))):.2f}"
            f"/{float(spread_levels.get('spread_enter_narrow', spread_levels.get('enter_narrow', 3.2))):.2f}"
            f"/{float(spread_levels.get('spread_exit_narrow', spread_levels.get('exit_narrow', 4.0))):.2f}"
        )
    hold_lose = max(0.0, float(max_hold_days_if_losing or 0))
    hold_no_trend = max(0.0, float(max_hold_days_no_exit_trend or 0))
    use_close_3d = bool(force_close_3d)
    from live.force_close_3d import normalize_mode as _norm_3d_mode

    mode_3d = _norm_3d_mode(force_close_3d_mode)
    dyn_main_frac = (
        float(dynamic_main_cap_frac)
        if dynamic_main_cap_frac is not None
        else float(DYNAMIC_POOL_MAIN_CAP_FRAC)
    )
    dyn_addon_frac = (
        float(dynamic_addon_reserve_frac)
        if dynamic_addon_reserve_frac is not None
        else float(DYNAMIC_POOL_ADDON_RESERVE_FRAC)
    )
    sl_part += (
        f"|hl={hold_lose:.0f}|hn={hold_no_trend:.0f}"
        f"|c3={int(use_close_3d)}|{mode_3d[:3]}"
        f"|dp={int(use_dyn_pool)}|pr={float(pool_seed or 0):.0f}"
        f"|dm={dyn_main_frac:.4f}|da={dyn_addon_frac:.4f}"
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
        end=end,
        as_live=as_live,
        replay_prod=use_replay,
        regime_z_mode=use_regime,
        spread_level_mode=use_spread,
        addon_mode=use_addon,
        extreme_addon_mode=use_extreme,
        base_mode=use_base,
        transition_swing_mode=use_swing,
        adaptive_corridor_mode=use_adapt,
        shelf_floor_ceiling_mode=use_shelf,
        wallet_main=dep_main,
        wallet_addon=dep_addon,
        wallet_extra=dep_extra,
        weekend_trading=use_weekend,
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
        if use_pyramid:
            parts = (
                ["база Short ≥6.2 / ≤5.8 · Long ≤3.2 / ≥4.0"]
                if use_base
                else ["без базы (AUTO-ноги выкл)"]
            )
            if use_addon:
                parts.append("добор 2/7 (Long→3.2, Short→6.2)")
            if use_extreme:
                parts.append("экстра 1/9 (Long→2, Short→7)")
            note_geo = (
                "пирамида · "
                + " + ".join(parts)
                + ". Динамический пул: база ≤"
                + f"{DYNAMIC_POOL_MAIN_CAP_FRAC * 100:.0f}% пула с резервом "
                + f"{DYNAMIC_POOL_ADDON_RESERVE_FRAC * 100:.0f}% под добор, "
                "экстра = свободный остаток (в т.ч. крупный). "
                "При капитализации потолки растут вместе с пулом. "
                "Добавки при открытой базе/хозяине. Только Тест, не Prod AUTO."
            )
        elif use_swing:
            note_geo = (
                "качалка зоны: база + ping-pong в коричневой зоне (L вых…S вых, "
                "обычно 4.0…5.8%). После выхода Long → Short на верхней границе, "
                "выход на нижней; после выхода Short → Long на нижней, выход на верхней. "
                "Только Тест, не Prod AUTO."
            )
    elif use_regime:
        note_geo = (
            "касание 1м + режим спреда (legacy Z): узкий ±1.0/±0.7 · широкий ±1.6/±1.3 · "
            "переход без входа; выход зафиксирован на режиме входа. "
            "Settle +10с как Prod. Heatmap остаётся классической сеткой E×X."
        )
    if use_adapt:
        note_geo += (
            " Добавка: липкая полка S% −0.25…1 (Long у низа; выход верх/ТП1.5%/"
            "слом/S≥3.2/нет хода 5д; без пересечения с базой). Не Prod AUTO."
        )
    hold_bits: list[str] = []
    if hold_no_trend > 0:
        hold_bits.append(f"нет хода {int(hold_no_trend)}д")
    if hold_lose > 0:
        hold_bits.append(f"в минусе {int(hold_lose)}д")
    if hold_bits:
        note_geo += " Стоп: " + ", ".join(hold_bits) + "."
    if use_shelf:
        note_geo += (
            " Добавка: пол–потолок (широкая полка, каузально; "
            "Short на потолке, Long на поле; выход кромка / цель панели / "
            "нет хода / вынос; берёт кошелёк базы: если база открыта — "
            "сначала выход базы, потом вход полки)."
        )
    if not use_base:
        note_geo += (
            " База выкл: новые AUTO-ноги (касание 3.2/6.1) не открываются; "
            "добор/экстра без открытой базы не входят; полка живёт отдельно."
        )
    note_prod = (
        "как Прод: входы/выходы из decision_bars + закрытых Prod-сделок "
        "(как вкладка История; пороги Z в симе не двигают edges)."
    )
    note_plain = (
        "Risk exit группы в режиме касания 1м не учитываются "
        "(уровни спреда + ТП + стоп по дням)."
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
            "end": end,
            "asLive": as_live,
            "replayProd": use_replay,
            "asLiveActions": hit.get("asLiveActions", 0),
            "windowStartMs": hit.get("windowStartMs", 0),
            "windowEndMs": hit.get("windowEndMs", 0),
            "simSec": 0.0,
            "simCacheHit": True,
            "riskExitNoteRu": risk_note,
            "tip1mSettleSec": settle_meta,
            "addonMode": use_addon,
            "extremeAddonMode": use_extreme,
            "baseMode": use_base,
            "enableBase": use_base,
            "transitionSwingMode": use_swing,
            "adaptiveCorridorMode": use_adapt,
            "shelfFloorCeilingMode": use_shelf,
            "weekendTrading": use_weekend,
            "weekendWindowMsk": "10:00–18:59" if use_weekend else None,
        }
        payload = {
            "trades": hit["trades"],
            "summary": dict(hit["summary"]),
            "params": dict(hit["params"]),
            "meta": _enrich_as_live_sparse_meta(
                base_meta, hit["trades"], use_replay=use_replay, start=start, end=end
            ),
        }
        if not use_replay:
            attach_wallet_summary(
                payload,
                wallets,
                wallet_mode="dynamic_pool" if use_pyramid else None,
                pool_rub=(dep_main + dep_addon + dep_extra) if use_pyramid else None,
                dynamic_main_cap=(
                    scale_dynamic_pool_caps(dep_main + dep_addon + dep_extra)[0]
                    if use_pyramid
                    else None
                ),
                dynamic_addon_reserve=(
                    scale_dynamic_pool_caps(dep_main + dep_addon + dep_extra)[1]
                    if use_pyramid
                    else None
                ),
            )
        return payload

    wms = _window_start_ms(prep, start)
    wems = _window_end_ms(end)
    actions = None
    if use_replay:
        actions = build_as_live_tip_actions(
            prep, window_start_ms=wms, window_end_ms=wems
        )
    t0 = time.time()
    pool_rub = float(pool_seed if pool_seed is not None else (dep_main + dep_addon + dep_extra))
    dyn_main_cap, dyn_addon_res = scale_dynamic_pool_caps(pool_rub)
    if dynamic_main_cap_frac is not None or dynamic_addon_reserve_frac is not None:
        dyn_main_cap = pool_rub * max(0.0, dyn_main_frac)
        dyn_addon_res = pool_rub * max(0.0, dyn_addon_frac)
    dyn_fn = (
        make_dynamic_pool_deposit_fn(
            pool=pool_rub,
            main_cap_frac=dyn_main_frac,
            addon_reserve_frac=dyn_addon_frac,
            extra_soft_cap=None,
        )
        if use_pyramid
        else None
    )
    if use_pyramid or use_shelf:
        result = run_base_plus_addon(
            prep,
            slip=slip,
            notional=dep_main,
            compound=compound,
            window_start_ms=wms,
            window_end_ms=wems,
            spread_levels=spread_levels,
            take_profit_pct=take_profit_pct,
            max_hold_days_if_losing=hold_lose,
            max_hold_days_no_exit_trend=hold_no_trend,
            force_close_3d=use_close_3d,
            force_close_3d_mode=mode_3d,
            enable_addon=use_addon,
            enable_extreme=use_extreme,
            enable_base=use_base,
            enable_shelf_ff=use_shelf,
            main_notional=dep_main,
            addon_notional=dep_addon,
            extra_notional=dep_extra,
            leg_deposit_fn=dyn_fn,
        )
    elif not use_base:
        result = run_base_plus_addon(
            prep,
            slip=slip,
            notional=dep_main,
            compound=compound,
            window_start_ms=wms,
            window_end_ms=wems,
            spread_levels=spread_levels,
            take_profit_pct=take_profit_pct,
            max_hold_days_if_losing=hold_lose,
            max_hold_days_no_exit_trend=hold_no_trend,
            force_close_3d=use_close_3d,
            force_close_3d_mode=mode_3d,
            enable_addon=False,
            enable_extreme=False,
            enable_base=False,
            enable_shelf_ff=False,
            main_notional=dep_main,
            addon_notional=dep_addon,
            extra_notional=dep_extra,
            leg_deposit_fn=dyn_fn,
        )
    elif use_swing:
        result = run_base_plus_transition_swing(
            prep,
            slip=slip,
            notional=dep_main,
            window_start_ms=wms,
            window_end_ms=wems,
            spread_levels=spread_levels,
            take_profit_pct=take_profit_pct,
            max_hold_days_if_losing=hold_lose,
            max_hold_days_no_exit_trend=hold_no_trend,
            force_close_3d=use_close_3d,
            force_close_3d_mode=mode_3d,
        )
    else:
        result = run_touch_1m_trades(
            prep,
            entry,
            exit_z,
            window_start_ms=wms,
            window_end_ms=wems,
            compound=compound,
            slip=slip,
            notional=dep_main,
            take_profit_pct=take_profit_pct,
            max_hold_days_if_losing=hold_lose,
            max_hold_days_no_exit_trend=hold_no_trend,
            force_close_3d=use_close_3d,
            force_close_3d_mode=mode_3d,
            as_live_actions=actions,
            regime_z_mode=use_regime,
            spread_level_mode=use_spread,
            spread_levels=spread_levels if use_spread else None,
        )
    if use_adapt:
        shelf = run_adaptive_corridor(
            prep,
            slip=slip,
            notional=notional,
            window_start_ms=wms,
            window_end_ms=wems,
            apply_fees=True,
        )
        result = merge_base_plus_sticky_shelf(
            result, shelf, notional=float(dep_main)
        )
    apply_ref_100k_metrics(result)
    result.setdefault("params", {})
    result["params"]["weekendTrading"] = use_weekend
    result["params"]["baseMode"] = use_base
    result["params"]["enableBase"] = use_base
    if not use_replay:
        attach_wallet_summary(
            result,
            wallets,
            wallet_mode="dynamic_pool" if use_pyramid else None,
            pool_rub=pool_rub if use_pyramid else None,
            dynamic_main_cap=dyn_main_cap if use_pyramid else None,
            dynamic_addon_reserve=dyn_addon_res if use_pyramid else None,
        )
    base_meta = {
        **meta,
        "start": start,
        "end": end,
        "asLive": as_live,
        "replayProd": use_replay,
        "asLiveActions": len(actions or []),
        "windowStartMs": wms,
        "windowEndMs": wems,
        "simSec": round(time.time() - t0, 3),
        "simCacheHit": False,
        "riskExitNoteRu": risk_note,
        "tip1mSettleSec": settle_meta,
        "addonMode": use_addon,
        "extremeAddonMode": use_extreme,
        "baseMode": use_base,
        "enableBase": use_base,
        "transitionSwingMode": use_swing,
        "adaptiveCorridorMode": use_adapt,
        "shelfFloorCeilingMode": use_shelf,
        "weekendTrading": use_weekend,
        "weekendWindowMsk": "10:00–18:59" if use_weekend else None,
    }
    result["meta"] = _enrich_as_live_sparse_meta(
        base_meta, result.get("trades") or [], use_replay=use_replay, start=start, end=end
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
                "windowEndMs": wems,
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
    end: str | None = None,
    spread_level_mode: bool = False,
    band: str = "wide",
    enter_wide: float | None = None,
    exit_wide: float | None = None,
    enter_narrow: float | None = None,
    exit_narrow: float | None = None,
    max_hold_days_if_losing: float = 0.0,
    max_hold_days_no_exit_trend: float = 0.0,
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
    hold_lose = max(0.0, float(max_hold_days_if_losing or 0))
    hold_no_trend = max(0.0, float(max_hold_days_no_exit_trend or 0))

    csv = resolve_csv_for_window(csv, start, end)
    prep, meta = ensure_tip_series(csv, start=start, end=end)
    tip_key = str(_tip_cache.get("key") or "")
    hkey = (
        f"{tip_key}|hm|v4|sl={int(use_spread)}|b={band_s}|"
        f"{entry_min:.2f}|{entry_max:.2f}|{exit_min:.2f}|{x_max:.2f}|{step:.2f}|"
        f"ew={ew:.2f}|xw={xw:.2f}|en={en:.2f}|xn={xn:.2f}|"
        f"s={slip:.4f}|n={notional:.2f}|c={int(compound)}|tp={take_profit_pct:.4f}|"
        f"hl={hold_lose:.0f}|hn={hold_no_trend:.0f}|"
        f"start={start or ''}|end={end or ''}"
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
    wems = _window_end_ms(end)
    edges = _edges_tip1m_settled(prep, _edges_from(prep, wms, wems))
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
                        max_hold_days_if_losing=hold_lose,
                        max_hold_days_no_exit_trend=hold_no_trend,
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
                        max_hold_days_if_losing=hold_lose,
                        max_hold_days_no_exit_trend=hold_no_trend,
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
        hold_note: list[str] = []
        if hold_no_trend > 0:
            hold_note.append(f"нет хода {int(hold_no_trend)}д")
        if hold_lose > 0:
            hold_note.append(f"в минусе {int(hold_lose)}д")
        if hold_note:
            risk_note += " Стоп: " + ", ".join(hold_note) + "."
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
        "end": end,
        "windowStartMs": wms,
        "windowEndMs": wems,
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
        "maxHoldDaysIfLosing": int(hold_lose) if hold_lose > 0 else 0,
        "maxHoldDaysNoExitTrend": int(hold_no_trend) if hold_no_trend > 0 else 0,
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
# После обрезки по дням не отдаём 80к минуток: Ctrl+F5 иначе минуты на JSON.
CHART_UI_MAX_BARS = 12_000


def _chart_thin_minutes(n: int, max_bars: int) -> int:
    cap = max(1, int(max_bars))
    if n <= cap:
        return 1
    minutes = 5
    if math.ceil(n / 5) > cap:
        minutes = 15
    if math.ceil(n / 15) > cap:
        minutes = 30
    if math.ceil(n / 30) > cap:
        minutes = 60
    return minutes


def _select_chart_bar_indices(
    ts_ms: np.ndarray,
    lo: int,
    hi: int,
    *,
    max_bars: int = CHART_UI_MAX_BARS,
) -> tuple[np.ndarray, int]:
    """Индексы баров для графика: последний в корзине N мин — без 700k dict."""
    lo = max(0, int(lo))
    hi = max(lo, int(hi))
    n = hi - lo
    if n <= 0:
        return np.zeros(0, dtype=np.int64), 1
    cap = max(1, int(max_bars))
    if n <= cap:
        return np.arange(lo, hi, dtype=np.int64), 1
    minutes = _chart_thin_minutes(n, cap)
    bucket = int(minutes) * 60_000
    seg = np.asarray(ts_ms[lo:hi], dtype=np.int64)
    keys = seg // bucket
    change = np.empty(len(keys), dtype=bool)
    change[0] = True
    if len(keys) > 1:
        change[1:] = keys[1:] != keys[:-1]
    starts = np.flatnonzero(change)
    ends = np.empty(len(starts), dtype=np.int64)
    ends[:-1] = starts[1:] - 1
    ends[-1] = len(keys) - 1
    idx = (lo + ends).astype(np.int64, copy=False)
    if int(idx[0]) != lo:
        idx = np.concatenate((np.array([lo], dtype=np.int64), idx))
    last = hi - 1
    if int(idx[-1]) != last:
        idx = np.concatenate((idx, np.array([last], dtype=np.int64)))
    if len(idx) > cap:
        take = np.linspace(0, len(idx) - 1, cap, dtype=np.int64)
        take[0] = 0
        take[-1] = len(idx) - 1
        idx = idx[np.unique(take)]
    return idx, minutes


def _repair_chart_timestamp_ms(ms: Any, trade_date: Any) -> int:
    """0 / пусто из sqlite не должно попасть в LC как time=0 / NaN."""
    try:
        n = int(ms or 0)
    except (TypeError, ValueError):
        n = 0
    if n > 0:
        return n
    from replay.replay_data import parse_ts_ms

    return int(parse_ts_ms(str(trade_date or "")))


def _thin_chart_bars(
    bars: list[dict[str, Any]],
    *,
    max_bars: int = CHART_UI_MAX_BARS,
) -> tuple[list[dict[str, Any]], int]:
    """Оставляем последний бар корзины N минут, чтобы график Теста не вис."""
    n = len(bars)
    if n <= max(1, int(max_bars)):
        repaired: list[dict[str, Any]] = []
        for b in bars:
            ms = _repair_chart_timestamp_ms(
                b.get("timestampMs"), b.get("tradeDate") or b.get("time")
            )
            if ms <= 0:
                continue
            if int(b.get("timestampMs") or 0) != ms:
                b = {**b, "timestampMs": ms}
            repaired.append(b)
        return repaired, 1
    minutes = _chart_thin_minutes(n, max_bars)
    bucket = int(minutes) * 60_000
    out: list[dict[str, Any]] = []
    cur_key: int | None = None
    for b in bars:
        try:
            raw_ms = b.get("timestampMs")
            ms = int(raw_ms or 0)
        except (TypeError, ValueError):
            continue
        if ms <= 0:
            ms = _repair_chart_timestamp_ms(0, b.get("tradeDate") or b.get("time"))
            if ms <= 0:
                continue
            b = {**b, "timestampMs": ms}
        key = ms // bucket
        if key != cur_key:
            out.append(b)
            cur_key = key
        else:
            out[-1] = b
    if len(out) > max(1, int(max_bars)):
        step = max(1, int(math.ceil(len(out) / float(max_bars))))
        kept = out[::step]
        if kept[-1] is not out[-1]:
            kept.append(out[-1])
        out = kept
    return out, minutes


_CORRIDOR_WIDE_BUDGET_SEC = 2.8


def _slice_causal_pack(
    pack: dict[str, Any],
    d0: str,
    d1: str,
) -> dict[str, Any]:
    """Окно графика: те же снимки as_of, без повторного детектора."""
    by_all = pack.get("by_date") or {}
    daily_all = pack.get("daily") or []
    if d0 and d1 and d0 <= d1:
        by_date = {k: v for k, v in by_all.items() if d0 <= str(k)[:10] <= d1}
        daily = [r for r in daily_all if d0 <= str(r.get("date") or "")[:10] <= d1]
    else:
        by_date = dict(by_all)
        daily = list(daily_all)
    return {
        "kind": "wide",
        "causal": True,
        "daily": daily,
        "by_date": by_date,
        "partial": bool(pack.get("partial")),
    }


def _desk_wide_corridor_from_prep(
    prep: Any,
    lo: int,
    hi: int,
    spread_now: float | None,
) -> dict[str, Any] | None:
    """Широкая полка для графика Теста: кэш дневных крайностей, as_of по окну.

    Кадр Теста (нояб–май) считаем только по дням окна — иначе годы слева
    ломают три ящика. Короткое окно: запас ~50д слева, чтобы полка не
    обрывалась на краю. Не обход всех минуток и не полный 3-летний индекс.
    """
    lo_i = max(0, int(lo))
    hi_i = min(int(hi), int(getattr(prep, "n", 0) or 0))
    if hi_i <= lo_i:
        return None
    from replay.shelf_floor_ceiling import daily_extremes_from_prep
    from live.spread_corridor_wide import causal_wide_pack_from_extremes

    dates = prep.trade_dates
    d0 = str(dates[lo_i])[:10]
    d1 = str(dates[hi_i - 1])[:10]
    if len(d0) < 10 or len(d1) < 10:
        return None
    try:
        span_days = 0
        y0 = m0 = dd0 = 0
        try:
            y0, m0, dd0 = (int(x) for x in d0.split("-"))
            y1, m1, dd1 = (int(x) for x in d1.split("-"))
            span_days = (
                date(y1, m1, dd1) - date(y0, m0, dd0)
            ).days + 1
        except ValueError:
            span_days = 0
        daily, mins, maxs = daily_extremes_from_prep(prep)
        keep_from = d0
        if 0 < span_days <= 40 and y0:
            try:
                keep_from = (
                    date(y0, m0, dd0) - timedelta(days=50)
                ).isoformat()
            except Exception:
                keep_from = d0
        daily_s = [(d, v) for d, v in daily if keep_from <= d <= d1]
        mins_s = [(d, v) for d, v in mins if keep_from <= d <= d1]
        maxs_s = [(d, v) for d, v in maxs if keep_from <= d <= d1]
        pack = causal_wide_pack_from_extremes(
            daily_s,
            touch_mins=mins_s,
            touch_maxs=maxs_s,
            deadline_mono=time.monotonic() + _CORRIDOR_WIDE_BUDGET_SEC,
        )
        out = _slice_causal_pack(pack, d0, d1)
        if spread_now is not None and out.get("by_date"):
            last = out["by_date"].get(d1)
            if isinstance(last, dict):
                last = dict(last)
                last["spread"] = round(float(spread_now), 3)
                out["by_date"][d1] = last
        if not out.get("by_date") and not out.get("daily"):
            return None
        return out
    except Exception:
        log.exception("wide corridor for Test chart failed")
        return None


def bars1m_chart(
    csv: str,
    start: str | None = None,
    end: str | None = None,
    chart_days: int | None = DEFAULT_CHART_DAYS,
    *,
    as_live: bool = False,
) -> dict[str, Any]:
    """1m tip-Z bars for Testing chart (same shape as /api/bars).

    Sim stays full-window server-side. Chart is the start→end window (or last
    ``chart_days`` if that is smaller), then thinned to ~12k points so a 1–3y
    Test window still paints. ``chart_days<=0`` — no tail cut.
    """
    if not CACHE_1M.is_file():
        raise FileNotFoundError(
            f"Нет кэша 1м: {CACHE_1M.name}. "
            "Сначала: python scripts/backtest_intrabar_touch_1y.py"
        )
    csv = resolve_csv_for_window(csv, start, end)
    prep, meta = ensure_tip_series(csv, start=start, end=end)
    wms = _window_start_ms(prep, start)
    wems = _window_end_ms(end)
    lo = 0
    if wms > 0:
        lo = int(np.searchsorted(prep.ts_ms, wms, side="left"))
    hi = int(prep.n)
    if wems > 0:
        hi = min(hi, int(np.searchsorted(prep.ts_ms, wems, side="right")))
    full_n = max(0, hi - lo)
    chart_limited = False
    applied_days: int | None = None
    days = None if chart_days is None else int(chart_days)
    # 0 / отриц. — весь start→end без обрезки хвоста (кнопка «весь» на Тесте).
    no_day_cut = bool(days is not None and days <= 0)
    if no_day_cut:
        days = None
    # Хвост N дней — только если явно просили (стол 7–30д, старый дефолт 90).
    # Не форсируем 90д на длинном окне Теста: иначе «весь» и клик по сделке 2025
    # не находят бар (ряд начинался с июня текущего года).
    if (not no_day_cut) and days is not None and full_n > 0 and hi > lo:
        last_ms = int(prep.ts_ms[hi - 1])
        cut_ms = last_ms - int(days) * 86_400_000
        cut_lo = int(np.searchsorted(prep.ts_ms, max(wms, cut_ms), side="left"))
        if cut_lo > lo:
            lo = cut_lo
            chart_limited = True
            applied_days = days

    # Не собираем 700k dict: прореживаем индексы, затем только их.
    bar_idx, step_min_pre = _select_chart_bar_indices(
        prep.ts_ms, lo, hi, max_bars=CHART_UI_MAX_BARS,
    )
    out_bars: list[dict[str, Any]] = []
    for i in bar_idx:
        ii = int(i)
        td = prep.trade_dates[ii]
        ms = _repair_chart_timestamp_ms(int(prep.ts_ms[ii]), td)
        if ms <= 0:
            continue
        out_bars.append(
            {
                "timestampMs": ms,
                "tradeDate": td,
                "zScore": float(prep.z[ii]),
                "spreadPercent": float(prep.spread[ii]),
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
    corridor = None
    span_days = 0
    if hi > lo and prep.n > 0:
        span_ms = int(prep.ts_ms[hi - 1]) - int(prep.ts_ms[lo])
        if span_ms >= 0:
            span_days = int(span_ms / 86_400_000) + 1
    # 3y: detect_spread_corridor на полной истории — минуты и блокирует uvicorn.
    if out_bars and span_days <= 400:
        try:
            from live.spread_corridor import (
                CORRIDOR_LOOKBACK_DAYS,
                daily_spread_extremes_from_bars,
                desk_corridor_payload,
                rolling_corridor_history,
            )

            # Сжимаем всю загруженную историю до трёх дневных точек:
            # этого достаточно для границ и позволяет показать старые коридоры.
            corr_src = out_bars
            daily, day_mins, day_maxs = daily_spread_extremes_from_bars([
                {
                    "time": b.get("tradeDate"),
                    "timestampMs": b.get("timestampMs"),
                    "spread": b.get("spreadPercent"),
                }
                for b in corr_src
            ])
            by_min = {d: v for d, v in day_mins}
            by_max = {d: v for d, v in day_maxs}
            corr_bars = []
            for d, med in daily:
                corr_bars.append({"time": f"{d} 12:00", "spread": med})
                if d in by_min:
                    corr_bars.append({"time": f"{d} 10:00", "spread": by_min[d]})
                if d in by_max:
                    corr_bars.append({"time": f"{d} 16:00", "spread": by_max[d]})
            corridor = desk_corridor_payload(
                corr_bars or [
                    {
                        "time": b.get("tradeDate"),
                        "timestampMs": b.get("timestampMs"),
                        "spread": b.get("spreadPercent"),
                    }
                    for b in corr_src[-500:]
                ],
                spread_now=float(out_bars[-1]["spreadPercent"]),
            )
            corridor["history"] = rolling_corridor_history(
                corr_bars,
                lookback_days=CORRIDOR_LOOKBACK_DAYS,
            )
        except Exception:
            corridor = None
    corridor_wide = None
    if out_bars:
        try:
            corridor_wide = _desk_wide_corridor_from_prep(
                prep,
                lo,
                hi,
                float(out_bars[-1]["spreadPercent"]),
            )
        except Exception:
            log.exception("corridor_wide payload failed")
            corridor_wide = None
    out_bars, step_min = _thin_chart_bars(out_bars)
    step_min = max(int(step_min or 1), int(step_min_pre or 1))
    if step_min > 1:
        extra = f"Шаг графика {step_min} мин ({len(out_bars)} точек), чтобы страница не висла."
        hint = f"{hint} {extra}".strip() if hint else extra
    actual_days = days
    if span_days > 0:
        actual_days = span_days if not chart_limited else applied_days
    elif hi > lo and prep.n > 0:
        span_ms = int(prep.ts_ms[hi - 1]) - int(prep.ts_ms[lo])
        if span_ms >= 0:
            actual_days = int(span_ms / 86_400_000) + 1
    return {
        "ok": True,
        "bars": out_bars,
        "count": len(out_bars),
        "first": out_bars[0]["tradeDate"] if out_bars else None,
        "last": out_bars[-1]["tradeDate"] if out_bars else None,
        "fullTipCount": full_n,
        "chartLimited": chart_limited,
        "chartDays": applied_days if chart_limited else actual_days,
        "displayStepMin": step_min,
        "hintRu": hint,
        "corridor": corridor,
        "corridor_wide": corridor_wide,
        "meta": {
            **meta,
            "start": start,
            "end": end,
            "windowStartMs": wms,
            "windowEndMs": wems,
            "chartLo": lo,
            "chartHi": hi,
        },
        **live_meta,
    }
