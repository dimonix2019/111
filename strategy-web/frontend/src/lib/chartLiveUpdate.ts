import type { IChartApi, ISeriesApi, UTCTimestamp } from 'lightweight-charts'

/** Ключ последней точки — обновлять series.update только при изменении. */
export function linePointKey(p: { time: UTCTimestamp; value: number }): string {
  return `${p.time}:${p.value}`
}

export function candlePointKey(p: {
  time: UTCTimestamp
  open: number
  high: number
  low: number
  close: number
}): string {
  return `${p.time}:${p.open}:${p.high}:${p.low}:${p.close}`
}

type Ref<T> = { current: T }

type LiveSyncRefs = {
  prevLen: Ref<number>
  prevLastKey: Ref<string | null>
  userInteracted: Ref<boolean>
}

function restoreVisibleRange(chart: IChartApi, refs: LiveSyncRefs): void {
  const range = refs.userInteracted.current ? chart.timeScale().getVisibleLogicalRange() : null
  if (range) {
    chart.timeScale().setVisibleLogicalRange(range)
  } else {
    chart.timeScale().scrollToRealTime()
  }
}

/**
 * Live: setData при изменении (MOEX пересчитывает Z по всей истории) + сохранение zoom/pan.
 */
export function syncLineSeriesLive(
  series: ISeriesApi<'Line'>,
  chart: IChartApi,
  points: { time: UTCTimestamp; value: number }[],
  liveMode: boolean,
  fitted: boolean,
  refs: LiveSyncRefs,
): void {
  if (!points.length) return

  const last = points[points.length - 1]!
  const lastKey = linePointKey(last)

  if (!fitted || !liveMode) {
    series.setData(points)
    refs.prevLen.current = points.length
    refs.prevLastKey.current = lastKey
    return
  }

  if (points.length !== refs.prevLen.current || lastKey !== refs.prevLastKey.current) {
    series.setData(points)
    restoreVisibleRange(chart, refs)
    refs.prevLen.current = points.length
    refs.prevLastKey.current = lastKey
  }
}

export function syncCandleSeriesLive(
  series: ISeriesApi<'Candlestick'>,
  chart: IChartApi,
  points: {
    time: UTCTimestamp
    open: number
    high: number
    low: number
    close: number
  }[],
  liveMode: boolean,
  fitted: boolean,
  refs: LiveSyncRefs,
): void {
  if (!points.length) return

  const last = points[points.length - 1]!
  const lastKey = candlePointKey(last)

  if (!fitted || !liveMode) {
    series.setData(points)
    refs.prevLen.current = points.length
    refs.prevLastKey.current = lastKey
    return
  }

  if (points.length !== refs.prevLen.current || lastKey !== refs.prevLastKey.current) {
    series.setData(points)
    restoreVisibleRange(chart, refs)
    refs.prevLen.current = points.length
    refs.prevLastKey.current = lastKey
  }
}
