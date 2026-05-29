import streamlit as st

from ui.views.charts_tab import render_charts
from ui.views.compare_tab import render_compare
from ui.views.optimization_tab import render_optimization
from ui.views.overview import render_overview
from ui.views.trades_tab import render_trades
from ui.test_mode import render_z_diagnostics_panel
from zsim import ZMode


def render_data_header(hq: dict, z_mode: ZMode) -> None:
    h1, h2, h3, h4 = st.columns(4)
    h1.metric("Баров 15м", hq["bar_count"])
    h2.metric("С", hq["first_ts"][:10] if hq["first_ts"] else "—")
    h3.metric("По", hq["last_ts"][:10] if hq["last_ts"] else "—")
    mode_label = "Rolling 30д" if z_mode == "rolling30" else "Global 255д"
    h4.metric("Режим Z", mode_label)
    if hq["looks_full_255d"]:
        st.markdown(
            f'<span class="badge-ok">Полный ряд · {hq["expected_trades_hint"]}</span>',
            unsafe_allow_html=True,
        )
    else:
        st.markdown(
            f'<span class="badge-warn">{hq["expected_trades_hint"]}</span>',
            unsafe_allow_html=True,
        )


def render_results(
    packs: list[dict],
    hq: dict,
    compare_mode: bool,
    *,
    z_mode: ZMode = "rolling30",
    z_diag: dict | None = None,
    z_compare=None,
) -> None:
    render_data_header(hq, z_mode)
    if z_diag:
        render_z_diagnostics_panel(z_diag)
    if z_mode == "rolling30" and z_compare is not None:
        st.subheader("Сравнение с Global")
        st.dataframe(z_compare, use_container_width=True, hide_index=True)
        st.caption(
            "Один CSV, одни пороги Entry/Exit — разница только в расчёте Z. "
            "Rolling ближе к честному бэктесту и Android PR #26."
        )

    tabs = st.tabs(["Обзор", "Графики", "Сделки", "Сравнение", "Оптимизация"])
    with tabs[0]:
        render_overview(packs, compare_mode)
    with tabs[1]:
        render_charts(packs, compare_mode)
    with tabs[2]:
        render_trades(packs, compare_mode)
    with tabs[3]:
        render_compare(packs, compare_mode)
    with tabs[4]:
        render_optimization()
