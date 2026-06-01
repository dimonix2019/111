import type { Trade } from '@/types'
import {
  allocateLegPnls,
  hasLegPrices,
  legPnlRub,
  legsCompactLabel,
  spreadDirectionLabel,
  sumLegPnlRub,
  tradeGrossPnlRub,
} from '@/lib/spreadLegs'

type Variant = 'compact' | 'detailed' | 'inline' | 'tooltip'

type Props = {
  trade: Trade
  variant?: Variant
  /** вход / выход — для тултипа графика */
  event?: string
  className?: string
  /** Крупнее на ~30% (полноэкранный график). */
  tooltipLarge?: boolean
}

function fmtPrice(v: number | undefined): string {
  if (v == null || !Number.isFinite(v)) return '—'
  return v.toFixed(1)
}

function fmtQty(v: number | undefined): string {
  if (v == null || !Number.isFinite(v)) return '—'
  return v >= 10 ? v.toFixed(0) : v.toFixed(1)
}

function fmtLegPnl(v: number): string {
  const rounded = Math.round(v)
  const sign = rounded >= 0 ? '+' : ''
  return `${sign}${rounded.toLocaleString('ru-RU')} ₽`
}

export function TradeArbitrageBadge({ tradeNo }: { tradeNo: number }) {
  return (
    <span className="inline-flex items-center rounded-md border border-cyan-500/35 bg-cyan-500/10 px-1.5 py-0.5 text-[10px] font-semibold text-cyan-200/90">
      Арбитраж №{tradeNo}
    </span>
  )
}

export function TradeLegsBlock({
  trade,
  variant = 'detailed',
  event,
  className = '',
  tooltipLarge = false,
}: Props) {
  const legs = allocateLegPnls(trade)
  const showPrices = hasLegPrices(trade)
  const isEntry = event === 'вход'
  const isExit = event === 'выход'
  const legsGross = sumLegPnlRub(legs)
  const tradeGross = tradeGrossPnlRub(trade)

  if (variant === 'inline') {
    return (
      <span className={`text-[11px] text-ink-2 ${className}`}>
        <TradeArbitrageBadge tradeNo={trade.no} />
        <span className="ml-1.5 text-ink-3">{legsCompactLabel(trade)}</span>
      </span>
    )
  }

  if (variant === 'compact') {
    return (
      <div className={`space-y-1 ${className}`}>
        <div className="flex flex-wrap items-center gap-1.5">
          <TradeArbitrageBadge tradeNo={trade.no} />
          <span className="text-[10px] text-ink-3">{spreadDirectionLabel(trade.direction)}</span>
        </div>
        <div className="flex flex-wrap gap-1.5">
          {legs.map((leg) => (
            <span
              key={leg.ticker}
              className={`rounded-md border border-surface-border-soft px-1.5 py-0.5 text-[10px] font-medium ${
                leg.side === 'buy' ? 'text-good' : 'text-bad'
              }`}
            >
              {leg.ticker} {leg.sideRu}
            </span>
          ))}
        </div>
      </div>
    )
  }

  if (variant === 'tooltip') {
    const legText = tooltipLarge ? 'text-[12px]' : 'text-[9px]'
    const tickerW = tooltipLarge ? 'min-w-[3.25rem]' : 'min-w-[2.5rem]'
    return (
      <ul className={`space-y-0.5 tabular-nums ${className}`}>
        {legs.map((leg) => (
          <li key={leg.ticker} className={`flex items-baseline gap-1.5 leading-snug ${legText}`}>
            <span className={`${tickerW} font-semibold text-ink-1`}>{leg.ticker}</span>
            <span className={`font-medium ${leg.side === 'buy' ? 'text-good' : 'text-bad'}`}>{leg.sideRu}</span>
            {showPrices ? (
              <span className="text-ink-2">
                {isEntry
                  ? `@ ${fmtPrice(leg.entry_price)}`
                  : isExit
                    ? `@ ${fmtPrice(leg.exit_price)}`
                    : `${fmtPrice(leg.entry_price)}→${fmtPrice(leg.exit_price)}`}
                {leg.qty != null ? <span className="text-ink-3"> · {fmtQty(leg.qty)} шт</span> : null}
              </span>
            ) : null}
          </li>
        ))}
      </ul>
    )
  }

  return (
    <div
      className={`rounded-lg border border-surface-border-soft bg-[rgba(8,16,28,0.45)] p-2 ${className}`}
    >
      <div className="mb-1.5 flex flex-wrap items-center gap-1.5">
        <TradeArbitrageBadge tradeNo={trade.no} />
        <span className="text-[10px] text-ink-3">2 ноги · одновременно</span>
        {event ? <span className="text-[10px] text-ink-3">· {event}</span> : null}
      </div>
      <ul className="space-y-1.5">
        {legs.map((leg, idx) => {
          const legPnl = legPnlRub(leg)
          return (
          <li
            key={leg.ticker}
            className="flex flex-wrap items-baseline justify-between gap-x-2 gap-y-0.5 border-t border-surface-border-soft/60 pt-1.5 first:border-0 first:pt-0"
          >
            <div className="flex items-center gap-1.5">
              <span className="text-[10px] text-ink-3">Ордер {idx + 1}</span>
              <span className="rounded border border-surface-border-soft px-1.5 py-0.5 text-[11px] font-semibold text-ink-1">
                {leg.ticker}
              </span>
              <span className={`text-[11px] font-medium ${leg.side === 'buy' ? 'text-good' : 'text-bad'}`}>
                {leg.sideRu}
              </span>
            </div>
            {showPrices ? (
              <div className="text-right text-[10px] tabular-nums text-ink-2">
                {isEntry ? (
                  <span>вход {fmtPrice(leg.entry_price)}</span>
                ) : isExit ? (
                  <>
                    <span>выход {fmtPrice(leg.exit_price)}</span>
                    {legPnl != null ? (
                      <span
                        className={`ml-1.5 font-semibold ${legPnl >= 0 ? 'text-good' : 'text-bad'}`}
                      >
                        · PnL {fmtLegPnl(legPnl)}
                      </span>
                    ) : null}
                  </>
                ) : (
                  <span>
                    {fmtPrice(leg.entry_price)} → {fmtPrice(leg.exit_price)}
                    {legPnl != null ? (
                      <span
                        className={`ml-1.5 font-semibold ${legPnl >= 0 ? 'text-good' : 'text-bad'}`}
                      >
                        · {fmtLegPnl(legPnl)}
                      </span>
                    ) : null}
                  </span>
                )}
                {leg.qty != null ? <span className="ml-1 text-ink-3">· {fmtQty(leg.qty)} шт</span> : null}
              </div>
            ) : null}
          </li>
          )
        })}
      </ul>
      {isExit && showPrices ? (
        <p className="mt-1.5 border-t border-surface-border-soft/60 pt-1.5 text-[10px] tabular-nums text-ink-2">
          <span className="text-ink-3">Σ ног </span>
          <span className={legsGross >= 0 ? 'font-medium text-good' : 'font-medium text-bad'}>
            {fmtLegPnl(legsGross)}
          </span>
          {trade.commission_rub ? (
            <span className="text-ink-3">
              {' '}
              · комиссия −{Math.round(trade.commission_rub).toLocaleString('ru-RU')} ₽
            </span>
          ) : null}
          {trade.overnight_rub ? (
            <span className="text-ink-3">
              {' '}
              · overnight −{Math.round(trade.overnight_rub).toLocaleString('ru-RU')} ₽
            </span>
          ) : null}
          <span className="text-ink-3"> · итого </span>
          <span className={trade.pnl_rub >= 0 ? 'font-semibold text-good' : 'font-semibold text-bad'}>
            {fmtLegPnl(trade.pnl_rub)}
          </span>
          {Math.abs(legsGross - tradeGross) > 1 ? (
            <span className="block mt-0.5 text-[9px] text-ink-3">
              (валовый по спреду {fmtLegPnl(tradeGross)})
            </span>
          ) : null}
        </p>
      ) : null}
    </div>
  )
}
