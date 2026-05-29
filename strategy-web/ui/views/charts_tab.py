import streamlit as st

from charts import (
    make_equity_chart,
    make_equity_compare,
    make_spread_chart,
    make_zscore_chart,
)


def render_charts(packs: list[dict], compare_mode: bool) -> None:
    if compare_mode:
        st.plotly_chart(
            make_equity_compare([(p["result"].equity_frame(), p["label"]) for p in packs]),
            use_container_width=True,
        )
        for pack in packs:
            result = pack["result"]
            markers = result.trade_markers_frame()
            st.plotly_chart(
                make_equity_chart(result.equity_frame(), markers, pack["label"]),
                use_container_width=True,
            )
            st.plotly_chart(
                make_spread_chart(result.spread_frame(), markers, pack["label"]),
                use_container_width=True,
            )
            st.plotly_chart(
                make_zscore_chart(
                    result.zscore_frame(), markers, pack["entry"], pack["exit_z"], pack["label"]
                ),
                use_container_width=True,
            )
        return

    pack = packs[0]
    result = pack["result"]
    markers = result.trade_markers_frame()
    if result.unrealized_pnl_rub != 0:
        st.info(f"Открытая позиция: unrealized {result.unrealized_pnl_rub:,.0f} ₽")
    st.plotly_chart(make_equity_chart(result.equity_frame(), markers), use_container_width=True)
    st.plotly_chart(make_spread_chart(result.spread_frame(), markers), use_container_width=True)
    st.plotly_chart(
        make_zscore_chart(result.zscore_frame(), markers, pack["entry"], pack["exit_z"]),
        use_container_width=True,
    )
