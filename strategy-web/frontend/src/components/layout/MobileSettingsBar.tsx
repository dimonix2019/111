import type { SimParams } from '@/types'

type Props = {
  params: SimParams
  liveMode: boolean
  loading: boolean
  moexBusy: boolean
  dataAgeHours?: number | null
  dataStale?: boolean
  onParamChange: (key: keyof SimParams, value: number) => void
  onLiveModeChange: (v: boolean) => void
  onRefreshMoex: () => void
  onRunNow: () => void
}

function numInput(
  label: string,
  value: number,
  step: number,
  onChange: (v: number) => void,
) {
  return (
    <label className="mobile-settings-field">
      <span className="mobile-settings-label">{label}</span>
      <input
        type="number"
        step={step}
        value={value}
        onChange={(e) => {
          const v = parseFloat(e.target.value.replace(',', '.'))
          if (Number.isFinite(v)) onChange(v)
        }}
      />
    </label>
  )
}

export function MobileSettingsBar({
  params,
  liveMode,
  loading,
  moexBusy,
  dataAgeHours,
  dataStale,
  onParamChange,
  onLiveModeChange,
  onRefreshMoex,
  onRunNow,
}: Props) {
  return (
    <details className="mobile-settings-bar" open>
      <summary className="mobile-settings-summary">
        <span className="font-semibold text-good">Rolling 30д</span>
        <span className="text-ink-3"> · настройки</span>
      </summary>
      <div className="mobile-settings-body">
        {numInput('Entry', Number(params.entry ?? 0), 0.05, (v) => onParamChange('entry', v))}
        {numInput('Exit', Number(params.exit_z ?? 0), 0.05, (v) => onParamChange('exit_z', v))}
        <label className="mobile-settings-check">
          <input type="checkbox" checked={liveMode} onChange={(e) => onLiveModeChange(e.target.checked)} />
          Live
        </label>
        {!liveMode ? (
          <button type="button" className="mobile-settings-btn" disabled={loading} onClick={onRunNow}>
            {loading ? '…' : '↻'}
          </button>
        ) : null}
        <button
          type="button"
          className="mobile-settings-btn primary"
          disabled={moexBusy || loading}
          onClick={onRefreshMoex}
        >
          {moexBusy ? '…' : 'MOEX'}
        </button>
        {dataStale ? (
          <span className="mobile-settings-hint text-warn">устарело</span>
        ) : dataAgeHours != null ? (
          <span className="mobile-settings-hint text-ink-3">{dataAgeHours.toFixed(0)}ч</span>
        ) : null}
      </div>
    </details>
  )
}
