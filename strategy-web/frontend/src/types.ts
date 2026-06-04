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

export type TradeOrderLeg = {
  ticker: string
  side: string
  sideRu?: string
  entry_price: number
  exit_price: number
  qty: number
  pnl_rub: number
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
  commission_rub?: number
  overnight_rub?: number
  exit_reason?: string
  legs?: TradeOrderLeg[]
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

export type IdleEventCorrelation = {
  window_days: number
  long_gaps_count: number
  matches_count: number
  strength: 'none' | 'weak' | 'moderate'
  summary: string
  events: { date: string; kind: string; label: string }[]
  rows: { days: number; from: string; to: string; tags: string[] }[]
}

export type IdlePrecursors = {
  risk_score: number
  signs: IdlePrecursorSign[]
  summary: string
  verdict: string
  duration_forecast?: IdleDurationForecast
  event_correlation?: IdleEventCorrelation
  features: Record<string, number>
  percentiles: Record<string, number>
  idle_current?: IdleGaps['current']
}

export type RiskMetrics = {
  worst_trade_rub: number
  best_trade_rub: number
  tail_ratio: number | null
  commission_pct_of_gross: number
  top10_concentration_pct: number
  stop_loss_exits: number
  z_exits: number
  end_of_data_exits?: number
  trading_halted: boolean
  halt_reason: string
}

export type OosVerdict = {
  grade: 'good' | 'caution' | 'overfit' | 'weak_data'
  title: string
  summary: string
  signals: string[]
  actions: string[]
}

export type OosResult = {
  train_ratio: number
  split_bar: number
  compound_returns?: boolean
  train: Stats
  test: Stats
  train_risk: RiskMetrics
  test_risk: RiskMetrics
  verdict?: OosVerdict
  error?: string
}

export type StressStats = Pick<
  Stats,
  'total_pnl_rub' | 'trade_count' | 'max_drawdown_rub' | 'win_rate_pct' | 'profit_factor' | 'total_commission_rub'
>

export type StressResult = {
  commission_multiplier: number
  slippage_pts: number
  baseline: StressStats
  stress: StressStats
}

export type SimPack = {
  label: string
  entry: number
  exit_z: number
  stats: Stats
  trades: Trade[]
  equity: { time: number; equity_rub: number; drawdown_rub: number }[]
  zscore: {
    time: number
    z_score: number
    spread_percent: number
    tatn_open?: number
    tatn_high?: number
    tatn_low?: number
    tatn_close?: number
    tatnp_open?: number
    tatnp_high?: number
    tatnp_low?: number
    tatnp_close?: number
    spread_open?: number
    spread_high?: number
    spread_low?: number
    spread_close?: number
    tatn_volume?: number
  }[]
  trade_markers: TradeMarker[]
  unrealized_pnl_rub: number
  idle_gaps: IdleGaps
  idle_precursors?: IdlePrecursors
  market_context: MarketContext
  latest_quote?: {
    tatn_close?: number
    tatnp_close?: number
    timestamp?: string
  }
  risk?: RiskMetrics
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
  oos?: OosResult | null
  stress?: StressResult | null
  packs: SimPack[]
}

export type SimParams = {
  csv_path?: string
  auto_download?: boolean
  moex_live?: boolean
  compare_mode?: boolean
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
  slippage?: number
  max_loss_spread?: number
  max_loss_rub?: number
  min_spread?: number
  max_spread?: number
  entry_z_buffer?: number
  max_dd_halt_rub?: number
  max_dd_halt_pct?: number
  oos_enabled?: boolean
  oos_train_ratio?: number
  pyramid_add_notional?: number
  pyramid_z_depth?: number
  include_stress?: boolean
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
    z_mode?: ZMode
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
