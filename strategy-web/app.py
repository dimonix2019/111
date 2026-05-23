from pathlib import Path

import pandas as pd
import streamlit as st

from m15_iss_loader import (
    MIN_BARS_FULL,
    STALE_HOURS,
    csv_file_status,
    download_csv_from_github,
    download_m15_csv,
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
st.title("Z-Strategy Backtester")

if "csv_path" not in st.session_state:
    st.session_state.csv_path = str(DEFAULT_M15 if DEFAULT_M15.is_file() else DEFAULT_M15)

with st.sidebar:
    st.subheader("Данные MOEX 15м")
    csv_path_str = st.text_input("Путь к CSV", value=st.session_state.csv_path, key="csv_path_input")
    st.session_state.csv_path = csv_path_str
    csv_path = Path(csv_path_str)

    status = csv_file_status(csv_path, min_bars=MIN_BARS_FULL_HISTORY)

    if not status["exists"]:
        st.error("Файл не найден на диске.")
    elif not status["looks_full"]:
        st.warning(
            f"Мало баров: **{status['bar_count']}** (нужно ≈{MIN_BARS_FULL_HISTORY}+ за {LOOKBACK_DAYS}д). "
            f"Результат **не совпадёт** с Android."
        )
    elif status["stale"]:
        st.warning(f"Данные устарели (более {STALE_HOURS}ч, возраст {status['age_hours']}ч).")
    else:
        st.success(
            f"OK: **{status['bar_count']}** баров, "
            f"{status['first_ts'][:10]} … {status['last_ts'][:10]}"
        )

    auto_dl = st.checkbox("При старте: дозагрузить если нет файла или мало баров", value=True)

    if st.button("Проверить наличие на диске"):
        st.session_state.data_check = status
        st.rerun()

    if st.button(f"Дозагрузить с MOEX ({LOOKBACK_DAYS} дн.)"):
        prog = st.empty()
        try:
            def on_progress(msg: str) -> None:
                prog.info(msg)

            with st.spinner("Загрузка с MOEX ISS (2–5 мин, не закрывайте вкладку)…"):
                summary = download_m15_csv(csv_path, days=LOOKBACK_DAYS, on_progress=on_progress)
            if summary["bar_count"] < MIN_BARS_FULL:
                prog.error(f"Слишком мало баров: {summary['bar_count']}")
            else:
                prog.success(
                    f"Готово: **{summary['bar_count']}** баров → `{summary['path']}`\n\n"
                    f"{summary['first_ts'][:10]} … {summary['last_ts'][:10]}"
                )
            st.session_state.csv_path = str(csv_path)
        except Exception as e:
            prog.error(f"Ошибка загрузки: {e}")

    if st.button("Скачать готовый CSV с GitHub (~700 KB)"):
        prog = st.empty()
        try:
            with st.spinner("Скачивание готового ряда из репозитория…"):
                summary = download_csv_from_github(csv_path)
            prog.success(
                f"Готово: **{summary['bar_count']}** баров (GitHub)\n\n"
                f"{summary['first_ts'][:10]} … {summary['last_ts'][:10]}"
            )
        except Exception as e:
            prog.error(
                f"{e}\n\nЕсли репозиторий приватный — см. PowerShell ниже или кэш с телефона."
            )

    st.caption(
        "MOEX ISS работает и ночью/в выходные (история). "
        "Если в CSV **~1000 строк** — обрыв загрузки, не «биржа закрыта». "
        "Надёжнее: PowerShell 2–5 мин:\n\n"
        f"`..\\.venv\\Scripts\\python.exe download_m15.py` "
        f"(или двойной клик download_m15.bat)"
    )

    uploaded = st.file_uploader("Или загрузить CSV", type=["csv"])

    st.divider()
    st.subheader("Параметры стратегии")
    entry = st.number_input("Entry Threshold (Z)", value=0.8, min_value=0.1, max_value=5.0, step=0.05)
    exit_z = st.number_input("Exit Threshold (Z)", value=0.7, min_value=0.0, max_value=5.0, step=0.05)
    notional = st.number_input("Номинал (₽)", value=100_000.0, min_value=10_000.0, step=10_000.0)
    leverage = st.number_input("Leverage", value=7.0, min_value=1.0, max_value=20.0, step=0.5)
    commission = st.number_input("Комиссия (% / сторона)", value=0.04, min_value=0.0, max_value=1.0, step=0.01)
    compound = st.checkbox("Капитализация PnL (как «фикс. номинал» выкл.)", value=False)

    force_short = st.checkbox("Всё равно симулировать на коротком CSV (не для сравнения с Android)", value=False)

# Автозагрузка при первом открытии
if auto_dl and not uploaded:
    need = not csv_path.is_file() or not csv_file_status(csv_path)["looks_full"]
    if need and "auto_download_attempted" not in st.session_state:
        st.session_state.auto_download_attempted = True
        try:
            with st.spinner(f"Автозагрузка MOEX ({LOOKBACK_DAYS}д)…"):
                download_m15_csv(csv_path, days=LOOKBACK_DAYS)
            st.toast(f"Скачан полный ряд → {csv_path.name}", icon="✅")
            st.rerun()
        except Exception as e:
            st.sidebar.error(f"Автозагрузка не удалась: {e}")

st.caption(
    f"Как Android «Тест страт.»: **{LOOKBACK_DAYS}д** 15м, ISS 10m→15m, Z по **всему** ряду. "
    f"При 100k × leverage 7, пороги 0.8/0.7 → **~140 сделок**, PnL **≈ +250k ₽** (удвоение+ депозита)."
)

with st.expander("Почему на вебе 1 сделка и минус, а на Android +100%?"):
    st.markdown(
        """
        1. **Короткий CSV** (несколько дней вместо 255) → мало пересечений порогов → 1–5 сделок вместо ~140.
        2. **Z пересчитывается по загруженному окну** — на графике Z может уходить к ±8; в Android Z всегда по полному кэшу ~13k баров.
        3. **Открытая позиция в конце короткого окна** даёт большой unrealized минус (как на вашем скрине −3250 ₽).

        **Решение:** нажмите «Дозагрузить с MOEX (255 дн.)» и снова «Запустить симуляцию».
        """
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
            path = Path(st.session_state.csv_path)
            if not path.is_file():
                st.error(
                    f"Файл не найден: `{path}`. "
                    f"Нажмите «Дозагрузить с MOEX ({LOOKBACK_DAYS} дн.)» в sidebar."
                )
                st.stop()

        file_status = csv_file_status(path, min_bars=MIN_BARS_FULL_HISTORY)
        if not file_status["looks_full"] and not force_short:
            st.error(
                f"**Слишком мало данных для сравнения с Android:** {file_status['bar_count']} баров "
                f"(нужно ≥ {MIN_BARS_FULL_HISTORY}). "
                f"Дозагрузите полный ряд с MOEX или включите «Всё равно симулировать на коротком CSV»."
            )
            st.stop()

        bars = load_bars_from_csv(str(path), recalc_z=True)
        hq = history_quality(bars)

        c1, c2, c3, c4 = st.columns(4)
        c1.metric("Баров 15m", hq["bar_count"])
        c2.metric("Период с", hq["first_ts"][:10] if hq["first_ts"] else "—")
        c3.metric("Период по", hq["last_ts"][:10] if hq["last_ts"] else "—")
        c4.metric("Ожид. сделок (Android)", "~140" if hq["looks_full_255d"] else "мало")

        if hq["looks_full_255d"]:
            st.success(f"Полный ряд (~{LOOKBACK_DAYS}д): результаты должны совпасть с Android «Тест страт.»")
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

        if hq["looks_full_255d"] and len(result.trades) < 50:
            st.warning(
                "Мало сделок при полном ряде — проверьте пороги (Android по умолчанию 0.8 / 0.7) "
                "и что CSV содержит TATN/TATNP spread с MOEX, а не sample.csv."
            )

        if result.unrealized_pnl_rub != 0:
            st.info(
                f"В PnL учтена открытая позиция на конце ряда: "
                f"realized {result.realized_pnl_rub:,.0f} ₽ + unrealized {result.unrealized_pnl_rub:,.0f} ₽ "
                f"(как totalPnlRubApprox в Android)."
            )

        st.subheader("Z-Score over time")
        st.line_chart(result.signals.set_index("timestamp")["z_score"])

        if result.equity_history:
            st.subheader("Equity (RUB) Chart")
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
        else:
            st.info("Сделок нет — проверьте пороги и длину CSV.")

    except Exception as e:
        st.exception(e)
