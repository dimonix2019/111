#!/usr/bin/env python3
"""Анимация SHORT round-trip: свечной Z-график, пересечение entry/exit на соседних 15м барах."""

from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.animation import FFMpegWriter, FuncAnimation
from matplotlib.patches import Rectangle
import numpy as np

ENTRY = 0.7
EXIT = 0.5
OUT = Path("/opt/cursor/artifacts/short_trade_z_animation.mp4")
CANDLE_W = 0.55
BULL = "#16a34a"
BEAR = "#dc2626"
WICK = "#374151"

# Упрощённый сценарий SHORT: 06:45 вход → 10:00 выход (как в журнале 10.06)
LABELS = [
    "06:30", "06:45", "07:00", "07:15", "07:30", "07:45",
    "08:00", "08:15", "08:30", "08:45", "09:00", "09:15",
    "09:30", "09:45", "10:00", "10:15",
]
Z_CLOSE = np.array([
    0.40, 0.85, 0.90, 0.92, 0.88, 0.86,
    0.84, 0.82, 0.78, 0.72, 0.65, 0.60,
    0.58, 0.56, 0.42, 0.35,
])
ENTRY_IDX = 1
EXIT_IDX = 14
N = len(LABELS)


def build_ohlc(close: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """OHLC Z-свечи: open = close предыдущего бара, тени ±0.04…0.06."""
    n = len(close)
    open_ = np.zeros(n)
    high = np.zeros(n)
    low = np.zeros(n)
    for i in range(n):
        open_[i] = close[i - 1] if i > 0 else close[i] - 0.05
        body_hi = max(open_[i], close[i])
        body_lo = min(open_[i], close[i])
        wick = 0.04 + 0.02 * (i % 3)
        high[i] = body_hi + wick
        low[i] = max(-0.05, body_lo - wick)
    return open_, high, low, close


O, H, L, C = build_ohlc(Z_CLOSE)


def draw_candles(ax: plt.Axes, count: int, highlight_idx: int | None) -> list:
    artists: list = []
    for i in range(count):
        o, h, l, c = O[i], H[i], L[i], C[i]
        bull = c >= o
        color = BULL if bull else BEAR
        if i == highlight_idx:
            color = "#7c3aed"
        (wick,) = ax.plot([i, i], [l, h], color=WICK if i != highlight_idx else color, linewidth=1.2, zorder=2)
        artists.append(wick)
        bottom = min(o, c)
        height = max(abs(c - o), 0.025)
        rect = Rectangle(
            (i - CANDLE_W / 2, bottom),
            CANDLE_W,
            height,
            facecolor=color,
            edgecolor=color,
            linewidth=1.2 if i == highlight_idx else 1.0,
            zorder=3,
            alpha=0.95 if i != highlight_idx else 1.0,
        )
        ax.add_patch(rect)
        artists.append(rect)
    return artists


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)

    fig, ax = plt.subplots(figsize=(10.5, 5.8), dpi=120)
    fig.patch.set_facecolor("#fafafa")
    ax.set_facecolor("#ffffff")

    x = np.arange(N)
    ax.axhline(ENTRY, color="#ea580c", linestyle="--", linewidth=1.5, alpha=0.9, zorder=1)
    ax.axhline(EXIT, color="#16a34a", linestyle="--", linewidth=1.5, alpha=0.75, zorder=1)
    ax.axhline(0, color="#9ca3af", linestyle="-", linewidth=0.8, alpha=0.45, zorder=1)

    ax.text(N - 0.35, ENTRY + 0.03, f"entry = {ENTRY}", color="#ea580c", fontsize=11, ha="right", zorder=6)
    ax.text(N - 0.35, EXIT + 0.03, f"exit = {EXIT}", color="#16a34a", fontsize=11, ha="right", zorder=6)

    ax.set_xlim(-0.55, N - 0.45)
    ax.set_ylim(-0.15, 1.12)
    ax.set_xticks(x)
    ax.set_xticklabels(LABELS, rotation=45, ha="right", fontsize=9)
    ax.set_ylabel("Z-score (OHLC свечи)", fontsize=11)
    ax.set_xlabel("15м бары (MSK, 10.06.2026)", fontsize=11)
    ax.set_title("SHORT: свечной Z-график · пересечение порога на соседних барах", fontsize=13, fontweight="bold", pad=12)
    ax.grid(True, alpha=0.22, zorder=0)

    status = ax.text(
        0.02, 0.97, "", transform=ax.transAxes, fontsize=10, va="top",
        bbox=dict(boxstyle="round,pad=0.4", facecolor="#fef3c7", edgecolor="#f59e0b", alpha=0.95), zorder=10,
    )
    pos_text = ax.text(
        0.98, 0.97, "Позиция: FLAT", transform=ax.transAxes, fontsize=10, ha="right", va="top",
        bbox=dict(boxstyle="round,pad=0.4", facecolor="#e0e7ff", edgecolor="#6366f1", alpha=0.95), zorder=10,
    )
    ohlc_text = ax.text(
        0.5, 0.02, "", transform=ax.transAxes, fontsize=9, ha="center", va="bottom",
        color="#4b5563", zorder=10,
    )

    cross_ann = ax.annotate(
        "", xy=(0, 0), xytext=(0, 0), textcoords="data",
        arrowprops=dict(arrowstyle="->", color="#dc2626", lw=2),
        fontsize=10, color="#991b1b", fontweight="bold", visible=False, zorder=10,
    )
    shade = ax.axvspan(0, 0, ymin=0, ymax=1, alpha=0.10, color="#6366f1", visible=False, zorder=0)

    bull_patch = mpatches.Patch(color=BULL, label="Z↑ (close ≥ open)")
    bear_patch = mpatches.Patch(color=BEAR, label="Z↓ (close < open)")
    short_patch = mpatches.Patch(color="#6366f1", alpha=0.2, label="SHORT")
    ax.legend(handles=[bull_patch, bear_patch, short_patch], loc="upper left", fontsize=8, framealpha=0.9)

    candle_artists: list = []

    def frame_title(i: int) -> str:
        if i < ENTRY_IDX:
            return f"Бар {LABELS[i]}: FLAT, close Z={C[i]:.2f}"
        if i < EXIT_IDX:
            return f"Бар {LABELS[i]}: SHORT открыт, close Z={C[i]:.2f}"
        if i == EXIT_IDX:
            return f"Бар {LABELS[i]}: выход SHORT, close Z={C[i]:.2f}"
        return f"Бар {LABELS[i]}: FLAT после выхода, close Z={C[i]:.2f}"

    def update(frame: int):
        nonlocal candle_artists
        i = frame + 1
        idx = i - 1

        for art in candle_artists:
            art.remove()
        candle_artists = draw_candles(ax, i, highlight_idx=idx)

        status.set_text(frame_title(idx))
        ohlc_text.set_text(
            f"Текущая свеча {LABELS[idx]}: O={O[idx]:.2f}  H={H[idx]:.2f}  L={L[idx]:.2f}  C={C[idx]:.2f}"
        )

        if idx < ENTRY_IDX:
            pos_text.set_text("Позиция: FLAT")
            pos_text.set_bbox(dict(boxstyle="round,pad=0.4", facecolor="#e0e7ff", edgecolor="#6366f1", alpha=0.95))
            shade.set_visible(False)
        elif idx < EXIT_IDX:
            pos_text.set_text("Позиция: SHORT")
            pos_text.set_bbox(dict(boxstyle="round,pad=0.4", facecolor="#ede9fe", edgecolor="#7c3aed", alpha=0.95))
            shade.set_visible(True)
            shade.set_xy((ENTRY_IDX - 0.5, -0.15))
            shade.set_width(max(0.15, idx - ENTRY_IDX + 1.0))
            shade.set_height(1.27)
        else:
            pos_text.set_text("Позиция: FLAT")
            pos_text.set_bbox(dict(boxstyle="round,pad=0.4", facecolor="#dcfce7", edgecolor="#16a34a", alpha=0.95))
            shade.set_visible(True)
            shade.set_xy((ENTRY_IDX - 0.5, -0.15))
            shade.set_width(EXIT_IDX - ENTRY_IDX + 1.0)
            shade.set_height(1.27)

        cross_ann.set_visible(False)
        if idx == ENTRY_IDX:
            cross_ann.xy = (ENTRY_IDX, C[ENTRY_IDX])
            cross_ann.xyann = (ENTRY_IDX - 1, C[ENTRY_IDX - 1])
            cross_ann.set_text("EnterShort\nprev close<0.7 → close≥0.7")
            cross_ann.set_visible(True)
        elif idx == EXIT_IDX:
            cross_ann.xy = (EXIT_IDX, C[EXIT_IDX])
            cross_ann.xyann = (EXIT_IDX - 1, C[EXIT_IDX - 1])
            cross_ann.set_text("ExitShort\nprev close>0.5 → close≤0.5")
            cross_ann.set_visible(True)

        return candle_artists + [status, pos_text, ohlc_text, cross_ann, shade]

    frames: list[int] = []
    for i in range(N):
        hold = 8 if i in (ENTRY_IDX, EXIT_IDX) else 3
        frames.extend([i] * hold)

    anim = FuncAnimation(fig, update, frames=frames, interval=180, blit=False, repeat=True)
    writer = FFMpegWriter(fps=6, metadata={"title": "SHORT Z-candles"}, bitrate=2800)
    anim.save(OUT, writer=writer, dpi=120)
    plt.close(fig)
    print(f"Saved: {OUT} ({OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
