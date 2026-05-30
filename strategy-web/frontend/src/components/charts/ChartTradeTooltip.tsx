import { createPortal } from 'react-dom'

import type { ChartTradeTooltipView } from '@/lib/chartTradeTooltip'
import { spreadDirectionLabel } from '@/lib/spreadLegs'
import { TradeLegsBlock } from '@/components/trades/TradeLegsBlock'

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
  if (hours < 1) return `${Math.max(1, Math.round(hours * 60))} мин`
  if (hours < 24) {
    const rounded = Math.round(hours * 10) / 10
    return Number.isInteger(rounded) ? `${rounded} ч` : `${rounded.toFixed(1)} ч`
  }
  const days = Math.floor(hours / 24)
  const restH = Math.round(hours - days * 24)
  if (restH === 0) return `${days} сут`
  return `${days} сут ${restH} ч`
}

function fmtRub(v: number): string {
  return `${Math.round(v).toLocaleString('ru-RU')} ₽`
}

function fmtRubCost(v: number): string {
  const abs = Math.round(Math.abs(v))
  if (abs === 0) return '0 ₽'
  return `−${abs.toLocaleString('ru-RU')} ₽`
}

function fmtFloat(v: number, digits = 3): string {
  return v.toFixed(digits)
}

export function ChartTradeTooltip({ view }: Props) {
  if (!view) return null

  const { trade, marker, left, top, accumulatedPnlRub } = view
  const eventLabel = marker.event === 'вход' ? 'вход' : marker.event === 'выход' ? 'выход' : marker.event
  const pnlPos = trade.pnl_rub >= 0
  const accPos = accumulatedPnlRub >= 0

  const card = (
    <div
      className="pointer-events-none fixed z-[9999] w-[min(360px,calc(100vw-24px))] rounded-lg border border-surface-border-soft bg-[rgba(12,22,38,0.98)] px-1.5 py-1 text-[10px] leading-tight text-ink-2 shadow-[0_8px_24px_rgba(0,0,0,0.55)] backdrop-blur-sm"
      style={{
        left: Math.max(8, left + 12),
        top: Math.max(8, top - 10),
        transform: 'translateY(-100%)',
      }}
      role="tooltip"
    >
      <div className="mb-0.5 flex flex-wrap items-baseline gap-x-1">
        <span
          className={
            trade.direction === 'LONG' ? 'font-medium text-emerald-300/95' : 'font-medium text-rose-300/95'
          }
        >
          {spreadDirectionLabel(trade.direction)}
        </span>
        <span className="text-ink-3">· {eventLabel}</span>
      </div>

      <TradeLegsBlock trade={trade} variant="detailed" event={marker.event} dense className="mb-0.5" />

      <dl className="grid grid-cols-[auto_1fr] gap-x-1.5 gap-y-1 tabular-nums leading-snug">
        <dt className="text-ink-3">Вход / выход</dt>
        <dd className="text-ink-1">
          {fmtTime(trade.entry_time)} → {fmtTime(trade.exit_time)}
        </dd>
        <dt className="text-ink-3">Длительность сделки</dt>
        <dd className="text-ink-1">{fmtTradeDuration(trade.entry_time, trade.exit_time)}</dd>
        <dt className="text-ink-3">Z вх / вых</dt>
        <dd className="text-ink-1">
          {fmtFloat(trade.entry_z, 2)} / {fmtFloat(trade.exit_z, 2)}
        </dd>
        <dt className="text-ink-3">Spread вх / вых</dt>
        <dd className="text-ink-1">
          {fmtFloat(trade.entry_spread, 3)}% / {fmtFloat(trade.exit_spread, 3)}%
        </dd>
        <dt className="text-ink-3">PnL сделки</dt>
        <dd className={pnlPos ? 'font-medium text-emerald-300/95' : 'font-medium text-rose-300/95'}>
          {fmtRub(trade.pnl_rub)}
        </dd>
        <dt className="text-ink-3">
          {marker.event === 'вход' ? 'Накопленный PnL (до входа)' : 'Накопленный PnL'}
        </dt>
        <dd className={accPos ? 'font-medium text-emerald-300/95' : 'font-medium text-rose-300/95'}>
          {fmtRub(accumulatedPnlRub)}
        </dd>
        {trade.commission_rub > 0 || trade.overnight_rub > 0 ? (
          <>
            <dt className="text-ink-3">
              {trade.commission_rub > 0 && trade.overnight_rub > 0
                ? 'Комиссия / overnight'
                : trade.commission_rub > 0
                  ? 'Комиссия'
                  : 'Overnight'}
            </dt>
            <dd className="font-medium text-rose-300/95">
              {trade.commission_rub > 0 ? fmtRubCost(trade.commission_rub) : null}
              {trade.commission_rub > 0 && trade.overnight_rub > 0 ? (
                <span className="text-ink-3"> · </span>
              ) : null}
              {trade.overnight_rub > 0 ? fmtRubCost(trade.overnight_rub) : null}
            </dd>
          </>
        ) : null}
      </dl>
    </div>
  )

  return createPortal(card, document.body)
}
