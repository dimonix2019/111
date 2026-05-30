"""Время MOEX / CSV: всегда Europe/Moscow (без сдвига +3ч на графике)."""

from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd

MOEX_TZ = ZoneInfo("Europe/Moscow")


def parse_moex_datetime(series: pd.Series) -> pd.Series:
    """Наивные метки из CSV/ISS — московское биржевое время."""
    ts = pd.to_datetime(series)
    if getattr(ts.dt, "tz", None) is None:
        return ts.dt.tz_localize(MOEX_TZ)
    return ts.dt.tz_convert(MOEX_TZ)


def moex_ts_to_unix(series: pd.Series) -> np.ndarray:
    """Unix sec (UTC instant) для lightweight-charts — подпись в браузере = MSK."""
    aware = parse_moex_datetime(series)
    return aware.map(lambda t: int(t.timestamp())).to_numpy(dtype=np.int64)


def moex_now() -> datetime:
    return datetime.now(MOEX_TZ)


def to_moex_datetime(dt: datetime | pd.Timestamp | str) -> datetime:
    """Одна метка → aware datetime Europe/Moscow."""
    t = pd.Timestamp(pd.to_datetime(dt))
    if t.tzinfo is None:
        t = t.tz_localize(MOEX_TZ)
    else:
        t = t.tz_convert(MOEX_TZ)
    return t.to_pydatetime()


def moex_age_seconds(last_ts_naive: str | pd.Timestamp) -> float:
    last = pd.to_datetime(last_ts_naive)
    if last.tzinfo is None:
        last = last.tz_localize(MOEX_TZ)
    else:
        last = last.tz_convert(MOEX_TZ)
    return max(0.0, (moex_now() - last).total_seconds())
