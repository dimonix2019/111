import type { Trade } from '@/types'
import type { TimeGranularityMin } from '@/lib/tradeDistribution'
import {
  bucketTimeOfDay,
  buildTradeHistogram,
  DISTRIBUTION_BY_ID,
  formatPct,
  isTimeOfDayDistribution,
  parseTs,
} from '@/lib/tradeDistribution'

export { formatPct } from '@/lib/tradeDistribution'

const DOW_FULL = ['воскресенье', 'понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота'] as const
const DOW_SHORT = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'] as const

export function formatCountPct(count: number, total: number, noun = 'сделок'): string {
  return `${count} ${noun} (${formatPct(count, total)} от всех)`
}

function countMinutesWindow(trades: Trade[], field: 'entry_time' | 'exit_time', fromMin: number, toMin: number): number {
  let n = 0
  for (const t of trades) {
    const d = parseTs(t[field])
    if (!d) continue
    const mod = d.getHours() * 60 + d.getMinutes()
    if (mod >= fromMin && mod < toMin) n++
  }
  return n
}

function peakTimeSlot(
  trades: Trade[],
  field: 'entry_time' | 'exit_time',
  granMin: TimeGranularityMin,
): { slot: string; count: number } | null {
  if (!trades.length) return null
  const counts = new Map<string, number>()
  for (const t of trades) {
    const d = parseTs(t[field])
    if (!d) continue
    const slot =
      granMin === 240
        ? (() => {
            const h = d.getHours()
            if (h < 6) return '0–6'
            if (h < 10) return '6–10'
            if (h < 14) return '10–14'
            if (h < 18) return '14–18'
            if (h < 22) return '18–22'
            return '22–24'
          })()
        : bucketTimeOfDay(d, granMin)
    counts.set(slot, (counts.get(slot) ?? 0) + 1)
  }
  let best = ''
  let bestN = 0
  for (const [k, v] of counts) {
    if (v > bestN) {
      bestN = v
      best = k
    }
  }
  return bestN > 0 ? { slot: best, count: bestN } : null
}

function dowStats(trades: Trade[], field: 'entry_time' | 'exit_time') {
  const byDay = new Map<number, { count: number; pnl: number }>()
  for (let i = 0; i < 7; i++) byDay.set(i, { count: 0, pnl: 0 })
  for (const t of trades) {
    const d = parseTs(t[field])
    if (!d) continue
    const day = d.getDay()
    const cur = byDay.get(day)!
    cur.count++
    cur.pnl += t.pnl_rub
  }
  return byDay
}

function fmtRub(v: number): string {
  const sign = v >= 0 ? '+' : ''
  return `${sign}${Math.round(v).toLocaleString('ru-RU')} ₽`
}

function entryExitInsights(trades: Trade[], total: number, granMin: TimeGranularityMin): string[] {
  const lines: string[] = []
  const morning = countMinutesWindow(trades, 'entry_time', 7 * 60, 8 * 60)
  if (morning > 0) {
    lines.push(
      `${formatCountPct(morning, total, morning === 1 ? 'вход' : 'входов')} приходится на интервал 07:00–08:00.`,
    )
  }
  const evening = countMinutesWindow(trades, 'exit_time', 17 * 60, 22 * 60)
  if (evening > 0) {
    lines.push(
      `${formatCountPct(evening, total, evening === 1 ? 'выход' : 'выходов')} — вечернее окно 17:00–22:00.`,
    )
  }
  const peakIn = peakTimeSlot(trades, 'entry_time', granMin)
  if (peakIn) {
    lines.push(
      `Пик входов: ${peakIn.slot} — ${formatCountPct(peakIn.count, total, peakIn.count === 1 ? 'сделка' : 'сделок')}.`,
    )
  }
  const peakOut = peakTimeSlot(trades, 'exit_time', granMin)
  if (peakOut) {
    lines.push(
      `Пик выходов: ${peakOut.slot} — ${formatCountPct(peakOut.count, total, peakOut.count === 1 ? 'сделка' : 'сделок')}.`,
    )
  }
  return lines
}

function dowInsights(trades: Trade[], total: number): string[] {
  const byDay = dowStats(trades, 'entry_time')
  let maxCountDay = -1
  let maxCount = 0
  let bestPnlDay = -1
  let bestAvgPnl = -Infinity
  for (let d = 0; d < 7; d++) {
    const { count, pnl } = byDay.get(d)!
    if (count > maxCount) {
      maxCount = count
      maxCountDay = d
    }
    if (count > 0) {
      const avg = pnl / count
      if (avg > bestAvgPnl) {
        bestAvgPnl = avg
        bestPnlDay = d
      }
    }
  }
  const lines: string[] = []
  if (maxCountDay >= 0 && maxCount > 0) {
    lines.push(
      `Больше всего входов в ${DOW_FULL[maxCountDay]} — ${formatCountPct(maxCount, total, maxCount === 1 ? 'сделка' : 'сделок')}.`,
    )
  }
  if (bestPnlDay >= 0 && bestAvgPnl > -Infinity) {
    const { count, pnl } = byDay.get(bestPnlDay)!
    lines.push(
      `Лучший средний PnL — ${DOW_FULL[bestPnlDay]}: ${fmtRub(pnl / count)} на сделку (${formatCountPct(count, total)}).`,
    )
  }
  return lines
}

function directionInsights(trades: Trade[], total: number): string[] {
  const long = trades.filter((t) => t.direction === 'LONG')
  const short = trades.filter((t) => t.direction === 'SHORT')
  if (!long.length && !short.length) return []
  const longPnl = long.reduce((s, t) => s + t.pnl_rub, 0)
  const shortPnl = short.reduce((s, t) => s + t.pnl_rub, 0)
  const lines = [
    `LONG ${formatPct(long.length, total)} (${long.length} ${long.length === 1 ? 'сделка' : 'сделок'}), SHORT ${formatPct(short.length, total)} (${short.length}).`,
  ]
  if (long.length && short.length) {
    const better = longPnl / long.length >= shortPnl / short.length ? 'LONG' : 'SHORT'
    lines.push(`В среднем выгоднее ${better}.`)
  }
  return lines
}

function pnlInsights(trades: Trade[], total: number): string[] {
  const wins = trades.filter((t) => t.pnl_rub > 0)
  const losses = trades.filter((t) => t.pnl_rub <= 0)
  return [
    `Win rate ${formatPct(wins.length, total)} — ${wins.length} из ${total} прибыльных, ${losses.length} убыточных или в ноль.`,
  ]
}

/** Общая аналитика по всем отфильтрованным сделкам */
export function buildGeneralTradeInsights(trades: Trade[], timeGranularityMin: TimeGranularityMin = 15): string[] {
  if (!trades.length) return ['Недостаточно сделок для аналитики.']
  const total = trades.length
  const seen = new Set<string>()
  const out: string[] = []
  for (const line of [
    ...entryExitInsights(trades, total, timeGranularityMin),
    ...dowInsights(trades, total),
    ...directionInsights(trades, total),
    ...pnlInsights(trades, total),
  ]) {
    if (!seen.has(line)) {
      seen.add(line)
      out.push(line)
    }
  }
  return out.slice(0, 6)
}

/** Инсайты для выбранного показателя гистограммы */
export function buildMetricInsights(
  trades: Trade[],
  metricId: string,
  timeGranularityMin: TimeGranularityMin = 15,
): string[] {
  if (!trades.length) return []
  const total = trades.length
  const def = DISTRIBUTION_BY_ID.get(metricId)
  if (!def) return []

  if (metricId === 'entry_hour') {
    return entryExitInsights(trades, total, timeGranularityMin).filter((l) => l.includes('вход'))
  }
  if (metricId === 'exit_hour') {
    return entryExitInsights(trades, total, timeGranularityMin).filter((l) => l.includes('выход'))
  }
  if (metricId === 'entry_dow' || metricId === 'exit_dow') {
    const field = metricId === 'entry_dow' ? 'entry_time' : 'exit_time'
    const byDay = dowStats(trades, field)
    const lines: string[] = []
    for (let d = 0; d < 7; d++) {
      const { count, pnl } = byDay.get(d)!
      if (count === 0) continue
      lines.push(
        `${DOW_SHORT[d]} — ${formatCountPct(count, total, count === 1 ? 'сделка' : 'сделок')}, ср. PnL ${fmtRub(pnl / count)}.`,
      )
    }
    return lines.slice(0, 5)
  }
  if (metricId === 'pnl_outcome' || metricId === 'pnl_rub') {
    return pnlInsights(trades, total)
  }
  if (metricId === 'direction') {
    return directionInsights(trades, total)
  }

  const bars = buildTradeHistogram(trades, def, {
    timeGranularityMin: isTimeOfDayDistribution(metricId) ? timeGranularityMin : undefined,
  })
  const sorted = [...bars].sort((a, b) => b.count - a.count)
  const top = sorted.filter((b) => b.count > 0).slice(0, 3)
  if (!top.length) return []
  return top.map(
    (b) =>
      `${b.bucket}: ${formatCountPct(b.count, total, b.count === 1 ? 'сделка' : 'сделок')}${b.highlight ? ' — пик' : ''}.`,
  )
}
