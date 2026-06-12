#!/usr/bin/env python3
"""
LONG спред TATN/TATNP: свечи обеих ног, на каждой свече в сделке — MTM PnL ноги.
"""

from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.animation import FFMpegWriter, FuncAnimation
from matplotlib.patches import Rectangle
import numpy as np

OUT = Path("/opt/cursor/artifacts/long_trade_legs_animation.mp4")
NOTIONAL = 100_000.0
LEVERAGE = 7.0
COMMISSION_PCT = 0.04
EFFECTIVE = NOTIONAL * LEVERAGE
COMMISSION = EFFECTIVE * (COMMISSION_PCT / 100.0) * 2

CANDLE_W = 0.42
BULL = "#16a34a"
BEAR = "#dc2626"
WICK = "#374151"

LABELS = [
    "06:30", "06:45", "07:00", "07:15", "07:30", "07:45",
    "08:00", "08:15", "08:30", "08:45", "09:00", "09:15",
    "09:30", "09:45", "10:00", "10:15",
]
ENTRY_IDX = 1
EXIT_IDX = 14
N = len(LABELS)

TATNP = np.array([
    600.4, 600.0, 599.6, 599.2, 598.9, 598.6,
    598.3, 598.0, 597.8, 597.5, 597.3, 597.0,
    596.8, 596.5, 597.2, 597.0,
])
TATN = np.array([
    636.8, 637.2, 637.8, 638.2, 638.5, 638.9,
    639.2, 639.5, 639.8, 640.1, 640.4, 640.7,
    641.0, 641.2, 638.8, 638.5,
])
SPREAD = (TATN / TATNP - 1.0) * 100.0
Z = np.array([
    -0.55, -0.85, -0.90, -0.92, -0.88, -0.86,
    -0.84, -0.82, -0.78, -0.72, -0.68, -0.64,
    -0.60, -0.56, -0.42, -0.35,
])

ENTRY_SPREAD = SPREAD[ENTRY_IDX]
EXIT_SPREAD = SPREAD[EXIT_IDX]
PNL_SPREAD_PP = EXIT_SPREAD - ENTRY_SPREAD
LEG_NOTIONAL = EFFECTIVE / 2.0  # 350k на каждую ногу → пара = TATN + TATNP


def leg_pnl_long(entry: float, close: float) -> float:
    return LEG_NOTIONAL * (close / entry - 1.0)


def leg_pnl_short(entry: float, close: float) -> float:
    return LEG_NOTIONAL * (entry / close - 1.0)


def build_leg_pnl_series(prices: np.ndarray, is_long: bool) -> np.ndarray:
    out = np.full(N, np.nan)
    entry = prices[ENTRY_IDX]
    fn = leg_pnl_long if is_long else leg_pnl_short
    for i in range(ENTRY_IDX, EXIT_IDX + 1):
        out[i] = fn(entry, prices[i])
    return out


TATN_PNL_SERIES = build_leg_pnl_series(TATN, is_long=True)
TATNP_PNL_SERIES = build_leg_pnl_series(TATNP, is_long=False)
# PnL пары = сумма двух ног (на каждом баре и в итоге)
PAIR_PNL_SERIES = TATN_PNL_SERIES + TATNP_PNL_SERIES

TATN_PNL = TATN_PNL_SERIES[EXIT_IDX]
TATNP_PNL = TATNP_PNL_SERIES[EXIT_IDX]
GROSS_PAIR = TATN_PNL + TATNP_PNL
NET_PAIR = GROSS_PAIR - COMMISSION


def build_ohlc(close: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    n = len(close)
    open_ = np.zeros(n)
    high = np.zeros(n)
    low = np.zeros(n)
    for i in range(n):
        open_[i] = close[i - 1] if i > 0 else close[i] * 0.9995
        body_hi = max(open_[i], close[i])
        body_lo = min(open_[i], close[i])
        wick = close[i] * 0.0012
        high[i] = body_hi + wick
        low[i] = body_lo - wick
    return open_, high, low, close


def fmt_pnl_short(x: float) -> str:
    if abs(x) >= 1000:
        return f"{x/1000:+.1f}k"
    return f"{x:+.0f}"


def fmt_rub(x: float) -> str:
    sign = "+" if x >= 0 else "-"
    return f"{sign}{abs(x):,.0f} руб".replace(",", " ")


def pnl_color(x: float) -> str:
    return "#059669" if x >= 0 else "#dc2626"


def draw_candles_with_pnl(
    ax,
    close: np.ndarray,
    count: int,
    highlight_idx: int,
    pnl_series: np.ndarray,
    label_prefix: str = "",
    y_pad_ratio: float = 0.008,
):
    O, H, L, C = build_ohlc(close)
    artists: list = []
    y_min = close[:count].min()
    y_max = close[:count].max()
    pad = max((y_max - y_min) * 0.22, close[0] * y_pad_ratio)
    ax.set_ylim(y_min - pad, y_max + pad * 0.85)

    for i in range(count):
        o, h, l, c = O[i], H[i], L[i], C[i]
        color = BULL if c >= o else BEAR
        if i == highlight_idx:
            color = "#7c3aed"
        wick_line, = ax.plot([i, i], [l, h], color=WICK if i != highlight_idx else color, lw=1.0, zorder=2)
        artists.append(wick_line)
        rect = Rectangle(
            (i - CANDLE_W / 2, min(o, c)), CANDLE_W, max(abs(c - o), c * 0.0004),
            facecolor=color, edgecolor=color, zorder=3,
        )
        ax.add_patch(rect)
        artists.append(rect)

        if not np.isnan(pnl_series[i]) and ENTRY_IDX <= i <= min(highlight_idx, EXIT_IDX):
            pnl = pnl_series[i]
            y_text = h + (y_max - y_min) * 0.04
            txt = ax.text(
                i, y_text,
                f"{label_prefix}{fmt_pnl_short(pnl)}",
                ha="center", va="bottom", fontsize=7.5, fontweight="bold",
                color=pnl_color(pnl), zorder=6,
                bbox=dict(boxstyle="round,pad=0.15", facecolor="white", edgecolor=pnl_color(pnl), alpha=0.92),
            )
            artists.append(txt)
            if i == ENTRY_IDX:
                entry_mark = ax.text(
                    i, l - (y_max - y_min) * 0.05, "ВХОД",
                    ha="center", va="top", fontsize=6.5, color="#059669", fontweight="bold", zorder=6,
                )
                artists.append(entry_mark)
            if i == EXIT_IDX and highlight_idx >= EXIT_IDX:
                exit_mark = ax.text(
                    i, l - (y_max - y_min) * 0.05, "ВЫХОД",
                    ha="center", va="top", fontsize=6.5, color="#16a34a", fontweight="bold", zorder=6,
                )
                artists.append(exit_mark)

    return artists


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)

    fig = plt.figure(figsize=(11, 8.5), dpi=120)
    fig.patch.set_facecolor("#fafafa")
    gs = fig.add_gridspec(4, 1, height_ratios=[0.5, 1.15, 1.15, 1.05], hspace=0.38)

    ax_idea = fig.add_subplot(gs[0])
    ax_tatn = fig.add_subplot(gs[1])
    ax_tatnp = fig.add_subplot(gs[2])
    ax_spread = fig.add_subplot(gs[3])

    for ax in (ax_tatn, ax_tatnp, ax_spread):
        ax.set_facecolor("#ffffff")
        ax.grid(True, alpha=0.2)

    ax_idea.axis("off")
    ax_idea.text(
        0.5, 0.55,
        "LONG спред: PnL пары = TATN + TATNP (по 350k на ногу при 100k x7).\n"
        "На каждой свече — MTM от цены входа до close бара.",
        ha="center", va="center", fontsize=10,
        bbox=dict(boxstyle="round,pad=0.5", facecolor="#ecfdf5", edgecolor="#059669"),
    )

    x = np.arange(N)
    for ax in (ax_tatn, ax_tatnp, ax_spread):
        ax.set_xlim(-0.5, N - 0.5)
        ax.set_xticks(x)
        ax.set_xticklabels(LABELS, rotation=45, ha="right", fontsize=8)

    ax_tatn.set_ylabel("TATN, руб", fontsize=10, color="#059669")
    ax_tatnp.set_ylabel("TATNP, руб", fontsize=10, color="#dc2626")
    ax_spread.set_ylabel("Спред, %", fontsize=10)
    ax_spread.set_xlabel("15м бары (MSK, 10.06.2026)", fontsize=10)

    ax_tatn.set_title("TATN — LONG: PnL над каждой свечой в сделке", fontsize=11, fontweight="bold", color="#065f46")
    ax_tatnp.set_title("TATNP — SHORT: PnL над каждой свечой в сделке", fontsize=11, fontweight="bold", color="#991b1b")
    ax_spread.set_title("Спред + PnL пары (= TATN + TATNP) над баром", fontsize=11, fontweight="bold")

    (spread_line,) = ax_spread.plot([], [], color="#2563eb", lw=2, zorder=3)
    ax_spread.axhline(ENTRY_SPREAD, color="#ea580c", ls=":", lw=1.2, alpha=0.7)
    ax_spread_twin = ax_spread.twinx()
    (z_line,) = ax_spread_twin.plot([], [], color="#9333ea", lw=1.5, ls="--", alpha=0.85)
    ax_spread_twin.set_ylabel("Z", fontsize=9, color="#9333ea")
    ax_spread_twin.axhline(-0.7, color="#ea580c", ls="--", lw=1, alpha=0.5)
    ax_spread_twin.axhline(-0.5, color="#16a34a", ls="--", lw=1, alpha=0.5)
    ax_spread_twin.set_ylim(-1.05, 0.2)

    entry_line_tatn = ax_tatn.axvline(ENTRY_IDX, color="#059669", ls="--", alpha=0, lw=1.5)
    exit_line_tatn = ax_tatn.axvline(EXIT_IDX, color="#16a34a", ls="--", alpha=0, lw=1.5)
    entry_line_tatnp = ax_tatnp.axvline(ENTRY_IDX, color="#059669", ls="--", alpha=0, lw=1.5)
    exit_line_tatnp = ax_tatnp.axvline(EXIT_IDX, color="#16a34a", ls="--", alpha=0, lw=1.5)
    long_shade = ax_spread.axvspan(0, 0, alpha=0.08, color="#059669", visible=False)

    status = fig.text(0.5, 0.01, "", ha="center", fontsize=9, color="#374151")
    pnl_box = fig.text(
        0.98, 0.48, "", ha="right", va="top", fontsize=9, family="monospace",
        bbox=dict(boxstyle="round,pad=0.55", facecolor="#fffbeb", edgecolor="#d97706", alpha=0.97),
        visible=False,
    )

    tatn_artists: list = []
    tatnp_artists: list = []
    spread_pnl_texts: list = []

    def pnl_text() -> str:
        return (
            f"── Итог LONG ({NOTIONAL/1000:.0f}k x{LEVERAGE:.0f}) ──\n"
            f"TATN:  {fmt_rub(TATN_PNL)}\n"
            f"TATNP: {fmt_rub(TATNP_PNL)}\n"
            f"Пара:  {fmt_rub(GROSS_PAIR)} (= сумма ног)\n"
            f"Комис: -{COMMISSION:,.0f} руб\n"
            f"Чист:  {fmt_rub(NET_PAIR)}\n"
            f"Спред: {ENTRY_SPREAD:.2f}% -> {EXIT_SPREAD:.2f}% ({PNL_SPREAD_PP:+.2f} п.п.)"
        ).replace(",", " ")

    def clear_spread_pnl_labels():
        for t in spread_pnl_texts:
            t.remove()
        spread_pnl_texts.clear()

    def draw_spread_pnl_labels(idx: int):
        clear_spread_pnl_labels()
        y_lo, y_hi = ax_spread.get_ylim()
        for i in range(ENTRY_IDX, min(idx, EXIT_IDX) + 1):
            if np.isnan(PAIR_PNL_SERIES[i]):
                continue
            pnl = PAIR_PNL_SERIES[i]
            t = ax_spread.text(
                i, SPREAD[i] + (y_hi - y_lo) * 0.06,
                fmt_pnl_short(pnl),
                ha="center", va="bottom", fontsize=7, fontweight="bold",
                color=pnl_color(pnl), zorder=6,
                bbox=dict(boxstyle="round,pad=0.12", facecolor="white", edgecolor="#2563eb", alpha=0.9),
            )
            spread_pnl_texts.append(t)

    def update(frame: int):
        nonlocal tatn_artists, tatnp_artists
        i = frame + 1
        idx = i - 1

        for art in tatn_artists + tatnp_artists:
            art.remove()
        tatn_artists = draw_candles_with_pnl(ax_tatn, TATN, i, idx, TATN_PNL_SERIES)
        tatnp_artists = draw_candles_with_pnl(ax_tatnp, TATNP, i, idx, TATNP_PNL_SERIES)

        spread_line.set_data(x[:i], SPREAD[:i])
        z_line.set_data(x[:i], Z[:i])
        ax_spread.set_ylim(SPREAD[:i].min() - 0.22, SPREAD[:i].max() + 0.28)
        draw_spread_pnl_labels(idx)

        show_entry = idx >= ENTRY_IDX
        show_exit = idx >= EXIT_IDX
        for ln in (entry_line_tatn, entry_line_tatnp):
            ln.set_alpha(0.75 if show_entry else 0)
        for ln in (exit_line_tatn, exit_line_tatnp):
            ln.set_alpha(0.75 if show_exit else 0)

        if ENTRY_IDX <= idx < EXIT_IDX:
            long_shade.set_visible(True)
            long_shade.set_xy((ENTRY_IDX - 0.45, ax_spread.get_ylim()[0]))
            long_shade.set_width(max(0.2, idx - ENTRY_IDX + 0.9))
            long_shade.set_height(ax_spread.get_ylim()[1] - ax_spread.get_ylim()[0])
        elif idx >= EXIT_IDX:
            long_shade.set_visible(True)
            long_shade.set_xy((ENTRY_IDX - 0.45, ax_spread.get_ylim()[0]))
            long_shade.set_width(EXIT_IDX - ENTRY_IDX + 0.9)
            long_shade.set_height(ax_spread.get_ylim()[1] - ax_spread.get_ylim()[0])
        else:
            long_shade.set_visible(False)

        if ENTRY_IDX <= idx <= EXIT_IDX:
            t_pnl = TATN_PNL_SERIES[min(idx, EXIT_IDX)]
            p_pnl = TATNP_PNL_SERIES[min(idx, EXIT_IDX)]
            pair = PAIR_PNL_SERIES[min(idx, EXIT_IDX)]
            status.set_text(
                f"Бар {LABELS[idx]}: TATN {fmt_pnl_short(t_pnl)} · TATNP {fmt_pnl_short(p_pnl)} · "
                f"пара {fmt_pnl_short(pair)} · спред {SPREAD[idx]:.2f}%"
            )
        elif idx < ENTRY_IDX:
            status.set_text(f"Бар {LABELS[idx]}: FLAT · спред {SPREAD[idx]:.2f}%")
        else:
            status.set_text(f"Бар {LABELS[idx]}: FLAT после выхода")

        if idx >= EXIT_IDX:
            pnl_box.set_text(pnl_text())
            pnl_box.set_visible(True)
        else:
            pnl_box.set_visible(False)

        return tatn_artists + tatnp_artists + spread_pnl_texts + [spread_line, z_line, status, pnl_box, long_shade]

    frames: list[int] = []
    for i in range(N):
        hold = 10 if i in (ENTRY_IDX, EXIT_IDX) else 3
        if i == N - 1:
            hold = 14
        frames.extend([i] * hold)

    anim = FuncAnimation(fig, update, frames=frames, interval=200, blit=False, repeat=True)
    fig.suptitle("LONG TATN/TATNP: PnL на каждой свече сделки", fontsize=13, fontweight="bold", y=0.98)
    writer = FFMpegWriter(
        fps=10,
        codec="libx264",
        metadata={"title": "LONG legs per-candle PnL"},
        bitrate=3400,
        extra_args=["-pix_fmt", "yuv420p", "-movflags", "+faststart", "-profile:v", "main"],
    )
    anim.save(OUT, writer=writer, dpi=120)
    plt.close(fig)
    print(f"Saved: {OUT}")


if __name__ == "__main__":
    main()
