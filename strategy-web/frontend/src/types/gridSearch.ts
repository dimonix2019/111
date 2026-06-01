import type { OosVerdict, Stats } from '@/types'

export type GridSearchRow = {
  entry: number
  exit_z: number
  slippage: number
  max_loss_spread: number
  max_loss_rub: number
  min_spread: number
  max_spread: number
  entry_z_buffer: number
  max_dd_halt_rub: number
  max_dd_halt_pct: number
  notional_rub: number
  leverage: number
  commission_pct_per_side: number
  compound_returns: boolean
  verdict_grade: string
  test_pnl_rub: number
  train_pnl_rub: number
  stats: Stats
  oos_verdict?: OosVerdict
  oos_test?: Stats
}

export type GridSearchResponse = {
  path: string
  evaluated: number
  combinations_planned: number
  good_count: number
  only_good: boolean
  rows: GridSearchRow[]
}

export type GridSearchRequest = {
  csv_path: string
  notional_rub: number
  leverage: number
  commission_pct_per_side: number
  compound_returns: boolean
  oos_train_ratio: number
  max_combinations?: number
  top_n?: number
  only_good?: boolean
}
