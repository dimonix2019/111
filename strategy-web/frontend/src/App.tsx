import { Panel } from '@/components/ui/Panel'import { useState } from 'react'
import type { SimPack, SimResponse } from '@/types'
import { fmtRub, sweep } from '@/lib/api'
import { Sidebar } from '@/components/layout/Sidebar'
import { MarketContextPanel } from '@/components/charts/MarketContextPanel'
import { TimeSeriesChart } from '@/components/charts/TimeSeriesChart'
import { ZScoreCandlestickChart } from '@/components/charts/ZScoreCandlestickChart'
import { SweepHeatmap } from '@/components/charts/SweepHeatmap'
import type { SweepRow } from '@/components/charts/SweepHeatmap'
import { TradesTable } from '@/components/trades/TradesTable'
import { Panel } from '@/components/ui/Panel'

import { MetricsOverview } from '@/components/overview/MetricsOverview'

type Tab = 'overview' | 'charts' | 'trades' | 'optimize'

function Overview({ pack }: { pack: SimPack }) {
  return (
    <div className="space-y-4">
      <MetricsOverview stats={pack.stats} unrealizedRub={pack.unrealized_pnl_rub} />
      {pack.market_context?.metrics?.length ? (
        <Panel title="Контекст рынка">
          <p className="mb-3 text-[11px] text-ink-3">
            Насколько текущие Z, spread и паузы отличаются от истории
          </p>
          <MarketContextPanel ctx={pack.market_context} />
        </Panel>
      ) : null}
    </div>
  )
}

function Charts({ pack }: { pack: SimPack }) {
  const hlines = [
    { value: pack.entry, color: 'rgba(212,184,106,0.55)', title: `+Entry ${pack.entry}` },
    { value: -pack.entry, color: 'rgba(212,184,106,0.55)', title: `-Entry ${pack.entry}` },
    { value: pack.exit_z, color: 'rgba(148,163,184,0.35)', title: `+Exit ${pack.exit_z}` },
    { value: -pack.exit_z, color: 'rgba(148,163,184,0.35)', title: `-Exit ${pack.exit_z}` },
  ]
  return (
    <div className="space-y-3">
      <TimeSeriesChart
        title="Кривая капитала"
        color="#5ec492"
        valueFormat="rub"
        data={pack.equity.map((e) => ({ time: e.time, value: e.equity_rub }))}
        markers={pack.trade_markers}
      />
      <TimeSeriesChart
        title="Spread % 15м"
        color="#c9b06a"
        valueFormat="float"
        data={pack.zscore.map((z) => ({ time: z.time, value: z.spread_percent }))}
        markers={pack.trade_markers}
      />
      <TimeSeriesChart
        title="Z-score 15м"
        color="#8eb4c4"
        valueFormat="float"
        data={pack.zscore.map((z) => ({ time: z.time, value: z.z_score }))}
        markers={pack.trade_markers}
        hlines={hlines}
      />
    </div>
  )
}


function Optimize({ result, pack }: { result: SimResponse; pack: SimPack }) {
  const [rows, setRows] = useState<SweepRow[] | null>(null)
  const [busy, setBusy] = useState(false)

  async function runSweep() {
    setBusy(true)
    try {
      const kw = result.sim_kw as Record<string, unknown>
      const res = await sweep({
        csv_path: result.path,
        notional_rub: kw.notional_rub,
        leverage: kw.leverage,
        commission_pct_per_side: kw.commission_pct_per_side,
        compound_returns: kw.compound_returns,
      })
      setRows(res.rows as SweepRow[])
    } catch (e) {
      alert(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Panel title="Heatmap Entry × Exit">
      <button type="button" className="primary mb-3 max-w-xs" disabled={busy} onClick={() => void runSweep()}>
        {busy ? 'Считаем…' : 'Построить heatmap'}
      </button>
      {rows ? (
        <>
          <SweepHeatmap rows={rows} currentEntry={pack.entry} currentExit={pack.exit_z} />
          <details className="mt-3 text-[12px] text-ink-3">
            <summary className="cursor-pointer text-ink-2 hover:text-ink-1">Таблица (топ-15 по PnL)</summary>
            <div className="scrollbar-thin mt-2 max-h-64 overflow-auto rounded-xl border border-panel-border-soft">
              <table className="w-full text-ink-2">
                <thead>
                  <tr>
                    <th className="px-2 py-1 text-left font-medium">Entry</th>
                    <th className="px-2 py-1 text-left font-medium">Exit</th>
                    <th className="px-2 py-1 text-left font-medium">PnL</th>
                    <th className="px-2 py-1 text-left font-medium">Сделок</th>
                  </tr>
                </thead>
                <tbody>
                  {rows
                    .slice()
                    .sort((a, b) => b.total_pnl_rub - a.total_pnl_rub)
                    .slice(0, 15)
                    .map((r, i) => (
                      <tr key={i} className="table-row">
                        <td className="px-2 py-1 tabular-nums">{r.entry}</td>
                        <td className="px-2 py-1 tabular-nums">{r.exit_z}</td>
                        <td className={`px-2 py-1 ${r.total_pnl_rub >= 0 ? 'text-good' : 'text-bad'}`}>
                          {fmtRub(r.total_pnl_rub)}
                        </td>
                        <td className="px-2 py-1">{r.trade_count}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          </details>
        </>
      ) : null}
    </Panel>
  )
}

export default function App() {
  const [tab, setTab] = useState<Tab>('overview')
  const [simLoading, setSimLoading] = useState(false)
  const [result, setResult] = useState<SimResponse | null>(null)
  const pack = result?.packs[0] ?? null

  const tabs: { id: Tab; label: string; hint: string }[] = [
    { id: 'overview', label: 'Обзор', hint: 'Метрики и рынок сейчас' },
    { id: 'charts', label: 'Графики', hint: 'Equity, spread, Z-score' },
    { id: 'trades', label: 'Сделки', hint: 'Журнал с фильтрами' },
    { id: 'optimize', label: 'Оптимизация', hint: 'Heatmap Entry × Exit' },
  ]

  return (
    <div className="flex h-dvh flex-col overflow-hidden p-4 md:p-5">
      <header className="mb-4 flex items-center justify-between gap-4">
        <div>
          <div className="text-[12px] font-semibold tracking-[0.28em] text-ink-3">Z-STRATEGY</div>
          <h1 className="text-xl font-bold text-ink-1">TATN / TATNP · Backtester</h1>
          <p className="text-[12px] text-ink-3">Vite + React · Python API</p>
        </div>
        {result ? (
          <div className="flex flex-wrap items-center gap-2">
            {simLoading ? (
              <span className="surface-chip text-[11px] text-warn">Обновление…</span>
            ) : null}
            {pack ? (
              <div className="surface-chip text-ink-2">
                Entry {pack.entry} · Exit {pack.exit_z} · {pack.stats.trade_count} сделок
              </div>
            ) : null}
            <div className="surface-chip text-ink-3">
              {result.hq.bar_count} баров · {result.hq.first_ts.slice(0, 10)} … {result.hq.last_ts.slice(0, 10)}
            </div>
          </div>
        ) : simLoading ? (
          <span className="text-[12px] text-ink-3">Загрузка данных…</span>
        ) : null}
      </header>

      <div className="flex min-h-0 flex-1 gap-4">
        <Sidebar onResult={setResult} onLoading={setSimLoading} />
        <main className="min-w-0 flex-1">
          {!pack ? (
            <Panel>
              <p className="text-ink-2">Live-режим считает автоматически. Подождите первый расчёт…</p>
            </Panel>
          ) : (
            <>
              <div className="segmented-track mb-4">
                {tabs.map((t) => (
                  <button
                    key={t.id}
                    type="button"
                    className={`tab-btn ${tab === t.id ? 'active' : ''}`}
                    title={t.hint}
                    onClick={() => setTab(t.id)}
                  >
                    {t.label}
                  </button>
                ))}
              </div>
              {tab === 'overview' && <Overview pack={pack} />}
              {tab === 'charts' && <Charts pack={pack} />}
              {tab === 'trades' && <TradesTable pack={pack} />}
              {tab === 'optimize' && result && pack && <Optimize result={result} pack={pack} />}
            </>
          )}
        </main>
      </div>
    </div>
  )
}
