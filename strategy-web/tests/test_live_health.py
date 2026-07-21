"""Watchdog probe: health_live stale / age semantics."""

from __future__ import annotations

import time

from live import engine
from live.constants import MONITOR_STALE_SEC


def test_health_live_shape():
    h = engine.health_live()
    assert h["status"] == "ok"
    assert "monitor_wanted" in h
    assert "monitor_alive" in h
    assert "stale" in h
    assert h["stale_after_sec"] == MONITOR_STALE_SEC


def test_health_live_stale_when_tick_old(monkeypatch):
    monkeypatch.setattr(engine, "_monitor_thread", None)
    monkeypatch.setattr(
        engine,
        "_last_status",
        {**engine._last_status, "last_tick_ms": int(time.time() * 1000) - 250_000},
    )
    monkeypatch.setattr(engine.store, "get_settings_bundle", lambda: {"monitor_running": True})
    h = engine.health_live()
    assert h["monitor_alive"] is False
    assert h["stale"] is True
    assert h["last_tick_age_sec"] is not None
    assert h["last_tick_age_sec"] >= 240


def test_health_live_not_stale_when_monitor_off(monkeypatch):
    monkeypatch.setattr(engine, "_monitor_thread", None)
    monkeypatch.setattr(engine.store, "get_settings_bundle", lambda: {"monitor_running": False})
    h = engine.health_live()
    assert h["monitor_wanted"] is False
    assert h["stale"] is False
