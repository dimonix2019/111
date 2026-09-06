from live.margin_headroom import compute_margin_call_headroom, enrich_margin_payload


def test_margin_headroom_green_zone():
    hr = compute_margin_call_headroom(
        liquid_portfolio_rub=130_000.0,
        corrected_margin_rub=100_000.0,
    )
    assert hr is not None
    assert hr["free_rub"] == 30_000.0
    assert hr["pct"] == 30.0
    assert hr["zone"] == "yellow"


def test_margin_headroom_above_green():
    hr = compute_margin_call_headroom(
        liquid_portfolio_rub=140_000.0,
        corrected_margin_rub=100_000.0,
    )
    assert hr is not None
    assert hr["zone"] == "green"
    assert hr["pct"] == 40.0


def test_margin_headroom_red_zone():
    hr = compute_margin_call_headroom(
        liquid_portfolio_rub=105_000.0,
        corrected_margin_rub=100_000.0,
    )
    assert hr is not None
    assert hr["zone"] == "red"
    assert hr["pct"] == 5.0


def test_enrich_margin_payload_adds_headroom():
    raw = {
        "liquid_portfolio_rub": 120_000.0,
        "corrected_margin_rub": 80_000.0,
        "starting_margin_rub": 80_000.0,
    }
    out = enrich_margin_payload(raw)
    assert out is not None
    assert "headroom" in out
    assert out["headroom"]["free_rub"] == 40_000.0
    assert out["headroom"]["zone"] == "green"
