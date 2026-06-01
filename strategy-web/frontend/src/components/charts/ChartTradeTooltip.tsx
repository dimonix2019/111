import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'

import type { ChartTradeTooltipView } from '@/lib/chartTradeTooltip'
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

function Metric({
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
    <div className={`flex min-w-0 items-baseline justify-between gap-3 ${large ? 'text-[12px]' : 'text-[9px]'}`}>
      <span className={large ? 'text-[11px] leading-tight text-ink-3' : 'text-[9px] leading-tight text-ink-3'}>
        {label}
      </span>
      <span
        className={`truncate text-right font-semibold tabular-nums leading-tight ${large ? 'text-[12px]' : 'text-[9px]'} ${toneClass}`}
      >
        {value}
      </span>
    </div>
  )
}

const TOOLTIP_WIDTH = 280
const TOOLTIP_WIDTH_FS = Math.round(TOOLTIP_WIDTH * 1.3)
const TOOLTIP_FS_LIFT_PX = 48
/** Ширина правой ценовой шкалы — карточка не должна на неё заезжать. */
const PRICE_SCALE_RESERVE_PX = 96

function layoutTooltipPosition(
  relLeft: number,
  relTop: number,
  cardW: number,
  cardH: number,
  hostW: number,
  hostH: number,
  inFullscreen: boolean,
): { cardLeft: number; cardTop: number; placeAbove: boolean } {
  const placeAbove = inFullscreen || relTop > cardH + 24
  const lift = inFullscreen ? TOOLTIP_FS_LIFT_PX : 0
  const maxLeft = Math.max(8, hostW - cardW - PRICE_SCALE_RESERVE_PX)

  // Смещаем влево от маркера, чтобы 4 колонки метрик не обрезались справа.
  let cardLeft = relLeft - cardW * 0.62
  cardLeft = Math.max(8, Math.min(cardLeft, maxLeft))

  let cardTop = placeAbove ? relTop - 12 - lift : relTop + 16
  if (placeAbove) {
    cardTop = Math.max(cardH + 10, cardTop)
  } else {
    cardTop = Math.min(cardTop, Math.max(cardH + 10, hostH - 12))
  }

  return { cardLeft, cardTop, placeAbove }
}

export function ChartTradeTooltip({ view }: Props) {
  const [portalEl, setPortalEl] = useState(tooltipPortalTarget)

  useEffect(() => {
    const sync = () => setPortalEl(tooltipPortalTarget())
    document.addEventListener('fullscreenchange', sync)
    return () => document.removeEventListener('fullscreenchange', sync)
  }, [])

  if (!view) return null

  const { trade, marker, left, top, accumulatedPnlRub, drawdownRub } = view
  const eventLabel = marker.event === 'вход' ? 'вх' : marker.event === 'выход' ? 'вых' : marker.event
  const pnlPos = trade.pnl_rub >= 0
  const accPos = accumulatedPnlRub >= 0
  const ddNeg = drawdownRub < 0
  const hasFees = (trade.commission_rub ?? 0) > 0 || (trade.overnight_rub ?? 0) > 0
  const accLabel = marker.event === 'вход' ? 'Накоп.' : 'Накоп.'

  const inFullscreen = portalEl !== document.body
  const hostRect = portalEl.getBoundingClientRect()
  const relLeft = inFullscreen ? left - hostRect.left : left
  const relTop = inFullscreen ? top - hostRect.top : top
  const hostW = inFullscreen ? hostRect.width : window.innerWidth
  const hostH = inFullscreen ? hostRect.height : window.innerHeight
  const cardW = Math.min(inFullscreen ? TOOLTIP_WIDTH_FS : TOOLTIP_WIDTH, hostW - PRICE_SCALE_RESERVE_PX - 16)
  const cardH = inFullscreen ? 190 : 136
  const { cardLeft, cardTop, placeAbove } = layoutTooltipPosition(
    relLeft,
    relTop,
    cardW,
    cardH,
    hostW,
    hostH,
    inFullscreen,
  )

  const card = (
    <div
      className={`chart-trade-tooltip pointer-events-none z-[9999] rounded-md border border-surface-border-soft bg-[rgba(10,18,32,0.98)] text-ink-2 shadow-[0_6px_18px_rgba(0,0,0,0.5)] backdrop-blur-sm ${
        inFullscreen ? 'chart-trade-tooltip--fullscreen px-2.5 py-2 text-[12px]' : 'w-[min(280px,calc(100vw-20px))] px-1.5 py-1 text-[9px] leading-none'
      }`}
      style={{
        position: inFullscreen ? 'absolute' : 'fixed',
        width: inFullscreen ? cardW : undefined,
        left: cardLeft,
        top: cardTop,
        transform: placeAbove ? 'translateY(-100%)' : 'none',
      }}
      role="tooltip"
    >
      <div
        className={`mb-1 flex items-baseline gap-1.5 border-b border-surface-border-soft/50 ${inFullscreen ? 'pb-1' : 'pb-0.5'}`}
      >
        <span
          className={`font-semibold tabular-nums text-ink-1 ${inFullscreen ? 'text-[13px]' : 'text-[10px]'}`}
        >
          #{trade.no}
        </span>
        <span
          className={
            (inFullscreen ? 'text-[13px] ' : '') +
            (trade.direction === 'LONG' ? 'font-medium text-emerald-300/95' : 'font-medium text-rose-300/95')
          }
        >
          {spreadDirectionLabel(trade.direction)}
        </span>
        <span className={inFullscreen ? 'text-[12px] text-ink-3' : 'text-ink-3'}>· {eventLabel}</span>
      </div>

      <TradeLegsBlock
        trade={trade}
        variant="tooltip"
        event={marker.event}
        className={inFullscreen ? 'mb-1 text-[12px]' : 'mb-0.5'}
        tooltipLarge={inFullscreen}
      />

      <div
        className={`mb-1 space-y-1 tabular-nums leading-tight ${inFullscreen ? 'text-[12px]' : 'mb-0.5 space-y-0.5 text-[9px]'}`}
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

      <div className={`space-y-0.5 border-t border-surface-border-soft/50 pt-1 ${inFullscreen ? 'mt-1' : ''}`}>
        <Metric label="PnL" value={fmtRub(trade.pnl_rub)} tone={pnlPos ? 'good' : 'bad'} large={inFullscreen} />
        <Metric label={accLabel} value={fmtRub(accumulatedPnlRub)} tone={accPos ? 'good' : 'bad'} large={inFullscreen} />
        <Metric label="DD" value={ddNeg ? fmtRubCost(drawdownRub) : '0₽'} tone={ddNeg ? 'bad' : 'neutral'} large={inFullscreen} />
        {hasFees ? (
          <Metric
            large={inFullscreen}
            label="Ком."
            value={
              (trade.commission_rub ?? 0) > 0 && (trade.overnight_rub ?? 0) > 0
                ? `${fmtRubCost(trade.commission_rub ?? 0)}`
                : (trade.commission_rub ?? 0) > 0
                  ? fmtRubCost(trade.commission_rub ?? 0)
                  : fmtRubCost(trade.overnight_rub ?? 0)
            }
            tone="bad"
          />
        ) : null}
      </div>
    </div>
  )

  return createPortal(card, portalEl)
}
