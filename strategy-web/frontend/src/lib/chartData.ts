/** Подготовка данных для lightweight-charts: сортировка + уникальные time. */
export function prepareLineData(data: { time: number; value: number }[]): { time: number; value: number }[] {
  if (!data.length) return []
  const byTime = new Map<number, number>()
  for (const p of data) {
    if (!Number.isFinite(p.time) || !Number.isFinite(p.value)) continue
    byTime.set(p.time, p.value)
  }
  return [...byTime.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([time, value]) => ({ time, value }))
}
