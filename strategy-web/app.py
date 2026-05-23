from pathlib import Path

import pandas as pd
import streamlit as st

from m15_iss_loader import (
    MIN_BARS_FULL,
    STALE_HOURS,
    csv_file_status,
    ensure_full_data_file,
)
from zsim import (
    LOOKBACK_DAYS,
    MIN_BARS_FULL_HISTORY,
    history_quality,
    load_bars_from_csv,
    run_z_strategy_sim,
)

ROOT = Path(__file__).resolve().parent
DEFAULT_M15 = ROOT / "data" / "m15_tatn_255d.csv"

st.set_page_config(page_title="Z-Strategy Backtester", layout="wide")

# --- Автоматически готовим data/m15_tatn_255d.csv (без кнопок и терминала) ---
if "data_ready" not in st.session_state:
    st.session_state.data_ready = False

if not st.session_state.data_ready:
    st.title("Z-Strategy Backtester")
    status_box = st.empty()
    try:
        with st.spinner("Подготовка истории TATN/TATNP (255 дней)…"):
            def on_progress(msg: str) -> None:
                status_box.info(msg)

            final = ensure_full_data_file(DEFAULT_M15, days=LOOKBACK_DAYS, on_progress=on_progress)
        if not final["looks_full"]:
            st.error(
                f"Не удалось получить полный ряд: {final['bar_count']} баров. "
                f"Проверьте интернет и обновите страницу (F5)."
            )
            st.stop()
        st.session_state.data_ready = True
        st.session_state.csv_path = str(DEFAULT_M15)
        st.rerun()
    except Exception as e:
        st.error(f"Ошибка загрузки данных: {e}")
        st.stop()

st.title("Z-Strategy Backtester")
csv_path = Path(st.session_state.get("csv_path", str(DEFAULT_M15)))
data_status = csv_file_status(csv_path, min_bars=MIN_BARS_FULL_HISTORY)

with st.sidebar:
    st.subheader("Данные MOEX 15м")
    if data_status["looks_full"]:
        st.success(
            f"**{data_status['bar_count']}** баров · "
            f"{data_status['first_ts'][:10]} … {data_status['last_ts'][:10]}"
        )
        if data_status["stale"]:
            st.caption(f"Возраст файла {data_status['age_hours']}ч (>{STALE_HOURS}ч) — при следующем запуске обновится.")
    else:
        st.warning("Данные неполные — перезагрузите страницу (F5).")

    if st.button("Обновить данные с MOEX/GitHub"):
        st.session_state.data_ready = False
        st.rerun()

    uploaded = st.file_uploader("Другой CSV (необязательно)", type=["csv"])

    st.divider()
    st.subheader("Параметры стратегии")
    entry = st.number_input("Entry Threshold (Z)", value=0.8, min_value=0.1, max_value=5.0, step=0.05)
    exit_z = st.number_input("Exit Threshold (Z)", value=0.7, min_value=0.0, max_value=5.0, step=0.05)
    notional = st.number_input("Номинал (₽)", value=100_000.0, min_value=10_000.0, step=10_000.0)
    leverage = st.number_input("Leverage", value=7.0, min_value=1.0, max_value=20.0, step=0.5)
    commission = st.number_input("Комиссия (% / сторона)", value=0.04, min_value=0.0, max_value=1.0, step=0.01)
    compound = st.checkbox("Капитализация PnL", value=False)

st.caption(
    f"Как Android «Тест страт.»: {LOOKBACK_DAYS}д 15м · 100k × 7 · 0.8/0.7 → **~140 сделок**, **~+250k ₽**"
)

if st.button("Запустить симуляцию", type="primary"):
    try:
        if uploaded is not None:
            df_raw = pd.read_csv(uploaded)
            tmp = ROOT / "data" / "_uploaded.csv"
            tmp.parent.mkdir(parents=True, exist_ok=True)
            df_raw.to_csv(tmp, index=False)
            path = tmp
        else:
            path = csv_path
            if not path.is_file() or not csv_file_status(path)["looks_full"]:
                st.session_state.data_ready = False
                st.rerun()

        bars = load_bars_from_csv(str(path), recalc_z=True)
        hq = history_quality(bars)

        c1, c2, c3, c4 = st.columns(4)
        c1.metric("Баров 15m", hq["bar_count"])
        c2.metric("Период с", hq["first_ts"][:10] if hq["first_ts"] else "—")
        c3.metric("Период по", hq["last_ts"][:10] if hq["last_ts"] else "—")
        c4.metric("Сделок (ожид.)", "~140" if hq["looks_full_255d"] else "мало")

        if hq["looks_full_255d"]:
            st.success("Полный ряд — сравнение с Android корректно")
        else:
            st.warning(hq["expected_trades_hint"])

        result = run_z_strategy_sim(
            bars,
            entry=entry,
            exit_z=exit_z,
            notional_rub=float(notional),
            leverage=float(leverage),
            commission_pct_per_side=float(commission),
            compound_returns=compound,
        )

        m1, m2, m3, m4 = st.columns(4)
        m1.metric("Сделок", len(result.trades))
        m2.metric("Total PnL (₽)", f"{result.total_pnl_rub:,.0f}")
        m3.metric(
            "Avg PnL/Trade",
            f"{result.total_pnl_rub / len(result.trades):,.0f}" if result.trades else "—",
        )
        wins = sum(1 for t in result.trades if t.pnl_rub > 0)
        m4.metric(
            "Win Rate",
            f"{100.0 * wins / len(result.trades):.1f}%" if result.trades else "—",
        )

        if result.unrealized_pnl_rub != 0:
            st.info(
                f"Открытая позиция на конце: unrealized {result.unrealized_pnl_rub:,.0f} ₽"
            )

        st.subheader("Z-Score over time")
        st.line_chart(result.signals.set_index("timestamp")["z_score"])

        if result.equity_history:
            st.subheader("Equity (RUB)")
            eq_df = pd.DataFrame(
                {"equity_rub": result.equity_history},
                index=result.signals["timestamp"],
            )
            st.line_chart(eq_df)

        st.subheader("Закрытые сделки")
        if result.trades:
            st.dataframe(
                pd.DataFrame(
                    [
                        {
                            "направление": t.direction.value,
                            "вход": t.entry_time,
                            "выход": t.exit_time,
                            "PnL ₽": round(t.pnl_rub, 0),
                        }
                        for t in result.trades
                    ]
                ),
                use_container_width=True,
            )
    except Exception as e:
        st.exception(e)
