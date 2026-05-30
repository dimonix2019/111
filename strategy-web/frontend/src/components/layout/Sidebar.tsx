import { useEffect, useState, type ChangeEvent, type FormEvent } from 'react'
import type { Presets, SimParams, SimResponse } from '@/types'
import { getDataStatus, getHealth, getPresets } from '@/lib/api'
import { getLlmStatus } from '@/lib/llmApi'
import { useLiveSim } from '@/hooks/useLiveSim'
import { Panel } from '@/components/ui/Panel'

const DEFAULT_CSV = 'data/m15_tatn_255d.csv'

type Props = {
  onResult: (r: SimResponse) => void
  onLoading?: (loading: boolean) => void
}

function parseNum(raw: string): number | null {
  const v = parseFloat(raw.replace(',', '.'))
  return Number.isFinite(v) ? v : null
}

export function Sidebar({ onResult, onLoading }: Props) {
  const [csvPath, setCsvPath] = useState(DEFAULT_CSV)
  const [dataInfo, setDataInfo] = useState<Record<string, unknown> | null>(null)
  const [presets, setPresets] = useState<Presets>({})
  const [preset, setPreset] = useState('Свои')
  const [compare, setCompare] = useState(false)
  const [liveMode, setLiveMode] = useState(true)
  const [apiOk, setApiOk] = useState<boolean | null>(null)
  const [apiLlmOk, setApiLlmOk] = useState<boolean | null>(null)
  const [params, setParams] = useState<SimParams>({
    z_mode: 'rolling30',
    entry: 0.8,
    exit_z: 0.7,
    notional: 100_000,
    leverage: 7,
    commission: 0.04,
    compound: false,
    entry_a: 0.8,
    exit_a: 0.7,
    notional_a: 100_000,
    entry_b: 1.0,
    exit_b: 0.7,
    notional_b: 100_000,
  })

  const { loading, error, lastMs, pending, stale, runNow } = useLiveSim({
    csvPath,
    compare,
    params,
    enabled: liveMode,
    onResult,
  })

  useEffect(() => {
    onLoading?.(loading)
  }, [loading, onLoading])

  useEffect(() => {
    void getPresets().then(setPresets)
    void getHealth()
      .then(async (h) => {
        setApiOk(!!h.ok)
        if (h.features?.llm) {
          try {
            const llm = await getLlmStatus()
            setApiLlmOk(llm.ok)
          } catch {
            setApiLlmOk(false)
          }
        } else {
          setApiLlmOk(false)
        }
      })
      .catch(() => {
        setApiOk(false)
        setApiLlmOk(null)
      })
  }, [])

  useEffect(() => {
    void getDataStatus(csvPath).then(setDataInfo).catch(() => setDataInfo(null))
  }, [csvPath])

  useEffect(() => {
    if (loading) return
    void getDataStatus(csvPath).then(setDataInfo).catch(() => setDataInfo(null))
  }, [loading, lastUpdatedAt, csvPath])

  function applyPreset(name: string) {
    setPreset(name)
    const p = presets[name]
    if (!p) return
    setParams((s) => ({
      ...s,
      entry: p.entry,
      exit_z: p.exit_z,
      notional: p.notional,
      leverage: p.leverage,
      commission: p.commission,
      compound: p.compound,
    }))
  }

  function patchParam(key: keyof SimParams, raw: string) {
    const v = parseNum(raw)
    if (v == null) return
    setParams((s) => ({ ...s, [key]: v }))
  }

  function onParamInput(key: keyof SimParams) {
    return (e: ChangeEvent<HTMLInputElement>) => patchParam(key, e.target.value)
  }

  function onParamNativeInput(key: keyof SimParams) {
    return (e: FormEvent<HTMLInputElement>) => patchParam(key, e.currentTarget.value)
  }

  const field = (label: string, key: keyof SimParams, step = 0.05) => (
    <label className="block text-[12px] font-medium text-ink-3">
      {label}
      <input
        type="number"
        step={step}
        value={Number(params[key] ?? 0)}
        onChange={onParamInput(key)}
        onInput={onParamNativeInput(key)}
        className="mt-1"
      />
    </label>
  )

  const statusLine = (() => {
    if (!liveMode) return 'Включите live или нажмите «Обновить»'
    if (loading) return 'Считаем…'
    if (pending) return 'Параметры изменены — пересчёт через ~0.4 с…'
    if (lastMs != null) return `Последний расчёт: ${lastMs} мс`
    return 'Ожидание первого расчёта…'
  })()

  const zThresholdsPanel = (
    <Panel title={compare ? 'Стратегии A / B' : 'Пороги Z'}>
      {compare ? (
        <div className="grid grid-cols-2 gap-2">
          <div className="space-y-2">
            <div className="text-[11px] font-medium text-ink-2">A</div>
            {field('Entry', 'entry_a')}
            {field('Exit', 'exit_a')}
            {field('Номинал', 'notional_a', 10_000)}
          </div>
          <div className="space-y-2">
            <div className="text-[11px] font-medium text-ink-2">B</div>
            {field('Entry', 'entry_b')}
            {field('Exit', 'exit_b')}
            {field('Номинал', 'notional_b', 10_000)}
          </div>
        </div>
      ) : (
        <div className="space-y-2">
          {field('Entry Z', 'entry')}
          {field('Exit Z', 'exit_z')}
          {field('Номинал ₽', 'notional', 10_000)}
        </div>
      )}
    </Panel>
  )

  const zModePanel = (
    <Panel title="Z-score · режим теста">
      <p className="mb-2 text-[11px] text-ink-3">Применяется ко всем вкладкам (кроме Basis).</p>
      <label className="flex cursor-pointer gap-2 rounded-lg border border-good/30 bg-good/5 px-2 py-2 text-[12px]">
        <input
          type="radio"
          name="z_mode"
          checked={params.z_mode !== 'global'}
          onChange={() => setParams((s) => ({ ...s, z_mode: 'rolling30' }))}
        />
        <span>
          <span className="font-semibold text-ink-1">Rolling 30д</span>
          <span className="mt-0.5 block text-[10px] text-ink-3">Без look-ahead — рекомендуется</span>
        </span>
      </label>
      <label className="mt-2 flex cursor-pointer gap-2 rounded-lg border border-slate-500/30 px-2 py-2 text-[12px]">
        <input
          type="radio"
          name="z_mode"
          checked={params.z_mode === 'global'}
          onChange={() => setParams((s) => ({ ...s, z_mode: 'global' }))}
        />
        <span>
          <span className="font-semibold text-ink-1">Global 255д</span>
          <span className="mt-0.5 block text-[10px] text-ink-3">Legacy APK — Z на хвосте часто ≈0</span>
        </span>
      </label>
    </Panel>
  )

  return (
    <aside className="scrollbar-thin flex h-full min-h-0 w-[320px] shrink-0 flex-col gap-3 overflow-x-hidden overflow-y-auto pb-2 pr-1">
      {zModePanel}
      {zThresholdsPanel}

      <Panel title="Live-режим">
        <label className="flex items-center gap-2 text-[12px] text-ink-2">
          <input type="checkbox" checked={liveMode} onChange={(e) => setLiveMode(e.target.checked)} />
          Пересчёт при изменении параметров
        </label>
        <p className={`mt-2 text-[11px] ${loading || pending ? 'text-warn' : 'text-ink-3'}`}>{statusLine}</p>
        {stale ? (
          <p className="mt-1 text-[11px] text-warn">На экране старые данные — дождитесь расчёта или кликните вне поля</p>
        ) : null}
        {apiOk === false ? (
          <p className="mt-1 text-[11px] text-bad">
            API не отвечает — закройте старые окна и перезапустите run_tester.bat
          </p>
        ) : null}
        {apiOk && apiLlmOk === false ? (
          <p className="mt-1 text-[11px] text-bad">
            API без ИИ-модуля — закройте все окна «Z-Strategy API» и перезапустите run_tester.bat
          </p>
        ) : null}
        {error ? <p className="mt-1 text-[11px] text-bad">{error}</p> : null}
        {!liveMode ? (
          <button type="button" className="primary mt-2" disabled={loading} onClick={runNow}>
            {loading ? 'Считаем…' : 'Обновить'}
          </button>
        ) : null}
      </Panel>

      <Panel title="Данные MOEX 15м">
        <label className="block text-[12px] font-medium text-ink-3">
          CSV
          <input type="text" value={csvPath} onChange={(e) => setCsvPath(e.target.value)} className="mt-1" />
        </label>
        {dataInfo?.exists ? (
          <>
            <p className="mt-2 text-[12px] text-good">
              {String(dataInfo.row_count)} баров · {String(dataInfo.first_ts).slice(0, 10)} …{' '}
              {String(dataInfo.last_ts).slice(0, 10)}
              {dataInfo.age_hours != null ? ` · возраст ${Number(dataInfo.age_hours).toFixed(1)} ч` : ''}
            </p>
            {dataInfo.is_stale ? (
              <p className="mt-1 text-[11px] text-warn">
                Данные старше {String(dataInfo.stale_hours ?? 3)} ч — при старте API и live-расчёте обновятся с MOEX
              </p>
            ) : null}
          </>
        ) : (
          <p className="mt-2 text-[12px] text-bad">Файл не найден</p>
        )}
      </Panel>

      <Panel title="Пресет">
        <select value={preset} onChange={(e) => applyPreset(e.target.value)} className="w-full">
          {Object.keys(presets).map((k) => (
            <option key={k} value={k}>
              {k}
            </option>
          ))}
        </select>
      </Panel>

      <Panel title="Риск">
        {field('Плечо', 'leverage', 0.5)}
        {field('Комиссия %', 'commission', 0.01)}
        <label className="mt-2 flex items-center gap-2 text-[12px] text-ink-2">
          <input
            type="checkbox"
            checked={!!params.compound}
            onChange={(e) => setParams((s) => ({ ...s, compound: e.target.checked }))}
          />
          Капитализация PnL
        </label>
        <label className="mt-2 flex items-center gap-2 text-[12px] text-ink-2">
          <input type="checkbox" checked={compare} onChange={(e) => setCompare(e.target.checked)} />
          Сравнение A / B
        </label>
      </Panel>

    </aside>
  )
}
