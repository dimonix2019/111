import { useMemo, useState } from 'react'
import type { SimParams } from '@/types'
import type { GridSearchRow } from '@/types/gridSearch'
import { fmtPf, fmtRub } from '@/lib/api'

type SortDir = 'asc' | 'desc'

export type GridSortKey =
  | 'rank'
  | 'entry'
  | 'exit_z'
  | 'slippage'
  | 'max_loss_spread'
  | 'max_loss_rub'
  | 'min_spread'
  | 'max_spread'
  | 'entry_z_buffer'
  | 'max_dd_halt_rub'
  | 'max_dd_halt_pct'
  | 'oos'
  | 'pnl'
  | 'win_rate'
  | 'profit_factor'
  | 'max_dd'
  | 'trades'
  | 'avg_hold'
  | 'test_pnl'
  | 'train_pnl'

type GridColumn = {
  key: GridSortKey | 'actions'
  label: string
  align?: 'left' | 'right'
  sortable?: boolean
  filterKind?: 'select' | 'text' | 'none'
}

const COLUMNS: GridColumn[] = [
  { key: 'rank', label: '#', align: 'right', sortable: true, filterKind: 'text' },
  { key: 'entry', label: 'Entry', align: 'right', sortable: true, filterKind: 'select' },
  { key: 'exit_z', label: 'Exit', align: 'right', sortable: true, filterKind: 'select' },
  { key: 'slippage', label: 'Slip', align: 'right', sortable: true, filterKind: 'select' },
  { key: 'max_loss_spread', label: 'SL spr', align: 'right', sortable: true, filterKind: 'select' },
  { key: 'max_loss_rub', label: 'SL ₽', align: 'right', sortable: true, filterKind: 'select' },
  { key: 'min_spread', label: 'Min sp', align: 'right', sortable: true, filterKind: 'select' },
  { key: 'max_spread', label: 'Max sp', align: 'right', sortable: true, filterKind: 'select' },
  { key: 'entry_z_buffer', label: 'Z buf', align: 'right', sortable: true, filterKind: 'select' },
  { key: 'max_dd_halt_rub', label: 'DD ₽', align: 'right', sortable: true, filterKind: 'select' },
  { key: 'max_dd_halt_pct', label: 'DD %', align: 'right', sortable: true, filterKind: 'select' },
  { key: 'oos', label: 'OOS', sortable: true, filterKind: 'select' },
  { key: 'pnl', label: 'PnL', align: 'right', sortable: true, filterKind: 'text' },
  { key: 'win_rate', label: 'WR', align: 'right', sortable: true, filterKind: 'text' },
  { key: 'profit_factor', label: 'PF', align: 'right', sortable: true, filterKind: 'text' },
  { key: 'max_dd', label: 'Max DD', align: 'right', sortable: true, filterKind: 'text' },
  { key: 'trades', label: 'Сделок', align: 'right', sortable: true, filterKind: 'text' },
  { key: 'avg_hold', label: 'Ср. удерж.', align: 'right', sortable: true, filterKind: 'text' },
  { key: 'test_pnl', label: 'Test PnL', align: 'right', sortable: true, filterKind: 'text' },
  { key: 'train_pnl', label: 'Train PnL', align: 'right', sortable: true, filterKind: 'text' },
  { key: 'actions', label: '', sortable: false, filterKind: 'none' },
]

type FilterPreset = {
  placeholder: string
  options: { value: string; label: string }[]
}

type RowView = { row: GridSearchRow; apiRank: number }

function rowToPatch(row: GridSearchRow): Partial<SimParams> {
  return {
    entry: row.entry,
    exit_z: row.exit_z,
    slippage: row.slippage,
    max_loss_spread: row.max_loss_spread,
    max_loss_rub: row.max_loss_rub,
    min_spread: row.min_spread,
    max_spread: row.max_spread,
    entry_z_buffer: row.entry_z_buffer,
    max_dd_halt_rub: row.max_dd_halt_rub,
    max_dd_halt_pct: row.max_dd_halt_pct,
    oos_enabled: true,
  }
}

function sortValue(item: RowView, key: GridSortKey): number | string {
  const { row, apiRank } = item
  const s = row.stats
  switch (key) {
    case 'rank':
      return apiRank
    case 'entry':
      return row.entry
    case 'exit_z':
      return row.exit_z
    case 'slippage':
      return row.slippage
    case 'max_loss_spread':
      return row.max_loss_spread
    case 'max_loss_rub':
      return row.max_loss_rub
    case 'min_spread':
      return row.min_spread
    case 'max_spread':
      return row.max_spread
    case 'entry_z_buffer':
      return row.entry_z_buffer
    case 'max_dd_halt_rub':
      return row.max_dd_halt_rub
    case 'max_dd_halt_pct':
      return row.max_dd_halt_pct
    case 'oos':
      return row.oos_verdict?.title ?? row.verdict_grade
    case 'pnl':
      return s.total_pnl_rub
    case 'win_rate':
      return s.win_rate_pct
    case 'profit_factor':
      return s.profit_factor ?? -1
    case 'max_dd':
      return s.max_drawdown_rub
    case 'trades':
      return s.trade_count
    case 'avg_hold':
      return s.avg_hold_hours
    case 'test_pnl':
      return row.test_pnl_rub
    case 'train_pnl':
      return row.train_pnl_rub
  }
}

function cellText(item: RowView, key: GridSortKey): string {
  const { row, apiRank } = item
  const s = row.stats
  switch (key) {
    case 'rank':
      return String(apiRank)
    case 'entry':
      return String(row.entry)
    case 'exit_z':
      return String(row.exit_z)
    case 'slippage':
      return String(row.slippage)
    case 'max_loss_spread':
      return String(row.max_loss_spread)
    case 'max_loss_rub':
      return String(row.max_loss_rub)
    case 'min_spread':
      return String(row.min_spread)
    case 'max_spread':
      return String(row.max_spread)
    case 'entry_z_buffer':
      return String(row.entry_z_buffer)
    case 'max_dd_halt_rub':
      return String(row.max_dd_halt_rub)
    case 'max_dd_halt_pct':
      return String(row.max_dd_halt_pct)
    case 'oos':
      return (row.oos_verdict?.title ?? row.verdict_grade).toLowerCase()
    case 'pnl':
      return fmtRub(s.total_pnl_rub)
    case 'win_rate':
      return s.win_rate_pct.toFixed(1)
    case 'profit_factor':
      return fmtPf(s.profit_factor)
    case 'max_dd':
      return fmtRub(s.max_drawdown_rub)
    case 'trades':
      return String(s.trade_count)
    case 'avg_hold':
      return s.avg_hold_hours.toFixed(1)
    case 'test_pnl':
      return fmtRub(row.test_pnl_rub)
    case 'train_pnl':
      return fmtRub(row.train_pnl_rub)
  }
}

function compareRows(a: RowView, b: RowView, key: GridSortKey, dir: SortDir): number {
  const av = sortValue(a, key)
  const bv = sortValue(b, key)
  let cmp = 0
  if (typeof av === 'number' && typeof bv === 'number') {
    cmp = av - bv
  } else {
    cmp = String(av).localeCompare(String(bv), 'ru')
  }
  return dir === 'asc' ? cmp : -cmp
}

function matchesFilter(item: RowView, key: GridSortKey, filter: string): boolean {
  const q = filter.trim().toLowerCase()
  if (!q || q === 'все') return true

  const raw = sortValue(item, key)
  const text = cellText(item, key).toLowerCase()

  if (typeof raw === 'number') {
    const opMatch = q.match(/^(>=|<=|>|<|=)\s*(-?\d+(?:[.,]\d+)?)$/)
    if (opMatch) {
      const num = Number(opMatch[2].replace(',', '.'))
      if (Number.isNaN(num)) return text.includes(q)
      const op = opMatch[1]
      if (op === '>') return raw > num
      if (op === '>=') return raw >= num
      if (op === '<') return raw < num
      if (op === '<=') return raw <= num
      return raw === num
    }
  }

  if (key === 'oos') {
    const grade = item.row.verdict_grade.toLowerCase()
    const title = (item.row.oos_verdict?.title ?? '').toLowerCase()
    return grade.includes(q) || title.includes(q) || text.includes(q)
  }

  return text.includes(q)
}

function buildPresets(rows: RowView[], key: GridSortKey): FilterPreset {
  const n = rows.length
  if (!n) return { placeholder: '—', options: [{ value: '', label: 'Все' }] }

  const paramKeys: GridSortKey[] = [
    'entry',
    'exit_z',
    'slippage',
    'max_loss_spread',
    'max_loss_rub',
    'min_spread',
    'max_spread',
    'entry_z_buffer',
    'max_dd_halt_rub',
    'max_dd_halt_pct',
  ]

  if (paramKeys.includes(key)) {
    const values = [...new Set(rows.map((r) => String(sortValue(r, key))))].sort((a, b) => Number(a) - Number(b))
    return {
      placeholder: values.length <= 4 ? values.join(' · ') : `${values[0]} … ${values[values.length - 1]}`,
      options: [
        { value: '', label: `Все (${n})` },
        ...values.map((v) => ({
          value: v,
          label: `${v} (${rows.filter((r) => String(sortValue(r, key)) === v).length})`,
        })),
      ],
    }
  }

  if (key === 'oos') {
    const titles = [...new Set(rows.map((r) => r.row.oos_verdict?.title ?? r.row.verdict_grade))].sort()
    return {
      placeholder: titles.join(' · '),
      options: [
        { value: '', label: `Все (${n})` },
        ...titles.map((t) => ({
          value: t.toLowerCase(),
          label: `${t} (${rows.filter((r) => (r.row.oos_verdict?.title ?? r.row.verdict_grade) === t).length})`,
        })),
      ],
    }
  }

  if (key === 'rank') {
    return {
      placeholder: `1 … ${n}`,
      options: [
        { value: '', label: `Все (${n})` },
        { value: '<=5', label: 'Топ 5' },
        { value: '<=10', label: 'Топ 10' },
      ],
    }
  }

  const nums = rows.map((r) => sortValue(r, key)).filter((v): v is number => typeof v === 'number')
  if (nums.length) {
    const min = Math.min(...nums)
    const max = Math.max(...nums)
    const positive = nums.filter((v) => v > 0).length
    const negative = nums.filter((v) => v < 0).length
    const isPnl = key === 'pnl' || key === 'test_pnl' || key === 'train_pnl'
    const fmt = (v: number) => (isPnl ? Math.round(v).toLocaleString('ru-RU') : v.toFixed(2))

    const options: { value: string; label: string }[] = [{ value: '', label: `Все (${n})` }]
    if (isPnl) {
      if (positive) options.push({ value: '>0', label: `Прибыльные (${positive})` })
      if (negative) options.push({ value: '<=0', label: `Убыточные (${negative})` })
    }
    options.push(
      { value: `>=${min}`, label: `≥ ${fmt(min)}` },
      { value: `<=${max}`, label: `≤ ${fmt(max)}` },
    )
    return { placeholder: `${fmt(min)} … ${fmt(max)}`, options }
  }

  return { placeholder: 'все', options: [{ value: '', label: `Все (${n})` }] }
}

function initialFilters(): Partial<Record<GridSortKey, string>> {
  const init: Partial<Record<GridSortKey, string>> = {}
  for (const col of COLUMNS) {
    if (col.key !== 'actions') init[col.key as GridSortKey] = ''
  }
  return init
}

type Props = {
  rows: GridSearchRow[]
  onApply?: (patch: Partial<SimParams>) => void
}

export function GridSearchTable({ rows, onApply }: Props) {
  const indexed = useMemo(
    () => rows.map((row, i) => ({ row, apiRank: i + 1 })),
    [rows],
  )

  const [sortKey, setSortKey] = useState<GridSortKey>('rank')
  const [sortDir, setSortDir] = useState<SortDir>('asc')
  const [filters, setFilters] = useState(initialFilters)

  const presets = useMemo(() => {
    const map = new Map<GridSortKey, FilterPreset>()
    for (const col of COLUMNS) {
      if (col.key !== 'actions') map.set(col.key as GridSortKey, buildPresets(indexed, col.key as GridSortKey))
    }
    return map
  }, [indexed])

  const filtered = useMemo(() => {
    const sortKeys = COLUMNS.filter((c) => c.key !== 'actions').map((c) => c.key as GridSortKey)
    let list = indexed.filter((item) =>
      sortKeys.every((key) => matchesFilter(item, key, filters[key] ?? '')),
    )
    list = [...list].sort((a, b) => compareRows(a, b, sortKey, sortDir))
    return list
  }, [indexed, filters, sortKey, sortDir])

  function toggleSort(key: GridSortKey) {
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortKey(key)
      setSortDir(key === 'pnl' || key === 'test_pnl' ? 'desc' : 'asc')
    }
  }

  function setFilter(key: GridSortKey, value: string) {
    setFilters((prev) => ({ ...prev, [key]: value }))
  }

  const hasFilters = Object.values(filters).some((v) => (v ?? '').trim() !== '')

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-2 text-[11px] text-ink-3">
        <span>
          <b className="text-good">{rows.length}</b> вариантов в зелёной зоне
          {filtered.length !== rows.length ? (
            <>
              {' '}
              · показано <b className="text-ink-2">{filtered.length}</b>
            </>
          ) : null}
        </span>
        {hasFilters ? (
          <button type="button" className="tab-btn !px-2 !py-1 text-[11px]" onClick={() => setFilters(initialFilters())}>
            Сбросить фильтры
          </button>
        ) : null}
      </div>
      <div className="scrollbar-thin max-h-[520px] overflow-auto rounded-xl border border-panel-border-soft">
        <table className="w-full min-w-[1280px] text-left text-[12px] text-ink-2">
          <thead className="sticky top-0 z-10 bg-[rgba(8,16,28,0.98)]">
            <tr>
              {COLUMNS.map((col) => {
                if (col.key === 'actions') {
                  return (
                    <th key="actions" className="border-b border-panel-border-soft px-2 py-1.5 align-top">
                      <span className="block font-medium text-ink-3">Действие</span>
                    </th>
                  )
                }
                const key = col.key as GridSortKey
                const active = sortKey === key
                const preset = presets.get(key)!
                const value = filters[key] ?? ''
                const useSelect = col.filterKind === 'select' || preset.options.length > 1

                return (
                  <th key={key} className="border-b border-panel-border-soft px-2 py-1.5 align-top">
                    {col.sortable !== false ? (
                      <button
                        type="button"
                        className={`flex w-full items-center gap-1 font-medium hover:text-ink-1 ${col.align === 'right' ? 'justify-end' : ''} ${active ? 'text-ink-1' : 'text-ink-3'}`}
                        onClick={() => toggleSort(key)}
                      >
                        {col.label}
                        <span className="text-[10px] opacity-70">
                          {active ? (sortDir === 'asc' ? '▲' : '▼') : '↕'}
                        </span>
                      </button>
                    ) : (
                      <span className={`block font-medium text-ink-3 ${col.align === 'right' ? 'text-right' : ''}`}>
                        {col.label}
                      </span>
                    )}
                    {useSelect ? (
                      <select
                        value={value}
                        onChange={(e) => setFilter(key, e.target.value)}
                        className="mt-1 !px-2 !py-1 text-[11px]"
                        onClick={(e) => e.stopPropagation()}
                        title={preset.placeholder}
                      >
                        {preset.options.map((opt) => (
                          <option key={opt.value || '__all'} value={opt.value}>
                            {opt.label}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <>
                        <input
                          type="text"
                          list={`grid-filter-${key}`}
                          placeholder={preset.placeholder}
                          value={value}
                          onChange={(e) => setFilter(key, e.target.value)}
                          className="mt-1 !px-2 !py-1 text-[11px]"
                          onClick={(e) => e.stopPropagation()}
                        />
                        <datalist id={`grid-filter-${key}`}>
                          {preset.options
                            .filter((o) => o.value)
                            .map((opt) => (
                              <option key={opt.value} value={opt.value}>
                                {opt.label}
                              </option>
                            ))}
                        </datalist>
                      </>
                    )}
                  </th>
                )
              })}
            </tr>
          </thead>
          <tbody>
            {filtered.length === 0 ? (
              <tr>
                <td colSpan={COLUMNS.length} className="px-3 py-8 text-center text-ink-3">
                  Нет вариантов по заданным фильтрам
                </td>
              </tr>
            ) : (
              filtered.map((item, displayIdx) => {
                const { row } = item
                const s = row.stats
                const pnlPos = s.total_pnl_rub >= 0
                const oosTitle = row.oos_verdict?.title ?? row.verdict_grade

                return (
                  <tr
                    key={`${row.entry}-${row.exit_z}-${row.slippage}-${item.apiRank}`}
                    className="table-row border-b border-panel-border-soft/40 hover:bg-[rgba(16,185,129,0.06)]"
                  >
                    <td className="px-2 py-1.5 text-right tabular-nums text-ink-3">{displayIdx + 1}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums">{row.entry}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums">{row.exit_z}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums text-ink-3">{row.slippage}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums text-ink-3">{row.max_loss_spread}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums text-ink-3">{row.max_loss_rub}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums text-ink-3">{row.min_spread}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums text-ink-3">{row.max_spread}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums text-ink-3">{row.entry_z_buffer}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums text-ink-3">{row.max_dd_halt_rub}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums text-ink-3">{row.max_dd_halt_pct}</td>
                    <td className="px-2 py-1.5 text-[11px] text-good" title={row.oos_verdict?.summary}>
                      {oosTitle}
                    </td>
                    <td
                      className={`px-2 py-1.5 text-right tabular-nums font-semibold ${pnlPos ? 'text-good' : 'text-bad'}`}
                    >
                      {fmtRub(s.total_pnl_rub)}
                    </td>
                    <td className="px-2 py-1.5 text-right tabular-nums">{s.win_rate_pct.toFixed(1)}%</td>
                    <td className="px-2 py-1.5 text-right tabular-nums">{fmtPf(s.profit_factor)}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums text-bad">{fmtRub(s.max_drawdown_rub)}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums">{s.trade_count}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums">{s.avg_hold_hours.toFixed(1)} ч</td>
                    <td
                      className={`px-2 py-1.5 text-right tabular-nums ${row.test_pnl_rub >= 0 ? 'text-good' : 'text-bad'}`}
                    >
                      {fmtRub(row.test_pnl_rub)}
                    </td>
                    <td className="px-2 py-1.5 text-right tabular-nums text-ink-3">{fmtRub(row.train_pnl_rub)}</td>
                    <td className="px-2 py-1.5">
                      {onApply ? (
                        <button
                          type="button"
                          className="primary whitespace-nowrap text-[11px]"
                          onClick={() => onApply(rowToPatch(row))}
                        >
                          В sidebar
                        </button>
                      ) : (
                        <span className="text-[10px] text-ink-3">—</span>
                      )}
                    </td>
                  </tr>
                )
              })
            )}
          </tbody>
        </table>
      </div>
      <p className="text-[10px] text-ink-3">
        Сортировка по клику на заголовок · фильтры: список для параметров, для метрик —{' '}
        <code className="text-ink-2">&gt;0</code>, <code className="text-ink-2">&gt;=1000</code>
      </p>
    </div>
  )
}
