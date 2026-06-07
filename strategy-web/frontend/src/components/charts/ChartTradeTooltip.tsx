import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { createPortal } from 'react-dom'

import type { ChartTradeTooltipView } from '@/lib/chartTradeTooltip'
import { markerPersistKey } from '@/lib/chartTradeTooltip'
import { detectMobileWebMode } from '@/lib/mobileWebMode'
import { spreadDirectionLabel } from '@/lib/spreadLegs'
import { TradeLegsBlock } from '@/components/trades/TradeLegsBlock'

function tooltipPortalTarget(): HTMLElement {
  return document.fullscreenElement instanceof HTMLElement ? document.fullscreenElement : document.body
}

type Props = {
  view: ChartTradeTooltipView | null
}

function parseTradeTime(s: string): number {
  const d = new Date(s.replace(' ', 'T'))
  const t = d.getTime()
  return Number.isNaN(t) ? NaN : t
}

function fmtTime(s: string): string {
  const t = parseTradeTime(s)
  if (!Number.isFinite(t)) return s
  return new Date(t).toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function fmtTradeDuration(entry: string, exit: string): string {
  const a = parseTradeTime(entry)
  const b = parseTradeTime(exit)
  if (!Number.isFinite(a) || !Number.isFinite(b) || b <= a) return '—'
  const hours = (b - a) / 3_600_000
  if (hours < 1) return `${Math.max(1, Math.round(hours * 60))}м`
  if (hours < 24) {
    const rounded = Math.round(hours * 10) / 10
    return Number.isInteger(rounded) ? `${rounded}ч` : `${rounded.toFixed(1)}ч`
  }
  const days = Math.floor(hours / 24)
  const restH = Math.round(hours - days * 24)
  if (restH === 0) return `${days}с`
  return `${days}с ${restH}ч`
}

function fmtRub(v: number): string {
  return `${Math.round(v).toLocaleString('ru-RU')}₽`
}

function fmtRubCost(v: number): string {
  const abs = Math.round(Math.abs(v))
  if (abs === 0) return '0₽'
  return `−${abs.toLocaleString('ru-RU')}₽`
}

function fmtFloat(v: number, digits = 3): string {
  return v.toFixed(digits)
}

function MetricInline({
  label,
  value,
  tone,
  large = false,
}: {
  label: string
  value: string
  tone?: 'good' | 'bad' | 'neutral'
  large?: boolean
}) {
  const toneClass =
    tone === 'good'
      ? 'text-emerald-300/95'
      : tone === 'bad'
        ? 'text-rose-300/95'
        : 'text-ink-1'
  return (
    <span className={`inline-flex shrink-0 items-baseline gap-1 whitespace-nowrap ${large ? 'text-[12px]' : 'text-[9px]'}`}>
      <span className="text-ink-3">{label}</span>
      <span className={`font-semibold tabular-nums ${toneClass}`}>{value}</span>
    </span>
  )
}

const TOOLTIP_WIDTH = 280
const TOOLTIP_WIDTH_FS = Math.round(TOOLTIP_WIDTH * 1.3)
/** Ширина правой ценовой шкалы — карточка не должна на неё заезжать. */
const PRICE_SCALE_RESERVE_PX = 96
/** Зазор между якорем и ближайшим краем карточки (десктоп). */
const MARKER_CLEARANCE_PX = 32
const MARKER_CLEARANCE_MOBILE_PX = 12
const MARKER_CLEARANCE_LANDSCAPE_PX = 8
const TOOLTIP_WIDTH_MOBILE_LANDSCAPE = 228
const HOST_PAD_PX = 8

type TooltipAnchor = 'above' | 'below' | 'left' | 'right'

function clamp(n: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, n))
}

type LayoutOpts = {
  clearancePx: number
  preferAboveBelow: boolean
  /** Альбомная ориентация: карточка над/под линией, ближе к центру сделки. */
  landscapeMobile: boolean
}

function layoutTooltipPosition(
  relLeft: number,
  relTop: number,
  cardW: number,
  cardH: number,
  hostW: number,
  hostH: number,
  reserveRightPx: number,
  opts: LayoutOpts,
): { cardLeft: number; cardTop: number; anchor: TooltipAnchor } {
  const { clearancePx, preferAboveBelow, landscapeMobile } = opts
  const maxLeft = Math.max(HOST_PAD_PX, hostW - cardW - reserveRightPx)
  const maxTop = Math.max(HOST_PAD_PX, hostH - cardH - HOST_PAD_PX)

  type Candidate = { cardLeft: number; cardTop: number; anchor: TooltipAnchor; score: number }

  const candidates: Candidate[] = []

  const centerLeft = () => clamp(relLeft - cardW * 0.5, HOST_PAD_PX, maxLeft)

  // Сверху: якорь top = низ карточки (translateY -100%), низ не ближе relTop - clearance.
  {
    const cardTop = relTop - clearancePx
    const cardLeft = centerLeft()
    const overflowTop = cardTop - cardH < HOST_PAD_PX ? HOST_PAD_PX - (cardTop - cardH) : 0
    const aboveBias = landscapeMobile ? (relTop > hostH * 0.42 ? -4 : 0) : 0
    candidates.push({ cardLeft, cardTop, anchor: 'above', score: 0 + aboveBias + overflowTop * 4 })
  }

  // Снизу.
  {
    const cardTop = relTop + clearancePx
    const cardLeft = centerLeft()
    const overflowBottom = cardTop + cardH > hostH - HOST_PAD_PX ? cardTop + cardH - (hostH - HOST_PAD_PX) : 0
    const belowPenalty = landscapeMobile ? 3 : 0
    candidates.push({ cardLeft, cardTop, anchor: 'below', score: 2 + belowPenalty + overflowBottom * 4 })
  }

  if (!preferAboveBelow) {
    // Слева от маркера.
    {
      const cardLeft = relLeft - clearancePx - cardW
      const cardTop = clamp(relTop - cardH * 0.45, HOST_PAD_PX, maxTop)
      const overflowLeft = cardLeft < HOST_PAD_PX ? HOST_PAD_PX - cardLeft : 0
      candidates.push({ cardLeft, cardTop, anchor: 'left', score: 4 + overflowLeft * 4 })
    }

    // Справа (не заезжая на шкалу).
    {
      const cardLeft = relLeft + clearancePx
      const cardTop = clamp(relTop - cardH * 0.45, HOST_PAD_PX, maxTop)
      const overflowRight = cardLeft + cardW > hostW - reserveRightPx - HOST_PAD_PX
        ? cardLeft + cardW - (hostW - reserveRightPx - HOST_PAD_PX)
        : 0
      candidates.push({
        cardLeft: clamp(cardLeft, HOST_PAD_PX, maxLeft),
        cardTop,
        anchor: 'right',
        score: 5 + overflowRight * 4,
      })
    }
  }

  candidates.sort((a, b) => a.score - b.score)
  const best = candidates[0]!
  return {
    cardLeft: clamp(best.cardLeft, HOST_PAD_PX, maxLeft),
    cardTop: clamp(best.cardTop, HOST_PAD_PX, maxTop),
    anchor: best.anchor,
  }
}

export function ChartTradeTooltip({ view }: Props) {
  const [portalEl, setPortalEl] = useState(tooltipPortalTarget)
  const perMarkerOffsetRef = useRef(new Map<string, { dx: number; dy: number }>())
  const dragRef = useRef<{
    key: string
    startX: number
    startY: number
    baseDx: number
    baseDy: number
    pointerId: number
  } | null>(null)
  const [dragOffset, setDragOffset] = useState<{ dx: number; dy: number }>({ dx: 0, dy: 0 })

  useEffect(() => {
    const sync = () => setPortalEl(tooltipPortalTarget())
    document.addEventListener('fullscreenchange', sync)
    return () => document.removeEventListener('fullscreenchange', sync)
  }, [])

  const markerKey = view ? markerPersistKey(view.marker) : ''

  useEffect(() => {
    if (!view) return
    const saved = perMarkerOffsetRef.current.get(markerKey) ?? { dx: 0, dy: 0 }
    setDragOffset(saved)
  }, [markerKey, view])

  if (!view) return null

  const { trade, marker, left, top, segmentCenter, accumulatedPnlRub, drawdownRub } = view
  const eventLabel = marker.event === 'вход' ? 'вх' : marker.event === 'выход' ? 'вых' : marker.event
  const pnlPos = trade.pnl_rub >= 0
  const accPos = accumulatedPnlRub >= 0
  const ddNeg = drawdownRub < 0
  const comm = trade.commission_rub ?? 0
  const ovn = trade.overnight_rub ?? 0
  const accLabel = 'Накоп.'

  const inFullscreen = portalEl !== document.body
  const hostRect = portalEl.getBoundingClientRect()
  const toRel = (x: number, y: number) => ({
    left: inFullscreen ? x - hostRect.left : x,
    top: inFullscreen ? y - hostRect.top : y,
  })
  const markerRel = toRel(left, top)
  const hostW = inFullscreen ? hostRect.width : window.innerWidth
  const hostH = inFullscreen ? hostRect.height : window.innerHeight
  const mobileCompact = detectMobileWebMode() || hostW < 520
  const landscapeMobile = mobileCompact && hostW > hostH * 1.05
  const anchorOnSegment = mobileCompact && !!segmentCenter
  const segmentRel = segmentCenter ? toRel(segmentCenter.left, segmentCenter.top) : markerRel
  const relLeft = anchorOnSegment ? segmentRel.left : markerRel.left
  const relTop = anchorOnSegment ? segmentRel.top : markerRel.top
  const interactive = mobileCompact
  const useLargeFsCard = inFullscreen && !mobileCompact
  const reserveRight = mobileCompact ? 48 : PRICE_SCALE_RESERVE_PX
  const cardW = Math.min(
    landscapeMobile
      ? TOOLTIP_WIDTH_MOBILE_LANDSCAPE
      : useLargeFsCard
        ? TOOLTIP_WIDTH_FS
        : TOOLTIP_WIDTH,
    hostW - reserveRight - (landscapeMobile ? 8 : 16),
  )
  const cardH = useLargeFsCard ? 190 : landscapeMobile ? 108 : 118
  const clearancePx = landscapeMobile
    ? MARKER_CLEARANCE_LANDSCAPE_PX
    : mobileCompact
      ? MARKER_CLEARANCE_MOBILE_PX
      : MARKER_CLEARANCE_PX
  const { cardLeft, cardTop, anchor } = layoutTooltipPosition(
    relLeft,
    relTop,
    cardW,
    cardH,
    hostW,
    hostH,
    reserveRight,
    {
      clearancePx,
      preferAboveBelow: anchorOnSegment,
      landscapeMobile,
    },
  )

  const transform =
    anchor === 'above'
      ? 'translateY(-100%)'
      : anchor === 'left'
        ? 'translateX(-100%)'
        : 'none'

  const onPointerDown = (e: ReactPointerEvent) => {
    if (!interactive) return
    // Ловим только первичный палец/мышь.
    if (e.pointerType === 'mouse' && e.button !== 0) return
    e.preventDefault()
    e.stopPropagation()
    const base = perMarkerOffsetRef.current.get(markerKey) ?? { dx: 0, dy: 0 }
    dragRef.current = {
      key: markerKey,
      startX: e.clientX,
      startY: e.clientY,
      baseDx: base.dx,
      baseDy: base.dy,
      pointerId: e.pointerId,
    }
    ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  }

  const onPointerMove = (e: ReactPointerEvent) => {
    const d = dragRef.current
    if (!d || d.pointerId !== e.pointerId) return
    e.preventDefault()
    e.stopPropagation()
    const dx = d.baseDx + (e.clientX - d.startX)
    const dy = d.baseDy + (e.clientY - d.startY)
    setDragOffset({ dx, dy })
  }

  const onPointerUp = (e: ReactPointerEvent) => {
    const d = dragRef.current
    if (!d || d.pointerId !== e.pointerId) return
    e.preventDefault()
    e.stopPropagation()
    dragRef.current = null
    perMarkerOffsetRef.current.set(markerKey, dragOffset)
  }

  const card = (
    <div
      className={`chart-trade-tooltip ${interactive ? 'pointer-events-auto' : 'pointer-events-none'} z-[9999] rounded-md border border-surface-border-soft bg-[rgba(10,18,32,0.98)] text-ink-2 shadow-[0_6px_18px_rgba(0,0,0,0.5)] backdrop-blur-sm ${
        useLargeFsCard
          ? 'chart-trade-tooltip--fullscreen px-2.5 py-2 text-[12px]'
          : landscapeMobile
            ? 'px-1.5 py-1 text-[9px] leading-none'
            : 'w-[min(280px,calc(100vw-20px))] px-1.5 py-1 text-[9px] leading-none'
      }`}
      style={{
        position: inFullscreen ? 'absolute' : 'fixed',
        width: inFullscreen || landscapeMobile ? cardW : undefined,
        maxWidth: landscapeMobile && !inFullscreen ? cardW : undefined,
        left: cardLeft + dragOffset.dx,
        top: cardTop + dragOffset.dy,
        transform,
      }}
      role="tooltip"
    >
      <div
        className={`mb-1 flex items-baseline gap-1.5 border-b border-surface-border-soft/50 ${useLargeFsCard ? 'pb-1' : 'pb-0.5'} ${interactive ? 'cursor-grab touch-none select-none' : ''}`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      >
        <span
          className={`font-semibold tabular-nums text-ink-1 ${useLargeFsCard ? 'text-[13px]' : 'text-[10px]'}`}
        >
          #{trade.no}
        </span>
        <span
          className={
            (useLargeFsCard ? 'text-[13px] ' : '') +
            (trade.direction === 'LONG' ? 'font-medium text-emerald-300/95' : 'font-medium text-rose-300/95')
          }
        >
          {spreadDirectionLabel(trade.direction)}
        </span>
        <span className={useLargeFsCard ? 'text-[12px] text-ink-3' : 'text-ink-3'}>· {eventLabel}</span>
      </div>

      <TradeLegsBlock
        trade={trade}
        variant="tooltip"
        event={marker.event}
        className={useLargeFsCard ? 'mb-1 text-[12px]' : 'mb-0.5'}
        tooltipLarge={useLargeFsCard}
      />

      <div
        className={`mb-1 space-y-1 tabular-nums leading-tight ${useLargeFsCard ? 'text-[12px]' : 'mb-0.5 space-y-0.5 text-[9px]'}`}
      >
        <div className="text-ink-1">
          <span className="text-ink-3">T </span>
          {fmtTime(trade.entry_time)}→{fmtTime(trade.exit_time)}
          <span className="text-ink-3"> · </span>
          {fmtTradeDuration(trade.entry_time, trade.exit_time)}
        </div>
        <div className="text-ink-1">
          <span className="text-ink-3">Z </span>
          {fmtFloat(trade.entry_z, 2)}/{fmtFloat(trade.exit_z, 2)}
          <span className="text-ink-3"> · Spr </span>
          {fmtFloat(trade.entry_spread, 2)}/{fmtFloat(trade.exit_spread, 2)}%
        </div>
      </div>

      <div className={`space-y-0.5 border-t border-surface-border-soft/50 pt-1 ${useLargeFsCard ? 'mt-1' : ''}`}>
        <div className={`flex flex-wrap items-baseline gap-x-3 gap-y-0.5 ${useLargeFsCard ? 'text-[12px]' : ''}`}>
          <MetricInline label="PnL" value={fmtRub(trade.pnl_rub)} tone={pnlPos ? 'good' : 'bad'} large={useLargeFsCard} />
          <MetricInline
            label={accLabel}
            value={fmtRub(accumulatedPnlRub)}
            tone={accPos ? 'good' : 'bad'}
            large={useLargeFsCard}
          />
        </div>
        <div className={`flex flex-wrap items-baseline gap-x-3 gap-y-0.5 ${useLargeFsCard ? 'text-[12px]' : ''}`}>
          <MetricInline
            label="DD"
            value={ddNeg ? fmtRubCost(drawdownRub) : '0₽'}
            tone={ddNeg ? 'bad' : 'neutral'}
            large={useLargeFsCard}
          />
          <MetricInline label="Ком." value={fmtRubCost(comm)} tone={comm > 0 ? 'bad' : 'neutral'} large={useLargeFsCard} />
          <MetricInline label="O/n" value={fmtRubCost(ovn)} tone={ovn > 0 ? 'bad' : 'neutral'} large={useLargeFsCard} />
        </div>
      </div>
    </div>
  )

  return createPortal(card, portalEl)
}
