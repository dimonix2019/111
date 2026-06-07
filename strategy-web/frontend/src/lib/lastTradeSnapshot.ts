import type { SimPack, Trade, TradeMarker } from '@/types'
import { formatTradeDateTimeMobile } from '@/lib/tradeTableColumns'
import { lastZscorePoint } from '@/lib/marketPosition'
import { fmtRub } from '@/lib/api'

export type TradeSituationStatus = 'open' | 'closed' | 'none'

export type LastTradeSnapshot = {
  status: TradeSituationStatus
  /** Строка для UI — как в мобильной таблице сделок. */
  trade: Trade | null
  statusLabel: string
}

function parseEntryUnix(s: string): number | null {
  const d = new Date(s.replace(' ', 'T'))
  const t = d.getTime()
  return Number.isNaN(t) ? null : Math.floor(t / 1000)
}

function barAtOrBefore(
  zscore: SimPack['zscore'],
  entryTime: string,
): SimPack['zscore'][number] | null {
  const target = parseEntryUnix(entryTime)
  if (target == null || !zscore.length) return null
  let best: SimPack['zscore'][number] | null = null
  for (const bar of zscore) {
    if (bar.time <= target) best = bar
    else break
  }
  return best ?? zscore[0]!
}

function lastEntryMarker(markers: TradeMarker[]): TradeMarker | null {
  const entries = markers.filter((m) => m.event === 'вход')
  return entries.length ? entries[entries.length - 1]! : null
}

function isInPosition(pack: SimPack): boolean {
  if (pack.market_context?.in_position) return true
  return Math.abs(pack.unrealized_pnl_rub ?? 0) > 1e-6
}

function buildOpenTradeFromPack(pack: SimPack): Trade | null {
  const entryM = lastEntryMarker(pack.trade_markers)
  const last = lastZscorePoint(pack)
  if (!entryM || !last) return null

  const entryBar = barAtOrBefore(pack.zscore, entryM.entry_time)
  const entrySpread = entryBar?.spread_percent ?? 0
  const exitSpread = last.spread_percent
  const dir = entryM.direction
  const pnlPts = dir === 'LONG' ? exitSpread - entrySpread : entrySpread - exitSpread

  return {
    no: entryM.trade_no,
    direction: dir,
    entry_time: entryM.entry_time,
    exit_time: pack.market_context?.as_of_display ?? '—',
    entry_spread: entrySpread,
    exit_spread: exitSpread,
    entry_z: entryM.z_score,
    exit_z: last.z_score,
    pnl_spread_pts: pnlPts,
    pnl_rub: pack.unrealized_pnl_rub ?? 0,
    commission_rub: 0,
    overnight_rub: 0,
  }
}

/** Последняя закрытая сделка в журнале. */
export function lastClosedTrade(pack: SimPack): Trade | null {
  if (!pack.trades.length) return null
  return pack.trades.reduce((a, b) => (b.no > a.no ? b : a), pack.trades[0]!)
}

export function getLastTradeSnapshot(pack: SimPack): LastTradeSnapshot {
  if (isInPosition(pack)) {
    const open = buildOpenTradeFromPack(pack)
    return {
      status: 'open',
      trade: open,
      statusLabel: 'Открыта',
    }
  }

  const closed = lastClosedTrade(pack)
  if (!closed) {
    return { status: 'none', trade: null, statusLabel: 'Нет сделок' }
  }

  return {
    status: 'closed',
    trade: closed,
    statusLabel: 'Закрыта',
  }
}

export function formatTradePnlRub(v: number, isOpen: boolean): string {
  const s = fmtRub(v)
  return isOpen ? `${s}*` : s
}

export function formatTradeFieldTime(s: string, isOpenExit: boolean): string {
  if (isOpenExit && (s === '—' || !s.trim())) return '—'
  return formatTradeDateTimeMobile(s, true)
}
