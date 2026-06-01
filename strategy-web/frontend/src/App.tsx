import { useCallback, useState } from 'react'
import type { SimPack, SimParams, SimResponse, StressResult } from '@/types'
import { fmtRub } from '@/lib/api'
import { Sidebar } from '@/components/layout/Sidebar'
import { TimeSeriesChart } from '@/components/charts/TimeSeriesChart'
import { SweepHeatmap } from '@/components/charts/SweepHeatmap'
import { TradesTable } from '@/components/trades/TradesTable'
import { Panel } from '@/components/ui/Panel'
import { PanelErrorBoundary } from '@/components/ui/PanelErrorBoundary'
import { MetricsOverview } from '@/components/overview/MetricsOverview'
import { ZModePanel } from '@/components/market/ZModePanel'
import { OosPanel } from '@/components/overview/OosPanel'
import { RiskStressPanel } from '@/components/overview/RiskStressPanel'
import { PeriodSelector } from '@/components/charts/PeriodSelector'
import { MarketTab, type MarketLiveControls } from '@/components/market/MarketTab'
import type { MarketPeriod } from '@/lib/marketPeriod'
import { BasisCarryTab } from '@/components/basis/BasisCarryTab'
import { ProtectionGridPanel } from '@/components/optimize/ProtectionGridPanel'
import { CompareTab } from '@/components/overview/CompareTab'
import { useSweepHeatmap } from '@/hooks/useSweepHeatmap'

type Tab = 'market' | 'overview' | 'charts' | 'trades' | 'optimize' | 'basis' | 'compare'

const EMPTY_LIVE: MarketLiveControls = {
  liveMode: true,
  setLiveMode: () => {},
  online5s: false,
  setOnline5s: () => {},
  loading: false,
  pending: false,
  stale: false,
  lastMs: null,
  lastUpdatedAt: null,
  runNow: () => {},
  refreshMoex: async () => {},
}

function Overview({
  pack,
  result,
  stress,
  onStressUpdate,
  simContext,
}: {
  pack: SimPack
  result: SimResponse
  stress: StressResult | null
  onStressUpdate: (s: StressResult) => void
  simContext: { params: SimParams; csvPath: string; compare: boolean }
}) {
  return (
    <div className="space-y-4">
      <MetricsOverview stats={pack.stats} unrealizedRub={pack.unrealized_pnl_rub} />
      <ZModePanel
        zMode={result.z_mode ?? result.hq.z_mode ?? 'rolling30'}
        diag={result.z_diagnostics}
        compare={result.z_compare ?? undefined}
      />
      <RiskStressPanel
        risk={pack.risk}
        stress={stress}
        params={simContext.params}
        csvPath={simContext.csvPath}
        compare={simContext.compare}
        onStressUpdate={onStressUpdate}
      />
      {result.oos ? <OosPanel oos={result.oos} /> : null}
    </div>
  )
}

function Charts({ pack, liveMode }: { pack: SimPack; liveMode?: boolean }) {
  const hlines = [
    { value: pack.entry, color: 'rgba(212,184,106,0.55)', title: `+Entry ${pack.entry}` },
    { value: -pack.entry, color: 'rgba(212,184,106,0.55)', title: `-Entry ${pack.entry}` },
    { value: pack.exit_z, color: 'rgba(148,163,184,0.35)', title: `+Exit ${pack.exit_z}` },
    { value: -pack.exit_z, color: 'rgba(148,163,184,0.35)', title: `-Exit ${pack.exit_z}` },
  ]
  return (
    <PanelErrorBoundary title="Графики">
      <div className="space-y-3">
        <TimeSeriesChart
          title="Кривая капитала · просадка"
          color="#5ec492"
          valueFormat="rub"
          height={300}
          data={pack.equity.map((e) => ({ time: e.time, value: e.equity_rub }))}
          markers={pack.trade_markers}
          trades={pack.trades}
          equity={pack.equity}
          showDrawdownOverlay
          drawdownOverlayVariant="combined"
          liveMode={liveMode}
        />
        <TimeSeriesChart
          title="Spread % 15м"
          color="#c9b06a"
          valueFormat="float"
          data={pack.zscore.map((z) => ({ time: z.time, value: z.spread_percent }))}
          markers={pack.trade_markers}
          trades={pack.trades}
          equity={pack.equity}
          liveMode={liveMode}
        />
        <TimeSeriesChart
          title="Z-score 15м"
          color="#8eb4c4"
          valueFormat="float"
          data={pack.zscore.map((z) => ({ time: z.time, value: z.z_score }))}
          markers={pack.trade_markers}
          trades={pack.trades}
          equity={pack.equity}
          hlines={hlines}
          liveMode={liveMode}
        />
      </div>
    </PanelErrorBoundary>
  )
}

function Optimize({
  result,
  pack,
  simContext,
  onApply,
}: {
  result: SimResponse
  pack: SimPack
  simContext: { params: SimParams; csvPath: string; compare: boolean }
  onApply: (patch: Partial<SimParams>) => void
}) {
  const { rows, loading, error, lastMs, refresh } = useSweepHeatmap(result, true)

  return (
    <div className="space-y-4">
      <Panel title="Heatmap Entry × Exit">
        <p className="mb-2 text-[11px] text-ink-3">
          Пересчёт при смене плеча/комиссии/номинала (debounce ~0,5 с)
          {loading ? ' · считаем…' : lastMs != null ? ` · ${lastMs} мс` : ''}
        </p>
        {error ? <p className="mb-2 text-[11px] text-bad">{error}</p> : null}
        <button type="button" className="tab-btn mb-3 text-[11px]" disabled={loading} onClick={refresh}>
          Обновить heatmap
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
        ) : loading ? (
          <p className="text-[12px] text-ink-3">Строим сетку Entry × Exit…</p>
        ) : null}
      </Panel>

      <ProtectionGridPanel
        csvPath={simContext.csvPath}
        simKw={result.sim_kw}
        params={simContext.params}
        onApply={onApply}
      />
    </div>
  )
}

export default function App() {
  const [tab, setTab] = useState<Tab>('market')
  const [marketPeriod, setMarketPeriod] = useState<MarketPeriod>('1M')
  const [simLoading, setSimLoading] = useState(false)
  const [result, setResult] = useState<SimResponse | null>(null)
  const [live, setLive] = useState<MarketLiveControls>(EMPTY_LIVE)
  const [stress, setStress] = useState<StressResult | null>(null)
  const [simContext, setSimContext] = useState<{ params: SimParams; csvPath: string; compare: boolean }>({
    params: {},
    csvPath: '',
    compare: false,
  })
  const [paramsPatch, setParamsPatch] = useState<Partial<SimParams> | null>(null)
  const [paramsPatchTick, setParamsPatchTick] = useState(0)

  const onLiveControls = useCallback((ctrl: MarketLiveControls) => setLive(ctrl), [])
  const onApplyParams = useCallback((patch: Partial<SimParams>) => {
    setParamsPatch(patch)
    setParamsPatchTick((t) => t + 1)
  }, [])

  const pack = result?.packs[0] ?? null

  const tabs: { id: Tab; label: string; hint: string }[] = [
    { id: 'market', label: 'Рынок', hint: 'Z-score live, MOEX, предвестники, гистограммы' },
    { id: 'overview', label: 'Обзор', hint: 'Метрики, OOS, stress-тест' },
    { id: 'charts', label: 'Графики', hint: 'Equity, DD, spread, Z-score' },
    { id: 'trades', label: 'Сделки', hint: 'Журнал, pivot, распределения, CSV, чат' },
    { id: 'optimize', label: 'Оптимизация', hint: 'Heatmap + OOS grid' },
    { id: 'basis', label: 'Basis', hint: 'Spot–futures carry MOEX FORTS' },
    { id: 'compare', label: 'Сравнение', hint: 'A / B overlay equity и метрики' },
  ]

  return (
    <div className="flex h-dvh flex-col overflow-hidden p-4 md:p-5">
      <header className="mb-4 flex shrink-0 items-center justify-between gap-4">
        <div>
          <div className="text-[12px] font-semibold tracking-[0.28em] text-ink-3">Z-STRATEGY</div>
          <h1 className="text-xl font-bold text-ink-1">TATN / TATNP · Backtester</h1>
          <p className="text-[12px] text-ink-3">Vite + React · Python API</p>
        </div>
        {result ? (
          <div className="flex flex-wrap items-center gap-2">
            {simLoading ? <span className="surface-chip text-[11px] text-warn">Обновление…</span> : null}
            {tab === 'market' && live.online5s ? (
              <span className="surface-chip text-[11px] text-good">live 5с</span>
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
        <Sidebar
          onResult={setResult}
          onLoading={setSimLoading}
          onLiveControls={onLiveControls}
          onSimContext={setSimContext}
          marketPollActive={tab === 'market'}
          paramsPatch={paramsPatch}
          paramsPatchTick={paramsPatchTick}
        />
        <main className="flex min-h-0 min-w-0 flex-1 flex-col">
          {!pack || !result ? (
            <Panel>
              <p className="text-ink-2">Live-режим считает автоматически. Подождите первый расчёт…</p>
              <p className="mt-2 text-[11px] text-ink-3">
                Rolling 30д (по умолчанию): ~280 сделок · Global 255д / пресет «Как Android»: ~140 сделок при 0.8/0.7.
              </p>
            </Panel>
          ) : (
            <>
              <div className="segmented-track mb-3 shrink-0 border-b border-panel-border-soft/50 bg-[rgba(4,13,25,0.94)] py-2">
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
              {tab === 'market' ? (
                <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto pr-1">
                  <div className="market-period-bar sticky top-0 z-[110] mb-2 shrink-0 border-b border-panel-border-soft/60 bg-[rgba(4,13,25,0.98)] px-1 py-2 shadow-[0_4px_12px_rgba(0,0,0,0.35)] backdrop-blur-sm">
                    <PeriodSelector selected={marketPeriod} onSelect={setMarketPeriod} />
                  </div>
                  <MarketTab
                    pack={pack}
                    result={result}
                    live={live}
                    period={marketPeriod}
                    dataStale={result.data_meta?.is_stale}
                    dataAgeHours={result.data_meta?.age_hours ?? null}
                  />
                </div>
              ) : (
                <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto pr-1">
                  {tab === 'overview' && (
                    <Overview
                      pack={pack}
                      result={result}
                      stress={stress}
                      onStressUpdate={setStress}
                      simContext={simContext}
                    />
                  )}
                  {tab === 'charts' && <Charts pack={pack} liveMode={live.liveMode} />}
                  {tab === 'trades' && <TradesTable pack={pack} csvPath={result.path} />}
                  {tab === 'optimize' && (
                    <Optimize result={result} pack={pack} simContext={simContext} onApply={onApplyParams} />
                  )}
                  {tab === 'basis' && <BasisCarryTab />}
                  {tab === 'compare' && <CompareTab packs={result.packs} compareMode={result.compare_mode} />}
                </div>
              )}
            </>
          )}
        </main>
      </div>
    </div>
  )
}
