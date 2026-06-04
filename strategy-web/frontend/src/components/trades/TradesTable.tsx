import { useEffect, useMemo, useState } from 'react'
import type { SimPack, Trade } from '@/types'
import { spreadDirectionHint, spreadLegs } from '@/lib/spreadLegs'
import { columnsForMobileTradesList, columnsForTrades, type TradeColumn } from '@/lib/tradeTableColumns'
import { TradeDistributionPanel } from '@/components/trades/TradeDistributionPanel'
import { TradePivotPanel } from '@/components/trades/TradePivotPanel'
import { ExitScenarioPanel } from '@/components/trades/ExitScenarioPanel'
import { StrategyChatPanel } from '@/components/trades/StrategyChatPanel'
import { downloadTradesCsv } from '@/lib/exportTradesCsv'

type SortDir = 'asc' | 'desc'
type SortKey = TradeColumn['key']

type FilterPreset = {
  placeholder: string
  options: { value: string; label: string }[]
}

function isTradeKey(key: SortKey): key is keyof Trade {
  return key !== 'leg_ticker' && key !== 'leg_action'
}

function cellText(trade: Trade, col: TradeColumn): string {
  if (col.key === 'leg_ticker' || col.key === 'leg_action') return ''
  if (col.key.startsWith('leg_')) return ''
  if (col.render) return col.render(trade)
  if (isTradeKey(col.key)) return String(trade[col.key] ?? '')
  return ''
}

function compareValues(a: Trade, b: Trade, key: SortKey, dir: SortDir): number {
  if (!isTradeKey(key)) {
    return dir === 'asc' ? a.no - b.no : b.no - a.no
  }
  const av = a[key]
  const bv = b[key]
  let cmp = 0
  if (typeof av === 'number' && typeof bv === 'number') {
    cmp = av - bv
  } else {
    cmp = String(av).localeCompare(String(bv), 'ru')
  }
  return dir === 'asc' ? cmp : -cmp
}

function matchesFilter(trade: Trade, col: TradeColumn, filter: string): boolean {
  if (col.key === 'leg_ticker' || col.key === 'leg_action') return true

  const q = filter.trim().toLowerCase()
  if (!q || q === 'все') return true

  if (!isTradeKey(col.key)) return true

  const raw = trade[col.key]
  const text = cellText(trade, col).toLowerCase()

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

  return text.includes(q)
}

function buildPresets(trades: Trade[], col: TradeColumn): FilterPreset {
  if (col.key === 'leg_ticker' || col.key === 'leg_action') {
    return { placeholder: '—', options: [{ value: '', label: 'Все' }] }
  }

  const key = col.key as keyof Trade
  const n = trades.length
  if (!n) return { placeholder: '—', options: [{ value: '', label: 'Все' }] }

  if (key === 'direction') {
    const dirs = [...new Set(trades.map((t) => t.direction))].sort()
    return {
      placeholder: dirs.map((d) => (d === 'LONG' ? 'LONG спред' : 'SHORT спред')).join(' · '),
      options: [
        { value: '', label: `Все (${n})` },
        ...dirs.map((d) => ({
          value: d,
          label: `${d === 'LONG' ? 'LONG спред' : 'SHORT спред'} (${trades.filter((t) => t.direction === d).length})`,
        })),
      ],
    }
  }

  if (key === 'entry_time' || key === 'exit_time') {
    const months = [...new Set(trades.map((t) => String(t[key]).slice(0, 7)))].sort()
    const first = String(trades[0][key]).slice(0, 10)
    const last = String(trades[n - 1][key]).slice(0, 10)
    return {
      placeholder: `${first} … ${last}`,
      options: [
        { value: '', label: `Все (${n})` },
        ...months.map((m) => ({
          value: m,
          label: `${m} (${trades.filter((t) => String(t[key]).startsWith(m)).length})`,
        })),
      ],
    }
  }

  if (typeof trades[0][key] === 'number') {
    const nums = trades.map((t) => t[key] as number)
    const min = Math.min(...nums)
    const max = Math.max(...nums)
    const positive = trades.filter((t) => (t[key] as number) > 0).length
    const negative = trades.filter((t) => (t[key] as number) < 0).length
    const isPnl =
      key === 'pnl_rub' ||
      key === 'pnl_spread_pts' ||
      key === 'commission_rub' ||
      key === 'overnight_rub'
    const fmt = (v: number) =>
      key === 'pnl_rub' ? Math.round(v).toLocaleString('ru-RU') : v.toFixed(3)

    const options: { value: string; label: string }[] = [{ value: '', label: `Все (${n})` }]
    if (isPnl) {
      if (positive) options.push({ value: '>0', label: `Прибыльные (${positive})` })
      if (negative) options.push({ value: '<=0', label: `Убыточные (${negative})` })
    }
    options.push(
      { value: `>=${min}`, label: `≥ ${fmt(min)}` },
      { value: `<=${max}`, label: `≤ ${fmt(max)}` },
    )

    return {
      placeholder: `${fmt(min)} … ${fmt(max)}`,
      options,
    }
  }

  if (key === 'no') {
    return {
      placeholder: `1 … ${n}`,
      options: [
        { value: '', label: `Все (${n})` },
        { value: '<=10', label: '№ 1–10' },
        { value: '>=10', label: '№ ≥ 10' },
        { value: `>=${Math.max(1, n - 9)}`, label: 'Последние 10' },
      ],
    }
  }

  return { placeholder: 'все', options: [{ value: '', label: `Все (${n})` }] }
}

function initialFilters(cols: TradeColumn[]): Partial<Record<SortKey, string>> {
  const init: Partial<Record<SortKey, string>> = {}
  for (const col of cols) {
    init[col.key] = ''
  }
  return init
}

function tradeLevelCellClass(col: TradeColumn, trade: Trade): string {
  const parts = [
    col.align === 'right' ? 'text-right tabular-nums' : '',
    col.key === 'pnl_rub' ? (trade.pnl_rub >= 0 ? 'font-semibold text-good' : 'font-semibold text-bad') : '',
    col.key === 'commission_rub' || col.key === 'overnight_rub' ? 'text-ink-3' : '',
    col.key === 'direction' ? (trade.direction === 'LONG' ? 'text-good' : 'text-bad') : '',
    col.tradeLevel ? 'align-middle bg-[rgba(17,28,50,0.35)]' : '',
  ]
  return parts.filter(Boolean).join(' ')
}

function PairTradeRows({ trade, columns }: { trade: Trade; columns: TradeColumn[] }) {
  const legs = spreadLegs(trade.direction)
  const tradeCols = columns.filter((c) => c.tradeLevel)
  const legCols = columns.filter((c) => !c.tradeLevel)

  return (
    <>
      {legs.map((leg, legIdx) => (
        <tr
          key={`${trade.no}-${leg.ticker}`}
          className={`table-row ${legIdx === 0 ? 'border-t-2 border-surface-border-soft' : ''}`}
        >
          {legIdx === 0
            ? tradeCols.map((col) => (
                <td
                  key={col.key}
                  rowSpan={2}
                  className={`px-2 py-2 ${tradeLevelCellClass(col, trade)}`}
                  title={col.key === 'direction' ? spreadDirectionHint(trade.direction) : undefined}
                >
                  {col.key === 'direction' ? (
                    <div className="space-y-0.5">
                      <div className="font-semibold">{col.render ? col.render(trade) : trade.direction}</div>
                      <div className="text-[10px] font-normal text-ink-3">2 ноги · одновременно</div>
                    </div>
                  ) : col.render ? (
                    col.render(trade)
                  ) : isTradeKey(col.key) ? (
                    String(trade[col.key] ?? '')
                  ) : null}
                </td>
              ))
            : null}

          {legCols.map((col) => {
            if (col.key === 'leg_ticker') {
              return (
                <td key={col.key} className="px-2 py-1.5">
                  <span className="rounded-md border border-surface-border-soft bg-surface-inner/80 px-2 py-0.5 font-semibold text-ink-1">
                    {leg.ticker}
                  </span>
                  <span className="ml-1.5 text-[10px] text-ink-3">нога {legIdx + 1}/2</span>
                </td>
              )
            }
            if (col.key === 'leg_action') {
              return (
                <td
                  key={col.key}
                  className={`px-2 py-1.5 font-medium ${leg.side === 'buy' ? 'text-good' : 'text-bad'}`}
                >
                  {leg.sideRu}
                </td>
              )
            }
            return <td key={col.key} />
          })}
        </tr>
      ))}
    </>
  )
}

function SimpleTradeRow({ trade, columns }: { trade: Trade; columns: TradeColumn[] }) {
  return (
    <tr className="table-row border-t border-surface-border-soft/60">
      {columns.map((col) => (
        <td key={col.key} className={`trades-mobile-cell ${tradeLevelCellClass(col, trade)}`}>
          {col.render ? col.render(trade) : isTradeKey(col.key) ? String(trade[col.key] ?? '') : null}
        </td>
      ))}
    </tr>
  )
}

export function TradesTable({
  pack,
  csvPath,
  mobileListOnly = false,
}: {
  pack: SimPack
  csvPath: string
  /** Мобильный /m: только таблица сделок */
  mobileListOnly?: boolean
}) {
  const columns = useMemo(
    () => (mobileListOnly ? columnsForMobileTradesList(pack.trades) : columnsForTrades(pack.trades)),
    [pack.trades, mobileListOnly],
  )
  const [sortKey, setSortKey] = useState<SortKey>('no')
  const [sortDir, setSortDir] = useState<SortDir>('desc')
  const [filters, setFilters] = useState<Partial<Record<SortKey, string>>>(() => initialFilters(columns))

  useEffect(() => {
    setFilters(initialFilters(columns))
    setSortKey('no')
    setSortDir('desc')
  }, [pack.trades, columns])

  const presets = useMemo(() => {
    const map = new Map<SortKey, FilterPreset>()
    for (const col of columns) map.set(col.key, buildPresets(pack.trades, col))
    return map
  }, [pack.trades, columns])

  const filtered = useMemo(() => {
    let rows = pack.trades.filter((t) => columns.every((col) => matchesFilter(t, col, filters[col.key] ?? '')))
    rows = [...rows].sort((a, b) => compareValues(a, b, sortKey, sortDir))
    return rows
  }, [pack.trades, filters, sortKey, sortDir, columns])

  function toggleSort(key: SortKey) {
    if (key === 'leg_ticker' || key === 'leg_action') return
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortKey(key)
      setSortDir(key === 'no' ? 'desc' : 'asc')
    }
  }

  function setFilter(key: SortKey, value: string) {
    setFilters((prev) => ({ ...prev, [key]: value }))
  }

  function clearFilters() {
    setFilters(initialFilters(columns))
  }

  const hasFilters = columns.some((col) => {
    const v = filters[col.key] ?? ''
    return v.trim() !== ''
  })

  return (
    <div className={mobileListOnly ? 'flex min-h-0 flex-1 flex-col gap-2' : 'space-y-4'}>
      {!mobileListOnly ? (
        <>
          <TradeDistributionPanel trades={filtered} totalCount={pack.trades.length} />
          <ExitScenarioPanel pack={pack} trades={filtered} totalCount={pack.trades.length} />
          <TradePivotPanel trades={filtered} totalCount={pack.trades.length} />
          <StrategyChatPanel pack={pack} filteredTrades={filtered} csvPath={csvPath} />
        </>
      ) : null}
      <div className={`space-y-2 ${mobileListOnly ? 'flex min-h-0 flex-1 flex-col' : ''}`}>
        {!mobileListOnly ? (
          <div className="surface-inner px-3 py-2 text-[11px] leading-relaxed text-ink-3">
            Каждая сделка — <span className="text-ink-2">одна спред-пара</span>: две строки (TATN и TATNP) открываются и
            закрываются <span className="text-ink-2">одновременно</span>. LONG спред = покупка TATN + продажа TATNP;
            SHORT = наоборот.
          </div>
        ) : null}
        <div className="flex flex-wrap items-center justify-between gap-2 text-[11px] text-ink-3">
          <span>
            {mobileListOnly ? (
              <>
                Показано <b className="text-ink-2">{filtered.length}</b> из {pack.trades.length} сделок
              </>
            ) : (
              <>
                Показано <b className="text-ink-2">{filtered.length}</b> пар ({filtered.length * 2} ног) из{' '}
                {pack.trades.length}
              </>
            )}
          </span>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              className="tab-btn !px-2 !py-1 text-[11px]"
              onClick={() => downloadTradesCsv(filtered, `trades_tatn_${filtered.length}.csv`)}
            >
              CSV ({filtered.length})
            </button>
            {hasFilters ? (
              <button type="button" className="tab-btn !px-2 !py-1 text-[11px]" onClick={clearFilters}>
                Сбросить фильтры
              </button>
            ) : null}
          </div>
        </div>
        <div
          className={`scrollbar-thin overflow-auto rounded-xl border border-panel-border-soft ${
            mobileListOnly ? 'trades-table-mobile min-h-0 flex-1' : 'max-h-[560px]'
          }`}
        >
          <table
            className={`w-full text-left text-ink-2 ${mobileListOnly ? 'trades-table-mobile__grid text-[10px]' : 'min-w-[1100px] text-[12px]'}`}
          >
            <thead className="sticky top-0 z-10 bg-[rgba(8,16,28,0.98)]">
              <tr>
                {columns.map((col) => {
                  const active = sortKey === col.key
                  const preset = presets.get(col.key)!
                  const value = filters[col.key] ?? ''
                  const useSelect = col.filterKind === 'select' || preset.options.length > 1
                  const sortable = col.key !== 'leg_ticker' && col.key !== 'leg_action'

                  return (
                    <th
                      key={col.key}
                      className={`border-b border-panel-border-soft align-top ${
                        mobileListOnly ? 'trades-mobile-cell' : 'px-2 py-1.5'
                      }`}
                    >
                      {sortable ? (
                        <button
                          type="button"
                          className={`flex w-full items-center font-medium hover:text-ink-1 ${mobileListOnly ? 'gap-0.5' : 'gap-1'} ${col.align === 'right' ? 'justify-end' : ''} ${active ? 'text-ink-1' : 'text-ink-3'}`}
                          onClick={() => toggleSort(col.key)}
                        >
                          {col.label}
                          <span className={`opacity-70 ${mobileListOnly ? 'text-[8px]' : 'text-[10px]'}`}>
                            {active ? (sortDir === 'asc' ? '▲' : '▼') : '↕'}
                          </span>
                        </button>
                      ) : (
                        <span className={`block font-medium text-ink-3 ${col.align === 'right' ? 'text-right' : ''}`}>
                          {col.label}
                        </span>
                      )}
                      {!mobileListOnly &&
                      col.key !== 'leg_ticker' &&
                      col.key !== 'leg_action' ? (
                        useSelect ? (
                          <select
                            value={value}
                            onChange={(e) => setFilter(col.key, e.target.value)}
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
                              list={`filter-${col.key}`}
                              placeholder={preset.placeholder}
                              value={value}
                              onChange={(e) => setFilter(col.key, e.target.value)}
                              className="mt-1 !px-2 !py-1 text-[11px]"
                              onClick={(e) => e.stopPropagation()}
                            />
                            <datalist id={`filter-${col.key}`}>
                              {preset.options
                                .filter((o) => o.value)
                                .map((opt) => (
                                  <option key={opt.value} value={opt.value}>
                                    {opt.label}
                                  </option>
                                ))}
                            </datalist>
                          </>
                        )
                      ) : !mobileListOnly ? (
                        <div className="mt-1 text-[10px] text-ink-3">{col.key === 'leg_ticker' ? 'TATN · TATNP' : '↔ пара'}</div>
                      ) : null}
                    </th>
                  )
                })}
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 ? (
                <tr>
                  <td colSpan={columns.length} className="px-3 py-8 text-center text-ink-3">
                    Нет сделок по заданным фильтрам
                  </td>
                </tr>
              ) : (
                filtered.map((t) =>
                  mobileListOnly ? (
                    <SimpleTradeRow key={t.no} trade={t} columns={columns} />
                  ) : (
                    <PairTradeRows key={t.no} trade={t} columns={columns} />
                  ),
                )
              )}
            </tbody>
          </table>
        </div>
        {!mobileListOnly ? (
          <p className="text-[10px] text-ink-3">
            Фильтры предзаполнены из данных · числа: <code className="text-ink-2">&gt;1000</code>,{' '}
            <code className="text-ink-2">&lt;=0</code> или выбор из списка
          </p>
        ) : null}
      </div>
    </div>
  )
}
