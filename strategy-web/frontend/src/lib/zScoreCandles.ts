/** Точка Z-score с бэкенда (15м бар). */
export type ZScoreBar = {
  time: number
  z_score: number
  spread_percent?: number
}

export type ZScoreCandle = {
  time: number
  open: number
  high: number
  low: number
  close: number
}

/** Минимальная длина тени (Z), если тело почти doji. */
const MIN_WICK_Z = 0.025

/**
 * Свечи Z-score из 15м close-ряда (Android buildZScoreCandlesFromM15Points + тени):
 * open = Z предыдущего бара, close = Z текущего;
 * high/low учитывают соседние бары → видимые фитили как в TradingView.
 */
export function buildZScoreCandlesFromPoints(points: ZScoreBar[]): ZScoreCandle[] {
  if (!points.length) return []

  const byTime = new Map<number, number>()
  for (const p of points) {
    if (!Number.isFinite(p.time) || !Number.isFinite(p.z_score)) continue
    byTime.set(p.time, p.z_score)
  }

  const times = [...byTime.keys()].sort((a, b) => a - b)
  const lastIdx = times.length - 1

  return times.map((time, index) => {
    const close = byTime.get(time)!
    const open = index > 0 ? byTime.get(times[index - 1]!)! : close
    const prevZ = index > 0 ? byTime.get(times[index - 1]!)! : close
    const nextZ = index < lastIdx ? byTime.get(times[index + 1]!)! : close

    const bodyHigh = Math.max(open, close)
    const bodyLow = Math.min(open, close)
    let high = Math.max(bodyHigh, prevZ, nextZ)
    let low = Math.min(bodyLow, prevZ, nextZ)

    if (high - low < MIN_WICK_Z) {
      const mid = (open + close) / 2
      high = mid + MIN_WICK_Z / 2
      low = mid - MIN_WICK_Z / 2
    }

    return { time, open, high, low, close }
  })
}
