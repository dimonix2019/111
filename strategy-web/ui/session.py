import streamlit as st

from config import PRESETS, SESSION_DEFAULTS


def init_inputs() -> None:
    for key, value in SESSION_DEFAULTS.items():
        st.session_state.setdefault(key, value)


def apply_preset() -> None:
    preset = PRESETS.get(st.session_state.preset_sel)
    if not preset:
        return
    st.session_state.inp_entry = preset["entry"]
    st.session_state.inp_exit_z = preset["exit_z"]
    st.session_state.inp_notional = preset["notional"]
    st.session_state.inp_leverage = preset["leverage"]
    st.session_state.inp_commission = preset["commission"]
    st.session_state.inp_compound = preset["compound"]
