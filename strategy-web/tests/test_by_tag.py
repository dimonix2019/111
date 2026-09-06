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
    assert "swing" not in by_tag
