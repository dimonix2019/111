import type { Trade } from '@/types'
import { fmtRub } from '@/lib/api'
import { spreadDirectionLabel } from '@/lib/spreadLegs'

type TradeColumn = {
  key:
    | keyof Trade
    | 'leg_ticker'
    | 'leg_action'
    | 'leg_entry_price'
    | 'leg_exit_price'
    | 'leg_qty'
    | 'leg_pnl'
  label: string
  align?: 'left' | 'right'
  render?: (t: Trade) => string
  filterKind?: 'select' | 'text'
  /** rowspan на уровне сделки (не ноги) */
  tradeLevel?: boolean
}

const BASE_COLUMNS: TradeColumn[] = [
  { key: 'no', label: '№', align: 'right', tradeLevel: true },
  {
    key: 'direction',
    label: 'Спрэд',
    filterKind: 'select',
    tradeLevel: true,
    render: (t) => spreadDirectionLabel(t.direction),
  },
  { key: 'leg_ticker', label: 'Бумага' },
  { key: 'leg_action', label: 'Ордер' },
  { key: 'leg_entry_price', label: 'Цена вх.', align: 'right' },
  { key: 'leg_exit_price', label: 'Цена вых.', align: 'right' },
  { key: 'leg_qty', label: 'Кол-во', align: 'right' },
  { key: 'leg_pnl', label: 'PnL ноги', align: 'right' },
  { key: 'entry_time', label: 'Вход', tradeLevel: true },
  { key: 'exit_time', label: 'Выход', tradeLevel: true },
  {
    key: 'entry_spread',
    label: 'Spread вх.',
    align: 'right',
    tradeLevel: true,
    render: (t) => t.entry_spread.toFixed(3),
  },
  {
    key: 'exit_spread',
    label: 'Spread вых.',
    align: 'right',
    tradeLevel: true,
    render: (t) => t.exit_spread.toFixed(3),
  },
  {
    key: 'entry_z',
    label: 'Z вх.',
    align: 'right',
    tradeLevel: true,
    render: (t) => t.entry_z.toFixed(3),
  },
  {
    key: 'exit_z',
    label: 'Z вых.',
    align: 'right',
    tradeLevel: true,
    render: (t) => t.exit_z.toFixed(3),
  },
  {
    key: 'pnl_spread_pts',
    label: 'PnL spread',
    align: 'right',
    tradeLevel: true,
    render: (t) => t.pnl_spread_pts.toFixed(3),
  },
  {
    key: 'commission_rub',
    label: 'Комиссия',
    align: 'right',
    tradeLevel: true,
    render: (t) => fmtRub(t.commission_rub ?? 0),
  },
  {
    key: 'overnight_rub',
    label: 'Overnight',
    align: 'right',
    tradeLevel: true,
    render: (t) => fmtRub(t.overnight_rub ?? 0),
  },
  {
    key: 'pnl_rub',
    label: 'PnL ₽',
    align: 'right',
    tradeLevel: true,
    render: (t) => fmtRub(t.pnl_rub),
  },
]

const EXIT_REASON_COLUMN: TradeColumn = {
  key: 'exit_reason',
  label: 'Причина выхода',
  filterKind: 'select',
  tradeLevel: true,
  render: (t) => {
    const r = t.exit_reason ?? 'z_exit'
    if (r === 'stop_loss') return 'Stop-loss'
    if (r === 'end_of_data') return 'Конец данных'
    return 'Z-exit'
  },
}

export function columnsForTrades(trades: Trade[]): TradeColumn[] {
  const showExitReason = trades.some((t) => t.exit_reason && t.exit_reason !== 'z_exit')
  if (!showExitReason) return BASE_COLUMNS
  const idx = BASE_COLUMNS.findIndex((c) => c.key === 'exit_time')
  return [...BASE_COLUMNS.slice(0, idx + 1), EXIT_REASON_COLUMN, ...BASE_COLUMNS.slice(idx + 1)]
}

/** Мобильная таблица: без секунд; compact — dd.mm HH:mm (без года). */
export function formatTradeDateTimeMobile(s: string, compact = false): string {
  const d = new Date(s.replace(' ', 'T'))
  if (!Number.isNaN(d.getTime())) {
    const dd = String(d.getDate()).padStart(2, '0')
    const mm = String(d.getMonth() + 1).padStart(2, '0')
    const hh = String(d.getHours()).padStart(2, '0')
    const min = String(d.getMinutes()).padStart(2, '0')
    if (compact) return `${dd}.${mm} ${hh}:${min}`
    const yy = String(d.getFullYear()).slice(-2)
    return `${dd}.${mm}.${yy} ${hh}:${min}`
  }
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/)
  if (m) {
    if (compact) return `${m[3]}.${m[2]} ${m[4]}:${m[5]}`
    return `${m[3]}.${m[2]}.${m[1]!.slice(-2)} ${m[4]}:${m[5]}`
  }
  return s.replace(/\d{4}/, (y) => y.slice(-2)).replace(/:\d{2}$/, '')
}

/** Мобильный /m — одна строка на сделку, без ног TATN/TATNP. */
const MOBILE_TRADE_COLUMNS: TradeColumn[] = [
  { key: 'no', label: '№', align: 'right', tradeLevel: true },
  {
    key: 'direction',
    label: 'L/S',
    filterKind: 'select',
    tradeLevel: true,
    render: (t) => (t.direction === 'LONG' ? 'L' : 'S'),
  },
  {
    key: 'entry_time',
    label: 'Вх',
    tradeLevel: true,
    render: (t) => formatTradeDateTimeMobile(t.entry_time, true),
  },
  {
    key: 'exit_time',
    label: 'Вых',
    tradeLevel: true,
    render: (t) => formatTradeDateTimeMobile(t.exit_time, true),
  },
  {
    key: 'entry_z',
    label: 'Zв',
    align: 'right',
    tradeLevel: true,
    render: (t) => t.entry_z.toFixed(2),
  },
  {
    key: 'exit_z',
    label: 'Zы',
    align: 'right',
    tradeLevel: true,
    render: (t) => t.exit_z.toFixed(2),
  },
  {
    key: 'pnl_rub',
    label: 'PnL',
    align: 'right',
    tradeLevel: true,
    render: (t) => fmtRub(t.pnl_rub),
  },
]

export function columnsForMobileTradesList(trades: Trade[]): TradeColumn[] {
  const showExitReason = trades.some((t) => t.exit_reason && t.exit_reason !== 'z_exit')
  if (!showExitReason) return MOBILE_TRADE_COLUMNS
  return [...MOBILE_TRADE_COLUMNS, EXIT_REASON_COLUMN]
}

export type { TradeColumn }
