"""До/После журнала Теста: одна цепь по №, минус не со своей базой."""

from replay.tip_touch import apply_journal_account_chain


def test_plus_minus_plus_sequential_balance():
    """Три сделки + / − / + как на скрине: До следующей = После предыдущей."""
    result = {
        "params": {"notional": 43_150.0},
        "trades": [
            {
                "index": 245,
                "status": "Закрыта",
                "net": 732.0,
                "accountBefore": 43_150.0,
                "accountAfter": 43_882.0,
            },
            {
                "index": 250,
                "status": "Закрыта",
                "net": -5_500.0,
                # чужой базис: После−Чист. в момент выхода с другими ногами
                "accountBefore": 55_420.0,
                "accountAfter": 49_920.0,
            },
            {
                "index": 254,
                "status": "Закрыта",
                "net": 928.0,
                "accountBefore": 43_882.0,
                "accountAfter": 44_810.0,
            },
        ],
    }
    apply_journal_account_chain(result)
    by_i = {t["index"]: t for t in result["trades"]}

    assert by_i[245]["accountBefore"] == 43_150.0
    assert by_i[245]["accountAfter"] == 43_882.0
    assert by_i[250]["accountBefore"] == 43_882.0
    assert by_i[250]["accountAfter"] == 38_382.0
    assert by_i[254]["accountBefore"] == 38_382.0
    assert by_i[254]["accountAfter"] == 39_310.0
    assert abs(by_i[250]["accountAfter"] - (by_i[250]["accountBefore"] + by_i[250]["net"])) < 1e-9
    assert abs(by_i[254]["accountAfter"] - (by_i[254]["accountBefore"] + by_i[254]["net"])) < 1e-9


def test_journal_chain_skips_live_account_snapshot():
    result = {
        "params": {"notional": 10_000.0, "asLive": True},
        "trades": [
            {
                "index": 1,
                "status": "Закрыта",
                "net": -3.82,
                "netFromAccount": True,
                "accountBefore": 10_000.0,
                "accountAfter": 9_996.18,
            },
        ],
    }
    apply_journal_account_chain(result)
    t = result["trades"][0]
    assert t["accountBefore"] == 10_000.0
    assert t["accountAfter"] == 9_996.18


def test_addon_overlap_chain_by_index():
    """Пересекающиеся ноги: у каждой свой кошелёк, не общая цепь."""
    import numpy as np
    from replay.tip_touch import PreparedTips, run_base_plus_addon

    dates = [
        "2024-01-02 10:00",
        "2024-01-02 10:01",
        "2024-01-02 10:02",
        "2024-01-02 10:03",
        "2024-01-02 10:04",
        "2024-01-02 10:05",
        "2024-01-02 10:06",
    ]
    spread = [4.5, 3.2, 2.0, 3.2, 4.0, 4.0, 4.0]
    n = len(dates)
    prep = PreparedTips(
        ts_ms=np.arange(n, dtype=np.int64) * 60_000,
        z=np.zeros(n, dtype=np.float64),
        spread=np.asarray(spread, dtype=np.float64),
        day_ord=np.zeros(n, dtype=np.int32),
        session=np.ones(n, dtype=bool),
        trade_dates=list(dates),
        edge_i=np.arange(1, n, dtype=np.int32),
        n=n,
    )
    r = run_base_plus_addon(prep=prep, slip=0.02, notional=10_000.0)
    closed = [
        t
        for t in r["trades"]
        if t.get("status") != "Открыта" and t.get("net") is not None
    ]
    assert len(closed) == 2
    main = next(t for t in closed if t["tag"] == "main")
    addon = next(t for t in closed if t["tag"] == "addon")
    assert main["accountBefore"] == 10_000.0
    assert addon["accountBefore"] == 10_000.0
    assert abs(main["accountAfter"] - (main["accountBefore"] + float(main["net"]))) < 1e-6
    assert abs(addon["accountAfter"] - (addon["accountBefore"] + float(addon["net"]))) < 1e-6
    # не общая цепь: добор не продолжает «После» базы
    assert addon["wallet"] == "addon"
    assert main["wallet"] == "main"
