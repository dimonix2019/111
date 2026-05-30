import type { Trade, TradeOrderLeg } from '@/types'

export type SpreadLegSide = 'buy' | 'sell'

export type SpreadLeg = {
  ticker: 'TATN' | 'TATNP'
  side: SpreadLegSide
  sideRu: string
}

const SIDE_RU: Record<SpreadLegSide, string> = {
  buy: 'покупка',
  sell: 'продажа',
}

/** Ноги спред-пары: LONG = buy TATN + sell TATNP; SHORT = наоборот. */
export function spreadLegs(direction: string): SpreadLeg[] {
  if (direction === 'LONG') {
    return [
      { ticker: 'TATN', side: 'buy', sideRu: 'покупка' },
      { ticker: 'TATNP', side: 'sell', sideRu: 'продажа' },
    ]
  }
  return [
    { ticker: 'TATN', side: 'sell', sideRu: 'продажа' },
    { ticker: 'TATNP', side: 'buy', sideRu: 'покупка' },
  ]
}

/** Ноги сделки: из API (цены/лоты) или по направлению спреда. */
export function resolveTradeLegs(trade: Trade): TradeOrderLeg[] {
  if (trade.legs?.length === 2) {
    return trade.legs.map((leg) => ({
      ...leg,
      sideRu: SIDE_RU[leg.side] ?? leg.side,
    }))
  }
  return spreadLegs(trade.direction).map((leg) => ({
    ticker: leg.ticker,
    side: leg.side,
    sideRu: leg.sideRu,
  }))
}

export function legPnlRub(leg: TradeOrderLeg): number | null {
  if (leg.pnl_rub != null && Number.isFinite(leg.pnl_rub)) return leg.pnl_rub
  const { entry_price: entry, exit_price: exit, qty } = leg
  if (entry == null || exit == null || qty == null || entry <= 0 || qty <= 0) return null
  if (leg.side === 'buy') return qty * (exit - entry)
  return qty * (entry - exit)
}

/** Валовый PnL сделки (до комиссии и overnight) — как в симуляторе по спреду. */
export function tradeGrossPnlRub(trade: Trade): number {
  return trade.pnl_rub + (trade.commission_rub ?? 0) + (trade.overnight_rub ?? 0)
}

export function sumLegPnlRub(legs: TradeOrderLeg[]): number {
  return legs.reduce((s, l) => s + (legPnlRub(l) ?? 0), 0)
}

/** PnL ног пропорционально валовому PnL сделки (Σ ног = валовый). */
export function allocateLegPnls(trade: Trade): TradeOrderLeg[] {
  const legs = resolveTradeLegs(trade)
  if (!hasLegPrices(trade) || legs.length !== 2) return legs

  const gross = tradeGrossPnlRub(trade)
  const legSum = sumLegPnlRub(legs)
  if (Math.abs(legSum - gross) <= 1) return legs

  const raw = legs.map((l) => legPnlRub(l) ?? 0)
  const rawSum = raw[0]! + raw[1]!
  if (Math.abs(rawSum) < 1e-6) {
    const half = Math.round((gross / 2) * 100) / 100
    return [
      { ...legs[0]!, pnl_rub: half },
      { ...legs[1]!, pnl_rub: Math.round((gross - half) * 100) / 100 },
    ]
  }
  const p0 = Math.round(((raw[0]! / rawSum) * gross) * 100) / 100
  const p1 = Math.round((gross - p0) * 100) / 100
  return [
    { ...legs[0]!, pnl_rub: p0 },
    { ...legs[1]!, pnl_rub: p1 },
  ]
}

export function hasLegPrices(trade: Trade): boolean {
  return (
    trade.legs?.length === 2 &&
    trade.legs.every(
      (l) =>
        l.entry_price != null &&
        l.exit_price != null &&
        l.entry_price > 0 &&
        l.exit_price > 0,
    )
  )
}

/** Краткая подпись ног для маркеров и строк статуса. */
export function legsCompactLabel(trade: Trade): string {
  return resolveTradeLegs(trade)
    .map((l) => {
      const arrow = l.side === 'buy' ? '↑' : '↓'
      return `${l.ticker}${arrow}`
    })
    .join(' · ')
}

export function spreadDirectionLabel(direction: string): string {
  if (direction === 'LONG') return 'LONG спред'
  if (direction === 'SHORT') return 'SHORT спред'
  return direction
}

export function spreadDirectionHint(direction: string): string {
  if (direction === 'LONG') {
    return 'Арбитраж: одновременно покупка TATN и продажа TATNP (ставка на рост спреда)'
  }
  if (direction === 'SHORT') {
    return 'Арбитраж: одновременно продажа TATN и покупка TATNP (ставка на падение спреда)'
  }
  return ''
}
