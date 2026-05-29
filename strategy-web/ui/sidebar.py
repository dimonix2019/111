from dataclasses import dataclass
from pathlib import Path
from typing import Any

import streamlit as st

from config import DEFAULT_M15, PRESETS
from m15_iss_loader import fetch_m15_from_iss, m15_data_status, save_m15_csv
from ui.session import apply_preset


@dataclass
class SidebarState:
    csv_path: Path
    uploaded: Any
    auto_download: bool
    compare_mode: bool
    leverage: float
    commission: float
    compound: bool
    run_clicked: bool
    entry: float = 0.8
    exit_z: float = 0.7
    notional: float = 100_000.0
    entry_a: float = 0.8
    exit_a: float = 0.7
    notional_a: float = 100_000.0
    entry_b: float = 1.0
    exit_b: float = 0.7
    notional_b: float = 100_000.0


def render_sidebar() -> SidebarState:
    csv_path_str = st.sidebar.text_input("Путь к CSV", value=str(DEFAULT_M15))
    csv_path = Path(csv_path_str)
    uploaded = st.sidebar.file_uploader("Загрузить CSV", type=["csv"])

    try:
        data_status = m15_data_status(csv_path)
    except Exception:
        data_status = {"exists": False}

    with st.sidebar:
        st.subheader("Данные MOEX 15м")
        if data_status.get("exists"):
            st.success(
                f"**{data_status.get('row_count', 0)}** баров · "
                f"{str(data_status.get('first_ts', ''))[:10]} … {str(data_status.get('last_ts', ''))[:10]}"
            )
            if data_status.get("file_size"):
                st.caption(f"Размер: {data_status['file_size']}")
            if data_status.get("is_stale"):
                st.warning("Данные устарели (>40ч)")
            else:
                st.markdown('<span class="badge-ok">● Актуально</span>', unsafe_allow_html=True)
        else:
            st.error("Файл не найден")

        if st.button("Дозагрузить с MOEX (255 дн.)"):
            try:
                with st.spinner("Загрузка..."):
                    save_m15_csv(fetch_m15_from_iss(days=255), csv_path)
                st.rerun()
            except Exception as e:
                st.error(str(e))

        st.divider()
        auto_download = st.checkbox("Автозагрузка если нет файла", value=True)
        compare_mode = st.checkbox("Сравнение A / B", value=False)

        st.subheader("Пресет")
        st.selectbox(
            "Профиль параметров",
            list(PRESETS.keys()),
            key="preset_sel",
            on_change=apply_preset,
        )
        if st.session_state.preset_sel != "Свои":
            st.caption(f"Активен пресет «{st.session_state.preset_sel}» — поля ниже можно подправить.")

        st.subheader("Риск и комиссии")
        leverage = st.number_input("Плечо", min_value=1.0, max_value=20.0, step=0.5, key="inp_leverage")
        commission = st.number_input(
            "Комиссия % / сторона", min_value=0.0, max_value=1.0, step=0.01, key="inp_commission"
        )
        compound = st.checkbox("Капитализация PnL", key="inp_compound")

        state = SidebarState(
            csv_path=csv_path,
            uploaded=uploaded,
            auto_download=auto_download,
            compare_mode=compare_mode,
            leverage=float(leverage),
            commission=float(commission),
            compound=bool(compound),
            run_clicked=False,
        )

        if compare_mode:
            col_a, col_b = st.columns(2)
            with col_a:
                st.markdown("**Стратегия A**")
                state.entry_a = st.number_input(
                    "Entry Z", value=0.8, min_value=0.1, max_value=5.0, step=0.05, key="ent_a"
                )
                state.exit_a = st.number_input(
                    "Exit Z", value=0.7, min_value=0.0, max_value=5.0, step=0.05, key="ext_a"
                )
                state.notional_a = st.number_input(
                    "Номинал ₽", value=100_000.0, min_value=10_000.0, step=10_000.0, key="not_a"
                )
            with col_b:
                st.markdown("**Стратегия B**")
                state.entry_b = st.number_input(
                    "Entry Z", value=1.0, min_value=0.1, max_value=5.0, step=0.05, key="ent_b"
                )
                state.exit_b = st.number_input(
                    "Exit Z", value=0.7, min_value=0.0, max_value=5.0, step=0.05, key="ext_b"
                )
                state.notional_b = st.number_input(
                    "Номинал ₽", value=100_000.0, min_value=10_000.0, step=10_000.0, key="not_b"
                )
        else:
            st.subheader("Пороги Z")
            state.entry = st.number_input("Entry Z", min_value=0.1, max_value=5.0, step=0.05, key="inp_entry")
            state.exit_z = st.number_input("Exit Z", min_value=0.0, max_value=5.0, step=0.05, key="inp_exit_z")
            state.notional = st.number_input("Номинал ₽", min_value=10_000.0, step=10_000.0, key="inp_notional")

        state.run_clicked = st.button("Запустить симуляцию", type="primary", use_container_width=True)

    return state
