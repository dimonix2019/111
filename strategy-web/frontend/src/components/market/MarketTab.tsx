import { useMemo, useState } from 'react'
import type { SimPack, SimResponse, TradeMarker } from '@/types'
import { TimeSeriesChart, type HLine } from '@/components/charts/TimeSeriesChart'
import { PanelErrorBoundary } from '@/components/ui/PanelErrorBoundary'
import {
  DAILY_SIGNAL_MAX,
  lastZscorePoint,
  positionLabel,
  signalsTodayCount,
  zZone,
  zZoneLabel,
} from '@/lib/marketPosition'
import { ZScoreCandlestickChart } from '@/components/charts/ZScoreCandlestickChart'
import { OhlcCandlestickChart } from '@/components/charts/OhlcCandlestickChart'
import { TATN_OHLC_FIELDS, SPREAD_OHLC_FIELDS } from '@/lib/ohlcCandles'
import { filterByPeriod, type MarketPeriod } from '@/lib/marketPeriod'
import { MarketContextPanel } from '@/components/charts/MarketContextPanel'
import { IdleGapsChart } from '@/components/charts/IdleGapsChart'
import { IdlePrecursorsPanel } from '@/components/market/IdlePrecursorsPanel'
import { MobileLastTradeCard } from '@/components/market/MobileLastTradeCard'

export type MarketLiveControls = {
  liveMode: boolean
  setLiveMode: (v: boolean) => void
  online5s: boolean
  setOnline5s: (v: boolean) => void
  loading: boolean
  pending: boolean
  stale: boolean
  lastMs: number | null
  lastUpdatedAt: number | null
  runNow: () => void
  /** Скачать MOEX + пересчёт (как кнопка в сайдбаре). */
  refreshMoex: () => Promise<void>
}

type Props = {
  pack: SimPack
  result: SimResponse
  live: MarketLiveControls
  period: MarketPeriod
  dataStale?: boolean
  dataAgeHours?: number | null
  /** Tailscale /m: только свечной Z-score 15м + краткая сводка */
  mobileMinimal?: boolean
}

function filterMarkers(markers: TradeMarker[], fromTs: number): TradeMarker[] {
  return markers.filter((m) => m.time >= fromTs)
}

function formatUpdatedAt(ms: number | null): string {
  if (ms == null) return '—'
  return new Date(ms).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function ZScoreHero({ pack, live }: { pack: SimPack; live: MarketLiveControls }) {
  const last = lastZscorePoint(pack)
  const zone = last ? zZone(last.z_score, pack.entry) : 'neutral'
  const zoneClass =
    zone === 'long' ? 'z-hero--long' : zone === 'short' ? 'z-hero--short' : 'z-hero--neutral'
  const q = pack.latest_quote
  const prices =
    q?.tatn_close != null && q?.tatnp_close != null
      ? `TATN ${q.tatn_close.toFixed(1)} · TATNP ${q.tatnp_close.toFixed(1)}`
      : null

  return (
    <div className={`z-hero ${zoneClass}`}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-[11px] font-semibold uppercase tracking-[0.2em] text-ink-3">Z-score сейчас</p>
          <p className="z-hero__value tabular-nums">{last ? last.z_score.toFixed(3) : '—'}</p>
          <p className="mt-1 text-[13px] text-ink-2">
            Спред {last ? `${last.spread_percent.toFixed(3)}%` : '—'}
            {prices ? ` · ${prices}` : ''}
          </p>
        </div>
        <div className="text-right">
          <p className="text-[12px] font-semibold text-ink-1">{last ? zZoneLabel(zone) : '—'}</p>
          <p className="mt-1 text-[11px] text-ink-3">Порог входа ±{pack.entry.toFixed(2)}</p>
          <p className="text-[11px] text-ink-3">Позиция: {positionLabel(pack)}</p>
        </div>
      </div>
      <p className="mt-2 text-[11px] text-ink-3">
        {live.online5s ? 'Онлайн 5с: вкл' : 'Онлайн 5с: выкл'}
        {live.loading ? ' · обновление…' : ''}
        {live.lastUpdatedAt != null ? ` · снимок ${formatUpdatedAt(live.lastUpdatedAt)}` : ''}
        {live.lastMs != null ? ` · ${live.lastMs} мс` : ''}
      </p>
    </div>
  )
}

function MobileMarketSummary({
  pack,
  result,
  live,
  dataStale,
  dataAgeHours,
  onRefresh,
  refreshDisabled,
  moexBusy,
}: {
  pack: SimPack
  result: SimResponse
  live: MarketLiveControls
  dataStale?: boolean
  dataAgeHours?: number | null
  onRefresh: () => void
  refreshDisabled: boolean
  moexBusy: boolean
}) {
  const last = lastZscorePoint(pack)
  const zone = last ? zZone(last.z_score, pack.entry) : 'neutral'
  const zoneClass =
    zone === 'long' ? 'z-hero--long' : zone === 'short' ? 'z-hero--short' : 'z-hero--neutral'
  const q = pack.latest_quote
  const prices =
    q?.tatn_close != null && q?.tatnp_close != null
      ? `TATN ${q.tatn_close.toFixed(1)} · TATNP ${q.tatnp_close.toFixed(1)}`
      : null
  const signalsToday = signalsTodayCount(pack.trade_markers)
  const stale = live.stale || !!dataStale
  const loadedAt = pack.market_context?.as_of_display ?? result.hq.last_ts.slice(0, 16).replace('T', ' ')
  const dataSource = stale
    ? 'Не актуально'
    : dataAgeHours != null && dataAgeHours > 3
      ? `CSV · ${dataAgeHours.toFixed(0)} ч`
      : 'MOEX / CSV'

  const statusParts: string[] = [
    `Данные: ${dataSource}`,
    loadedAt,
    `Онлайн 5с: ${live.online5s ? 'вкл' : 'выкл'}`,
  ]
  if (live.lastUpdatedAt != null) statusParts.push(`снимок ${formatUpdatedAt(live.lastUpdatedAt)}`)
  if (live.lastMs != null) statusParts.push(`${live.lastMs} мс`)

  return (
    <div className={`z-hero market-mobile-summary ${zoneClass} space-y-2 p-3`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-[14px] font-bold text-ink-1">Сводка · Z-score</span>
        <button
          type="button"
          className="market-action-btn market-action-btn--primary !py-1.5 !text-[11px]"
          disabled={refreshDisabled}
          onClick={onRefresh}
        >
          {moexBusy ? 'MOEX…' : live.loading ? '…' : 'MOEX'}
        </button>
      </div>

      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="z-hero__value tabular-nums leading-none">{last ? last.z_score.toFixed(3) : '—'}</p>
          <p className="mt-1 text-[12px] text-ink-2">
            Спред {last ? `${last.spread_percent.toFixed(3)}%` : '—'}
            {prices ? ` · ${prices}` : ''}
          </p>
        </div>
        <div className="text-right text-[11px]">
          <p className="font-semibold text-ink-1">{last ? zZoneLabel(zone) : '—'}</p>
          <p className="mt-0.5 text-ink-3">Вход ±{pack.entry.toFixed(2)} · {positionLabel(pack)}</p>
        </div>
      </div>

      <p className="text-[11px] text-warn">
        Сигналы сегодня: {signalsToday} / {DAILY_SIGNAL_MAX}
      </p>
      {pack.stats.unrealized_pnl_rub !== 0 ? (
        <p className="text-[11px] text-purple-300/90">
          Нереализ. PnL: {pack.stats.unrealized_pnl_rub.toLocaleString('ru-RU')} ₽
        </p>
      ) : null}

      <p className="text-[10px] leading-snug text-ink-3">{statusParts.join(' · ')}</p>
      {stale ? (
        <p className="text-[10px] text-orange-300/90">Показан последний успешный снимок</p>
      ) : null}
      {live.loading ? <p className="text-[10px] text-warn">Обновление…</p> : null}
    </div>
  )
}

function MarketsSummaryStrip({
  pack,
  result,
  live,
  dataStale,
  dataAgeHours,
  onRefresh,
  refreshDisabled,
  moexBusy,
  mobileMinimal,
}: {
  pack: SimPack
  result: SimResponse
  live: MarketLiveControls
  dataStale?: boolean
  dataAgeHours?: number | null
  onRefresh: () => void
  refreshDisabled: boolean
  moexBusy: boolean
  mobileMinimal?: boolean
}) {
  if (mobileMinimal) {
    return (
      <MobileMarketSummary
        pack={pack}
        result={result}
        live={live}
        dataStale={dataStale}
        dataAgeHours={dataAgeHours}
        onRefresh={onRefresh}
        refreshDisabled={refreshDisabled}
        moexBusy={moexBusy}
      />
    )
  }

  const last = lastZscorePoint(pack)
  const signalsToday = signalsTodayCount(pack.trade_markers)
  const stale = live.stale || !!dataStale
  const loadedAt = pack.market_context?.as_of_display ?? result.hq.last_ts.slice(0, 16).replace('T', ' ')
  const dataSource = stale
    ? 'Не актуально (последний снимок)'
    : dataAgeHours != null && dataAgeHours > 3
      ? 'Кэш CSV'
      : 'MOEX / CSV backtest'

  const feedStatusParts = [
    `Данные: ${dataSource}`,
    loadedAt,
    `Онлайн 5с: ${live.online5s ? 'вкл' : 'выкл'}`,
  ]
  if (live.lastUpdatedAt != null) feedStatusParts.push(`обновлено ${formatUpdatedAt(live.lastUpdatedAt)}`)
  if (live.lastMs != null) feedStatusParts.push(`расчёт ${live.lastMs} мс`)

  return (
    <div className="market-controls surface-inner relative z-20 space-y-2 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-[14px] font-bold text-ink-1">Сводка</span>
        <button
          type="button"
          className="market-action-btn market-action-btn--primary"
          disabled={refreshDisabled}
          onClick={onRefresh}
        >
          {moexBusy ? 'Загрузка MOEX…' : live.loading ? 'Обновление…' : 'Обновить MOEX'}
        </button>
      </div>
      <p className="text-[13px] text-ink-2">
        Z: {last ? last.z_score.toFixed(2) : '—'} &nbsp;|&nbsp; Спред:{' '}
        {last ? `${last.spread_percent.toFixed(2)}%` : '—'}
      </p>
      <p className="text-[12px] text-cyan-200/80">Позиция: {positionLabel(pack)}</p>
      <p className="text-[12px] text-warn">
        Сигналы сегодня (бэктест): {signalsToday} / {DAILY_SIGNAL_MAX}
      </p>
      {pack.stats.unrealized_pnl_rub !== 0 ? (
        <p className="text-[12px] text-purple-300/90">
          Нереализ. PnL (симуляция): {pack.stats.unrealized_pnl_rub.toLocaleString('ru-RU')} ₽
        </p>
      ) : null}
      <p className="text-[11px] text-ink-3">{feedStatusParts.join(' · ')}</p>
      {stale ? (
        <p className="text-[11px] text-orange-300/90">
          Показан последний успешный снимок — текущее обновление не завершено или параметры изменены.
        </p>
      ) : null}
    </div>
  )
}

function RealtimeControls({ live }: { live: MarketLiveControls }) {
  const status = live.loading
    ? 'Статус: обновление MOEX…'
    : live.pending
      ? 'Статус: ожидание пересчёта параметров…'
      : live.online5s
        ? 'Статус: онлайн 5с — опрос MOEX'
        : 'Статус: актуально'

  return (
    <div className="surface-inner space-y-3 p-3">
      <div className="flex items-center justify-between gap-2">
        <span className="text-[13px] font-bold text-ink-1">Онлайн 5с</span>
        <button
          type="button"
          className={`market-action-btn ${live.online5s ? 'market-action-btn--on' : ''}`}
          onClick={() => live.setOnline5s(!live.online5s)}
        >
          {live.online5s ? 'ВКЛ' : 'ВЫКЛ'}
        </button>
      </div>
      <p className="text-[12px] text-cyan-200/75">
        Каждые 5 с — загрузка с MOEX и полный пересчёт (как SignalForegroundService в Android). Пока идёт
        предыдущий запрос, новый не ставится.
      </p>
      <p className="text-[12px] text-ink-3">{status}</p>
      {live.lastUpdatedAt != null ? (
        <p className="text-[11px] text-ink-3">Последний успешный снимок: {formatUpdatedAt(live.lastUpdatedAt)}</p>
      ) : null}

      <div className="border-t border-panel-border-soft pt-3">
        <div className="flex items-center justify-between gap-2">
          <span className="text-[13px] font-bold text-ink-1">Live параметры</span>
          <button
            type="button"
            className={`market-action-btn ${live.liveMode ? 'market-action-btn--on' : ''}`}
            onClick={() => live.setLiveMode(!live.liveMode)}
          >
            {live.liveMode ? 'ON' : 'OFF'}
          </button>
        </div>
        <p className="mt-1 text-[12px] text-cyan-200/75">Пересчёт ~0,4 с при изменении Entry/Exit в сайдбаре</p>
      </div>

      {!live.online5s && !live.liveMode ? (
        <button type="button" className="market-action-btn w-full" disabled={live.loading} onClick={live.runNow}>
          Принудительный пересчёт
        </button>
      ) : null}
    </div>
  )
}

export function MarketTab({ pack, result, live, period, dataStale, dataAgeHours, mobileMinimal }: Props) {
  const [testToast, setTestToast] = useState<string | null>(null)
  const [moexBusy, setMoexBusy] = useState(false)

  const zFiltered = useMemo(() => filterByPeriod(pack.zscore, period), [pack.zscore, period])
  const fromTs = zFiltered[0]?.time ?? 0
  const equityFiltered = useMemo(
    () => (fromTs ? pack.equity.filter((e) => e.time >= fromTs) : pack.equity),
    [pack.equity, fromTs],
  )
  const markers = useMemo(() => filterMarkers(pack.trade_markers, fromTs), [pack.trade_markers, fromTs])

  const hlines: HLine[] = useMemo(
    () => [
      { value: pack.entry, color: 'rgba(212,184,106,0.55)', title: `+Entry ${pack.entry}` },
      { value: -pack.entry, color: 'rgba(212,184,106,0.55)', title: `-Entry ${pack.entry}` },
      { value: pack.exit_z, color: 'rgba(148,163,184,0.35)', title: `+Exit ${pack.exit_z}` },
      { value: -pack.exit_z, color: 'rgba(148,163,184,0.35)', title: `-Exit ${pack.exit_z}` },
    ],
    [pack.entry, pack.exit_z],
  )

  function onTestSignal() {
    const body = `Тест сигнала (веб). Пороги Z: вход ±${pack.entry.toFixed(2)}, выход ±${pack.exit_z.toFixed(2)}.`
    setTestToast(body)
    window.setTimeout(() => setTestToast(null), 5000)
  }

  async function onRefresh() {
    if (moexBusy || live.loading) return
    setMoexBusy(true)
    try {
      await live.refreshMoex()
    } finally {
      setMoexBusy(false)
    }
  }

  const refreshDisabled = moexBusy || live.loading

  return (
    <PanelErrorBoundary title="Рынок">
      <div className="space-y-3">
        {mobileMinimal ? (
          <>
            <MarketsSummaryStrip
              pack={pack}
              result={result}
              live={live}
              dataStale={dataStale}
              dataAgeHours={dataAgeHours}
              onRefresh={() => void onRefresh()}
              refreshDisabled={refreshDisabled}
              moexBusy={moexBusy}
              mobileMinimal
            />
            <MobileLastTradeCard pack={pack} />
          </>
        ) : null}

        <div className="market-tab-charts space-y-3">
          <ZScoreCandlestickChart
            title="Z-score · 15м свечи"
            height={mobileMinimal ? 340 : 280}
            data={zFiltered}
            markers={markers}
            trades={pack.trades}
            equity={equityFiltered}
            hlines={hlines}
            liveMode={live.liveMode}
            hideKeyboardHint={mobileMinimal}
          />

          {mobileMinimal && pack.market_context?.metrics?.length ? (
            <MarketContextPanel
              ctx={pack.market_context}
              metricIds={['z_score', 'spread', 'hold_hours', 'idle_days']}
              showBanner={false}
              refreshKey={live.lastUpdatedAt}
              stack
            />
          ) : null}

          {!mobileMinimal ? (
            <>
              <OhlcCandlestickChart
                title="TATN · 15м свечи (MOEX)"
                height={260}
                rows={zFiltered}
                fields={TATN_OHLC_FIELDS}
                markers={markers}
                trades={pack.trades}
                equity={equityFiltered}
                showDrawdownOverlay
                liveMode={live.liveMode}
                showVolume
              />

              <OhlcCandlestickChart
                title="Spread % · 15м OHLC"
                height={240}
                rows={zFiltered}
                fields={SPREAD_OHLC_FIELDS}
                markers={markers}
                trades={pack.trades}
                equity={equityFiltered}
                showDrawdownOverlay
                liveMode={live.liveMode}
                showVolume={false}
              />

              <TimeSeriesChart
                title="График 4: Z-score спрэда · 15м"
                color="#8eb4c4"
                valueFormat="float"
                height={228}
                data={zFiltered.map((z) => ({ time: z.time, value: z.z_score }))}
                markers={markers}
                trades={pack.trades}
                equity={equityFiltered}
                showDrawdownOverlay
                hlines={hlines}
              />

              <TimeSeriesChart
                title="График 2: spread = (TATN / TATNP - 1) * 100"
                color="#5ec492"
                valueFormat="float"
                height={208}
                data={zFiltered.map((z) => ({ time: z.time, value: z.spread_percent }))}
                markers={markers}
                trades={pack.trades}
                equity={equityFiltered}
                showDrawdownOverlay
              />

              {pack.market_context?.metrics?.length ? (
                <MarketContextPanel
                  ctx={pack.market_context}
                  metricIds={['z_score', 'spread', 'hold_hours', 'idle_days']}
                  showBanner={false}
                  refreshKey={live.lastUpdatedAt}
                />
              ) : null}

              {pack.idle_gaps?.histogram?.length ? <IdleGapsChart data={pack.idle_gaps} /> : null}

              {pack.idle_precursors ? <IdlePrecursorsPanel data={pack.idle_precursors} /> : null}
            </>
          ) : null}
        </div>

        {!mobileMinimal ? <ZScoreHero pack={pack} live={live} /> : null}

        {!mobileMinimal ? (
          <MarketsSummaryStrip
            pack={pack}
            result={result}
            live={live}
            dataStale={dataStale}
            dataAgeHours={dataAgeHours}
            onRefresh={() => void onRefresh()}
            refreshDisabled={refreshDisabled}
            moexBusy={moexBusy}
          />
        ) : null}

        {!mobileMinimal ? (
          <>
            <h2 className="text-lg font-bold text-ink-1">TATN / TATNP (MOEX ISS)</h2>

            <p className="text-[12px] text-pink-300/90">
              Пороги сделок (Портфель): вход ±{pack.entry.toFixed(2)}, выход ±{pack.exit_z.toFixed(2)}
            </p>

            <div className="grid gap-2 sm:grid-cols-2">
              <button type="button" className="market-action-btn w-full" onClick={onTestSignal}>
                Тест
              </button>
              <button
                type="button"
                className="market-action-btn w-full"
                disabled={refreshDisabled}
                onClick={() => void onRefresh()}
              >
                {moexBusy ? 'MOEX…' : live.loading ? 'Считаем…' : 'Обновить сейчас'}
              </button>
            </div>

            {testToast ? (
              <p className="rounded-xl border border-panel-border-soft bg-[rgba(8,16,28,0.5)] px-3 py-2 text-[11px] text-ink-2">
                {testToast}
              </p>
            ) : null}

            <RealtimeControls live={live} />
          </>
        ) : null}
      </div>
    </PanelErrorBoundary>
  )
}
