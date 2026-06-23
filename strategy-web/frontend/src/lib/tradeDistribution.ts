import type { Trade } from '@/types'

export type DistributionBar = {
  bucket: string
  count: number
  tone?: 'good' | 'bad' | 'neutral'
  highlight?: boolean
}

export type TimeGranularityMin = 5 | 10 | 15 | 30 | 60 | 240

export const TIME_GRANULARITY_OPTIONS: { value: TimeGranularityMin; label: string; hint: string }[] = [
  { value: 240, label: '4 ч × 6 интервалов', hint: 'Сессии по полдня' },
  { value: 60, label: '1 ч × 24 слота', hint: 'Почасово' },
  { value: 30, label: '30 мин × 48', hint: 'Полчаса' },
  { value: 15, label: '15 мин × 96', hint: 'Как бар MOEX 15м' },
  { value: 10, label: '10 мин × 144', hint: 'Детально' },
  { value: 5, label: '5 мин × 288', hint: 'Максимальная детализация' },
]

export type DistributionDef = {
  id: string
  label: string
  group: string
  hint?: string
  timeField?: 'entry_time' | 'exit_time'
  bucket: (t: Trade) => string
  sortBuckets?: (keys: string[]) => string[]
  tone?: (bucket: string) => DistributionBar['tone']
  summary?: (trades: Trade[]) => string
}

const DOW = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'] as const

function parseTs(s: string): Date | null {
  const d = new Date(s.replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? null : d
}

export { parseTs }

export function formatPct(count: number, total: number): string {
  if (!total) return '0%'
  return `${((100 * count) / total).toFixed(1).replace('.', ',')}%`
}

function slotLabel(totalMinutes: number): string {
  const h = Math.floor(totalMinutes / 60)
  const m = totalMinutes % 60
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
}

export function bucketTimeOfDay(d: Date, granMin: TimeGranularityMin): string {
  const mod = d.getHours() * 60 + d.getMinutes()
  const slot = Math.floor(mod / granMin) * granMin
  return slotLabel(slot)
}

function allDaySlotLabels(granMin: TimeGranularityMin): string[] {
  const out: string[] = []
  for (let m = 0; m < 1440; m += granMin) out.push(slotLabel(m))
  return out
}

export function isTimeOfDayDistribution(id: string): boolean {
  return id === 'entry_hour' || id === 'exit_hour'
}

function hourBucketFixed(d: Date): string {
  const h = d.getHours()
  if (h < 6) return '0–6'
  if (h < 10) return '6–10'
  if (h < 14) return '10–14'
  if (h < 18) return '14–18'
  if (h < 22) return '18–22'
  return '22–24'
}

const HOUR_ORDER = ['0–6', '6–10', '10–14', '14–18', '18–22', '22–24']

function monthBucket(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  return `${y}-${m}`
}

function holdHours(t: Trade): number {
  const a = parseTs(t.entry_time)
  const b = parseTs(t.exit_time)
  if (!a || !b) return 0
  return Math.max(0, (b.getTime() - a.getTime()) / 3_600_000)
}

/** Длительность сделки entry→exit, часы. */
export function tradeHoldHours(t: Trade): number {
  return holdHours(t)
}

function holdLabel(h: number): string {
  if (h < 4) return '<4ч'
  if (h < 12) return '4–12ч'
  if (h < 24) return '12–24ч'
  if (h < 48) return '1–2д'
  if (h < 72) return '2–3д'
  if (h < 120) return '3–5д'
  return '>5д'
}

const HOLD_ORDER = ['<4ч', '4–12ч', '12–24ч', '1–2д', '2–3д', '3–5д', '>5д']

export const HOLD_DURATION_ORDER = HOLD_ORDER

export function holdDurationBucket(hours: number): string {
  return holdLabel(hours)
}

function pnlRubBucket(v: number): string {
  if (v < 0) return '<0 ₽'
  if (v < 1_000) return '0–1k'
  if (v < 3_000) return '1–3k'
  if (v < 5_000) return '3–5k'
  if (v < 10_000) return '5–10k'
  return '>10k'
}

const PNL_RUB_ORDER = ['<0 ₽', '0–1k', '1–3k', '3–5k', '5–10k', '>10k']

function pnlSpreadBucket(v: number): string {
  if (v < -0.2) return '<-0.2'
  if (v < 0) return '-0.2…0'
  if (v < 0.1) return '0…0.1'
  if (v < 0.3) return '0.1…0.3'
  if (v < 0.5) return '0.3…0.5'
  return '>0.5'
}

const PNL_SPREAD_ORDER = ['<-0.2', '-0.2…0', '0…0.1', '0.1…0.3', '0.3…0.5', '>0.5']

function zBucket(v: number): string {
  if (v <= -1.5) return '≤-1.5'
  if (v <= -1) return '-1.5…-1'
  if (v <= -0.5) return '-1…-0.5'
  if (v < 0.5) return '-0.5…0.5'
  if (v < 1) return '0.5…1'
  if (v < 1.5) return '1…1.5'
  return '>1.5'
}

const Z_ORDER = ['≤-1.5', '-1.5…-1', '-1…-0.5', '-0.5…0.5', '0.5…1', '1…1.5', '>1.5']

function spreadBucket(v: number): string {
  if (v < 4) return '<4%'
  if (v < 6) return '4–6%'
  if (v < 8) return '6–8%'
  if (v < 10) return '8–10%'
  return '>10%'
}

const SPREAD_ORDER = ['<4%', '4–6%', '6–8%', '8–10%', '>10%']

function sortByOrder(order: string[]) {
  const idx = new Map(order.map((k, i) => [k, i]))
  return (keys: string[]) => [...keys].sort((a, b) => (idx.get(a) ?? 999) - (idx.get(b) ?? 999))
}

function numericSummary(trades: Trade[], pick: (t: Trade) => number, fmt: (v: number) => string): string {
  if (!trades.length) return 'Нет данных'
  const vals = trades.map(pick)
  const sum = vals.reduce((a, b) => a + b, 0)
  const mean = sum / vals.length
  const sorted = [...vals].sort((a, b) => a - b)
  const med = sorted[Math.floor(sorted.length / 2)]!
  return `ср. ${fmt(mean)} · медиана ${fmt(med)} · min ${fmt(sorted[0]!)} · max ${fmt(sorted[sorted.length - 1]!)}`
}

function timeSummary(trades: Trade[], field: 'entry_time' | 'exit_time'): string {
  if (!trades.length) return 'Нет данных'
  const dates = trades.map((t) => parseTs(t[field])).filter(Boolean) as Date[]
  if (!dates.length) return '—'
  dates.sort((a, b) => a.getTime() - b.getTime())
  const fmt = (d: Date) =>
    d.toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
  return `${fmt(dates[0]!)} … ${fmt(dates[dates.length - 1]!)}`
}

export const TRADE_DISTRIBUTIONS: DistributionDef[] = [
  {
    id: 'entry_hour',
    label: 'Час входа',
    group: 'Время',
    hint: 'Сессия суток (MSK по timestamp CSV)',
    timeField: 'entry_time',
    bucket: (t) => {
      const d = parseTs(t.entry_time)
      return d ? hourBucketFixed(d) : '?'
    },
    sortBuckets: sortByOrder(HOUR_ORDER),
    summary: (trades) => timeSummary(trades, 'entry_time'),
  },
  {
    id: 'exit_hour',
    label: 'Час выхода',
    group: 'Время',
    timeField: 'exit_time',
    bucket: (t) => {
      const d = parseTs(t.exit_time)
      return d ? hourBucketFixed(d) : '?'
    },
    sortBuckets: sortByOrder(HOUR_ORDER),
    summary: (trades) => timeSummary(trades, 'exit_time'),
  },
  {
    id: 'entry_dow',
    label: 'День недели входа',
    group: 'Время',
    bucket: (t) => {
      const d = parseTs(t.entry_time)
      return d ? DOW[d.getDay()]! : '?'
    },
    sortBuckets: (keys) => [...keys].sort((a, b) => DOW.indexOf(a as (typeof DOW)[number]) - DOW.indexOf(b as (typeof DOW)[number])),
  },
  {
    id: 'exit_dow',
    label: 'День недели выхода',
    group: 'Время',
    bucket: (t) => {
      const d = parseTs(t.exit_time)
      return d ? DOW[d.getDay()]! : '?'
    },
    sortBuckets: (keys) => [...keys].sort((a, b) => DOW.indexOf(a as (typeof DOW)[number]) - DOW.indexOf(b as (typeof DOW)[number])),
  },
  {
    id: 'entry_month',
    label: 'Месяц входа',
    group: 'Время',
    bucket: (t) => {
      const d = parseTs(t.entry_time)
      return d ? monthBucket(d) : '?'
    },
    sortBuckets: (keys) => [...keys].sort(),
    summary: (trades) => timeSummary(trades, 'entry_time'),
  },
  {
    id: 'exit_month',
    label: 'Месяц выхода',
    group: 'Время',
    bucket: (t) => {
      const d = parseTs(t.exit_time)
      return d ? monthBucket(d) : '?'
    },
    sortBuckets: (keys) => [...keys].sort(),
    summary: (trades) => timeSummary(trades, 'exit_time'),
  },
  {
    id: 'hold_hours',
    label: 'Длительность сделки',
    group: 'Время',
    bucket: (t) => holdLabel(holdHours(t)),
    sortBuckets: sortByOrder(HOLD_ORDER),
    summary: (trades) => numericSummary(trades, holdHours, (v) => `${Math.round(v)} ч`),
  },
  {
    id: 'direction',
    label: 'Направление',
    group: 'Сделка',
    bucket: (t) => t.direction,
    sortBuckets: (keys) => [...keys].sort(),
    tone: (b) => (b === 'LONG' ? 'good' : b === 'SHORT' ? 'bad' : 'neutral'),
    summary: (trades) => {
      const long = trades.filter((t) => t.direction === 'LONG').length
      const short = trades.length - long
      return `LONG ${long} · SHORT ${short}`
    },
  },
  {
    id: 'pnl_rub',
    label: 'PnL ₽',
    group: 'Прибыльность',
    bucket: (t) => pnlRubBucket(t.pnl_rub),
    sortBuckets: sortByOrder(PNL_RUB_ORDER),
    tone: (b) => (b === '<0 ₽' ? 'bad' : 'neutral'),
    summary: (trades) => {
      const wins = trades.filter((t) => t.pnl_rub > 0).length
      const pnl = trades.reduce((s, t) => s + t.pnl_rub, 0)
      return `win ${((wins / trades.length) * 100).toFixed(0)}% · Σ ${Math.round(pnl).toLocaleString('ru-RU')} ₽`
    },
  },
  {
    id: 'pnl_outcome',
    label: 'Исход (±)',
    group: 'Прибыльность',
    bucket: (t) => (t.pnl_rub > 0 ? 'Прибыль' : t.pnl_rub < 0 ? 'Убыток' : 'Ноль'),
    sortBuckets: sortByOrder(['Убыток', 'Ноль', 'Прибыль']),
    tone: (b) => (b === 'Прибыль' ? 'good' : b === 'Убыток' ? 'bad' : 'neutral'),
    summary: (trades) => {
      const wins = trades.filter((t) => t.pnl_rub > 0).length
      return `${wins} / ${trades.length} прибыльных`
    },
  },
  {
    id: 'pnl_spread',
    label: 'PnL spread',
    group: 'Прибыльность',
    bucket: (t) => pnlSpreadBucket(t.pnl_spread_pts),
    sortBuckets: sortByOrder(PNL_SPREAD_ORDER),
    summary: (trades) => numericSummary(trades, (t) => t.pnl_spread_pts, (v) => v.toFixed(3)),
  },
  {
    id: 'entry_z',
    label: 'Z-score входа',
    group: 'Z / Spread',
    bucket: (t) => zBucket(t.entry_z),
    sortBuckets: sortByOrder(Z_ORDER),
    summary: (trades) => numericSummary(trades, (t) => t.entry_z, (v) => v.toFixed(2)),
  },
  {
    id: 'exit_z',
    label: 'Z-score выхода',
    group: 'Z / Spread',
    bucket: (t) => zBucket(t.exit_z),
    sortBuckets: sortByOrder(Z_ORDER),
    summary: (trades) => numericSummary(trades, (t) => t.exit_z, (v) => v.toFixed(2)),
  },
  {
    id: 'entry_spread',
    label: 'Spread входа',
    group: 'Z / Spread',
    bucket: (t) => spreadBucket(t.entry_spread),
    sortBuckets: sortByOrder(SPREAD_ORDER),
    summary: (trades) => numericSummary(trades, (t) => t.entry_spread, (v) => `${v.toFixed(2)}%`),
  },
  {
    id: 'exit_spread',
    label: 'Spread выхода',
    group: 'Z / Spread',
    bucket: (t) => spreadBucket(t.exit_spread),
    sortBuckets: sortByOrder(SPREAD_ORDER),
    summary: (trades) => numericSummary(trades, (t) => t.exit_spread, (v) => `${v.toFixed(2)}%`),
  },
]

export const DISTRIBUTION_BY_ID = new Map(TRADE_DISTRIBUTIONS.map((d) => [d.id, d]))

function buildTimeOfDayHistogram(
  trades: Trade[],
  field: 'entry_time' | 'exit_time',
  granMin: TimeGranularityMin,
): DistributionBar[] {
  if (granMin === 240) {
    const counts = new Map<string, number>()
    for (const k of HOUR_ORDER) counts.set(k, 0)
    for (const t of trades) {
      const d = parseTs(t[field])
      if (!d) continue
      const b = hourBucketFixed(d)
      counts.set(b, (counts.get(b) ?? 0) + 1)
    }
    let peak = ''
    let peakN = 0
    for (const [k, v] of counts) {
      if (v > peakN) {
        peakN = v
        peak = k
      }
    }
    return HOUR_ORDER.map((bucket) => ({
      bucket,
      count: counts.get(bucket) ?? 0,
      highlight: bucket === peak && peakN > 0,
      tone: bucket === peak && peakN > 0 ? 'good' : 'neutral',
    }))
  }

  const labels = allDaySlotLabels(granMin)
  const counts = new Map<string, number>()
  for (const label of labels) counts.set(label, 0)
  for (const t of trades) {
    const d = parseTs(t[field])
    if (!d) continue
    const b = bucketTimeOfDay(d, granMin)
    counts.set(b, (counts.get(b) ?? 0) + 1)
  }
  let peak = ''
  let peakN = 0
  for (const [k, v] of counts) {
    if (v > peakN) {
      peakN = v
      peak = k
    }
  }
  return labels.map((bucket) => ({
    bucket,
    count: counts.get(bucket) ?? 0,
    highlight: bucket === peak && peakN > 0,
    tone: bucket === peak && peakN > 0 ? 'good' : 'neutral',
  }))
}

export function getTradeDimensionBucket(
  trade: Trade,
  dimId: string,
  timeGranularityMin?: TimeGranularityMin,
): string {
  const def = DISTRIBUTION_BY_ID.get(dimId)
  if (!def) return '?'
  if (def.timeField && timeGranularityMin != null) {
    const d = parseTs(trade[def.timeField])
    if (!d) return '?'
    if (timeGranularityMin === 240) return hourBucketFixed(d)
    return bucketTimeOfDay(d, timeGranularityMin)
  }
  return def.bucket(trade)
}

export function orderedDimensionKeys(
  dimId: string,
  timeGranularityMin?: TimeGranularityMin,
  observed?: Iterable<string>,
): string[] {
  const def = DISTRIBUTION_BY_ID.get(dimId)
  if (!def) return [...(observed ?? [])]
  if (def.timeField && timeGranularityMin != null) {
    if (timeGranularityMin === 240) return [...HOUR_ORDER]
    return allDaySlotLabels(timeGranularityMin)
  }
  const keys = observed ? [...new Set(observed)] : []
  if (def.sortBuckets) return def.sortBuckets(keys)
  return keys.sort((a, b) => a.localeCompare(b, 'ru'))
}

export function buildTradeHistogram(
  trades: Trade[],
  def: DistributionDef,
  opts?: { timeGranularityMin?: TimeGranularityMin },
): DistributionBar[] {
  if (def.timeField && opts?.timeGranularityMin) {
    return buildTimeOfDayHistogram(trades, def.timeField, opts.timeGranularityMin)
  }

  const counts = new Map<string, number>()
  for (const t of trades) {
    const b = def.bucket(t)
    counts.set(b, (counts.get(b) ?? 0) + 1)
  }
  let keys = [...counts.keys()]
  if (def.sortBuckets) keys = def.sortBuckets(keys)
  else keys.sort((a, b) => a.localeCompare(b, 'ru'))
  const max = Math.max(...keys.map((k) => counts.get(k) ?? 0), 0)
  return keys.map((bucket) => ({
    bucket,
    count: counts.get(bucket) ?? 0,
    tone: def.tone?.(bucket) ?? 'neutral',
    highlight: (counts.get(bucket) ?? 0) === max && max > 0,
  }))
}

export function distributionGroups(): { group: string; items: DistributionDef[] }[] {
  const map = new Map<string, DistributionDef[]>()
  for (const d of TRADE_DISTRIBUTIONS) {
    const list = map.get(d.group) ?? []
    list.push(d)
    map.set(d.group, list)
  }
  return [...map.entries()].map(([group, items]) => ({ group, items }))
}
