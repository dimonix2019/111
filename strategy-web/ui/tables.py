import pandas as pd


def pf_txt(profit_factor: float) -> str:
    return "∞" if profit_factor == float("inf") else f"{profit_factor:.2f}"


def trades_table(result) -> pd.DataFrame:
    rows = []
    for i, trade in enumerate(result.trades, start=1):
        try:
            hold_h = (
                pd.to_datetime(trade.exit_time) - pd.to_datetime(trade.entry_time)
            ).total_seconds() / 3600.0
            hold_h = max(0.0, hold_h)
        except Exception:
            hold_h = 0.0
        rows.append(
            {
                "№": i,
                "направление": trade.direction.value,
                "вход": trade.entry_time,
                "выход": trade.exit_time,
                "часов": round(hold_h, 1),
                "spread вход": round(trade.entry_spread, 3),
                "spread выход": round(trade.exit_spread, 3),
                "Z вход": round(trade.entry_z, 3),
                "Z выход": round(trade.exit_z, 3),
                "PnL pts": round(trade.pnl_spread_pts, 3),
                "PnL ₽": round(trade.pnl_rub, 0),
            }
        )
    return pd.DataFrame(rows)
