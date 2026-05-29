from pathlib import Path

import pandas as pd
import streamlit as st

from config import DEFAULT_M15, UPLOADED_CSV
from m15_iss_loader import ensure_m15_data
from ui.sidebar import SidebarState
from zsim import (
    ZMode,
    compare_z_modes,
    history_quality,
    load_bars_from_csv,
    run_z_strategy_sim,
    z_diagnostics,
)


def resolve_data_path(sidebar: SidebarState) -> Path:
    if sidebar.uploaded is not None:
        UPLOADED_CSV.parent.mkdir(parents=True, exist_ok=True)
        pd.read_csv(sidebar.uploaded).to_csv(UPLOADED_CSV, index=False)
        return UPLOADED_CSV

    if sidebar.auto_download:
        with st.spinner("Автозагрузка MOEX…"):
            ensure_m15_data(sidebar.csv_path, days=255)
        if sidebar.csv_path.is_file():
            return sidebar.csv_path

    if sidebar.csv_path.is_file():
        return sidebar.csv_path

    st.error(f"Файл не найден: {sidebar.csv_path}")
    st.stop()


def run_simulation(sidebar: SidebarState, *, z_mode: ZMode = "rolling30") -> None:
    path = resolve_data_path(sidebar)
    bars = load_bars_from_csv(str(path), recalc_z=True, z_mode=z_mode)
    hq = history_quality(bars, z_mode=z_mode)
    diag = z_diagnostics(bars)
    sim_kw = {
        "leverage": sidebar.leverage,
        "commission_pct_per_side": sidebar.commission,
        "compound_returns": sidebar.compound,
    }
    packs: list[dict] = []

    if sidebar.compare_mode:
        for label, entry, exit_z, notional in (
            ("A", sidebar.entry_a, sidebar.exit_a, sidebar.notional_a),
            ("B", sidebar.entry_b, sidebar.exit_b, sidebar.notional_b),
        ):
            result = run_z_strategy_sim(
                bars,
                entry=entry,
                exit_z=exit_z,
                notional_rub=float(notional),
                **sim_kw,
            )
            packs.append(
                {"label": f"Стратегия {label}", "result": result, "entry": entry, "exit_z": exit_z}
            )
        notional_rub = float(sidebar.notional_a)
        entry = float(sidebar.entry_a)
        exit_z = float(sidebar.exit_a)
    else:
        entry = float(sidebar.entry)
        exit_z = float(sidebar.exit_z)
        result = run_z_strategy_sim(
            bars,
            entry=entry,
            exit_z=exit_z,
            notional_rub=float(sidebar.notional),
            **sim_kw,
        )
        packs.append(
            {
                "label": "Стратегия",
                "result": result,
                "entry": entry,
                "exit_z": exit_z,
            }
        )
        notional_rub = float(sidebar.notional)

    z_compare: pd.DataFrame | None = None
    if z_mode == "rolling30" and not sidebar.compare_mode:
        with st.spinner("Сравнение Rolling vs Global…"):
            z_compare = compare_z_modes(
                str(path),
                entry=entry,
                exit_z=exit_z,
                notional_rub=notional_rub,
                leverage=float(sidebar.leverage),
                commission_pct_per_side=float(sidebar.commission),
                compound_returns=bool(sidebar.compound),
            )

    st.session_state["sim_packs"] = packs
    st.session_state["sim_hq"] = hq
    st.session_state["sim_compare"] = sidebar.compare_mode
    st.session_state["sim_bars_path"] = str(path)
    st.session_state["sim_kw"] = {**sim_kw, "notional_rub": notional_rub}
    st.session_state["sim_z_mode"] = z_mode
    st.session_state["sim_z_diag"] = diag
    st.session_state["sim_z_compare"] = z_compare
    st.session_state.pop("sim_sweep", None)
