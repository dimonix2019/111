import type { IChartApi } from 'lightweight-charts'

import { applyChartRightMargin } from '@/lib/format'

export type ChartKeyboardOptions = {
  /** Число баров в серии (logical index upper bound). */
  barCount: number
  /** Пользователь сдвинул/zoom — не сбрасывать live-позицию. */
  onUserInteract?: () => void
}

const MIN_VISIBLE_BARS = 8
const PAN_BARS = 1
const PAN_BARS_FAST = 10
const ZOOM_IN_FACTOR = 1.12
const ZOOM_OUT_FACTOR = 1.12
const DEFAULT_PRICE_MARGINS = { top: 0.1, bottom: 0.1 }

function isEditableTarget(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

function clampRange(
  from: number,
  to: number,
  barCount: number,
): { from: number; to: number } | null {
  const span = to - from
  if (!Number.isFinite(span) || span < MIN_VISIBLE_BARS) return null
  let f = from
  let t = to
  if (t - f < MIN_VISIBLE_BARS) t = f + MIN_VISIBLE_BARS
  const maxTo = barCount + 30
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

function applyLogicalRange(chart: IChartApi, from: number, to: number, barCount: number): boolean {
  const next = clampRange(from, to, barCount)
  if (!next) return false
  chart.timeScale().setVisibleLogicalRange(next)
  return true
}

function panChart(chart: IChartApi, bars: number, barCount: number): boolean {
  const range = chart.timeScale().getVisibleLogicalRange()
  if (!range) return false
  return applyLogicalRange(chart, range.from + bars, range.to + bars, barCount)
}

/** Zoom вокруг logical-индекса (0…barCount). */
function zoomChart(
  chart: IChartApi,
  direction: 'in' | 'out',
  barCount: number,
  anchorLogical: number,
): boolean {
  const range = chart.timeScale().getVisibleLogicalRange()
  if (!range) return false
  const span = range.to - range.from
  const factor = direction === 'in' ? ZOOM_IN_FACTOR : 1 / ZOOM_OUT_FACTOR
  let newSpan = direction === 'in' ? span / factor : span * factor
  newSpan = Math.max(MIN_VISIBLE_BARS, Math.min(barCount * 0.98, newSpan))

  const anchor = Math.max(0, Math.min(barCount, anchorLogical))
  const anchorRatio = span > 0 ? (anchor - range.from) / span : 0.5
  let from = anchor - newSpan * anchorRatio
  let to = from + newSpan
  return applyLogicalRange(chart, from, to, barCount)
}

function resetChartView(chart: IChartApi, priceMargins: { top: number; bottom: number }): void {
  applyChartRightMargin(chart.timeScale())
  priceMargins.top = DEFAULT_PRICE_MARGINS.top
  priceMargins.bottom = DEFAULT_PRICE_MARGINS.bottom
  chart.priceScale('right').applyOptions({ scaleMargins: { ...priceMargins } })
}

function scrollToStart(chart: IChartApi, barCount: number): boolean {
  const range = chart.timeScale().getVisibleLogicalRange()
  if (!range) return false
  const span = range.to - range.from
  return applyLogicalRange(chart, 0, Math.min(span, barCount), barCount)
}

function scrollToEnd(chart: IChartApi): void {
  chart.timeScale().scrollToRealTime()
}

function logicalAnchorFromX(chart: IChartApi, clientX: number, root: HTMLElement): number {
  const range = chart.timeScale().getVisibleLogicalRange()
  if (!range) return 0
  const rect = root.getBoundingClientRect()
  const w = rect.width || 1
  const ratio = Math.max(0, Math.min(1, (clientX - rect.left) / w))
  return range.from + (range.to - range.from) * ratio
}

/**
 * Клавиатура и колёсико как в TradingView:
 * ←/→ — 1 бар; Ctrl+←/→ — дальше; Ctrl+↑/↓ — zoom; Alt+R — сброс;
 * Shift+колёсико — pan; Ctrl+колёсико — zoom у курсора.
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
    'ArrowLeft ArrowRight Control+ArrowLeft Control+ArrowRight Control+ArrowUp Control+ArrowDown Alt+R Home End',
  )
  root.dataset.chartKeyboard = 'true'

  const priceMargins = { ...DEFAULT_PRICE_MARGINS }

  const focusRoot = () => {
    if (document.activeElement !== root) root.focus({ preventScroll: true })
  }

  const markInteract = () => {
    getOptions().onUserInteract?.()
  }

  const onMouseDown = (e: MouseEvent) => {
    if (e.button === 0) focusRoot()
  }

  const onKeyDown = (e: KeyboardEvent) => {
    if (document.activeElement !== root) return
    if (isEditableTarget(e.target)) return

    const chart = getChart()
    if (!chart) return
    const { barCount } = getOptions()
    if (barCount < 1) return

    const mod = e.ctrlKey || e.metaKey
    let handled = false

    if (mod && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
      zoomChart(chart, e.key === 'ArrowUp' ? 'in' : 'out', barCount, barCount - 1)
      handled = true
    } else if (mod && e.key === 'ArrowLeft') {
      panChart(chart, -PAN_BARS_FAST, barCount)
      handled = true
    } else if (mod && e.key === 'ArrowRight') {
      panChart(chart, PAN_BARS_FAST, barCount)
      handled = true
    } else if (e.key === 'ArrowLeft') {
      panChart(chart, -PAN_BARS, barCount)
      handled = true
    } else if (e.key === 'ArrowRight') {
      panChart(chart, PAN_BARS, barCount)
      handled = true
    } else if (e.key === 'Home') {
      scrollToStart(chart, barCount)
      handled = true
    } else if (e.key === 'End') {
      scrollToEnd(chart)
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

    if (handled) {
      e.preventDefault()
      e.stopPropagation()
      markInteract()
    }
  }

  const onWheel = (e: WheelEvent) => {
    const chart = getChart()
    if (!chart) return
    const { barCount } = getOptions()
    if (barCount < 1) return

    const mod = e.ctrlKey || e.metaKey
    if (!mod && !e.shiftKey) return

    e.preventDefault()
    focusRoot()

    if (mod) {
      const anchor = logicalAnchorFromX(chart, e.clientX, root)
      zoomChart(chart, e.deltaY < 0 ? 'in' : 'out', barCount, anchor)
    } else if (e.shiftKey) {
      const step = e.deltaY !== 0 ? Math.sign(e.deltaY) * PAN_BARS * 3 : Math.sign(e.deltaX) * PAN_BARS * 3
      panChart(chart, step, barCount)
    }
    markInteract()
  }

  root.addEventListener('mousedown', onMouseDown)
  root.addEventListener('keydown', onKeyDown)
  root.addEventListener('wheel', onWheel, { passive: false })

  return () => {
    root.removeEventListener('mousedown', onMouseDown)
    root.removeEventListener('keydown', onKeyDown)
    root.removeEventListener('wheel', onWheel)
  }
}
