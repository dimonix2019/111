"""Unit tests for pref hang screener (без сети — синтетический ряд S%)."""

from live.pref_hang_screener import (
    BASKET_RULES_RU,
    PAIRS,
    analyze_pair_spreads,
    classify_status,
    clear_cache,
)


def test_pairs_are_four_basket():
    assert len(PAIRS) == 4
    ids = {(p["ord"], p["pref"]) for p in PAIRS}
    assert ids == {
        ("TATN", "TATNP"),
        ("SNGS", "SNGSP"),
        ("RTKM", "RTKMP"),
        ("MTLR", "MTLRP"),
    }
    assert "не больше 2" in BASKET_RULES_RU
    assert "Мечел" in BASKET_RULES_RU


def test_classify_normal_near_median():
    # узкий шум вокруг 5
    vals = [5.0 + (i % 3) * 0.05 for i in range(40)]
    st = classify_status(vals, median=5.0, p10=4.0, p90=6.0)
    assert st["status"] == "норма"


def test_classify_edge_at_p10():
    vals = [5.0] * 30 + [0.5]
    st = classify_status(vals, median=5.0, p10=1.0, p90=8.0)
    assert st["status"] == "край"


def test_classify_shelf_long_hang_away():
    # долго сидим у 1.5 при медиане 5
    vals = [5.0] * 10 + [1.5] * 12
    st = classify_status(vals, median=5.0, p10=0.5, p90=8.0)
    assert st["status"] == "полка"
    assert st["days_hang"] >= 8


def test_classify_compression_recent():
    vals = [6.0] * 20 + [2.0, 2.1, 1.9, 2.0]
    st = classify_status(vals, median=5.5, p10=1.0, p90=8.0)
    assert st["status"] in ("сжатие", "край", "полка")
    assert st["days_compression"] >= 1


def test_sngs_negative_zone_vs_own_median():
    """SNGS: отрицательный спред — норма; статус от своей медианы ≈ −50."""
    from datetime import date, timedelta

    base = date(2024, 1, 2)
    # Широкая «своя» зона (−58…−42), текущий уровень у медианы −51
    spreads = []
    for i in range(80):
        # пила вокруг медианы, без ухода в хвосты
        s = -51.0 + ((i % 7) - 3) * 0.4
        spreads.append(((base + timedelta(days=i)).isoformat(), s))
    row = analyze_pair_spreads(
        spreads,
        {"ord": "SNGS", "pref": "SNGSP", "name": "Сургутнефтегаз"},
    )
    assert row["ok"] is True
    assert row["s_now"] < 0
    assert row["median"] < -40
    assert row["status"] == "норма"
    assert row["note"] and "отрицательн" in row["note"].lower()


def test_clear_cache_noop():
    clear_cache()
