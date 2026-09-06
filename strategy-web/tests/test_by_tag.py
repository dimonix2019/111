"""Агрегация by_tag для бублика Теста — без смены логики сима."""

from replay.tip_touch import attach_by_tag, _by_tag_bucket


def test_by_tag_bucket_skips_swing():
    assert _by_tag_bucket({"tag": "swing"}) is None
    assert _by_tag_bucket({"tag": "main", "source": "качалка"}) is None
    assert _by_tag_bucket({"tag": "shelf_ff"}) == "shelf_ff"
    assert _by_tag_bucket({"tag": "addon"}) == "addon"


def test_attach_by_tag_sums_closed_only():
    result = attach_by_tag(
        {
            "trades": [
                {"tag": "main", "status": "Закрыта", "net": 100},
                {"tag": "addon", "status": "Закрыта", "net": 40},
                {"tag": "extra", "status": "Открыта", "net": 99},
                {"tag": "swing", "status": "Закрыта", "net": 50},
                {"tag": "shelf_ff", "status": "Закрыта", "net": -10},
            ],
            "summary": {"trades": 3, "pnlRub": 130},
        }
    )
    by_tag = result["summary"]["by_tag"]
    assert by_tag["main"] == {"n": 1, "pnlRub": 100.0}
    assert by_tag["addon"] == {"n": 1, "pnlRub": 40.0}
    assert by_tag["extra"] == {"n": 0, "pnlRub": 0.0}
    assert by_tag["shelf_ff"] == {"n": 1, "pnlRub": -10.0}
    assert by_tag["weekend"] == {"n": 0, "pnlRub": 0.0}
    assert "swing" not in by_tag


def test_weekend_entry_split_from_tag_buckets():
    """Сб/вс МСК — отдельный бакет; ₽ не остаются в База/добор."""
    result = attach_by_tag(
        {
            "trades": [
                {
                    "tag": "main",
                    "status": "Закрыта",
                    "net": 100,
                    "entryDate": "2026-01-12 10:15",
                },
                {
                    "tag": "main",
                    "status": "Закрыта",
                    "net": 50,
                    "entryDate": "2026-01-10 11:00",
                },
                {
                    "tag": "addon",
                    "status": "Закрыта",
                    "net": 20,
                    "entryDate": "2026-01-11 12:00",
                },
                {
                    "tag": "extra",
                    "status": "Закрыта",
                    "net": 8,
                    "entryDate": "2026-01-09 15:00",
                },
                {
                    "tag": "main",
                    "status": "Открыта",
                    "net": 99,
                    "entryDate": "2026-01-10 10:00",
                },
            ],
            "summary": {"pnlRub": 178},
        }
    )
    by_tag = result["summary"]["by_tag"]
    assert by_tag["main"] == {"n": 1, "pnlRub": 100.0}
    assert by_tag["addon"] == {"n": 0, "pnlRub": 0.0}
    assert by_tag["extra"] == {"n": 1, "pnlRub": 8.0}
    assert by_tag["weekend"] == {"n": 2, "pnlRub": 70.0}
    total = sum(v["pnlRub"] for v in by_tag.values())
    assert total == 178.0


def test_shelf_not_dumped_into_main():
    """Пол–потолок пишет в кошелёк базы (src «база») — столбик полка, не База."""
    assert _by_tag_bucket({"tag": "пол–потолок", "source": "база"}) == "shelf_ff"
    assert _by_tag_bucket({"tag": "пол-потолок", "source": "база"}) == "shelf_ff"
    assert _by_tag_bucket({"source": "пол–потолок"}) == "shelf_ff"
    assert _by_tag_bucket({"tag": "", "source": "база", "exitReason": "shelf_edge"}) == "shelf_ff"
    assert _by_tag_bucket({"tag": "main", "source": "база", "exitReason": "shelf_displace"}) == "main"
    result = attach_by_tag(
        {
            "trades": [
                {
                    "tag": "пол–потолок",
                    "source": "база",
                    "status": "Закрыта",
                    "net": 250,
                    "entryDate": "2026-01-12 10:00",
                },
                {
                    "tag": "main",
                    "source": "база",
                    "status": "Закрыта",
                    "net": 100,
                    "entryDate": "2026-01-12 11:00",
                },
                {
                    "tag": "shelf_ff",
                    "source": "пол–потолок",
                    "status": "Закрыта",
                    "net": 15,
                    "entryDate": "2026-01-10 11:00",
                },
            ],
            "summary": {"pnlRub": 365},
        }
    )
    by_tag = result["summary"]["by_tag"]
    assert by_tag["shelf_ff"] == {"n": 1, "pnlRub": 250.0}
    assert by_tag["main"] == {"n": 1, "pnlRub": 100.0}
    assert by_tag["weekend"] == {"n": 1, "pnlRub": 15.0}
