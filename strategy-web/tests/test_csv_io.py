"""Атомарная запись CSV и устойчивое чтение на Windows."""

from __future__ import annotations

import threading
import time
from pathlib import Path

import pandas as pd
import pytest

from csv_io import atomic_write_csv, read_csv_tail_line, safe_read_csv


def test_atomic_write_csv_roundtrip(tmp_path: Path):
    p = tmp_path / "m15_tatn_255d.csv"
    df = pd.DataFrame(
        {
            "timestamp": ["2026-09-02 16:45:00"],
            "z_score": [0.1],
            "spread_percent": [5.0],
            "tatn_close": [600.0],
            "tatnp_close": [570.0],
        }
    )
    atomic_write_csv(df, p)
    out = safe_read_csv(p)
    assert len(out) == 1
    assert out.iloc[0]["timestamp"] == "2026-09-02 16:45:00"


def test_read_csv_tail_line(tmp_path: Path):
    p = tmp_path / "m15_tatn_255d.csv"
    p.write_text(
        "timestamp,z_score,spread_percent,tatn_close,tatnp_close\n"
        "2026-09-02 16:30:00,0.1,5.0,600,570\n"
        "2026-09-02 16:45:00,0.2,5.1,601,571\n",
        encoding="utf-8",
    )
    tail = read_csv_tail_line(p)
    assert tail is not None
    assert tail.startswith("2026-09-02 16:45:00")


def test_atomic_write_survives_concurrent_read(tmp_path: Path):
    p = tmp_path / "m15_tatn_255d.csv"
    base = pd.DataFrame(
        {
            "timestamp": ["2026-09-02 16:30:00"],
            "z_score": [0.1],
            "spread_percent": [5.0],
            "tatn_close": [600.0],
            "tatnp_close": [570.0],
        }
    )
    atomic_write_csv(base, p)
    stop = threading.Event()
    errors: list[Exception] = []

    def _reader() -> None:
        while not stop.is_set():
            try:
                safe_read_csv(p)
                read_csv_tail_line(p)
            except Exception as exc:
                errors.append(exc)
                return
            time.sleep(0.005)

    t = threading.Thread(target=_reader, daemon=True)
    t.start()
    try:
        for i in range(8):
            nxt = base.copy()
            nxt.loc[0, "z_score"] = float(i)
            atomic_write_csv(nxt, p)
            time.sleep(0.01)
    finally:
        stop.set()
        t.join(timeout=2)
    assert not errors
    assert float(safe_read_csv(p).iloc[0]["z_score"]) == 7.0
