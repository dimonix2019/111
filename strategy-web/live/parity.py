"""Отложенная сверка AUTO-сделок Live с tip1m-симом (Testing ≈ Prod)."""

from __future__ import annotations

import json
import logging
import time
from pathlib import Path
from typing import Any

from live import store
from live.signals import (
    Position,
    Signal,
    determine_z_signal,
    is_consecutive_m15,
    is_moex_equity_session_bar,
)
from live.tip_touch_signals import collect_tip1m_sim_edges, load_tip_bars_for_parity

log = logging.getLogger(__name__)

DEFAULT_PARITY_DELAY_MIN = 15
# Match window in minutes (tip1m ±few min; also covers legacy M15 ±2 slots).
TOLERANCE_BARS = 2  # legacy name kept for callers
TOLERANCE_MIN = 30


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
    """Tip1m edge-проход (Prod Mode B). ``bars`` may be tip bars or M15 (auto-detect)."""
    if not bars:
        return []
    # Heuristic: tip series has ~1m steps; M15 has 15m.
    if len(bars) >= 2:
        d0 = int(bars[1].get("timestampMs") or 0) - int(bars[0].get("timestampMs") or 0)
        if 0 < d0 <= 90_000:
            return collect_tip1m_sim_edges(bars, entry, exit_z, respect_live_signal=True)
    # Legacy M15 path (tests / old callers)
    pos = Position.FLAT
    edges: list[dict[str, Any]] = []
    for i in range(1, len(bars)):
        prev, cur = bars[i - 1], bars[i]
        prev_ms = int(prev.get("timestampMs") or 0)
        cur_ms = int(cur.get("timestampMs") or 0)
        if not is_consecutive_m15(prev_ms, cur_ms):
            continue
        cur_td = str(cur.get("tradeDate") or "")
        if not is_moex_equity_session_bar(cur_td):
            continue
        sig = determine_z_signal(
            float(prev.get("zScore") or 0),
            float(cur.get("zScore") or 0),
            pos,
            entry,
            exit_z,
        )
        live_sig = str(cur.get("liveSignal") or "").upper()
        if live_sig == Signal.NONE.value and sig in (
            Signal.ENTER_LONG,
            Signal.ENTER_SHORT,
        ):
            continue
        if sig == Signal.NONE:
            continue
        edges.append(
            {
                "bar_ts": cur_td[:16],
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
    tolerance_min: int | None = None,
) -> dict[str, Any] | None:
    want = _signal_to_sim_name(live_signal)
    window = int(tolerance_min) if tolerance_min is not None else TOLERANCE_MIN
    # Legacy callers passed tolerance_bars as M15 slots — honour if explicitly > default path.
    if tolerance_min is None and tolerance_bars != TOLERANCE_BARS:
        window = 15 * int(tolerance_bars)
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
    """Сверить один live tip1m edge с симом на тех же порогах."""
    entry = float(edge.get("entry_z") or 1.3)
    exit_z = float(edge.get("exit_z") or 1.2)
    live_ts = str(edge.get("bar_ts") or "")
    live_sig = str(edge.get("signal") or "")

    try:
        from replay.replay_db import ensure_replay_bars, load_bars_from_db

        m15 = load_bars_from_db()
        if len(m15) < 2:
            data_dir = Path(__file__).resolve().parent.parent / "data"
            payload = ensure_replay_bars(
                data_dir / "m15_tatn_255d.csv",
                "m15_tatn_255d.csv",
                online=True,
                wait_sync=True,
            )
            m15 = payload.get("bars") or []
        if len(m15) < 2:
            raise RuntimeError("мало баров M15 для tip1m сима")
        store.overlay_decision_bars_on_series(m15)
        tips = load_tip_bars_for_parity(m15)
        if len(tips) < 2:
            raise RuntimeError("мало 1м tip для сима")
        store.overlay_decision_bars_on_series(tips)
        bars = tips
    except Exception as exc:
        result = {
            "ok": False,
            "status": "error",
            "detail": f"не удалось загрузить tip1m: {exc}",
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
            "detail": f"sim tip1m {match['signal']} @ {match['bar_ts']} Z={match['z']:.2f}",
            "live_bar": live_ts,
            "live_signal": live_sig,
            "sim_bar": match["bar_ts"],
            "sim_z": match["z"],
            "entry_z": entry,
            "exit_z": exit_z,
            "last_bar": last_bar,
            "signal_mode": "tip1m",
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
        if abs(_bar_delta_min(e["bar_ts"], live_ts[:16])) <= 60
    ][:8]
    result = {
        "ok": False,
        "status": "missing",
        "detail": (
            f"в Testing tip1m нет {live_sig} около {live_ts} "
            f"(пороги ±{entry:.2f}/±{exit_z:.2f})"
        ),
        "live_bar": live_ts,
        "live_signal": live_sig,
        "nearby_sim": nearby,
        "entry_z": entry,
        "exit_z": exit_z,
        "last_bar": last_bar,
        "signal_mode": "tip1m",
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
    try:
        out.append(check_open_pnl_parity())
    except Exception as exc:
        log.exception("open pnl parity failed")
        store.log_event(f"Parity open PnL error: {exc}", "parity")
    try:
        out.append(reconcile_closed_trades_parity(days=7, fix=True))
    except Exception as exc:
        log.exception("closed trades parity failed")
        store.log_event(f"Parity trades error: {exc}", "parity")
    return out


# --- Hourly digest (durable: MoexLiveWatchdog / monitor tick, not Cursor chat) ---

HOURLY_PARITY_INTERVAL_MS = 3_600_000
_HOURLY_LAST_MS_KEY = "parity_hourly_last_ms"
_HOURLY_SNAPSHOT_KEY = "parity_hourly_snapshot_json"


def _msk_now_str() -> str:
    from datetime import datetime, timedelta, timezone

    try:
        from zoneinfo import ZoneInfo

        tz = ZoneInfo("Europe/Moscow")
    except Exception:
        tz = timezone(timedelta(hours=3))
    return datetime.now(tz).strftime("%Y-%m-%d %H:%M")


def _position_label() -> str:
    open_t = store.get_open_trade()
    if not open_t:
        return "FLAT"
    d = str(open_t.get("direction") or "").upper()
    if d == "LONG":
        return "Long"
    if d == "SHORT":
        return "Short"
    return d or "FLAT"


def _load_hourly_snapshot() -> dict[str, Any]:
    raw = store.get_setting(_HOURLY_SNAPSHOT_KEY, "") or ""
    if not raw:
        return {}
    try:
        data = json.loads(raw)
        return data if isinstance(data, dict) else {}
    except json.JSONDecodeError:
        return {}


def build_hourly_parity_digest(
    *,
    prev_snapshot: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Собрать почасовой дайджест Test↔Prod из текущего parity_summary (без I/O)."""
    summary = store.parity_summary()
    edges = summary.get("edges") or []
    missing_ids = sorted(
        int(e["id"])
        for e in edges
        if e.get("status") == "missing" and e.get("id") is not None
    )
    pending = int(summary.get("pending") or 0)
    matched = int(summary.get("matched") or 0)
    missing = int(summary.get("missing") or 0)

    latest = summary.get("latest")
    if latest:
        last_edge = f"{latest.get('signal')}@{latest.get('bar_ts')} {latest.get('status')}"
    else:
        last_edge = "—"

    open_pnl = summary.get("open_pnl") if isinstance(summary.get("open_pnl"), dict) else {}
    open_status = str(open_pnl.get("status") or ("ok" if open_pnl.get("ok") else "n/a"))
    open_ok = bool(open_pnl.get("ok", True)) if open_pnl else True

    trades = summary.get("trades") if isinstance(summary.get("trades"), dict) else {}
    hard = int(
        trades.get("hard_mismatches")
        if trades
        else summary.get("trades_hard_mismatches")
        or 0
    )
    soft = int(trades.get("soft_mismatches") or 0) if trades else 0

    prev = prev_snapshot if prev_snapshot is not None else _load_hourly_snapshot()
    prev_missing = {int(x) for x in (prev.get("missing_ids") or []) if x is not None}
    new_missing_ids = sorted(set(missing_ids) - prev_missing)
    prev_hard = int(prev.get("hard_mismatches") or 0)
    new_hard = max(0, hard - prev_hard)

    pos = _position_label()
    clean = (not new_missing_ids) and new_hard == 0 and (pos == "FLAT" or open_ok)
    status_tag = "OK" if clean else "ALERT"

    new_parts: list[str] = []
    if new_missing_ids:
        new_parts.append("missing+" + ",".join(f"#{i}" for i in new_missing_ids))
    if new_hard:
        new_parts.append(f"hard+{new_hard}")
    new_str = ", ".join(new_parts) if new_parts else "none"

    msk = _msk_now_str()
    line = (
        f"{msk} MSK | {pos} | edges P{pending}/M{matched}/X{missing} | "
        f"last {last_edge} | open_pnl={open_status} | "
        f"trades hard={hard} soft={soft} | NEW: {new_str} | {status_tag} | "
        f"chat-loop=optional"
    )
    return {
        "ok": clean,
        "status": status_tag,
        "msk": msk,
        "position": pos,
        "pending": pending,
        "matched": matched,
        "missing": missing,
        "missing_ids": missing_ids,
        "last_edge": last_edge,
        "open_pnl_status": open_status,
        "open_pnl_ok": open_ok,
        "trades_hard": hard,
        "trades_soft": soft,
        "new_missing_ids": new_missing_ids,
        "new_hard_delta": new_hard,
        "line": line,
        "ts_ms": int(time.time() * 1000),
        "note": "Cursor chat wake loop is optional; this digest is from MoexLiveWatchdog",
    }


def write_hourly_parity_digest(digest: dict[str, Any]) -> dict[str, Any]:
    """Записать дайджест в parity-hourly.log, latest.json и live_events."""
    data_dir = store.DATA_DIR
    data_dir.mkdir(parents=True, exist_ok=True)
    line = str(digest.get("line") or "")
    try:
        with (data_dir / "parity-hourly.log").open("a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError as exc:
        log.warning("parity-hourly.log write failed: %s", exc)
    try:
        with (data_dir / "parity-hourly-latest.json").open("w", encoding="utf-8") as f:
            json.dump(digest, f, ensure_ascii=False, indent=2)
    except OSError as exc:
        log.warning("parity-hourly-latest.json write failed: %s", exc)

    store.log_event(f"Parity hourly: {line}", "parity")
    snap = {
        "missing_ids": list(digest.get("missing_ids") or []),
        "hard_mismatches": int(digest.get("trades_hard") or 0),
        "soft_mismatches": int(digest.get("trades_soft") or 0),
        "ts_ms": int(digest.get("ts_ms") or time.time() * 1000),
    }
    store.set_setting(_HOURLY_SNAPSHOT_KEY, json.dumps(snap, ensure_ascii=False))
    store.set_setting(_HOURLY_LAST_MS_KEY, str(snap["ts_ms"]))
    log.info("Parity hourly digest: %s", line)
    return digest


def maybe_run_hourly_parity_digest(
    *,
    force: bool = False,
    run_checks: bool = True,
) -> dict[str, Any] | None:
    """
    Раз в час (или force): parity checks + строка в data/parity-hourly.log.
    Не зависит от Cursor chat wake loop — вызывается из monitor tick.
    """
    now_ms = int(time.time() * 1000)
    last_ms = int(store.get_setting(_HOURLY_LAST_MS_KEY, "0") or "0")
    if not force and last_ms > 0 and (now_ms - last_ms) < HOURLY_PARITY_INTERVAL_MS:
        return None
    if run_checks:
        try:
            process_due_parity_checks()
        except Exception as exc:
            log.exception("hourly parity checks failed")
            store.log_event(f"Parity hourly checks error: {exc}", "parity")
    digest = build_hourly_parity_digest()
    return write_hourly_parity_digest(digest)


# Допуск по открытому PnL: 50 ₽ или 0.05% номинала — что больше.
OPEN_PNL_TOL_RUB = 50.0
OPEN_PNL_TOL_PCT = 0.05


def _spread_mtm_rub(
    *,
    direction: str,
    entry_spread: float,
    spread_now: float,
    notional: float,
) -> float:
    d = (direction or "").upper()
    if d == "LONG":
        pts = spread_now - entry_spread
    elif d == "SHORT":
        pts = entry_spread - spread_now
    else:
        return 0.0
    return notional * (pts / 100.0)


def ensure_open_entry_from_fills(open_t: dict[str, Any]) -> dict[str, Any]:
    """
    Если в сделке ISS-спред/цены, а в legs есть fill — переписать entry_* на исполнение.
    Иначе Test (спред×номинал) и Prod (ноги) расходятся навсегда.
    """
    from live.open_mark import fill_prices_from_legs

    fill_tatn, fill_tatnp = fill_prices_from_legs(open_t)
    if not fill_tatn or not fill_tatnp or fill_tatnp <= 0:
        return open_t
    fill_spread = (fill_tatn - fill_tatnp) / fill_tatnp * 100.0
    snap_sp = open_t.get("entry_spread")
    try:
        snap_f = float(snap_sp) if snap_sp is not None else None
    except (TypeError, ValueError):
        snap_f = None
    # Уже близко к fill — не трогаем
    if snap_f is not None and abs(snap_f - fill_spread) < 0.02:
        return open_t
    tid = int(open_t["id"])
    store.update_open_trade_fields(
        tid,
        {
            "entry_tatn": fill_tatn,
            "entry_tatnp": fill_tatnp,
            "entry_spread": fill_spread,
        },
    )
    store.log_event(
        f"Parity open: entry_spread ISS→fill "
        f"{snap_f if snap_f is not None else '—':.3f}→{fill_spread:.3f}% "
        f"(TATN {fill_tatn:.2f} / TATNP {fill_tatnp:.2f})",
        "parity",
    )
    refreshed = store.get_open_trade()
    return refreshed or open_t


def check_open_pnl_parity(*, market: dict[str, Any] | None = None) -> dict[str, Any]:
    """
    Сверка PnL открытой сделки: Prod vs Test по ногам (fill).
    Если entry_* ещё от ISS — переписывает на цены исполнения, затем сравнивает.
    """
    from live.open_mark import enrich_open_trade, fill_prices_from_legs, legs_unrealized_rub

    open_t = store.get_open_trade()
    if not open_t:
        result = {
            "ok": True,
            "status": "flat",
            "kind": "open_pnl",
            "detail": "нет открытой позиции",
        }
        store.set_setting("parity_open_pnl_json", json.dumps(result, ensure_ascii=False))
        return result

    if market is None:
        try:
            from live.engine import market_snapshot

            market = market_snapshot()
        except Exception as exc:
            result = {
                "ok": False,
                "status": "error",
                "kind": "open_pnl",
                "detail": f"нет рынка: {exc}",
            }
            store.set_setting("parity_open_pnl_json", json.dumps(result, ensure_ascii=False))
            return result

    # Сначала выровнять entry под fill (причина вчерашнего +111 vs минус в Тинькофф)
    open_t = ensure_open_entry_from_fills(open_t)

    z_now = market.get("z")
    spread_now = market.get("spread")
    tatn_now = market.get("tatn")
    tatnp_now = market.get("tatnp")
    trade_date = market.get("trade_date")
    entry_th = float(store.get_setting("entry_z", "1.6") or "1.6")

    enriched = enrich_open_trade(
        open_t,
        z_now=z_now if z_now is None else float(z_now),
        spread_now=spread_now if spread_now is None else float(spread_now),
        trade_date=str(trade_date) if trade_date else None,
        entry_threshold=entry_th,
        tatn_now=tatn_now if tatn_now is None else float(tatn_now),
        tatnp_now=tatnp_now if tatnp_now is None else float(tatnp_now),
    )
    mark = (enriched or {}).get("mark") or {}
    prod_pnl = mark.get("unrealized_pnl_rub")
    notional = float(open_t.get("execution_notional_rub") or mark.get("notional_rub") or 0)
    direction = str(open_t.get("direction") or "")
    entry_spread = float(open_t["entry_spread"]) if open_t.get("entry_spread") is not None else None
    sp_now = float(spread_now) if spread_now is not None else None

    fill_tatn, fill_tatnp = fill_prices_from_legs(open_t)
    legs_pnl = None
    if (
        fill_tatn
        and fill_tatnp
        and tatn_now is not None
        and tatnp_now is not None
        and float(tatn_now) > 0
        and float(tatnp_now) > 0
    ):
        legs_pnl = legs_unrealized_rub(
            direction=direction,
            lots=int(open_t.get("quantity_lots") or 0),
            fill_tatn=fill_tatn,
            fill_tatnp=fill_tatnp,
            now_tatn=float(tatn_now),
            now_tatnp=float(tatnp_now),
        )

    # Test ≈ Prod: при наличии fill обе стороны — MTM по ногам (как Тинькофф).
    # Спред×номинал оставляем как диагностику (приближение, часто расходится).
    test_pnl = legs_pnl if legs_pnl is not None else None
    spread_approx_pnl = None
    if entry_spread is not None and sp_now is not None and notional > 0:
        spread_approx_pnl = _spread_mtm_rub(
            direction=direction,
            entry_spread=entry_spread,
            spread_now=sp_now,
            notional=notional,
        )
        if test_pnl is None:
            test_pnl = spread_approx_pnl

    tol = max(OPEN_PNL_TOL_RUB, notional * (OPEN_PNL_TOL_PCT / 100.0)) if notional else OPEN_PNL_TOL_RUB

    issues: list[str] = []
    if prod_pnl is not None and legs_pnl is not None and abs(prod_pnl - legs_pnl) > tol:
        issues.append(
            f"Prod mark {prod_pnl:.0f}₽ ≠ пересчёт ног {legs_pnl:.0f}₽ (допуск {tol:.0f}₽)"
        )
    if prod_pnl is not None and test_pnl is not None and abs(prod_pnl - test_pnl) > tol:
        issues.append(
            f"Prod {prod_pnl:.0f}₽ ≠ Test {test_pnl:.0f}₽"
        )

    notes: list[str] = []
    if (
        spread_approx_pnl is not None
        and prod_pnl is not None
        and abs(spread_approx_pnl - prod_pnl) > tol
    ):
        notes.append(
            f"спред×номинал ≈{spread_approx_pnl:.0f}₽ (приближение; канон — ноги/fill)"
        )

    ok = not issues
    detail = "PnL открытой: Prod ≈ Test (ноги/fill)" if ok else "; ".join(issues)
    if notes:
        detail = f"{detail} · {'; '.join(notes)}"

    result = {
        "ok": ok,
        "status": "matched" if ok else "mismatch",
        "kind": "open_pnl",
        "detail": detail,
        "direction": direction,
        "prod_pnl_rub": prod_pnl,
        "test_pnl_rub": test_pnl,
        "legs_pnl_rub": legs_pnl,
        "spread_approx_pnl_rub": spread_approx_pnl,
        "tol_rub": tol,
        "entry_spread": entry_spread,
        "spread_now": sp_now,
        "fill_tatn": fill_tatn,
        "fill_tatnp": fill_tatnp,
        "pnl_source": mark.get("pnl_source"),
        "trade_id": open_t.get("id"),
        "notional_rub": notional,
    }
    store.set_setting("parity_open_pnl_json", json.dumps(result, ensure_ascii=False))
    if ok:
        store.log_event(
            f"Parity open PnL OK: Prod {prod_pnl:.0f}₽ ≈ Test {test_pnl:.0f}₽"
            if prod_pnl is not None and test_pnl is not None
            else f"Parity open PnL OK: {result['detail']}",
            "parity",
        )
    else:
        store.log_event(f"Parity open PnL MISMATCH: {result['detail']}", "parity")
        log.warning("Open PnL parity mismatch: %s", result)
    return result
# —— Сверка полей закрытых сделок Prod ↔ Test (бар-сим) ——

TRADE_TIME_TOL_MIN = 30  # ±2 бара
Z_TOL = 0.08
SPREAD_TOL = 0.08
PNL_TOL_RUB = 80.0
PNL_TOL_PCT = 0.15  # % номинала


def _f(v):
    if v is None:
        return None
    try:
        n = float(v)
    except (TypeError, ValueError):
        return None
    return n if n == n else None


def _ts16(s):
    return str(s or "").strip().replace("T", " ")[:16]


def _bar_by_ts(bars, ts):
    want = _ts16(ts)
    if not want:
        return None
    for b in bars:
        if _ts16(b.get("tradeDate")) == want:
            return b
    return None


def _slice_bars(bars, entry_time, exit_time):
    """Вырезать окно hold из уже загруженных баров (без SQLite/ensure)."""
    from live.closed_metrics import _parse_ms

    entry_ms = _parse_ms(entry_time)
    exit_ms = _parse_ms(exit_time)
    if entry_ms is None or exit_ms is None:
        return bars
    lo, hi = entry_ms - 3 * 60 * 60 * 1000, exit_ms + 60_000
    out = []
    for b in bars:
        ms = b.get("timestampMs")
        if ms is None:
            continue
        try:
            msi = int(ms)
        except (TypeError, ValueError):
            continue
        if lo <= msi <= hi:
            out.append(b)
    return out


def collect_sim_closed_trades(
    bars,
    entry,
    exit_z,
    *,
    deposit_rub=10_000,
    leverage=7,
):
    """Закрытые сделки бар-сима (как Testing) с метриками closed_metrics."""
    from live.closed_metrics import compute_path_metrics

    edges = collect_sim_edges(bars, entry, exit_z)
    out = []
    open_e = None
    for e in edges:
        sig = str(e.get("signal") or "")
        if sig.startswith("ENTER"):
            open_e = e
            continue
        if not (sig.startswith("EXIT") and open_e):
            continue
        entry_bar = _bar_by_ts(bars, open_e["bar_ts"])
        exit_bar = _bar_by_ts(bars, e["bar_ts"])
        direction = "LONG" if "LONG" in sig else "SHORT"
        raw = {
            "direction": direction,
            "entry_time": open_e["bar_ts"],
            "exit_time": e["bar_ts"],
            "entry_z": _f(open_e.get("z")),
            "exit_z": _f(e.get("z")),
            "entry_spread": _f((entry_bar or {}).get("spreadPercent")),
            "exit_spread": _f((exit_bar or {}).get("spreadPercent")),
            "source": "SIM",
        }
        if raw["entry_spread"] is not None and raw["exit_spread"] is not None:
            if direction == "LONG":
                raw["pnl_pts"] = raw["exit_spread"] - raw["entry_spread"]
            else:
                raw["pnl_pts"] = raw["entry_spread"] - raw["exit_spread"]
        path_bars = _slice_bars(bars, raw["entry_time"], raw["exit_time"])
        metrics = compute_path_metrics(
            raw, path_bars, deposit_rub=deposit_rub, leverage=leverage
        )
        for k, v in metrics.items():
            if str(k).startswith("_"):
                continue
            if raw.get(k) is None and v is not None:
                raw[k] = v
        raw["sim_entry_bar"] = open_e["bar_ts"]
        raw["sim_exit_bar"] = e["bar_ts"]
        out.append(raw)
        open_e = None
    return out


def _match_sim_trade(prod, sim_trades):
    """Совпадение в первую очередь по входу (±30м); спред отсекает ложные пары."""
    p_dir = str(prod.get("direction") or "").upper()
    p_entry = _ts16(prod.get("entry_time"))
    p_exit = _ts16(prod.get("exit_time"))
    p_es = _f(prod.get("entry_spread"))
    if not p_entry and not p_exit:
        return None
    best = None
    best_score = None
    for t in sim_trades:
        if str(t.get("direction") or "").upper() != p_dir:
            continue
        d_entry = abs(_bar_delta_min(_ts16(t.get("entry_time")), p_entry)) if p_entry else 10_000
        d_exit = abs(_bar_delta_min(_ts16(t.get("exit_time")), p_exit)) if p_exit else 10_000
        t_es = _f(t.get("entry_spread"))
        spread_abs = (
            abs(p_es - t_es) if (p_es is not None and t_es is not None) else 0.0
        )
        # основной критерий — вход
        if d_entry <= TRADE_TIME_TOL_MIN:
            if d_entry > 15 and spread_abs > 0.25:
                continue
            score = d_entry + spread_abs * 40.0 + min(d_exit, 60) * 0.25
        elif d_exit <= 15 and d_entry <= 45 and spread_abs <= 0.15:
            # редкий случай: выход совпал, вход чуть съехал, спред тот же
            score = 50 + d_entry + spread_abs * 40.0
        else:
            continue
        if best_score is None or score < best_score:
            best_score = score
            best = t
    return best


def _diff_field(name, prod_v, test_v, *, kind, tol=None):
    if kind == "time":
        a, b = _ts16(prod_v), _ts16(test_v)
        if not a and not b:
            return None
        if a == b:
            return None
        return {"field": name, "prod": a or None, "test": b or None, "mismatch": True}
    if kind == "str":
        a = str(prod_v) if prod_v is not None else None
        b = str(test_v) if test_v is not None else None
        if a == b:
            return None
        return {"field": name, "prod": a, "test": b, "mismatch": True}
    pa, ta = _f(prod_v), _f(test_v)
    if pa is None and ta is None:
        return None
    if pa is None or ta is None:
        return {"field": name, "prod": pa, "test": ta, "mismatch": True}
    lim = tol if tol is not None else 0.0
    if abs(pa - ta) <= lim:
        return None
    return {
        "field": name,
        "prod": round(pa, 6),
        "test": round(ta, 6),
        "delta": round(pa - ta, 6),
        "mismatch": True,
    }


def compare_trade_fields(prod, test):
    """Все ключевые поля сделки. soft=True для PnL (fill ≠ бар)."""
    notional = (
        _f(prod.get("execution_notional_rub"))
        or _f(test.get("execution_notional_rub"))
        or 70_000
    )
    pnl_tol = max(PNL_TOL_RUB, notional * (PNL_TOL_PCT / 100.0))
    prod_pts = _f(prod.get("pnl_pts"))
    if prod_pts is None:
        es, xs = _f(prod.get("entry_spread")), _f(prod.get("exit_spread"))
        if es is not None and xs is not None:
            prod_pts = (
                (xs - es)
                if str(prod.get("direction") or "").upper().startswith("L")
                else (es - xs)
            )
    test_pts = _f(test.get("pnl_pts"))
    if test_pts is None:
        es, xs = _f(test.get("entry_spread")), _f(test.get("exit_spread"))
        if es is not None and xs is not None:
            test_pts = (
                (xs - es)
                if str(test.get("direction") or "").upper().startswith("L")
                else (es - xs)
            )

    specs = [
        ("entry_time", prod.get("entry_time"), test.get("entry_time"), "time", None, False),
        ("exit_time", prod.get("exit_time"), test.get("exit_time"), "time", None, False),
        ("direction", prod.get("direction"), test.get("direction"), "str", None, False),
        ("entry_z", prod.get("entry_z"), test.get("entry_z"), "num", Z_TOL, False),
        ("exit_z", prod.get("exit_z"), test.get("exit_z"), "num", Z_TOL, False),
        ("entry_spread", prod.get("entry_spread"), test.get("entry_spread"), "num", SPREAD_TOL, False),
        ("exit_spread", prod.get("exit_spread"), test.get("exit_spread"), "num", SPREAD_TOL, False),
        ("pnl_pts", prod_pts, test_pts, "num", SPREAD_TOL, False),
        ("pnl_rub", prod.get("pnl_rub"), test.get("pnl_rub"), "num", pnl_tol, True),
        ("gross_rub", prod.get("gross_rub"), test.get("gross_rub"), "num", pnl_tol, True),
        ("commission_rub", prod.get("commission_rub"), test.get("commission_rub"), "num", 25.0, True),
        ("overnight_rub", prod.get("overnight_rub"), test.get("overnight_rub"), "num", 15.0, True),
        ("pnl_min_rub", prod.get("pnl_min_rub"), test.get("pnl_min_rub"), "num", pnl_tol, True),
        ("pnl_max_rub", prod.get("pnl_max_rub"), test.get("pnl_max_rub"), "num", pnl_tol, True),
        ("entry_slip_pts", prod.get("entry_slip_pts"), test.get("entry_slip_pts"), "num", 0.12, True),
    ]
    diffs = []
    for name, pv, tv, kind, tol, soft in specs:
        d = _diff_field(name, pv, tv, kind=kind, tol=tol)
        if d:
            d["soft"] = soft
            diffs.append(d)
    return diffs


def _fix_prod_z_from_bars(prod, bars):
    patch = {}
    for key_t, key_z in (("entry_time", "entry_z"), ("exit_time", "exit_z")):
        bar = _bar_by_ts(bars, prod.get(key_t) or "")
        if not bar:
            continue
        bz = _f(bar.get("zScore"))
        if bz is None:
            continue
        cur = _f(prod.get(key_z))
        if cur is None or abs(cur - bz) > Z_TOL:
            patch[key_z] = bz
    return patch


def _fix_prod_times_from_sim(prod, sim):
    """Выровнять времена/Z на сигнал. Не трогаем pnl_rub (fill)."""
    patch = {}
    for key in ("entry_time", "exit_time"):
        if _ts16(prod.get(key)) != _ts16(sim.get(key)):
            patch[key] = _ts16(sim.get(key))
    for key in ("entry_z", "exit_z"):
        sz = _f(sim.get(key))
        if sz is None:
            continue
        pz = _f(prod.get(key))
        if pz is None or abs(pz - sz) > Z_TOL:
            patch[key] = sz
    return patch


def reconcile_closed_trades_parity(*, days=7, fix=True, limit=40):
    """
    Сверить закрытые Prod с Test-симом по всем полям.
    fix=True: безопасные правки метаданных (Z/времена) + досчитать Min/Max.
    """
    from datetime import datetime, timedelta

    from replay.replay_db import ensure_replay_bars, load_bars_from_db

    settings = store.get_settings_bundle()
    entry = float(settings.get("entry_z") or 1.6)
    exit_z = float(settings.get("exit_z") or 1.3)
    deposit = float(settings.get("entry_deposit_rub") or 10_000)
    leverage = float(settings.get("leverage") or 7)

    bars = load_bars_from_db()
    if len(bars) < 2:
        data_dir = Path(__file__).resolve().parent.parent / "data"
        payload = ensure_replay_bars(
            data_dir / "m15_tatn_255d.csv",
            "m15_tatn_255d.csv",
            online=False,
        )
        bars = payload.get("bars") or []
    store.overlay_decision_bars_on_series(bars)
    tips = load_tip_bars_for_parity(bars)
    if len(tips) >= 2:
        store.overlay_decision_bars_on_series(tips)
        bars = tips

    sim_trades = collect_sim_closed_trades(
        bars, entry, exit_z, deposit_rub=deposit, leverage=leverage
    )
    closed = store.get_closed_trades(limit)
    cutoff = datetime.now() - timedelta(days=days) if days > 0 else None

    pairs = []
    unmatched_prod = []
    fixes = []
    hard_mismatches = 0
    soft_mismatches = 0
    used_sim = set()

    for prod in closed:
        exit_t = _ts16(prod.get("exit_time") or prod.get("entry_time"))
        if cutoff and exit_t:
            try:
                if datetime.strptime(exit_t, "%Y-%m-%d %H:%M") < cutoff:
                    continue
            except ValueError:
                pass

        sim = _match_sim_trade(prod, sim_trades)
        if sim is None:
            unmatched_prod.append(
                {
                    "prod_id": prod.get("id"),
                    "entry_time": _ts16(prod.get("entry_time")),
                    "exit_time": _ts16(prod.get("exit_time")),
                    "direction": prod.get("direction"),
                    "source": prod.get("source"),
                    "pnl_rub": _f(prod.get("pnl_rub")),
                }
            )
            hard_mismatches += 1
            continue

        used_sim.add(id(sim))
        diffs = compare_trade_fields(prod, sim)
        hard = [d for d in diffs if not d.get("soft")]
        soft = [d for d in diffs if d.get("soft")]
        hard_mismatches += len(hard)
        soft_mismatches += len(soft)

        applied = []
        if fix:
            patch = {}
            # Только Z с баров в СОХРАНЁННЫЕ timestamps (внутренняя согласованность).
            # Времена/спреды/PnL от сима НЕ переписываем — fill/брокер канон для денег.
            patch.update(_fix_prod_z_from_bars(prod, bars))
            need_metrics = (
                prod.get("pnl_min_rub") is None
                or prod.get("pnl_max_rub") is None
                or prod.get("pnl_rub") is None
            )
            if need_metrics:
                path = _slice_bars(bars, prod.get("entry_time"), prod.get("exit_time"))
                from live.closed_metrics import compute_path_metrics

                enriched_metrics = compute_path_metrics(
                    prod, path, deposit_rub=deposit, leverage=leverage
                )
                for k, v in enriched_metrics.items():
                    if str(k).startswith("_") or v is None:
                        continue
                    if prod.get(k) is None and k in (
                        "pnl_min_rub",
                        "pnl_max_rub",
                        "hit1_time",
                        "hit2_time",
                        "hit3_time",
                        "gross_rub",
                        "commission_rub",
                        "overnight_rub",
                        "pnl_rub",
                        "execution_notional_rub",
                    ):
                        patch[k] = v

            if patch and prod.get("id") is not None:
                try:
                    before_keys = set(patch.keys())
                    store.update_closed_trade(int(prod["id"]), patch)
                    # только реально разрешённые колонки
                    applied = sorted(
                        k
                        for k in before_keys
                        if k
                        in {
                            "entry_spread_iss",
                            "entry_slip_pts",
                            "execution_notional_rub",
                            "gross_rub",
                            "commission_rub",
                            "overnight_rub",
                            "pnl_rub",
                            "pnl_min_rub",
                            "pnl_max_rub",
                            "hit1_time",
                            "hit2_time",
                            "hit3_time",
                            "account_after_rub",
                            "entry_z",
                            "exit_z",
                        }
                    )
                    if applied:
                        fixes.append(
                            {"prod_id": prod.get("id"), "patch": {k: patch[k] for k in applied}}
                        )
                        store.log_event(
                            f"Parity trades fix #{prod.get('id')}: {', '.join(applied)}",
                            "parity",
                        )
                except Exception as exc:
                    log.warning("parity trade fix failed #%s: %s", prod.get("id"), exc)

        pairs.append(
            {
                "prod_id": prod.get("id"),
                "prod_entry": _ts16(prod.get("entry_time")),
                "prod_exit": _ts16(prod.get("exit_time")),
                "sim_entry": _ts16(sim.get("entry_time")),
                "sim_exit": _ts16(sim.get("exit_time")),
                "direction": prod.get("direction"),
                "source": prod.get("source"),
                "diffs": diffs,
                "hard": len(hard),
                "soft": len(soft),
                "fixed": applied,
            }
        )

    unmatched_sim = []
    for t in sim_trades:
        if id(t) in used_sim:
            continue
        et = _ts16(t.get("entry_time"))
        if cutoff and et:
            try:
                if datetime.strptime(et, "%Y-%m-%d %H:%M") < cutoff:
                    continue
            except ValueError:
                pass
        unmatched_sim.append(
            {
                "entry_time": et,
                "exit_time": _ts16(t.get("exit_time")),
                "direction": t.get("direction"),
                "pnl_rub": _f(t.get("pnl_rub")),
            }
        )
        hard_mismatches += 1

    ok = hard_mismatches == 0 and not unmatched_prod and not unmatched_sim
    result = {
        "ok": ok,
        "kind": "closed_trades",
        "status": "matched" if ok else "mismatch",
        "entry_z": entry,
        "exit_z": exit_z,
        "days": days,
        "prod_count": len(pairs) + len(unmatched_prod),
        "sim_count": len(sim_trades),
        "pairs": pairs,
        "unmatched_prod": unmatched_prod,
        "unmatched_sim": unmatched_sim,
        "hard_mismatches": hard_mismatches,
        "soft_mismatches": soft_mismatches,
        "fixes": fixes,
        "detail": (
            "закрытые сделки: поля совпали"
            if ok
            else (
                f"hard={hard_mismatches} soft={soft_mismatches} "
                f"unmatched_prod={len(unmatched_prod)} unmatched_sim={len(unmatched_sim)} "
                f"fixes={len(fixes)}"
            )
        ),
    }
    store.set_setting("parity_trades_json", json.dumps(result, ensure_ascii=False))
    if ok:
        store.log_event("Parity trades OK: поля Prod ≈ Test", "parity")
    else:
        store.log_event(f"Parity trades MISMATCH: {result['detail']}", "parity")
        log.warning("Closed trades parity: %s", result["detail"])
    return result
