"""Прореживание рядов для графиков: хвост без пропусков (свечи после последней сделки)."""

from __future__ import annotations

import numpy as np

CHART_MAX_POINTS = 2500
CHART_TAIL_FULL_BARS = 512


def chart_downsample_indices(length: int, max_pts: int = CHART_MAX_POINTS, tail_keep: int = CHART_TAIL_FULL_BARS) -> np.ndarray:
    """Равномерно по истории + последние tail_keep баров без прореживания."""
    if length <= 0:
        return np.array([], dtype=int)
    if length <= max_pts:
        return np.arange(length, dtype=int)

    tail_keep = min(tail_keep, max_pts - 1, length)
    head_len = length - tail_keep
    head_budget = max_pts - tail_keep
    if head_len <= 0 or head_budget <= 0:
        return np.arange(length, dtype=int)

    step = max(1, head_len // head_budget)
    head_idx = np.arange(0, head_len, step, dtype=int)
    tail_idx = np.arange(head_len, length, dtype=int)
    return np.unique(np.concatenate([head_idx, tail_idx]))
