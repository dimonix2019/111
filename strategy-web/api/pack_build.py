"""Сборка JSON pack: кэш графиков, параллельные секции."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

from api.chart_cache import zscore_chart_records
from api.serialize import (
    _sanitize,
    _series_records,
    _ts_unix_series,
    latest_quote_from_csv,
)
from zsim import Bar, SimResult


def pack_to_dict_fast(
    pack: dict,
    *,
    csv_path: str = "",
    bars: list[Bar] | None = None,
) -> dict:
    res: SimResult = pack["result"]
    entry = float(pack["entry"])
    exit_z = float(pack["exit_z"])
    path = Path(csv_path) if csv_path else None

    def build_trades() -> list[dict]:
        return [
            {
                "no": i,
                "direction": t.direction.value,
                "entry_time": t.entry_time,
                "exit_time": t.exit_time,
                "entry_spread": t.entry_spread,
                "exit_spread": t.exit_spread,
                "entry_z": t.entry_z,
                "exit_z": t.exit_z,
                "pnl_spread_pts": t.pnl_spread_pts,
                "commission_rub": t.commission_rub,
                "overnight_rub": t.overnight_rub,
                "pnl_rub": t.pnl_rub,
                "exit_reason": t.exit_reason,
                "legs": [
                    {
                        "ticker": leg.ticker,
                        "side": leg.side,
                        "entry_price": float(leg.entry_price),
                        "exit_price": float(leg.exit_price),
                        "qty": round(float(leg.qty), 2),
                        "pnl_rub": round(float(leg.pnl_rub), 2),
                    }
                    for leg in t.legs
                ],
            }
            for i, t in enumerate(res.trades, start=1)
        ]

    def build_equity() -> list[dict]:
        return _series_records(res.equity_frame(), ["equity_rub", "drawdown_rub"])

    def build_zscore() -> list[dict]:
        if path and bars:
            return zscore_chart_records(path, bars)
        return _series_records(res.zscore_frame(), ["z_score", "spread_percent"])

    def build_markers() -> list[dict]:
        markers = res.trade_markers_frame()
        if markers.empty:
            return []
        m_times = _ts_unix_series(markers["timestamp"])
        return [
            {
                "trade_no": int(row.trade_no),
                "event": row.event,
                "time": int(m_times[i]),
                "z_score": float(row.z_score),
                "direction": row.direction,
                "entry_time": row.entry_time,
                "exit_time": row.exit_time,
                "pnl_rub": float(row.pnl_rub),
                "pnl_spread_pts": float(row.pnl_spread_pts),
                "marker_symbol": row.marker_symbol,
                "marker_color": row.marker_color,
            }
            for i, row in enumerate(markers.itertuples(index=False))
        ]

    def build_idle_bundle() -> tuple[dict, dict, dict]:
        idle = res.idle_gaps_analysis()
        prec = res.idle_precursors(entry, exit_z, idle=idle)
        ctx = res.market_context(entry, exit_z, idle=idle)
        return idle, prec, ctx

    with ThreadPoolExecutor(max_workers=4) as pool:
        fut_trades = pool.submit(build_trades)
        fut_equity = pool.submit(build_equity)
        fut_z = pool.submit(build_zscore)
        fut_markers = pool.submit(build_markers)
        fut_idle = pool.submit(build_idle_bundle)
        trades = fut_trades.result()
        equity = fut_equity.result()
        zscore = fut_z.result()
        trade_markers = fut_markers.result()
        idle, idle_precursors, market_context = fut_idle.result()

    latest_quote = latest_quote_from_csv(csv_path) if csv_path else {}

    return {
        "label": pack["label"],
        "entry": entry,
        "exit_z": exit_z,
        "latest_quote": latest_quote,
        "stats": _sanitize(res.stats()),
        "trades": trades,
        "equity": equity,
        "zscore": zscore,
        "trade_markers": trade_markers,
        "unrealized_pnl_rub": res.unrealized_pnl_rub,
        "idle_gaps": _sanitize(idle),
        "idle_precursors": _sanitize(idle_precursors),
        "market_context": _sanitize(market_context),
        "risk": _sanitize(res.risk_metrics()),
    }
