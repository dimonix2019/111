"""Spot–futures basis scanner (cash-and-carry) via MOEX ISS."""

from __future__ import annotations

import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, datetime, timedelta
from typing import Any

import requests

log = logging.getLogger(__name__)

ISS_TIMEOUT = 60
FORTS_BOARD = "RFUD"
SPOT_BOARD = "TQBR"
DEFAULT_FIN_RATE = 0.18
DEFAULT_MIN_YIELD_ANN = 15.0
DEFAULT_Z_ENTRY = 1.0
DEFAULT_HISTORY_DAYS = 60
Z_WINDOW = 60
COMM_STOCK_PCT = 0.0004
COMM_FUT_RUB = 1.0

# Ликвидные акции с одиночными фьючерсами на MOEX (расширяйте по необходимости).
BASIS_WATCHLIST: tuple[str, ...] = (
    "TATN",
    "TATP",
    "SBER",
    "GAZP",
    "LKOH",
    "ROSN",
    "GMKN",
    "NVTK",
    "VTBR",
    "MGNT",
    "MOEX",
    "ALRS",
    "CHMF",
    "MTSS",
    "PLZL",
    "T",
    "YDEX",
    "TRNF",
)

# ASSETCODE фьючерса → тикер на TQBR (если отличается).
SPOT_TICKER_MAP: dict[str, str] = {
    "TATP": "TATNP",
}

# (date ISO, asset, amount_rub per share) — частичный календарь; без суммы div не вычитается.
DIVIDENDS: list[tuple[str, str, float]] = [
    ("2025-10-14", "TATN", 16.0),
    ("2026-01-11", "TATN", 16.0),
    ("2026-04-28", "TATN", 30.0),
    ("2026-07-15", "TATN", 30.0),
]

_cache_lock = threading.Lock()
_cache: dict[str, tuple[float, Any]] = {}
_CACHE_TTL_SEC = 45.0


def _d(s: str) -> date:
    return date.fromisoformat(str(s)[:10])


def _today() -> date:
    return date.today()


def _iss_get(url: str, params: dict | None = None) -> dict:
    resp = requests.get(url, params=params or {}, timeout=ISS_TIMEOUT)
    resp.raise_for_status()
    return resp.json()


def _fetch_forts_stock_futures() -> list[dict]:
    """Активные одиночные фьючерсы на акции (не индексы/валюта)."""
    url = "https://iss.moex.com/iss/engines/futures/markets/forts/securities.json"
    rows: list = []
    start = 0
    cols: list[str] = []
    while True:
        data = _iss_get(
            url,
            {"iss.meta": "off", "iss.only": "securities", "start": start},
        )
        block = data.get("securities", {})
        cols = block.get("columns") or cols
        chunk = block.get("data") or []
        if not chunk:
            break
        rows.extend(chunk)
        start += len(chunk)
        if len(chunk) < 500:
            break
    watch = {a.upper() for a in BASIS_WATCHLIST}
    out: list[dict] = []
    skip_assets = {"BTC", "ETH", "USD", "EUR", "CNY", "RTS", "MIX", "Si", "BR", "GOLD", "SILV"}
    for row in rows:
        d = dict(zip(cols, row))
        asset = str(d.get("ASSETCODE") or "").upper()
        secid = str(d.get("SECID") or "")
        if not asset or not secid or asset in skip_assets:
            continue
        if watch and asset not in watch:
            continue
        lot = int(d.get("LOTVOLUME") or 0)
        if lot < 1:
            continue
        ltd = str(d.get("LASTTRADEDATE") or "")[:10]
        if not ltd:
            continue
        try:
            if _d(ltd) < _today():
                continue
        except ValueError:
            continue
        # Одиночные акции: лот 1–1000, без префиксов индексов в shortname
        short = str(d.get("SHORTNAME") or secid)
        out.append(
            {
                "secid": secid,
                "shortname": short,
                "asset": asset,
                "lot_volume": lot,
                "last_trade_date": ltd,
                "last_del_date": str(d.get("LASTDELDATE") or ltd)[:10],
                "initial_margin": float(d.get("INITIALMARGIN") or 0),
                "prev_price": float(d.get("PREVPRICE") or d.get("LASTSETTLEPRICE") or 0),
            }
        )
    return out


def _fetch_daily_candles(engine: str, market: str, board: str, secid: str, days: int) -> dict[str, float]:
    till = _today().isoformat()
    fr = (_today() - timedelta(days=days + 5)).isoformat()
    if engine == "stock":
        url = (
            f"https://iss.moex.com/iss/engines/stock/markets/{market}/boards/"
            f"{board}/securities/{secid}/candles.json"
        )
    else:
        url = (
            f"https://iss.moex.com/iss/engines/futures/markets/{market}/"
            f"securities/{secid}/candles.json"
        )
    rows: list = []
    start = 0
    while True:
        data = _iss_get(
            url,
            {
                "from": fr,
                "till": till,
                "interval": 24,
                "iss.meta": "off",
                "iss.only": "candles",
                "start": start,
            },
        )
        chunk = data.get("candles", {}).get("data") or []
        if not chunk:
            break
        rows.extend(chunk)
        start += len(chunk)
        if len(chunk) < 500:
            break
    return {str(r[6])[:10]: float(r[1]) for r in rows if r[1] is not None}


def _spot_price(asset: str, spot_hist: dict[str, float], fut_meta: dict) -> float | None:
    if spot_hist:
        last_day = max(spot_hist.keys())
        return spot_hist[last_day]
    # fallback: ISS last quote
    url = (
        f"https://iss.moex.com/iss/engines/stock/markets/shares/boards/"
        f"{SPOT_BOARD}/securities/{asset}.json"
    )
    try:
        data = _iss_get(url, {"iss.meta": "off", "iss.only": "marketdata,securities"})
        for block in ("marketdata", "securities"):
            b = data.get(block, {})
            if not b.get("data"):
                continue
            cols = b["columns"]
            row = dict(zip(cols, b["data"][0]))
            for key in ("LAST", "LCURRENTPRICE", "PREVPRICE"):
                v = row.get(key)
                if v is not None and float(v) > 0:
                    return float(v)
    except Exception as exc:
        log.debug("spot quote %s: %s", asset, exc)
    pp = fut_meta.get("prev_price") or 0
    lot = int(fut_meta.get("lot_volume") or 1)
    return _fut_per_share(pp, lot) if pp > 0 else None


def _fut_per_share(price_pts: float, lot_volume: int) -> float:
    """Цена MOEX FORTS на акции: пункты / размер лота = ₽ за акцию."""
    lot = max(1, int(lot_volume or 1))
    return float(price_pts) / lot


def _div_pv(asset: str, spot: float, as_of: date, expiry: date) -> float:
    total = 0.0
    for ds, a, amt in DIVIDENDS:
        if a.upper() != asset.upper() or amt <= 0:
            continue
        dd = _d(ds)
        if as_of < dd <= expiry:
            total += amt
    return total


def _fair_basis(spot: float, days_to_exp: int, fin_rate: float, div_pv: float) -> float:
    if days_to_exp <= 0:
        return 0.0
    carry = spot * (fin_rate * days_to_exp / 365.0)
    return carry - div_pv


def _edge_ann(edge: float, spot: float, days_to_exp: int) -> float:
    if spot <= 0 or days_to_exp <= 0:
        return 0.0
    return (edge / spot) * (365.0 / days_to_exp) * 100.0


def _basis_history_z(
    spot_hist: dict[str, float],
    fut_hist: dict[str, float],
    *,
    asset: str,
    fin_rate: float,
    expiry: date,
    lot_volume: int,
) -> tuple[float | None, list[dict]]:
    days = sorted(set(spot_hist) & set(fut_hist))
    series: list[dict] = []
    edges: list[float] = []
    for d in days:
        spot = spot_hist[d]
        fut = _fut_per_share(fut_hist[d], lot_volume)
        dte = max(0, (expiry - _d(d)).days)
        div = _div_pv(asset, spot, _d(d), expiry)
        fair = _fair_basis(spot, dte, fin_rate, div)
        obs = fut - spot
        edge = obs - fair
        series.append({"date": d, "basis": round(obs, 4), "edge": round(edge, 4)})
        edges.append(edge)
    if len(edges) < 10:
        return None, series[-30:]
    window = edges[-Z_WINDOW:]
    mean = sum(window) / len(window)
    var = sum((x - mean) ** 2 for x in window) / len(window)
    std = var ** 0.5
    if std < 1e-9:
        return 0.0, series[-30:]
    z = (edges[-1] - mean) / std
    return z, series[-30:]


def _net_yield_ann(edge: float, spot: float, days_to_exp: int, lot: int) -> float:
    """Годовая доходность edge после грубой оценки комиссий (круг: open+close)."""
    if spot <= 0 or days_to_exp <= 0:
        return 0.0
    gross = edge * lot
    notional = spot * lot
    comm = notional * COMM_STOCK_PCT * 2 + COMM_FUT_RUB * 2
    net = gross - comm
    return (net / notional) * (365.0 / days_to_exp) * 100.0


def scan_basis(
    *,
    fin_rate: float = DEFAULT_FIN_RATE,
    min_yield_ann: float = DEFAULT_MIN_YIELD_ANN,
    z_entry: float = DEFAULT_Z_ENTRY,
    history_days: int = DEFAULT_HISTORY_DAYS,
) -> dict:
    cache_key = f"{fin_rate:.4f}|{min_yield_ann}|{z_entry}|{history_days}"
    now = time.time()
    with _cache_lock:
        hit = _cache.get(cache_key)
        if hit and now - hit[0] < _CACHE_TTL_SEC:
            return hit[1]

    futures = _fetch_forts_stock_futures()
    # Ближайший контракт на актив (минимальная дата экспирации)
    by_asset: dict[str, dict] = {}
    for f in futures:
        a = f["asset"]
        cur = by_asset.get(a)
        if cur is None or f["last_trade_date"] < cur["last_trade_date"]:
            by_asset[a] = f

    def _scan_one(asset: str, meta: dict) -> dict | None:
        secid = meta["secid"]
        spot_ticker = SPOT_TICKER_MAP.get(asset.upper(), asset)
        expiry = _d(meta["last_trade_date"])
        days_to_exp = max(0, (expiry - _today()).days)
        if days_to_exp < 3:
            return None

        spot_hist = _fetch_daily_candles("stock", "shares", SPOT_BOARD, spot_ticker, history_days)
        if not spot_hist:
            return None
        fut_hist = _fetch_daily_candles("futures", "forts", "", secid, history_days)
        if not fut_hist:
            if meta.get("prev_price"):
                fut_hist = {max(spot_hist.keys()): meta["prev_price"]}
            else:
                return None

        spot = _spot_price(spot_ticker, spot_hist, meta)
        if spot is None or spot <= 0:
            return None

        fut_pts = fut_hist.get(max(fut_hist.keys()), meta.get("prev_price") or 0)
        if fut_pts <= 0:
            return None
        fut = _fut_per_share(fut_pts, meta["lot_volume"])

        div = _div_pv(asset, spot, _today(), expiry)
        fair = _fair_basis(spot, days_to_exp, fin_rate, div)
        basis_obs = fut - spot
        edge = basis_obs - fair
        edge_ann = _edge_ann(edge, spot, days_to_exp)
        net_ann = _net_yield_ann(edge, spot, days_to_exp, meta["lot_volume"])
        z, hist = _basis_history_z(
            spot_hist,
            fut_hist,
            asset=asset,
            fin_rate=fin_rate,
            expiry=expiry,
            lot_volume=meta["lot_volume"],
        )
        signal = bool(net_ann >= min_yield_ann and z is not None and z >= z_entry and edge > 0)
        return {
            "asset": asset,
            "secid": secid,
            "shortname": meta["shortname"],
            "spot": round(spot, 2),
            "fut": round(fut, 2),
            "basis_obs": round(basis_obs, 2),
            "basis_fair": round(fair, 2),
            "edge": round(edge, 2),
            "edge_ann_pct": round(edge_ann, 1),
            "net_ann_pct": round(net_ann, 1),
            "z_score": round(z, 2) if z is not None else None,
            "days_to_exp": days_to_exp,
            "expiry": meta["last_trade_date"],
            "lot_volume": meta["lot_volume"],
            "initial_margin": round(meta["initial_margin"], 2),
            "div_pv": round(div, 2),
            "fin_rate_pct": round(fin_rate * 100, 1),
            "signal": signal,
            "history": hist,
        }

    rows: list[dict] = []
    with ThreadPoolExecutor(max_workers=6) as pool:
        futs = {pool.submit(_scan_one, a, m): a for a, m in by_asset.items()}
        for fut in as_completed(futs):
            asset = futs[fut]
            try:
                row = fut.result()
                if row:
                    rows.append(row)
            except Exception as exc:
                log.warning("basis scan skip %s: %s", asset, exc)

    rows.sort(key=lambda r: (-r["net_ann_pct"], -(r["z_score"] or -99)))

    signals = [r for r in rows if r["signal"]]
    payload = {
        "as_of": datetime.now().isoformat(timespec="seconds"),
        "fin_rate_pct": round(fin_rate * 100, 1),
        "min_yield_ann_pct": min_yield_ann,
        "z_entry": z_entry,
        "contracts_scanned": len(rows),
        "signals_count": len(signals),
        "summary": (
            f"Скан {len(rows)} контрактов · сигналов {len(signals)} "
            f"(net >= {min_yield_ann:.0f}% год., Z >= {z_entry:.1f})."
        ),
        "rows": rows,
    }

    with _cache_lock:
        _cache[cache_key] = (now, payload)
    return payload
