"""Отложенная сверка AUTO-сделок Live с бар-симуляцией (Testing ≈ Prod)."""

from __future__ import annotations

import logging
import time
from pathlib import Path
from typing import Any

from live import store
from live.signals import Position, Signal, determine_z_signal, is_consecutive_m15

log = logging.getLogger(__name__)

DEFAULT_PARITY_DELAY_MIN = 45
TOLERANCE_BARS = 1


def _signal_to_sim_name(signal: str) -> str:
    return {
        "ENTER_LONG": "ENTER_LONG",
        "ENTER_SHORT": "ENTER_SHORT",
        "EXIT_LONG": "EXIT_LONG",
        "EXIT_SHORT": "EXIT_SHORT",
    }.get(signal, signal)


def collect_sim_edges(
    bars: list[dict[str, Any]],
    entry: float,
    exit_z: float,
) -> list[dict[str, Any]]:
    """Тот же edge-проход, что live monitor (consecutive 15м + determine_z_signal)."""
    pos = Position.FLAT
    edges: list[dict[str, Any]] = []
    for i in range(1, len(bars)):
        prev, cur = bars[i - 1], bars[i]
        prev_ms = int(prev.get("timestampMs") or 0)
        cur_ms = int(cur.get("timestampMs") or 0)
        if not is_consecutive_m15(prev_ms, cur_ms):
            continue
        sig = determine_z_signal(
            float(prev.get("zScore") or 0),
            float(cur.get("zScore") or 0),
            pos,
            entry,
            exit_z,
        )
        if sig == Signal.NONE:
            continue
        edges.append(
            {
                "bar_ts": str(cur.get("tradeDate") or "")[:16],
                "bar_ms": cur_ms,
                "signal": sig.value,
                "z": float(cur.get("zScore") or 0),
            }
        )
        if sig == Signal.ENTER_LONG:
            pos = Position.LONG
        elif sig == Signal.ENTER_SHORT:
            pos = Position.SHORT
        elif sig in (Signal.EXIT_LONG, Signal.EXIT_SHORT):
            pos = Position.FLAT
    return edges


def _bar_delta_min(a: str, b: str) -> int:
    from datetime import datetime
    from zoneinfo import ZoneInfo

    msk = ZoneInfo("Europe/Moscow")
    fmt = "%Y-%m-%d %H:%M"
    da = datetime.strptime(a[:16], fmt).replace(tzinfo=msk)
    db = datetime.strptime(b[:16], fmt).replace(tzinfo=msk)
    return int((da - db).total_seconds() // 60)


def find_matching_sim_edge(
    live_bar_ts: str,
    live_signal: str,
    sim_edges: list[dict[str, Any]],
    *,
    tolerance_bars: int = TOLERANCE_BARS,
) -> dict[str, Any] | None:
    want = _signal_to_sim_name(live_signal)
    window = 15 * tolerance_bars
    candidates = [
        e
        for e in sim_edges
        if e["signal"] == want and abs(_bar_delta_min(e["bar_ts"], live_bar_ts[:16])) <= window
    ]
    if not candidates:
        return None
    return min(candidates, key=lambda e: abs(_bar_delta_min(e["bar_ts"], live_bar_ts[:16])))


def schedule_parity_for_auto(
    *,
    bar_ts: str,
    bar_ms: int,
    signal: str,
    z_score: float,
    entry_z: float,
    exit_z: float,
    trade_id: int | None = None,
    delay_min: int | None = None,
) -> int:
    delay = delay_min
    if delay is None:
        delay = int(store.get_setting("parity_delay_min", str(DEFAULT_PARITY_DELAY_MIN)) or DEFAULT_PARITY_DELAY_MIN)
    delay = max(5, min(180, delay))
    check_after = int(time.time() * 1000) + delay * 60_000
    edge_id = store.insert_parity_edge(
        {
            "bar_ts": bar_ts[:16],
            "bar_ms": bar_ms,
            "signal": signal,
            "z_score": z_score,
            "entry_z": entry_z,
            "exit_z": exit_z,
            "trade_id": trade_id,
            "check_after_ms": check_after,
        }
    )
    store.log_event(
        f"Parity: запланирована сверка #{edge_id} {signal} @ {bar_ts[:16]} через {delay} мин",
        "parity",
    )
    return edge_id


def run_parity_check(edge: dict[str, Any]) -> dict[str, Any]:
    """Сверить один live edge с симом на тех же порогах и rolling Z барах."""
    entry = float(edge.get("entry_z") or 1.3)
    exit_z = float(edge.get("exit_z") or 1.2)
    live_ts = str(edge.get("bar_ts") or "")
    live_sig = str(edge.get("signal") or "")

    try:
        from replay.replay_db import ensure_replay_bars, load_bars_from_db

        bars = load_bars_from_db()
        if len(bars) < 2:
            data_dir = Path(__file__).resolve().parent.parent / "data"
            payload = ensure_replay_bars(
                data_dir / "m15_tatn_255d.csv",
                "m15_tatn_255d.csv",
                online=True,
                wait_sync=True,
            )
            bars = payload.get("bars") or []
        if len(bars) < 2:
            raise RuntimeError("мало баров для сима")
    except Exception as exc:
        result = {
            "ok": False,
            "status": "error",
            "detail": f"не удалось загрузить бары: {exc}",
            "live_bar": live_ts,
            "live_signal": live_sig,
        }
        store.update_parity_edge(int(edge["id"]), result)
        store.log_event(f"Parity FAIL #{edge['id']}: {result['detail']}", "parity")
        return result

    sim_edges = collect_sim_edges(bars, entry, exit_z)
    match = find_matching_sim_edge(live_ts, live_sig, sim_edges)
    last_bar = str(bars[-1].get("tradeDate") or "") if bars else None
    if match:
        result = {
            "ok": True,
            "status": "matched",
            "detail": f"sim {match['signal']} @ {match['bar_ts']} Z={match['z']:.2f}",
            "live_bar": live_ts,
            "live_signal": live_sig,
            "sim_bar": match["bar_ts"],
            "sim_z": match["z"],
            "entry_z": entry,
            "exit_z": exit_z,
            "last_bar": last_bar,
        }
        store.update_parity_edge(int(edge["id"]), result)
        store.log_event(
            f"Parity OK #{edge['id']}: {live_sig} @ {live_ts} ≈ sim {match['bar_ts']}",
            "parity",
        )
        return result

    nearby = [
        e
        for e in sim_edges
        if abs(_bar_delta_min(e["bar_ts"], live_ts[:16])) <= 15 * 8
    ][:8]
    result = {
        "ok": False,
        "status": "missing",
        "detail": (
            f"в Testing нет {live_sig} около {live_ts} "
            f"(пороги ±{entry:.2f}/±{exit_z:.2f}, rolling Z)"
        ),
        "live_bar": live_ts,
        "live_signal": live_sig,
        "nearby_sim": nearby,
        "entry_z": entry,
        "exit_z": exit_z,
        "last_bar": last_bar,
    }
    store.update_parity_edge(int(edge["id"]), result)
    store.log_event(f"Parity MISSING #{edge['id']}: {result['detail']}", "parity")
    log.warning("Parity missing: %s", result)
    return result


def process_due_parity_checks() -> list[dict[str, Any]]:
    due = store.list_due_parity_edges()
    out = []
    for edge in due:
        try:
            out.append(run_parity_check(edge))
        except Exception as exc:
            log.exception("parity check failed for #%s", edge.get("id"))
            store.log_event(f"Parity error #{edge.get('id')}: {exc}", "parity")
    return out
