"""Europe/Moscow time helpers (parity с Android moexZoneId)."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import pandas as pd

MOEX_TZ = ZoneInfo("Europe/Moscow")


def moex_now() -> datetime:
    return datetime.now(MOEX_TZ)


def parse_moex_datetime(series_or_value) -> pd.Series | pd.Timestamp:
    """Парсинг timestamp в MSK (naive → localize)."""
    if isinstance(series_or_value, pd.Series):
        ts = pd.to_datetime(series_or_value, errors="coerce")
        if getattr(ts.dt, "tz", None) is None:
            return ts.dt.tz_localize(MOEX_TZ)
        return ts.dt.tz_convert(MOEX_TZ)
    ts = pd.to_datetime(series_or_value, errors="coerce")
    if ts is pd.NaT:
        return ts
    if getattr(ts, "tzinfo", None) is None:
        return ts.tz_localize(MOEX_TZ)
    return ts.tz_convert(MOEX_TZ)


def to_moex_datetime(value) -> datetime:
    parsed = parse_moex_datetime(value)
    if isinstance(parsed, pd.Series):
        return parsed.iloc[-1].to_pydatetime()
    return parsed.to_pydatetime()


def moex_age_seconds(last_ts_raw: str) -> float:
    last = parse_moex_datetime(last_ts_raw)
    if isinstance(last, pd.Series):
        last_dt = last.iloc[0].to_pydatetime()
    else:
        last_dt = last.to_pydatetime()
    return max(0.0, (moex_now() - last_dt).total_seconds())
