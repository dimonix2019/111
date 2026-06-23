"""Комплексный функциональный тест strategy-web (API + zsim + serialize)."""

from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from api.bars_cache import cache_info, get_bars, invalidate
from api.serialize import pack_to_dict
from zsim import load_bars_from_csv, param_sweep, run_z_strategy_sim

API = "http://127.0.0.1:8765"
CSV = ROOT / "data" / "m15_tatn_255d.csv"

passed = 0
failed = 0
warnings: list[str] = []


def ok(name: str) -> None:
    global passed
    passed += 1
    print(f"  OK  {name}")


def fail(name: str, detail: str) -> None:
    global failed
    failed += 1
    print(f"  FAIL {name}: {detail}")


def warn(msg: str) -> None:
    warnings.append(msg)
    print(f"  WARN {msg}")


def api_get(path: str) -> dict:
    with urllib.request.urlopen(f"{API}{path}", timeout=30) as r:
        return json.loads(r.read())


def api_post(path: str, body: dict) -> dict:
    data = json.dumps(body).encode()
    req = urllib.request.Request(
        f"{API}{path}",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def test_zsim_core() -> None:
    print("\n=== zsim core ===")
    if not CSV.is_file():
        fail("csv_exists", str(CSV))
        return
    ok("csv_exists")

    bars = load_bars_from_csv(CSV, recalc_z=False)
    if len(bars) < 1000:
        fail("bar_count", f"{len(bars)}")
    else:
        ok(f"bar_count={len(bars)}")

    res = run_z_strategy_sim(bars, entry=0.8, exit_z=0.7, notional_rub=100_000)
    if res.stats()["trade_count"] < 1:
        fail("sim_trades", "0 trades")
    else:
        ok(f"sim_trades={res.stats()['trade_count']}")

    ctx = res.market_context(0.8, 0.7)
    if len(ctx.get("metrics", [])) != 4:
        fail("market_context", f"metrics={len(ctx.get('metrics', []))}")
    else:
        ok("market_context 4 metrics")

    idle = res.idle_gaps_analysis()
    if "histogram" not in idle or "current" not in idle:
        fail("idle_gaps", str(idle.keys()))
    else:
        ok("idle_gaps")


def test_serialize_speed() -> None:
    print("\n=== serialize ===")
    bars = load_bars_from_csv(CSV, recalc_z=False)
    res = run_z_strategy_sim(bars, 0.8, 0.7)
    pack = {"label": "T", "result": res, "entry": 0.8, "exit_z": 0.7}
    t0 = time.perf_counter()
    d = pack_to_dict(pack)
    dt = time.perf_counter() - t0
    if dt > 2.0:
        warn(f"serialize slow: {dt:.2f}s (expected <2s)")
    else:
        ok(f"serialize {dt:.2f}s")

    for key in ("stats", "trades", "equity", "zscore", "trade_markers", "idle_gaps", "market_context"):
        if key not in d:
            fail(f"pack_key_{key}", "missing")
        else:
            ok(f"pack has {key}")

    if len(d["equity"]) != len(bars) - 1 and len(d["equity"]) != len(bars):
        warn(f"equity len {len(d['equity'])} vs bars {len(bars)}")
    else:
        ok(f"equity points={len(d['equity'])}")

    if "time" not in d["equity"][0] or "equity_rub" not in d["equity"][0]:
        fail("equity_schema", str(d["equity"][0].keys()))
    else:
        ok("equity schema")


def test_bars_cache() -> None:
    print("\n=== bars cache ===")
    invalidate()
    t0 = time.perf_counter()
    b1 = get_bars(CSV)
    t1 = time.perf_counter()
    b2 = get_bars(CSV)
    t2 = time.perf_counter()
    if len(b1) != len(b2):
        fail("cache_len", f"{len(b1)} vs {len(b2)}")
    else:
        ok("cache same length")
    if t2 - t1 >= t1 - t0:
        warn(f"cache miss={t1-t0:.3f}s hit={t2-t1:.3f}s (hit should be faster)")
    else:
        ok(f"cache hit faster ({t2-t1:.4f}s vs {t1-t0:.3f}s)")
    info = cache_info()
    if info.get("entries", 0) < 1:
        fail("cache_info", str(info))
    else:
        ok(f"cache entries={info['entries']}")


def test_param_sweep() -> None:
    print("\n=== param_sweep ===")
    bars = get_bars(CSV)
    df = param_sweep(bars, [0.8, 0.9], [0.5, 0.7], notional_rub=100_000, leverage=7)
    if len(df) != 4:
        fail("sweep_rows", str(len(df)))
    else:
        ok("sweep 2x2=4 rows")


def test_api() -> None:
    print("\n=== HTTP API ===")
    try:
        h = api_get("/api/health")
    except urllib.error.URLError as e:
        fail("api_health", str(e))
        warn("API not running — start with: uvicorn api.server:app --port 8765")
        return
    if not h.get("ok"):
        fail("api_health", str(h))
    else:
        ok("GET /api/health")
    if "bars_cache" not in h:
        warn("health missing bars_cache — restart API to pick up latest code")
    else:
        ok("health bars_cache")

    presets = api_get("/api/presets")
    if "Свои" not in presets:
        fail("presets", str(list(presets.keys())[:3]))
    else:
        ok("GET /api/presets")

    rel_csv = "data/m15_tatn_255d.csv"
    status = api_get(f"/api/data/status?path={urllib.parse.quote(rel_csv)}")
    if not status.get("exists"):
        fail("data_status", str(status))
    else:
        ok(f"data status rows={status.get('row_count')}")

    t0 = time.perf_counter()
    sim = api_post(
        "/api/simulate",
        {
            "csv_path": rel_csv,
            "auto_download": False,
            "entry": 0.8,
            "exit_z": 0.7,
            "notional": 100_000,
            "leverage": 7,
            "commission": 0.04,
            "compound": False,
        },
    )
    dt = time.perf_counter() - t0
    if dt > 5.0:
        warn(f"POST /api/simulate slow: {dt:.2f}s")
    else:
        ok(f"POST /api/simulate {dt:.2f}s")

    if not sim.get("packs") or len(sim["packs"]) != 1:
        fail("simulate_packs", str(sim.keys()))
        return
    pack = sim["packs"][0]
    for key in ("stats", "trades", "equity", "zscore", "market_context", "idle_gaps"):
        if key not in pack:
            fail(f"api_pack_{key}", "missing")
        else:
            ok(f"api pack.{key}")

    if pack["stats"]["trade_count"] < 1:
        fail("api_trade_count", "0")
    else:
        ok(f"api trades={pack['stats']['trade_count']}")

    t0 = time.perf_counter()
    sw = api_post(
        "/api/sweep",
        {
            "csv_path": rel_csv,
            "entry_min": 0.7,
            "entry_max": 0.8,
            "entry_step": 0.1,
            "exit_min": 0.5,
            "exit_max": 0.6,
            "exit_step": 0.1,
            "notional_rub": 100_000,
            "leverage": 7,
            "commission_pct_per_side": 0.04,
            "compound_returns": False,
        },
    )
    dt = time.perf_counter() - t0
    rows = sw.get("rows", [])
    if len(rows) != 4:
        fail("api_sweep_rows", str(len(rows)))
    else:
        ok(f"POST /api/sweep {len(rows)} rows in {dt:.1f}s")

    # second simulate — cache should help
    t0 = time.perf_counter()
    api_post(
        "/api/simulate",
        {"csv_path": rel_csv, "auto_download": False, "entry": 0.9, "exit_z": 0.6},
    )
    dt2 = time.perf_counter() - t0
    ok(f"simulate rerun {dt2:.2f}s")


def main() -> int:
    print("Strategy-web functional test")
    print(f"ROOT={ROOT}")
    test_zsim_core()
    test_serialize_speed()
    test_bars_cache()
    test_param_sweep()
    test_api()
    print(f"\n=== SUMMARY: {passed} passed, {failed} failed, {len(warnings)} warnings ===")
    for w in warnings:
        print(f"  ! {w}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
