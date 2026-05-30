import { useMemo, useState } from 'react'
import { Panel } from '@/components/ui/Panel'
import { useBasisScan } from '@/hooks/useBasisScan'
import type { BasisScanRow } from '@/types'

function toneAnn(v: number): string {
  if (v >= 25) return 'text-good'
  if (v >= 15) return 'text-warn'
  return 'text-ink-2'
}

function toneZ(z: number | null): string {
  if (z == null) return 'text-ink-3'
  if (z >= 2) return 'text-good'
  if (z >= 1) return 'text-warn'
  return 'text-ink-2'
}

function MiniSpark({ history }: { history: BasisScanRow['history'] }) {
  const pts = history ?? []
  if (pts.length < 2) return <span className="text-ink-3">—</span>
  const vals = pts.map((p) => p.basis)
  const lo = Math.min(...vals)
  const hi = Math.max(...vals)
  const span = hi - lo || 1
  const w = 56
  const h = 18
  const d = pts
    .map((p, i) => {
      const x = (i / (pts.length - 1)) * w
      const y = h - ((p.basis - lo) / span) * h
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
  return (
    <svg width={w} height={h} className="inline-block align-middle opacity-80">
      <polyline fill="none" stroke="#5ec492" strokeWidth="1.2" points={d} />
    </svg>
  )
}

export function BasisCarryTab() {
  const [finRate, setFinRate] = useState(18)
  const [minYield, setMinYield] = useState(15)
  const [zEntry, setZEntry] = useState(1.0)

  const scanParams = useMemo(
    () => ({ finRate: finRate / 100, minYieldAnn: minYield, zEntry }),
    [finRate, minYield, zEntry],
  )
  const { data, loading, error, reload } = useBasisScan(scanParams, true, 60)

  return (
    <div className="space-y-4">
      <Panel>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-lg font-bold text-ink-1">Basis / Cash-and-Carry</h2>
            <p className="mt-1 max-w-2xl text-[12px] leading-relaxed text-ink-2">
              Сканер MOEX: <span className="text-ink-1">лонг акция + шорт фьюч</span> при широком
              базисе. Сигнал — net-доходность после грубых комиссий и Z-score edge vs история.
            </p>
          </div>
          <button
            type="button"
            className="surface-chip text-[11px] text-ink-1 hover:bg-white/10"
            onClick={() => void reload()}
            disabled={loading}
          >
            {loading ? 'Обновление…' : 'Обновить'}
          </button>
        </div>

        <div className="mt-4 grid gap-3 sm:grid-cols-3">
          <label className="block text-[11px] text-ink-2">
            Ставка финансирования, % год.
            <input
              type="number"
              min={5}
              max={30}
              step={0.5}
              value={finRate}
              onChange={(e) => setFinRate(Number(e.target.value))}
              className="mt-1 w-full rounded-lg border border-slate-500/30 bg-slate-900/40 px-2 py-1.5 text-[13px] text-ink-1"
            />
          </label>
          <label className="block text-[11px] text-ink-2">
            Мин. net-доходность, % год.
            <input
              type="number"
              min={5}
              max={50}
              step={1}
              value={minYield}
              onChange={(e) => setMinYield(Number(e.target.value))}
              className="mt-1 w-full rounded-lg border border-slate-500/30 bg-slate-900/40 px-2 py-1.5 text-[13px] text-ink-1"
            />
          </label>
          <label className="block text-[11px] text-ink-2">
            Порог Z (edge)
            <input
              type="number"
              min={0}
              max={3}
              step={0.1}
              value={zEntry}
              onChange={(e) => setZEntry(Number(e.target.value))}
              className="mt-1 w-full rounded-lg border border-slate-500/30 bg-slate-900/40 px-2 py-1.5 text-[13px] text-ink-1"
            />
          </label>
        </div>
      </Panel>

      {error ? (
        <Panel>
          <p className="text-[12px] text-bad">{error}</p>
          <p className="mt-1 text-[11px] text-ink-3">
            Перезапустите API (run_tester.bat) — нужен api_build ≥ 5 с /api/basis/scan.
          </p>
        </Panel>
      ) : null}

      {data ? (
        <>
          <div className="surface-inner px-3 py-2 text-[12px] text-ink-2">
            {data.summary}
            <span className="ml-2 text-ink-3">· {data.as_of.slice(0, 19).replace('T', ' ')}</span>
          </div>

          <div className="overflow-x-auto rounded-2xl border border-surface-border bg-[linear-gradient(165deg,rgba(17,28,50,0.94),rgba(13,20,37,0.98))] shadow-panel">
            <table className="w-full min-w-[880px] border-collapse text-[11px]">
              <thead>
                <tr className="border-b border-slate-500/25 text-left text-ink-3">
                  <th className="px-3 py-2 font-semibold">Актив</th>
                  <th className="px-2 py-2 font-semibold">Контракт</th>
                  <th className="px-2 py-2 font-semibold tabular-nums">Спот</th>
                  <th className="px-2 py-2 font-semibold tabular-nums">Фьюч</th>
                  <th className="px-2 py-2 font-semibold tabular-nums">Базис</th>
                  <th className="px-2 py-2 font-semibold tabular-nums">Fair</th>
                  <th className="px-2 py-2 font-semibold tabular-nums">Edge</th>
                  <th className="px-2 py-2 font-semibold tabular-nums">Net %</th>
                  <th className="px-2 py-2 font-semibold tabular-nums">Z</th>
                  <th className="px-2 py-2 font-semibold tabular-nums">Дней</th>
                  <th className="px-2 py-2 font-semibold">90д</th>
                  <th className="px-2 py-2 font-semibold">Сигнал</th>
                </tr>
              </thead>
              <tbody>
                {data.rows.map((r) => (
                  <tr
                    key={r.secid}
                    className={`border-b border-slate-500/15 ${
                      r.signal ? 'bg-good/10' : 'hover:bg-white/[0.02]'
                    }`}
                  >
                    <td className="px-3 py-2 font-semibold text-ink-1">{r.asset}</td>
                    <td className="px-2 py-2 text-ink-2">
                      {r.shortname}
                      <div className="text-[10px] text-ink-3">до {r.expiry}</div>
                    </td>
                    <td className="px-2 py-2 tabular-nums text-ink-1">{r.spot.toFixed(2)}</td>
                    <td className="px-2 py-2 tabular-nums text-ink-1">{r.fut.toFixed(2)}</td>
                    <td className="px-2 py-2 tabular-nums text-ink-2">{r.basis_obs.toFixed(2)}</td>
                    <td className="px-2 py-2 tabular-nums text-ink-3">{r.basis_fair.toFixed(2)}</td>
                    <td className="px-2 py-2 tabular-nums text-cyan-300/90">{r.edge.toFixed(2)}</td>
                    <td className={`px-2 py-2 tabular-nums font-semibold ${toneAnn(r.net_ann_pct)}`}>
                      {r.net_ann_pct.toFixed(1)}%
                    </td>
                    <td className={`px-2 py-2 tabular-nums ${toneZ(r.z_score)}`}>
                      {r.z_score != null ? r.z_score.toFixed(2) : '—'}
                    </td>
                    <td className="px-2 py-2 tabular-nums text-ink-2">{r.days_to_exp}</td>
                    <td className="px-2 py-2">
                      <MiniSpark history={r.history} />
                    </td>
                    <td className="px-2 py-2">
                      {r.signal ? (
                        <span className="rounded-md bg-good/20 px-2 py-0.5 text-[10px] font-semibold text-good">
                          ВХОД
                        </span>
                      ) : (
                        <span className="text-ink-3">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!data.rows.length ? (
              <p className="p-4 text-[12px] text-ink-3">Контракты не найдены или нет котировок.</p>
            ) : null}
          </div>

          <Panel>
            <p className="text-[11px] leading-relaxed text-ink-3">
              <span className="text-ink-2">Fair basis</span> = spot × r × T/365 − дивиденды до экспирации
              (календарь частичный, сейчас в основном TATN).{' '}
              <span className="text-ink-2">Net %</span> — edge на лот минус комиссии 0,04%×2 на акции и ~1 ₽×2
              на фьюч, годовая. Сделка: купить {data.rows[0]?.lot_volume ?? 100} акций на каждый контракт + шорт 1
              фьюч.
            </p>
          </Panel>
        </>
      ) : loading ? (
        <Panel>
          <p className="text-[12px] text-ink-2">Загрузка MOEX FORTS…</p>
        </Panel>
      ) : null}
    </div>
  )
}
