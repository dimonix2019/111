"""Загрузка 15м TATN/TATNP с MOEX ISS для Streamlit (как MoexIssData.kt)."""

from __future__ import annotations

import csv
import json
import statistics
import time
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple
from zoneinfo import ZoneInfo

ProgressFn = Callable[[str], None]

MSK = ZoneInfo("Europe/Moscow")
ISS_BASE = "https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities"
DEFAULT_LOOKBACK_DAYS = 255
STALE_HOURS = 40
MIN_BARS_FULL = 8_000  # ~255д 15м; меньше — неполная выгрузка
ISS_PAGE_SIZE = 500
CHUNK_CALENDAR_DAYS = 35  # окна по датам — надёжнее одного start=… на весь год

# Готовый ряд в репозитории (если ISS оборвался в Streamlit)
GITHUB_RAW_CSV = (
    "https://raw.githubusercontent.com/dimonix2019/111/"
    "cursor/strategy-web-moex-parity-4bf6/strategy-web/data/m15_tatn_255d.csv"
)


def _get_json(url: str, retries: int = 3) -> dict:
    last_err: Optional[Exception] = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "moexmvp-strategy-web/1.0"})
            with urllib.request.urlopen(req, timeout=120) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as e:
            last_err = e
            time.sleep(0.4 * (attempt + 1))
    raise RuntimeError(f"ISS request failed: {url}") from last_err


def _fetch_candles_10m_window(
    sec_id: str, from_d: date, till_d: date
) -> List[Tuple[datetime, float]]:
    """Одно окно дат с полной пагинацией start=0,500,1000…"""
    start = 0
    bars: List[Tuple[datetime, float]] = []
    while True:
        qs = urllib.parse.urlencode(
            {
                "iss.meta": "off",
                "candles.columns": "begin,open,high,low,close",
                "interval": 10,
                "from": from_d.isoformat(),
                "till": till_d.isoformat(),
                "limit": ISS_PAGE_SIZE,
                "start": start,
            }
        )
        url = f"{ISS_BASE}/{sec_id}/candles.json?{qs}"
        data = _get_json(url)
        rows = data.get("candles", {}).get("data") or []
        if not rows:
            break
        for row in rows:
            ts = datetime.strptime(row[0][:19], "%Y-%m-%d %H:%M:%S").replace(tzinfo=MSK)
            bars.append((ts, float(row[4])))
        if len(rows) < ISS_PAGE_SIZE:
            break
        start += ISS_PAGE_SIZE
    return bars


def fetch_candles_10m(
    sec_id: str,
    from_d: date,
    till_d: date,
    on_progress: Optional[ProgressFn] = None,
) -> List[Tuple[datetime, float]]:
    """
    10м свечи TQBR с пагинацией.
    Большой диапазон качаем окнами по CHUNK_CALENDAR_DAYS — так не упираемся в лимиты ISS
    и виден прогресс (важно для Streamlit).
    """
    merged: Dict[datetime, float] = {}
    chunk_start = from_d
    while chunk_start < till_d:
        chunk_end = min(chunk_start + timedelta(days=CHUNK_CALENDAR_DAYS), till_d)
        if on_progress:
            on_progress(f"{sec_id} 10м: {chunk_start} … {chunk_end}")
        for ts, close in _fetch_candles_10m_window(sec_id, chunk_start, chunk_end):
            merged[ts] = close
        chunk_start = chunk_end + timedelta(days=1)
    return sorted(merged.items(), key=lambda x: x[0])


def aggregate_15m(bars_10m: List[Tuple[datetime, float]]) -> Dict[datetime, float]:
    buckets: Dict[datetime, List[float]] = {}
    for ts, close in sorted(bars_10m, key=lambda x: x[0]):
        minute = (ts.minute // 15) * 15
        bucket = ts.replace(minute=minute, second=0, microsecond=0)
        buckets.setdefault(bucket, []).append(close)
    return {bucket: closes[-1] for bucket, closes in buckets.items()}


def build_spread_series(
    tatn: Dict[datetime, float], tatnp: Dict[datetime, float]
) -> List[Tuple[datetime, float, float, float]]:
    times = sorted(set(tatn.keys()) & set(tatnp.keys()))
    rows: List[Tuple[datetime, float, float, float]] = []
    for ts in times:
        c1, c2 = tatn[ts], tatnp[ts]
        if c2 == 0:
            continue
        spread = (c1 / c2 - 1.0) * 100.0
        rows.append((ts, c1, c2, spread))
    return rows


def apply_z_scores(rows: List[Tuple[datetime, float, float, float]]) -> List[dict]:
    spreads = [r[3] for r in rows]
    if not spreads:
        return []
    mean = statistics.mean(spreads)
    std = statistics.pstdev(spreads) if len(spreads) > 1 else 1.0
    if std <= 0:
        std = 1.0
    out = []
    for ts, tatn, tatnp, spread in rows:
        z = (spread - mean) / std
        out.append(
            {
                "timestamp": ts.strftime("%Y-%m-%d %H:%M:%S"),
                "z_score": round(z, 6),
                "spread_percent": round(spread, 6),
                "tatn_close": round(tatn, 6),
                "tatnp_close": round(tatnp, 6),
            }
        )
    return out


def moex_fetch_till() -> date:
    return (datetime.now(MSK).date() + timedelta(days=1))


def download_m15_csv(
    out_path: Path,
    days: int = DEFAULT_LOOKBACK_DAYS,
    on_progress: Optional[ProgressFn] = None,
) -> dict:
    """Скачать ISS → 15м → CSV. Возвращает сводку."""
    def prog(msg: str) -> None:
        if on_progress:
            on_progress(msg)

    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    till = moex_fetch_till()
    from_d = till - timedelta(days=days + 2)

    prog(f"MOEX ISS: TATN/TATNP 10м, {from_d} … {till} (окна по {CHUNK_CALENDAR_DAYS} дн.)")
    tatn_10 = fetch_candles_10m("TATN", from_d, till, on_progress=prog)
    prog(f"TATN 10м всего: {len(tatn_10)} баров")
    tatnp_10 = fetch_candles_10m("TATNP", from_d, till, on_progress=prog)
    prog(f"TATNP 10м всего: {len(tatnp_10)} баров")

    tatn_15 = aggregate_15m(tatn_10)
    tatnp_15 = aggregate_15m(tatnp_10)
    spread_rows = build_spread_series(tatn_15, tatnp_15)
    prog(f"15м после выравнивания: {len(spread_rows)} баров")

    records = apply_z_scores(spread_rows)
    if not records:
        raise RuntimeError("MOEX вернул пустой ряд — проверьте интернет и даты.")

    if len(records) < MIN_BARS_FULL:
        raise RuntimeError(
            f"Выгрузка неполная: {len(records)} баров 15м (нужно ≥{MIN_BARS_FULL}). "
            f"Часто Streamlit обрывает долгий запрос (~1000 строк). "
            f"Запустите в PowerShell: "
            f'python download_m15.py --days {days} --out "{out_path}" '
            f"или «Скачать готовый CSV с GitHub»."
        )

    fieldnames = ["timestamp", "z_score", "spread_percent", "tatn_close", "tatnp_close"]
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        w.writerows(records)

    return {
        "bar_count": len(records),
        "first_ts": records[0]["timestamp"],
        "last_ts": records[-1]["timestamp"],
        "path": str(out_path),
    }


def download_csv_from_github(
    out_path: Path,
    url: str = GITHUB_RAW_CSV,
    on_progress: Optional[ProgressFn] = None,
) -> dict:
    """Готовый полный CSV из репозитория (обход обрыва Streamlit / старого loader без пагинации)."""
    if on_progress:
        on_progress(f"GitHub: {url}")
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "moexmvp-strategy-web/1.0"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        data = resp.read()
    out_path.write_bytes(data)
    status = csv_file_status(out_path, min_bars=MIN_BARS_FULL)
    if not status["looks_full"]:
        raise RuntimeError(
            f"С GitHub скачано только {status['bar_count']} строк "
            f"(репозиторий приватный или URL недоступен). "
            f"Скопируйте data/m15_tatn_255d.csv из git или запустите download_m15.py в терминале."
        )
    return {
        "bar_count": status["bar_count"],
        "first_ts": status["first_ts"],
        "last_ts": status["last_ts"],
        "path": str(out_path),
        "source": "github",
    }


def csv_file_status(path: Path, min_bars: int = MIN_BARS_FULL) -> dict:
    """Статус CSV на диске (для sidebar)."""
    path = Path(path)
    if not path.is_file():
        return {
            "exists": False,
            "bar_count": 0,
            "looks_full": False,
            "stale": True,
            "age_hours": None,
            "first_ts": "",
            "last_ts": "",
            "mtime": None,
        }

    mtime = datetime.fromtimestamp(path.stat().st_mtime)
    age_hours = (datetime.now() - mtime).total_seconds() / 3600.0
    stale = age_hours > STALE_HOURS

    bar_count = 0
    first_ts = ""
    last_ts = ""
    try:
        with open(path, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                bar_count += 1
                ts = row.get("timestamp", "")
                if bar_count == 1:
                    first_ts = ts
                last_ts = ts
    except OSError:
        pass

    return {
        "exists": True,
        "bar_count": bar_count,
        "looks_full": bar_count >= min_bars,
        "stale": stale,
        "age_hours": round(age_hours, 1),
        "first_ts": first_ts,
        "last_ts": last_ts,
        "mtime": mtime.isoformat(timespec="seconds"),
    }


def ensure_csv(
    path: Path,
    days: int = DEFAULT_LOOKBACK_DAYS,
    auto_download: bool = False,
    on_progress: Optional[ProgressFn] = None,
) -> tuple[bool, dict]:
    """
    Если файла нет или мало баров — опционально скачать.
    Возвращает (downloaded_now, status_dict).
    """
    status = csv_file_status(path)
    need = not status["exists"] or not status["looks_full"]
    if need and auto_download:
        summary = download_m15_csv(path, days=days, on_progress=on_progress)
        status = csv_file_status(path)
        status["download_summary"] = summary
        return True, status
    return False, status
