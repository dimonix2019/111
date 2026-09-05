"""One-bar 1m spread spikes (dealer prints / weekend garbage).

Do not clip every bar near 4.5%: a weekday grind 3.2→4.5 or Long exit 4.0 can
be real. Cut only an isolated needle: |ΔS| vs both nearby neighbors is large,
or a TATN/TATNP leg jumps ~8₽ in 1–3 minutes on dealer/weekend.
"""

from __future__ import annotations

from typing import Any, Sequence

# Isolated |ΔS| vs both sides (percentage points) inside 1–3 minutes.
SPREAD_1M_SPIKE_PP = 0.8
# Last/forming bar has no right neighbor — slightly stricter vs previous.
SPREAD_1M_SPIKE_LAST_PP = 1.0
# Look left/right this many 1m bars (covers a 2–3 minute bad print).
SPREAD_1M_SPIKE_NEIGHBOR_BARS = 3
SPREAD_1M_SPIKE_MAX_DT_MS = 3 * 60_000
# Unrealistic TATN/TATNP jump on dealer / weekend (₽ per 1–3 minutes).
SPREAD_1M_LEG_JUMP_RUB = 8.0


def _finite(v: Any) -> float | None:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    if f != f:  # NaN
        return None
    return f


def _cluster_bounds(
    spreads: Sequence[float],
    ts_ms: Sequence[int],
    index: int,
    *,
    jump_pp: float,
    max_dt_ms: int,
) -> tuple[int, int]:
    """Inclusive [lo, hi] of bars in the same needle as ``index`` (1–3 min)."""
    n = len(spreads)
    cur = _finite(spreads[index])
    if cur is None:
        return index, index
    lo = hi = index
    while lo > 0:
        dt = int(ts_ms[lo]) - int(ts_ms[lo - 1])
        if dt <= 0 or dt > max_dt_ms:
            break
        prev = _finite(spreads[lo - 1])
        here = _finite(spreads[lo])
        if prev is None or here is None or abs(prev - here) >= jump_pp:
            break
        lo -= 1
    while hi + 1 < n:
        dt = int(ts_ms[hi + 1]) - int(ts_ms[hi])
        if dt <= 0 or dt > max_dt_ms:
            break
        nxt = _finite(spreads[hi + 1])
        here = _finite(spreads[hi])
        if nxt is None or here is None or abs(nxt - here) >= jump_pp:
            break
        hi += 1
    return lo, hi


def is_isolated_spread_spike(
    spreads: Sequence[float],
    ts_ms: Sequence[int],
    index: int,
    *,
    jump_pp: float = SPREAD_1M_SPIKE_PP,
    last_pp: float = SPREAD_1M_SPIKE_LAST_PP,
    radius: int = SPREAD_1M_SPIKE_NEIGHBOR_BARS,
    max_dt_ms: int = SPREAD_1M_SPIKE_MAX_DT_MS,
) -> bool:
    """True if bar ``index`` sits in a 1–3 min needle vs both outer neighbors."""
    n = len(spreads)
    if n < 2 or index < 0 or index >= n:
        return False
    cur = _finite(spreads[index])
    if cur is None:
        return False
    lo, hi = _cluster_bounds(
        spreads, ts_ms, index, jump_pp=jump_pp, max_dt_ms=max_dt_ms
    )
    if int(ts_ms[hi]) - int(ts_ms[lo]) > max_dt_ms:
        return False
    left = None
    if lo > 0:
        dt = int(ts_ms[lo]) - int(ts_ms[lo - 1])
        if 0 < dt <= max_dt_ms:
            left = _finite(spreads[lo - 1])
    right = None
    if hi + 1 < n:
        dt = int(ts_ms[hi + 1]) - int(ts_ms[hi])
        if 0 < dt <= max_dt_ms:
            right = _finite(spreads[hi + 1])
    if left is not None and right is not None:
        if abs(left - right) >= jump_pp:
            return False
        return abs(cur - left) >= jump_pp and abs(cur - right) >= jump_pp
    if left is not None and right is None:
        # Forming/last cluster: a return to an earlier baseline is not a needle.
        for j in range(lo - 1, max(-1, lo - 1 - radius), -1):
            prev = _finite(spreads[j])
            if prev is not None and abs(prev - cur) < jump_pp:
                return False
        return abs(cur - left) >= last_pp
    return False


def is_unrealistic_leg_jump(
    prev_leg: float | None,
    cur_leg: float | None,
    next_leg: float | None = None,
    *,
    jump_rub: float = SPREAD_1M_LEG_JUMP_RUB,
) -> bool:
    """Dealer/weekend: a TATN or TATNP print jumped ~8₽ in a minute."""
    prev = _finite(prev_leg)
    cur = _finite(cur_leg)
    if prev is None or cur is None:
        return False
    if abs(cur - prev) < jump_rub:
        return False
    nxt = _finite(next_leg)
    if nxt is None:
        return True
    # Isolated: next snaps back toward prev (neighbors agree).
    return abs(nxt - prev) < jump_rub * 0.5


def spread_spike_indices(
    spreads: Sequence[float],
    ts_ms: Sequence[int],
    *,
    tatn: Sequence[float] | None = None,
    tatnp: Sequence[float] | None = None,
    dealer_legs: bool = False,
    jump_pp: float = SPREAD_1M_SPIKE_PP,
    last_pp: float = SPREAD_1M_SPIKE_LAST_PP,
    jump_rub: float = SPREAD_1M_LEG_JUMP_RUB,
    radius: int = SPREAD_1M_SPIKE_NEIGHBOR_BARS,
    max_dt_ms: int = SPREAD_1M_SPIKE_MAX_DT_MS,
) -> list[int]:
    n = len(spreads)
    if n != len(ts_ms) or n < 2:
        return []
    use_legs = bool(dealer_legs) and tatn is not None and tatnp is not None
    if use_legs and (len(tatn) != n or len(tatnp) != n):
        use_legs = False
    out: list[int] = []
    for i in range(n):
        if is_isolated_spread_spike(
            spreads,
            ts_ms,
            i,
            jump_pp=jump_pp,
            last_pp=last_pp,
            radius=radius,
            max_dt_ms=max_dt_ms,
        ):
            out.append(i)
            continue
        if not use_legs or i <= 0:
            continue
        dt = int(ts_ms[i]) - int(ts_ms[i - 1])
        if dt <= 0 or dt > max_dt_ms:
            continue
        nxt_n = tatn[i + 1] if i + 1 < n else None
        nxt_p = tatnp[i + 1] if i + 1 < n else None
        if is_unrealistic_leg_jump(tatn[i - 1], tatn[i], nxt_n, jump_rub=jump_rub) or (
            is_unrealistic_leg_jump(tatnp[i - 1], tatnp[i], nxt_p, jump_rub=jump_rub)
        ):
            out.append(i)
    return out


def _flatten_bar_to_prev(bar: dict[str, Any], prev: dict[str, Any]) -> None:
    sp = _finite(prev.get("spread"))
    if sp is None:
        return
    bar["spread"] = sp
    if prev.get("spread_open") is not None:
        bar["spread_open"] = prev.get("spread_open")
    else:
        bar["spread_open"] = sp
    bar["spread_high"] = sp
    bar["spread_low"] = sp
    if prev.get("tatn") is not None:
        bar["tatn"] = prev.get("tatn")
    if prev.get("tatnp") is not None:
        bar["tatnp"] = prev.get("tatnp")
    if prev.get("z") is not None:
        bar["z"] = prev.get("z")
    bar["spread_spike"] = True


def sanitize_dealer_spread_bars(
    bars: list[dict[str, Any]] | None,
    *,
    dealer_legs: bool = True,
) -> list[dict[str, Any]]:
    """Replace isolated dealer needles; flatten OHLC so the chart has no wick."""
    src = [b for b in (bars or []) if isinstance(b, dict)]
    if len(src) < 2:
        return src
    spreads = [b.get("spread") for b in src]
    ts = [int(b.get("timestampMs") or 0) for b in src]
    tatn = [b.get("tatn") for b in src]
    tatnp = [b.get("tatnp") for b in src]
    spikes = set(
        spread_spike_indices(
            spreads,
            ts,
            tatn=tatn,
            tatnp=tatnp,
            dealer_legs=dealer_legs,
        )
    )
    if not spikes:
        return src
    out = [dict(b) for b in src]
    for i in sorted(spikes):
        if i <= 0:
            continue
        _flatten_bar_to_prev(out[i], out[i - 1])
    return out


def guard_live_quote_against_spike(
    payload: dict[str, Any],
    bars: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """If LAST print is a needle vs last 1m bar, keep the last good bar spread."""
    out = dict(payload)
    series = bars if bars is not None else out.get("bars")
    if not isinstance(series, list) or not series:
        return out
    last = series[-1]
    last_sp = _finite(last.get("spread"))
    cur_sp = _finite(out.get("spread"))
    if last_sp is None or cur_sp is None:
        return out
    last_n = _finite(last.get("tatn"))
    last_p = _finite(last.get("tatnp"))
    cur_n = _finite(out.get("tatn"))
    cur_p = _finite(out.get("tatnp"))
    jump = abs(cur_sp - last_sp) >= SPREAD_1M_SPIKE_LAST_PP
    leg = False
    if last_n is not None and cur_n is not None:
        leg = leg or abs(cur_n - last_n) >= SPREAD_1M_LEG_JUMP_RUB
    if last_p is not None and cur_p is not None:
        leg = leg or abs(cur_p - last_p) >= SPREAD_1M_LEG_JUMP_RUB
    if not (jump or leg):
        return out
    out["spread"] = last_sp
    out["spread_last"] = last_sp
    if last_n is not None:
        out["tatn"] = last_n
    if last_p is not None:
        out["tatnp"] = last_p
    out["spread_spike"] = True
    return out


def sanitize_spread_arrays(
    spreads,
    ts_ms,
    *,
    z=None,
    tatn=None,
    tatnp=None,
    dealer_legs: bool = False,
):
    """Copy-on-write: replace spike closes with the previous bar. Returns dict."""
    import numpy as np

    sp = np.asarray(spreads, dtype=np.float64)
    ts = np.asarray(ts_ms, dtype=np.int64)
    z_arr = None if z is None else np.asarray(z, dtype=np.float64)
    tn = None if tatn is None else np.asarray(tatn, dtype=np.float64)
    tp = None if tatnp is None else np.asarray(tatnp, dtype=np.float64)
    idx = spread_spike_indices(
        sp.tolist(),
        ts.tolist(),
        tatn=None if tn is None else tn.tolist(),
        tatnp=None if tp is None else tp.tolist(),
        dealer_legs=dealer_legs,
    )
    if not idx:
        return {
            "spread": sp,
            "z": z_arr,
            "tatn": tn,
            "tatnp": tp,
            "n_spikes": 0,
            "copied": False,
        }
    sp = np.array(sp, copy=True)
    if z_arr is not None:
        z_arr = np.array(z_arr, copy=True)
    if tn is not None:
        tn = np.array(tn, copy=True)
    if tp is not None:
        tp = np.array(tp, copy=True)
    for i in idx:
        if i <= 0:
            continue
        sp[i] = sp[i - 1]
        if z_arr is not None and i < len(z_arr):
            z_arr[i] = z_arr[i - 1]
        if tn is not None and i < len(tn):
            tn[i] = tn[i - 1]
        if tp is not None and i < len(tp):
            tp[i] = tp[i - 1]
    return {
        "spread": sp,
        "z": z_arr,
        "tatn": tn,
        "tatnp": tp,
        "n_spikes": len(idx),
        "copied": True,
    }
