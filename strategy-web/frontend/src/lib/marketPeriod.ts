/** Периоды графика «Рынок» — как Period в Android MOEX MVP. */
export type MarketPeriod = '1D' | '1W' | '1M' | '3M' | '6M' | '1Y'

export const MARKET_PERIODS: { id: MarketPeriod; label: string }[] = [
  { id: '1D', label: '1D' },
  { id: '1W', label: '1W' },
  { id: '1M', label: '1M' },
  { id: '3M', label: '3M' },
  { id: '6M', label: '6M' },
  { id: '1Y', label: '1Y' },
]

export function periodStartTs(period: MarketPeriod, tillTs: number): number {
  const till = new Date(tillTs * 1000)
  const start = new Date(till)
  switch (period) {
    case '1D':
      start.setDate(start.getDate() - 1)
      break
    case '1W':
      start.setDate(start.getDate() - 7)
      break
    case '1M':
      start.setMonth(start.getMonth() - 1)
      break
    case '3M':
      start.setMonth(start.getMonth() - 3)
      break
    case '6M':
      start.setMonth(start.getMonth() - 6)
      break
    case '1Y':
      start.setDate(start.getDate() - 365)
      break
  }
  return Math.floor(start.getTime() / 1000)
}

export function filterByPeriod<T extends { time: number }>(data: T[], period: MarketPeriod): T[] {
  if (!data.length) return []
  const till = data[data.length - 1]!.time
  const from = periodStartTs(period, till)
  return data.filter((p) => p.time >= from)
}
