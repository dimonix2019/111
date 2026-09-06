"""Open plaque survives empty RAM after watchdog restart: SQLite is source of truth."""

from __future__ import annotations

from live import engine, store
from live.trade_api import (
    _remember_open_marks,
    cheap_open_marks,
    desk_open_from_store,
    trade_open,
)


def _seed_open(tmp_path, monkeypatch, *, lots: int = 246) -> int:
    db = tmp_path / "live_restore.db"
    monkeypatch.setattr(store, "DB_PATH", db)
    monkeypatch.setattr(store, "DATA_DIR", tmp_path)
    monkeypatch.setattr(store, "_SCHEMA_READY", False)
    monkeypatch.setattr(engine, "_last_status", {
        "running": False,
        "last_tick_ms": 0,
        "last_message": "",
        "last_z": None,
        "last_bar": None,
    })
    monkeypatch.setattr(engine, "_monitor_started_ms", 0)
    return store.insert_open_trade(
        {
            "mode": "prod",
            "account_id": "acc",
            "direction": "LONG",
            "entry_signal": "ENTER_LONG",
            "quantity_lots": lots,
            "entry_time": "2026-09-01 21:00:00",
            "entry_z": 0.97,
            "entry_spread": 3.35,
            "entry_tatn": 500.0,
            "entry_tatnp": 484.0,
            "execution_notional_rub": 120000,
            "source": "AUTO",
            "legs": [],
        }
    )


def test_desk_open_from_store_when_ram_empty(tmp_path, monkeypatch):
    tid = _seed_open(tmp_path, monkeypatch, lots=246)
    packed = desk_open_from_store()
    assert packed["position"] == "LONG"
    open_e = packed["open"]
    assert open_e is not None
    assert open_e["id"] == tid
    assert open_e["direction"] == "LONG"
    assert open_e["quantity_lots"] == 246
    assert open_e["entry_time"] == "2026-09-01 21:00:00"
    assert open_e["source"] == "AUTO"


def test_trade_open_endpoint_when_ram_empty(tmp_path, monkeypatch):
    tid = _seed_open(tmp_path, monkeypatch, lots=246)
    out = trade_open()
    assert out["ok"] is True
    assert out["from_store"] is True
    assert out["position"] == "LONG"
    assert out["open"]["id"] == tid
    assert out["open"]["quantity_lots"] == 246
    assert out["open"]["direction"] == "LONG"


def test_trade_open_mtm_not_zero_when_spread_moved(tmp_path, monkeypatch):
    tid = _seed_open(tmp_path, monkeypatch, lots=246)
    monkeypatch.setattr(
        "live.trade_api.cheap_open_marks",
        lambda: {
            "spread": 3.21,
            "z": 0.9,
            "trade_date": "2026-09-02 09:50:00",
            "tatn": 501.0,
            "tatnp": 485.5,
        },
    )
    out = trade_open()
    assert out["open"]["id"] == tid
    mark = out["open"]["mark"]
    assert mark["spread_now"] == 3.21
    assert mark["unrealized_pnl_rub"] is not None
    assert mark["unrealized_pnl_rub"] != 0
    assert mark["net_approx_rub"] is not None


def test_cheap_open_marks_from_remembered(monkeypatch):
    monkeypatch.setattr(
        "live.dealer_quotes.peek_cached_dealer_quotes",
        lambda: None,
        raising=False,
    )
    from live import trade_api as ta

    with ta._DESK_LOCK:
        ta._DESK_CACHE["payload"] = None
    ta._LAST_MARKS.clear()
    _remember_open_marks(spread=3.21, tatn=500.0, tatnp=484.0, trade_date="2026-09-02 10:00:00")
    marks = cheap_open_marks()
    assert marks.get("spread") == 3.21
    assert marks.get("tatn") == 500.0


def test_cheap_open_marks_from_desk_cache(monkeypatch):
    monkeypatch.setattr(
        "live.dealer_quotes.peek_cached_dealer_quotes",
        lambda: None,
        raising=False,
    )
    from live import trade_api as ta

    with ta._DESK_LOCK:
        ta._DESK_CACHE["payload"] = {
            "summary": {
                "spread": 3.21,
                "z": 0.5,
                "tatn": 500.0,
                "tatnp": 484.0,
                "trade_date": "2026-09-02 10:00:00",
            }
        }
    marks = cheap_open_marks()
    assert marks.get("spread") == 3.21
    assert marks.get("tatn") == 500.0


def test_restore_open_from_store_fills_status(tmp_path, monkeypatch):
    tid = _seed_open(tmp_path, monkeypatch, lots=246)
    out = engine.restore_open_from_store(announce=False)
    assert out["ok"] is True
    assert out["position"] == "LONG"
    assert out["open"]["id"] == tid
    assert engine._last_status.get("open_id") == tid
    assert engine._last_status.get("open_direction") == "LONG"
    assert engine.current_position().value == "LONG"
    st = engine.monitor_status()
    assert st["open"] is not None
    assert st["open"]["id"] == tid
    assert st["open"]["quantity_lots"] == 246


def test_trade_desk_request_does_not_build_markets_sync():
    import inspect

    from live import trade_api

    src = inspect.getsource(trade_api.trade_desk)
    assert "build_markets_snapshot" not in src
    assert "peek_markets_snapshot" in src
    assert "get_desk_metric_dists(allow_build=False)" in src
