"""Время MOEX (Europe/Moscow) для m15_iss_loader."""

from __future__ import annotations

from datetime import datetime
from typing import Union

import pandas as pd
from zoneinfo import ZoneInfo

MOEX_TZ = ZoneInfo("Europe/Moscow")


def moex_now() -> datetime:
    return datetime.now(MOEX_TZ)


def parse_moex_datetime(value: Union[pd.Series, datetime, str]) -> pd.Series | datetime:
    if isinstance(value, pd.Series):
        ts = pd.to_datetime(value, errors="coerce")
        if ts.dt.tz is None:
            return ts.dt.tz_localize(MOEX_TZ)
        return ts.dt.tz_convert(MOEX_TZ)
    if isinstance(value, datetime):
        if value.tzinfo is None:
            return value.replace(tzinfo=MOEX_TZ)
        return value.astimezone(MOEX_TZ)
    ts = pd.to_datetime(str(value), errors="coerce")
    if pd.isna(ts):
        return moex_now()
    if ts.tzinfo is None:
        return ts.tz_localize(MOEX_TZ).to_pydatetime()
    return ts.tz_convert(MOEX_TZ).to_pydatetime()


def to_moex_datetime(value: Union[pd.Series, datetime, str]) -> datetime:
    parsed = parse_moex_datetime(value)
    if isinstance(parsed, pd.Series):
        if parsed.empty:
            return moex_now()
        return parsed.iloc[-1].to_pydatetime()
    return parsed


def moex_age_seconds(ts_str: str) -> float:
    ts = parse_moex_datetime(ts_str)
    if isinstance(ts, pd.Series):
        if ts.empty:
            return float("inf")
        ts = ts.iloc[-1].to_pydatetime()
    now = moex_now()
    return max(0.0, (now - ts).total_seconds())
