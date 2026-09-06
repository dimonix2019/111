# PnL sources policy

Prod = broker (GetPortfolio.expectedYield). Test = sim output. Chips = UI filter layer only — never a PnL engine.

| Контекст | Источник | Файл |
|----------|----------|------|
| Prod открытая позиция (desk/APK) | `GetPortfolio.expectedYield` → `mark.pnl_source=tinkoff_expected_yield`, API `pnl_source=broker_expected_yield` | `live/open_mark.py`, `live/tinvest.py`, `live/trade_api.py`, `live/portfolio_api.py` |
| Prod закрытая сделка | Брокер: Δ счёта / yield на выходе; path metrics — `closed_metrics` (не display PnL) | `live/closed_metrics.py`, `live/portfolio_api.py` |
| Prod прогноз закрытия | `expected_yield_rub` из broker mark; без ISS MTM | `live/close_forecast.py`, `replay/static/trade.js` |
| Test «Итого PnL» | `buildTradeSimSummary(filtered rows)` — sim net + open MTM | `replay/static/app.js`, `replay/static/replay-sim.js` |
| Test chip delta (donut) | `by_tag` / `chip_delta` из sim summary; фильтр UI | `replay/static/app.js` |
| Test monthly PnL | Закрытые sim-строки после `filterTradeRows` | `replay/static/app.js` (`renderMonthlyPnl`) |
| APK Prod PnL | `TinkoffSandboxApi` / `MoexOpenTradeEnrichment` expectedYield | `app/.../MoexOpenTradeEnrichment.kt`, `TinkoffSandboxApi.kt` |

Backend `closed_metrics`, `open_mark` (spread snap), `close_forecast` — вспомогательные; не подменяют broker display когда есть expectedYield.
