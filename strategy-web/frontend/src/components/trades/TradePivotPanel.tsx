import { useMemo, useState } from 'react'

import type { Trade } from '@/types'

import { PivotBars } from '@/components/charts/PivotBars'
import { PivotHeatmap } from '@/components/charts/PivotHeatmap'
import { Panel } from '@/components/ui/Panel'
import {
  distributionGroups,
  isTimeOfDayDistribution,
  TIME_GRANULARITY_OPTIONS,
  type TimeGranularityMin,
} from '@/lib/tradeDistribution'
import {
  buildPivot1D,
  buildPivot2D,
  formatPivotValue,
  PIVOT_MEASURES,
  pivotCellTitle,
  pivotDimensionLabel,
  pivotSummaryLine,
  type PivotMeasureId,
} from '@/lib/tradePivot'

type Props = {
  trades: Trade[]
  totalCount: number
}

const PRESETS: { label: string; row: string; col: string; measure: PivotMeasureId; gran: TimeGranularityMin }[] = [
  { label: 'Σ PnL × время входа', row: 'entry_hour', col: '', measure: 'sum_pnl', gran: 15 },
  { label: 'Σ PnL × время выхода', row: 'exit_hour', col: '', measure: 'sum_pnl', gran: 15 },
  { label: 'Σ PnL × день недели', row: 'entry_dow', col: '', measure: 'sum_pnl', gran: 15 },
  { label: 'Σ PnL: время × LONG/SHORT', row: 'entry_hour', col: 'direction', measure: 'sum_pnl', gran: 60 },
]

export function TradePivotPanel({ trades, totalCount }: Props) {
  const [rowDim, setRowDim] = useState('entry_hour')
  const [colDim, setColDim] = useState('')
  const [measure, setMeasure] = useState<PivotMeasureId>('sum_pnl')
  const [rowGran, setRowGran] = useState<TimeGranularityMin>(15)
  const [colGran, setColGran] = useState<TimeGranularityMin>(15)

  const groups = useMemo(() => distributionGroups(), [])
  const showRowGran = isTimeOfDayDistribution(rowDim)
  const showColGran = colDim !== '' && isTimeOfDayDistribution(colDim)

  const pivot1d = useMemo(() => {
    if (colDim) return null
    return buildPivot1D(trades, rowDim, measure, rowGran)
  }, [trades, rowDim, colDim, measure, rowGran])

  const pivot2d = useMemo(() => {
    if (!colDim) return null
    return buildPivot2D(trades, rowDim, colDim, measure, rowGran, colGran)
  }, [trades, rowDim, colDim, measure, rowGran, colGran])

  const scrollable = showRowGran && rowGran < 60

  const filteredNote =
    trades.length !== totalCount ? (
      <span className="text-warn">
        {trades.length} из {totalCount} сделок (фильтры таблицы)
      </span>
    ) : (
      <span>все {totalCount} сделок</span>
    )

  return (
    <Panel title="Сводная (Pivot)">
      <p className="mb-3 text-[11px] text-ink-3">
        Как в Excel: разрез строк · разрез столбцов (опц.) · показатель · {filteredNote}
      </p>

      <div className="mb-3 flex flex-wrap gap-1.5">
        {PRESETS.map((p) => {
          const active = rowDim === p.row && colDim === p.col && measure === p.measure && rowGran === p.gran
          return (
            <button
              key={p.label}
              type="button"
              className={`tab-btn !px-2 !py-1 text-[10px] ${active ? 'active' : ''}`}
              onClick={() => {
                setRowDim(p.row)
                setColDim(p.col)
                setMeasure(p.measure)
                setRowGran(p.gran)
                if (p.col && isTimeOfDayDistribution(p.col)) setColGran(p.gran)
              }}
            >
              {p.label}
            </button>
          )
        })}
      </div>

      <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
        <label className="block text-[12px] font-medium text-ink-3">
          Разрез строк
          <select value={rowDim} onChange={(e) => setRowDim(e.target.value)} className="mt-1 w-full">
            {groups.map(({ group, items }) => (
              <optgroup key={group} label={group}>
                {items.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.label}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        </label>

        <label className="block text-[12px] font-medium text-ink-3">
          Разрез столбцов
          <select value={colDim} onChange={(e) => setColDim(e.target.value)} className="mt-1 w-full">
            <option value="">— только строки —</option>
            {groups.map(({ group, items }) => (
              <optgroup key={group} label={group}>
                {items.map((d) => (
                  <option key={d.id} value={d.id} disabled={d.id === rowDim}>
                    {d.label}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        </label>

        <label className="block text-[12px] font-medium text-ink-3">
          Показатель
          <select
            value={measure}
            onChange={(e) => setMeasure(e.target.value as PivotMeasureId)}
            className="mt-1 w-full"
          >
            {PIVOT_MEASURES.map((m) => (
              <option key={m.id} value={m.id} title={m.hint}>
                {m.label}
              </option>
            ))}
          </select>
        </label>

        {showRowGran ? (
          <label className="block text-[12px] font-medium text-ink-3">
            Шаг времени (строки)
            <select
              value={rowGran}
              onChange={(e) => setRowGran(Number(e.target.value) as TimeGranularityMin)}
              className="mt-1 w-full"
            >
              {TIME_GRANULARITY_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        ) : showColGran ? (
          <label className="block text-[12px] font-medium text-ink-3">
            Шаг времени (столбцы)
            <select
              value={colGran}
              onChange={(e) => setColGran(Number(e.target.value) as TimeGranularityMin)}
              className="mt-1 w-full"
            >
              {TIME_GRANULARITY_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        ) : (
          <div className="hidden lg:block" />
        )}
      </div>

      {showRowGran && showColGran ? (
        <label className="mt-2 block max-w-xs text-[12px] font-medium text-ink-3">
          Шаг времени (столбцы)
          <select
            value={colGran}
            onChange={(e) => setColGran(Number(e.target.value) as TimeGranularityMin)}
            className="mt-1 w-full"
          >
            {TIME_GRANULARITY_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
      ) : null}

      <p className="mt-3 text-[11px] font-medium text-ink-2">
        {pivotSummaryLine(rowDim, colDim || null, measure, rowGran)}
      </p>

      {!trades.length ? (
        <p className="mt-2 text-[12px] text-ink-3">Нет сделок для сводной</p>
      ) : colDim && pivot2d ? (
        <div className="mt-3 space-y-3">
          <PivotHeatmap
            rowKeys={pivot2d.rowKeys}
            colKeys={pivot2d.colKeys}
            cells={pivot2d.cells}
            measure={measure}
            totalTrades={pivot2d.totalTrades}
            rowLabel={pivotDimensionLabel(rowDim)}
            colLabel={pivotDimensionLabel(colDim)}
          />
          <p className="text-[10px] text-ink-3">В ячейке: показатель · число сделок. Цвет — для PnL.</p>
        </div>
      ) : pivot1d ? (
        <div className="mt-3 space-y-3">
          <PivotBars
            rows={pivot1d.rows}
            measure={measure}
            totalTrades={pivot1d.totalTrades}
            scrollable={scrollable}
          />
          <div className="scrollbar-thin max-h-48 overflow-auto rounded-lg border border-panel-border-soft">
            <table className="w-full text-left text-[11px] text-ink-2">
              <thead className="sticky top-0 bg-[rgba(8,16,28,0.98)]">
                <tr>
                  <th className="px-2 py-1.5 font-medium text-ink-3">{pivotDimensionLabel(rowDim)}</th>
                  <th className="px-2 py-1.5 text-right font-medium text-ink-3">Сделок</th>
                  <th className="px-2 py-1.5 text-right font-medium text-ink-3">Доля</th>
                  <th className="px-2 py-1.5 text-right font-medium text-ink-3">Показатель</th>
                </tr>
              </thead>
              <tbody>
                {pivot1d.rows
                  .filter((r) => r.count > 0)
                  .map((r) => (
                    <tr key={r.rowKey} className="table-row" title={pivotCellTitle(r, measure, pivot1d.totalTrades)}>
                      <td className="px-2 py-1">{r.rowKey}</td>
                      <td className="px-2 py-1 text-right tabular-nums">{r.count}</td>
                      <td className="px-2 py-1 text-right tabular-nums">
                        {((100 * r.count) / pivot1d.totalTrades).toFixed(1).replace('.', ',')}%
                      </td>
                      <td className="px-2 py-1 text-right tabular-nums font-medium">{formatPivotValue(measure, r.value)}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : null}
    </Panel>
  )
}
