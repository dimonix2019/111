import type { IChartApi, ISeriesApi, MouseEventParams, Time } from 'lightweight-charts'
import type { Trade, TradeMarker } from '@/types'
import { lookupDrawdownAtTime, type EquityPoint } from '@/lib/drawdownOverlay'

/** 15m bar length (seconds). */
export const BAR_15M_SEC = 15 * 60

/** Half-bar time tolerance: crosshair may snap to adjacent 15m open. */
export const MARKER_NEAR_SEC = BAR_15M_SEC / 2

/** Euclidean hit radius around marker screen position. */
export const MARKER_HIT_RADIUS_PX = 48

/** X-only slack when Y is off (стрелки входа выше/ниже бара). */
export const MARKER_HIT_RADIUS_X_PX = 44

/** Доп. slack по X для маркеров входа (belowBar / aboveBar). */
export const MARKER_ENTRY_HIT_RADIUS_X_PX = 56

export type ChartTradeTooltipView = {
  trade: Trade
  marker: TradeMarker
  /** Координаты viewport (position: fixed). */
  left: number
  top: number
  /** Центр жёлтого отрезка entry→exit (viewport), для позиции карточки на мобильном. */
  segmentCenter?: { left: number; top: number }
  accumulatedPnlRub: number
  /** Просадка ₽ на момент маркера (≤ 0). */
  drawdownRub: number
}

export type ChartTradeTooltipPersist = {
  /** `${trade_no}:${event}:${time}` — переживает переподключение tooltip. */
  pinnedKey: string | null
}

export function markerPersistKey(marker: TradeMarker): string {
  return `${marker.trade_no}:${marker.event}:${marker.time}`
}

function findMarkerByKey(markers: TradeMarker[], key: string): TradeMarker | null {
  return markers.find((m) => markerPersistKey(m) === key) ?? null
}

/** Задержка перед скрытием hover-карточки (resize/setData дают кратковременный «пустой» crosshair). */
const HOVER_CLEAR_MS = 160

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
  let y = series.priceToCoordinate(price)
  if (y == null) return null
  // Стрелки входа рисуются belowBar/aboveBar — смещаем hit-point к иконке.
  const yOffset = marker.event === 'вход' ? (marker.direction === 'LONG' ? 18 : -18) : 0
  return { x, y: y + yOffset }
}

/** Локальные координаты lightweight-charts → viewport (для position: fixed). */
export function chartLocalToViewport(
  chart: IChartApi,
  local: { x: number; y: number },
): { left: number; top: number } {
  const rect = chart.chartElement().getBoundingClientRect()
  return { left: rect.left + local.x, top: rect.top + local.y }
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
  const preferPixelHit = options.preferPixelHit ?? false

  let bestPixel: MarkerCandidate | null = null
  let bestTime: MarkerCandidate | null = null

  for (const m of markers) {
    const pos = markerScreenPosition(chart, series, m, tradeByNo)
    const x = pos?.x ?? chart.timeScale().timeToCoordinate(m.time as Time)
    if (x == null) continue

    const dx = Math.abs(point.x - x)
    const hitRadiusXPx =
      m.event === 'вход'
        ? (options.hitRadiusXPx ?? MARKER_ENTRY_HIT_RADIUS_X_PX)
        : (options.hitRadiusXPx ?? MARKER_HIT_RADIUS_X_PX)
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

function parseTradeTimeSec(s: string): number | null {
  const ms = new Date(s.replace(' ', 'T')).getTime()
  return Number.isFinite(ms) ? Math.round(ms / 1000) : null
}

/** Середина отрезка сделки на экране (как жёлтая линия entry→exit). */
export function tradeSegmentViewportCenter(
  chart: IChartApi,
  series: ISeriesApi<'Line'> | ISeriesApi<'Candlestick'>,
  trade: Trade,
): { left: number; top: number } | undefined {
  const t0 = parseTradeTimeSec(trade.entry_time)
  const t1 = parseTradeTimeSec(trade.exit_time)
  if (t0 == null || t1 == null) return undefined
  const x0 = chart.timeScale().timeToCoordinate(t0 as Time)
  const x1 = chart.timeScale().timeToCoordinate(t1 as Time)
  const y0 = series.priceToCoordinate(trade.entry_z)
  const y1 = series.priceToCoordinate(trade.exit_z)
  if (x0 == null || x1 == null || y0 == null || y1 == null) return undefined
  return chartLocalToViewport(chart, { x: (x0 + x1) / 2, y: (y0 + y1) / 2 })
}

function buildTooltipViewFromMarker(
  chart: IChartApi,
  series: ISeriesApi<'Line'> | ISeriesApi<'Candlestick'>,
  marker: TradeMarker,
  trade: Trade,
  tradeByNo: Map<number, Trade>,
  accumulatedByNo: Map<number, number>,
  getEquity?: () => EquityPoint[] | undefined,
  fallbackLocal?: { x: number; y: number },
): ChartTradeTooltipView {
  const pos = markerScreenPosition(chart, series, marker, tradeByNo)
  const local = pos ?? fallbackLocal ?? { x: 0, y: 0 }
  const viewport = chartLocalToViewport(chart, local)
  const segmentCenter = tradeSegmentViewportCenter(chart, series, trade)
  const isEntry = marker.event === 'вход'
  const eq = getEquity?.()
  return {
    trade,
    marker,
    left: viewport.left,
    top: viewport.top,
    segmentCenter,
    accumulatedPnlRub: accumulatedPnlForMarker(accumulatedByNo, marker.trade_no, isEntry),
    drawdownRub: eq?.length ? lookupDrawdownAtTime(eq, marker.time) : 0,
  }
}

function buildTooltipView(
  chart: IChartApi,
  series: ISeriesApi<'Line'> | ISeriesApi<'Candlestick'>,
  marker: TradeMarker,
  trade: Trade,
  param: MouseEventParams,
  tradeByNo: Map<number, Trade>,
  accumulatedByNo: Map<number, number>,
  getEquity?: () => EquityPoint[] | undefined,
): ChartTradeTooltipView {
  const fallback =
    param.point && param.point.x >= 0 && param.point.y >= 0
      ? { x: param.point.x, y: param.point.y }
      : undefined
  return buildTooltipViewFromMarker(
    chart,
    series,
    marker,
    trade,
    tradeByNo,
    accumulatedByNo,
    getEquity,
    fallback,
  )
}

function resolveMarkerAtCrosshair(
  chart: IChartApi,
  series: ISeriesApi<'Line'> | ISeriesApi<'Candlestick'>,
  markers: TradeMarker[],
  param: MouseEventParams,
  tradeByNo: Map<number, Trade>,
  options: MarkerHitOptions,
  preferXTime: boolean,
): TradeMarker | null {
  if (!param.point || param.point.x < 0 || param.point.y < 0) return null
  const timeSec = crosshairTimeSec(param, chart, preferXTime)
  return findNearestMarkerAtPoint(chart, series, markers, param.point, timeSec, tradeByNo, options)
}

export function attachChartTradeTooltip(
  chart: IChartApi,
  series: ISeriesApi<'Line'> | ISeriesApi<'Candlestick'>,
  markers: TradeMarker[] | undefined,
  trades: Trade[] | undefined,
  onChange: (view: ChartTradeTooltipView | null) => void,
  options: MarkerHitOptions = {},
  getEquity?: () => EquityPoint[] | undefined,
  persistRef?: { current: ChartTradeTooltipPersist },
): () => void {
  const tradeByNo = trades?.length ? buildTradeByNo(trades) : new Map<number, Trade>()
  const accumulatedByNo = trades?.length ? buildAccumulatedPnlByTradeNo(trades) : new Map<number, number>()
  if (!markers?.length || tradeByNo.size === 0) {
    if (!persistRef?.current.pinnedKey) onChange(null)
    return () => {
      if (!persistRef?.current.pinnedKey) onChange(null)
    }
  }

  const markerList = markers!
  const preferXTime = options.preferPixelHit === true
  let hoverMarker: TradeMarker | null = null
  let lastViewport: { left: number; top: number } | null = null
  let clearTimer: ReturnType<typeof setTimeout> | null = null

  const rememberViewport = (view: ChartTradeTooltipView) => {
    lastViewport = { left: view.left, top: view.top }
    onChange(view)
  }

  const isPinned = () => !!persistRef?.current.pinnedKey

  const clearHover = () => {
    hoverMarker = null
    if (!isPinned()) onChange(null)
  }

  const cancelClearHover = () => {
    if (clearTimer != null) {
      clearTimeout(clearTimer)
      clearTimer = null
    }
  }

  const scheduleClearHover = () => {
    if (isPinned()) return
    cancelClearHover()
    clearTimer = setTimeout(clearHover, HOVER_CLEAR_MS)
  }

  const viewForMarker = (marker: TradeMarker, fallbackLocal?: { x: number; y: number }) => {
    const trade = tradeByNo.get(marker.trade_no)
    if (!trade) return null
    const view = buildTooltipViewFromMarker(
      chart,
      series,
      marker,
      trade,
      tradeByNo,
      accumulatedByNo,
      getEquity,
      fallbackLocal,
    )
    if (!markerScreenPosition(chart, series, marker, tradeByNo) && lastViewport) {
      return { ...view, left: lastViewport.left, top: lastViewport.top }
    }
    return view
  }

  const refreshActiveTooltip = () => {
    const pinnedKey = persistRef?.current.pinnedKey
    if (pinnedKey) {
      const marker = findMarkerByKey(markerList, pinnedKey)
      if (!marker) {
        persistRef!.current.pinnedKey = null
        clearHover()
        return
      }
      const view = viewForMarker(marker)
      if (view) rememberViewport(view)
      return
    }
    if (hoverMarker) {
      const view = viewForMarker(hoverMarker)
      if (view) rememberViewport(view)
    }
  }

  const restorePinnedOnAttach = () => {
    const pinnedKey = persistRef?.current.pinnedKey
    if (!pinnedKey) return
    const marker = findMarkerByKey(markerList, pinnedKey)
    if (!marker) {
      persistRef!.current.pinnedKey = null
      return
    }
    const view = viewForMarker(marker)
    if (view) rememberViewport(view)
  }

  restorePinnedOnAttach()

  const showHoverAt = (param: MouseEventParams) => {
    if (isPinned()) return
    if (!param.point || param.point.x < 0 || param.point.y < 0) {
      scheduleClearHover()
      return
    }
    cancelClearHover()
    const marker = resolveMarkerAtCrosshair(chart, series, markerList, param, tradeByNo, options, preferXTime)
    if (!marker) {
      scheduleClearHover()
      return
    }
    const trade = tradeByNo.get(marker.trade_no)
    if (!trade) {
      scheduleClearHover()
      return
    }
    hoverMarker = marker
    rememberViewport(
      buildTooltipView(chart, series, marker, trade, param, tradeByNo, accumulatedByNo, getEquity),
    )
  }

  const onCrosshairMove = (param: MouseEventParams) => {
    if (isPinned()) {
      refreshActiveTooltip()
      return
    }
    showHoverAt(param)
  }

  const onClick = (param: MouseEventParams) => {
    cancelClearHover()
    if (!param.point || param.point.x < 0 || param.point.y < 0) {
      if (persistRef) persistRef.current.pinnedKey = null
      clearHover()
      return
    }
    const marker = resolveMarkerAtCrosshair(chart, series, markerList, param, tradeByNo, options, preferXTime)
    if (!marker) {
      if (persistRef) persistRef.current.pinnedKey = null
      clearHover()
      return
    }
    const trade = tradeByNo.get(marker.trade_no)
    if (!trade) {
      if (persistRef) persistRef.current.pinnedKey = null
      clearHover()
      return
    }
    hoverMarker = marker
    if (persistRef) persistRef.current.pinnedKey = markerPersistKey(marker)
    rememberViewport(
      buildTooltipView(chart, series, marker, trade, param, tradeByNo, accumulatedByNo, getEquity),
    )
  }

  const onLayoutChange = () => {
    refreshActiveTooltip()
  }

  const chartEl = chart.chartElement()
  const ro = new ResizeObserver(() => refreshActiveTooltip())
  ro.observe(chartEl)

  chart.timeScale().subscribeVisibleLogicalRangeChange(onLayoutChange)
  chart.subscribeCrosshairMove(onCrosshairMove)
  chart.subscribeClick(onClick)

  return () => {
    cancelClearHover()
    ro.disconnect()
    chart.timeScale().unsubscribeVisibleLogicalRangeChange(onLayoutChange)
    chart.unsubscribeCrosshairMove(onCrosshairMove)
    chart.unsubscribeClick(onClick)
    if (!persistRef?.current.pinnedKey) onChange(null)
  }
}
