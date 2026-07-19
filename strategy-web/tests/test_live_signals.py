"""Parity smoke tests for live signal + lot sizing."""

from live.lot_sizing import compute_spread_quantity_lots
from live.signals import Position, Signal, determine_z_signal


def test_enter_long_on_cross():
    assert determine_z_signal(-1.2, -1.4, Position.FLAT, 1.3, 1.2) == Signal.ENTER_LONG


def test_enter_short_on_cross():
    assert determine_z_signal(1.2, 1.4, Position.FLAT, 1.3, 1.2) == Signal.ENTER_SHORT


def test_exit_long():
    assert determine_z_signal(-1.3, -1.1, Position.LONG, 1.3, 1.2) == Signal.EXIT_LONG


def test_lot_sizing_cash_only():
    r = compute_spread_quantity_lots(cash_rub=50_000, price_tatn=600, price_tatnp=560)
    assert r.quantity_lots >= 1
    assert r.go_per_lot_rub > 0
