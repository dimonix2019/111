"""Агрегация by_tag и вклад чипа (тумблер) для легенды Теста."""

from replay.tip_touch import (
    _by_tag_bucket,
    _chip_bucket_for_trade,
    _is_weekend_entry_msk,
    apply_chip_contrib,
    attach_by_tag,
)


def test_by_tag_bucket_matches_chip_identity():
    assert _by_tag_bucket({"tag": "swing"}) is None
    assert _by_tag_bucket({"tag": "main", "source": "качалка"}) is None
    assert _chip_bucket_for_trade({"tag": "addon"}) == "addon"
    assert _chip_bucket_for_trade({"tag": "extra"}) == "extra"
    assert _chip_bucket_for_trade({"tag": "shelf_ff"}) == "shelf_ff"
    assert _chip_bucket_for_trade({"tag": "main"}) == "main"
    assert _chip_bucket_for_trade({"tag": "addon", "entryDate": "2026-01-10 11:00"}) == "weekend"


def test_weekend_chip_uses_session_window_not_whole_saturday():
    sat_in = {"tag": "main", "entryDate": "2026-01-10 11:00"}
    sat_before = {"tag": "main", "entryDate": "2026-01-10 09:59"}
    sat_after = {"tag": "main", "entryDate": "2026-01-10 19:00"}
    friday = {"tag": "main", "entryDate": "2026-01-09 11:00"}
    assert _is_weekend_entry_msk(sat_in) is True
    assert _is_weekend_entry_msk(sat_before) is False
    assert _is_weekend_entry_msk(sat_after) is False
    assert _is_weekend_entry_msk(friday) is False
    assert _chip_bucket_for_trade(sat_in) == "weekend"
    assert _chip_bucket_for_trade(sat_before) == "main"
    assert _chip_bucket_for_trade(friday) == "main"


def test_attach_by_tag_includes_open_mtm():
    result = attach_by_tag(
        {
            "trades": [
                {"tag": "main", "status": "Закрыта", "net": 100},
                {"tag": "addon", "status": "Закрыта", "net": 40},
                {"tag": "extra", "status": "Открыта", "openMtm": 12},
                {"tag": "swing", "status": "Закрыта", "net": 50},
                {"tag": "shelf_ff", "status": "Закрыта", "net": -10},
            ],
            "summary": {"trades": 3, "pnlRub": 130},
        }
    )
    by_tag = result["summary"]["by_tag"]
    assert by_tag["main"] == {"n": 1, "pnlRub": 100.0}
    assert by_tag["addon"] == {"n": 1, "pnlRub": 40.0}
    assert by_tag["extra"] == {"n": 1, "pnlRub": 12.0}
    assert by_tag["shelf_ff"] == {"n": 1, "pnlRub": -10.0}
    assert by_tag["weekend"] == {"n": 0, "pnlRub": 0.0}
    assert "swing" not in by_tag


def test_weekend_entry_split_from_tag_buckets():
    """Сб/вс МСК в окне чипа — отдельный бакет; ₽ не остаются в База/добор."""
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
                    "openMtm": 5,
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
    assert by_tag["weekend"] == {"n": 3, "pnlRub": 75.0}


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


def test_chip_contrib_is_on_minus_off_total():
    """Столбик = дельта итога при выключении чипа, не сумма закрытых net."""
    on = {
        "trades": [
            {"tag": "main", "status": "Закрыта", "net": 100, "entryDate": "2026-01-12 10:00"},
            {
                "tag": "addon",
                "status": "Закрыта",
                "net": 40,
                "entryDate": "2026-01-10 11:00",
            },
        ],
        "summary": {"pnlRub": 500.0, "openMtmRub": 0.0},
    }
    off_by_flag = {
        "weekend_trading": {"summary": {"pnlRub": 120.0, "openMtmRub": 0.0}, "trades": []},
        "addon_mode": {"summary": {"pnlRub": 450.0, "openMtmRub": 0.0}, "trades": []},
        "base_mode": {"summary": {"pnlRub": 80.0, "openMtmRub": 0.0}, "trades": []},
    }

    def runner(**kw):
        for flag, payload in off_by_flag.items():
            if kw.get(flag) is False:
                return payload
        return {"summary": {"pnlRub": 500.0, "openMtmRub": 0.0}, "trades": []}

    out = apply_chip_contrib(
        on,
        compound=False,
        sim_kwargs={
            "base_mode": True,
            "addon_mode": True,
            "extreme_addon_mode": False,
            "shelf_floor_ceiling_mode": False,
            "weekend_trading": True,
            "chip_contrib": False,
        },
        runner=runner,
    )
    by_tag = out["summary"]["by_tag"]
    assert out["summary"]["by_tag_mode"] == "chip_delta"
    assert by_tag["weekend"]["pnlRub"] == 380.0
    assert by_tag["addon"]["pnlRub"] == 50.0
    assert by_tag["main"]["pnlRub"] == 420.0
    assert by_tag["extra"]["pnlRub"] == 0.0
    assert by_tag["shelf_ff"]["pnlRub"] == 0.0
    assert by_tag["weekend"]["n"] == 1
    assert by_tag["addon"]["n"] == 0


def test_chip_contrib_off_chip_is_zero():
    result = {
        "trades": [{"tag": "main", "status": "Закрыта", "net": 10}],
        "summary": {"pnlRub": 10.0, "openMtmRub": 0.0},
    }
    out = apply_chip_contrib(
        result,
        compound=False,
        sim_kwargs={
            "base_mode": True,
            "addon_mode": False,
            "extreme_addon_mode": False,
            "shelf_floor_ceiling_mode": False,
            "weekend_trading": False,
            "chip_contrib": False,
        },
        runner=lambda **kw: {"summary": {"pnlRub": 0.0}, "trades": []},
    )
    by_tag = out["summary"]["by_tag"]
    assert by_tag["addon"]["pnlRub"] == 0.0
    assert by_tag["weekend"]["pnlRub"] == 0.0
    assert by_tag["shelf_ff"]["pnlRub"] == 0.0
    assert by_tag["main"]["pnlRub"] == 10.0


def test_chip_contrib_compound_includes_open_mtm():
    on = {
        "trades": [{"tag": "shelf_ff", "status": "Открыта", "openMtm": 80}],
        "summary": {"pnlRub": 20.0, "openMtmRub": 80.0},
    }

    def runner(**kw):
        assert kw.get("shelf_floor_ceiling_mode") is False
        return {"summary": {"pnlRub": 20.0, "openMtmRub": 0.0}, "trades": []}

    out = apply_chip_contrib(
        on,
        compound=True,
        sim_kwargs={
            "base_mode": False,
            "addon_mode": False,
            "extreme_addon_mode": False,
            "shelf_floor_ceiling_mode": True,
            "weekend_trading": False,
            "chip_contrib": False,
        },
        runner=runner,
    )
    assert out["summary"]["by_tag"]["shelf_ff"]["pnlRub"] == 80.0
    assert out["summary"]["by_tag"]["shelf_ff"]["n"] == 1
