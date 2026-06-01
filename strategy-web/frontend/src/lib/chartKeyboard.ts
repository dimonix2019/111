import type { IChartApi } from 'lightweight-charts'

import { applyChartRightMargin, CHART_MAX_USER_RIGHT_MARGIN, CHART_RIGHT_OFFSET_BARS, scrollChartToEndWithMargin } from '@/lib/format'

export type ChartKeyboardOptions = {
  /** Актуальное число баров в серии (0…n-1). */
  getBarCount: () => number
  /** Пользователь сдвинул/zoom — не сбрасывать live-позицию. */
  onUserInteract?: () => void
}

type ChartBinding = {
  getChart: () => IChartApi | null
  getOptions: () => ChartKeyboardOptions
  priceMargins: { top: number; bottom: number }
}

const MIN_VISIBLE_BARS = 8
const PAN_BARS = 1
const PAN_BARS_FAST = 10
const PAGE_PAN_VISIBLE_FRACTION = 0.8
const ZOOM_IN_FACTOR = 1.12
const ZOOM_OUT_FACTOR = 1.12
const DEFAULT_PRICE_MARGINS = { top: 0.1, bottom: 0.1 }
const WHEEL_PAN_DIVISOR = 12
const WHEEL_ZOOM_SENSITIVITY = 0.006
const WHEEL_PRICE_SCALE_SENSITIVITY = 0.0012
const PRICE_SCALE_HIT_PX = 96
const MIN_PRICE_MARGIN = 0.02
const MAX_PRICE_MARGIN = 0.42
const TOUCH_PAN_PX_PER_BAR = 14

const bindings = new Map<HTMLElement, ChartBinding>()
let globalListenersInstalled = false

function isOverPriceScale(chart: IChartApi, clientX: number): boolean {
  const rect = chart.chartElement().getBoundingClientRect()
  return clientX >= rect.right - PRICE_SCALE_HIT_PX
}

function zoomPriceScaleByWheel(
  chart: IChartApi,
  deltaY: number,
  priceMargins: { top: number; bottom: number },
): void {
  if (deltaY === 0 || !Number.isFinite(deltaY)) return
  const delta = deltaY * WHEEL_PRICE_SCALE_SENSITIVITY
  priceMargins.top = Math.max(MIN_PRICE_MARGIN, Math.min(MAX_PRICE_MARGIN, priceMargins.top + delta))
  priceMargins.bottom = Math.max(MIN_PRICE_MARGIN, Math.min(MAX_PRICE_MARGIN, priceMargins.bottom + delta))
  chart.priceScale('right').applyOptions({
    autoScale: false,
    scaleMargins: { top: priceMargins.top, bottom: priceMargins.bottom },
  })
}

function isEditableTarget(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

function isArrowLeft(e: KeyboardEvent): boolean {
  return e.key === 'ArrowLeft' || e.code === 'ArrowLeft'
}

function isArrowRight(e: KeyboardEvent): boolean {
  return e.key === 'ArrowRight' || e.code === 'ArrowRight'
}

function isArrowUp(e: KeyboardEvent): boolean {
  return e.key === 'ArrowUp' || e.code === 'ArrowUp'
}

function isArrowDown(e: KeyboardEvent): boolean {
  return e.key === 'ArrowDown' || e.code === 'ArrowDown'
}

function lastBarLogical(barCount: number): number {
  return Math.max(0, barCount - 1)
}

function maxLogicalTo(barCount: number): number {
  return lastBarLogical(barCount) + CHART_RIGHT_OFFSET_BARS + CHART_MAX_USER_RIGHT_MARGIN
}

function clampRange(
  from: number,
  to: number,
  barCount: number,
  preserveSpan = false,
): { from: number; to: number } | null {
  const span = to - from
  if (!Number.isFinite(span) || span < MIN_VISIBLE_BARS) return null
  let f = from
  let t = to
  if (t - f < MIN_VISIBLE_BARS) t = f + MIN_VISIBLE_BARS
  const maxTo = maxLogicalTo(barCount)

  if (preserveSpan) {
    const windowSpan = t - f
    if (t > maxTo) {
      t = maxTo
      f = t - windowSpan
    }
    if (f < 0) {
      f = 0
      t = Math.min(windowSpan, maxTo)
    }
    if (t - f < MIN_VISIBLE_BARS) return null
    if (Math.abs(f - from) < 1e-6 && Math.abs(t - to) < 1e-6) return null
    return { from: f, to: t }
  }

  if (f < 0) {
    t -= f
    f = 0
  }
  if (t > maxTo) {
    f -= t - maxTo
    t = maxTo
  }
  if (f < 0) f = 0
  if (t - f < MIN_VISIBLE_BARS) return null
  return { from: f, to: t }
}

function applyLogicalRange(
  chart: IChartApi,
  from: number,
  to: number,
  barCount: number,
  preserveSpan = false,
): boolean {
  const next = clampRange(from, to, barCount, preserveSpan)
  if (!next) return false
  chart.timeScale().setVisibleLogicalRange(next)
  return true
}

function ensureVisibleLogicalRange(chart: IChartApi, barCount: number): void {
  if (!chart.timeScale().getVisibleLogicalRange()) {
    scrollChartToEndWithMargin(chart.timeScale(), barCount)
  }
}

function panChart(chart: IChartApi, bars: number, barCount: number): boolean {
  if (bars === 0 || barCount < 1) return false
  ensureVisibleLogicalRange(chart, barCount)
  const range = chart.timeScale().getVisibleLogicalRange()
  if (!range) return false
  return applyLogicalRange(chart, range.from + bars, range.to + bars, barCount, true)
}

function panChartPage(chart: IChartApi, direction: -1 | 1, barCount: number): boolean {
  ensureVisibleLogicalRange(chart, barCount)
  const range = chart.timeScale().getVisibleLogicalRange()
  if (!range) return false
  const span = range.to - range.from
  const bars = Math.max(PAN_BARS_FAST, Math.round(span * PAGE_PAN_VISIBLE_FRACTION)) * direction
  return panChart(chart, bars, barCount)
}

/** Zoom вокруг logical-индекса (0…barCount). */
function zoomChart(
  chart: IChartApi,
  direction: 'in' | 'out',
  barCount: number,
  anchorLogical: number,
): boolean {
  ensureVisibleLogicalRange(chart, barCount)
  const range = chart.timeScale().getVisibleLogicalRange()
  if (!range) return false
  const span = range.to - range.from
  const factor = direction === 'in' ? ZOOM_IN_FACTOR : 1 / ZOOM_OUT_FACTOR
  let newSpan = direction === 'in' ? span / factor : span * factor
  newSpan = Math.max(MIN_VISIBLE_BARS, Math.min(barCount * 0.98, newSpan))

  const anchor = Math.max(0, Math.min(barCount, anchorLogical))
  const anchorRatio = span > 0 ? (anchor - range.from) / span : 0.5
  const from = anchor - newSpan * anchorRatio
  const to = from + newSpan
  return applyLogicalRange(chart, from, to, barCount)
}

/** Плавный zoom по относительному масштабу (touch pinch); ratio > 1 — пальцы в стороны (zoom in). */
function zoomChartByRatio(
  chart: IChartApi,
  ratio: number,
  barCount: number,
  anchorLogical: number,
): boolean {
  if (!Number.isFinite(ratio) || ratio <= 0 || Math.abs(ratio - 1) < 0.002) return false
  ensureVisibleLogicalRange(chart, barCount)
  const range = chart.timeScale().getVisibleLogicalRange()
  if (!range) return false
  const span = range.to - range.from
  let newSpan = span / ratio
  newSpan = Math.max(MIN_VISIBLE_BARS, Math.min(barCount * 0.98, newSpan))

  const anchor = Math.max(0, Math.min(barCount, anchorLogical))
  const anchorRatio = span > 0 ? (anchor - range.from) / span : 0.5
  const from = anchor - newSpan * anchorRatio
  const to = from + newSpan
  return applyLogicalRange(chart, from, to, barCount)
}

function touchSpan(touches: TouchList): number {
  if (touches.length < 2) return 0
  const dx = touches[0].clientX - touches[1].clientX
  const dy = touches[0].clientY - touches[1].clientY
  return Math.hypot(dx, dy)
}

function touchCenterX(touches: TouchList): number {
  return (touches[0].clientX + touches[1].clientX) / 2
}

function wheelDeltaToPanBars(delta: number): number {
  if (delta === 0 || !Number.isFinite(delta)) return 0
  const mag = Math.max(PAN_BARS, Math.min(PAN_BARS_FAST, Math.round(Math.abs(delta) / WHEEL_PAN_DIVISOR)))
  return Math.sign(delta) * mag
}

function resetChartView(chart: IChartApi, priceMargins: { top: number; bottom: number }): void {
  chart.timeScale().fitContent()
  applyChartRightMargin(chart.timeScale())
  priceMargins.top = DEFAULT_PRICE_MARGINS.top
  priceMargins.bottom = DEFAULT_PRICE_MARGINS.bottom
  chart.priceScale('right').applyOptions({
    autoScale: true,
    scaleMargins: { ...priceMargins },
  })
}

/** Сброс zoom/pan с кнопки тулбара. */
export function resetChartViewPublic(chart: IChartApi | null): void {
  if (!chart) return
  resetChartView(chart, { ...DEFAULT_PRICE_MARGINS })
}

function scrollToStart(chart: IChartApi, barCount: number): boolean {
  ensureVisibleLogicalRange(chart, barCount)
  const range = chart.timeScale().getVisibleLogicalRange()
  if (!range) return false
  const span = range.to - range.from
  return applyLogicalRange(chart, 0, Math.min(span, barCount), barCount)
}

function scrollToEnd(chart: IChartApi, barCount: number): void {
  scrollChartToEndWithMargin(chart.timeScale(), barCount)
}

function logicalAnchorFromX(chart: IChartApi, clientX: number): number {
  const range = chart.timeScale().getVisibleLogicalRange()
  if (!range) return 0
  const chartEl = chart.chartElement()
  const rect = chartEl.getBoundingClientRect()
  const w = rect.width || 1
  const ratio = Math.max(0, Math.min(1, (clientX - rect.left) / w))
  return range.from + (range.to - range.from) * ratio
}

function normalizeWheelDelta(e: WheelEvent): number {
  let dy = e.deltaY
  if (e.deltaMode === WheelEvent.DOM_DELTA_LINE) dy *= 16
  else if (e.deltaMode === WheelEvent.DOM_DELTA_PAGE) dy *= 120
  return dy
}

/** Pinch на тачпаде (Ctrl+wheel): deltaY>0 — отдаление (больше баров). */
function zoomChartByWheelDelta(
  chart: IChartApi,
  deltaY: number,
  barCount: number,
  anchorLogical: number,
): boolean {
  if (deltaY === 0 || !Number.isFinite(deltaY)) return false
  ensureVisibleLogicalRange(chart, barCount)
  const range = chart.timeScale().getVisibleLogicalRange()
  if (!range) return false
  const span = range.to - range.from
  const factor = Math.exp(deltaY * WHEEL_ZOOM_SENSITIVITY)
  let newSpan = span * factor
  newSpan = Math.max(MIN_VISIBLE_BARS, Math.min(barCount * 0.98, newSpan))

  const anchor = Math.max(0, Math.min(barCount, anchorLogical))
  const anchorRatio = span > 0 ? (anchor - range.from) / span : 0.5
  const from = anchor - newSpan * anchorRatio
  const to = from + newSpan
  return applyLogicalRange(chart, from, to, barCount)
}

function isPinchZoomWheel(e: WheelEvent): boolean {
  return e.ctrlKey || e.metaKey
}

function isEventInsideRoot(root: HTMLElement, e: Event): boolean {
  const t = e.target
  if (!(t instanceof Node)) return false
  return root === t || root.contains(t)
}

function findBindingRoot(target: EventTarget | null): HTMLElement | null {
  if (!(target instanceof Node)) return null
  for (const root of bindings.keys()) {
    if (root === target || root.contains(target)) return root
  }
  return null
}

/** Последний график, по которому кликнули — получает стрелки без жёсткого focus на div. */
let activeKeyboardRoot: HTMLElement | null = null

/** Для синхронизации при монтировании ChartPanel. */
export function activateChartKeyboardRoot(root: HTMLElement | null): void {
  activeKeyboardRoot = root
}

function chartHasKeyboardFocus(root: HTMLElement): boolean {
  const ae = document.activeElement
  if (ae instanceof Node && (root === ae || root.contains(ae))) return true
  return activeKeyboardRoot === root
}

function shouldBlockChartKeys(root: HTMLElement): boolean {
  const ae = document.activeElement
  if (!isEditableTarget(ae)) return false
  if (!(ae instanceof Node)) return true
  // Поле внутри графика (нет) или пользователь явно кликнул по графику — стрелки работают
  if (root.contains(ae)) return true
  if (activeKeyboardRoot === root) return false
  return true
}

function handleChartKeyDown(e: KeyboardEvent, root: HTMLElement, binding: ChartBinding): void {
  if (!chartHasKeyboardFocus(root)) return
  if (shouldBlockChartKeys(root)) return

  const chart = binding.getChart()
  if (!chart) return
  const barCount = binding.getOptions().getBarCount()
  if (barCount < 1) return

  ensureVisibleLogicalRange(chart, barCount)

  const mod = e.ctrlKey || e.metaKey
  const { priceMargins } = binding
  let handled = false

  if (mod && (isArrowUp(e) || isArrowDown(e))) {
    zoomChart(chart, isArrowUp(e) ? 'in' : 'out', barCount, barCount - 1)
    handled = true
  } else if (mod && isArrowLeft(e)) {
    panChart(chart, -PAN_BARS_FAST, barCount)
    handled = true
  } else if (mod && isArrowRight(e)) {
    panChart(chart, PAN_BARS_FAST, barCount)
    handled = true
  } else if (isArrowLeft(e)) {
    panChart(chart, -PAN_BARS, barCount)
    handled = true
  } else if (isArrowRight(e)) {
    panChart(chart, PAN_BARS, barCount)
    handled = true
  } else if (e.key === 'PageUp') {
    panChartPage(chart, -1, barCount)
    handled = true
  } else if (e.key === 'PageDown') {
    panChartPage(chart, 1, barCount)
    handled = true
  } else if (e.key === 'Home') {
    scrollToStart(chart, barCount)
    handled = true
  } else if (e.key === 'End') {
    scrollToEnd(chart, barCount)
    handled = true
  } else if (e.altKey && (e.key === 'r' || e.key === 'R' || e.key === 'к' || e.key === 'К')) {
    resetChartView(chart, priceMargins)
    handled = true
  } else if ((e.key === '+' || e.key === '=') && mod) {
    zoomChart(chart, 'in', barCount, barCount - 1)
    handled = true
  } else if (e.key === '-' && mod) {
    zoomChart(chart, 'out', barCount, barCount - 1)
    handled = true
  }

  const isNavKey =
    isArrowLeft(e) ||
    isArrowRight(e) ||
    isArrowUp(e) ||
    isArrowDown(e) ||
    e.key === 'PageUp' ||
    e.key === 'PageDown' ||
    e.key === 'Home' ||
    e.key === 'End' ||
    ((e.key === '+' || e.key === '=' || e.key === '-') && mod) ||
    (e.altKey && (e.key === 'r' || e.key === 'R' || e.key === 'к' || e.key === 'К'))

  if (handled || isNavKey) {
    e.preventDefault()
    e.stopPropagation()
    if (handled) binding.getOptions().onUserInteract?.()
  }
}

function onGlobalKeyDown(e: KeyboardEvent): void {
  const root = activeKeyboardRoot
  if (!root) return
  const binding = bindings.get(root)
  if (!binding) return
  handleChartKeyDown(e, root, binding)
}

function onGlobalPointerDown(e: PointerEvent): void {
  const root = findBindingRoot(e.target)
  if (root) {
    activeKeyboardRoot = root
    if (document.activeElement !== root && !root.contains(document.activeElement)) {
      root.focus({ preventScroll: true })
    }
    return
  }
  if (activeKeyboardRoot) activeKeyboardRoot = null
}

function onGlobalFocusIn(e: FocusEvent): void {
  if (!isEditableTarget(e.target)) return
  const root = findBindingRoot(e.target)
  if (!root) activeKeyboardRoot = null
}

function installGlobalListeners(): void {
  if (globalListenersInstalled) return
  const capture = { capture: true } as const
  document.addEventListener('keydown', onGlobalKeyDown, capture)
  document.addEventListener('pointerdown', onGlobalPointerDown, capture)
  document.addEventListener('focusin', onGlobalFocusIn, capture)
  globalListenersInstalled = true
}

function uninstallGlobalListeners(): void {
  if (!globalListenersInstalled || bindings.size > 0) return
  const capture = { capture: true } as const
  document.removeEventListener('keydown', onGlobalKeyDown, capture)
  document.removeEventListener('pointerdown', onGlobalPointerDown, capture)
  document.removeEventListener('focusin', onGlobalFocusIn, capture)
  globalListenersInstalled = false
  activeKeyboardRoot = null
}

/**
 * Клавиатура, колёсико и тач как в TradingView.
 * Колёсико/тачпад: горизонтальный или вертикальный скролл — pan по времени;
 * Ctrl+колёсико (pinch) — zoom у курсора.
 */
export function attachChartKeyboard(
  root: HTMLElement,
  getChart: () => IChartApi | null,
  getOptions: () => ChartKeyboardOptions,
): () => void {
  root.tabIndex = 0
  root.setAttribute('role', 'application')
  root.setAttribute(
    'aria-keyshortcuts',
    'ArrowLeft ArrowRight PageUp PageDown Control+ArrowLeft Control+ArrowRight Control+ArrowUp Control+ArrowDown Control+Plus Control+Minus Alt+R Home End',
  )
  root.dataset.chartKeyboard = 'true'

  const priceMargins = { ...DEFAULT_PRICE_MARGINS }
  const binding: ChartBinding = { getChart, getOptions, priceMargins }
  bindings.set(root, binding)
  installGlobalListeners()

  let touchMode: 'none' | 'pan' | 'pinch' = 'none'
  let touchPanLastX = 0
  let touchPanRemainder = 0
  let pinchStartSpan = 0

  const activate = () => {
    activeKeyboardRoot = root
    if (document.activeElement !== root && !root.contains(document.activeElement)) {
      root.focus({ preventScroll: true })
    }
  }

  const onPointerDown = (e: PointerEvent) => {
    if (e.button === 0) activate()
  }

  const onFocusIn = () => {
    activeKeyboardRoot = root
  }

  const onRootKeyDown = (e: KeyboardEvent) => {
    handleChartKeyDown(e, root, binding)
  }

  const markInteract = () => {
    getOptions().onUserInteract?.()
  }

  const focusRoot = () => {
    activate()
  }

  const handleWheelPinch = (e: WheelEvent) => {
    if (!isPinchZoomWheel(e) || !isEventInsideRoot(root, e)) return

    const chart = getChart()
    if (!chart) return
    const barCount = getOptions().getBarCount()
    if (barCount < 1) return

    e.preventDefault()
    e.stopPropagation()
    focusRoot()
    const anchor = logicalAnchorFromX(chart, e.clientX)
    if (zoomChartByWheelDelta(chart, normalizeWheelDelta(e), barCount, anchor)) {
      markInteract()
    }
  }

  const handleWheelPan = (e: WheelEvent) => {
    if (isPinchZoomWheel(e) || !isEventInsideRoot(root, e)) return

    const chart = getChart()
    if (!chart) return
    const barCount = getOptions().getBarCount()
    if (barCount < 1) return

    const { deltaX, deltaY } = e
    if (deltaX === 0 && deltaY === 0) return

    e.preventDefault()
    e.stopPropagation()
    focusRoot()

    if (isOverPriceScale(chart, e.clientX)) {
      zoomPriceScaleByWheel(chart, normalizeWheelDelta(e), priceMargins)
      markInteract()
      return
    }

    const panDelta = Math.abs(deltaX) >= Math.abs(deltaY) ? -deltaX : -deltaY
    const bars = wheelDeltaToPanBars(panDelta)
    if (bars !== 0) {
      panChart(chart, bars, barCount)
      markInteract()
    }
  }

  const resetTouch = () => {
    touchMode = 'none'
    touchPanRemainder = 0
    pinchStartSpan = 0
  }

  const onTouchStart = (e: TouchEvent) => {
    const chart = getChart()
    if (!chart) return
    const barCount = getOptions().getBarCount()
    if (barCount < 1) return

    if (e.touches.length >= 2) {
      touchMode = 'pinch'
      pinchStartSpan = touchSpan(e.touches)
      touchPanRemainder = 0
      return
    }

    if (e.touches.length === 1) {
      touchMode = 'pan'
      touchPanLastX = e.touches[0].clientX
      touchPanRemainder = 0
    }
  }

  const onTouchMove = (e: TouchEvent) => {
    const chart = getChart()
    if (!chart) return
    const barCount = getOptions().getBarCount()
    if (barCount < 1) return

    if (touchMode === 'pinch' && e.touches.length >= 2 && pinchStartSpan > 0) {
      e.preventDefault()
      const span = touchSpan(e.touches)
      const ratio = span / pinchStartSpan
      pinchStartSpan = span
      const anchor = logicalAnchorFromX(chart, touchCenterX(e.touches))
      if (zoomChartByRatio(chart, ratio, barCount, anchor)) {
        markInteract()
      }
      return
    }

    if (touchMode === 'pan' && e.touches.length === 1) {
      e.preventDefault()
      const x = e.touches[0].clientX
      const dx = x - touchPanLastX
      touchPanLastX = x
      touchPanRemainder += dx
      const bars = Math.trunc(touchPanRemainder / TOUCH_PAN_PX_PER_BAR)
      if (bars !== 0) {
        touchPanRemainder -= bars * TOUCH_PAN_PX_PER_BAR
        panChart(chart, -bars, barCount)
        markInteract()
      }
    }
  }

  const onTouchEnd = () => {
    resetTouch()
  }

  const capture = { capture: true, passive: false } as const

  root.addEventListener('pointerdown', onPointerDown, capture)
  root.addEventListener('focusin', onFocusIn, capture)
  root.addEventListener('keydown', onRootKeyDown, capture)
  root.addEventListener('wheel', handleWheelPan, capture)
  document.addEventListener('wheel', handleWheelPinch, capture)
  root.addEventListener('touchstart', onTouchStart, capture)
  root.addEventListener('touchmove', onTouchMove, capture)
  root.addEventListener('touchend', onTouchEnd, capture)
  root.addEventListener('touchcancel', onTouchEnd, capture)

  return () => {
    bindings.delete(root)
    uninstallGlobalListeners()
    root.removeEventListener('pointerdown', onPointerDown, capture)
    root.removeEventListener('focusin', onFocusIn, capture)
    root.removeEventListener('keydown', onRootKeyDown, capture)
    root.removeEventListener('wheel', handleWheelPan, capture)
    document.removeEventListener('wheel', handleWheelPinch, capture)
    root.removeEventListener('touchstart', onTouchStart, capture)
    root.removeEventListener('touchmove', onTouchMove, capture)
    root.removeEventListener('touchend', onTouchEnd, capture)
    root.removeEventListener('touchcancel', onTouchEnd, capture)
  }
}
