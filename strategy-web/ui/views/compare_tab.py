import pandas as pd
import streamlit as st

from charts import make_equity_compare
from ui.tables import pf_txt


def render_compare(packs: list[dict], compare_mode: bool) -> None:
    if not compare_mode:
        st.info("Включите «Сравнение A / B» в sidebar для этой вкладки.")
        return

    stats_a = packs[0]["result"].stats()
    stats_b = packs[1]["result"].stats()
    st.dataframe(
        pd.DataFrame(
            {
                "метрика": [
                    "PnL ₽",
                    "Сделок",
                    "Win %",
                    "Max DD ₽",
                    "Profit Factor",
                    "Комиссии ₽",
                    "Overnight ₽",
                ],
                packs[0]["label"]: [
                    f"{stats_a['total_pnl_rub']:,.0f}",
                    stats_a["trade_count"],
                    f"{stats_a['win_rate_pct']:.1f}",
                    f"{stats_a['max_drawdown_rub']:,.0f}",
                    pf_txt(stats_a["profit_factor"]),
                    f"{stats_a['total_commission_rub']:,.0f}",
                    f"{stats_a['total_overnight_rub']:,.0f}",
                ],
                packs[1]["label"]: [
                    f"{stats_b['total_pnl_rub']:,.0f}",
                    stats_b["trade_count"],
                    f"{stats_b['win_rate_pct']:.1f}",
                    f"{stats_b['max_drawdown_rub']:,.0f}",
                    pf_txt(stats_b["profit_factor"]),
                    f"{stats_b['total_commission_rub']:,.0f}",
                    f"{stats_b['total_overnight_rub']:,.0f}",
                ],
            }
        ),
        use_container_width=True,
        hide_index=True,
    )
    st.plotly_chart(
        make_equity_compare([(p["result"].equity_frame(), p["label"]) for p in packs]),
        use_container_width=True,
    )
