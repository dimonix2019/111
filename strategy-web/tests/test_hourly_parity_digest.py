"""Hourly Test↔Prod parity digest (durable watchdog path)."""

from __future__ import annotations

import json

from live import store
from live.parity import (
    build_hourly_parity_digest,
    maybe_run_hourly_parity_digest,
    write_hourly_parity_digest,
)


def _seed_summary(monkeypatch, *, missing_ids, hard, soft, open_pnl=None):
    edges = [
        {
            "id": i,
            "status": "missing" if i in missing_ids else "matched",
            "signal": "ENTER_LONG",
            "bar_ts": "2026-07-20 16:45",
        }
        for i in range(1, 4)
    ]
    for mid in missing_ids:
        if mid not in {e["id"] for e in edges}:
            edges.insert(
                0,
                {
                    "id": mid,
                    "status": "missing",
                    "signal": "ENTER_LONG",
                    "bar_ts": "2026-07-20 16:45",
                },
            )
    latest = edges[0]
    summary = {
        "pending": 0,
        "matched": sum(1 for e in edges if e["status"] == "matched"),
        "missing": sum(1 for e in edges if e["status"] == "missing"),
        "latest": latest,
        "edges": edges,
        "open_pnl": open_pnl
        or {"ok": True, "status": "flat", "kind": "open_pnl", "detail": "нет открытой позиции"},
        "trades": {
            "ok": hard == 0,
            "hard_mismatches": hard,
            "soft_mismatches": soft,
        },
        "trades_hard_mismatches": hard,
        "trades_ok": hard == 0,
    }
    monkeypatch.setattr(store, "parity_summary", lambda: summary)
    monkeypatch.setattr(store, "get_open_trade", lambda: None)


def test_build_digest_ok_when_no_new_issues(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "DB_PATH", tmp_path / "t.db")
    monkeypatch.setattr(store, "_SCHEMA_READY", False)
    _seed_summary(monkeypatch, missing_ids=[2], hard=15, soft=5)
    digest = build_hourly_parity_digest(
        prev_snapshot={"missing_ids": [2], "hard_mismatches": 15, "soft_mismatches": 5}
    )
    assert digest["status"] == "OK"
    assert digest["position"] == "FLAT"
    assert digest["new_missing_ids"] == []
    assert digest["new_hard_delta"] == 0
    assert "NEW: none" in digest["line"]
    assert "OK" in digest["line"]
    assert "chat-loop=optional" in digest["line"]


def test_build_digest_alert_on_new_missing_and_hard(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    _seed_summary(monkeypatch, missing_ids=[2, 9], hard=17, soft=5)
    digest = build_hourly_parity_digest(
        prev_snapshot={"missing_ids": [2], "hard_mismatches": 15}
    )
    assert digest["status"] == "ALERT"
    assert digest["new_missing_ids"] == [9]
    assert digest["new_hard_delta"] == 2
    assert "missing+#9" in digest["line"]
    assert "hard+2" in digest["line"]


def test_write_hourly_digest_files_and_interval(tmp_path, monkeypatch):
    db = tmp_path / "hourly.db"
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)
    _seed_summary(monkeypatch, missing_ids=[], hard=0, soft=0)

    calls = {"n": 0}

    def _fake_checks():
        calls["n"] += 1
        return []

    monkeypatch.setattr("live.parity.process_due_parity_checks", _fake_checks)

    d1 = maybe_run_hourly_parity_digest(force=True, run_checks=True)
    assert d1 is not None
    assert calls["n"] == 1
    log_path = tmp_path / "parity-hourly.log"
    latest = tmp_path / "parity-hourly-latest.json"
    assert log_path.is_file()
    assert "FLAT" in log_path.read_text(encoding="utf-8")
    assert latest.is_file()
    payload = json.loads(latest.read_text(encoding="utf-8"))
    assert payload["status"] == "OK"

    # Within the hour — skip
    skipped = maybe_run_hourly_parity_digest(force=False, run_checks=True)
    assert skipped is None
    assert calls["n"] == 1

    # Force still writes
    d2 = maybe_run_hourly_parity_digest(force=True, run_checks=False)
    assert d2 is not None
    assert calls["n"] == 1
    assert len(log_path.read_text(encoding="utf-8").strip().splitlines()) == 2


def test_write_digest_persists_snapshot(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "DB_PATH", tmp_path / "s.db")
    monkeypatch.setattr(store, "_SCHEMA_READY", False)
    _seed_summary(monkeypatch, missing_ids=[2], hard=3, soft=1)
    digest = build_hourly_parity_digest(prev_snapshot={})
    write_hourly_parity_digest(digest)
    raw = store.get_setting("parity_hourly_snapshot_json") or ""
    snap = json.loads(raw)
    assert snap["missing_ids"] == [2]
    assert snap["hard_mismatches"] == 3
    assert int(store.get_setting("parity_hourly_last_ms") or "0") > 0
