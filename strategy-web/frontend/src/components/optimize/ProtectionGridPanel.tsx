import { useState } from 'react'
import type { SimParams } from '@/types'
import type { GridSearchRow } from '@/types/gridSearch'
import { gridSearch } from '@/lib/api'
import { GridSearchTable } from '@/components/optimize/GridSearchTable'
import { Panel } from '@/components/ui/Panel'

type Props = {
  csvPath: string
  simKw: Record<string, unknown>
  params: SimParams
  onApply?: (patch: Partial<SimParams>) => void
  onGridComplete?: (rows: GridSearchRow[]) => void
}

export function ProtectionGridPanel({ csvPath, simKw, params, onApply, onGridComplete }: Props) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<Awaited<ReturnType<typeof gridSearch>> | null>(null)
  const [lastMs, setLastMs] = useState<number | null>(null)

  async function run() {
    setLoading(true)
    setError(null)
    const t0 = performance.now()
    try {
      const res = await gridSearch({
        csv_path: csvPath,
        notional_rub: Number(simKw.notional_rub ?? params.notional ?? 100_000),
        leverage: Number(simKw.leverage ?? params.leverage ?? 7),
        commission_pct_per_side: Number(simKw.commission_pct_per_side ?? params.commission ?? 0.04),
        compound_returns: Boolean(simKw.compound_returns ?? params.compound),
        oos_train_ratio: params.oos_train_ratio ?? 0.7,
        max_combinations: 400,
        top_n: 25,
        only_good: true,
      })
      setResult(res)
      setLastMs(Math.round(performance.now() - t0))
      onGridComplete?.(res.rows)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  return (
    <Panel title="Подбор · зелёная OOS-зона">
      <p className="mb-2 text-[11px] text-ink-3">
        Перебор Entry/Exit и параметров «Защита» (slippage, stop-loss, spread, DD halt). Остаются варианты с
        вердиктом <span className="text-good">good</span> — test подтверждает train без доминирующих признаков
        переобучения.
      </p>
      <button type="button" className="primary mb-3 max-w-xs" disabled={loading} onClick={() => void run()}>
        {loading ? 'Считаем подбор…' : 'Запустить подбор'}
      </button>
      {loading ? (
        <p className="mb-2 text-[11px] text-warn">До ~400 комбинаций · OOS 70/30 на каждой…</p>
      ) : null}
      {error ? <p className="mb-2 text-[11px] text-bad">{error}</p> : null}
      {result && !loading ? (
        <p className="mb-3 text-[11px] text-ink-3">
          Проверено {result.evaluated} из {result.combinations_planned} · зелёных: {result.good_count}
          {lastMs != null ? ` · ${lastMs} мс` : ''}
        </p>
      ) : null}
      {result && result.rows.length === 0 && !loading ? (
        <p className="text-[12px] text-warn">
          Нет комбинаций с grade=good. Ослабьте фильтры, увеличьте slippage в сетке или снимите only_good на API.
        </p>
      ) : null}
      {result && result.rows.length > 0 && !loading ? (
        <GridSearchTable rows={result.rows} onApply={onApply} />
      ) : null}
    </Panel>
  )
}
