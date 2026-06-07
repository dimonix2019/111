"""Plotly-графики в стиле ArbiCurs (тёмная тема)."""

from __future__ import annotations

import pandas as pd
import plotly.graph_objects as go

DARK = {
    "paper": "#0b111b",
    "plot": "#111827",
    "grid": "rgba(148, 163, 184, 0.12)",
    "text": "#e5e7eb",
    "muted": "#94a3b8",
    "green": "#10b981",
    "red": "#ef4444",
    "gold": "#fbbf24",
    "blue": "#38bdf8",
    "orange": "#fb923c",
}

COMPARE_COLORS = [DARK["blue"], DARK["orange"]]

HOVER_TRADE = (
    "<b>Сделка #%{customdata[0]}</b> · %{customdata[6]}<br>"
    "%{customdata[1]}<br>"
    "Вход: %{customdata[2]}<br>"
    "Выход: %{customdata[3]}<br>"
    "PnL: %{customdata[4]:,.0f} ₽ · %{customdata[5]:.3f} pts<extra></extra>"
)


def _base_layout(title: str, height: int = 420) -> dict:
    return dict(
        title=dict(text=title, font=dict(color=DARK["text"], size=14)),
        margin=dict(l=48, r=24, t=48, b=40),
        hovermode="x unified",
        height=height,
        plot_bgcolor=DARK["plot"],
        paper_bgcolor=DARK["paper"],
        font=dict(color=DARK["text"], size=12),
        legend=dict(bgcolor="rgba(17,24,39,0.8)", bordercolor=DARK["grid"]),
        xaxis=dict(gridcolor=DARK["grid"], showspikes=True, spikemode="across", spikesnap="cursor"),
        yaxis=dict(gridcolor=DARK["grid"], showspikes=True, spikemode="across"),
    )


def _marker_customdata(df: pd.DataFrame, event_label: str) -> pd.DataFrame:
    out = df.copy()
    out["event_label"] = event_label
    return out


def _add_trade_markers(fig: go.Figure, markers_df: pd.DataFrame) -> None:
    if markers_df.empty:
        return
    m = markers_df.copy()
    m["ts"] = pd.to_datetime(m["timestamp"])
    entries = m[m["event"] == "вход"]
    exits = m[m["event"] == "выход"]

    if not entries.empty:
        ent = _marker_customdata(entries, "вход")
        fig.add_trace(
            go.Scatter(
                x=ent["ts"],
                y=ent["plot_y"],
                mode="markers",
                name="Вход",
                marker=dict(
                    symbol=ent["marker_symbol"],
                    size=10,
                    color=ent["marker_color"],
                    line=dict(width=1, color="#fff"),
                ),
                customdata=ent[
                    ["trade_no", "direction", "entry_time", "exit_time", "pnl_rub", "pnl_spread_pts", "event_label"]
                ].values,
                hovertemplate=HOVER_TRADE,
            )
        )
    if not exits.empty:
        ex = _marker_customdata(exits, "выход")
        fig.add_trace(
            go.Scatter(
                x=ex["ts"],
                y=ex["plot_y"],
                mode="markers",
                name="Выход",
                marker=dict(symbol="circle", size=7, color=DARK["orange"], line=dict(width=1, color="#fff")),
                customdata=ex[
                    ["trade_no", "direction", "entry_time", "exit_time", "pnl_rub", "pnl_spread_pts", "event_label"]
                ].values,
                hovertemplate=HOVER_TRADE,
            )
        )


def markers_on_series(markers_df: pd.DataFrame, series_df: pd.DataFrame, y_col: str) -> pd.DataFrame:
    """Подставляет Y из ряда (equity или spread) для маркеров сделок."""
    if markers_df.empty:
        return markers_df
    lookup = dict(zip(series_df["timestamp"].astype(str), series_df[y_col]))
    m = markers_df.copy()
    fallback = m.apply(
        lambda r: r["entry_spread"] if r["event"] == "вход" else r["exit_spread"],
        axis=1,
    )
    m["plot_y"] = m["timestamp"].astype(str).map(lookup).fillna(fallback)
    return m


def make_equity_chart(
    eq_df: pd.DataFrame,
    markers_df: pd.DataFrame | None = None,
    label: str = "Equity",
) -> go.Figure:
    df = eq_df.copy()
    df["ts"] = pd.to_datetime(df["timestamp"])
    y_min = min(df["equity_rub"].min(), df["drawdown_rub"].min())
    y_max = max(df["equity_rub"].max(), 0)

    fig = go.Figure()
    fig.add_trace(
        go.Scatter(
            x=df["ts"],
            y=df["equity_rub"],
            name=label,
            line=dict(color=DARK["green"], width=2),
            hovertemplate="%{x|%Y-%m-%d %H:%M}<br>Equity: %{y:,.0f} ₽<extra></extra>",
        )
    )
    fig.add_trace(
        go.Scatter(
            x=df["ts"],
            y=df["drawdown_rub"],
            name="Просадка",
            line=dict(color=DARK["red"], width=1, dash="dot"),
            hovertemplate="%{x|%Y-%m-%d %H:%M}<br>DD: %{y:,.0f} ₽<extra></extra>",
        )
    )
    if markers_df is not None:
        _add_trade_markers(fig, markers_on_series(markers_df, eq_df, "equity_rub"))

    layout = _base_layout("Кривая капитала")
    layout["yaxis"]["title"] = "₽"
    layout["yaxis"]["range"] = [y_min * 1.08, y_max * 1.08]
    fig.update_layout(**layout)
    return fig


def make_equity_compare(charts: list[tuple[pd.DataFrame, str]]) -> go.Figure:
    fig = go.Figure()
    y_min, y_max = 0.0, 0.0
    for i, (eq_df, label) in enumerate(charts):
        df = eq_df.copy()
        df["ts"] = pd.to_datetime(df["timestamp"])
        color = COMPARE_COLORS[i % len(COMPARE_COLORS)]
        y_min = min(y_min, df["equity_rub"].min(), df["drawdown_rub"].min())
        y_max = max(y_max, df["equity_rub"].max())
        fig.add_trace(
            go.Scatter(
                x=df["ts"],
                y=df["equity_rub"],
                name=label,
                line=dict(color=color, width=2),
                hovertemplate="%{x|%Y-%m-%d %H:%M}<br>%{y:,.0f} ₽<extra></extra>",
            )
        )
    layout = _base_layout("Сравнение equity")
    layout["yaxis"]["title"] = "₽"
    layout["yaxis"]["range"] = [y_min * 1.08, max(y_max, 1) * 1.08]
    fig.update_layout(**layout)
    return fig


def make_spread_chart(
    spread_df: pd.DataFrame,
    markers_df: pd.DataFrame,
    label: str = "",
) -> go.Figure:
    df = spread_df.copy()
    df["ts"] = pd.to_datetime(df["timestamp"])
    title = f"Spread % 15м{(' · ' + label) if label else ''}"
    fig = go.Figure()
    fig.add_trace(
        go.Scatter(
            x=df["ts"],
            y=df["spread_percent"],
            name="Spread %",
            line=dict(color=DARK["gold"], width=1.5),
            hovertemplate="%{x|%Y-%m-%d %H:%M}<br>Spread: %{y:.3f}%<extra></extra>",
        )
    )
    if not markers_df.empty:
        m = markers_on_series(markers_df, df, "spread_percent")
        _add_trade_markers(fig, m)

    layout = _base_layout(title, height=420)
    layout["yaxis"]["title"] = "%"
    fig.update_layout(**layout)
    return fig


def make_zscore_chart(
    z_df: pd.DataFrame,
    markers_df: pd.DataFrame,
    entry: float,
    exit_z: float,
    label: str = "",
) -> go.Figure:
    df = z_df.copy()
    df["ts"] = pd.to_datetime(df["timestamp"])

    title = f"Z-score 15м{(' · ' + label) if label else ''}"
    fig = go.Figure()
    fig.add_trace(
        go.Scatter(
            x=df["ts"],
            y=df["z_score"],
            name="Z-score",
            line=dict(color=DARK["green"], width=1.5),
            hovertemplate="%{x|%Y-%m-%d %H:%M}<br>Z: %{y:.3f}<extra></extra>",
        )
    )

    for y_val, name, color, dash in (
        (entry, f"+Entry {entry}", DARK["gold"], "dash"),
        (-entry, f"-Entry {entry}", DARK["gold"], "dash"),
        (exit_z, f"+Exit {exit_z}", DARK["muted"], "dot"),
        (-exit_z, f"-Exit {exit_z}", DARK["muted"], "dot"),
    ):
        fig.add_hline(y=y_val, line=dict(color=color, dash=dash, width=1), annotation_text=name)

    if not markers_df.empty:
        m = markers_on_series(markers_df, df, "z_score")
        _add_trade_markers(fig, m)

    layout = _base_layout(title, height=480)
    layout["yaxis"]["title"] = "Z"
    fig.update_layout(**layout)
    return fig


def make_sweep_heatmap(sweep_df: pd.DataFrame, value_col: str = "total_pnl_rub") -> go.Figure:
    pivot = sweep_df.pivot(index="exit_z", columns="entry", values=value_col)
    pivot = pivot.sort_index(ascending=False)
    fig = go.Figure(
        go.Heatmap(
            z=pivot.values,
            x=[str(c) for c in pivot.columns],
            y=[str(i) for i in pivot.index],
            colorscale=[
                [0.0, DARK["red"]],
                [0.5, "#374151"],
                [1.0, DARK["green"]],
            ],
            zmid=0,
            hovertemplate="Entry: %{x}<br>Exit: %{y}<br>PnL: %{z:,.0f} ₽<extra></extra>",
            colorbar=dict(title="PnL ₽", tickfont=dict(color=DARK["text"])),
        )
    )
    layout = _base_layout("Heatmap Entry × Exit → PnL", height=460)
    layout["xaxis"]["title"] = "Entry Z"
    layout["yaxis"]["title"] = "Exit Z"
    fig.update_layout(**layout)
    return fig
