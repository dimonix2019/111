"""Экран «Кто завис»: дневной спред обычка/преф по парам корзины."""

from __future__ import annotations

import statistics
import threading
import time
from datetime import date, timedelta
from typing import Any

import requests

BOARD = "TQBR"
ISS_BASE = "https://iss.moex.com/iss"
ISS_TIMEOUT = 45
LOOKBACK_DAYS = 365 * 3 + 30  # ~3 года
CACHE_TTL_SEC = 10 * 60  # 5–15 мин → берём 10

PAIRS: tuple[dict[str, str], ...] = (
    {"ord": "TATN", "pref": "TATNP", "name": "Татнефть"},
    {"ord": "SNGS", "pref": "SNGSP", "name": "Сургутнефтегаз"},
    {"ord": "RTKM", "pref": "RTKMP", "name": "Ростелеком"},
    {"ord": "MTLR", "pref": "MTLRP", "name": "Мечел"},
)

BASKET_RULES_RU = (
    "Корзина Татнефть + Сургут + Ростелеком + Мечел: не больше 2 позиций из 4 одновременно; "
    "капитал делить; потолок платы за перенос на весь портфель; "
    "уровни входа/выхода — свои у каждой пары "
    "(у Сургута отрицательный спред — норма, смотреть относительно своей медианы)."
)

# Полоса «зависания» вокруг текущего уровня: доля ширины p10–p90
_HANG_BAND_FRAC = 0.12
_HANG_BAND_MIN_PP = 0.35
# «Норма» вокруг медианы
_NORM_FRAC = 0.25
# Дней на полке, чтобы статус стал «полка»
_SHELF_MIN_DAYS = 8

_CACHE: dict[str, Any] = {"ts": 0.0, "payload": None}
_LOCK = threading.Lock()


def _f(v: Any) -> float | None:
    if v is None:
        return None
    try:
        x = float(v)
    except (TypeError, ValueError):
        return None
    if x != x:  # NaN
        return None
    return x


def _percentile(sorted_vals: list[float], p: float) -> float:
    """Линейная интерполяция процентиля; p в [0, 100]."""
    if not sorted_vals:
        raise ValueError("empty")
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    k = (len(sorted_vals) - 1) * (p / 100.0)
    lo = int(k)
    hi = min(lo + 1, len(sorted_vals) - 1)
    w = k - lo
    return sorted_vals[lo] * (1.0 - w) + sorted_vals[hi] * w


def _iss_history_closes(secid: str, from_d: date, till_d: date) -> list[tuple[str, float]]:
    """Дневные закрытия TQBR: [(YYYY-MM-DD, close), ...]."""
    rows: list[tuple[str, float]] = []
    start = 0
    while True:
        url = (
            f"{ISS_BASE}/history/engines/stock/markets/shares/boards/"
            f"{BOARD}/securities/{secid}.json"
        )
        params = {
            "from": from_d.isoformat(),
            "till": till_d.isoformat(),
            "start": start,
            "iss.meta": "off",
            "iss.only": "history",
        }
        resp = requests.get(url, params=params, timeout=ISS_TIMEOUT)
        resp.raise_for_status()
        block = resp.json().get("history") or {}
        cols = block.get("columns") or []
        data = block.get("data") or []
        if not data:
            break
        idx_date = cols.index("TRADEDATE") if "TRADEDATE" in cols else 0
        idx_close = cols.index("CLOSE") if "CLOSE" in cols else None
        idx_legal = cols.index("LEGALCLOSEPRICE") if "LEGALCLOSEPRICE" in cols else None
        for row in data:
            td = str(row[idx_date])[:10]
            px = None
            if idx_close is not None:
                px = _f(row[idx_close])
            if (px is None or px <= 0) and idx_legal is not None:
                px = _f(row[idx_legal])
            if px is not None and px > 0:
                rows.append((td, px))
        if len(data) < 100:
            break
        start += len(data)
        time.sleep(0.05)
    # уникальные даты, последняя цена на дату
    by_d: dict[str, float] = {}
    for td, px in rows:
        by_d[td] = px
    return sorted(by_d.items(), key=lambda x: x[0])


def _spread_series(
    ord_closes: list[tuple[str, float]],
    pref_closes: list[tuple[str, float]],
) -> list[tuple[str, float]]:
    pref_map = dict(pref_closes)
    out: list[tuple[str, float]] = []
    for td, o in ord_closes:
        p = pref_map.get(td)
        if p is None or p <= 0 or o <= 0:
            continue
        out.append((td, (o / p - 1.0) * 100.0))
    return out


def _trailing_run(mask: list[bool]) -> int:
    n = 0
    for v in reversed(mask):
        if not v:
            break
        n += 1
    return n


def classify_status(
    s_values: list[float],
    *,
    median: float,
    p10: float,
    p90: float,
) -> dict[str, Any]:
    """
    Статус относительно собственной зоны пары (не уровней Татнефти):
      норма / сжатие / полка / край
    """
    if not s_values:
        return {
            "status": "—",
            "days_hang": 0,
            "days_compression": 0,
            "width_pp": None,
        }
    s_now = s_values[-1]
    width = max(abs(p90 - p10), 1e-6)
    hang_band = max(_HANG_BAND_MIN_PP, _HANG_BAND_FRAC * width)
    norm_band = max(_HANG_BAND_MIN_PP, _NORM_FRAC * width)

    # Полка: серия дней у текущего уровня (узкая полоса вокруг s_now)
    near_now = [abs(v - s_now) <= hang_band for v in s_values]
    days_hang = _trailing_run(near_now)

    # Сжатие: серия дней заметно ниже собственной медианы
    # (для Сургута с отрицательной медианой — то же правило: S < median − полоса)
    compressed = [v < (median - 0.15 * width) for v in s_values]
    days_compression = _trailing_run(compressed)

    at_extreme = s_now <= p10 or s_now >= p90
    near_median = abs(s_now - median) <= norm_band
    away = abs(s_now - median) > norm_band

    if days_hang >= _SHELF_MIN_DAYS and away:
        status = "полка"
    elif at_extreme:
        status = "край"
    elif days_compression >= 5 or (away and s_now < median):
        status = "сжатие"
    elif near_median:
        status = "норма"
    else:
        # выше медианы, но не край — для экрана зависаний это тоже «нормальная» сторона
        status = "норма"

    return {
        "status": status,
        "days_hang": int(days_hang),
        "days_compression": int(days_compression),
        "width_pp": round(width, 3),
    }


def analyze_pair_spreads(spreads: list[tuple[str, float]], meta: dict[str, str]) -> dict[str, Any]:
    if len(spreads) < 30:
        return {
            "ord": meta["ord"],
            "pref": meta["pref"],
            "name": meta["name"],
            "ok": False,
            "error": f"мало дней ({len(spreads)})",
        }
    vals = [s for _, s in spreads]
    ordered = sorted(vals)
    med = float(statistics.median(vals))
    p10 = _percentile(ordered, 10)
    p90 = _percentile(ordered, 90)
    s_now = vals[-1]
    td = spreads[-1][0]
    st = classify_status(vals, median=med, p10=p10, p90=p90)
    note = None
    if meta["ord"] == "SNGS":
        note = "Отрицательный спред — норма; зона относительно своей медианы."
    return {
        "ord": meta["ord"],
        "pref": meta["pref"],
        "name": meta["name"],
        "ok": True,
        "error": None,
        "pair": f"{meta['ord']}/{meta['pref']}",
        "s_now": round(s_now, 3),
        "s_date": td,
        "median": round(med, 3),
        "p10": round(p10, 3),
        "p90": round(p90, 3),
        "n_days": len(vals),
        "from": spreads[0][0],
        "to": td,
        "status": st["status"],
        "days_hang": st["days_hang"],
        "days_compression": st["days_compression"],
        "width_pp": st["width_pp"],
        "note": note,
    }


def _build_fresh(*, lookback_days: int = LOOKBACK_DAYS) -> dict[str, Any]:
    till = date.today()
    from_d = till - timedelta(days=lookback_days)
    pairs_out: list[dict[str, Any]] = []
    errors: list[str] = []
    for meta in PAIRS:
        try:
            o_closes = _iss_history_closes(meta["ord"], from_d, till)
            p_closes = _iss_history_closes(meta["pref"], from_d, till)
            spreads = _spread_series(o_closes, p_closes)
            row = analyze_pair_spreads(spreads, meta)
            pairs_out.append(row)
            if not row.get("ok"):
                errors.append(f"{meta['ord']}: {row.get('error')}")
        except Exception as exc:
            errors.append(f"{meta['ord']}: {exc}")
            pairs_out.append(
                {
                    "ord": meta["ord"],
                    "pref": meta["pref"],
                    "name": meta["name"],
                    "ok": False,
                    "error": str(exc),
                    "pair": f"{meta['ord']}/{meta['pref']}",
                }
            )
    ok_n = sum(1 for p in pairs_out if p.get("ok"))
    return {
        "ok": ok_n > 0,
        "asof": till.isoformat(),
        "window": {"from": from_d.isoformat(), "till": till.isoformat()},
        "definition": "S = (обычка / преф − 1) × 100 · дневное закрытие TQBR",
        "basket_rules": BASKET_RULES_RU,
        "pairs": pairs_out,
        "cache_ttl_sec": CACHE_TTL_SEC,
        "errors": errors or None,
    }


def get_pref_hang_screener(*, force: bool = False) -> dict[str, Any]:
    """Кэш в памяти ~10 мин; force=1 — пересчёт с ISS."""
    now = time.time()
    with _LOCK:
        cached = _CACHE["payload"]
        if (
            not force
            and cached is not None
            and (now - float(_CACHE["ts"])) < CACHE_TTL_SEC
        ):
            out = dict(cached)
            out["cached"] = True
            out["age_sec"] = round(now - float(_CACHE["ts"]), 1)
            return out

    payload = _build_fresh()
    with _LOCK:
        _CACHE["ts"] = time.time()
        _CACHE["payload"] = payload
    out = dict(payload)
    out["cached"] = False
    out["age_sec"] = 0.0
    return out


def clear_cache() -> None:
    with _LOCK:
        _CACHE["ts"] = 0.0
        _CACHE["payload"] = None
