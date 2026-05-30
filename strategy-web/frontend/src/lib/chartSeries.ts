/** Точка Z/spread с бэкенда или live. */
export type ZscorePoint = {
  time: number
  z_score: number
  spread_percent?: number
}

export function spreadFromZPoint(p: ZscorePoint): number {
  const v = p.spread_percent
  return typeof v === 'number' && Number.isFinite(v) ? v : Number.NaN
}

/** Подмешать spread из эталонного ряда (pack), если в live-точке поля нет. */
export function enrichZscoreSpread(points: ZscorePoint[], fallback: ZscorePoint[]): ZscorePoint[] {
  if (!points.length) return points
  const byTime = new Map<number, number>()
  for (const p of fallback) {
    const sp = spreadFromZPoint(p)
    if (Number.isFinite(sp)) byTime.set(p.time, sp)
  }
  return points.map((p) => {
    const sp = spreadFromZPoint(p)
    if (Number.isFinite(sp)) return p
    const fb = byTime.get(p.time)
    return fb != null ? { ...p, spread_percent: fb } : p
  })
}

export function toZLine(points: ZscorePoint[]): { time: number; value: number }[] {
  return points
    .filter((p) => Number.isFinite(p.time) && Number.isFinite(p.z_score))
    .map((p) => ({ time: p.time, value: p.z_score }))
}

export function toSpreadLine(points: ZscorePoint[]): { time: number; value: number }[] {
  return points
    .map((p) => ({ time: p.time, value: spreadFromZPoint(p) }))
    .filter((p) => Number.isFinite(p.time) && Number.isFinite(p.value))
}
