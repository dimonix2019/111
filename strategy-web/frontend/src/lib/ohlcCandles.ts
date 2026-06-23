/** Бар OHLC для lightweight-charts. */
export type OhlcBar = {
  time: number
  open: number
  high: number
  low: number
  close: number
  volume?: number
}

export type OhlcFieldMap = {
  open: string
  high: string
  low: string
  close: string
  volume?: string
}

const MIN_WICK = 0.0001

function finite(v: unknown): v is number {
  return typeof v === 'number' && Number.isFinite(v)
}

/** Есть ли полный OHLC в точке API. */
export function hasNativeOhlc(row: Record<string, unknown>, fields: OhlcFieldMap): boolean {
  return [fields.open, fields.high, fields.low, fields.close].every((k) => finite(row[k]))
}

/** OHLC из API или синтетика из close-ряда (как Z-свечи). */
export function buildOhlcSeries(
  rows: Record<string, unknown>[],
  fields: OhlcFieldMap,
): OhlcBar[] {
  if (!rows.length) return []

  if (rows.some((r) => hasNativeOhlc(r, fields))) {
    const out: OhlcBar[] = []
    for (const r of rows) {
      if (!hasNativeOhlc(r, fields) || !finite(r.time)) continue
      const bar: OhlcBar = {
        time: r.time as number,
        open: r[fields.open] as number,
        high: r[fields.high] as number,
        low: r[fields.low] as number,
        close: r[fields.close] as number,
      }
      if (fields.volume && finite(r[fields.volume])) {
        bar.volume = r[fields.volume] as number
      }
      out.push(bar)
    }
    return out.sort((a, b) => a.time - b.time)
  }

  return buildOhlcFromClose(
    rows
      .filter((r) => finite(r.time) && finite(r[fields.close]))
      .map((r) => ({ time: r.time as number, close: r[fields.close] as number })),
  )
}

/** Свечи из close-only: open=prev close, high/low с соседями. */
export function buildOhlcFromClose(points: { time: number; close: number }[]): OhlcBar[] {
  if (!points.length) return []

  const byTime = new Map<number, number>()
  for (const p of points) {
    if (!Number.isFinite(p.time) || !Number.isFinite(p.close)) continue
    byTime.set(p.time, p.close)
  }

  const times = [...byTime.keys()].sort((a, b) => a - b)
  const lastIdx = times.length - 1
  const span = Math.max(MIN_WICK, estimateMinWick(points.map((p) => p.close)))

  return times.map((time, index) => {
    const close = byTime.get(time)!
    const open = index > 0 ? byTime.get(times[index - 1]!)! : close
    const prev = index > 0 ? byTime.get(times[index - 1]!)! : close
    const next = index < lastIdx ? byTime.get(times[index + 1]!)! : close

    const bodyHigh = Math.max(open, close)
    const bodyLow = Math.min(open, close)
    let high = Math.max(bodyHigh, prev, next)
    let low = Math.min(bodyLow, prev, next)

    if (high - low < span) {
      const mid = (open + close) / 2
      high = mid + span / 2
      low = mid - span / 2
    }

    return { time, open, high, low, close }
  })
}

function estimateMinWick(values: number[]): number {
  const finiteVals = values.filter(Number.isFinite)
  if (finiteVals.length < 2) return MIN_WICK
  const min = Math.min(...finiteVals)
  const max = Math.max(...finiteVals)
  const range = max - min
  if (range <= 0) return MIN_WICK
  return Math.max(MIN_WICK, range * 0.002)
}

export const TATN_OHLC_FIELDS: OhlcFieldMap = {
  open: 'tatn_open',
  high: 'tatn_high',
  low: 'tatn_low',
  close: 'tatn_close',
  volume: 'tatn_volume',
}

export const SPREAD_OHLC_FIELDS: OhlcFieldMap = {
  open: 'spread_open',
  high: 'spread_high',
  low: 'spread_low',
  close: 'spread_close',
}
