"""Contract tests: spread-level signals (Python core = etalon).

One fixture bar series; ``determine_spread_level_signal`` is canonical.
JS duplicate in ``trade.js`` guarded by threshold regex test (drift detection).
"""

from __future__ import annotations

import json
import re
from pathlib import Path

from live.constants import (
    DEFAULT_SPREAD_ENTER_NARROW,
    DEFAULT_SPREAD_ENTER_WIDE,
    DEFAULT_SPREAD_EXIT_NARROW,
    DEFAULT_SPREAD_EXIT_WIDE,
)
from live.signals import Position, Signal
from live.spread_levels import SpreadLevels, determine_spread_level_signal
from live.spread_regime import (
    SPREAD_WIDTH_NARROW_MAX,
    SPREAD_WIDTH_WIDE_MIN,
    classify_spread_pct,
)
from live.tip_touch_signals import collect_tip1m_sim_edges

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = Path(__file__).resolve().parent / "fixtures" / "signal_contract_bars.json"
TRADE_JS = ROOT / "replay" / "static" / "trade.js"

SIGNAL_BY_NAME = {
    "ENTER_LONG": Signal.ENTER_LONG,
    "ENTER_SHORT": Signal.ENTER_SHORT,
    "EXIT_LONG": Signal.EXIT_LONG,
    "EXIT_SHORT": Signal.EXIT_SHORT,
    "NONE": Signal.NONE,
}


def _load_fixture() -> dict:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def _position_after(signal: Signal, pos: Position) -> Position:
    if signal == Signal.ENTER_LONG:
        return Position.LONG
    if signal == Signal.ENTER_SHORT:
        return Position.SHORT
    if signal in (Signal.EXIT_LONG, Signal.EXIT_SHORT):
        return Position.FLAT
    return pos


def _simulate_edges(bars: list[dict], lv: SpreadLevels) -> list[tuple[int, Signal]]:
    """Walk fixture bars; return (bar_index, signal) for non-NONE edges."""
    pos = Position.FLAT
    edges: list[tuple[int, Signal]] = []
    spreads = [float(b["spread_pct"]) for b in bars]
    for i in range(1, len(spreads)):
        prev, cur = spreads[i - 1], spreads[i]
        sig = determine_spread_level_signal(prev, cur, pos, lv)
        if sig != Signal.NONE:
            edges.append((i, sig))
        pos = _position_after(sig, pos)
    return edges


def _bars_to_tips(bars: list[dict]) -> list[dict]:
    """Tip1m dicts for collect_tip1m_sim_edges (Mon session, 1m steps)."""
    base = "2026-07-27 "
    t0 = 1_720_000_000_000
    tips: list[dict] = []
    for i, bar in enumerate(bars):
        hh, mm = divmod(10 * 60 + i, 60)
        tips.append(
            {
                "tradeDate": f"{base}{hh:02d}:{mm:02d}",
                "timestampMs": t0 + i * 60_000,
                "spreadPercent": float(bar["spread_pct"]),
                "zScore": 0.0,
            }
        )
    return tips


def test_fixture_thresholds_match_constants():
    fx = _load_fixture()
    th = fx["thresholds"]
    assert th["enter_wide"] == DEFAULT_SPREAD_ENTER_WIDE
    assert th["exit_wide"] == DEFAULT_SPREAD_EXIT_WIDE
    assert th["enter_narrow"] == DEFAULT_SPREAD_ENTER_NARROW
    assert th["exit_narrow"] == DEFAULT_SPREAD_EXIT_NARROW
    assert th["narrow_max"] == SPREAD_WIDTH_NARROW_MAX
    assert th["wide_min"] == SPREAD_WIDTH_WIDE_MIN


def test_fixture_regime_labels():
    fx = _load_fixture()
    for i, bar in enumerate(fx["bars"]):
        assert classify_spread_pct(bar["spread_pct"]) == bar["regime"], (
            f"bar {i} spread={bar['spread_pct']}"
        )


def test_signal_contract_expected_edges():
    fx = _load_fixture()
    lv = SpreadLevels()
    simulated = _simulate_edges(fx["bars"], lv)
    expected = [
        (e["bar_index"], SIGNAL_BY_NAME[e["signal"]]) for e in fx["expected_edges"]
    ]
    assert simulated == expected


def test_signal_contract_per_bar_deterministic():
    """Each bar: prev/cur + tracked position → signal matches walk simulation."""
    fx = _load_fixture()
    lv = SpreadLevels()
    spreads = [float(b["spread_pct"]) for b in fx["bars"]]
    pos = Position.FLAT
    for i in range(1, len(spreads)):
        sig = determine_spread_level_signal(spreads[i - 1], spreads[i], pos, lv)
        pos = _position_after(sig, pos)


def test_transition_bars_no_phantom_entries():
    fx = _load_fixture()
    lv = SpreadLevels()
    no_entry_indices = {e["bar_index"] for e in fx["transition_no_entry"]}
    pos = Position.FLAT
    spreads = [float(b["spread_pct"]) for b in fx["bars"]]
    for i in range(1, len(spreads)):
        sig = determine_spread_level_signal(spreads[i - 1], spreads[i], pos, lv)
        if i in no_entry_indices:
            assert sig == Signal.NONE, f"bar {i} should not enter"
        pos = _position_after(sig, pos)


def test_collect_tip1m_sim_edges_matches_fixture():
    """tip_touch path uses same determine_spread_level_signal core."""
    fx = _load_fixture()
    tips = _bars_to_tips(fx["bars"])
    edges = collect_tip1m_sim_edges(
        tips, 1.6, 1.3, respect_live_signal=False, spread_level_mode=True
    )
    expected_sigs = [e["signal"] for e in fx["expected_edges"]]
    assert [e["signal"] for e in edges] == expected_sigs


def _extract_js_fn(name: str, js: str) -> str:
    m = re.search(rf"function {name}\([^)]*\)\s*\{{", js)
    assert m, f"{name} not found in trade.js"
    start = m.start()
    depth = 0
    for j in range(m.end() - 1, len(js)):
        if js[j] == "{":
            depth += 1
        elif js[j] == "}":
            depth -= 1
            if depth == 0:
                return js[start : j + 1]
    raise AssertionError(f"unclosed {name}")


def test_trade_js_spread_thresholds_match_python():
    """JS fallback + determineSpreadLevelSignalJs must track Python constants."""
    js = TRADE_JS.read_text(encoding="utf-8")
    fn = _extract_js_fn("determineSpreadLevelSignalJs", js)

    assert "spreadCfgSpread()" in fn, "determineSpreadLevelSignalJs must use spreadCfgSpread()"
    assert "spreadCfgLevels(lv)" in fn, "determineSpreadLevelSignalJs must use spreadCfgLevels(lv)"
    assert re.search(r"cfg\.regime_wide_min", fn), (
        "JS wide regime cut must use cfg.regime_wide_min"
    )
    assert re.search(r"cfg\.regime_narrow_max", fn), (
        "JS narrow regime cut must use cfg.regime_narrow_max"
    )

    fb = _extract_js_spread_cfg_fallback(js)
    assert fb["enter_wide"] == DEFAULT_SPREAD_ENTER_WIDE
    assert fb["exit_wide"] == DEFAULT_SPREAD_EXIT_WIDE
    assert fb["enter_narrow"] == DEFAULT_SPREAD_ENTER_NARROW
    assert fb["exit_narrow"] == DEFAULT_SPREAD_EXIT_NARROW
    assert fb["regime_narrow_max"] == SPREAD_WIDTH_NARROW_MAX
    assert fb["regime_wide_min"] == SPREAD_WIDTH_WIDE_MIN


def _extract_js_spread_cfg_fallback(js: str) -> dict[str, float]:
    m = re.search(r"SPREAD_CFG_FALLBACK\s*=\s*Object\.freeze\(\s*\{([^}]+)\}", js, re.S)
    assert m, "SPREAD_CFG_FALLBACK not found in trade.js"
    block = m.group(1)
    out: dict[str, float] = {}
    for key in (
        "enter_wide",
        "exit_wide",
        "enter_narrow",
        "exit_narrow",
        "regime_narrow_max",
        "regime_wide_min",
    ):
        km = re.search(rf"{key}\s*:\s*([0-9.]+)", block)
        assert km, f"SPREAD_CFG_FALLBACK.{key} missing"
        out[key] = float(km.group(1))
    return out
