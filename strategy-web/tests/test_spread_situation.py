"""Подпись «Сейчас» на вкладке Ситуация — торговая полоса графика."""
from __future__ import annotations

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from live.spread_situation import (
    BAND_NEAR_PP,
    TAPE_EPS_PP,
    build_levels_line,
    build_regime_line,
    build_spread_situation,
    classify_vs_band,
    fmt_delta,
    fmt_hhmm_msk,
    fmt_level,
    pick_auto_levels_side,
    pick_trade_band,
)

MSK = ZoneInfo("Europe/Moscow")
LEVELS = {
    "enter_narrow": 3.2,
    "exit_narrow": 4.0,
    "exit_wide": 5.8,
    "enter_wide": 6.2,
}
WIDE_CORR = {"lo": 3.02, "hi": 3.97, "width": 0.95, "touches_lo": 4, "touches_hi": 4}


def _bar(label: str, close: float, low: float | None = None, high: float | None = None) -> dict:
    dt = datetime.strptime(label, "%Y-%m-%d %H:%M").replace(tzinfo=MSK)
    row = {
        "time": label,
        "timestampMs": int(dt.timestamp() * 1000),
        "spread": close,
    }
    if low is not None:
        row["spread_low"] = low
    if high is not None:
        row["spread_high"] = high
    return row


def test_fmt_level_and_clock():
    assert fmt_level(3.2) == "3,20"
    assert fmt_level(4.0) == "4,00"
    dt = datetime(2026, 9, 1, 8, 50, tzinfo=MSK)
    assert fmt_hhmm_msk(dt) == "8:50"
    assert fmt_hhmm_msk(datetime(2026, 9, 1, 10, 5, tzinfo=MSK)) == "10:05"


def test_test_lo_long_sticky_start_time():
    bars = [
        _bar("2026-09-01 08:49", 3.40),
        _bar("2026-09-01 08:50", 3.22),
        _bar("2026-09-01 08:51", 3.21),
        _bar("2026-09-01 08:52", 3.18),
    ]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["ok"] is True
    assert out["kind"] == "test_lo"
    assert out["side"] == "Long"
    assert out["label"] == "Тест низа Long 3,20 · 8:50"
    assert out["since_hm"] == "8:50"


def test_new_episode_after_leaving_edge():
    bars = [
        _bar("2026-09-01 08:50", 3.22),
        _bar("2026-09-01 08:51", 3.50),
        _bar("2026-09-01 08:54", 3.22),
    ]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["label"] == "Тест низа Long 3,20 · 8:54"


def test_test_hi_long():
    bars = [
        _bar("2026-09-01 07:12", 3.98),
        _bar("2026-09-01 07:13", 3.97),
    ]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["label"] == "Тест верха Long 4,00 · 7:12"
    assert out["kind"] == "test_hi"


def test_break_below_long():
    bars = [_bar("2026-09-01 09:03", 3.10)]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["kind"] == "break_lo"
    assert out["label"] == "Вынос ниже Long 3,20 · 9:03"


def test_break_above_long_while_near_band():
    bars = [_bar("2026-09-01 09:10", 4.15)]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["kind"] == "break_hi"
    assert out["side"] == "Long"
    assert out["label"] == "Вынос выше Long 4,00 · 9:10"


def test_inside_no_fake_test():
    bars = [_bar("2026-09-01 11:00", 3.55)]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["kind"] == "inside"
    assert out["label"] == "Внутри Long 3,20…4,00"
    assert out["since_hm"] is None
    assert "·" not in out["label"]


def test_wick_counts_as_touch():
    bars = [_bar("2026-09-01 08:50", 3.40, low=3.18, high=3.42)]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["kind"] == "test_lo"
    assert out["label"] == "Тест низа Long 3,20 · 8:50"


def test_close_only_when_no_wick_fields():
    bars = [_bar("2026-09-01 08:50", 3.22)]
    assert "spread_low" not in bars[0]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["kind"] == "test_lo"


def test_eps_boundary_close_at_lo_minus_eps_is_test():
    # 3.20 − 0.08 = 3.12 — ещё не вынос
    assert classify_vs_band(close=3.12, low=3.12, high=3.12, lo=3.2, hi=4.0) == "test_lo"
    assert classify_vs_band(close=3.11, low=3.11, high=3.11, lo=3.2, hi=4.0) == "break_lo"


def test_sweep_line_on_big_dump():
    bars = [
        _bar("2026-09-01 07:12", 3.98),
        _bar("2026-09-01 08:20", 3.60),
        _bar("2026-09-01 08:50", 3.22),
    ]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["sweep"] == "Сегодня макс 3,98 · 7:12 · мин 3,22 · 8:50"
    assert out["day_max_hm"] == "7:12"
    assert out["day_min_hm"] == "8:50"
    assert out["label"] == "Тест низа Long 3,20 · 8:50"


def test_extrema_on_small_move():
    bars = [
        _bar("2026-09-01 08:00", 3.40),
        _bar("2026-09-01 08:50", 3.22),
    ]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["sweep"] == "Сегодня макс 3,40 · 8:00 · мин 3,22 · 8:50"


def test_short_band_test_hi():
    bars = [_bar("2026-09-01 14:03", 6.15)]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["side"] == "Short"
    assert out["kind"] == "test_hi"
    assert out["label"] == "Тест верха Short 6,20 · 14:03"


def test_between_bands_no_fake_test():
    bars = [_bar("2026-09-01 12:00", 4.80)]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["kind"] == "between"
    assert out["label"] == "Между полосами 4,00…5,80"
    assert out["since_hm"] is None


def test_deep_crash_stays_long_break():
    bars = [_bar("2026-09-01 10:00", 2.50)]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["side"] == "Long"
    assert out["kind"] == "break_lo"
    assert out["label"].startswith("Вынос ниже Long 3,20")


def test_pick_band_near_threshold():
    assert pick_trade_band(4.0 + BAND_NEAR_PP, long_lo=3.2, long_hi=4.0, short_lo=5.8, short_hi=6.2)[0] == "Long"
    assert pick_trade_band(4.0 + BAND_NEAR_PP + 0.01, long_lo=3.2, long_hi=4.0, short_lo=5.8, short_hi=6.2)[0] is None


def test_empty_bars():
    out = build_spread_situation([], levels=LEVELS)
    assert out["ok"] is False
    assert out["label"] == "—"


def test_one_mark_bar_gives_now_label():
    out = build_spread_situation(
        [{"spread": 3.21, "time": "2026-09-02 10:07:00", "timestampMs": 0}],
        levels=LEVELS,
    )
    assert out["ok"] is True
    assert out["label"] and out["label"] != "—"


def test_uses_passed_levels_not_hardcoded_only():
    bars = [_bar("2026-09-01 08:50", 2.22)]
    custom = {
        "enter_narrow": 2.2,
        "exit_narrow": 3.0,
        "exit_wide": 5.8,
        "enter_wide": 6.2,
    }
    out = build_spread_situation(bars, levels=custom)
    assert out["label"] == "Тест низа Long 2,20 · 8:50"


def test_eps_constant():
    assert abs(TAPE_EPS_PP - 0.08) < 1e-12


def test_ignores_other_day_for_episode_and_sweep():
    bars = [
        _bar("2026-08-31 18:00", 4.50),
        _bar("2026-09-01 08:50", 3.22),
    ]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["day"] == "2026-09-01"
    assert out["sweep"] == "Сегодня 3,22 · 8:50"
    assert out["since_hm"] == "8:50"


def test_fmt_delta_unicode_minus():
    assert fmt_delta(-0.28) == "−0,28"
    assert fmt_delta(0.52) == "+0,52"
    assert fmt_delta(0.0) == "+0,00"


def test_levels_line_long_example_3_48():
    bars = [_bar("2026-09-01 10:00", 3.48)]
    out = build_spread_situation(bars, levels=LEVELS)
    # Δ≈2%/7 ≈ 0,29 п.п. от входа до ТП
    assert out["levels_line"] == "до Long 3,20 −0,28 п.п. · до ТП ≈ +0,29 (вход+2%)"
    assert out["levels_side"] == "Long"
    assert out["regime_line"] == "режим узкий · входы Long"


def test_levels_line_short_near_wide():
    bars = [_bar("2026-09-01 14:00", 5.90)]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["levels_side"] == "Short"
    assert out["levels_line"] == "до Short 6,20 +0,30 п.п. · до ТП ≈ −0,29 (вход+2%)"
    assert out["regime_line"] == "режим широкий · входы Short"


def test_levels_line_uses_passed_thresholds():
    custom = {
        "enter_narrow": 2.5,
        "exit_narrow": 3.1,
        "exit_wide": 5.8,
        "enter_wide": 6.2,
    }
    line, side, d_enter, d_exit = build_levels_line(
        2.80, enter_n=2.5, exit_n=3.1, exit_w=5.8, enter_w=6.2
    )
    assert side == "Long"
    assert line == "до Long 2,50 −0,30 п.п. · до ТП ≈ +0,29 (вход+2%)"
    assert abs(d_enter - (-0.30)) < 1e-9
    assert abs(d_exit - (3.1 - 2.80)) < 1e-9
    out = build_spread_situation([_bar("2026-09-01 10:00", 2.80)], levels=custom)
    assert out["levels_line"] == line


def test_regime_transition_no_new_entries():
    bars = [_bar("2026-09-01 12:00", 4.50)]
    out = build_spread_situation(bars, levels=LEVELS)
    assert out["regime_line"] == "режим переход · новых входов нет"
    assert build_regime_line(3.5)[0] == "режим переход · новых входов нет"
    assert build_regime_line(5.5)[0] == "режим переход · новых входов нет"


def test_pick_auto_side_closer_to_short():
    side, enter, exit_lv = pick_auto_levels_side(
        5.50, enter_n=3.2, exit_n=4.0, exit_w=5.8, enter_w=6.2
    )
    assert side == "Short"
    assert enter == 6.2
    assert exit_lv == 5.8


def _bounce_1m(day: str, start: str, n: int, lo: float, hi: float) -> list[dict]:
    dt = datetime.strptime(f"{day} {start}", "%Y-%m-%d %H:%M").replace(tzinfo=MSK)
    rows = []
    for i in range(n):
        near_lo = i % 4 in (0, 1)
        sp = lo + 0.03 if near_lo else hi - 0.03
        low = lo if near_lo else sp - 0.02
        high = hi if not near_lo else sp + 0.02
        t = dt + timedelta(minutes=i)
        rows.append(_bar(t.strftime("%Y-%m-%d %H:%M"), sp, low=low, high=high))
    return rows


def test_nested_shelf_from_1m_vs_corridor():
    bars = _bounce_1m("2026-09-01", "07:00", 80, 3.20, 3.45)
    out = build_spread_situation(bars, levels=LEVELS, corridor=WIDE_CORR)
    assert out["shelf_line"] is not None
    assert out["shelf_line"].startswith("полка 3,20…3,45 с 7:00")
    assert "касания" in out["shelf_line"]
    assert out["corridor_line"] == "коридор 3,02…3,97"


def test_no_invented_shelf_when_1m_fills_corridor():
    bars = _bounce_1m("2026-09-01", "07:00", 80, 3.05, 3.90)
    out = build_spread_situation(bars, levels=LEVELS, corridor=WIDE_CORR)
    assert out["shelf_line"] is None
    assert out["corridor_line"] == "коридор 3,02…3,97"


def test_payload_inner_shelf_shown_without_inventing():
    bars = [_bar("2026-09-01 10:00", 3.30)]
    corr = {
        "lo": 3.20,
        "hi": 3.45,
        "inner_shelf": True,
        "outer_lo": 3.02,
        "outer_hi": 3.97,
        "touches_lo": 3,
        "touches_hi": 2,
        "since_hm": "7:00",
        "title": "Коридор 3.20…3.45% (расчёт: calculated; текущая узкая полка)",
    }
    out = build_spread_situation(bars, levels=LEVELS, corridor=corr)
    assert out["shelf_line"] == "полка 3,20…3,45 с 7:00 (касания низ 3 · верх 2)"
    assert out["corridor_line"] == "коридор 3,02…3,97"


def test_nested_inner_dict_in_corridor_payload():
    bars = [_bar("2026-09-01 11:00", 3.28)]
    corr = {
        "lo": 3.02,
        "hi": 3.97,
        "inner": {
            "lo": 3.20,
            "hi": 3.45,
            "touches_lo": 4,
            "touches_hi": 3,
            "since_hm": "7:00",
        },
    }
    out = build_spread_situation(bars, levels=LEVELS, corridor=corr)
    assert out["shelf_line"].startswith("полка 3,20…3,45")
    assert out["corridor_line"] == "коридор 3,02…3,97"


def test_corridor_only_when_no_shelf_and_no_payload_inner():
    bars = [_bar("2026-09-01 11:00", 3.50)]
    out = build_spread_situation(bars, levels=LEVELS, corridor=WIDE_CORR)
    assert out["shelf_line"] is None
    assert out["corridor_line"] == "коридор 3,02…3,97"


def test_empty_bars_has_no_extra_lines():
    out = build_spread_situation([], levels=LEVELS)
    assert out["ok"] is False
    assert out["levels_line"] is None
    assert out["regime_line"] is None
    assert out["shelf_line"] is None
    assert out["corridor_line"] is None
