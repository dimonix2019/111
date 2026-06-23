"""Загрузка 15м TATN/TATNP с MOEX ISS (10м свечи → 15м spread + Z)."""

from __future__ import annotations

import logging
import threading
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import requests

from api.moex_time import MOEX_TZ, moex_age_seconds, moex_now, parse_moex_datetime, to_moex_datetime

log = logging.getLogger(__name__)

_download_lock = threading.Lock()

DEFAULT_M15_CSV = Path("data/m15_tatn_255d.csv")
LOOKBACK_DAYS = 255
STALE_HOURS = 3
# Онлайн-опрос (5с): запасной порог по возрасту последнего бара
LIVE_STALE_HOURS = 10 / 60.0
BAR_MINUTES = 15
BOARD = "TQBR"
ISS_TIMEOUT = 60
INTERVAL_10 = 10


def _iss_candles_url(secid: str) -> str:
    return (
        "https://iss.moex.com/iss/engines/stock/markets/shares/boards/"
        f"{BOARD}/securities/{secid}/candles.json"
    )


def _fetch_candles(secid: str, date_from: str, date_till: str) -> pd.DataFrame:
    rows: list = []
    start = 0
    while True:
        params = {
            "interval": INTERVAL_10,
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
        return pd.DataFrame(columns=["timestamp", "open", "high", "low", "close", "volume"])
    cols = ["open", "close", "high", "low", "value", "volume", "begin", "end"]
    df = pd.DataFrame(rows, columns=cols)
    df["timestamp"] = parse_moex_datetime(pd.to_datetime(df["begin"]))
    for c in ("open", "high", "low", "close", "volume"):
        df[c] = pd.to_numeric(df[c], errors="coerce")
    return df[["timestamp", "open", "high", "low", "close", "volume"]].dropna(subset=["close"])


def _floor_15m(dt: datetime) -> datetime:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=MOEX_TZ)
    return dt.replace(minute=(dt.minute // BAR_MINUTES) * BAR_MINUTES, second=0, microsecond=0)


def _to_15m_ohlc(df10: pd.DataFrame, prefix: str) -> pd.DataFrame:
    """10м OHLC → 15м OHLC (open=first, high=max, low=min, close=last)."""
    o, h, l, c = f"{prefix}_open", f"{prefix}_high", f"{prefix}_low", f"{prefix}_close"
    vol = f"{prefix}_volume"
    if df10.empty:
        return pd.DataFrame(columns=["timestamp", o, h, l, c, vol])
    df = df10.set_index("timestamp").sort_index()
    for col in ("open", "high", "low", "close", "volume"):
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    r = df.resample(f"{BAR_MINUTES}min")
    out = pd.DataFrame(
        {
            o: r["open"].first(),
            h: r["high"].max(),
            l: r["low"].min(),
            c: r["close"].last(),
            vol: r["volume"].sum() if "volume" in df.columns else np.nan,
        }
    ).dropna(subset=[c])
    out = out.reset_index().rename(columns={"index": "timestamp"})
    return out


def _spread_ohlc(merged: pd.DataFrame) -> pd.DataFrame:
    """Spread % OHLC из OHLC ног TATN/TATNP."""
    out = merged.copy()
    t_o, t_h, t_l, t_c = "tatn_open", "tatn_high", "tatn_low", "tatn_close"
    p_o, p_h, p_l, p_c = "tatnp_open", "tatnp_high", "tatnp_low", "tatnp_close"
    if not all(col in out.columns for col in (t_o, t_h, t_l, t_c, p_o, p_h, p_l, p_c)):
        out["spread_percent"] = (out[t_c] / out[p_c] - 1.0) * 100.0
        return out
    out["spread_open"] = (out[t_o] / out[p_o] - 1.0) * 100.0
    out["spread_high"] = (out[t_h] / out[p_l] - 1.0) * 100.0
    out["spread_low"] = (out[t_l] / out[p_h] - 1.0) * 100.0
    out["spread_close"] = (out[t_c] / out[p_c] - 1.0) * 100.0
    out["spread_percent"] = out["spread_close"]
    return out


def _recalc_z(merged: pd.DataFrame) -> pd.DataFrame:
    arr = merged["spread_percent"].to_numpy(dtype=float)
    std = float(np.std(arr))
    merged = merged.copy()
    merged["z_score"] = (arr - float(np.mean(arr))) / std if std > 0 else 0.0
    return merged


def _append_forming_15m_bar(
    merged: pd.DataFrame, tatn10: pd.DataFrame, tatnp10: pd.DataFrame
) -> pd.DataFrame:
    """Незакрытый 15м бар по 10м свечам текущего слота (чтобы хвост не отставал на период)."""
    if merged.empty or tatn10.empty or tatnp10.empty:
        return merged
    now = moex_now()
    bucket = _floor_15m(now)
    last_ts = to_moex_datetime(merged["timestamp"].iloc[-1])
    if last_ts >= bucket:
        return merged

    t10_ts = parse_moex_datetime(tatn10["timestamp"])
    p10_ts = parse_moex_datetime(tatnp10["timestamp"])
    t10 = tatn10.loc[(t10_ts >= bucket) & (t10_ts <= now)]
    p10 = tatnp10.loc[(p10_ts >= bucket) & (p10_ts <= now)]
    if t10.empty or p10.empty:
        return merged

    def _leg_ohlc(slice10: pd.DataFrame, prefix: str) -> dict:
        o = float(slice10["open"].iloc[0]) if "open" in slice10.columns else float(slice10["close"].iloc[0])
        h = float(slice10["high"].max()) if "high" in slice10.columns else float(slice10["close"].max())
        l = float(slice10["low"].min()) if "low" in slice10.columns else float(slice10["close"].min())
        c = float(slice10["close"].iloc[-1])
        vol = float(slice10["volume"].sum()) if "volume" in slice10.columns else np.nan
        return {
            f"{prefix}_open": o,
            f"{prefix}_high": h,
            f"{prefix}_low": l,
            f"{prefix}_close": c,
            f"{prefix}_volume": vol,
        }

    row = pd.DataFrame([{"timestamp": bucket.strftime("%Y-%m-%d %H:%M:%S"), **_leg_ohlc(t10, "tatn"), **_leg_ohlc(p10, "tatnp")}])
    row = _spread_ohlc(row)
    out = pd.concat([merged, row], ignore_index=True)
    out["timestamp"] = parse_moex_datetime(out["timestamp"])
    return _recalc_z(out)


def _build_m15_frame(tatn10: pd.DataFrame, tatnp10: pd.DataFrame, *, include_forming: bool = True) -> pd.DataFrame:
    tatn = _to_15m_ohlc(tatn10, "tatn")
    tatnp = _to_15m_ohlc(tatnp10, "tatnp")
    merged = pd.merge(tatn, tatnp, on="timestamp", how="inner")
    if merged.empty:
        return merged
    merged = _spread_ohlc(merged)
    merged = _recalc_z(merged)
    if include_forming:
        merged = _append_forming_15m_bar(merged, tatn10, tatnp10)
    merged["timestamp"] = merged["timestamp"].dt.strftime("%Y-%m-%d %H:%M:%S")
    cols = [
        "timestamp",
        "z_score",
        "spread_percent",
        "spread_open",
        "spread_high",
        "spread_low",
        "spread_close",
        "tatn_open",
        "tatn_high",
        "tatn_low",
        "tatn_close",
        "tatnp_open",
        "tatnp_high",
        "tatnp_low",
        "tatnp_close",
        "tatn_volume",
        "tatnp_volume",
    ]
    return merged[[c for c in cols if c in merged.columns]]


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
        progress_callback(0.95, "Готово")
    log.info("MOEX 15m bars: %s rows", len(frame))
    return frame.to_dict("records")


def save_m15_csv(rows, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    df = pd.DataFrame(rows)
    if "spread_percent" in df.columns:
        arr = df["spread_percent"].to_numpy(dtype=float)
        std = float(np.std(arr))
        df["z_score"] = (arr - float(np.mean(arr))) / std if std > 0 else 0.0
    df.to_csv(path, index=False)


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


def _merge_m15_csv(path: Path, new_rows: list[dict]) -> None:
    old = pd.read_csv(path)
    new = pd.DataFrame(new_rows)
    merged = pd.concat([old, new], ignore_index=True)
    merged["timestamp"] = parse_moex_datetime(merged["timestamp"])
    merged = merged.drop_duplicates(subset=["timestamp"], keep="last").sort_values("timestamp")

    cutoff = moex_now() - timedelta(days=LOOKBACK_DAYS + 5)
    merged = merged[merged["timestamp"] >= cutoff]

    arr = merged["spread_percent"].to_numpy(dtype=float)
    std = float(np.std(arr))
    merged["z_score"] = (arr - float(np.mean(arr))) / std if std > 0 else 0.0
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
    days: int = LOOKBACK_DAYS,
    force: bool = False,
    stale_hours: float = STALE_HOURS,
    moex_live: bool = False,
) -> tuple[Path, bool]:
    """Обновить CSV: полная загрузка или догрузка хвоста. Возвращает (path, refreshed)."""
    with _download_lock:
        status = m15_data_status(path, stale_hours=stale_hours)
        live_tail = moex_live and path.is_file() and needs_live_tail_refresh(path)
        needs_download = (
            force
            or not status.get("exists", False)
            or (path.is_file() and _looks_like_stub_csv(path))
            or status.get("row_count", 0) < 500
        )
        incremental = (
            not needs_download
            and path.is_file()
            and (status.get("is_stale", False) or live_tail)
        )

        if needs_download:
            log.info("MOEX full download → %s", path)
            data = fetch_m15_from_iss(days=days)
            save_m15_csv(data, path)
            return path, True

        if incremental:
            last_ts = pd.to_datetime(status["last_ts"])
            from_d = (last_ts - timedelta(days=3)).strftime("%Y-%m-%d")
            till_d = datetime.now().strftime("%Y-%m-%d")
            reason = "live-tail" if live_tail else "stale"
            log.info("MOEX incremental (%s) %s … %s → %s", reason, from_d, till_d, path)
            new_rows = fetch_m15_from_iss(date_from=from_d, date_till=till_d)
            _merge_m15_csv(path, new_rows)
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
            _merge_m15_csv(path, rows)
            return True
        except Exception as exc:
            log.warning("merge_live_intraday failed: %s", exc)
            return path.is_file()
