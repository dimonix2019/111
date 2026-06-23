import type { SimPack } from '@/types'
import { fmtPf, fmtRub } from '@/lib/api'
import { Panel } from '@/components/ui/Panel'
import { EquityCompareChart } from '@/components/charts/EquityCompareChart'
import { MetricsOverview } from '@/components/overview/MetricsOverview'

type Props = {
  packs: SimPack[]
  compareMode: boolean
}

const METRICS: { key: keyof SimPack['stats']; label: string; fmt: (v: number | null) => string }[] = [
  { key: 'total_pnl_rub', label: 'PnL ₽', fmt: (v) => fmtRub(v ?? 0) },
  { key: 'trade_count', label: 'Сделок', fmt: (v) => String(v ?? 0) },
  { key: 'win_rate_pct', label: 'Win %', fmt: (v) => `${(v ?? 0).toFixed(1)}%` },
  { key: 'max_drawdown_rub', label: 'Max DD ₽', fmt: (v) => fmtRub(v ?? 0) },
  { key: 'profit_factor', label: 'Profit Factor', fmt: (v) => fmtPf(v) },
  { key: 'total_commission_rub', label: 'Комиссии ₽', fmt: (v) => fmtRub(v ?? 0) },
  { key: 'total_overnight_rub', label: 'Overnight ₽', fmt: (v) => fmtRub(v ?? 0) },
]

export function CompareTab({ packs, compareMode }: Props) {
  if (!compareMode || packs.length < 2) {
    return (
      <Panel>
        <p className="text-ink-2">Включите «Сравнение A / B» в sidebar и задайте разные Entry/Exit для стратегий.</p>
      </Panel>
    )
  }

  const [a, b] = packs

  return (
    <div className="space-y-4">
      <Panel title="Метрики A vs B">
        <div className="scrollbar-thin overflow-auto rounded-xl border border-panel-border-soft">
          <table className="w-full min-w-[480px] text-left text-[12px] text-ink-2">
            <thead>
              <tr className="border-b border-panel-border-soft">
                <th className="px-3 py-2 font-medium text-ink-3">Метрика</th>
                <th className="px-3 py-2 font-medium text-ink-1">{a.label || 'A'}</th>
                <th className="px-3 py-2 font-medium text-ink-1">{b.label || 'B'}</th>
              </tr>
            </thead>
            <tbody>
              {METRICS.map(({ key, label, fmt }) => (
                <tr key={key} className="table-row border-b border-panel-border-soft/50">
                  <td className="px-3 py-2 text-ink-3">{label}</td>
                  <td className="px-3 py-2 tabular-nums">{fmt(a.stats[key] as number | null)}</td>
                  <td className="px-3 py-2 tabular-nums">{fmt(b.stats[key] as number | null)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>

      <div className="grid gap-4 lg:grid-cols-2">
        <MetricsOverview stats={a.stats} unrealizedRub={a.unrealized_pnl_rub} />
        <MetricsOverview stats={b.stats} unrealizedRub={b.unrealized_pnl_rub} />
      </div>

      <EquityCompareChart
        title="Equity overlay"
        series={[
          {
            label: a.label || 'A',
            color: '#38bdf8',
            data: a.equity.map((e) => ({ time: e.time, value: e.equity_rub })),
          },
          {
            label: b.label || 'B',
            color: '#fb923c',
            data: b.equity.map((e) => ({ time: e.time, value: e.equity_rub })),
          },
        ]}
      />

      <div className="grid gap-4 lg:grid-cols-2">
        <Panel title={`Сделки ${a.label || 'A'}`}>
          <p className="text-[12px] text-ink-2">
            {a.stats.trade_count} сделок · PnL {fmtRub(a.stats.total_pnl_rub)} · Entry {a.entry} / Exit{' '}
            {a.exit_z}
          </p>
        </Panel>
        <Panel title={`Сделки ${b.label || 'B'}`}>
          <p className="text-[12px] text-ink-2">
            {b.stats.trade_count} сделок · PnL {fmtRub(b.stats.total_pnl_rub)} · Entry {b.entry} / Exit{' '}
            {b.exit_z}
          </p>
        </Panel>
      </div>
    </div>
  )
}
