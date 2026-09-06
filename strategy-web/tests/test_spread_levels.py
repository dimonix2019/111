"""Spread-% level signals (tip1m AUTO primary path)."""



from live.constants import DEFAULT_SPREAD_ENTER_WIDE

from live.signals import Position, Signal

from live.spread_levels import (

    SpreadLevels,

    determine_spread_level_signal,

    parse_spread_level_mode,

)

from live.tip_touch_signals import collect_tip1m_sim_edges





def _tip(td: str, ms: int, spread: float, z: float = 0.0) -> dict:

    return {

        "tradeDate": td,

        "timestampMs": ms,

        "spreadPercent": spread,

        "zScore": z,

    }





def test_parse_spread_level_mode_default_on():

    assert parse_spread_level_mode(None) is True

    assert parse_spread_level_mode({}) is True

    assert parse_spread_level_mode({"spread_level_mode": False}) is False

    assert parse_spread_level_mode({"spread_level_mode": "0"}) is False





def test_prod_short_enter_default_is_61():

    assert DEFAULT_SPREAD_ENTER_WIDE == 6.1





def test_enter_short_cross_up_wide():

    lv = SpreadLevels()

    # Cross 6.1 from below while landing in wide (6.17 must enter)

    assert (

        determine_spread_level_signal(6.0, 6.1, Position.FLAT, lv)

        == Signal.ENTER_SHORT

    )

    assert (

        determine_spread_level_signal(6.05, 6.17, Position.FLAT, lv)

        == Signal.ENTER_SHORT

    )

    # Already at/above enter — no re-cross

    assert determine_spread_level_signal(6.1, 6.2, Position.FLAT, lv) == Signal.NONE

    assert determine_spread_level_signal(6.3, 6.4, Position.FLAT, lv) == Signal.NONE





def test_enter_long_cross_down_narrow():

    lv = SpreadLevels()

    assert (

        determine_spread_level_signal(3.3, 3.2, Position.FLAT, lv) == Signal.ENTER_LONG

    )

    assert determine_spread_level_signal(3.1, 3.0, Position.FLAT, lv) == Signal.NONE





def test_transition_no_enter():

    lv = SpreadLevels()

    # Cross through 6.0 but somehow still not wide? 6.0 is >5.5 so wide.

    # Transition zone: crossing 5.0 → 5.4 does nothing; enter needs 6.1.

    assert determine_spread_level_signal(4.0, 5.0, Position.FLAT, lv) == Signal.NONE

    # Touch enter_narrow from transition (4.0 → 3.2): lands in narrow → Long OK

    assert (

        determine_spread_level_signal(4.0, 3.2, Position.FLAT, lv) == Signal.ENTER_LONG

    )





def test_exit_short_hysteresis():

    lv = SpreadLevels()

    assert (

        determine_spread_level_signal(5.9, 5.8, Position.SHORT, lv) == Signal.EXIT_SHORT

    )

    # Still above exit — hold

    assert determine_spread_level_signal(6.0, 5.9, Position.SHORT, lv) == Signal.NONE





def test_exit_long_hysteresis():

    lv = SpreadLevels()

    assert (

        determine_spread_level_signal(3.9, 4.0, Position.LONG, lv) == Signal.EXIT_LONG

    )

    assert determine_spread_level_signal(3.5, 3.8, Position.LONG, lv) == Signal.NONE





def test_exit_in_transition_while_holding():

    """Transition can still EXIT if exit level hit."""

    lv = SpreadLevels()

    # Short entered above 6.2; now S drops through 5.8 (transition zone)

    assert (

        determine_spread_level_signal(5.9, 5.7, Position.SHORT, lv) == Signal.EXIT_SHORT

    )

    # Long: cross up through 4.0 (transition)

    assert (

        determine_spread_level_signal(3.9, 4.1, Position.LONG, lv) == Signal.EXIT_LONG

    )





def test_ignore_opposite_while_in_position():

    lv = SpreadLevels()

    # Holding Short — Long entry cross ignored

    assert (

        determine_spread_level_signal(3.3, 3.0, Position.SHORT, lv) == Signal.NONE

    )

    # Holding Long — Short entry cross ignored

    assert (

        determine_spread_level_signal(6.1, 6.3, Position.LONG, lv) == Signal.NONE

    )





def test_collect_edges_spread_level_roundtrip():

    # Mon weekday session times

    base = "2026-07-27 "

    tips = [

        _tip(base + "12:00", 1_000_000, 6.0),

        _tip(base + "12:01", 1_060_000, 6.25),  # ENTER_SHORT

        _tip(base + "12:02", 1_120_000, 6.0),

        _tip(base + "12:03", 1_180_000, 5.75),  # EXIT_SHORT

        _tip(base + "12:04", 1_240_000, 3.5),

        _tip(base + "12:05", 1_300_000, 3.15),  # ENTER_LONG

        _tip(base + "12:06", 1_360_000, 3.5),

        _tip(base + "12:07", 1_420_000, 4.05),  # EXIT_LONG

    ]

    # Fix consecutive 1m ms

    t0 = 1_720_000_000_000

    for i, t in enumerate(tips):

        t["timestampMs"] = t0 + i * 60_000



    edges = collect_tip1m_sim_edges(

        tips, 1.6, 1.3, respect_live_signal=False, spread_level_mode=True

    )

    sigs = [e["signal"] for e in edges]

    assert sigs == [

        Signal.ENTER_SHORT.value,

        Signal.EXIT_SHORT.value,

        Signal.ENTER_LONG.value,

        Signal.EXIT_LONG.value,

    ]





def test_collect_edges_transition_no_phantom_z():

    """With spread levels ON, Z extremes in transition must not enter."""

    t0 = 1_720_000_000_000

    tips = [

        _tip("2026-07-27 12:00", t0, 4.0, z=-2.0),

        _tip("2026-07-27 12:01", t0 + 60_000, 4.2, z=-2.5),

    ]

    edges = collect_tip1m_sim_edges(

        tips, 1.0, 0.7, respect_live_signal=False, spread_level_mode=True

    )

    assert edges == []


