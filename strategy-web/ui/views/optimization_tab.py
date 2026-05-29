import numpy as np
import streamlit as st

from charts import make_sweep_heatmap
from zsim import load_bars_from_csv, param_sweep


def render_optimization() -> None:
    if "sim_bars_path" not in st.session_state:
        st.info("Сначала запустите симуляцию — heatmap использует те же данные и риск-параметры.")
        return

    kw = st.session_state.get("sim_kw", {})
    st.caption(
        f"Сетка Entry × Exit · номинал {kw.get('notional_rub', 0):,.0f} ₽ · "
        f"плечо ×{kw.get('leverage', 7)} · комиссия {kw.get('commission_pct_per_side', 0.04)}%"
    )
    col1, col2 = st.columns(2)
    with col1:
        entry_min = st.number_input("Entry от", value=0.5, step=0.1, key="hm_e0")
        entry_max = st.number_input("Entry до", value=1.2, step=0.1, key="hm_e1")
        entry_step = st.number_input("Entry шаг", value=0.1, step=0.05, key="hm_es")
    with col2:
        exit_min = st.number_input("Exit от", value=0.3, step=0.1, key="hm_x0")
        exit_max = st.number_input("Exit до", value=0.9, step=0.1, key="hm_x1")
        exit_step = st.number_input("Exit шаг", value=0.1, step=0.05, key="hm_xs")

    if st.button("Построить heatmap", type="primary"):
        entries = [round(float(x), 2) for x in np.arange(entry_min, entry_max + entry_step * 0.5, entry_step)]
        exits = [round(float(x), 2) for x in np.arange(exit_min, exit_max + exit_step * 0.5, exit_step)]
        combos = len(entries) * len(exits)
        if combos > 100:
            st.error(f"Слишком много комбинаций ({combos}). Увеличьте шаг или сузьте диапазон.")
            return
        bars = load_bars_from_csv(st.session_state["sim_bars_path"])
        prog = st.progress(0.0, text="Считаем сетку параметров…")
        sweep = param_sweep(bars, entries, exits, progress_cb=prog.progress, **kw)
        prog.progress(1.0, text="Готово")
        st.session_state["sim_sweep"] = sweep
        best = sweep.loc[sweep["total_pnl_rub"].idxmax()]
        st.success(
            f"Лучшее: Entry {best['entry']}, Exit {best['exit_z']} → "
            f"{best['total_pnl_rub']:,.0f} ₽ ({int(best['trade_count'])} сделок)"
        )

    if "sim_sweep" in st.session_state:
        st.plotly_chart(make_sweep_heatmap(st.session_state["sim_sweep"]), use_container_width=True)
        st.dataframe(
            st.session_state["sim_sweep"].sort_values("total_pnl_rub", ascending=False).head(15),
            use_container_width=True,
            hide_index=True,
        )
