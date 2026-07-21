"""Загрузка 15м TATN/TATNP с MOEX ISS (10м свечи → 15м spread + Z)."""

from __future__ import annotations

import logging
import re
import threading
from datetime import datetime, timedelta
from pathlib import Path

import pandas as pd
import requests

from api.moex_time import MOEX_TZ, moex_age_seconds, moex_now, parse_moex_datetime, to_moex_datetime

log = logging.getLogger(__name__)

_download_lock = threading.RLock()

DEFAULT_M15_CSV = Path("data/m15_tatn_255d.csv")
LOOKBACK_DAYS = 255
DEFAULT_LOOKBACK_DAYS = LOOKBACK_DAYS
STALE_HOURS = 3
_LOOKBACK_FROM_NAME = re.compile(r"^m15_tatn_(\d+)d\.csv$", re.IGNORECASE)
# Онлайн-опрос (5с): запасной порог по возрасту последнего бара
LIVE_STALE_HOURS = 10 / 60.0
BAR_MINUTES = 15
BOARD = "TQBR"
ISS_TIMEOUT = 60
INTERVAL_10 = 10
INTERVAL_1 = 1
# ISS 10м свечи часто отстают на 1–2 слота; 1м хвост догоняет текущий 15м бар.
LIVE_TIP_1M_HOURS = 6
# LAST из marketdata (до появления свечей / внутри слота) — не чаще раза в N сек.
_LAST_OVERLAY_GAP_SEC = 5.0
_last_overlay_ok_ms = 0.0


def _iss_candles_url(secid: str) -> str:
    return (
        "https://iss.moex.com/iss/engines/stock/markets/shares/boards/"
        f"{BOARD}/securities/{secid}/candles.json"
    )


def _iss_security_url(secid: str) -> str:
    return (
        "https://iss.moex.com/iss/engines/stock/markets/shares/boards/"
        f"{BOARD}/securities/{secid}.json"
    )


def _fetch_candles(
    secid: str,
    date_from: str,
    date_till: str,
    *,
    interval: int = INTERVAL_10,
) -> pd.DataFrame:
    rows: list = []
    start = 0
    while True:
        params = {
            "interval": interval,
            "from": date_from,
            "till": date_till,
            "iss.meta": "off",
            "iss.only": "candles",
            "start": start,
        }
        resp = requests.get(_iss_candles_url(secid), params=params, timeout=ISS_TIMEOUT)
        resp.raise_for_status()
        data = resp.json().get("candles", {}).get("data") or []
        if not data:
            break
        rows.extend(data)
        start += len(data)
        if len(data) < 500:
            break
    if not rows:
        return pd.DataFrame(columns=["timestamp", "close"])
    cols = ["open", "close", "high", "low", "value", "volume", "begin", "end"]
    df = pd.DataFrame(rows, columns=cols)
    df["timestamp"] = parse_moex_datetime(pd.to_datetime(df["begin"]))
    df["close"] = pd.to_numeric(df["close"], errors="coerce")
    return df[["timestamp", "close"]].dropna()


def _floor_15m(dt: datetime) -> datetime:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=MOEX_TZ)
    return dt.replace(minute=(dt.minute // BAR_MINUTES) * BAR_MINUTES, second=0, microsecond=0)


def _to_15m(df10: pd.DataFrame, price_col: str) -> pd.DataFrame:
    if df10.empty:
        return pd.DataFrame(columns=["timestamp", price_col])
    s = df10.set_index("timestamp").sort_index()["close"].resample(f"{BAR_MINUTES}min").last().dropna()
    out = s.to_frame(price_col).reset_index()
    out.columns = ["timestamp", price_col]
    return out


def _timestamp_strings(series: pd.Series) -> list[str]:
    out: list[str] = []
    for t in series:
        if hasattr(t, "strftime"):
            out.append(t.strftime("%Y-%m-%d %H:%M:%S"))
        else:
            out.append(str(t).replace("T", " ")[:19])
    return out


def _recalc_z(merged: pd.DataFrame) -> pd.DataFrame:
    """Rolling Z 30д — как Android live / zsim (без look-ahead global)."""
    from zsim import apply_z_scores_rolling

    if merged.empty:
        return merged
    merged = merged.copy()
    timestamps = _timestamp_strings(merged["timestamp"])
    spreads = merged["spread_percent"].astype(float).tolist()
    merged["z_score"] = apply_z_scores_rolling(spreads, timestamps)
    return merged


def _append_forming_15m_bar(
    merged: pd.DataFrame, tatn_px: pd.DataFrame, tatnp_px: pd.DataFrame
) -> pd.DataFrame:
    """Добить 15м слоты от last+15м до текущего floor по доступным свечам (10м или 1м)."""
    if merged.empty or tatn_px.empty or tatnp_px.empty:
        return merged
    now = moex_now()
    bucket = _floor_15m(now)
    last_ts = to_moex_datetime(merged["timestamp"].iloc[-1])
    if last_ts >= bucket:
        return merged

    t_ts = parse_moex_datetime(tatn_px["timestamp"])
    p_ts = parse_moex_datetime(tatnp_px["timestamp"])
    rows: list[dict] = []
    slot = last_ts + timedelta(minutes=BAR_MINUTES)
    while slot <= bucket:
        slot_end = slot + timedelta(minutes=BAR_MINUTES)
        hi = min(slot_end, now)
        t_slice = tatn_px.loc[(t_ts >= slot) & (t_ts < hi)]
        p_slice = tatnp_px.loc[(p_ts >= slot) & (p_ts < hi)]
        # Для текущего (ещё открытого) слота допускаем свечи с begin == slot …
        # и до now включительно (1м begin совпадает с минутой).
        if t_slice.empty or p_slice.empty:
            t_slice = tatn_px.loc[(t_ts >= slot) & (t_ts <= now)]
            p_slice = tatnp_px.loc[(p_ts >= slot) & (p_ts <= now)]
        if not t_slice.empty and not p_slice.empty:
            rows.append(
                {
                    "timestamp": slot.strftime("%Y-%m-%d %H:%M:%S"),
                    "tatn_close": float(t_slice["close"].iloc[-1]),
                    "tatnp_close": float(p_slice["close"].iloc[-1]),
                }
            )
        slot += timedelta(minutes=BAR_MINUTES)

    if not rows:
        return merged

    add = pd.DataFrame(rows)
    add["spread_percent"] = (add["tatn_close"] / add["tatnp_close"] - 1.0) * 100.0
    out = pd.concat([merged, add], ignore_index=True)
    out["timestamp"] = parse_moex_datetime(out["timestamp"])
    out = out.drop_duplicates(subset=["timestamp"], keep="last").sort_values("timestamp")
    return _recalc_z(out)


def _build_m15_frame(
    tatn_px: pd.DataFrame,
    tatnp_px: pd.DataFrame,
    *,
    include_forming: bool = True,
) -> pd.DataFrame:
    tatn = _to_15m(tatn_px, "tatn_close")
    tatnp = _to_15m(tatnp_px, "tatnp_close")
    merged = pd.merge(tatn, tatnp, on="timestamp", how="inner")
    if merged.empty:
        return merged
    merged["spread_percent"] = (merged["tatn_close"] / merged["tatnp_close"] - 1.0) * 100.0
    merged = _recalc_z(merged)
    if include_forming:
        merged = _append_forming_15m_bar(merged, tatn_px, tatnp_px)
    merged["timestamp"] = merged["timestamp"].dt.strftime("%Y-%m-%d %H:%M:%S")
    return merged[["timestamp", "z_score", "spread_percent", "tatn_close", "tatnp_close"]]


def _overlay_1m_tip(frame: pd.DataFrame, *, till: str) -> pd.DataFrame:
    """Перекрыть хвост 15м рядом из 1м свечей — ISS 10м часто отстаёт на слот+."""
    if frame.empty:
        return frame
    end = moex_now()
    tip_from = (end - timedelta(hours=LIVE_TIP_1M_HOURS)).strftime("%Y-%m-%d")
    try:
        tatn1 = _fetch_candles("TATN", tip_from, till, interval=INTERVAL_1)
        tatnp1 = _fetch_candles("TATNP", tip_from, till, interval=INTERVAL_1)
    except Exception as exc:
        log.warning("MOEX 1m tip skipped: %s", exc)
        return frame
    tip = _build_m15_frame(tatn1, tatnp1, include_forming=True)
    if tip.empty:
        return frame

    base = frame.copy()
    base["timestamp"] = parse_moex_datetime(base["timestamp"])
    tip2 = tip.copy()
    tip2["timestamp"] = parse_moex_datetime(tip2["timestamp"])
    head = base[base["timestamp"] < tip2["timestamp"].iloc[0]]
    merged = pd.concat([head, tip2], ignore_index=True)
    merged = merged.drop_duplicates(subset=["timestamp"], keep="last").sort_values("timestamp")
    if not base.empty and not merged.empty:
        if to_moex_datetime(merged["timestamp"].iloc[-1]) < to_moex_datetime(
            base["timestamp"].iloc[-1]
        ):
            return frame
    merged = _recalc_z(merged)
    merged["timestamp"] = merged["timestamp"].dt.strftime("%Y-%m-%d %H:%M:%S")
    return merged[["timestamp", "z_score", "spread_percent", "tatn_close", "tatnp_close"]]


def _live_tip_session_ok(now: datetime | None = None) -> bool:
    """Будни, утро→вечер: LAST есть до/без свечей (утро TQBR ~07:00)."""
    now = now or moex_now()
    if now.weekday() >= 5:
        return False
    mins = now.hour * 60 + now.minute
    return (6 * 60 + 30) <= mins <= (23 * 60 + 50)


def _fetch_last_price(secid: str) -> float | None:
    """LAST из ISS marketdata (тик без свечи)."""
    resp = requests.get(
        _iss_security_url(secid),
        params={"iss.meta": "off", "iss.only": "marketdata"},
        timeout=15,
    )
    resp.raise_for_status()
    block = resp.json().get("marketdata") or {}
    cols = block.get("columns") or []
    rows = block.get("data") or []
    if not cols or not rows:
        return None
    row = dict(zip(cols, rows[0]))
    last = row.get("LAST")
    if last is None:
        last = row.get("LCURRENTPRICE") or row.get("MARKETPRICE")
    try:
        px = float(last)
    except (TypeError, ValueError):
        return None
    return px if px > 0 else None


def fetch_live_last_prices() -> tuple[float, float] | None:
    """(TATN, TATNP) LAST или None."""
    if not _live_tip_session_ok():
        return None
    try:
        tatn = _fetch_last_price("TATN")
        tatnp = _fetch_last_price("TATNP")
    except Exception as exc:
        log.warning("MOEX marketdata LAST failed: %s", exc)
        return None
    if tatn is None or tatnp is None:
        return None
    return float(tatn), float(tatnp)


def _overlay_live_last_prices(frame: pd.DataFrame, tatn: float, tatnp: float) -> pd.DataFrame:
    """Обновить/добавить текущий 15м слот ценами LAST (как в Тинькофф до закрытия свечи)."""
    if frame.empty or tatn <= 0 or tatnp <= 0:
        return frame
    bucket = _floor_15m(moex_now())
    bucket_s = bucket.strftime("%Y-%m-%d %H:%M:%S")
    spread = (tatn / tatnp - 1.0) * 100.0

    out = frame.copy()
    out["timestamp"] = parse_moex_datetime(out["timestamp"])
    mask = out["timestamp"] == bucket
    if mask.any():
        out.loc[mask, "tatn_close"] = tatn
        out.loc[mask, "tatnp_close"] = tatnp
        out.loc[mask, "spread_percent"] = spread
    else:
        add = pd.DataFrame(
            [
                {
                    "timestamp": bucket,
                    "tatn_close": tatn,
                    "tatnp_close": tatnp,
                    "spread_percent": spread,
                    "z_score": 0.0,
                }
            ]
        )
        out = pd.concat([out, add], ignore_index=True)
        out = out.sort_values("timestamp")
    out = _recalc_z(out)
    out["timestamp"] = out["timestamp"].dt.strftime("%Y-%m-%d %H:%M:%S")
    cols = ["timestamp", "z_score", "spread_percent", "tatn_close", "tatnp_close"]
    return out[cols]


def apply_live_last_overlay(path: Path = DEFAULT_M15_CSV, *, force: bool = False) -> bool:
    """
    Догоняет тик по ISS LAST, когда свечей ещё нет (утро) или 10м/1м отстают.
    Пишет CSV; True если хвост изменился.
    """
    global _last_overlay_ok_ms
    import time as _time

    if not path.is_file():
        return False
    now_ms = _time.time() * 1000.0
    if not force and _last_overlay_ok_ms and (now_ms - _last_overlay_ok_ms) < _LAST_OVERLAY_GAP_SEC * 1000:
        return False

    prices = fetch_live_last_prices()
    if not prices:
        return False
    tatn, tatnp = prices

    with _download_lock:
        last_raw = _csv_last_timestamp(path)
        try:
            old = pd.read_csv(path)
        except Exception as exc:
            log.warning("live LAST overlay read failed: %s", exc)
            return False
        if old.empty or "tatn_close" not in old.columns:
            return False

        bucket = _floor_15m(moex_now()).strftime("%Y-%m-%d %H:%M:%S")
        if last_raw == bucket:
            last_row = old.iloc[-1]
            if (
                abs(float(last_row["tatn_close"]) - tatn) < 1e-6
                and abs(float(last_row["tatnp_close"]) - tatnp) < 1e-6
            ):
                _last_overlay_ok_ms = now_ms
                return False

        updated = _overlay_live_last_prices(old, tatn, tatnp)
        updated.to_csv(path, index=False)
        _last_overlay_ok_ms = now_ms
        log.info(
            "MOEX live LAST tip → %s TATN=%.2f TATNP=%.2f spread=%.4f",
            bucket,
            tatn,
            tatnp,
            (tatn / tatnp - 1.0) * 100.0,
        )
        return True

def lookback_days_for_path(path: Path | str, default: int = LOOKBACK_DAYS) -> int:
    """Дни истории из имени `m15_tatn_{N}d.csv`, иначе default."""
    name = Path(path).name
    m = _LOOKBACK_FROM_NAME.match(name)
    if m:
        return max(1, int(m.group(1)))
    return default


def fetch_m15_from_iss(
    days: int = LOOKBACK_DAYS,
    date_from: str | None = None,
    date_till: str | None = None,
    progress_callback=None,
) -> list[dict]:
    end = moex_now()
    till = date_till or end.strftime("%Y-%m-%d")
    start = date_from or (end - timedelta(days=days)).strftime("%Y-%m-%d")

    if progress_callback:
        progress_callback(0.1, "MOEX TATN…")
    log.info("MOEX fetch TATN %s … %s", start, till)
    tatn10 = _fetch_candles("TATN", start, till)

    if progress_callback:
        progress_callback(0.45, "MOEX TATNP…")
    log.info("MOEX fetch TATNP %s … %s", start, till)
    tatnp10 = _fetch_candles("TATNP", start, till)

    if progress_callback:
        progress_callback(0.75, "15м spread…")
    frame = _build_m15_frame(tatn10, tatnp10)
    if frame.empty:
        raise RuntimeError(f"MOEX: нет 15м баров за {start} … {till}")

    if progress_callback:
        progress_callback(0.85, "MOEX 1м хвост…")
    frame = _overlay_1m_tip(frame, till=till)

    prices = fetch_live_last_prices()
    if prices:
        frame = _overlay_live_last_prices(frame, prices[0], prices[1])

    if progress_callback:
        progress_callback(0.95, "Готово")
    log.info("MOEX 15m bars: %s rows (last %s)", len(frame), frame.iloc[-1]["timestamp"] if len(frame) else "—")
    return frame.to_dict("records")


def save_m15_csv(rows, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    df = pd.DataFrame(rows)
    if "spread_percent" in df.columns and "timestamp" in df.columns:
        df = _recalc_z(df)
    df.to_csv(path, index=False)


def download_m15_csv(
    path: Path,
    days: int | None = None,
    on_progress=None,
) -> dict:
    """Полная выгрузка ISS → CSV. Совместимо с download_m15.py / export_m15_iss.py."""
    path = Path(path)
    lookback = days if days is not None else lookback_days_for_path(path)

    def _cb(frac: float, msg: str) -> None:
        if on_progress:
            on_progress(f"{int(frac * 100)}% {msg}")

    rows = fetch_m15_from_iss(days=lookback, progress_callback=_cb)
    save_m15_csv(rows, path)
    status = m15_data_status(path)
    return {
        "path": str(path),
        "bar_count": status.get("row_count", len(rows)),
        "first_ts": status.get("first_ts"),
        "last_ts": status.get("last_ts"),
        "days": lookback,
    }


def _csv_row_count(path: Path) -> int:
    with path.open("r", encoding="utf-8") as f:
        return max(0, sum(1 for _ in f) - 1)


def _csv_last_timestamp(path: Path) -> str | None:
    with path.open("rb") as f:
        f.seek(0, 2)
        size = f.tell()
        if size == 0:
            return None
        chunk = min(size, 16384)
        f.seek(size - chunk)
        tail = f.read().decode("utf-8", errors="ignore")
    lines = [ln.strip() for ln in tail.splitlines() if ln.strip()]
    if not lines:
        return None
    last = lines[-1]
    if last.lower().startswith("timestamp"):
        return None
    return last.split(",", 1)[0].strip()


def m15_data_status(path: Path, stale_hours: float = STALE_HOURS) -> dict:
    if not path.exists():
        return {"exists": False, "is_stale": True, "stale_hours": stale_hours}

    try:
        row_count = _csv_row_count(path)
        if row_count <= 0:
            return {
                "exists": True,
                "row_count": 0,
                "is_stale": True,
                "stale_hours": stale_hours,
            }

        last_raw = _csv_last_timestamp(path)
        if not last_raw:
            return {"exists": True, "row_count": row_count, "is_stale": True, "stale_hours": stale_hours}

        age_sec = moex_age_seconds(last_raw)
        is_stale = age_sec > stale_hours * 3600

        # first_ts — только при необходимости (дорого на больших файлах не читаем целиком)
        first_ts = None
        with path.open("r", encoding="utf-8") as f:
            f.readline()
            first_line = f.readline().strip()
            if first_line:
                first_ts = first_line.split(",", 1)[0].strip()

        return {
            "exists": True,
            "row_count": row_count,
            "first_ts": first_ts,
            "last_ts": str(last_raw),
            "file_size": f"{path.stat().st_size / 1024:.1f} KB",
            "is_stale": is_stale,
            "stale_hours": stale_hours,
            "age_hours": round(age_sec / 3600, 2),
            "needs_refresh": is_stale,
        }
    except Exception as e:
        return {"exists": False, "error": str(e), "is_stale": True, "stale_hours": stale_hours}


def _merge_m15_csv(path: Path, new_rows: list[dict], *, keep_days: int | None = None) -> None:
    old = pd.read_csv(path)
    new = pd.DataFrame(new_rows)
    merged = pd.concat([old, new], ignore_index=True)
    merged["timestamp"] = parse_moex_datetime(merged["timestamp"])
    merged = merged.drop_duplicates(subset=["timestamp"], keep="last").sort_values("timestamp")

    days = keep_days if keep_days is not None else lookback_days_for_path(path)
    cutoff = moex_now() - timedelta(days=days + 5)
    merged = merged[merged["timestamp"] >= cutoff]
    merged = _recalc_z(merged)
    merged["timestamp"] = merged["timestamp"].dt.strftime("%Y-%m-%d %H:%M:%S")
    merged.to_csv(path, index=False)


def needs_live_tail_refresh(path: Path) -> bool:
    """True, если в CSV нет открытого сейчас 15м слота (напр. last=14:30 при now=14:46)."""
    last_raw = _csv_last_timestamp(path)
    if not last_raw:
        return True
    last_ts = pd.to_datetime(last_raw)
    if last_ts.tzinfo is None:
        last_ts = last_ts.tz_localize(MOEX_TZ)
    return last_ts.to_pydatetime() < _floor_15m(moex_now())


def _looks_like_stub_csv(path: Path) -> bool:
    """Старый stub-CSV без колонок MOEX — перекачать полностью."""
    try:
        with path.open("r", encoding="utf-8") as f:
            header = f.readline().strip().lower()
        return "tatn_close" not in header
    except OSError:
        return False


def ensure_m15_data(
    path: Path = DEFAULT_M15_CSV,
    days: int | None = None,
    force: bool = False,
    stale_hours: float = STALE_HOURS,
    moex_live: bool = False,
) -> tuple[Path, bool]:
    """Обновить CSV: полная загрузка или догрузка хвоста. Возвращает (path, refreshed)."""
    path = Path(path)
    lookback = days if days is not None else lookback_days_for_path(path)
    with _download_lock:
        status = m15_data_status(path, stale_hours=stale_hours)
        live_tail = moex_live and path.is_file() and needs_live_tail_refresh(path)
        # Короткие тестовые CSV (напр. 3д) не считаем «пустыми» по порогу 500.
        min_rows = 50 if lookback <= 30 else 500
        needs_download = (
            force
            or not status.get("exists", False)
            or (path.is_file() and _looks_like_stub_csv(path))
            or status.get("row_count", 0) < min_rows
        )
        incremental = (
            not needs_download
            and path.is_file()
            and (status.get("is_stale", False) or live_tail)
        )

        if needs_download:
            log.info("MOEX full download (%sd) → %s", lookback, path)
            data = fetch_m15_from_iss(days=lookback)
            save_m15_csv(data, path)
            return path, True

        if incremental:
            last_ts = pd.to_datetime(status["last_ts"])
            from_d = (last_ts - timedelta(days=3)).strftime("%Y-%m-%d")
            till_d = datetime.now().strftime("%Y-%m-%d")
            reason = "live-tail" if live_tail else "stale"
            log.info("MOEX incremental (%s) %s … %s → %s", reason, from_d, till_d, path)
            new_rows = fetch_m15_from_iss(date_from=from_d, date_till=till_d)
            _merge_m15_csv(path, new_rows, keep_days=lookback)
            return path, True

        # Свечи свежие, но LAST внутри слота мог уйти — лёгкий tip.
        if moex_live and apply_live_last_overlay(path):
            return path, True

        return path, False


def merge_live_intraday(path: Path = DEFAULT_M15_CSV) -> bool:
    """Каждый live-тик: MOEX за 2 дня → merge + формирующийся 15м бар (как TradingView)."""
    with _download_lock:
        till_d = datetime.now().strftime("%Y-%m-%d")
        from_d = (datetime.now() - timedelta(days=2)).strftime("%Y-%m-%d")
        try:
            if not path.is_file():
                save_m15_csv(fetch_m15_from_iss(days=30), path)
                return True
            rows = fetch_m15_from_iss(date_from=from_d, date_till=till_d)
            _merge_m15_csv(path, rows, keep_days=lookback_days_for_path(path))
            # fetch_m15_from_iss уже кладёт LAST-tip в rows; force не нужен
            return True
        except Exception as exc:
            log.warning("merge_live_intraday failed: %s", exc)
            return path.is_file()
