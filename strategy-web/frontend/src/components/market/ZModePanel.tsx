import type { SimResponse, ZMode } from '@/types'

type Props = {
  zMode: ZMode
  diag?: SimResponse['z_diagnostics']
  compare?: SimResponse['z_compare']
}

export function ZModePanel({ zMode, diag, compare }: Props) {
  const modeLabel = zMode === 'rolling30' ? 'Rolling 30д' : 'Global 255д'
  const modeHint =
    zMode === 'rolling30'
      ? 'Без look-ahead — честный бэктест (как Android PR #26)'
      : 'μ/σ на весь CSV — legacy APK, Z на хвосте часто прижат'

  return (
    <div className="surface-inner space-y-3 p-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="text-[13px] font-bold text-ink-1">Режим Z: {modeLabel}</p>
          <p className="mt-0.5 text-[11px] text-ink-2">{modeHint}</p>
        </div>
        <span
          className={`rounded-md px-2 py-0.5 text-[10px] font-semibold ${
            zMode === 'rolling30' ? 'bg-good/20 text-good' : 'bg-warn/20 text-warn'
          }`}
        >
          {modeLabel}
        </span>
      </div>

      {diag ? (
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          <div className="rounded-lg bg-slate-900/40 px-2 py-1.5">
            <p className="text-[10px] text-ink-3">max |Z|</p>
            <p className="tabular-nums text-[13px] font-semibold text-ink-1">{diag.max_abs_z.toFixed(3)}</p>
          </div>
          <div className="rounded-lg bg-slate-900/40 px-2 py-1.5">
            <p className="text-[10px] text-ink-3">|Z| ≥ 0.8</p>
            <p className="tabular-nums text-[13px] font-semibold text-ink-1">{diag.bars_ge_08}</p>
          </div>
          <div className="rounded-lg bg-slate-900/40 px-2 py-1.5">
            <p className="text-[10px] text-ink-3">|Z| ≥ 0.7</p>
            <p className="tabular-nums text-[13px] font-semibold text-ink-1">{diag.bars_ge_07}</p>
          </div>
          <div className="rounded-lg bg-slate-900/40 px-2 py-1.5">
            <p className="text-[10px] text-ink-3">Z / spread</p>
            <p className="tabular-nums text-[13px] font-semibold text-ink-1">
              {diag.last_z.toFixed(3)} / {diag.last_spread.toFixed(3)}%
            </p>
          </div>
        </div>
      ) : null}

      {zMode === 'rolling30' && compare && compare.length > 0 ? (
        <details className="group rounded-lg border border-slate-500/30 bg-slate-900/30">
          <summary className="cursor-pointer list-none px-3 py-2 text-[12px] font-semibold text-slate-200 marker:content-none hover:text-ink-1 [&::-webkit-details-marker]:hidden">
            <span className="inline-flex items-center gap-2">
              <span className="text-slate-400 transition group-open:rotate-90">▸</span>
              Сравнение с Global 255д
            </span>
          </summary>
          <div className="overflow-x-auto border-t border-slate-500/25 px-2 pb-2 pt-2">
            <table className="w-full min-w-[420px] border-collapse text-[11px]">
              <thead>
                <tr className="text-left text-ink-3">
                  <th className="px-2 py-1">Режим</th>
                  <th className="px-2 py-1">Сделок</th>
                  <th className="px-2 py-1">PnL ₽</th>
                  <th className="px-2 py-1">max DD</th>
                  <th className="px-2 py-1">max |Z|</th>
                  <th className="px-2 py-1">|Z|≥0.8</th>
                </tr>
              </thead>
              <tbody>
                {compare.map((row) => (
                  <tr key={String(row['режим Z'])} className="border-t border-slate-500/15">
                    <td className="px-2 py-1 text-ink-2">{String(row['режим Z'])}</td>
                    <td className="px-2 py-1 tabular-nums">{Number(row['сделок'])}</td>
                    <td className="px-2 py-1 tabular-nums">{Number(row['PnL ₽']).toLocaleString('ru-RU')}</td>
                    <td className="px-2 py-1 tabular-nums">{Number(row['max DD ₽']).toLocaleString('ru-RU')}</td>
                    <td className="px-2 py-1 tabular-nums">{Number(row['max |Z|']).toFixed(3)}</td>
                    <td className="px-2 py-1 tabular-nums">{Number(row['баров |Z|≥0.8'])}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      ) : null}
    </div>
  )
}
