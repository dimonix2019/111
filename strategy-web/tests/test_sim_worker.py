"""Async tip1m sim worker — long windows off uvicorn hot path."""

from __future__ import annotations

import threading
import time

import pytest
from fastapi.testclient import TestClient

from replay import replay_app, sim_worker, tip_touch
from replay.replay_app import app


def _fake_sim_result(**_kwargs) -> dict:
    return {
        "trades": [],
        "summary": {"trades": 0},
        "params": {},
        "meta": {"simSec": 0.01, "simCacheHit": False},
    }


def test_should_run_async_threshold():
    assert sim_worker.should_run_async("m15_tatn_255d.csv", "2026-01-01", "2026-03-01") is False
    assert sim_worker.should_run_async("m15_tatn_255d.csv", "2024-01-01", "2026-01-01") is True
    assert sim_worker.sim_span_days("m15_tatn_1095d.csv", None, None) == 1095


def test_sync_short_window_returns_trades_directly(monkeypatch):
    monkeypatch.setattr(tip_touch, "sim_tip1m", lambda **k: _fake_sim_result(**k))
    with TestClient(app) as client:
        r = client.post(
            "/api/sim/tip1m",
            json={
                "csv": "m15_tatn_255d.csv",
                "start": "2026-08-01",
                "end": "2026-08-15",
            },
        )
    assert r.status_code == 200
    data = r.json()
    assert not data.get("async")
    assert "trades" in data


def test_async_long_window_submit_and_poll(monkeypatch):
    started = threading.Event()

    def slow_sim(**kwargs):
        started.set()
        time.sleep(0.08)
        return _fake_sim_result(**kwargs)

    monkeypatch.setattr(tip_touch, "sim_tip1m", slow_sim)

    with TestClient(app) as client:
        t0 = time.time()
        r = client.post(
            "/api/sim/tip1m",
            json={
                "csv": "m15_tatn_1095d.csv",
                "start": "2023-01-01",
                "end": "2025-12-31",
            },
        )
        post_sec = time.time() - t0
        assert r.status_code == 200
        data = r.json()
        assert data.get("async") is True
        assert data.get("status") == "pending"
        job_id = data.get("job_id")
        assert job_id
        assert post_sec < 0.15, "POST must return immediately, not wait for sim"

        assert started.wait(3.0), "worker should start sim"

        result = None
        for _ in range(120):
            sr = client.get(f"/api/sim/tip1m/status/{job_id}")
            assert sr.status_code == 200
            st = sr.json()
            assert st["job_id"] == job_id
            if st["status"] == "done":
                result = st.get("result")
                break
            if st["status"] == "error":
                pytest.fail(st.get("error"))
            time.sleep(0.02)
        assert result is not None
        assert "trades" in result

        missing = client.get("/api/sim/tip1m/status/no-such-job")
        assert missing.status_code == 404


def test_api_sim_tip1m_parses_weekend_flag(monkeypatch):
    calls: list[dict] = []

    def _fake_sim(**kwargs):
        calls.append(kwargs)
        return _fake_sim_result(**kwargs)

    monkeypatch.setattr(tip_touch, "sim_tip1m", _fake_sim)
    replay_app.api_sim_tip1m({"csv": "m15_tatn_255d.csv", "start": "2026-08-01", "end": "2026-08-07"})
    replay_app.api_sim_tip1m(
        {
            "csv": "m15_tatn_255d.csv",
            "start": "2026-08-01",
            "end": "2026-08-07",
            "weekend_trading": "true",
        }
    )
    assert calls[0]["weekend_trading"] is False
    assert calls[1]["weekend_trading"] is True
