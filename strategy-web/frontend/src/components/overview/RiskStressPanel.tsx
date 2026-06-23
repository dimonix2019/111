import { useState } from 'react'
import type { RiskMetrics, SimParams, StressResult } from '@/types'
import { fmtRub, simulate } from '@/lib/api'
import { Panel } from '@/components/ui/Panel'

type Props = {
  risk?: RiskMetrics
  stress?: StressResult | null
  params: SimParams
  csvPath: string
  compare: boolean
  onStressUpdate: (stress: StressResult) => void
}

function MetricRow({ label, value, tone }: { label: string; value: string; tone?: 'good' | 'bad' | 'neutral' }) {
  const cls =
    tone === 'good' ? 'text-good' : tone === 'bad' ? 'text-bad' : 'text-ink-1'
  return (
    <div className="rounded-xl border border-panel-border-soft bg-[rgba(8,16,28,0.5)] px-3 py-2">
      <div className="text-[11px] font-medium text-ink-3">{label}</div>
      <div className={`text-[14px] font-semibold tabular-nums ${cls}`}>{value}</div>
    </div>
  )
}

export function RiskStressPanel({ risk, stress, params, csvPath, compare, onStressUpdate }: Props) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (!risk) return null

  async function runStress() {
    setLoading(true)
    setError(null)
    try {
      const res = await simulate({
        ...params,
        csv_path: csvPath,
        compare_mode: compare,
        include_stress: true,
      })
      if (res.stress) onStressUpdate(res.stress)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  const stressRows: { key: keyof StressResult['baseline']; label: string }[] = [
    { key: 'total_pnl_rub', label: 'PnL итого' },
    { key: 'trade_count', label: 'Сделок' },
    { key: 'max_drawdown_rub', label: 'Max DD' },
    { key: 'win_rate_pct', label: 'Win Rate %' },
    { key: 'profit_factor', label: 'Profit Factor' },
    { key: 'total_commission_rub', label: 'Комиссии' },
  ]

  return (
    <Panel title="Риск и стресс-тест">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <button type="button" className="primary !py-1.5 !text-[12px]" disabled={loading} onClick={runStress}>
          {loading ? 'Считаем…' : 'Стресс ×2Scheduled'}
        </button>
        {risk.trading_halted ? (
          <span className="rounded-full border border-bad/40 bg-[rgba(209,122,136,0.12)] px-2.5 py-1 text-[11px] text-bad">
            Торговля остановлена: {risk.halt_reason || 'circuit breaker'}
          </span>
        ) : null}
      </div>
      {error ? <p className="mb-2 text-[11px] text-bad">{error}</p> : null}

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
        <MetricRow label="Худшая сделка" value={fmtRub(risk.worst_trade_rub)} tone="bad" />
        <MetricRow label="Лучшая сделка" value={fmtRub(risk.best_trade_rub)} tone="good" />
        <MetricRow
          label="Tail ratio"
          value={risk.tail_ratio != null ? risk.tail_ratio.toFixed(2) : '—'}
          tone={risk.tail_ratio != null && risk.tail_ratio > 2 ? 'bad' : 'neutral'}
        />
        <MetricRow label="Комиссия % от gross" value={`${risk.commission_pct_of_gross.toFixed(1)}%`} tone="neutral" />
        <MetricRow label="Top-10% концентрация" value={`${risk.top10_concentration_pct.toFixed(1)}%`} tone="neutral" />
        <MetricRow label="Stop-loss выходы" value={String(risk.stop_loss_exits)} tone={risk.stop_loss_exits > 0 ? 'bad' : 'neutral'} />
        <MetricRow label="Z-exit выходы" value={String(risk.z_exits)} tone="neutral" />
      </div>

      {stress ? (
        <div className="mt-4">
          <h4 className="mb-2 text-[12px] font-semibold text-ink-2">
            Базовый vs стресс (комиссия ×{stress.commission_multiplier}, slippage {stress.slippage_pts} pts)
          </h4>
          <div className="scrollbar-thin overflow-auto rounded-xl border border-panel-border-soft">
            <table className="w-full text-[12px] text-ink-2">
              <thead>
                <tr className="border-b border-panel-border-soft">
                  <th className="px-3 py-2 text-left font-medium text-ink-3">Метрика</th>
                  <th className="px-3 py-2 text-right font-medium text-ink-3">Базовый</th>
                  <th className="px-3 py-2 text-right font-medium text-ink-3">Стресс</th>
                  <th className="px-3 py-2 text-right font-medium text-ink-3">Δ</th>
                </tr>
              </thead>
              <tbody>
                {stressRows.map(({ key, label }) => {
                  const base = stress.baseline[key] as number
                  const st = stress.stress[key] as number
                  const delta = st - base
                  const isPnl = key === 'total_pnl_rub' || key === 'max_drawdown_rub'
                  const fmt = (v: number) =>
                    key === 'profit_factor'
                      ? v == null
                        ? '∞'
                        : Number(v).toFixed(2)
                      : key === 'win_rate_pct'
                        ? `${v.toFixed(1)}%`
                        : isPnl
                          ? fmtRub(v)
                          : String(Math.round(v))
                  return (
                    <tr key={key} className="table-row">
                      <td className="px-3 py-2">{label}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{fmt(base)}</td>
                      <td className={`px-3 py-2 text-right tabular-nums ${delta < 0 && isPnl ? 'text-bad' : ''}`}>
                        {fmt(st)}
                      </td>
                      <td
                        className={`px-3 py-2 text-right tabular-nums ${delta >= 0 ? 'text-good' : 'text-bad'}`}
                      >
                        {isPnl ? fmtRub(delta) : delta.toFixed(key === 'win_rate_pct' ? 1 : 0)}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>
      ) : (
        <p className="mt-3 text-[11px] text-ink-3">Нажмите «Стресс ×2» для сравнения с удвоенными комиссией и slippage</p>
      )}
    </Panel>
  )
}
