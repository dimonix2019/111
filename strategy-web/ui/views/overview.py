import streamlit as st

from ui.tables import pf_txt


def render_overview(packs: list[dict], compare_mode: bool) -> None:
    if compare_mode:
        sa = packs[0]["result"].stats()
        sb = packs[1]["result"].stats()
        c1, c2, c3, c4, c5, c6 = st.columns(6)
        c1.metric("Сделок A / B", f"{sa['trade_count']} / {sb['trade_count']}")
        c2.metric("PnL A", f"{sa['total_pnl_rub']:,.0f} ₽")
        c3.metric("PnL B", f"{sb['total_pnl_rub']:,.0f} ₽")
        diff = sa["total_pnl_rub"] - sb["total_pnl_rub"]
        c4.metric("Разница", f"{diff:,.0f} ₽", delta=f"{diff:,.0f} ₽")
        c5.metric("Win A", f"{sa['win_rate_pct']:.1f}%")
        c6.metric("Win B", f"{sb['win_rate_pct']:.1f}%")
        return

    s = packs[0]["result"].stats()
    c1, c2, c3, c4, c5, c6 = st.columns(6)
    c1.metric("Сделок", s["trade_count"])
    c2.metric("PnL итого", f"{s['total_pnl_rub']:,.0f} ₽")
    c3.metric("Win Rate", f"{s['win_rate_pct']:.1f}%")
    c4.metric("Ср. / сделку", f"{s['avg_pnl_rub']:,.0f} ₽")
    c5.metric("Max просадка", f"{s['max_drawdown_rub']:,.0f} ₽")
    c6.metric("Profit Factor", pf_txt(s["profit_factor"]))

    c7, c8, c9, c10, c11, c12 = st.columns(6)
    c7.metric("Long PnL", f"{s['long_pnl_rub']:,.0f} ₽")
    c8.metric("Short PnL", f"{s['short_pnl_rub']:,.0f} ₽")
    c9.metric("Реализовано", f"{s['realized_pnl_rub']:,.0f} ₽")
    c10.metric("Ср. удержание", f"{s['avg_hold_hours']:.1f} ч")
    c11.metric("Комиссии", f"{s['total_commission_rub']:,.0f} ₽")
    c12.metric("Overnight", f"{s['total_overnight_rub']:,.0f} ₽")
