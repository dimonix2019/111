"""PostOrder order_id uniqueness (T‑Invest duplicate error)."""
from __future__ import annotations

from live.tinvest import TInvestClient, _mint_order_idempotency_key


def test_order_bodies_unique_order_ids():
    client = TInvestClient.__new__(TInvestClient)
    bodies = client._order_bodies(
        account_id="acc",
        instrument_id="BBG004730N88",
        direction="ORDER_DIRECTION_SELL",
        quantity_lots=1,
    )
    assert len(bodies) >= 6
    ids: list[str] = []
    for b in bodies:
        oid = b.get("order_id") or b.get("orderId")
        assert oid, b
        ids.append(str(oid))
    assert len(ids) == len(set(ids))


def test_refresh_order_idempotency_key_changes_both_styles():
    snake = {"order_id": "old"}
    camel = {"orderId": "old"}
    TInvestClient._refresh_order_idempotency_key(snake)
    TInvestClient._refresh_order_idempotency_key(camel)
    assert snake["order_id"] != "old"
    assert camel["orderId"] != "old"


def test_mint_order_idempotency_key():
    body = {"orderId": "a", "order_id": "a"}
    a = _mint_order_idempotency_key(body)
    b = _mint_order_idempotency_key(body)
    assert a != b
    assert body["orderId"] == b
    assert body["order_id"] == b
