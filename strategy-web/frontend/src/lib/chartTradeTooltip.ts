import type { IChartApi, ISeriesApi, MouseEventParams, Time } from 'lightweight-charts'
import type { Trade, TradeMarker } from '@/types'

/** 15m bar length (seconds). */
export const BAR_15M_SEC = 15 * 60

/** Half-bar time tolerance: crosshair may snap to adjacent 15m open. */
export const MARKER_NEAR_SEC = BAR_15M_SEC / 2

/** Euclidean hit radius around marker screen position. */
export const MARKER_HIT_RADIUS_PX = 38

/** X-only slack when Y is off (стрелки входа выше/ниже бара). */
export const MARKER_HIT_RADIUS_X_PX = 32

export type ChartTradeTooltipView = {
  trade: Trade
  marker: TradeMarker
  /** Координаты viewport (position: fixed). */
  left: number
  top: number
  accumulatedPnlRub: number
}

export type MarkerHitOptions = {
  maxDeltaSec?: number
  hitRadiusPx?: number
  hitRadiusXPx?: number
  /** Candlestick/line: x from crosshair, prefer pixel over param.time snap. */
  preferPixelHit?: boolean
}

export function buildTradeByNo(trades: Trade[]): Map<number, Trade> {
  const map = new Map<number, Trade>()
  for (const t of trades) map.set(t.no, t)
  return map
}

/** Накопленный PnL по номеру сделки (сумма pnl_rub с #1). */
export function buildAccumulatedPnlByTradeNo(trades: Trade[]): Map<number, number> {
  const sorted = [...trades].sort((a, b) => a.no - b.no)
  const map = new Map<number, number>()
  let cum = 0
  for (const t of sorted) {
    cum += t.pnl_rub
    map.set(t.no, cum)
  }
  return map
}

export function accumulatedPnlForMarker(
  accumulatedByNo: Map<number, number>,
  tradeNo: number,
  isEntry: boolean,
): number {
  if (isEntry) {
    if (tradeNo <= 1) return 0
    return accumulatedByNo.get(tradeNo - 1) ?? 0
  }
  return accumulatedByNo.get(tradeNo) ?? 0
}

/** Y на графике Z: из маркера или entry_z/exit_z сделки. */
export function markerChartPrice(marker: TradeMarker, trade: Trade | undefined): number {
  if (Number.isFinite(marker.z_score) && Math.abs(marker.z_score) > 1e-9) {
    return marker.z_score
  }
  if (!trade) return marker.z_score
  return marker.event === 'вход' ? trade.entry_z : trade.exit_z
}

export function findNearestMarker(
  markers: TradeMarker[],
  timeSec: number,
  maxDeltaSec = MARKER_NEAR_SEC,
): TradeMarker | null {
  if (!markers.length) return null
  let best: TradeMarker | null = null
  let bestDelta = maxDeltaSec + 1
  for (const m of markers) {
    const d = Math.abs(m.time - timeSec)
    if (d <= maxDeltaSec && d < bestDelta) {
      bestDelta = d
      best = m
    }
  }
  return best
}

export function markerScreenPosition(
  chart: IChartApi,
  series: ISeriesApi<'Line'> | ISeriesApi<'Candlestick'>,
  marker: TradeMarker,
  tradeByNo: Map<number, Trade>,
): { x: number; y: number } | null {
  const x = chart.timeScale().timeToCoordinate(marker.time as Time)
  if (x == null) return null
  const trade = tradeByNo.get(marker.trade_no)
  const price = markerChartPrice(marker, trade)
  const y = series.priceToCoordinate(price)
  if (y == null) return null
  return { x, y }
}

type MarkerCandidate = { marker: TradeMarker; score: number }

/**
 * Hit test by pixel distance (timeToCoordinate + priceToCoordinate) and/or time.
 * Picks nearest entry/exit when both markers share trade_no but differ in time.
 */
export function findNearestMarkerAtPoint(
  chart: IChartApi,
  series: ISeriesApi<'Line'> | ISeriesApi<'Candlestick'>,
  markers: TradeMarker[],
  point: { x: number; y: number },
  timeSec: number | null,
  tradeByNo: Map<number, Trade>,
  options: MarkerHitOptions = {},
): TradeMarker | null {
  if (!markers.length) return null

  const maxDeltaSec = options.maxDeltaSec ?? MARKER_NEAR_SEC
  const hitRadiusPx = options.hitRadiusPx ?? MARKER_HIT_RADIUS_PX
  const hitRadiusXPx = options.hitRadiusXPx ?? MARKER_HIT_RADIUS_X_PX
  const preferPixelHit = options.preferPixelHit ?? false

  let bestPixel: MarkerCandidate | null = null
  let bestTime: MarkerCandidate | null = null

  for (const m of markers) {
    const pos = markerScreenPosition(chart, series, m, tradeByNo)
    const x = pos?.x ?? chart.timeScale().timeToCoordinate(m.time as Time)
    if (x == null) continue

    const dx = Math.abs(point.x - x)
    let pixelHit = false
    let pixelScore = Number.POSITIVE_INFINITY

    if (pos) {
      const dy = Math.abs(point.y - pos.y)
      const dist = Math.hypot(dx, dy)
      pixelHit = dist <= hitRadiusPx || dx <= hitRadiusXPx
      pixelScore = preferPixelHit ? dx * 1.5 + dy : dist
    } else {
      pixelHit = dx <= hitRadiusXPx
      pixelScore = dx
    }

    if (pixelHit && (!bestPixel || pixelScore < bestPixel.score)) {
      bestPixel = { marker: m, score: pixelScore }
    }

    if (timeSec != null) {
      const delta = Math.abs(m.time - timeSec)
      if (delta <= maxDeltaSec && (!bestTime || delta < bestTime.score)) {
        bestTime = { marker: m, score: delta }
      }
    }
  }

  if (preferPixelHit && bestPixel) return bestPixel.marker
  if (bestPixel && bestTime) {
    return bestPixel.score <= bestTime.score * 4 ? bestPixel.marker : bestTime.marker
  }
  return bestPixel?.marker ?? bestTime?.marker ?? null
}

/** UTCTimestamp from crosshair; candlestick often omits param.time — prefer x→time. */
function crosshairTimeSec(
  param: MouseEventParams,
  chart: IChartApi,
  preferXTime: boolean,
): number | null {
  if (preferXTime && param.point) {
    const fromX = chart.timeScale().coordinateToTime(param.point.x)
    if (typeof fromX === 'number' && Number.isFinite(fromX)) return fromX
  }
  const t = param.time
  if (typeof t === 'number' && Number.isFinite(t)) return t
  if (!param.point) return null
  const fromX = chart.timeScale().coordinateToTime(param.point.x)
  if (typeof fromX === 'number' && Number.isFinite(fromX)) return fromX
  return null
}

export function attachChartTradeTooltip(
  chart: IChartApi,
  series: ISeriesApi<'Line'> | ISeriesApi<'Candlestick'>,
  markers: TradeMarker[] | undefined,
  trades: Trade[] | undefined,
  onChange: (view: ChartTradeTooltipView | null) => void,
  options: MarkerHitOptions = {},
): () => void {
  const tradeByNo = trades?.length ? buildTradeByNo(trades) : new Map<number, Trade>()
  const accumulatedByNo = trades?.length ? buildAccumulatedPnlByTradeNo(trades) : new Map<number, number>()
  if (!markers?.length || tradeByNo.size === 0) {
    onChange(null)
    return () => onChange(null)
  }

  const preferXTime = options.preferPixelHit === true

  const handler = (param: MouseEventParams) => {
    if (!param.point || param.point.x < 0 || param.point.y < 0) {
      onChange(null)
      return
    }

    const timeSec = crosshairTimeSec(param, chart, preferXTime)
    const marker = findNearestMarkerAtPoint(
      chart,
      series,
      markers,
      param.point,
      timeSec,
      tradeByNo,
      options,
    )
    if (!marker) {
      onChange(null)
      return
    }

    const trade = tradeByNo.get(marker.trade_no)
    if (!trade) {
      onChange(null)
      return
    }

    const pos = markerScreenPosition(chart, series, marker, tradeByNo)
    onChange({
      trade,
      marker,
      left: pos?.x ?? param.point.x,
      top: pos?.y ?? param.point.y,
    })
  }

  chart.subscribeCrosshairMove(handler)
  return () => {
    chart.unsubscribeCrosshairMove(handler)
    onChange(null)
  }
}
