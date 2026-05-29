"""Выбор режима Z-score на главном экране (не в sidebar)."""

from __future__ import annotations

import streamlit as st

from zsim import Z_SCORE_ROLLING_LOOKBACK_DAYS, ZMode

_MODE_LABELS: dict[ZMode, tuple[str, str]] = {
    "rolling30": (
        f"Тест: Z rolling {Z_SCORE_ROLLING_LOOKBACK_DAYS}д",
        "Без look-ahead — как честный бэктест (рекомендуется)",
    ),
    "global": (
        "Тест: Z global 255д",
        "μ/σ на весь год — Z на хвосте часто прижат (legacy APK)",
    ),
}


def render_test_mode_selector() -> ZMode:
    """Крупный переключатель режима теста под заголовком."""
    st.markdown("#### Вид тестирования")
    choice = st.radio(
        "Режим нормализации Z-score",
        options=["rolling30", "global"],
        format_func=lambda k: _MODE_LABELS[k][0],
        index=0 if st.session_state.get("inp_z_mode", "rolling30") == "rolling30" else 1,
        horizontal=True,
        key="main_z_mode_radio",
        label_visibility="collapsed",
    )
    st.session_state["inp_z_mode"] = choice
    _, caption = _MODE_LABELS[choice]
    st.caption(caption)
    return choice  # type: ignore[return-value]


def render_z_diagnostics_panel(diag: dict) -> None:
    d1, d2, d3, d4 = st.columns(4)
    d1.metric("max |Z|", f"{diag['max_abs_z']:.3f}")
    d2.metric("баров |Z|≥0.8", diag["bars_ge_08"])
    d3.metric("баров |Z|≥0.7", diag["bars_ge_07"])
    d4.metric("Z / spread сейчас", f"{diag['last_z']:.3f} / {diag['last_spread']:.3f}%")
