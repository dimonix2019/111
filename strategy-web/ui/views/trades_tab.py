import streamlit as st

from ui.tables import trades_table


def render_trades(packs: list[dict], compare_mode: bool) -> None:
    if compare_mode:
        col_a, col_b = st.columns(2)
        with col_a:
            st.subheader(packs[0]["label"])
            st.dataframe(trades_table(packs[0]["result"]), use_container_width=True, hide_index=True)
        with col_b:
            st.subheader(packs[1]["label"])
            st.dataframe(trades_table(packs[1]["result"]), use_container_width=True, hide_index=True)
        return

    df = trades_table(packs[0]["result"])
    if df.empty:
        st.info("Нет закрытых сделок")
        return
    st.dataframe(df, use_container_width=True, hide_index=True)
    st.download_button(
        "Скачать CSV",
        df.to_csv(index=False).encode("utf-8-sig"),
        file_name="trades.csv",
        mime="text/csv",
    )
