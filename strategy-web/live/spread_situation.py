"""Живая подпись «Сейчас» на вкладке Ситуация.

Опора — торговая полоса на графике (бирюза Long / широкая Short),
не «узкая зона 1%…вход Long» и не адаптивный коридор.
Пороги AUTO не меняет.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

from live.constants import (
    DEFAULT_SPREAD_ENTER_NARROW,
    DEFAULT_SPREAD_ENTER_WIDE,
    DEFAULT_SPREAD_EXIT_NARROW,
    DEFAULT_SPREAD_EXIT_WIDE,
)
from live.spread_regime import (
    REGIME_NARROW,
    REGIME_TRANSITION,
    REGIME_WIDE,
    classify_spread_pct,
)

MSK = ZoneInfo("Europe/Moscow")

# Как в договорённости: касание/вынос у кромки полосы.
TAPE_EPS_PP = 0.08
# Снос за день — оставляем константу (вторая строка теперь всегда экстремумы дня).
SWEEP_PP = 0.40
# «Около полосы»: дальше — «между полосами», без фальшивого теста.
BAND_NEAR_PP = 0.40

# Узкая полка по 1м vs corridor.lo/hi. Пороги чуть консервативнее коридора —
# не выдумываем полку из короткого качания.
SHELF_MIN_BARS = 40
SHELF_MIN_WIDTH_PP = 0.20
SHELF_WIDTH_FRAC = 0.70
SHELF_WIDTH_GAP_PP = 0.25
SHELF_TOUCH_EPS_PP = 0.08
SHELF_MIN_TOUCHES = 2
SHELF_LOOKBACK_DAYS = 2
SHELF_MEMBER_EPS_PP = 0.08

KIND_TEST_LO = "test_lo"
KIND_TEST_HI = "test_hi"
KIND_BREAK_LO = "break_lo"
KIND_BREAK_HI = "break_hi"
KIND_INSIDE = "inside"
KIND_BETWEEN = "between"

_MINUS = "\u2212"

_EMPTY = {
    "ok": False,
    "label": "—",
    "sweep": None,
    "kind": None,
    "side": None,
    "levels_line": None,
    "regime_line": None,
    "shelf_line": None,
    "corridor_line": None,
}


def fmt_level(value: float) -> str:
    return f"{float(value):.2f}".replace(".", ",")


def fmt_hhmm_msk(dt: datetime) -> str:
    local = dt.astimezone(MSK) if dt.tzinfo else dt.replace(tzinfo=MSK)
    return f"{local.hour}:{local.minute:02d}"


def fmt_delta(value: float) -> str:
    """Знак к порогу: −0,28 / +0,52 (минус = ещё сжаться до входа Long)."""
    v = round(float(value), 2)
    if abs(v) < 5e-4:
        return "+0,00"
    sign = _MINUS if v < 0 else "+"
    return f"{sign}{abs(v):.2f}".replace(".", ",")


def _f(raw: Any) -> float | None:
    if raw is None:
        return None
    try:
        v = float(raw)
    except (TypeError, ValueError):
        return None
    if v != v:  # NaN
        return None
    return v


def _i(raw: Any) -> int | None:
    v = _f(raw)
    if v is None:
        return None
    return int(round(v))


def _levels(levels: dict[str, Any] | None) -> tuple[float, float, float, float]:
    src = levels if isinstance(levels, dict) else {}

    def pick(*keys: str, default: float) -> float:
        for key in keys:
            v = _f(src.get(key))
            if v is not None:
                return v
        return float(default)

    enter_n = pick("enter_narrow", "spread_enter_narrow", default=DEFAULT_SPREAD_ENTER_NARROW)
    exit_n = pick("exit_narrow", "spread_exit_narrow", default=DEFAULT_SPREAD_EXIT_NARROW)
    enter_w = pick("enter_wide", "spread_enter_wide", default=DEFAULT_SPREAD_ENTER_WIDE)
    exit_w = pick("exit_wide", "spread_exit_wide", default=DEFAULT_SPREAD_EXIT_WIDE)
    return enter_n, exit_n, exit_w, enter_w


def bar_dt_msk(bar: dict[str, Any]) -> datetime | None:
    if not isinstance(bar, dict):
        return None
    ms = bar.get("timestampMs")
    if ms is None:
        ms = bar.get("timestamp_ms")
    if ms is not None:
        try:
            ms_i = int(ms)
        except (TypeError, ValueError):
            ms_i = 0
        if ms_i > 0:
            return datetime.fromtimestamp(ms_i / 1000.0, tz=MSK)
    for key in ("time", "tradeDate", "trade_date"):
        raw = bar.get(key)
        if raw is None:
            continue
        s = str(raw).strip().replace("T", " ")
        for fmt, n in (("%Y-%m-%d %H:%M:%S", 19), ("%Y-%m-%d %H:%M", 16)):
            chunk = s[:n]
            if len(chunk) < n:
                continue
            try:
                return datetime.strptime(chunk, fmt).replace(tzinfo=MSK)
            except ValueError:
                continue
    return None


def bar_day_msk(bar: dict[str, Any]) -> str | None:
    dt = bar_dt_msk(bar)
    if dt is not None:
        return dt.strftime("%Y-%m-%d")
    if not isinstance(bar, dict):
        return None
    for key in ("time", "tradeDate", "trade_date"):
        raw = bar.get(key)
        if raw is None:
            continue
        s = str(raw).strip().replace("T", " ")
        if len(s) >= 10 and s[4] == "-" and s[7] == "-":
            return s[:10]
    return None


def bar_close(bar: dict[str, Any]) -> float | None:
    if not isinstance(bar, dict):
        return None
    for key in ("spread", "spreadPercent", "spread_close"):
        v = _f(bar.get(key))
        if v is not None:
            return v
    return None


def bar_low(bar: dict[str, Any]) -> float | None:
    if not isinstance(bar, dict):
        return None
    v = _f(bar.get("spread_low"))
    if v is not None:
        return v
    return bar_close(bar)


def bar_high(bar: dict[str, Any]) -> float | None:
    if not isinstance(bar, dict):
        return None
    v = _f(bar.get("spread_high"))
    if v is not None:
        return v
    return bar_close(bar)


def classify_vs_band(
    *,
    close: float,
    low: float,
    high: float,
    lo: float,
    hi: float,
    eps: float = TAPE_EPS_PP,
) -> str:
    """Состояние относительно полосы [lo, hi]."""
    if close < lo - eps:
        return KIND_BREAK_LO
    if close > hi + eps:
        return KIND_BREAK_HI
    touch_lo = low <= lo + eps
    touch_hi = high >= hi - eps
    if touch_lo and touch_hi:
        return KIND_TEST_LO if abs(close - lo) <= abs(close - hi) else KIND_TEST_HI
    if touch_lo:
        return KIND_TEST_LO
    if touch_hi:
        return KIND_TEST_HI
    return KIND_INSIDE


def pick_trade_band(
    spread: float,
    *,
    long_lo: float,
    long_hi: float,
    short_lo: float,
    short_hi: float,
    near: float = BAND_NEAR_PP,
) -> tuple[str | None, float | None, float | None]:
    """Какая полоса графика сейчас в теме: Long, Short или ни одна."""
    near_long = spread <= long_hi + near
    near_short = spread >= short_lo - near
    if near_long and not near_short:
        return "Long", long_lo, long_hi
    if near_short and not near_long:
        return "Short", short_lo, short_hi
    if near_long and near_short:
        mid_long = 0.5 * (long_lo + long_hi)
        mid_short = 0.5 * (short_lo + short_hi)
        if abs(spread - mid_long) <= abs(spread - mid_short):
            return "Long", long_lo, long_hi
        return "Short", short_lo, short_hi
    return None, None, None


def _headline(
    kind: str,
    *,
    side: str | None,
    lo: float | None,
    hi: float | None,
    long_hi: float,
    short_lo: float,
    since_hm: str | None,
) -> str:
    if kind == KIND_BETWEEN or side is None or lo is None or hi is None:
        return f"Между полосами {fmt_level(long_hi)}…{fmt_level(short_lo)}"
    if kind == KIND_INSIDE:
        return f"Внутри {side} {fmt_level(lo)}…{fmt_level(hi)}"
    clock = f" · {since_hm}" if since_hm else ""
    if kind == KIND_TEST_LO:
        return f"Тест низа {side} {fmt_level(lo)}{clock}"
    if kind == KIND_TEST_HI:
        return f"Тест верха {side} {fmt_level(hi)}{clock}"
    if kind == KIND_BREAK_LO:
        return f"Вынос ниже {side} {fmt_level(lo)}{clock}"
    if kind == KIND_BREAK_HI:
        return f"Вынос выше {side} {fmt_level(hi)}{clock}"
    return "—"


def _sort_key(bar: dict[str, Any]) -> tuple[int, str]:
    dt = bar_dt_msk(bar)
    if dt is not None:
        return int(dt.timestamp() * 1000), bar_day_msk(bar) or ""
    return 0, bar_day_msk(bar) or ""


def _episode_kind(
    bar: dict[str, Any],
    levels_t: tuple[float, float, float, float],
    *,
    eps: float = TAPE_EPS_PP,
) -> tuple[str, str | None, float | None, float | None]:
    enter_n, exit_n, exit_w, enter_w = levels_t
    close = bar_close(bar)
    if close is None:
        return KIND_BETWEEN, None, None, None
    low = bar_low(bar)
    high = bar_high(bar)
    if low is None:
        low = close
    if high is None:
        high = close
    side, lo, hi = pick_trade_band(
        close,
        long_lo=enter_n,
        long_hi=exit_n,
        short_lo=exit_w,
        short_hi=enter_w,
    )
    if side is None or lo is None or hi is None:
        return KIND_BETWEEN, None, None, None
    kind = classify_vs_band(close=close, low=low, high=high, lo=lo, hi=hi, eps=eps)
    return kind, side, lo, hi


def _dist_to_band(spread: float, lo: float, hi: float) -> float:
    if spread < lo:
        return lo - spread
    if spread > hi:
        return spread - hi
    return 0.0


def pick_auto_levels_side(
    spread: float,
    *,
    enter_n: float,
    exit_n: float,
    exit_w: float,
    enter_w: float,
) -> tuple[str, float, float]:
    """Ближе к Long-полосе AUTO или к Short. Ничья — по середине между полосами."""
    d_long = _dist_to_band(spread, enter_n, exit_n)
    d_short = _dist_to_band(spread, exit_w, enter_w)
    if d_short < d_long:
        return "Short", enter_w, exit_w
    if d_long < d_short:
        return "Long", enter_n, exit_n
    mid = 0.5 * (exit_n + exit_w)
    if spread >= mid:
        return "Short", enter_w, exit_w
    return "Long", enter_n, exit_n


def build_levels_line(
    spread: float,
    *,
    enter_n: float,
    exit_n: float,
    exit_w: float,
    enter_w: float,
    take_profit_pct: float = 2.0,
    leverage: float = 7.0,
) -> tuple[str, str, float, float]:
    side, enter, exit_lv = pick_auto_levels_side(
        spread, enter_n=enter_n, exit_n=exit_n, exit_w=exit_w, enter_w=enter_w
    )
    d_enter = enter - spread
    d_exit = exit_lv - spread  # расстояние до AUTO-выхода (для API); в тексте — ТП
    lev = max(1.0, float(leverage or 7.0))
    tp = float(take_profit_pct if take_profit_pct is not None else 2.0)
    if tp <= 0:
        tp = 2.0
    # Грубый Δ спреда ≈ tp%/плечо (без комиссий/overnight).
    rough_pp = tp / lev
    tp_signed = rough_pp if side == "Long" else -rough_pp
    tp_pct_txt = (
        f"{tp:.0f}" if abs(tp - round(tp)) < 1e-9 else f"{tp:.1f}".replace(".", ",")
    )
    line = (
        f"до {side} {fmt_level(enter)} {fmt_delta(d_enter)} п.п. · "
        f"до ТП ≈ {fmt_delta(tp_signed)} (вход+{tp_pct_txt}%)"
    )
    return line, side, d_enter, d_exit


def build_regime_line(spread: float) -> tuple[str, str]:
    reg = classify_spread_pct(spread)
    if reg == REGIME_NARROW:
        return "режим узкий · входы Long", reg
    if reg == REGIME_WIDE:
        return "режим широкий · входы Short", reg
    if reg == REGIME_TRANSITION:
        return "режим переход · новых входов нет", reg
    return "режим —", reg


def format_day_extrema_line(
    *,
    day_max: float | None,
    day_max_hm: str | None,
    day_min: float | None,
    day_min_hm: str | None,
) -> str | None:
    if day_max is None or day_min is None:
        return None
    if abs(day_max - day_min) < 1e-9:
        hm = day_max_hm or day_min_hm
        clock = f" · {hm}" if hm else ""
        return f"Сегодня {fmt_level(day_max)}{clock}"
    parts = [f"Сегодня макс {fmt_level(day_max)}"]
    if day_max_hm:
        parts.append(day_max_hm)
    parts.append(f"мин {fmt_level(day_min)}")
    if day_min_hm:
        parts.append(day_min_hm)
    return " · ".join(parts)


def _day_extrema(today: list[dict[str, Any]]) -> tuple[
    float | None, str | None, int | None, float | None, str | None, int | None
]:
    best_max: float | None = None
    best_min: float | None = None
    max_dt: datetime | None = None
    min_dt: datetime | None = None
    for b in today:
        dt = bar_dt_msk(b)
        hi = bar_high(b)
        lo = bar_low(b)
        if hi is not None and (best_max is None or hi > best_max):
            best_max = hi
            max_dt = dt
        if lo is not None and (best_min is None or lo < best_min):
            best_min = lo
            min_dt = dt
    max_hm = fmt_hhmm_msk(max_dt) if max_dt else None
    min_hm = fmt_hhmm_msk(min_dt) if min_dt else None
    max_ms = int(max_dt.timestamp() * 1000) if max_dt else None
    min_ms = int(min_dt.timestamp() * 1000) if min_dt else None
    return best_max, max_hm, max_ms, best_min, min_hm, min_ms


def _band_pair(
    src: Any, lo_key: str = "lo", hi_key: str = "hi"
) -> tuple[float, float] | None:
    if not isinstance(src, dict):
        return None
    lo, hi = _f(src.get(lo_key)), _f(src.get(hi_key))
    if lo is None or hi is None or hi <= lo:
        return None
    return float(lo), float(hi)


def _truthy_flag(raw: Any) -> bool:
    if raw is True:
        return True
    if isinstance(raw, (int, float)) and raw == 1:
        return True
    if isinstance(raw, str) and raw.strip().lower() in ("1", "true", "yes"):
        return True
    return False


def _substantially_narrower(
    inner_lo: float, inner_hi: float, outer_lo: float, outer_hi: float
) -> bool:
    iw = inner_hi - inner_lo
    ow = outer_hi - outer_lo
    if ow <= 0 or iw <= 0:
        return False
    if iw < SHELF_MIN_WIDTH_PP:
        return False
    if iw > ow * SHELF_WIDTH_FRAC:
        return False
    if ow - iw < SHELF_WIDTH_GAP_PP:
        return False
    return True


def _outer_from_history(
    corridor: dict[str, Any], inner: tuple[float, float]
) -> tuple[float, float] | None:
    hist = corridor.get("history")
    if not isinstance(hist, list):
        return None
    found: tuple[float, float] | None = None
    for row in hist:
        pair = _band_pair(row)
        if pair and _substantially_narrower(inner[0], inner[1], pair[0], pair[1]):
            found = pair
    return found


def _payload_shelf_state(corridor: dict[str, Any] | None) -> dict[str, Any]:
    """Прочитать узкую полку / внешний коридор из payload, без правок детектора."""
    empty = {
        "inner": None,
        "outer": None,
        "corr": None,
        "flagged": False,
        "touches_lo": None,
        "touches_hi": None,
        "since_date": None,
        "since_hm": None,
    }
    if not isinstance(corridor, dict):
        return empty
    corr = _band_pair(corridor)
    outer = _band_pair(corridor, "outer_lo", "outer_hi") or _band_pair(
        corridor, "wide_lo", "wide_hi"
    )
    inner_obj = None
    for key in ("inner", "shelf", "narrow_shelf"):
        raw = corridor.get(key)
        if isinstance(raw, dict) and _band_pair(raw):
            inner_obj = raw
            break
    title = str(corridor.get("title") or "")
    flagged = _truthy_flag(corridor.get("inner_shelf")) or ("узкая полка" in title)
    inner = _band_pair(inner_obj) if inner_obj else None
    src = inner_obj if inner_obj else (corridor if flagged else None)
    touches_lo = _i(src.get("touches_lo")) if isinstance(src, dict) else None
    touches_hi = _i(src.get("touches_hi")) if isinstance(src, dict) else None
    since_date = None
    since_hm = None
    if isinstance(src, dict):
        since_date = src.get("since_date") or src.get("since")
        since_hm = src.get("since_hm")
    if inner is None and flagged and corr is not None:
        inner = corr
        if touches_lo is None:
            touches_lo = _i(corridor.get("touches_lo"))
        if touches_hi is None:
            touches_hi = _i(corridor.get("touches_hi"))
        if not since_date:
            since_date = corridor.get("since_date")
        if not since_hm:
            since_hm = corridor.get("since_hm")
    if inner is not None and outer is None and corr is not None:
        if inner != corr and _substantially_narrower(inner[0], inner[1], corr[0], corr[1]):
            outer = corr
        elif inner == corr:
            outer = _outer_from_history(corridor, inner)
    elif inner is not None and outer is None:
        outer = _outer_from_history(corridor, inner)
    return {
        "inner": inner,
        "outer": outer,
        "corr": corr,
        "flagged": flagged or inner is not None,
        "touches_lo": touches_lo,
        "touches_hi": touches_hi,
        "since_date": str(since_date)[:10] if since_date else None,
        "since_hm": str(since_hm) if since_hm else None,
    }


def _recent_1m(
    parsed: list[dict[str, Any]], day: str | None
) -> list[dict[str, Any]]:
    if not day:
        return parsed[-max(SHELF_MIN_BARS * 4, 120) :]
    try:
        d0 = datetime.strptime(day, "%Y-%m-%d")
    except ValueError:
        return parsed
    cut = (d0 - timedelta(days=SHELF_LOOKBACK_DAYS - 1)).strftime("%Y-%m-%d")
    return [b for b in parsed if (bar_day_msk(b) or "") >= cut]


def _count_edge_touches(
    window: list[dict[str, Any]], lo: float, hi: float, eps: float = SHELF_TOUCH_EPS_PP
) -> tuple[int, int]:
    t_lo = 0
    t_hi = 0
    for b in window:
        lv, hv = bar_low(b), bar_high(b)
        if lv is not None and lv <= lo + eps:
            t_lo += 1
        if hv is not None and hv >= hi - eps:
            t_hi += 1
    return t_lo, t_hi


def _trailing_1m_shelf(
    recent: list[dict[str, Any]], outer_lo: float, outer_hi: float
) -> dict[str, Any] | None:
    if not recent:
        return None
    ow = outer_hi - outer_lo
    if ow < SHELF_MIN_WIDTH_PP + SHELF_WIDTH_GAP_PP:
        return None
    lo: float | None = None
    hi: float | None = None
    start = len(recent) - 1
    for i in range(len(recent) - 1, -1, -1):
        b_lo, b_hi = bar_low(recent[i]), bar_high(recent[i])
        if b_lo is None or b_hi is None:
            continue
        nlo = b_lo if lo is None else min(lo, b_lo)
        nhi = b_hi if hi is None else max(hi, b_hi)
        w = nhi - nlo
        too_wide = w > ow * SHELF_WIDTH_FRAC or (ow - w) < SHELF_WIDTH_GAP_PP
        if lo is not None and too_wide:
            break
        lo, hi = nlo, nhi
        start = i
    if lo is None or hi is None:
        return None
    window = recent[start:]
    if len(window) < SHELF_MIN_BARS:
        return None
    if not _substantially_narrower(lo, hi, outer_lo, outer_hi):
        return None
    t_lo, t_hi = _count_edge_touches(window, lo, hi)
    if t_lo < SHELF_MIN_TOUCHES or t_hi < SHELF_MIN_TOUCHES:
        return None
    mid = 0.5 * (lo + hi)
    if mid < outer_lo - 0.15 or mid > outer_hi + 0.15:
        return None
    since_dt = bar_dt_msk(window[0])
    return {
        "lo": lo,
        "hi": hi,
        "touches_lo": t_lo,
        "touches_hi": t_hi,
        "since_dt": since_dt,
        "n_bars": len(window),
    }


def _trailing_run_since(
    recent: list[dict[str, Any]], lo: float, hi: float
) -> datetime | None:
    start: datetime | None = None
    seen = False
    for b in reversed(recent):
        c = bar_close(b)
        if c is None:
            continue
        if lo - SHELF_MEMBER_EPS_PP <= c <= hi + SHELF_MEMBER_EPS_PP:
            start = bar_dt_msk(b)
            seen = True
            continue
        if seen:
            break
    return start


def _fmt_since_clock(
    *,
    since_dt: datetime | None,
    since_hm: str | None,
    since_date: str | None,
    today: str | None,
) -> str:
    if since_dt is not None:
        hm = fmt_hhmm_msk(since_dt)
        day = since_dt.strftime("%Y-%m-%d")
        if today and day != today:
            return f"{since_dt.day:d}.{since_dt.month:02d} {hm}"
        return hm
    if since_hm:
        return str(since_hm)
    if since_date:
        chunk = str(since_date)[:10]
        if today and chunk == today:
            return ""
        try:
            d = datetime.strptime(chunk, "%Y-%m-%d")
            return f"{d.day:d}.{d.month:02d}"
        except ValueError:
            return chunk
    return ""


def format_shelf_and_corridor_lines(
    *,
    shelf: dict[str, Any] | None,
    outer: tuple[float, float] | None,
    corr: tuple[float, float] | None,
    today: str | None,
) -> tuple[str | None, str | None]:
    corridor_line = None
    if shelf and outer:
        corridor_line = f"коридор {fmt_level(outer[0])}…{fmt_level(outer[1])}"
    elif not shelf and corr:
        corridor_line = f"коридор {fmt_level(corr[0])}…{fmt_level(corr[1])}"
    if not shelf:
        return None, corridor_line
    clock = _fmt_since_clock(
        since_dt=shelf.get("since_dt"),
        since_hm=shelf.get("since_hm"),
        since_date=shelf.get("since_date"),
        today=today,
    )
    clock_bit = f" с {clock}" if clock else ""
    t_lo = shelf.get("touches_lo")
    t_hi = shelf.get("touches_hi")
    touch_bit = ""
    if t_lo is not None and t_hi is not None:
        touch_bit = f" (касания низ {int(t_lo)} · верх {int(t_hi)})"
    shelf_line = (
        f"полка {fmt_level(float(shelf['lo']))}…{fmt_level(float(shelf['hi']))}"
        f"{clock_bit}{touch_bit}"
    )
    return shelf_line, corridor_line


def _choose_shelf(
    *,
    payload: dict[str, Any],
    recent: list[dict[str, Any]],
) -> tuple[dict[str, Any] | None, tuple[float, float] | None]:
    corr = payload.get("corr")
    inner = payload.get("inner")
    outer = payload.get("outer")
    flagged = bool(payload.get("flagged"))
    ref_outer = outer or corr
    trail = None
    if ref_outer:
        trail = _trailing_1m_shelf(recent, ref_outer[0], ref_outer[1])

    if trail:
        wide = ref_outer
        if corr and _substantially_narrower(trail["lo"], trail["hi"], corr[0], corr[1]):
            wide = corr
        if outer and (
            wide is None
            or (outer[1] - outer[0]) > (wide[1] - wide[0]) + 1e-9
        ):
            wide = outer
        return trail, wide

    if inner and flagged:
        wide = outer
        if wide is None and corr and inner != corr:
            if _substantially_narrower(inner[0], inner[1], corr[0], corr[1]):
                wide = corr
        since_dt = None
        if len(recent) >= 20:
            since_dt = _trailing_run_since(recent, inner[0], inner[1])
        shelf = {
            "lo": inner[0],
            "hi": inner[1],
            "touches_lo": payload.get("touches_lo"),
            "touches_hi": payload.get("touches_hi"),
            "since_dt": since_dt,
            "since_hm": payload.get("since_hm"),
            "since_date": payload.get("since_date"),
        }
        if wide and not _substantially_narrower(inner[0], inner[1], wide[0], wide[1]):
            wide = None
        return shelf, wide

    return None, None


def build_spread_situation(
    bars: list[dict[str, Any]] | None,
    *,
    levels: dict[str, Any] | None = None,
    corridor: dict[str, Any] | None = None,
    eps: float = TAPE_EPS_PP,
    sweep_pp: float = SWEEP_PP,
) -> dict[str, Any]:
    """Собрать подпись по 1м барам стола (после подстановки tip1m)."""
    del sweep_pp  # экстремумы дня всегда; порог сноса больше не прячет строку
    levels_t = _levels(levels)
    enter_n, exit_n, exit_w, enter_w = levels_t
    parsed = [b for b in (bars or []) if isinstance(b, dict) and bar_close(b) is not None]
    if not parsed:
        return {
            **_EMPTY,
            "lo": enter_n,
            "hi": exit_n,
            "long_lo": enter_n,
            "long_hi": exit_n,
            "short_lo": exit_w,
            "short_hi": enter_w,
        }
    parsed.sort(key=_sort_key)
    last = parsed[-1]
    day = bar_day_msk(last)
    today = [b for b in parsed if bar_day_msk(b) == day] if day else parsed
    if not today:
        today = [last]

    kind, side, lo, hi = _episode_kind(last, levels_t, eps=eps)
    since_dt: datetime | None = None
    if kind in (KIND_TEST_LO, KIND_TEST_HI, KIND_BREAK_LO, KIND_BREAK_HI):
        start = last
        for prev in reversed(today[:-1]):
            pk, ps, _, _ = _episode_kind(prev, levels_t, eps=eps)
            if pk == kind and ps == side:
                start = prev
                continue
            break
        since_dt = bar_dt_msk(start)

    since_hm = fmt_hhmm_msk(since_dt) if since_dt else None
    close = bar_close(last)
    day_max, day_max_hm, day_max_ms, day_min, day_min_hm, day_min_ms = _day_extrema(today)
    if day_max is None:
        day_max = close
    if day_min is None:
        day_min = close

    sweep = format_day_extrema_line(
        day_max=day_max,
        day_max_hm=day_max_hm,
        day_min=day_min,
        day_min_hm=day_min_hm,
    )

    label = _headline(
        kind,
        side=side,
        lo=lo,
        hi=hi,
        long_hi=exit_n,
        short_lo=exit_w,
        since_hm=since_hm,
    )

    levels_line = None
    levels_side = None
    d_enter = None
    d_exit = None
    regime_line = None
    regime = None
    if close is not None:
        levels_line, levels_side, d_enter, d_exit = build_levels_line(
            close, enter_n=enter_n, exit_n=exit_n, exit_w=exit_w, enter_w=enter_w
        )
        regime_line, regime = build_regime_line(close)

    payload_shelf = _payload_shelf_state(corridor)
    recent = _recent_1m(parsed, day)
    shelf_info, shelf_outer = _choose_shelf(payload=payload_shelf, recent=recent)
    shelf_line, corridor_line = format_shelf_and_corridor_lines(
        shelf=shelf_info,
        outer=shelf_outer,
        corr=payload_shelf.get("corr"),
        today=day,
    )

    return {
        "ok": True,
        "label": label,
        "sweep": sweep,
        "kind": kind,
        "side": side,
        "lo": lo,
        "hi": hi,
        "since_hm": since_hm,
        "since_ms": int(since_dt.timestamp() * 1000) if since_dt else None,
        "spread": close,
        "day": day,
        "day_max": day_max,
        "day_min": day_min,
        "day_max_hm": day_max_hm,
        "day_min_hm": day_min_hm,
        "day_max_ms": day_max_ms,
        "day_min_ms": day_min_ms,
        "long_lo": enter_n,
        "long_hi": exit_n,
        "short_lo": exit_w,
        "short_hi": enter_w,
        "eps": TAPE_EPS_PP,
        "levels_line": levels_line,
        "levels_side": levels_side,
        "d_enter": round(d_enter, 4) if d_enter is not None else None,
        "d_exit": round(d_exit, 4) if d_exit is not None else None,
        "regime_line": regime_line,
        "regime": regime,
        "shelf_line": shelf_line,
        "corridor_line": corridor_line,
    }


def desk_situation_payload(
    bars: list[dict[str, Any]] | None,
    *,
    levels: dict[str, Any] | None = None,
    corridor: dict[str, Any] | None = None,
) -> dict[str, Any]:
    try:
        return build_spread_situation(bars, levels=levels, corridor=corridor)
    except Exception:
        return dict(_EMPTY)
