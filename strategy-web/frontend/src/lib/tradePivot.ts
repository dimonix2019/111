import type { Trade } from '@/types'
import {
  DISTRIBUTION_BY_ID,
  formatPct,
  getTradeDimensionBucket,
  isTimeOfDayDistribution,
  orderedDimensionKeys,
  type TimeGranularityMin,
} from '@/lib/tradeDistribution'

export type PivotMeasureId =
  | 'count'
  | 'sum_pnl'
  | 'avg_pnl'
  | 'win_rate'
  | 'sum_spread'
  | 'avg_hold'
  | 'avg_entry_z'
  | 'avg_exit_z'

export type PivotMeasureDef = {
  id: PivotMeasureId
  label: string
  hint: string
}

export const PIVOT_MEASURES: PivotMeasureDef[] = [
  { id: 'count', label: 'Кол-во сделок', hint: 'Число сделок в ячейке' },
  { id: 'sum_pnl', label: 'Σ PnL ₽', hint: 'Суммарная прибыль/убыток' },
  { id: 'avg_pnl', label: 'Ср. PnL ₽', hint: 'Средний PnL на сделку' },
  { id: 'win_rate', label: 'Win rate %', hint: 'Доля прибыльных сделок' },
  { id: 'sum_spread', label: 'Σ PnL spread', hint: 'Сумма PnL в пунктах spread' },
  { id: 'avg_hold', label: 'Ср. удержание, ч', hint: 'Средняя длительность сделки' },
  { id: 'avg_entry_z', label: 'Ср. Z входа', hint: 'Средний Z-score на входе' },
  { id: 'avg_exit_z', label: 'Ср. Z выхода', hint: 'Средний Z-score на выходе' },
]

export type PivotCell = {
  rowKey: string
  colKey: string | null
  count: number
  value: number
}

export type Pivot1DResult = {
  rows: PivotCell[]
  rowKeys: string[]
  measure: PivotMeasureId
  totalTrades: number
}

export type Pivot2DResult = {
  rowKeys: string[]
  colKeys: string[]
  cells: Map<string, PivotCell>
  measure: PivotMeasureId
  totalTrades: number
}

function holdHours(t: Trade): number {
  const a = new Date(t.entry_time.replace(' ', 'T'))
  const b = new Date(t.exit_time.replace(' ', 'T'))
  if (Number.isNaN(a.getTime()) || Number.isNaN(b.getTime())) return 0
  return Math.max(0, (b.getTime() - a.getTime()) / 3_600_000)
}

export function aggregatePivotMeasure(trades: Trade[], measure: PivotMeasureId): { value: number; count: number } {
  const count = trades.length
  if (!count) return { value: 0, count: 0 }
  switch (measure) {
    case 'count':
      return { value: count, count }
    case 'sum_pnl':
      return { value: trades.reduce((s, t) => s + t.pnl_rub, 0), count }
    case 'avg_pnl':
      return { value: trades.reduce((s, t) => s + t.pnl_rub, 0) / count, count }
    case 'win_rate':
      return { value: (100 * trades.filter((t) => t.pnl_rub > 0).length) / count, count }
    case 'sum_spread':
      return { value: trades.reduce((s, t) => s + t.pnl_spread_pts, 0), count }
    case 'avg_hold':
      return { value: trades.reduce((s, t) => s + holdHours(t), 0) / count, count }
    case 'avg_entry_z':
      return { value: trades.reduce((s, t) => s + t.entry_z, 0) / count, count }
    case 'avg_exit_z':
      return { value: trades.reduce((s, t) => s + t.exit_z, 0) / count, count }
  }
}

function dimGranularity(dimId: string, gran: TimeGranularityMin): TimeGranularityMin | undefined {
  return isTimeOfDayDistribution(dimId) ? gran : undefined
}

function groupTrades(
  trades: Trade[],
  rowDim: string,
  colDim: string | null,
  rowGran: TimeGranularityMin,
  colGran: TimeGranularityMin,
): Map<string, Trade[]> {
  const map = new Map<string, Trade[]>()
  const rGran = dimGranularity(rowDim, rowGran)
  const cGran = colDim ? dimGranularity(colDim, colGran) : undefined
  for (const t of trades) {
    const rk = getTradeDimensionBucket(t, rowDim, rGran)
    const ck = colDim ? getTradeDimensionBucket(t, colDim, cGran) : ''
    const key = colDim ? `${rk}\0${ck}` : rk
    const list = map.get(key)
    if (list) list.push(t)
    else map.set(key, [t])
  }
  return map
}

export function buildPivot1D(
  trades: Trade[],
  rowDim: string,
  measure: PivotMeasureId,
  timeGranularityMin: TimeGranularityMin = 15,
): Pivot1DResult {
  const groups = groupTrades(trades, rowDim, null, timeGranularityMin, timeGranularityMin)
  const observed = new Set<string>()
  for (const k of groups.keys()) observed.add(k)
  const rowKeys = orderedDimensionKeys(rowDim, dimGranularity(rowDim, timeGranularityMin), observed)
  const rows: PivotCell[] = rowKeys.map((rowKey) => {
    const bucket = groups.get(rowKey) ?? []
    const { value, count } = aggregatePivotMeasure(bucket, measure)
    return { rowKey, colKey: null, count, value }
  })
  return { rows, rowKeys, measure, totalTrades: trades.length }
}

export function buildPivot2D(
  trades: Trade[],
  rowDim: string,
  colDim: string,
  measure: PivotMeasureId,
  rowGran: TimeGranularityMin = 15,
  colGran: TimeGranularityMin = 15,
): Pivot2DResult {
  const groups = groupTrades(trades, rowDim, colDim, rowGran, colGran)
  const rowObs = new Set<string>()
  const colObs = new Set<string>()
  for (const key of groups.keys()) {
    const [r, c] = key.split('\0')
    if (r) rowObs.add(r)
    if (c) colObs.add(c)
  }
  const rowKeys = orderedDimensionKeys(rowDim, dimGranularity(rowDim, rowGran), rowObs)
  const colKeys = orderedDimensionKeys(colDim, dimGranularity(colDim, colGran), colObs)
  const cells = new Map<string, PivotCell>()
  for (const rowKey of rowKeys) {
    for (const colKey of colKeys) {
      const bucket = groups.get(`${rowKey}\0${colKey}`) ?? []
      const { value, count } = aggregatePivotMeasure(bucket, measure)
      cells.set(`${rowKey}\0${colKey}`, { rowKey, colKey, count, value })
    }
  }
  return { rowKeys, colKeys, cells, measure, totalTrades: trades.length }
}

export function pivotDimensionLabel(dimId: string): string {
  return DISTRIBUTION_BY_ID.get(dimId)?.label ?? dimId
}

export function formatPivotValue(measure: PivotMeasureId, value: number): string {
  switch (measure) {
    case 'count':
      return String(Math.round(value))
    case 'sum_pnl':
    case 'avg_pnl':
      return `${Math.round(value).toLocaleString('ru-RU')} ₽`
    case 'win_rate':
      return `${value.toFixed(1).replace('.', ',')}%`
    case 'sum_spread':
      return value.toFixed(3)
    case 'avg_hold':
      return `${value.toFixed(1).replace('.', ',')} ч`
    case 'avg_entry_z':
    case 'avg_exit_z':
      return value.toFixed(2)
  }
}

export function pivotSummaryLine(
  rowDim: string,
  colDim: string | null,
  measure: PivotMeasureId,
  rowGran: TimeGranularityMin,
): string {
  const m = PIVOT_MEASURES.find((x) => x.id === measure)?.label ?? measure
  const row = pivotDimensionLabel(rowDim)
  if (!colDim) {
    const gran = isTimeOfDayDistribution(rowDim) ? ` · шаг ${rowGran} мин` : ''
    return `${m} по разрезу «${row}»${gran}`
  }
  const col = pivotDimensionLabel(colDim)
  return `${m} · строки: ${row} · столбцы: ${col}`
}

export function pivotCellTitle(cell: PivotCell, measure: PivotMeasureId, total: number): string {
  const val = formatPivotValue(measure, cell.value)
  const pct = total > 0 ? formatPct(cell.count, total) : '0%'
  const loc = cell.colKey ? `${cell.rowKey} × ${cell.colKey}` : cell.rowKey
  return `${loc}: ${val} · ${cell.count} сд. (${pct})`
}
