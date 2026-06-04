import { useCallback, useEffect, useState, type ChangeEvent, type FormEvent } from 'react'
import type { Presets, SimParams, SimResponse } from '@/types'
import { downloadMoex, getDataStatus, getHealth, getPresets } from '@/lib/api'
import { getLlmStatus } from '@/lib/llmApi'
import { useLiveSim } from '@/hooks/useLiveSim'
import { useOnlineMoexPoll } from '@/hooks/useOnlineMoexPoll'
import type { MarketLiveControls } from '@/components/market/MarketTab'
import {
  defaultSimParams,
  loadPersistedSimSettings,
  savePersistedSimSettings,
} from '@/lib/simSettingsStorage'
import { Panel } from '@/components/ui/Panel'
import { MobileSettingsBar } from '@/components/layout/MobileSettingsBar'

type Props = {
  onResult: (r: SimResponse) => void
  onLoading?: (loading: boolean) => void
  onLiveControls?: (ctrl: MarketLiveControls) => void
  onSimContext?: (ctx: { params: SimParams; csvPath: string; compare: boolean }) => void
  marketPollActive?: boolean
  paramsPatch?: Partial<SimParams> | null
  paramsPatchTick?: number
  className?: string
  /** /m — узкая панель, только Rolling 30д */
  mobileCompact?: boolean
}

function parseNum(raw: string): number | null {
  const v = parseFloat(raw.replace(',', '.'))
  return Number.isFinite(v) ? v : null
}

export function Sidebar({
  onResult,
  onLoading,
  onLiveControls,
  onSimContext,
  marketPollActive = false,
  paramsPatch,
  paramsPatchTick = 0,
  className,
  mobileCompact = false,
}: Props) {
  const saved = loadPersistedSimSettings()
  const [csvPath, setCsvPath] = useState(saved.csvPath)
  const [dataInfo, setDataInfo] = useState<Record<string, unknown> | null>(null)
  const [presets, setPresets] = useState<Presets>({})
  const [preset, setPreset] = useState(saved.preset)
  const [compare, setCompare] = useState(saved.compare)
  const [liveMode, setLiveMode] = useState(saved.liveMode)
  const [online5s, setOnline5s] = useState(false)
  const [apiOk, setApiOk] = useState<boolean | null>(null)
  const [apiLlmOk, setApiLlmOk] = useState<boolean | null>(null)
  const [moexBusy, setMoexBusy] = useState(false)
  const [params, setParams] = useState<SimParams>(saved.params)

  const { loading, error, lastMs, lastUpdatedAt, pending, stale, runNow } = useLiveSim({
    csvPath,
    compare,
    params,
    enabled: liveMode,
    onResult,
  })

  useOnlineMoexPoll({
    enabled: marketPollActive && online5s,
    loading,
    csvPath,
    runSim: runNow,
  })

  const onDownloadMoex = useCallback(async () => {
    setMoexBusy(true)
    try {
      await downloadMoex(csvPath)
      void getDataStatus(csvPath).then(setDataInfo)
      runNow()
    } catch (e) {
      alert(e instanceof Error ? e.message : String(e))
    } finally {
      setMoexBusy(false)
    }
  }, [csvPath, runNow])

  useEffect(() => {
    onLoading?.(loading)
  }, [loading, onLoading])

  useEffect(() => {
    onSimContext?.({ params, csvPath, compare })
  }, [params, csvPath, compare, onSimContext])

  useEffect(() => {
    onLiveControls?.({
      liveMode,
      setLiveMode,
      online5s,
      setOnline5s,
      loading,
      pending,
      stale,
      lastMs,
      lastUpdatedAt,
      runNow,
      refreshMoex: onDownloadMoex,
    })
  }, [
    onLiveControls,
    liveMode,
    online5s,
    loading,
    pending,
    stale,
    lastMs,
    lastUpdatedAt,
    runNow,
    onDownloadMoex,
  ])

  useEffect(() => {
    if (!paramsPatch || paramsPatchTick === 0) return
    setParams((s) => ({ ...s, ...paramsPatch }))
    setPreset('Свои')
  }, [paramsPatch, paramsPatchTick])

  useEffect(() => {
    if (!mobileCompact) return
    setParams((s) => (s.z_mode === 'rolling30' ? s : { ...s, z_mode: 'rolling30' }))
    setCompare(false)
  }, [mobileCompact])

  useEffect(() => {
    savePersistedSimSettings({ params, csvPath, compare, liveMode, preset })
  }, [params, csvPath, compare, liveMode, preset])

  useEffect(() => {
    void getPresets().then(setPresets)
    void getHealth()
      .then(async (h) => {
        setApiOk(!!h.ok)
        if (h.features?.llm) {
          try {
            setApiLlmOk((await getLlmStatus()).ok)
          } catch {
            setApiLlmOk(false)
          }
        } else setApiLlmOk(false)
      })
      .catch(() => {
        setApiOk(false)
        setApiLlmOk(null)
      })
  }, [])

  useEffect(() => {
    void getDataStatus(csvPath).then(setDataInfo).catch(() => setDataInfo(null))
  }, [csvPath, loading])

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
      ...(p.z_mode ? { z_mode: p.z_mode } : {}),
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

  const zModePanel = (
    <Panel title="Z-score · режим теста">
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
          <span className="mt-0.5 block text-[10px] text-ink-3">Legacy APK</span>
        </span>
      </label>
    </Panel>
  )

  const pyramidPanel = (
    <Panel title="Пирамидинг">
      <p className="mb-2 text-[11px] text-ink-3">
        Одна добавка номинала за сделку при углублении |Z| (как в APK). 0 ₽ — выключено.
      </p>
      {field('Добавка номинала ₽', 'pyramid_add_notional', 10_000)}
      {field('Порог |Z| (depth)', 'pyramid_z_depth', 0.1)}
    </Panel>
  )

  const protectionPanel = (
    <Panel title="Защита">
      <p className="mb-2 text-[11px] text-ink-3">Stop-loss, spread-фильтры, DD halt, slippage</p>
      {field('Slippage (spread pts)', 'slippage', 0.01)}
      {field('Max loss spread pts', 'max_loss_spread', 0.05)}
      {field('Max loss ₽', 'max_loss_rub', 1000)}
      {field('Min spread %', 'min_spread', 0.01)}
      {field('Max spread %', 'max_spread', 0.01)}
      {field('Entry Z buffer', 'entry_z_buffer', 0.05)}
      {field('Max DD halt ₽', 'max_dd_halt_rub', 1000)}
      {field('Max DD halt %', 'max_dd_halt_pct', 1)}
      <label className="mt-2 flex items-center gap-2 text-[12px] text-ink-2">
        <input
          type="checkbox"
          checked={!!params.oos_enabled}
          onChange={(e) => setParams((s) => ({ ...s, oos_enabled: e.target.checked }))}
        />
        OOS train/test в simulate
      </label>
      {params.oos_enabled ? field('Train ratio', 'oos_train_ratio', 0.05) : null}
      <button
        type="button"
        className="mt-2 text-[11px] text-ink-3 underline"
        onClick={() => setParams(defaultSimParams())}
      >
        Сбросить защиту
      </button>
    </Panel>
  )

  return mobileCompact ? (
    <MobileSettingsBar
      params={params}
      liveMode={liveMode}
      loading={loading}
      moexBusy={moexBusy}
      dataAgeHours={dataInfo?.age_hours != null ? Number(dataInfo.age_hours) : null}
      dataStale={!!dataInfo?.is_stale}
      onParamChange={(key, value) => setParams((s) => ({ ...s, [key]: value }))}
      onLiveModeChange={setLiveMode}
      onRefreshMoex={() => void onDownloadMoex()}
      onRunNow={runNow}
    />
  ) : (
    <aside
      className={`scrollbar-thin flex h-full min-h-0 w-[320px] shrink-0 flex-col gap-3 overflow-x-hidden overflow-y-auto pb-2 pr-1${className ? ` ${className}` : ''}`}
    >
      {zModePanel}
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
      {pyramidPanel}
      {protectionPanel}
      <Panel title="Live-режим">
        <label className="flex items-center gap-2 text-[12px] text-ink-2">
          <input type="checkbox" checked={liveMode} onChange={(e) => setLiveMode(e.target.checked)} />
          Пересчёт при изменении параметров
        </label>
        <p className={`mt-2 text-[11px] ${loading || pending ? 'text-warn' : 'text-ink-3'}`}>{statusLine}</p>
        {stale ? (
          <p className="mt-1 text-[11px] text-warn">На экране старые данные — дождитесь расчёта</p>
        ) : null}
        {apiOk === false ? (
          <p className="mt-1 text-[11px] text-bad">API не отвечает — перезапустите run_tester.bat</p>
        ) : null}
        {apiOk && apiLlmOk === false ? (
          <p className="mt-1 text-[11px] text-warn">LLM недоступен (чат на вкладке Сделки может не работать)</p>
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
              {dataInfo.age_hours != null ? ` · ${Number(dataInfo.age_hours).toFixed(1)} ч` : ''}
            </p>
            {dataInfo.is_stale ? (
              <p className="mt-1 text-[11px] text-warn">Данные устарели — догрузите MOEX</p>
            ) : null}
          </>
        ) : (
          <p className="mt-2 text-[12px] text-bad">Файл не найден</p>
        )}
        <button type="button" className="primary mt-2 w-full" disabled={moexBusy} onClick={() => void onDownloadMoex()}>
          {moexBusy ? 'Загрузка…' : 'Скачать MOEX 255д'}
        </button>
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
