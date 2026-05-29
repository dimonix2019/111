import streamlit as st

from config import DEFAULT_M15
from m15_iss_loader import ensure_m15_data
from sim.runner import run_simulation
from ui.results import render_results
from ui.session import init_inputs
from ui.sidebar import render_sidebar
from ui.test_mode import render_test_mode_selector
from ui.theme import setup_page
from zsim import LOOKBACK_DAYS, Z_SCORE_ROLLING_LOOKBACK_DAYS, ZMode


def _ensure_data_on_startup() -> None:
    if st.session_state.get("data_ready"):
        return
    with st.spinner(f"Подготовка истории TATN/TATNP ({LOOKBACK_DAYS} дн.)…"):
        ensure_m15_data(DEFAULT_M15, days=LOOKBACK_DAYS)
    st.session_state["data_ready"] = True
    st.session_state["csv_path"] = str(DEFAULT_M15)


setup_page()
init_inputs()
_ensure_data_on_startup()

z_mode: ZMode = render_test_mode_selector()
sidebar = render_sidebar()

if sidebar.run_clicked:
    try:
        run_simulation(sidebar, z_mode=z_mode)
    except Exception as exc:
        st.exception(exc)

if "sim_packs" in st.session_state:
    render_results(
        st.session_state["sim_packs"],
        st.session_state["sim_hq"],
        st.session_state.get("sim_compare", False),
        z_mode=st.session_state.get("sim_z_mode", z_mode),
        z_diag=st.session_state.get("sim_z_diag"),
        z_compare=st.session_state.get("sim_z_compare"),
    )
else:
    st.info(
        "Настройте параметры слева и нажмите **Запустить симуляцию**. "
        f"По умолчанию **rolling {Z_SCORE_ROLLING_LOOKBACK_DAYS}д** — честный бэктест без look-ahead."
    )
