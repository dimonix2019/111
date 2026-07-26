"""Settle 90s + decision_bars freeze + as_live overlay."""

from __future__ import annotations

from live import constants
from live import store
from replay import replay_db


def test_monitor_bar_settle_is_90():
    assert constants.MONITOR_BAR_SETTLE_SEC == 90.0


def test_upsert_decision_bar_and_timestamps(tmp_path, monkeypatch):
    db = tmp_path / "live_trading.db"
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)

    store.upsert_decision_bar(
        bar_ts="2026-07-24 10:00",
        open_=2.1,
        high=2.1,
        low=2.1,
        close=2.1,
        spread_percent=2.1,
        z_score=-1.55,
        signal="ENTER_LONG",
        tatn_close=450.0,
        tatnp_close=440.0,
    )
    rows = store.get_decision_bars()
    assert len(rows) == 1
    assert rows[0]["bar_ts"] == "2026-07-24 10:00"
    assert abs(float(rows[0]["z_score"]) - (-1.55)) < 1e-9
    assert "2026-07-24 10:00" in store.get_decision_bar_timestamps()

    store.upsert_decision_bar_from_series(
        {
            "tradeDate": "2026-07-24 10:15",
            "spreadPercent": 2.2,
            "zScore": -1.7,
            "tatnClose": 451.0,
            "tatnpClose": 441.0,
        },
        signal="NONE",
    )
    assert len(store.get_decision_bars()) == 2


def test_upsert_bars_skips_frozen(tmp_path, monkeypatch):
    live_db = tmp_path / "live_trading.db"
    replay_path = tmp_path / "replay_m15.db"
    monkeypatch.setattr(store, "DB_PATH", live_db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)
    monkeypatch.setattr(replay_db, "DB_PATH", replay_path)
    monkeypatch.setattr(replay_db, "DATA_DIR", tmp_path)
    monkeypatch.setattr(replay_db, "_SCHEMA_READY", False)

    store.upsert_decision_bar(
        bar_ts="2026-07-24 10:00",
        spread_percent=2.0,
        z_score=-1.5,
        signal="NONE",
        tatn_close=100.0,
        tatnp_close=98.0,
    )

    conn = replay_db._connect()
    try:
        # Seed frozen bar
        n = replay_db._upsert_bars(
            conn,
            [
                {
                    "timestampMs": 1_000,
                    "tradeDate": "2026-07-24 10:00",
                    "zScore": -1.5,
                    "spreadPercent": 2.0,
                    "tatnClose": 100.0,
                    "tatnpClose": 98.0,
                }
            ],
            "seed",
        )
        assert n == 1
        # ISS would rewrite OHLC/Z — must be ignored for frozen ts
        replay_db._upsert_bars(
            conn,
            [
                {
                    "timestampMs": 1_000,
                    "tradeDate": "2026-07-24 10:00",
                    "zScore": -9.9,
                    "spreadPercent": 9.9,
                    "tatnClose": 1.0,
                    "tatnpClose": 1.0,
                },
                {
                    "timestampMs": 2_000,
                    "tradeDate": "2026-07-24 10:15",
                    "zScore": 0.1,
                    "spreadPercent": 2.05,
                    "tatnClose": 101.0,
                    "tatnpClose": 99.0,
                },
            ],
            "iss",
        )
        row = conn.execute(
            "SELECT z_score, spread_percent, tatn_close FROM m15_bars WHERE timestamp_ms = 1000"
        ).fetchone()
        assert abs(float(row["z_score"]) - (-1.5)) < 1e-9
        assert abs(float(row["spread_percent"]) - 2.0) < 1e-9
        assert abs(float(row["tatn_close"]) - 100.0) < 1e-9
        row2 = conn.execute(
            "SELECT z_score FROM m15_bars WHERE timestamp_ms = 2000"
        ).fetchone()
        assert abs(float(row2["z_score"]) - 0.1) < 1e-9
    finally:
        conn.close()


def test_overlay_decision_bars_meta(tmp_path, monkeypatch):
    db = tmp_path / "live_trading.db"
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)

    store.upsert_decision_bar(
        bar_ts="2026-07-24 10:00",
        spread_percent=2.5,
        z_score=-1.61,
        signal="ENTER_LONG",
        tatn_close=455.0,
        tatnp_close=444.0,
    )
    bars = [
        {
            "tradeDate": "2026-07-24 09:45",
            "zScore": 0.0,
            "spreadPercent": 2.0,
            "tatnClose": 450.0,
            "tatnpClose": 441.0,
        },
        {
            "tradeDate": "2026-07-24 10:00",
            "zScore": -0.5,
            "spreadPercent": 2.1,
            "tatnClose": 451.0,
            "tatnpClose": 442.0,
        },
    ]
    meta = store.overlay_decision_bars_on_series(bars)
    assert meta["as_live"] is True
    assert meta["locked_count"] == 1
    assert meta["bars_count"] == 2
    assert abs(meta["coverage"] - 0.5) < 1e-9
    assert meta["locked_from"] == "2026-07-24 10:00"
    assert abs(bars[1]["zScore"] - (-1.61)) < 1e-9
    assert abs(bars[1]["spreadPercent"] - 2.5) < 1e-9
    assert abs(bars[1]["tatnClose"] - 455.0) < 1e-9
    # Unlocked bar unchanged
    assert abs(bars[0]["zScore"] - 0.0) < 1e-9


def test_backfill_decision_z_from_parity(tmp_path, monkeypatch):
    db = tmp_path / "live_trading.db"
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)

    store.insert_parity_edge(
        {
            "bar_ts": "2026-07-24 11:00",
            "bar_ms": 123,
            "signal": "ENTER_SHORT",
            "z_score": 1.72,
            "entry_z": 1.6,
            "exit_z": 1.3,
            "trade_id": None,
            "check_after_ms": 0,
        }
    )
    n = store.backfill_decision_z_from_parity_edges()
    assert n >= 1
    rows = store.get_decision_bars(from_ts="2026-07-24 11:00", to_ts="2026-07-24 11:00")
    assert len(rows) == 1
    assert abs(float(rows[0]["z_score"]) - 1.72) < 1e-9
    assert rows[0]["open"] is None  # Z-only backfill


def test_backfill_decision_z_from_live_events_kills_phantom_short(tmp_path, monkeypatch):
    """Tip heartbeats below ±1.6 replace final CSV cross at 17:45; unconfirmed tip spike → liveSignal NONE."""
    db = tmp_path / "live_trading.db"
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)

    # Real freeze later in the day must not be overwritten.
    store.upsert_decision_bar(
        bar_ts="2026-07-24 20:00",
        z_score=1.709,
        signal="NONE",
        spread_percent=6.47,
    )

    events = [
        {"message": "Монитор OK · 2026-07-24 17:30 · Z=1.20"},
        {"message": "Монитор OK · 2026-07-24 17:45 · Z=1.13"},
        {"message": "Монитор OK · 2026-07-24 17:45 · Z=1.25"},  # last tip wins
        {"message": "Монитор OK · 2026-07-24 18:00 · Z=1.43"},
        {"message": "Монитор OK · 2026-07-24 18:15 · Z=1.85"},  # tip spike, no live signal
        {"message": "Монитор OK · 2026-07-24 20:00 · Z=1.49"},  # ignored: freeze exists
    ]
    monkeypatch.setattr(store, "list_events", lambda limit=500: list(reversed(events)))

    n = store.backfill_decision_z_from_live_events(entry_z=1.6, exit_z=1.3)
    assert n >= 4

    rows = {
        r["bar_ts"]: r
        for r in store.get_decision_bars(
            from_ts="2026-07-24 17:30", to_ts="2026-07-24 20:00"
        )
    }
    assert abs(float(rows["2026-07-24 17:45"]["z_score"]) - 1.25) < 1e-9
    assert rows["2026-07-24 17:45"]["signal"] == "NONE"
    # Unconfirmed tip spike keeps raw Z but signal NONE (sim gates on liveSignal)
    assert abs(float(rows["2026-07-24 18:15"]["z_score"]) - 1.85) < 1e-9
    assert rows["2026-07-24 18:15"]["signal"] == "NONE"
    # Existing freeze preserved
    assert abs(float(rows["2026-07-24 20:00"]["z_score"]) - 1.709) < 1e-9

    bars = [
        {
            "tradeDate": "2026-07-24 17:30",
            "timestampMs": 1_000_000,
            "zScore": 1.371,
            "spreadPercent": 6.2,
        },
        {
            "tradeDate": "2026-07-24 17:45",
            "timestampMs": 1_000_000 + 15 * 60_000,
            "zScore": 1.658,
            "spreadPercent": 6.4,
        },
        {
            "tradeDate": "2026-07-24 18:00",
            "timestampMs": 1_000_000 + 30 * 60_000,
            "zScore": 1.771,
            "spreadPercent": 6.5,
        },
        {
            "tradeDate": "2026-07-24 18:15",
            "timestampMs": 1_000_000 + 45 * 60_000,
            "zScore": 1.721,
            "spreadPercent": 6.47,
        },
        {
            "tradeDate": "2026-07-24 20:00",
            "timestampMs": 1_000_000 + 150 * 60_000,
            "zScore": 9.9,
            "spreadPercent": 6.47,
        },
    ]
    store.overlay_decision_bars_on_series(bars)
    assert abs(bars[1]["zScore"] - 1.25) < 1e-9
    assert bars[1]["liveSignal"] == "NONE"
    assert abs(bars[3]["zScore"] - 1.85) < 1e-9
    assert bars[3]["liveSignal"] == "NONE"
    assert bars[4]["liveSignal"] == "NONE"

    from live.parity import collect_sim_edges

    edges = collect_sim_edges(bars, 1.6, 1.3)
    assert not any(e["bar_ts"].startswith("2026-07-24 17:45") for e in edges)
    assert not any(e["signal"] == "ENTER_SHORT" for e in edges)
