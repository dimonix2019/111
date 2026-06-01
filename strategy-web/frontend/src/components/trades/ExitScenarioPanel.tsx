import { useMemo } from 'react'

import type { SimPack, Trade } from '@/types'
import { Panel } from '@/components/ui/Panel'
import { runAllExitScenarios, type ExitScenarioResult } from '@/lib/asymmetricExitSimulation'

type Props = {
  pack: SimPack
  trades: Trade[]
  totalCount: number
}

function fmtRub(v: number): string {
  return `${Math.round(v).toLocaleString('ru-RU')} ₽`
}

function ScenarioRow({ s }: { s: ExitScenarioResult }) {
  const deltaTone = s.deltaPnl >= 0 ? 'text-good' : 'text-bad'
  const ddDelta = s.scenarioMaxDrawdownPct - s.baselineMaxDrawdownPct
  const ddTone = ddDelta <= 0 ? 'text-good' : 'text-bad'
  return (
    <tr className="table-row">
      <td className="px-2 py-1.5 font-medium text-ink-2">{s.label}</td>
      <td className="px-2 py-1.5 text-right tabular-nums">{fmtRub(s.scenarioPnl)}</td>
      <td className={`px-2 py-1.5 text-right tabular-nums ${deltaTone}`}>
        {s.deltaPnl >= 0 ? '+' : ''}
        {fmtRub(s.deltaPnl)} ({s.deltaPct >= 0 ? '+' : ''}
        {s.deltaPct.toFixed(1)}%)
      </td>
      <td className="px-2 py-1.5 text-right tabular-nums">
        {s.baselineMaxDrawdownPct.toFixed(1)}% → {s.scenarioMaxDrawdownPct.toFixed(1)}%
      </td>
      <td className={`px-2 py-1.5 text-right tabular-nums ${ddTone}`}>
        {ddDelta >= 0 ? '+' : ''}
        {ddDelta.toFixed(1)}%
      </td>
      <td className="px-2 py-1.5 text-right tabular-nums">{s.cappedCount}</td>
    </tr>
  )
}

export function ExitScenarioPanel({ pack, trades, totalCount }: Props) {
  const { baseline, scenarios } = useMemo(
    () => runAllExitScenarios(trades, pack.zscore, pack.exit_z),
    [trades, pack.zscore, pack.exit_z],
  )

  const filteredNote =
    trades.length !== totalCount ? (
      <span className="text-warn">
        {trades.length} из {totalCount} сделок
      </span>
    ) : (
      <span>все {totalCount} сделок</span>
    )

  if (!trades.length) {
    return (
      <Panel title="Сценарии выхода (PnL / Max DD)">
        <p className="text-[12px] text-ink-3">Нет сделок для симуляции</p>
      </Panel>
    )
  }

  return (
    <Panel title="Сценарии выхода (PnL / Max DD)">
      <p className="mb-3 text-[11px] text-ink-3">
        Baseline {fmtRub(baseline.baselinePnl)}, Max DD {baseline.baselineMaxDrawdownPct.toFixed(1)}% · {filteredNote}
      </p>
      <div className="scrollbar-thin overflow-auto rounded-lg border border-panel-border-soft">
        <table className="w-full min-w-[720px] text-left text-[11px] text-ink-2">
          <thead className="sticky top-0 bg-[rgba(8,16,28,0.98)]">
            <tr>
              <th className="px-2 py-1.5 font-medium text-ink-3">Сценарий</th>
              <th className="px-2 py-1.5 text-right font-medium text-ink-3">PnL</th>
              <th className="px-2 py-1.5 text-right font-medium text-ink-3">Δ vs baseline</th>
              <th className="px-2 py-1.5 text-right font-medium text-ink-3">Max DD</th>
              <th className="px-2 py-1.5 text-right font-medium text-ink-3">Δ DD</th>
              <th className="px-2 py-1.5 text-right font-medium text-ink-3">Закрыто</th>
            </tr>
          </thead>
          <tbody>
            {scenarios.map((s) => (
              <ScenarioRow key={s.id} s={s} />
            ))}
          </tbody>
        </table>
      </div>
      <p className="mt-2 text-[10px] text-ink-3">
        A/B/C — правило на 12ч; Max Hold — uniform cap. Max DD по кумулятивному PnL (порядок exit_time).
      </p>
    </Panel>
  )
}
