import type { SimPack, Trade, TradeOrderLeg } from '@/types'
import { buildHoldCapContextLines } from '@/lib/holdCapSimulation'
import { buildExitScenarioContextLines } from '@/lib/asymmetricExitSimulation'
import { buildHoldTimeContextLines } from '@/lib/holdTimeAnalytics'
import { parseTs } from '@/lib/tradeDistribution'
import { resolveTradeLegs } from '@/lib/spreadLegs'

export type ZBar = SimPack['zscore'][number]

/** ~1200 баров + все сделки ≈ 35–45k токенов — влезает в 64–73k context LM Studio. */
export const LLM_MAX_BARS_IN_CONTEXT = 1200
export const LLM_MAX_EQUITY_IN_CONTEXT = 1200
export const LLM_EST_CHARS_PER_TOKEN = 3.6

const MSK_TZ = 'Europe/Moscow'

function fmtNum(v: number | undefined | null, digits: number): string {
  if (v == null || !Number.isFinite(v)) return ''
  return v.toFixed(digits)
}

function fmtUnixMsk(unix: number): string {
  return new Date(unix * 1000).toLocaleString('ru-RU', {
    timeZone: MSK_TZ,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}

function legByTicker(legs: TradeOrderLeg[], ticker: string): TradeOrderLeg | undefined {
  return legs.find((l) => l.ticker === ticker)
}

function wallTimeToUnix(s: string): number | null {
  const d = parseTs(s)
  if (!d) return null
  return Math.floor(d.getTime() / 1000)
}

function nearestBarIndex(bars: ZBar[], unix: number): number {
  if (!bars.length) return 0
  let lo = 0
  let hi = bars.length - 1
  while (lo < hi) {
    const mid = Math.floor((lo + hi) / 2)
    if (bars[mid]!.time < unix) lo = mid + 1
    else hi = mid
  }
  if (lo > 0 && Math.abs(bars[lo - 1]!.time - unix) < Math.abs(bars[lo]!.time - unix)) {
    return lo - 1
  }
  return lo
}

/** Равномерное прореживание + хвост без дыр (как на графиках). */
export function downsampleIndices(length: number, maxPoints: number, tailKeep = 256): number[] {
  if (length <= 0) return []
  if (length <= maxPoints) return Array.from({ length }, (_, i) => i)
  tailKeep = Math.min(tailKeep, maxPoints - 1, length)
  const headLen = length - tailKeep
  const headBudget = maxPoints - tailKeep
  if (headLen <= 0 || headBudget <= 0) {
    return Array.from({ length: maxPoints }, (_, i) => Math.floor((i * (length - 1)) / Math.max(1, maxPoints - 1)))
  }
  const step = Math.max(1, Math.floor(headLen / headBudget))
  const head = Array.from({ length: headBudget }, (_, i) => i * step)
  const tail = Array.from({ length: tailKeep }, (_, i) => headLen + i)
  return [...new Set([...head, ...tail])].sort((a, b) => a - b)
}

/** Бары для LLM: все entry/exit сделок + прореженная история. */
export function selectBarsForLlm(
  bars: ZBar[],
  trades: Trade[],
  maxPoints = LLM_MAX_BARS_IN_CONTEXT,
): { selected: ZBar[]; anchorCount: number; sampledCount: number } {
  if (!bars.length) return { selected: [], anchorCount: 0, sampledCount: 0 }
  if (bars.length <= maxPoints) {
    return { selected: bars, anchorCount: 0, sampledCount: bars.length }
  }

  const must = new Set<number>()
  for (const t of trades) {
    for (const ts of [wallTimeToUnix(t.entry_time), wallTimeToUnix(t.exit_time)]) {
      if (ts != null) must.add(nearestBarIndex(bars, ts))
    }
  }

  const room = Math.max(must.size, maxPoints)
  const base = downsampleIndices(bars.length, room)
  const merged = [...new Set([...must, ...base])].sort((a, b) => a - b)

  let indices = merged
  if (merged.length > maxPoints) {
    const mustArr = [...must].sort((a, b) => a - b)
    const rest = merged.filter((i) => !must.has(i))
    const restBudget = Math.max(0, maxPoints - mustArr.length)
    const restPick =
      restBudget >= rest.length
        ? rest
        : downsampleIndices(rest.length, restBudget).map((j) => rest[j]!)
    indices = [...new Set([...mustArr, ...restPick])].sort((a, b) => a - b)
  }

  return {
    selected: indices.map((i) => bars[i]!),
    anchorCount: must.size,
    sampledCount: indices.length,
  }
}

export function selectEquityForLlm(
  equity: SimPack['equity'],
  maxPoints = LLM_MAX_EQUITY_IN_CONTEXT,
): SimPack['equity'] {
  if (equity.length <= maxPoints) return equity
  const idx = downsampleIndices(equity.length, maxPoints)
  return idx.map((i) => equity[i]!)
}

export function estimateTokens(charCount: number): number {
  return Math.ceil(charCount / LLM_EST_CHARS_PER_TOKEN)
}

/** Все сделки — TSV с ногами TATN/TATNP. */
export function buildTradesTsv(trades: Trade[]): string {
  const header = [
    'no',
    'direction',
    'entry_time',
    'exit_time',
    'entry_z',
    'exit_z',
    'entry_spread_pct',
    'exit_spread_pct',
    'pnl_rub',
    'pnl_spread_pts',
    'commission_rub',
    'overnight_rub',
    'exit_reason',
    'tatn_entry',
    'tatn_exit',
    'tatn_qty',
    'tatn_leg_pnl',
    'tatnp_entry',
    'tatnp_exit',
    'tatnp_qty',
    'tatnp_leg_pnl',
  ].join('\t')

  const rows = trades.map((t) => {
    const legs = resolveTradeLegs(t)
    const tatn = legByTicker(legs, 'TATN')
    const tatnp = legByTicker(legs, 'TATNP')
    return [
      t.no,
      t.direction,
      t.entry_time,
      t.exit_time,
      fmtNum(t.entry_z, 3),
      fmtNum(t.exit_z, 3),
      fmtNum(t.entry_spread, 4),
      fmtNum(t.exit_spread, 4),
      Math.round(t.pnl_rub),
      fmtNum(t.pnl_spread_pts, 4),
      Math.round(t.commission_rub ?? 0),
      Math.round(t.overnight_rub ?? 0),
      t.exit_reason ?? '',
      fmtNum(tatn?.entry_price, 2),
      fmtNum(tatn?.exit_price, 2),
      fmtNum(tatn?.qty, 0),
      fmtNum(tatn?.pnl_rub, 2),
      fmtNum(tatnp?.entry_price, 2),
      fmtNum(tatnp?.exit_price, 2),
      fmtNum(tatnp?.qty, 0),
      fmtNum(tatnp?.pnl_rub, 2),
    ].join('\t')
  })

  return [header, ...rows].join('\n')
}

/** Компактные 15м бары (без дубля time_msk — экономия контекста). */
export function buildMarketBarsTsv(bars: ZBar[], compact = true): string {
  if (compact) {
    const header = ['time_unix', 'z', 'spread_pct', 'tatn_c', 'tatnp_c', 'spread_c'].join('\t')
    const rows = bars.map((b) =>
      [
        b.time,
        fmtNum(b.z_score, 4),
        fmtNum(b.spread_percent, 4),
        fmtNum(b.tatn_close, 2),
        fmtNum(b.tatnp_close, 2),
        fmtNum(b.spread_close, 4),
      ].join('\t'),
    )
    return [header, ...rows].join('\n')
  }

  const header = [
    'time_unix',
    'time_msk',
    'z_score',
    'spread_pct',
    'tatn_open',
    'tatn_high',
    'tatn_low',
    'tatn_close',
    'tatnp_open',
    'tatnp_high',
    'tatnp_low',
    'tatnp_close',
    'spread_close',
    'tatn_volume',
  ].join('\t')

  const rows = bars.map((b) =>
    [
      b.time,
      fmtUnixMsk(b.time),
      fmtNum(b.z_score, 4),
      fmtNum(b.spread_percent, 4),
      fmtNum(b.tatn_open, 2),
      fmtNum(b.tatn_high, 2),
      fmtNum(b.tatn_low, 2),
      fmtNum(b.tatn_close, 2),
      fmtNum(b.tatnp_open, 2),
      fmtNum(b.tatnp_high, 2),
      fmtNum(b.tatnp_low, 2),
      fmtNum(b.tatnp_close, 2),
      fmtNum(b.spread_close, 4),
      b.tatn_volume != null ? Math.round(b.tatn_volume) : '',
    ].join('\t'),
  )

  return [header, ...rows].join('\n')
}

export function buildEquityTsv(equity: SimPack['equity'], compact = true): string {
  if (compact) {
    const header = ['time_unix', 'equity_rub', 'drawdown_rub'].join('\t')
    const rows = equity.map((e) => [e.time, Math.round(e.equity_rub), Math.round(e.drawdown_rub)].join('\t'))
    return [header, ...rows].join('\n')
  }
  const header = ['time_unix', 'time_msk', 'equity_rub', 'drawdown_rub'].join('\t')
  const rows = equity.map((e) =>
    [e.time, fmtUnixMsk(e.time), Math.round(e.equity_rub), Math.round(e.drawdown_rub)].join('\t'),
  )
  return [header, ...rows].join('\n')
}

export type StrategyChatDatasetMeta = {
  tradeCount: number
  barCount: number
  barCountTotal: number
  equityCount: number
  equityCountTotal: number
  anchorBarCount: number
  charCount: number
  estimatedTokens: number
  barsTruncated: boolean
}

export function buildStrategyChatContext(opts: {
  pack: SimPack
  trades: Trade[]
  totalTrades: number
  marketBars: ZBar[]
  meta?: { barCountTotal?: number }
}): { text: string; meta: StrategyChatDatasetMeta } {
  const { pack, trades, totalTrades, marketBars, meta: barMeta } = opts
  const s = pack.stats
  const barCountTotal = barMeta?.barCountTotal ?? marketBars.length

  const { selected: llmBars, anchorCount, sampledCount } = selectBarsForLlm(marketBars, trades)
  const llmEquity = selectEquityForLlm(pack.equity)

  const parts: string[] = [
    '=== СВОДКА ===',
    `Стратегия: Entry Z=${pack.entry}, Exit Z=${pack.exit_z}, label=${pack.label}`,
    `Сделок: ${s.trade_count}, PnL ${Math.round(s.total_pnl_rub).toLocaleString('ru-RU')} ₽, win ${s.win_rate_pct.toFixed(1)}%`,
    `Max DD ${Math.round(s.max_drawdown_rub).toLocaleString('ru-RU')} ₽, PF ${s.profit_factor?.toFixed(2) ?? '∞'}, ср. PnL ${Math.round(s.avg_pnl_rub).toLocaleString('ru-RU')} ₽`,
    `LONG ${Math.round(s.long_pnl_rub).toLocaleString('ru-RU')} ₽ / SHORT ${Math.round(s.short_pnl_rub).toLocaleString('ru-RU')} ₽, ср. hold ${s.avg_hold_hours.toFixed(1)} ч`,
    `Комиссии ${Math.round(s.total_commission_rub).toLocaleString('ru-RU')} ₽, overnight ${Math.round(s.total_overnight_rub).toLocaleString('ru-RU')} ₽`,
  ]

  if (trades.length !== totalTrades) {
    parts.push(`Фильтр таблицы: ${trades.length} из ${totalTrades} сделок (TSV ниже — по фильтру)`)
  }

  parts.push(...buildHoldTimeContextLines(trades))
  parts.push(...buildExitScenarioContextLines(trades, marketBars, pack.exit_z))
  parts.push(...buildHoldCapContextLines(trades, marketBars))

  if (pack.latest_quote?.tatn_close) {
    parts.push(
      '',
      `Последняя котировка: TATN ${pack.latest_quote.tatn_close}, TATNP ${pack.latest_quote.tatnp_close ?? '—'} (${pack.latest_quote.timestamp ?? '?'})`,
    )
  }

  parts.push(
    '',
    '=== ФОРМАТ ДАННЫХ ===',
    'TSV (tab-separated). time_unix — UTC сек; для MSK: +3ч от unix.',
    'TRADES — все сделки с Z, spread, PnL, ценами TATN/TATNP.',
    `BARS_15M — ${sampledCount} баров из ${barCountTotal} (включая все entry/exit сделок, anchor=${anchorCount}; остальное прорежено).`,
    `EQUITY — ${llmEquity.length} точек из ${pack.equity.length}.`,
    'spread_pct = (TATN/TATNP - 1)*100.',
  )

  parts.push('', '=== TRADES (все сделки) ===', buildTradesTsv(trades))
  parts.push('', '=== BARS_15M ===', buildMarketBarsTsv(llmBars, true))
  parts.push('', '=== EQUITY ===', buildEquityTsv(llmEquity, true))

  const text = parts.join('\n')
  const charCount = text.length

  return {
    text,
    meta: {
      tradeCount: trades.length,
      barCount: llmBars.length,
      barCountTotal,
      equityCount: llmEquity.length,
      equityCountTotal: pack.equity.length,
      anchorBarCount: anchorCount,
      charCount,
      estimatedTokens: estimateTokens(charCount),
      barsTruncated: barCountTotal > llmBars.length,
    },
  }
}

/** Краткая подпись размера контекста для UI. */
export function formatContextSize(chars: number): string {
  if (chars < 1024) return `${chars} симв.`
  if (chars < 1024 * 1024) return `${(chars / 1024).toFixed(0)} KB`
  return `${(chars / (1024 * 1024)).toFixed(1)} MB`
}

export function formatTokenEstimate(tokens: number): string {
  if (tokens < 1000) return `~${tokens} tok`
  return `~${(tokens / 1000).toFixed(0)}k tok`
}
