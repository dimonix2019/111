export type Stats = {
  trade_count: number
  total_pnl_rub: number
  realized_pnl_rub: number
  unrealized_pnl_rub: number
  win_rate_pct: number
  avg_pnl_rub: number
  max_drawdown_rub: number
  profit_factor: number | null
  long_pnl_rub: number
  short_pnl_rub: number
  avg_hold_hours: number
  total_commission_rub: number
  total_overnight_rub: number
}

export type Trade = {
  no: number
  direction: string
  entry_time: string
  exit_time: string
  entry_spread: number
  exit_spread: number
  entry_z: number
  exit_z: number
  pnl_spread_pts: number
  pnl_rub: number
}

export type TradeMarker = {
  trade_no: number
  event: string
  time: number
  z_score: number
  direction: string
  entry_time: string
  exit_time: string
  pnl_rub: number
  pnl_spread_pts: number
  marker_color: string
}

export type IdleGaps = {
  gaps: { days: number; from: string; to: string }[]
  histogram: { bucket: string; count: number }[]
  current: {
    idle_days: number
    since_display: string
    in_position: boolean
    label: string
  }
}

export type DistributionMetric = {
  id: string
  title: string
  current_value: number | null
  current_display: string
  percentile: number | null
  abs_percentile?: number | null
  histogram: { bucket: string; count: number }[]
  current_bucket: string
  label: string
  highlight_current: boolean
}

export type MarketContext = {
  as_of: string
  as_of_display: string
  in_position: boolean
  metrics: DistributionMetric[]
}

export type IdlePrecursorSign = {
  id: string
  title: string
  active: boolean
  weight: number
  label: string
  level: string
}

export type IdleDurationForecast = {
  applicable: boolean
  reason?: string
  current_idle_days?: number
  median_remaining_days?: number | null
  median_total_days?: number | null
  p25_remaining_days?: number | null
  p75_remaining_days?: number | null
  historical_gaps_count?: number
  similar_gaps_count?: number
  percentile_vs_history?: number | null
  label: string
  display_short?: string
}

export type IdlePrecursors = {
  risk_score: number
  signs: IdlePrecursorSign[]
  summary: string
  verdict: string
  duration_forecast?: IdleDurationForecast
  features: Record<string, number>
  percentiles: Record<string, number>
  idle_current?: IdleGaps['current']
}

export type SimPack = {
  label: string
  entry: number
  exit_z: number
  stats: Stats
  trades: Trade[]
  equity: { time: number; equity_rub: number; drawdown_rub: number }[]
  zscore: { time: number; z_score: number; spread_percent: number }[]
  trade_markers: TradeMarker[]
  unrealized_pnl_rub: number
  idle_gaps: IdleGaps
  idle_precursors?: IdlePrecursors
  market_context: MarketContext
}

export type ZMode = 'rolling30' | 'global'

export type ZDiagnostics = {
  max_abs_z: number
  bars_ge_08: number
  bars_ge_07: number
  last_z: number
  last_spread: number
}

export type ZCompareRow = {
  'режим Z': string
  сделок: number
  'PnL ₽': number
  'max DD ₽': number
  'max |Z|': number
  'баров |Z|≥0.8': number
}

export type SimResponse = {
  path: string
  hq: {
    bar_count: number
    looks_full_255d: boolean
    first_ts: string
    last_ts: string
    expected_trades_hint: string
    z_mode?: ZMode
  }
  data_meta?: {
    last_ts?: string
    age_hours?: number
    is_stale?: boolean
    needs_refresh?: boolean
  }
  sim_kw: Record<string, unknown>
  compare_mode: boolean
  z_mode?: ZMode
  z_diagnostics?: ZDiagnostics
  z_compare?: ZCompareRow[] | null
  packs: SimPack[]
}

export type SimParams = {
  csv_path?: string
  auto_download?: boolean
  /** Онлайн 5с / «Обновить MOEX» — догрузка при возрасте баров > ~20 мин */
  moex_live?: boolean
  compare_mode?: boolean
  /** rolling30 (default) — без look-ahead; global — legacy APK */
  z_mode?: ZMode
  entry?: number
  exit_z?: number
  notional?: number
  entry_a?: number
  exit_a?: number
  notional_a?: number
  entry_b?: number
  exit_b?: number
  notional_b?: number
  leverage?: number
  commission?: number
  compound?: boolean
}

export type Presets = Record<
  string,
  {
    entry: number
    exit_z: number
    notional: number
    leverage: number
    commission: number
    compound: boolean
  } | null
>

export type BasisHistoryPoint = {
  date: string
  basis: number
  edge: number
}

export type BasisScanRow = {
  asset: string
  secid: string
  shortname: string
  spot: number
  fut: number
  basis_obs: number
  basis_fair: number
  edge: number
  edge_ann_pct: number
  net_ann_pct: number
  z_score: number | null
  days_to_exp: number
  expiry: string
  lot_volume: number
  initial_margin: number
  div_pv: number
  fin_rate_pct: number
  signal: boolean
  history: BasisHistoryPoint[]
}

export type BasisScanResponse = {
  as_of: string
  fin_rate_pct: number
  min_yield_ann_pct: number
  z_entry: number
  contracts_scanned: number
  signals_count: number
  summary: string
  rows: BasisScanRow[]
}
