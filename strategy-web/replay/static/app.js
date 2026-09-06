/** MOEX Bar Replay — UI + TradingView chart + localStorage */
(function () {
  const LS = {
    startDate: 'moexReplay.startDate',
    endDate: 'moexReplay.endDate',
    entry: 'moexReplay.entry',
    exit: 'moexReplay.exit',
    period: 'moexReplay.period',
    csv: 'moexReplay.csv',
    tradesScrollLeft: 'moexReplay.tradesScrollLeft',
    tradesScrollTop: 'moexReplay.tradesScrollTop',
    tradesSummaryScrollTop: 'moexReplay.tradesSummaryScrollTop',
    riskCompareScrollTop: 'moexReplay.riskCompareScrollTop',
    monthlyPnlScrollTop: 'moexReplay.monthlyPnlScrollTop',
    zHeatmapScrollTop: 'moexReplay.zHeatmapScrollTop',
    tradesScrollHeight: 'moexReplay.tradesScrollHeight',
    riskCompareHeight: 'moexReplay.riskCompareHeight',
    monthlyPnlHeight: 'moexReplay.monthlyPnlHeight',
    zHeatmapHeight: 'moexReplay.zHeatmapHeight',
    tradeColumns: 'moexReplay.tradeColumns',
    tradesPanelWidth: 'moexReplay.tradesPanelWidth',
    pnlPaneHeight: 'moexReplay.pnlPaneHeight',
    deltaPaneHeight: 'moexReplay.deltaPaneHeight',
    deltaPaneHidden: 'moexReplay.deltaPaneHidden',
    pnlPaneHidden: 'moexReplay.pnlPaneHidden',
    tradesTableHidden: 'moexReplay.tradesTableHidden',
    riskCompareHidden: 'moexReplay.riskCompareHidden',
    tradesSummaryHidden: 'moexReplay.tradesSummaryHidden',
    monthlyPnlHidden: 'moexReplay.monthlyPnlHidden',
    zHeatmapHidden: 'moexReplay.zHeatmapHidden',
    chartsHidden: 'moexReplay.chartsHidden',
    hmEntryMin: 'moexReplay.hmEntryMin',
    hmEntryMax: 'moexReplay.hmEntryMax',
    hmExitMin: 'moexReplay.hmExitMin',
    hmExitMax: 'moexReplay.hmExitMax',
    hmStep: 'moexReplay.hmStep',
    hmBand: 'moexReplay.hmBand',
    hmSpreadEnterWide: 'moexReplay.hmSpreadEnterWide',
    hmSpreadExitWide: 'moexReplay.hmSpreadExitWide',
    hmSpreadEnterNarrow: 'moexReplay.hmSpreadEnterNarrow',
    hmSpreadExitNarrow: 'moexReplay.hmSpreadExitNarrow',
    /** One-shot: migrate saved Short enter 6.2 → 6.1. */
    hmSpreadEnterWideV61: 'moexReplay.hmSpreadEnterWideV61',
    hmSGridDefaultsV1: 'moexReplay.hmSGridDefaultsV1',
    hmSGridDefaultsNarrowV1: 'moexReplay.hmSGridDefaultsNarrowV1',
    hmSGridDefaultsWideV1: 'moexReplay.hmSGridDefaultsWideV1',
    pnlMode: 'moexReplay.pnlMode',
    notionalRub: 'moexReplay.notionalRub',
    slippageSpreadPts: 'moexReplay.slippageSpreadPts',
    tradeSortCol: 'moexReplay.tradeSortCol',
    tradeSortDir: 'moexReplay.tradeSortDir',
    riskFilter: 'moexReplay.riskFilter',
    srcFilter: 'moexReplay.srcFilter',
    takeProfit: 'moexReplay.takeProfit',
    holdNoTrendDays: 'moexReplay.holdNoTrendDays',
    holdLosingDays: 'moexReplay.holdLosingDays',
    riskExit: 'moexReplay.riskExit', // legacy single preset
    compound: 'moexReplay.compound',
    columnTips: 'moexReplay.columnTips',
    barsSource: 'moexReplay.barsSource',
    /** Legacy migrations (as_live toggle removed from Testing UI). */
    barsSourceFinalDefaultV1: 'moexReplay.barsSourceFinalDefaultV1',
    barsSourceAsLiveDefaultV2: 'moexReplay.barsSourceAsLiveDefaultV2',
    /** One-shot: force Testing to final CSV; ignore old as_live preference. */
    barsSourceFinalOnlyV3: 'moexReplay.barsSourceFinalOnlyV3',
    edgeMode: 'moexReplay.edgeMode',
    addonMode27: 'moexReplay.addonMode27',
    extremeAddonMode: 'moexReplay.extremeAddonMode',
    baseMode: 'moexReplay.baseMode',
    weekendTrading: 'moexReplay.weekendTrading',
    transitionSwingMode: 'moexReplay.transitionSwingMode',
    adaptiveCorridorMode: 'moexReplay.adaptiveCorridorMode',
    shelfFloorCeilingMode: 'moexReplay.shelfFloorCeiling',
    testShowCascade: 'testShowCascade',
    testShowZoneBands: 'testShowZoneBands',
    testShowWideShelves: 'testShowWideShelves',
    tipRegimeZ: 'moexReplay.tipRegimeZ',
    tipSpreadLevels: 'moexReplay.tipSpreadLevels',
  };

  const TRADES_PANEL_DEFAULT = 300;
  const TRADES_PANEL_MIN = 180;
  const CHART_MIN = 280;
  const SPLITTER_WIDTH = 6;

  const PNL_CHART_DEFAULT = 96;
  const PNL_CHART_MIN = 48;
  const DELTA_CHART_DEFAULT = 96;
  const DELTA_CHART_MIN = 48;
  const Z_PANE_MIN = 120;
  const CHART_SPLITTER_HEIGHT = 6;

  const TRADES_SCROLL_HEIGHT_DEFAULT = 240;
  const TRADES_SCROLL_HEIGHT_MIN = 72;
  const RISK_COMPARE_HEIGHT_DEFAULT = 140;
  const RISK_COMPARE_HEIGHT_MIN = 48;
  const MONTHLY_PNL_HEIGHT_DEFAULT = 200;
  const MONTHLY_PNL_HEIGHT_MIN = 88;
  const Z_HEATMAP_HEIGHT_DEFAULT = 240;
  const Z_HEATMAP_HEIGHT_MIN = 120;
  const TRADES_SUMMARY_MIN = 120;

  /** Prod tip1m spread-% levels (live/constants.py) — UI mark + fixed other band. */
  const HM_PROD_WIDE = { enter: 6.1, exit: 5.8 };
  const HM_PROD_NARROW = { enter: 3.2, exit: 4.0 };
  const HM_S_WIDE_DEFAULTS = { entryMin: 5.6, entryMax: 7.0, exitMin: 5.0, exitMax: 6.2, step: 0.2 };
  const HM_S_NARROW_DEFAULTS = { entryMin: 2.4, entryMax: 3.8, exitMin: 3.4, exitMax: 4.8, step: 0.2 };
  const HM_Z_DEFAULTS = { entryMin: 0.5, entryMax: 2.5, exitMin: 0.3, step: 0.1 };

  let allPoints = [];
  /** Полная серия по CSV (для быстрых пресетов start без повторного /api/bars). */
  const barsCacheByCsv = Object.create(null);
  let engine = null;
  let chart = null;
  let playing = false;
  let speed = 1;
  let visibleDays = 30;
  /** После смены параметров / «Всё» — подогнать график под весь период симуляции. */
  let pendingFitFullChart = false;
  /** Пока график скрыт — откладываем тяжёлую перерисовку; при показе — полный кадр. */
  let pendingChartRepaint = false;
  /** Кэш бейджей режима/зоны/каскада в строке статуса (по дню курсора). */
  let statusBadgeCache = { key: '', regimeHtml: '', zoneHtml: '', cascadeHtml: '' };
  /** Кэш тяжёлых серий для light-шагов ±1 / Play. */
  let uiSeriesCache = {
    cursor: -1,
    edgesLen: -1,
    rangeStart: -1,
    rangeEnd: -1,
    markers: null,
    equityTotal: null,
    equity: null,
    deltaPp: null,
    trades: null,
    cascadeVlines: null,
    cascadeCursorKey: '',
    pnlMode: null,
  };
  let tradesRefreshTimer = null;
  /** Precomputed column distributions for the filtered trades table (hover tip). */
  let tradeMetricDists = {};
  /** Distributions of visible bars for status-bar Z / Спред hover tips. */
  let statusBarMetricDists = { z: null, spread: null, cursor: -1 };
  let tradeMetricTipEl = null;
  let tradeMetricTipHideTimer = null;
  let timer = null;
  let scrubbing = false;
  let scrubRaf = 0;
  let scrubPendingFrac = null;
  let visibleTradeColumns = [...TRADE_COLUMNS_DEFAULT];
  let scrollRestored = false;
  let summaryScrollRestored = false;
  let riskCompareScrollRestored = false;
  let monthlyPnlScrollRestored = false;
  let zHeatmapScrollRestored = false;
  let zHeatmapCache = { key: '', cells: null, grid: null, inFlightKey: '' };
  let zHeatmapInFlightSince = 0;
  /** Reuse typed arrays across heatmap recalcs (same bars / endIdx). */
  let heatmapSeriesCache = { endIdx: -1, len: 0, series: null };
  let zHeatmapTimer = null;
  /** Server tip-1m sim (Mode B) for Testing «касание 1м». */
  let tipSimCache = { key: '', rows: null, meta: null, summary: null };
  /** Коридор S с /api/trade/desk — запасной источник, если bars1m ещё без corridor. */
  let testDeskCorridor = null;
  let testDeskCorridorTs = 0;
  /** Узкий коридор / «адапт. коридор» как сделка — выкл. (не меняет входы AUTO). */
  const TEST_DRAW_CORRIDOR = false;
  /** Широкая полка: жёлтый пунктир на графике Теста, не входы AUTO. */
  const TEST_DRAW_WIDE_CORRIDOR = true;
  let causalHistCache = { fp: '', hist: [] };

  function historyFromCausalShelves(shelves, daily, cursorDay, rangeLo) {
    const list = Array.isArray(shelves) ? shelves : [];
    const days = Array.isArray(daily) ? daily : [];
    const lo = String(rangeLo || '').slice(0, 10);
    const shelfFp = list.map((s) => `${s && s.from}|${s && s.to}|${s && s.lo}|${s && s.hi}`).join(',');
    const fp = `${lo}|${cursorDay}|${shelfFp}|${days.length}`;
    if (causalHistCache.fp === fp) return causalHistCache.hist;
    const hist = [];
    const dayLo = /^\d{4}-\d{2}-\d{2}$/.test(lo) ? lo : '0000-01-01';
    list.forEach((s, i) => {
      const from = String(s && s.from || '').slice(0, 10);
      const to = String(s && s.to || '').slice(0, 10);
      const slo = Number(s && s.lo);
      const shi = Number(s && s.hi);
      if (!from || !to || !Number.isFinite(slo) || !Number.isFinite(shi)) return;
      const visFrom = from < dayLo ? dayLo : from;
      const visTo = to > cursorDay ? cursorDay : to;
      if (visFrom > visTo || visFrom > cursorDay) return;
      let n = 0;
      days.forEach((row) => {
        const d = String((row && row.date) || row || '').slice(0, 10);
        if (!d || d < dayLo || d > cursorDay) return;
        if (from <= d && d <= to) {
          hist.push({
            date: d,
            lo: slo,
            hi: shi,
            phase: 'formed',
            segment_id: i + 1,
            since_date: from,
          });
          n += 1;
        }
      });
      if (!n) {
        hist.push({
          date: visFrom,
          lo: slo,
          hi: shi,
          phase: 'formed',
          segment_id: i + 1,
          since_date: from,
        });
        if (visTo !== visFrom) {
          hist.push({
            date: visTo,
            lo: slo,
            hi: shi,
            phase: 'formed',
            segment_id: i + 1,
            since_date: from,
          });
        }
      }
    });
    causalHistCache = { fp, hist };
    return hist;
  }

  function wideCorridorAtCursor(pack, barLabel, rangeLo) {
    if (!pack) return null;
    const cursorDay = String(barLabel || '').replace('T', ' ').slice(0, 10);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(cursorDay)) return null;
    if (pack.causal && pack.by_date) {
      const by = pack.by_date;
      let key = Object.prototype.hasOwnProperty.call(by, cursorDay) ? cursorDay : '';
      if (!key) {
        let best = '';
        for (const d of Object.keys(by)) {
          if (d <= cursorDay && d > best) best = d;
        }
        key = best;
      }
      const entry = key ? by[key] : null;
      if (!entry) {
        return {
          kind: 'wide',
          phase: 'none',
          history: [],
          shelves: [],
          excursions: [],
          last_date: cursorDay,
        };
      }
      return {
        ...entry,
        kind: 'wide',
        history: historyFromCausalShelves(entry.shelves, pack.daily, cursorDay, rangeLo),
        last_date: cursorDay,
      };
    }
    if (pack.kind === 'wide' && (pack.history || pack.shelves)) {
      return {
        ...pack,
        kind: 'wide',
        history: (pack.history && pack.history.length)
          ? pack.history
          : historyFromCausalShelves(pack.shelves, pack.daily, cursorDay, rangeLo),
        last_date: cursorDay,
      };
    }
    return null;
  }

  let tipSimJobId = 0;
  let tipSimTimer = null;
  /**
   * Manual «Закрыть все» for tip1m: force-close sim rows at current replay bar
   * (no +1 bar needed; works at 100%/last bar). Invalid when tipSimCache.key changes.
   * Array — несколько одновременно открытых (ручные + sim).
   */
  let tipForceCloses = [];
  /** Ручные Long/Short в tip1m (как tipForceClose — сброс при смене ключа sim). */
  let tipManualOpens = [];
  /** M15 bars stash while tip1m chart is active. */
  let m15PointsStash = null;
  /** Last /api/bars1m meta (window limit note). */
  let tip1mChartMeta = null;
  let tip1mBarsCacheKey = '';
  let tip1mChartJobId = 0;
  let zHeatmapJobId = 0;
  /** Пока true — не писать scroll в localStorage (восстановление / layout). */
  let suppressScrollSave = false;
  let suppressScrollSaveTimer = null;
  let selectedTradeId = null;
  let pnlMode = 'account';
  let tradeSortColumn = 'Index';
  let tradeSortDir = 'asc';
  /** all | no-red | only-red | hit1 | hit2 | hit3 */
  let riskFilter = 'all';
  /** all | base | addon | extra | shelf | manual */
  let srcFilter = 'all';
  /** Hover histogram tips on metric columns (Длит., Чист., Min…). Default ON. */
  let columnTipsEnabled = true;
  /** Last max drawdown from trades summary — same metric as «Макс. просадка». */
  let lastMaxDd = 0;
  let lastMaxDdPct = 0;
  /** Накопленный PnL закрытых (для подписи «сейчас» на режиме Счёт). */
  let lastTotalPnl = 0;
  /** Индекс бара для расширения окна графика (двойной клик по сделке). Не двигает курсор replay назад. */
  let chartFocusIndex = null;

  const $ = (id) => document.getElementById(id);

  function loadSettings() {
    const weekendUrl = new URLSearchParams(window.location.search).get('weekend_trading');
    if (weekendUrl != null) {
      const on = ['1', 'true', 'yes', 'on'].includes(String(weekendUrl).toLowerCase());
      localStorage.setItem(LS.weekendTrading, on ? '1' : '');
    }
    const entry = localStorage.getItem(LS.entry);
    const exit = localStorage.getItem(LS.exit);
    const period = localStorage.getItem(LS.period);
    const csv = localStorage.getItem(LS.csv);
    const startDate = localStorage.getItem(LS.startDate);
    const endDate = localStorage.getItem(LS.endDate);
    const cols = localStorage.getItem(LS.tradeColumns);

    if (entry) populateThresholdSelect($('entrySel'), 0.5, entry);
    else populateThresholdSelect($('entrySel'), 0.5, '0.7');
    if (exit) populateThresholdSelect($('exitSel'), 0.3, exit);
    else populateThresholdSelect($('exitSel'), 0.3, '0.5');
    if (csv) $('csvSel').value = csv;
    if (startDate) $('startDate').value = startDate;
    if (endDate && $('endDate')) $('endDate').value = endDate;
    if (period) {
      visibleDays = parseInt(period, 10) || 30;
      document.querySelectorAll('#periodChips .chip[data-days]').forEach((b) => {
        b.classList.toggle('active', parseInt(b.dataset.days, 10) === visibleDays);
      });
      if ($('btnFitFullChart')) {
        $('btnFitFullChart').classList.remove('active');
      }
    }
    visibleTradeColumns = migrateTradeColumnsOnce(decodeTradeColumns(cols));
    // Persist after one-time migration so reload keeps the set.
    if (cols !== encodeTradeColumns(visibleTradeColumns)) {
      saveSetting(LS.tradeColumns, encodeTradeColumns(visibleTradeColumns));
    }
    const notional = localStorage.getItem(LS.notionalRub);
    if (notional) $('notionalSel').value = notional;
    setSimNotionalRub($('notionalSel').value);
    const slipSaved = localStorage.getItem(LS.slippageSpreadPts);
    // Старый дефолт 0.05 → калибровка 0.12 (сделка 20.07.2026)
    if (slipSaved != null && $('slipSel') && slipSaved !== '0.05') {
      $('slipSel').value = slipSaved;
    } else if ($('slipSel')) {
      $('slipSel').value = '0.12';
    }
    if (typeof setSimSlippageSpreadPts === 'function') {
      const slipV = setSimSlippageSpreadPts($('slipSel')?.value ?? 0.12);
      if ($('slipSel')) $('slipSel').value = String(slipV);
      if ($('hmSlip')) $('hmSlip').value = String(slipV);
      saveSetting(LS.slippageSpreadPts, slipV);
    }
    const hmEntryMin = localStorage.getItem(LS.hmEntryMin);
    const hmEntryMax = localStorage.getItem(LS.hmEntryMax);
    const hmExitMin = localStorage.getItem(LS.hmExitMin);
    const hmExitMax = localStorage.getItem(LS.hmExitMax);
    const hmStep = localStorage.getItem(LS.hmStep);
    if (hmEntryMin && $('hmEntryMin')) $('hmEntryMin').value = hmEntryMin;
    if (hmEntryMax && $('hmEntryMax')) $('hmEntryMax').value = hmEntryMax;
    if (hmExitMin && $('hmExitMin')) $('hmExitMin').value = hmExitMin;
    if (hmExitMax && $('hmExitMax')) $('hmExitMax').value = hmExitMax;
    if (hmStep && $('hmStep')) $('hmStep').value = hmStep;
    const hmBand = localStorage.getItem(LS.hmBand);
    if (hmBand === 'wide' || hmBand === 'narrow') {
      document.querySelectorAll('#hmBandChips .chip').forEach((btn) => {
        btn.classList.toggle('active', btn.getAttribute('data-hm-band') === hmBand);
      });
    }
    const sortCol = localStorage.getItem(LS.tradeSortCol);
    const sortDir = localStorage.getItem(LS.tradeSortDir);
    if (sortCol && TRADE_COLUMN_KEYS.includes(sortCol)) tradeSortColumn = sortCol;
    if (sortDir === 'asc' || sortDir === 'desc') tradeSortDir = sortDir;
    const rf = localStorage.getItem(LS.riskFilter);
    if (rf === 'all' || rf === 'no-red' || rf === 'only-red'
      || rf === 'hit1' || rf === 'hit2' || rf === 'hit3') {
      riskFilter = rf;
    }
    const sf = localStorage.getItem(LS.srcFilter);
    if (sf === 'all' || sf === 'base' || sf === 'addon' || sf === 'extra'
      || sf === 'shelf' || sf === 'manual') {
      srcFilter = sf;
    }
    const tp = localStorage.getItem(LS.takeProfit);
    if (tp != null && $('tpSel')) $('tpSel').value = tp;
    const hn = localStorage.getItem(LS.holdNoTrendDays);
    if (hn != null && $('holdNoTrendSel') && ['0', '5', '7', '10'].includes(hn)) {
      $('holdNoTrendSel').value = hn;
    }
    const hl = localStorage.getItem(LS.holdLosingDays);
    if (hl != null && $('holdLosingSel') && ['0', '7', '10'].includes(hl)) {
      $('holdLosingSel').value = hl;
    }
    if (tp === '0' || tp === '1' || tp === '2' || tp === '3') {
      const tpEl = $('tpSel');
      if (tpEl) tpEl.value = tp;
    }
    fillRiskExitGroupSelects();
    const compoundOn = localStorage.getItem(LS.compound) === '1';
    const compoundChk = $('compoundChk');
    if (compoundChk) compoundChk.checked = compoundOn;
    setSimCompound(compoundOn);
    columnTipsEnabled = localStorage.getItem(LS.columnTips) !== '0';
    const columnTipsChk = $('columnTipsChk');
    if (columnTipsChk) columnTipsChk.checked = columnTipsEnabled;
  }

  function migrateLegacyRiskExitIfNeeded() {
    if (typeof RISK_EXIT_GROUPS === 'undefined') return;
    const anySaved = RISK_EXIT_GROUPS.some((g) => localStorage.getItem(g.lsKey));
    if (anySaved) return;
    const legacy = localStorage.getItem(LS.riskExit);
    if (!legacy || typeof RISK_EXIT_LEGACY_MAP === 'undefined') return;
    const map = RISK_EXIT_LEGACY_MAP[legacy];
    if (!map) return;
    for (const g of RISK_EXIT_GROUPS) {
      if (map[g.id]) localStorage.setItem(g.lsKey, map[g.id]);
    }
  }

  function fillRiskExitGroupSelects() {
    const host = $('riskExitGroups');
    if (!host || typeof RISK_EXIT_GROUPS === 'undefined') return;
    migrateLegacyRiskExitIfNeeded();
    host.innerHTML = '';
    for (const g of RISK_EXIT_GROUPS) {
      if (g.id === 'zStop') continue;
      const label = document.createElement('label');
      label.className = 'meta';
      label.title = g.title || g.label;
      label.appendChild(document.createTextNode(`${g.label} `));
      const sel = document.createElement('select');
      sel.id = g.selId;
      sel.dataset.riskGroup = g.id;
      const saved = localStorage.getItem(g.lsKey) || 'off';
      sel.innerHTML = g.options.map((o) => (
        `<option value="${o.id}">${o.label}</option>`
      )).join('');
      sel.value = g.options.some((o) => o.id === saved) ? saved : 'off';
      label.appendChild(sel);
      host.appendChild(label);
    }
    fillHmRiskExitGroupSelects();
  }

  function fillHmRiskExitGroupSelects() {
    const host = $('hmRiskExitGroups');
    if (!host || typeof RISK_EXIT_GROUPS === 'undefined') return;
    host.innerHTML = '';
    for (const g of RISK_EXIT_GROUPS) {
      if (g.id === 'zStop') continue;
      const label = document.createElement('label');
      label.className = 'meta';
      label.title = g.title || g.label;
      label.appendChild(document.createTextNode(`${g.label} `));
      const sel = document.createElement('select');
      sel.id = `hm_${g.selId}`;
      sel.dataset.riskGroup = g.id;
      sel.dataset.mirrorSel = g.selId;
      const primary = $(g.selId);
      const saved = primary?.value || localStorage.getItem(g.lsKey) || 'off';
      sel.innerHTML = g.options.map((o) => (
        `<option value="${o.id}">${o.label}</option>`
      )).join('');
      sel.value = g.options.some((o) => o.id === saved) ? saved : 'off';
      label.appendChild(sel);
      host.appendChild(label);
    }
  }

  let hmStrategySyncLock = false;

  function syncHmStrategyControlsFromToolbar() {
    if (hmStrategySyncLock) return;
    hmStrategySyncLock = true;
    try {
      const entry = $('entrySel')?.value;
      const exit = $('exitSel')?.value;
      if (entry != null && $('hmEntrySel')) {
        populateThresholdSelect($('hmEntrySel'), 0.5, entry);
      }
      if (exit != null && $('hmExitSel')) {
        populateThresholdSelect($('hmExitSel'), 0.3, exit);
      }
      if ($('hmTpSel') && $('tpSel')) $('hmTpSel').value = $('tpSel').value;
      if ($('hmHoldNoTrendSel') && $('holdNoTrendSel')) {
        $('hmHoldNoTrendSel').value = $('holdNoTrendSel').value;
      }
      if ($('hmHoldLosingSel') && $('holdLosingSel')) {
        $('hmHoldLosingSel').value = $('holdLosingSel').value;
      }
      if ($('hmNotionalSel') && $('notionalSel')) {
        $('hmNotionalSel').value = $('notionalSel').value;
      }
      if ($('hmSlip') && $('slipSel')) $('hmSlip').value = $('slipSel').value;
      if ($('hmCompoundChk') && $('compoundChk')) {
        $('hmCompoundChk').checked = !!$('compoundChk').checked;
      }
      if (typeof RISK_EXIT_GROUPS !== 'undefined') {
        for (const g of RISK_EXIT_GROUPS) {
          const primary = $(g.selId);
          const hm = $(`hm_${g.selId}`);
          if (primary && hm) hm.value = primary.value;
        }
      }
    } finally {
      hmStrategySyncLock = false;
    }
  }

  function selectedRiskExitGroups() {
    const out = {};
    if (typeof RISK_EXIT_GROUPS === 'undefined') return out;
    for (const g of RISK_EXIT_GROUPS) {
      const sel = $(g.selId);
      out[g.id] = g.id === 'zStop' ? 'off' : (sel?.value || 'off');
    }
    return out;
  }

  function riskExitSelectionKey() {
    const s = selectedRiskExitGroups();
    return RISK_EXIT_GROUPS.map((g) => `${g.id}:${s[g.id] || 'off'}`).join('|');
  }

  /** Avg win baseline (Z only) → money stop 3.5×avgWin, min 4000₽ — как Android. */
  let moneyStopDynCache = { key: '', value: 4000 };

  function computeMoneyStopDynamic(cursorIndex, entryOverride, exitOverride) {
    const th = thresholds();
    const entry = entryOverride != null ? entryOverride : th.entry;
    const exit = exitOverride != null ? exitOverride : th.exit;
    const idx = Math.max(0, cursorIndex|0);
    const key = [
      entry, exit, getSimNotionalRub(), getSimCompound() ? 1 : 0,
      computeMinCursor(), idx, allPoints.length,
    ].join('|');
    if (moneyStopDynCache.key === key) return moneyStopDynCache.value;
    const eng = new BarReplayEngine(allPoints, entry, exit, computeMinCursor(), {
      spreadLevels: typeof readHmSelectedSpreadLevels === 'function'
        ? readHmSelectedSpreadLevels()
        : null,
    });
    eng.seekTo(idx);
    const rows = buildTradeRows(eng.edges, entry, allPoints, idx, {
      spreadLevels: typeof readHmSelectedSpreadLevels === 'function'
        ? readHmSelectedSpreadLevels()
        : undefined,
    });
    const avgWin = buildTradeSimSummary(rows, getSimNotionalRub()).avgWin || 0;
    const value = Math.max(4000, avgWin * 3.5);
    moneyStopDynCache = { key, value };
    return value;
  }

  /** TP + выбран Risk exit groups → opts для BarReplayEngine. */
  function buildActiveSimOpts(cursorIndex) {
    const idx = cursorIndex != null ? cursorIndex : (engine?.cursor ?? 0);
    const raw = {
      takeProfitPct: thresholds().takeProfitPct,
      ...mergeRiskExitGroupOpts(selectedRiskExitGroups()),
      spreadLevels: typeof readHmSelectedSpreadLevels === 'function'
        ? readHmSelectedSpreadLevels()
        : null,
    };
    if (raw.maxLossRubDynamic) {
      raw.maxLossRub = computeMoneyStopDynamic(idx);
      delete raw.maxLossRubDynamic;
    }
    return normalizeSimExitOpts(raw);
  }

  async function copyThresholdsFromLive() {
    /* Только Торговля → Тестирование. В Live/Prod ничего не пишет. */
    const btn = $('btnCopyLiveThresh');
    try {
      if (btn) btn.disabled = true;
      const res = await fetch('/api/live/status');
      if (!res.ok) throw new Error(await res.text());
      const data = await res.json();
      const s = data.settings || {};
      if (s.entry_z == null || s.exit_z == null) {
        throw new Error('На Торговле нет порогов');
      }
      const entry = String(s.entry_z);
      const exit = String(s.exit_z);
      populateThresholdSelect($('entrySel'), 0.5, entry);
      populateThresholdSelect($('exitSel'), 0.3, exit);
      saveSetting(LS.entry, entry);
      saveSetting(LS.exit, exit);
      chartFocusIndex = null;
      applyStrategyParamsChange();
      const st = $('status');
      if (st) st.textContent = `Пороги из Торговли: ±${entry} / ±${exit} (только тест)`;
    } catch (e) {
      alert('Не удалось перенести пороги: ' + (e.message || e));
    } finally {
      if (btn) btn.disabled = false;
    }
  }

  function saveSetting(key, value) {
    if (value == null || value === '') localStorage.removeItem(key);
    else localStorage.setItem(key, String(value));
  }

  function tradesPanelWidthBounds(mainEl) {
    const total = mainEl.clientWidth - SPLITTER_WIDTH;
    const min = TRADES_PANEL_MIN;
    const max = Math.max(min, total - CHART_MIN);
    return { min, max };
  }

  function applyTradesPanelWidth(widthPx) {
    const main = $('mainSplit');
    if (!main) return TRADES_PANEL_DEFAULT;
    // While layout is 0-width (hidden / early load), keep preferred size — do not clamp to min.
    if (main.clientWidth <= 0) {
      const w = Math.round(Math.max(TRADES_PANEL_MIN, widthPx));
      main.style.setProperty('--trades-panel-width', `${w}px`);
      return w;
    }
    const { min, max } = tradesPanelWidthBounds(main);
    const w = Math.round(Math.max(min, Math.min(max, widthPx)));
    main.style.setProperty('--trades-panel-width', `${w}px`);
    requestAnimationFrame(() => chart?.resize());
    return w;
  }

  function loadTradesPanelWidth() {
    const saved = parseInt(localStorage.getItem(LS.tradesPanelWidth) || '', 10);
    return Number.isFinite(saved) && saved >= TRADES_PANEL_MIN ? saved : TRADES_PANEL_DEFAULT;
  }

  function isTradesTableHidden() {
    return !!$('tradesTablePane')?.classList.contains('is-collapsed');
  }

  function isRiskCompareHidden() {
    return !!$('riskComparePane')?.classList.contains('is-collapsed');
  }

  function isTradesSummaryHidden() {
    return !!$('tradesSummaryPane')?.classList.contains('is-collapsed');
  }

  function isMonthlyPnlHidden() {
    return !!$('monthlyPnlPane')?.classList.contains('is-collapsed');
  }

  function isZHeatmapHidden() {
    return !!$('zHeatmapPane')?.classList.contains('is-collapsed');
  }

  function tradesScrollHeightBounds(panelEl) {
    if (!panelEl) {
      return { min: TRADES_SCROLL_HEIGHT_MIN, max: TRADES_SCROLL_HEIGHT_DEFAULT };
    }
    const head = panelEl.querySelector('.trades-panel-head');
    const picker = panelEl.querySelector('.column-picker');
    const pickerH = picker && !picker.classList.contains('hidden') ? picker.offsetHeight : 0;
    const used = (head?.offsetHeight || 0) + pickerH + CHART_SPLITTER_HEIGHT + TRADES_SUMMARY_MIN;
    const max = Math.max(TRADES_SCROLL_HEIGHT_MIN, panelEl.clientHeight - used);
    return { min: TRADES_SCROLL_HEIGHT_MIN, max };
  }

  function applyTradesScrollHeight(heightPx) {
    const panel = $('tradesPanel');
    const scrollEl = $('tradesScroll');
    if (!panel || !scrollEl) return TRADES_SCROLL_HEIGHT_DEFAULT;
    // Hidden / not laid out yet: write preferred height without clamping to a 0-box max.
    if (panel.clientHeight <= 0) {
      const h = Math.round(Math.max(TRADES_SCROLL_HEIGHT_MIN, heightPx));
      panel.style.setProperty('--trades-scroll-height', `${h}px`);
      return h;
    }
    const { min, max } = tradesScrollHeightBounds(panel);
    const h = Math.round(Math.max(min, Math.min(max, heightPx)));
    panel.style.setProperty('--trades-scroll-height', `${h}px`);
    return h;
  }

  function loadTradesScrollHeight() {
    const saved = parseInt(localStorage.getItem(LS.tradesScrollHeight) || '', 10);
    if (Number.isFinite(saved) && saved >= TRADES_SCROLL_HEIGHT_MIN) return saved;
    // миграция со старого ключа высоты сводки
    const legacy = parseInt(localStorage.getItem('moexReplay.tradesSummaryHeight') || '', 10);
    if (Number.isFinite(legacy) && legacy > 0) {
      const panel = $('tradesPanel');
      if (panel?.clientHeight) {
        const approx = panel.clientHeight - legacy - CHART_SPLITTER_HEIGHT - 40;
        if (approx >= TRADES_SCROLL_HEIGHT_MIN) return approx;
      }
    }
    return TRADES_SCROLL_HEIGHT_DEFAULT;
  }

  function riskCompareHeightBounds(summaryEl) {
    if (!summaryEl) {
      return { min: RISK_COMPARE_HEIGHT_MIN, max: RISK_COMPARE_HEIGHT_DEFAULT };
    }
    const riskHead = summaryEl.querySelector('#riskComparePane .trades-section-head');
    const summaryHead = summaryEl.querySelector('#tradesSummaryPane .trades-section-head');
    const monthlyHead = summaryEl.querySelector('#monthlyPnlPane .trades-section-head');
    const heatmapHead = summaryEl.querySelector('#zHeatmapPane .trades-section-head');
    const metricsMin = isTradesSummaryHidden() ? 0 : 48;
    const monthlyH = isMonthlyPnlHidden()
      ? 0
      : readCssPxVar(summaryEl, '--monthly-pnl-height', loadMonthlyPnlHeight());
    const used = (riskHead?.offsetHeight || 0)
      + (summaryHead?.offsetHeight || 0) + metricsMin
      + (isTradesSummaryHidden() ? 0 : CHART_SPLITTER_HEIGHT)
      + (monthlyHead?.offsetHeight || 0) + monthlyH
      + (isMonthlyPnlHidden() ? 0 : CHART_SPLITTER_HEIGHT)
      + (heatmapHead?.offsetHeight || 0)
      + 16;
    const max = Math.max(RISK_COMPARE_HEIGHT_MIN, summaryEl.clientHeight - used);
    return { min: RISK_COMPARE_HEIGHT_MIN, max };
  }

  function applyRiskCompareHeight(heightPx) {
    const summary = $('tradesSummary');
    const scrollEl = $('riskCompareScroll');
    if (!summary || !scrollEl) return RISK_COMPARE_HEIGHT_DEFAULT;
    if (summary.clientHeight <= 0) {
      const h = Math.round(Math.max(RISK_COMPARE_HEIGHT_MIN, heightPx));
      summary.style.setProperty('--risk-compare-height', `${h}px`);
      return h;
    }
    const { min, max } = riskCompareHeightBounds(summary);
    const h = Math.round(Math.max(min, Math.min(max, heightPx)));
    summary.style.setProperty('--risk-compare-height', `${h}px`);
    return h;
  }

  function loadRiskCompareHeight() {
    const saved = parseInt(localStorage.getItem(LS.riskCompareHeight) || '', 10);
    return Number.isFinite(saved) && saved >= RISK_COMPARE_HEIGHT_MIN
      ? saved
      : RISK_COMPARE_HEIGHT_DEFAULT;
  }

  function monthlyPnlHeightBounds(summaryEl) {
    if (!summaryEl) {
      return { min: MONTHLY_PNL_HEIGHT_MIN, max: MONTHLY_PNL_HEIGHT_DEFAULT };
    }
    const filters = summaryEl.querySelector('.trades-summary-filters');
    const metricsMin = 48;
    const riskH = readCssPxVar(
      summaryEl,
      '--risk-compare-height',
      loadRiskCompareHeight(),
    );
    const heatmapHead = summaryEl.querySelector('#zHeatmapPane .trades-section-head');
    const used = (filters?.offsetHeight || 0)
      + riskH + CHART_SPLITTER_HEIGHT
      + metricsMin + CHART_SPLITTER_HEIGHT
      + (heatmapHead?.offsetHeight || 0)
      + 16;
    const max = Math.max(MONTHLY_PNL_HEIGHT_MIN, summaryEl.clientHeight - used);
    return { min: MONTHLY_PNL_HEIGHT_MIN, max };
  }

  function applyMonthlyPnlHeight(heightPx) {
    const summary = $('tradesSummary');
    const scrollEl = $('tradesMonthlyPnlScroll');
    if (!summary || !scrollEl) return MONTHLY_PNL_HEIGHT_DEFAULT;
    if (summary.clientHeight <= 0) {
      const h = Math.round(Math.max(MONTHLY_PNL_HEIGHT_MIN, heightPx));
      summary.style.setProperty('--monthly-pnl-height', `${h}px`);
      return h;
    }
    const { min, max } = monthlyPnlHeightBounds(summary);
    const h = Math.round(Math.max(min, Math.min(max, heightPx)));
    summary.style.setProperty('--monthly-pnl-height', `${h}px`);
    return h;
  }

  function loadMonthlyPnlHeight() {
    const saved = parseInt(localStorage.getItem(LS.monthlyPnlHeight) || '', 10);
    return Number.isFinite(saved) && saved >= MONTHLY_PNL_HEIGHT_MIN
      ? saved
      : MONTHLY_PNL_HEIGHT_DEFAULT;
  }

  function zHeatmapHeightBounds(summaryEl) {
    if (!summaryEl) {
      return { min: Z_HEATMAP_HEIGHT_MIN, max: Z_HEATMAP_HEIGHT_DEFAULT };
    }
    const riskHead = summaryEl.querySelector('#riskComparePane .trades-section-head');
    const summaryHead = summaryEl.querySelector('#tradesSummaryPane .trades-section-head');
    const monthlyHead = summaryEl.querySelector('#monthlyPnlPane .trades-section-head');
    const heatmapHead = summaryEl.querySelector('#zHeatmapPane .trades-section-head');
    const riskH = isRiskCompareHidden()
      ? 0
      : readCssPxVar(summaryEl, '--risk-compare-height', loadRiskCompareHeight());
    const metricsMin = isTradesSummaryHidden() ? 0 : 48;
    const monthlyH = isMonthlyPnlHidden()
      ? 0
      : readCssPxVar(summaryEl, '--monthly-pnl-height', loadMonthlyPnlHeight());
    const used = (riskHead?.offsetHeight || 0)
      + (isRiskCompareHidden() ? 0 : riskH + CHART_SPLITTER_HEIGHT)
      + (summaryHead?.offsetHeight || 0)
      + (isTradesSummaryHidden() ? 0 : metricsMin + CHART_SPLITTER_HEIGHT)
      + (monthlyHead?.offsetHeight || 0)
      + (isMonthlyPnlHidden() ? 0 : monthlyH + CHART_SPLITTER_HEIGHT)
      + (heatmapHead?.offsetHeight || 0)
      + CHART_SPLITTER_HEIGHT
      + 16;
    const max = Math.max(Z_HEATMAP_HEIGHT_MIN, summaryEl.clientHeight - used);
    return { min: Z_HEATMAP_HEIGHT_MIN, max };
  }

  function applyZHeatmapHeight(heightPx) {
    const summary = $('tradesSummary');
    const scrollEl = $('tradesZHeatmapScroll');
    if (!summary || !scrollEl) return Z_HEATMAP_HEIGHT_DEFAULT;
    if (summary.clientHeight <= 0) {
      const h = Math.round(Math.max(Z_HEATMAP_HEIGHT_MIN, heightPx));
      summary.style.setProperty('--z-heatmap-height', `${h}px`);
      return h;
    }
    const { min, max } = zHeatmapHeightBounds(summary);
    const h = Math.round(Math.max(min, Math.min(max, heightPx)));
    summary.style.setProperty('--z-heatmap-height', `${h}px`);
    return h;
  }

  function loadZHeatmapHeight() {
    const saved = parseInt(localStorage.getItem(LS.zHeatmapHeight) || '', 10);
    return Number.isFinite(saved) && saved >= Z_HEATMAP_HEIGHT_MIN
      ? saved
      : Z_HEATMAP_HEIGHT_DEFAULT;
  }

  function readCssPxVar(el, prop, fallback) {
    if (!el) return fallback;
    const n = parseInt(getComputedStyle(el).getPropertyValue(prop), 10);
    return Number.isFinite(n) && n > 0 ? n : fallback;
  }

  function bindTradesPanelVerticalSplits() {
    applyTradesScrollHeight(loadTradesScrollHeight());
    applyRiskCompareHeight(loadRiskCompareHeight());
    applyMonthlyPnlHeight(loadMonthlyPnlHeight());
    applyZHeatmapHeight(loadZHeatmapHeight());

    const bindH = ({
      dividerId, readHeight, applyHeight, saveKey, defaultHeight, invertDy = false, isHidden,
    }) => {
      const divider = $(dividerId);
      if (!divider) return;
      let dragging = false;
      let startY = 0;
      let startH = 0;
      let lastH = 0;

      const onMove = (e) => {
        if (!dragging) return;
        const dy = e.clientY - startY;
        lastH = applyHeight(startH + (invertDy ? -dy : dy));
      };

      const endDrag = () => {
        if (!dragging) return;
        dragging = false;
        divider.classList.remove('active');
        document.body.classList.remove('split-dragging-v');
        document.removeEventListener('mousemove', onMove);
        document.removeEventListener('mouseup', endDrag);
        if (lastH > 0) saveSetting(saveKey, lastH);
      };

      divider.addEventListener('mousedown', (e) => {
        if (divider.classList.contains('is-disabled') || (isHidden && isHidden())) return;
        e.preventDefault();
        e.stopPropagation();
        dragging = true;
        startY = e.clientY;
        startH = readHeight();
        lastH = startH;
        divider.classList.add('active');
        document.body.classList.add('split-dragging-v');
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', endDrag);
      });

      divider.addEventListener('dblclick', (e) => {
        if (divider.classList.contains('is-disabled') || (isHidden && isHidden())) return;
        e.preventDefault();
        const h = applyHeight(defaultHeight);
        saveSetting(saveKey, h);
      });
    };

    // Полоска ПОД таблицей сделок: вверх = меньше сделок, вниз = больше
    bindH({
      dividerId: 'tradesSplitDividerH',
      readHeight: () => readCssPxVar($('tradesPanel'), '--trades-scroll-height', loadTradesScrollHeight()),
      applyHeight: applyTradesScrollHeight,
      saveKey: LS.tradesScrollHeight,
      defaultHeight: TRADES_SCROLL_HEIGHT_DEFAULT,
      invertDy: false,
      isHidden: isTradesTableHidden,
    });

    // Полоска ПОД таблицей «правило»: вверх = меньше, вниз = больше
    bindH({
      dividerId: 'riskCompareSplitDividerH',
      readHeight: () => readCssPxVar($('tradesSummary'), '--risk-compare-height', loadRiskCompareHeight()),
      applyHeight: applyRiskCompareHeight,
      saveKey: LS.riskCompareHeight,
      defaultHeight: RISK_COMPARE_HEIGHT_DEFAULT,
      invertDy: false,
      isHidden: isRiskCompareHidden,
    });

    // Полоска НАД гистограммой (как у chart panes): вверх = больше PnL, вниз = меньше
    bindH({
      dividerId: 'monthlyPnlSplitDividerH',
      readHeight: () => {
        const el = $('tradesMonthlyPnlScroll');
        if (el && el.clientHeight > 0) return el.clientHeight;
        return readCssPxVar($('tradesSummary'), '--monthly-pnl-height', loadMonthlyPnlHeight());
      },
      applyHeight: applyMonthlyPnlHeight,
      saveKey: LS.monthlyPnlHeight,
      defaultHeight: MONTHLY_PNL_HEIGHT_DEFAULT,
      invertDy: true,
      isHidden: isMonthlyPnlHidden,
    });

    bindH({
      dividerId: 'zHeatmapSplitDividerH',
      readHeight: () => {
        const el = $('tradesZHeatmapScroll');
        if (el && el.clientHeight > 0) return el.clientHeight;
        return readCssPxVar($('tradesSummary'), '--z-heatmap-height', loadZHeatmapHeight());
      },
      applyHeight: applyZHeatmapHeight,
      saveKey: LS.zHeatmapHeight,
      defaultHeight: Z_HEATMAP_HEIGHT_DEFAULT,
      invertDy: true,
      isHidden: isZHeatmapHidden,
    });

    // Re-clamp for display only. Never write back to LS — early/hidden layout
    // would clamp to mins and wipe user prefs on Ctrl+F5.
    window.addEventListener('resize', () => {
      if (!isTradesTableHidden()) applyTradesScrollHeight(loadTradesScrollHeight());
      if (!isRiskCompareHidden()) applyRiskCompareHeight(loadRiskCompareHeight());
      if (!isMonthlyPnlHidden()) applyMonthlyPnlHeight(loadMonthlyPnlHeight());
      if (!isZHeatmapHidden()) applyZHeatmapHeight(loadZHeatmapHeight());
    });
  }

  function secondaryPanesHeadHeight(stackEl) {
    const heads = stackEl.querySelectorAll('.pnl-head');
    let h = 0;
    heads.forEach((el) => { h += el.offsetHeight || 28; });
    return h || 56;
  }

  function isDeltaPaneHidden() {
    return !!$('deltaPane')?.classList.contains('is-collapsed');
  }

  function isPnlPaneHidden() {
    return !!$('pnlPane')?.classList.contains('is-collapsed');
  }

  function secondaryChartHeightBounds(stackEl, otherHeight) {
    const headH = secondaryPanesHeadHeight(stackEl);
    let splitters = 0;
    if (!isDeltaPaneHidden()) splitters += 1;
    if (!isPnlPaneHidden()) splitters += 1;
    const total = stackEl.clientHeight - splitters * CHART_SPLITTER_HEIGHT - headH;
    const min = Math.min(PNL_CHART_MIN, DELTA_CHART_MIN);
    const reserved = Math.max(0, otherHeight || 0);
    const max = Math.max(min, total - Z_PANE_MIN - reserved);
    return { min, max };
  }

  function applyPnlChartHeight(heightPx) {
    const stack = $('chartStack');
    if (!stack) return PNL_CHART_DEFAULT;
    if (stack.clientHeight <= 0) {
      const h = Math.round(Math.max(PNL_CHART_MIN, heightPx));
      stack.style.setProperty('--pnl-chart-height', `${h}px`);
      return h;
    }
    const deltaH = isDeltaPaneHidden()
      ? 0
      : (parseInt(getComputedStyle(stack).getPropertyValue('--delta-chart-height'), 10)
        || loadDeltaChartHeight());
    const { min, max } = secondaryChartHeightBounds(stack, deltaH);
    const h = Math.round(Math.max(min, Math.min(max, heightPx)));
    stack.style.setProperty('--pnl-chart-height', `${h}px`);
    requestAnimationFrame(() => {
      requestAnimationFrame(() => chart?.resize());
    });
    return h;
  }

  function applyDeltaChartHeight(heightPx) {
    const stack = $('chartStack');
    if (!stack) return DELTA_CHART_DEFAULT;
    if (stack.clientHeight <= 0) {
      const h = Math.round(Math.max(DELTA_CHART_MIN, heightPx));
      stack.style.setProperty('--delta-chart-height', `${h}px`);
      return h;
    }
    const pnlH = isPnlPaneHidden()
      ? 0
      : (parseInt(getComputedStyle(stack).getPropertyValue('--pnl-chart-height'), 10)
        || loadPnlChartHeight());
    const { min, max } = secondaryChartHeightBounds(stack, pnlH);
    const h = Math.round(Math.max(min, Math.min(max, heightPx)));
    stack.style.setProperty('--delta-chart-height', `${h}px`);
    requestAnimationFrame(() => {
      requestAnimationFrame(() => chart?.resize());
    });
    return h;
  }

  function loadPnlChartHeight() {
    const saved = parseInt(localStorage.getItem(LS.pnlPaneHeight) || '', 10);
    if (!Number.isFinite(saved)) return PNL_CHART_DEFAULT;
    return Math.max(PNL_CHART_MIN, saved);
  }

  function loadDeltaChartHeight() {
    const saved = parseInt(localStorage.getItem(LS.deltaPaneHeight) || '', 10);
    if (!Number.isFinite(saved)) return DELTA_CHART_DEFAULT;
    return Math.max(DELTA_CHART_MIN, saved);
  }

  function loadPnlMode() {
    const saved = localStorage.getItem(LS.pnlMode);
    if (saved === 'trade') return 'trade';
    if (saved === 'total') return 'total';
    if (saved === 'account') return 'account';
    return 'account';
  }

  function formatPnlDrawdownLabel(maxDd = lastMaxDd, maxDdPct = lastMaxDdPct) {
    if (!(maxDd > 0)) return 'просадка 0%';
    const rub = typeof formatAccountRub === 'function'
      ? formatAccountRub(maxDd)
      : formatRub(maxDd);
    return `просадка −${maxDdPct.toFixed(1)}% (−${rub} ₽)`;
  }

  function formatSimDepositPlain() {
    return Math.round(getSimNotionalRub()).toLocaleString('ru-RU');
  }

  function updatePnlLabel(
    maxDd = lastMaxDd,
    maxDdPct = lastMaxDdPct,
    totalPnl = lastTotalPnl,
  ) {
    const label = $('pnlLabel');
    if (!label) return;
    const dep = formatSimDepositPlain();
    if (pnlMode === 'account') {
      const nowRub = getSimNotionalRub() + (Number.isFinite(totalPnl) ? totalPnl : 0);
      const now = typeof formatAccountRub === 'function'
        ? formatAccountRub(nowRub)
        : formatRub(nowRub);
      label.textContent = `Счёт · от ${dep} ₽ · сейчас ${now} ₽ · ${formatPnlDrawdownLabel(maxDd, maxDdPct)}`;
      return;
    }
    if (pnlMode === 'trade') {
      label.textContent = 'Сделка · PnL только открытой позиции (между сделками — 0)';
      return;
    }
    label.textContent = `PnL % · кумулятивно от пика капитала · ${formatPnlDrawdownLabel(maxDd, maxDdPct)}`;
  }

  function updatePnlModeChips() {
    document.querySelectorAll('#pnlModeChips .chip').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.pnlMode === pnlMode);
    });
    updatePnlLabel(lastMaxDd, lastMaxDdPct, lastTotalPnl);
  }

  function bindPnlModeToggle() {
    pnlMode = loadPnlMode();
    updatePnlModeChips();
    document.querySelectorAll('#pnlModeChips .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        const mode = btn.dataset.pnlMode || 'account';
        pnlMode = mode === 'trade' ? 'trade' : mode === 'total' ? 'total' : 'account';
        saveSetting(LS.pnlMode, pnlMode);
        updatePnlModeChips();
        // Полная перерисовка: иначе light-кэш может оставить кривую другого режима.
        pendingChartRepaint = true;
        uiSeriesCache = { ...uiSeriesCache, equity: null, pnlMode: null };
        refreshUi();
      });
    });
  }

  function bindVerticalPaneSplit(dividerId, chartElId, {
    loadHeight,
    applyHeight,
    saveKey,
    defaultHeight,
  }) {
    const divider = $(dividerId);
    const stack = $('chartStack');
    const paneChart = $(chartElId);
    if (!divider || !stack || !paneChart) return;

    applyHeight(loadHeight());

    let dragging = false;
    let startY = 0;
    let startH = 0;

    const onMove = (e) => {
      if (!dragging) return;
      const dy = e.clientY - startY;
      applyHeight(startH - dy);
    };

    const endDrag = () => {
      if (!dragging) return;
      dragging = false;
      divider.classList.remove('active');
      document.body.classList.remove('split-dragging-v');
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', endDrag);
      const cssVar = chartElId === 'deltaChart' ? '--delta-chart-height' : '--pnl-chart-height';
      const h = parseInt(getComputedStyle(stack).getPropertyValue(cssVar), 10);
      if (h > 0) saveSetting(saveKey, h);
      chart?.resize();
    };

    divider.addEventListener('mousedown', (e) => {
      if (divider.classList.contains('is-disabled')) return;
      if (chartElId === 'deltaChart' && isDeltaPaneHidden()) return;
      if (chartElId === 'pnlChart' && isPnlPaneHidden()) return;
      e.preventDefault();
      dragging = true;
      startY = e.clientY;
      startH = paneChart.offsetHeight;
      divider.classList.add('active');
      document.body.classList.add('split-dragging-v');
      document.addEventListener('mousemove', onMove);
      document.addEventListener('mouseup', endDrag);
    });

    divider.addEventListener('dblclick', () => {
      if (divider.classList.contains('is-disabled')) return;
      if (chartElId === 'deltaChart' && isDeltaPaneHidden()) return;
      if (chartElId === 'pnlChart' && isPnlPaneHidden()) return;
      const h = applyHeight(defaultHeight);
      saveSetting(saveKey, h);
    });

    window.addEventListener('resize', () => {
      if (chartElId === 'deltaChart' && isDeltaPaneHidden()) return;
      if (chartElId === 'pnlChart' && isPnlPaneHidden()) return;
      // Re-apply preferred height from LS; do not persist clamped result.
      applyHeight(loadHeight());
    });
  }

  function syncPaneCollapseUi() {
    const deltaHidden = isDeltaPaneHidden();
    const pnlHidden = isPnlPaneHidden();
    const tradesHidden = isTradesTableHidden();
    const riskHidden = isRiskCompareHidden();
    const summaryHidden = isTradesSummaryHidden();
    const monthlyHidden = isMonthlyPnlHidden();
    const heatmapHidden = isZHeatmapHidden();

    const deltaSplit = $('deltaSplitDividerH');
    const pnlSplit = $('chartSplitDividerH');
    if (deltaSplit) {
      deltaSplit.classList.toggle('is-disabled', deltaHidden);
      deltaSplit.setAttribute('aria-hidden', deltaHidden ? 'true' : 'false');
    }
    if (pnlSplit) {
      pnlSplit.classList.toggle('is-disabled', pnlHidden);
      pnlSplit.setAttribute('aria-hidden', pnlHidden ? 'true' : 'false');
    }

    const syncBtn = (id, hidden) => {
      const btn = $(id);
      if (!btn) return;
      btn.setAttribute('aria-expanded', hidden ? 'false' : 'true');
      btn.textContent = hidden ? '+' : '−';
      btn.title = hidden ? 'Показать' : 'Скрыть';
    };
    syncBtn('btnCollapseDelta', deltaHidden);
    syncBtn('btnCollapsePnl', pnlHidden);
    syncBtn('btnCollapseTradesTable', tradesHidden);
    syncBtn('btnCollapseRiskCompare', riskHidden);
    syncBtn('btnCollapseTradesSummary', summaryHidden);
    syncBtn('btnCollapseMonthlyPnl', monthlyHidden);
    syncBtn('btnCollapseZHeatmap', heatmapHidden);

    const syncSplit = (id, hidden) => {
      const el = $(id);
      if (!el) return;
      el.classList.toggle('is-disabled', hidden);
      el.setAttribute('aria-hidden', hidden ? 'true' : 'false');
    };
    syncSplit('tradesSplitDividerH', tradesHidden);
    syncSplit('riskCompareSplitDividerH', riskHidden);
    // Hide monthly splitter when either adjacent body is collapsed
    syncSplit('monthlyPnlSplitDividerH', monthlyHidden || summaryHidden);
    syncSplit('zHeatmapSplitDividerH', heatmapHidden || monthlyHidden);

    if (!deltaHidden) applyDeltaChartHeight(loadDeltaChartHeight());
    if (!pnlHidden) applyPnlChartHeight(loadPnlChartHeight());
    if (!tradesHidden) applyTradesScrollHeight(loadTradesScrollHeight());
    if (!riskHidden) applyRiskCompareHeight(loadRiskCompareHeight());
    if (!monthlyHidden) applyMonthlyPnlHeight(loadMonthlyPnlHeight());
    if (!heatmapHidden) applyZHeatmapHeight(loadZHeatmapHeight());

    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        if (typeof window.__moexReplayResize === 'function') window.__moexReplayResize();
        else chart?.resize();
      });
    });
  }

  function isChartsHidden() {
    return !!$('mainSplit')?.classList.contains('charts-collapsed');
  }

  function syncChartsCollapseUi() {
    const hidden = isChartsHidden();
    const collapseBtn = $('btnCollapseCharts');
    const restoreBtn = $('btnRestoreCharts');
    if (collapseBtn) {
      collapseBtn.setAttribute('aria-expanded', hidden ? 'false' : 'true');
      collapseBtn.textContent = hidden ? '+' : '−';
      collapseBtn.title = hidden ? 'Показать график' : 'Скрыть график';
    }
    if (restoreBtn) restoreBtn.hidden = !hidden;
  }

  /**
   * Скрыть/показать весь блок графиков (как панель параметров на «Торговле»).
   * @param {boolean} collapsed
   * @param {{ skipPaint?: boolean }} [opts]
   */
  function setChartsHidden(collapsed, opts = {}) {
    const main = $('mainSplit');
    if (!main) return;
    const next = !!collapsed;
    const was = isChartsHidden();
    main.classList.toggle('charts-collapsed', next);
    syncChartsCollapseUi();
    saveSetting(LS.chartsHidden, next ? '1' : '');
    if (next) {
      if (isTestChartFullscreen()) setTestChartFullscreen(false);
      pendingChartRepaint = true;
      // Сбросить кэш серий — при показе пересоберём с нуля.
      uiSeriesCache = {
        cursor: uiSeriesCache.cursor,
        edgesLen: uiSeriesCache.edgesLen,
        rangeStart: -1,
        rangeEnd: -1,
        markers: null,
        equityTotal: null,
        equity: null,
        deltaPp: null,
        trades: null,
        cascadeVlines: null,
        cascadeCursorKey: '',
        pnlMode: null,
      };
    } else if (was && !opts.skipPaint) {
      pendingChartRepaint = true;
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          if (typeof window.__moexReplayResize === 'function') window.__moexReplayResize();
          else chart?.resize();
          ensureChartsVisiblePaint();
        });
      });
    } else if (!next) {
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          if (typeof window.__moexReplayResize === 'function') window.__moexReplayResize();
          else chart?.resize();
        });
      });
    }
  }

  /** После показа графика: догрузить tip1m при необходимости и полный кадр. */
  function ensureChartsVisiblePaint() {
    if (isChartsHidden() || !engine) return;
    pendingChartRepaint = true;
    if (isTip1mMode() && tip1mChartNeedsReload()) {
      const st = $('status');
      if (st) st.textContent = 'касание 1м · гружу окно графика…';
      activateTip1mChart().catch((e) => {
        console.error(e);
        try { refreshUi({ afterParams: true }); } catch (_) { /* ignore */ }
      });
      return;
    }
    try {
      refreshUi({ afterParams: true });
    } catch (_) { /* ignore */ }
  }

  function bindChartsCollapse() {
    setChartsHidden(loadPaneHidden(LS.chartsHidden), { skipPaint: true });
    $('btnCollapseCharts')?.addEventListener('click', () => {
      setChartsHidden(!isChartsHidden());
    });
    $('btnRestoreCharts')?.addEventListener('click', () => {
      setChartsHidden(false);
    });
  }

  function setPaneCollapsed(paneId, collapsed) {
    const pane = $(paneId);
    if (!pane) return;
    pane.classList.toggle('is-collapsed', !!collapsed);
  }

  function loadPaneHidden(key) {
    return localStorage.getItem(key) === '1';
  }

  function bindPaneCollapse() {
    setPaneCollapsed('deltaPane', loadPaneHidden(LS.deltaPaneHidden));
    setPaneCollapsed('pnlPane', loadPaneHidden(LS.pnlPaneHidden));
    setPaneCollapsed('tradesTablePane', loadPaneHidden(LS.tradesTableHidden));
    setPaneCollapsed('riskComparePane', loadPaneHidden(LS.riskCompareHidden));
    setPaneCollapsed('tradesSummaryPane', loadPaneHidden(LS.tradesSummaryHidden));
    setPaneCollapsed('monthlyPnlPane', loadPaneHidden(LS.monthlyPnlHidden));
    setPaneCollapsed('zHeatmapPane', loadPaneHidden(LS.zHeatmapHidden));

    const bindToggle = (btnId, paneId, lsKey, isHidden) => {
      $(btnId)?.addEventListener('click', () => {
        const next = !isHidden();
        setPaneCollapsed(paneId, next);
        saveSetting(lsKey, next ? '1' : '');
        syncPaneCollapseUi();
        if (paneId === 'zHeatmapPane' && !next) {
          scheduleZHeatmapUpdate(engine?.cursor ?? -1);
        }
      });
    };
    bindToggle('btnCollapseDelta', 'deltaPane', LS.deltaPaneHidden, isDeltaPaneHidden);
    bindToggle('btnCollapsePnl', 'pnlPane', LS.pnlPaneHidden, isPnlPaneHidden);
    bindToggle('btnCollapseTradesTable', 'tradesTablePane', LS.tradesTableHidden, isTradesTableHidden);
    bindToggle('btnCollapseRiskCompare', 'riskComparePane', LS.riskCompareHidden, isRiskCompareHidden);
    bindToggle('btnCollapseTradesSummary', 'tradesSummaryPane', LS.tradesSummaryHidden, isTradesSummaryHidden);
    bindToggle('btnCollapseMonthlyPnl', 'monthlyPnlPane', LS.monthlyPnlHidden, isMonthlyPnlHidden);
    bindToggle('btnCollapseZHeatmap', 'zHeatmapPane', LS.zHeatmapHidden, isZHeatmapHidden);
    bindZHeatmapFullscreen();
    bindTestChartFullscreen();
    bindReplayPageFullscreen();
    bindChartsCollapse();

    syncPaneCollapseUi();
  }

  function replayFsRoot() {
    return $('app') || document.documentElement;
  }

  function nativeFsElement() {
    return document.fullscreenElement || document.webkitFullscreenElement || null;
  }

  function isReplayPageFullscreen() {
    const root = replayFsRoot();
    const native = nativeFsElement();
    if (native) return native === root || !!(root && root.contains(native));
    return !!root?.classList.contains('is-replay-fs');
  }

  function syncReplayFsButton() {
    const btn = $('btnExpandReplay');
    if (!btn) return;
    const on = isReplayPageFullscreen();
    btn.setAttribute('aria-pressed', on ? 'true' : 'false');
    btn.title = on ? 'Свернуть с экрана (Esc)' : 'На весь экран';
    btn.textContent = on ? '✕' : '⛶';
  }

  function scheduleReplayFsResize() {
    const run = () => {
      if (typeof window.__moexReplayResize === 'function') window.__moexReplayResize();
    };
    requestAnimationFrame(() => {
      run();
      requestAnimationFrame(() => {
        run();
        setTimeout(run, 80);
      });
    });
  }

  async function setReplayPageFullscreen(on) {
    const root = replayFsRoot();
    if (!root) return;
    const want = !!on;
    const native = nativeFsElement();
    try {
      if (want) {
        if (isTestChartFullscreen()) setTestChartFullscreen(false);
        if (!native) {
          if (root.requestFullscreen) await root.requestFullscreen();
          else if (root.webkitRequestFullscreen) root.webkitRequestFullscreen();
          else throw new Error('no-fs');
        }
        root.classList.remove('is-replay-fs');
      } else {
        if (native) {
          if (document.exitFullscreen) await document.exitFullscreen();
          else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
        }
        root.classList.remove('is-replay-fs');
      }
    } catch (_) {
      root.classList.toggle('is-replay-fs', want);
    }
    syncReplayFsButton();
    scheduleReplayFsResize();
  }

  function bindReplayPageFullscreen() {
    $('btnExpandReplay')?.addEventListener('click', () => {
      setReplayPageFullscreen(!isReplayPageFullscreen());
    });
    const onFsChange = () => {
      const root = replayFsRoot();
      if (root && nativeFsElement()) root.classList.remove('is-replay-fs');
      syncReplayFsButton();
      scheduleReplayFsResize();
    };
    document.addEventListener('fullscreenchange', onFsChange);
    document.addEventListener('webkitfullscreenchange', onFsChange);
    document.addEventListener('keydown', (e) => {
      if (e.key !== 'Escape') return;
      const root = replayFsRoot();
      if (root?.classList.contains('is-replay-fs') && !nativeFsElement()) {
        setReplayPageFullscreen(false);
      }
    });
    $('viewModeChips')?.addEventListener('click', (e) => {
      const chip = e.target.closest('[data-view]');
      if (!chip || chip.dataset.view === 'replay') return;
      if (isReplayPageFullscreen()) setReplayPageFullscreen(false);
    });
  }

  function isZHeatmapFullscreen() {
    return !!$('zHeatmapPane')?.classList.contains('is-fullscreen');
  }

  function setZHeatmapFullscreen(on) {
    const pane = $('zHeatmapPane');
    const btn = $('btnExpandZHeatmap');
    if (!pane) return;
    const next = !!on;
    if (next) {
      if (isTestChartFullscreen()) setTestChartFullscreen(false);
      if (isZHeatmapHidden()) {
        setPaneCollapsed('zHeatmapPane', false);
        saveSetting(LS.zHeatmapHidden, '');
        syncPaneCollapseUi();
      }
    }
    pane.classList.toggle('is-fullscreen', next);
    document.body.classList.toggle('z-heatmap-fs-open', next);
    if (btn) {
      btn.setAttribute('aria-pressed', next ? 'true' : 'false');
      btn.title = next ? 'Свернуть с экрана (Esc)' : 'На весь экран';
      btn.textContent = next ? '✕' : '⛶';
    }
    if (next) {
      scheduleZHeatmapUpdate(null, { immediate: true });
      requestAnimationFrame(() => {
        applyZHeatmapScrollTop(zHeatmapScrollRestored ? $('tradesZHeatmap')?.scrollTop : readSavedZHeatmapScrollTop());
      });
    }
  }

  function bindZHeatmapFullscreen() {
    $('btnExpandZHeatmap')?.addEventListener('click', () => {
      setZHeatmapFullscreen(!isZHeatmapFullscreen());
    });
    document.addEventListener('keydown', (e) => {
      if (e.key !== 'Escape' || !isZHeatmapFullscreen()) return;
      setZHeatmapFullscreen(false);
    });
  }

  function isTestChartFullscreen() {
    return !!$('testChartPane')?.classList.contains('is-fullscreen');
  }

  function syncTestChartFsButton(on) {
    const btn = $('btnExpandTestChart');
    if (!btn) return;
    btn.setAttribute('aria-pressed', on ? 'true' : 'false');
    btn.title = on ? 'Свернуть с экрана (Esc)' : 'На весь экран';
    btn.textContent = on ? '✕' : '⛶';
  }

  function setTestChartFullscreen(on) {
    const pane = $('testChartPane');
    if (!pane) return;
    const next = !!on;
    if (next) {
      if (isZHeatmapFullscreen()) setZHeatmapFullscreen(false);
      if (isReplayPageFullscreen()) setReplayPageFullscreen(false);
    }
    pane.classList.toggle('is-fullscreen', next);
    document.body.classList.toggle('test-chart-fs-open', next);
    syncTestChartFsButton(next);
    scheduleReplayFsResize();
  }

  function bindTestChartFullscreen() {
    $('btnExpandTestChart')?.addEventListener('click', () => {
      setTestChartFullscreen(!isTestChartFullscreen());
    });
    document.addEventListener('keydown', (e) => {
      if (e.key !== 'Escape' || !isTestChartFullscreen()) return;
      setTestChartFullscreen(false);
    });
    $('viewModeChips')?.addEventListener('click', (e) => {
      const chip = e.target.closest('[data-view]');
      if (!chip || chip.dataset.view === 'replay') return;
      if (isTestChartFullscreen()) setTestChartFullscreen(false);
    });
  }

  function bindChartVerticalSplit() {
    bindVerticalPaneSplit('deltaSplitDividerH', 'deltaChart', {
      loadHeight: loadDeltaChartHeight,
      applyHeight: applyDeltaChartHeight,
      saveKey: LS.deltaPaneHeight,
      defaultHeight: DELTA_CHART_DEFAULT,
    });
    bindVerticalPaneSplit('chartSplitDividerH', 'pnlChart', {
      loadHeight: loadPnlChartHeight,
      applyHeight: applyPnlChartHeight,
      saveKey: LS.pnlPaneHeight,
      defaultHeight: PNL_CHART_DEFAULT,
    });
  }

  function bindSplitDivider() {
    const divider = $('splitDivider');
    const main = $('mainSplit');
    if (!divider || !main) return;

    applyTradesPanelWidth(loadTradesPanelWidth());

    let dragging = false;
    let startX = 0;
    let startWidth = 0;

    const onMove = (e) => {
      if (!dragging) return;
      const dx = startX - e.clientX;
      applyTradesPanelWidth(startWidth + dx);
    };

    const endDrag = () => {
      if (!dragging) return;
      dragging = false;
      divider.classList.remove('active');
      document.body.classList.remove('split-dragging');
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', endDrag);
      const w = parseInt(getComputedStyle(main).getPropertyValue('--trades-panel-width'), 10);
      if (w > 0) saveSetting(LS.tradesPanelWidth, w);
    };

    divider.addEventListener('mousedown', (e) => {
      if (window.innerWidth <= 900) return;
      e.preventDefault();
      dragging = true;
      startX = e.clientX;
      startWidth = $('tradesPanel')?.offsetWidth ?? TRADES_PANEL_DEFAULT;
      divider.classList.add('active');
      document.body.classList.add('split-dragging');
      document.addEventListener('mousemove', onMove);
      document.addEventListener('mouseup', endDrag);
    });

    divider.addEventListener('dblclick', () => {
      const w = applyTradesPanelWidth(TRADES_PANEL_DEFAULT);
      saveSetting(LS.tradesPanelWidth, w);
    });

    window.addEventListener('resize', () => {
      // Re-clamp preferred width for the new viewport; do not overwrite LS.
      applyTradesPanelWidth(loadTradesPanelWidth());
    });
  }

  function thresholds() {
    return {
      entry: parseFloat($('entrySel').value),
      exit: parseFloat($('exitSel').value),
      takeProfitPct: parseFloat($('tpSel')?.value || '0') || 0,
    };
  }

  function readHoldParams() {
    const noTrend = parseFloat($('holdNoTrendSel')?.value || '0') || 0;
    const losing = parseFloat($('holdLosingSel')?.value || '0') || 0;
    return {
      maxHoldDaysNoExitTrend: Number.isFinite(noTrend) ? noTrend : 0,
      maxHoldDaysIfLosing: Number.isFinite(losing) ? losing : 0,
    };
  }

  function holdParamsKey() {
    const h = readHoldParams();
    return `hn=${h.maxHoldDaysNoExitTrend}|hl=${h.maxHoldDaysIfLosing}`;
  }

  function computeMinCursor() {
    if (!allPoints.length) return Z_SCORE_ROLLING_MIN_BARS;
    let idx = Z_SCORE_ROLLING_MIN_BARS;
    const startDate = $('startDate').value;
    if (startDate) {
      const ms = new Date(`${startDate}T00:00:00+03:00`).getTime();
      const found = allPoints.findIndex((p) => p.timestampMs >= ms);
      if (found >= 0) idx = Math.max(found, 1);
    }
    return Math.min(idx, allPoints.length - 1);
  }

  /** YYYY-MM-DD в календаре Europe/Moscow. */
  function mskTodayYmd(date = new Date()) {
    return new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Europe/Moscow',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).format(date);
  }

  function ymdAddCalendarMonths(ymd, deltaMonths) {
    const [y0, m0, d0] = ymd.split('-').map((x) => parseInt(x, 10));
    let monthIndex = m0 - 1 + deltaMonths;
    const y = y0 + Math.floor(monthIndex / 12);
    monthIndex = ((monthIndex % 12) + 12) % 12;
    const lastDay = new Date(Date.UTC(y, monthIndex + 1, 0)).getUTCDate();
    const d = Math.min(d0, lastDay);
    return `${y}-${String(monthIndex + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
  }

  function ymdAddCalendarYears(ymd, deltaYears) {
    return ymdAddCalendarMonths(ymd, deltaYears * 12);
  }

  /** Понедельник недели, содержащей ymd (МСК), как первый рабочий день. */
  function firstWorkdayOfWeekYmd(ymd) {
    const noon = new Date(`${ymd}T12:00:00+03:00`);
    const utcDay = noon.getUTCDay(); // 0=Sun … 6=Sat (дата совпадает с МСК в полдень)
    const toMonday = utcDay === 0 ? -6 : 1 - utcDay;
    const mon = new Date(noon.getTime() + toMonday * 86400000);
    return mskTodayYmd(mon);
  }

  function startDateForPreset(preset) {
    const today = mskTodayYmd();
    switch (preset) {
      case '1d':
        return today;
      case '1w':
        return firstWorkdayOfWeekYmd(today);
      case '1m':
        return ymdAddCalendarMonths(today, -1);
      case '3m':
        return ymdAddCalendarMonths(today, -3);
      case '6m':
        return ymdAddCalendarMonths(today, -6);
      case '1y':
        return ymdAddCalendarYears(today, -1);
      case '3y':
        return ymdAddCalendarYears(today, -3);
      default:
        return today;
    }
  }

  function startYmdToMs(ymd) {
    if (!ymd || ymd.length < 10) return NaN;
    return new Date(`${ymd.slice(0, 10)}T00:00:00+03:00`).getTime();
  }

  function endYmdToMs(ymd) {
    if (!ymd || ymd.length < 10) return NaN;
    return new Date(`${ymd.slice(0, 10)}T23:59:59+03:00`).getTime();
  }

  function readWindowEndYmd() {
    return ($('endDate')?.value || '').trim();
  }

  /** Нормализует пару дат: конец ≥ начала; пустой конец допустим. */
  function normalizeWindowRange(startYmd, endYmd) {
    const start = (startYmd || '').trim();
    let end = (endYmd || '').trim();
    if (start && end && end < start) end = start;
    return { start, end };
  }

  function barTimestampMs(p) {
    const ms = p?.timestampMs;
    if (ms != null && Number.isFinite(Number(ms)) && Number(ms) > 0) return Number(ms);
    const td = String(p?.tradeDate || '').replace('T', ' ').trim();
    if (td.length >= 16) return new Date(`${td.slice(0, 16).replace(' ', 'T')}:00+03:00`).getTime();
    if (td.length >= 10) return new Date(`${td.slice(0, 10)}T00:00:00+03:00`).getTime();
    return NaN;
  }

  /** Кэш покрывает запрошенный start, если первый бар не позже начала дня start (МСК). */
  function barsCacheCoversStart(bars, ymd) {
    if (!bars?.length || !ymd) return false;
    const startMs = startYmdToMs(ymd);
    if (!Number.isFinite(startMs)) return false;
    const firstMs = barTimestampMs(bars[0]);
    return Number.isFinite(firstMs) && firstMs <= startMs;
  }

  function sliceBarsFromStart(bars, ymd) {
    if (!bars?.length) return [];
    const startMs = startYmdToMs(ymd);
    if (!Number.isFinite(startMs)) return bars.slice();
    let lo = 0;
    let hi = bars.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      const ms = barTimestampMs(bars[mid]);
      if (Number.isFinite(ms) && ms < startMs) lo = mid + 1;
      else hi = mid;
    }
    return bars.slice(lo);
  }

  function sliceBarsToEnd(bars, ymd) {
    if (!bars?.length) return [];
    if (!ymd) return bars.slice();
    const endMs = endYmdToMs(ymd);
    if (!Number.isFinite(endMs)) return bars.slice();
    let lo = 0;
    let hi = bars.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      const ms = barTimestampMs(bars[mid]);
      if (Number.isFinite(ms) && ms <= endMs) lo = mid + 1;
      else hi = mid;
    }
    return bars.slice(0, lo);
  }

  function sliceBarsWindow(bars, startYmd, endYmd) {
    return sliceBarsToEnd(sliceBarsFromStart(bars, startYmd), endYmd);
  }

  function rememberBarsCache(csv, bars) {
    if (!csv || !bars?.length) return;
    const cur = barsCacheByCsv[csv];
    if (!cur?.length) {
      barsCacheByCsv[csv] = bars;
      return;
    }
    const curFirst = barTimestampMs(cur[0]);
    const curLast = barTimestampMs(cur[cur.length - 1]);
    const newFirst = barTimestampMs(bars[0]);
    const newLast = barTimestampMs(bars[bars.length - 1]);
    if (
      Number.isFinite(newFirst) && Number.isFinite(newLast)
      && Number.isFinite(curFirst) && Number.isFinite(curLast)
      && newFirst <= curFirst && newLast >= curLast
    ) {
      barsCacheByCsv[csv] = bars;
      return;
    }
    if (
      Number.isFinite(newFirst) && Number.isFinite(newLast)
      && Number.isFinite(curFirst) && Number.isFinite(curLast)
      && newFirst >= curFirst && newLast <= curLast
    ) {
      return; // подмножество — оставляем более длинный кэш
    }
    const map = new Map();
    for (const b of cur) {
      const ms = barTimestampMs(b);
      if (Number.isFinite(ms)) map.set(ms, b);
    }
    for (const b of bars) {
      const ms = barTimestampMs(b);
      if (Number.isFinite(ms)) map.set(ms, b);
    }
    barsCacheByCsv[csv] = [...map.values()].sort((a, b) => barTimestampMs(a) - barTimestampMs(b));
  }

  function applyPointsLocally(points, { csv, sourceLabel, seekEnd = false } = {}) {
    allPoints = points || [];
    chartFocusIndex = null;
    selectedTradeId = null;
    updateMoexLastBarHint(allPoints.length ? allPoints[allPoints.length - 1]?.tradeDate : null);
    const csvName = csv || $('csvSel')?.value || '';
    const csvSafe = String(csvName).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const src = sourceLabel || 'cache';
    if ($('meta')) {
      $('meta').innerHTML = `TATN/TATNP · ${allPoints.length} баров · ${csvSafe} · ${src}`;
    }
    rebuildEngine();
    // Period/start change: always finish at the right edge so СДЕЛКИ = full window.
    if (seekEnd && engine) engine.seekToEnd();
    chart?.followReplayEdge(true);
    reapplyLayoutFromStorage();
    if (!chart) {
      chart = new ReplayChart($('chart'), {
        onSelectionChange: (id) => {
          selectedTradeId = id;
          refreshTradesTable();
        },
      });
    } else {
      chart.resize();
    }
    refreshUi({ afterParams: true });
    setTimeout(() => chart?.resize(), 100);
    reapplyLayoutFromStorage();
    requestAnimationFrame(() => {
      reapplyLayoutFromStorage();
      chart?.resize();
    });
  }

  async function applyStartDateAndReload(ymd) {
    await applyWindowAndReload(ymd, readWindowEndYmd());
  }

  async function applyWindowAndReload(startYmd, endYmd) {
    const range = normalizeWindowRange(startYmd, endYmd);
    if (!range.start || !$('startDate')) return;
    $('startDate').value = range.start;
    saveSetting(LS.startDate, range.start);
    if ($('endDate')) {
      $('endDate').value = range.end;
      saveSetting(LS.endDate, range.end);
    }
    syncStartPresetChips();
    // Окно данных сменилось → показать весь загруженный период на графике
    // («Всё» = масштаб, не второе окно данных). Не путать с пресетом «1 мес».
    setVisibleDaysPeriod(400, { fitFull: true });
    scrollRestored = false;
    summaryScrollRestored = false;
    riskCompareScrollRestored = false;
    monthlyPnlScrollRestored = false;
    zHeatmapScrollRestored = false;

    const csv = $('csvSel')?.value || 'm15_tatn_255d.csv';
    // Start window changed → drop tip1m chart stash + sim/heatmap caches so СДЕЛКИ recalculates.
    tip1mBarsCacheKey = '';
    tip1mChartMeta = null;
    m15PointsStash = null;
    tipSimCache = { key: '', rows: null, meta: null, summary: null };
    clearTipManualOverrides();
    tipSimJobId += 1;
    zHeatmapCache = { key: '', cells: null, grid: null, inFlightKey: '' };
    zHeatmapJobId += 1;
    const cache = barsCacheByCsv[csv];
    if (barsCacheCoversStart(cache, range.start)) {
      const sliced = sliceBarsWindow(cache, range.start, range.end);
      applyPointsLocally(sliced, { csv, sourceLabel: 'cache', seekEnd: true });
      if (isTip1mMode()) {
        // Chart + sim in parallel: table fills as soon as sim returns (not after bars1m).
        // При скрытом графике не качаем тяжёлые 1м бары — только симуляцию сделок.
        const chartP = isChartsHidden()
          ? Promise.resolve()
          : activateTip1mChart().catch((e) => {
            console.error(e);
          });
        if (isChartsHidden()) pendingChartRepaint = true;
        scheduleTipSimFetch({ immediate: true });
        await chartP;
      } else {
        // M15: local engine already rebuilt for the new window — refresh PnL/summary.
        refreshTradesTable();
      }
      // Как «В конец»: стоп воспроизведения, курсор и график к правому краю окна.
      jumpToEnd();
      return;
    }

    $('loading').classList.remove('hidden');
    $('app').classList.add('hidden');
    await bootstrap(csv);
    jumpToEnd();
  }

  function syncStartPresetChips() {
    const ymd = $('startDate')?.value;
    const end = readWindowEndYmd();
    const today = mskTodayYmd();
    const endOk = !end || end === today;
    document.querySelectorAll('#startPresetChips .chip[data-start-preset]').forEach((b) => {
      b.classList.toggle(
        'active',
        !!ymd && endOk && startDateForPreset(b.dataset.startPreset) === ymd,
      );
    });
  }

  function setVisibleDaysPeriod(days, { fitFull = false } = {}) {
    visibleDays = days;
    saveSetting(LS.period, visibleDays);
    document.querySelectorAll('#periodChips .chip[data-days]').forEach((b) => {
      b.classList.toggle('active', parseInt(b.dataset.days, 10) === visibleDays);
    });
    if ($('btnFitFullChart')) {
      $('btnFitFullChart').classList.remove('active');
    }
    if (fitFull || visibleDays >= 400) pendingFitFullChart = true;
  }

  function fitLoadedChartRange() {
    const applyFit = () => {
      if (chart && typeof chart.fitFullRange === 'function') chart.fitFullRange();
    };
    applyFit();
    requestAnimationFrame(() => requestAnimationFrame(applyFit));
  }

  /** Показать все уже загруженные бары на экране (zoom-out). Без перезагрузки tip1m. */
  function showFullSimulationChart() {
    chartFocusIndex = null;
    selectedTradeId = null;
    if (chart && typeof chart.selectTrade === 'function') chart.selectTrade(null);
    if (chart && typeof chart.followReplayEdge === 'function') {
      chart.followReplayEdge(false);
    }
    // Масштаб «как Всё»: окно данных не качаем заново — только fit по текущим барам.
    const prevDays = visibleDays;
    if (visibleDays < 400) {
      visibleDays = 400;
      saveSetting(LS.period, visibleDays);
      document.querySelectorAll('#periodChips .chip[data-days]').forEach((b) => {
        b.classList.toggle('active', parseInt(b.dataset.days, 10) === 400);
      });
    }
    if ($('btnFitFullChart')) {
      $('btnFitFullChart').classList.add('active');
    }
    pendingFitFullChart = true;
    if (isChartsHidden()) {
      pendingChartRepaint = true;
      return;
    }
    try {
      refreshUi();
    } catch (_) { /* ignore */ }
    fitLoadedChartRange();
    // Если баров мало и пользователь реально на «Всё» — подгрузка через chip, не через «весь».
    if (prevDays < 400 && isTip1mMode() && tip1mChartNeedsReload()) {
      // Не блокируем UI: fit уже сделан по текущим данным.
      const st = $('status');
      if (st) st.textContent = 'весь экран · текущие бары (для полной истории нажмите «Всё»)';
    }
  }

  function rebuildEngine() {
    const { entry, exit } = thresholds();
    const savedManual = engine?.manualEdges ? [...engine.manualEdges] : [];
    const savedSeq = engine?.manualSeq ?? 0;
    const savedCursor = engine?.cursor;
    const cursorForOpts = typeof savedCursor === 'number' ? savedCursor : (engine?.cursor ?? 0);
    moneyStopDynCache = { key: '', value: 4000 };
    engine = new BarReplayEngine(
      allPoints,
      entry,
      exit,
      computeMinCursor(),
      {
        ...buildActiveSimOpts(cursorForOpts),
        disableSignals: isTip1mMode(),
      },
    );
    engine.manualEdges = savedManual;
    engine.manualSeq = savedSeq;
    riskCompareCache = { key: '', html: '' };
    zHeatmapCache = { key: '', cells: null, grid: null, inFlightKey: '' };
    heatmapSeriesCache = { endIdx: -1, len: 0, series: null };
    zHeatmapJobId += 1;
    statusBadgeCache = { key: '', regimeHtml: '', zoneHtml: '', cascadeHtml: '' };
    uiSeriesCache = {
      cursor: -1,
      edgesLen: -1,
      rangeStart: -1,
      rangeEnd: -1,
      markers: null,
      equityTotal: null,
      equity: null,
      deltaPp: null,
      trades: null,
      cascadeVlines: null,
      cascadeCursorKey: '',
      pnlMode: null,
    };
    if (typeof savedCursor === 'number') {
      engine.seekTo(savedCursor);
    } else {
      engine.rebuildStateToCursor(engine.cursor);
    }
  }

  /** Пересобрать симуляцию и показать весь график от начала до курсора. */
  function rebuildEngineShowFullChart() {
    rebuildEngine();
    pendingFitFullChart = true;
    if (visibleDays < 400) setVisibleDaysPeriod(400, { fitFull: true });
    if (isChartsHidden()) {
      pendingChartRepaint = true;
      return;
    }
    if (chart && typeof chart.followReplayEdge === 'function') {
      chart.followReplayEdge(false);
    }
  }

  /**
   * Быстрая смена порогов/параметров: без принудительного «Всё»,
   * тяжёлое сравнение Risk и extras таблицы — отложены.
   */
  function applyStrategyParamsChange() {
    chartFocusIndex = null;
    rebuildEngine();
    deferRiskCompareOnce = true;
    skipTradeExtrasOnce = true;
    if (enrichAfterParamsTimer) clearTimeout(enrichAfterParamsTimer);
    syncHmStrategyControlsFromToolbar();
    if (isTip1mMode()) {
      tipSimCache = { key: '', rows: null, meta: null, summary: null };
      clearTipManualOverrides();
      markMonthlyPnlPending();
      // Debounced: slider/select spam shouldn't fire N full-year sims.
      scheduleTipSimFetch({ immediate: false });
      refreshUi({ afterParams: true });
      return;
    }
    refreshUi({ afterParams: true });
    enrichAfterParamsTimer = setTimeout(() => {
      enrichAfterParamsTimer = null;
      if (!engine) return;
      // Досчитать Min/Max, Hit1/2/3 и risk-compare в фоне
      skipTradeExtrasOnce = false;
      refreshTradesTable();
    }, 120);
  }

  function persistTradeColumns() {
    saveSetting(LS.tradeColumns, encodeTradeColumns(visibleTradeColumns));
  }

  function renderColumnPicker() {
    const picker = $('columnPicker');
    picker.innerHTML = '';
    const allBtn = document.createElement('button');
    allBtn.type = 'button';
    allBtn.className = 'chip col-chip';
    allBtn.textContent = 'Все';
    allBtn.title = 'Сбросить порядок и показать все столбцы';
    allBtn.addEventListener('click', () => {
      visibleTradeColumns = [...TRADE_COLUMNS_DEFAULT];
      persistTradeColumns();
      renderColumnPicker();
      refreshTradesTable();
    });
    picker.appendChild(allBtn);

    const pickerCols = [
      ...resolveVisibleTradeColumns(visibleTradeColumns),
      ...TRADE_COLUMNS.filter((c) => !visibleTradeColumns.includes(c.key)),
    ];
    pickerCols.forEach((col) => {
      const active = visibleTradeColumns.includes(col.key);
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'chip col-chip' + (active ? ' active' : '');
      btn.dataset.col = col.key;
      btn.textContent = col.title;
      btn.title = (col.hint || col.key) + (active ? ' · перетащите для порядка' : '');
      if (active) {
        bindColumnChipDrag(
          btn,
          () => visibleTradeColumns,
          (keys) => { visibleTradeColumns = keys; persistTradeColumns(); },
          () => { renderColumnPicker(); refreshTradesTable(); },
        );
      }
      btn.addEventListener('click', () => {
        visibleTradeColumns = toggleTradeColumnKey(visibleTradeColumns, col.key);
        persistTradeColumns();
        renderColumnPicker();
        refreshTradesTable();
      });
      picker.appendChild(btn);
    });
  }

  function tradeBadgeForRow(row) {
    return tradeSelectId(row.index);
  }

  function findIndexByTradeDate(tradeDate) {
    if (!tradeDate || tradeDate === '—' || !allPoints.length) return -1;
    const ms = parseTradeMs(tradeDate);
    if (ms == null) return -1;
    let best = -1;
    for (let i = 0; i < allPoints.length; i++) {
      if (allPoints[i].timestampMs === ms) return i;
      if (allPoints[i].timestampMs <= ms) best = i;
      else break;
    }
    return best;
  }

  /**
   * Выделить сделку и подогнать графики под entry→exit.
   * @param {{ seek?: boolean }} opts — seek=true (dblclick): дотянуть курсор вперёд до выхода;
   *   seek=false (click): только pan/fit, курсор не трогаем.
   */
  function focusChartsOnTrade(row, opts = {}) {
    if (!row) return;
    const seek = !!opts.seek;
    if (seek) pausePlayback();

    const entryIdx = findIndexByTradeDate(row.entryDate);
    if (entryIdx < 0 && !isTip1mMode()) return;
    const exitIdx = row.exitDate && row.exitDate !== '—'
      ? findIndexByTradeDate(row.exitDate)
      : (engine ? engine.cursor : -1);
    const endIdx = exitIdx >= 0 ? exitIdx : entryIdx;
    if (entryIdx >= 0) {
      const mid = Math.round((entryIdx + Math.max(entryIdx, endIdx)) / 2);
      chartFocusIndex = mid;
      if (seek && engine && engine.cursor < endIdx) {
        engine.seekTo(endIdx);
      }
    }

    selectedTradeId = tradeBadgeForRow(row);
    if (isChartsHidden()) {
      pendingChartRepaint = true;
      refreshUi();
      return;
    }
    if (!chart) return;
    chart.followReplayEdge(false);
    refreshUi();
    chart.selectTrade(selectedTradeId);
    if (typeof chart.centerOnTrade === 'function') {
      const centered = chart.centerOnTrade(selectedTradeId);
      if (!centered && entryIdx >= 0) {
        requestAnimationFrame(() => chart.centerOnTrade(selectedTradeId));
      }
    }
  }

  /** Двойной клик: fit + при необходимости seek вперёд до выхода. */
  function jumpChartsToTrade(row) {
    focusChartsOnTrade(row, { seek: true });
  }

  function updateOpenTradeOverlay(edges, currentPoint, positionHint) {
    const el = $('openTradeOverlay');
    if (!el) return;
    let overlay = null;
    try {
      overlay = buildOpenTradeOverlay(edges, currentPoint, positionHint);
    } catch (err) {
      console.warn('openTradeOverlay', err);
    }
    if (!overlay) {
      el.classList.add('hidden');
      el.innerHTML = '';
      return;
    }
    const pnlClass = overlay.net > 0 ? 'pnl-pos' : overlay.net < 0 ? 'pnl-neg' : '';
    el.classList.remove('hidden');
    el.innerHTML = [
      `<div class="ot-trade">${overlay.tradePrefix} <span class="ot-pnl ${pnlClass}">${overlay.netText}</span></div>`,
      `<div class="ot-spread">${overlay.spreadLine}</div>`,
      `<div class="ot-duration">${overlay.duration}</div>`,
    ].join('');
  }

  function isShelfFloorCeilingSrc(row) {
    if (!row) return false;
    const src = String(row.source || '').trim().toLowerCase();
    const tag = String(row.tag || '').trim().toLowerCase();
    const reason = String(row.exitReason || row.exit_reason || '').trim().toLowerCase();
    const blob = `${tag} ${src}`;
    if (tag === 'shelf_ff' || tag === 'shelf' || tag === 'полка') return true;
    if (blob.indexOf('shelf_ff') >= 0 || blob.indexOf('полка') >= 0) return true;
    if (blob.indexOf('пол') >= 0 && blob.indexOf('потол') >= 0) return true;
    if (reason === 'shelf_edge' || reason === 'shelf_break') return true;
    if (reason.indexOf('shelf_') === 0 && reason !== 'shelf_displace') return true;
    return false;
  }

  function normalizeTradeSrcFilterKey(row) {
    if (!row) return 'other';
    const src = String(row.source || '').trim();
    const low = src.toLowerCase();
    const tag = String(row.tag || '').trim().toLowerCase();
    const up = src.toUpperCase();

    if (low === 'ручн.' || low === 'manual' || up === 'MANUAL' || up === 'BROKER') return 'manual';
    if (tag === 'addon' || low === 'добор' || (up.indexOf('ADDON') >= 0 && up.indexOf('EXTRA') < 0)) {
      return 'addon';
    }
    if (
      tag === 'extra' || tag === 'extreme' || tag === 'экстра'
      || low === 'экстра' || up.indexOf('EXTRA') >= 0
    ) {
      return 'extra';
    }
    if (isShelfFloorCeilingSrc(row)) {
      return 'shelf';
    }
    if (
      tag === 'main' || tag === 'база' || low === 'база' || low === 'касание'
      || up === 'AUTO' || up === 'AUTO_TP' || up.indexOf('AUTO_MTLR') === 0
      || (up.indexOf('AUTO') === 0 && up.indexOf('ADDON') < 0 && up.indexOf('EXTRA') < 0)
    ) {
      return 'base';
    }
    return 'other';
  }

  function filterRowsByRisk(rows) {
    if (riskFilter === 'no-red') return rows.filter((r) => !r.riskRed);
    if (riskFilter === 'only-red') return rows.filter((r) => r.riskRed);
    if (riskFilter === 'hit1') return rows.filter((r) => r.hitPnl1 || r.hit1Ms != null);
    if (riskFilter === 'hit2') return rows.filter((r) => r.hitPnl2 || r.hit2Ms != null);
    if (riskFilter === 'hit3') return rows.filter((r) => r.hitPnl3 || r.hit3Ms != null);
    return rows;
  }

  function filterRowsBySrc(rows) {
    if (srcFilter === 'all') return rows;
    return rows.filter((r) => normalizeTradeSrcFilterKey(r) === srcFilter);
  }

  function filterTradeRows(rows) {
    return filterRowsBySrc(filterRowsByRisk(rows));
  }

  const SRC_FILTER_LABELS = {
    all: 'Все src',
    base: 'AUTO',
    addon: 'добор',
    extra: 'экстра',
    shelf: 'полка',
    manual: 'ручн.',
  };

  function pnlClass(v) {
    if (v == null || !Number.isFinite(v) || v === 0) return '';
    return v > 0 ? 'pnl-pos' : 'pnl-neg';
  }

  function formatPf(pf) {
    if (pf == null) return '—';
    if (!Number.isFinite(pf)) return '∞';
    return pf.toFixed(2);
  }

  function readSavedTradesSummaryScrollTop() {
    const saved = parseInt(localStorage.getItem(LS.tradesSummaryScrollTop) || '0', 10);
    return Number.isNaN(saved) ? 0 : Math.max(0, saved);
  }

  function readSavedRiskCompareScrollTop() {
    const saved = parseInt(localStorage.getItem(LS.riskCompareScrollTop) || '0', 10);
    return Number.isNaN(saved) ? 0 : Math.max(0, saved);
  }

  function readSavedMonthlyPnlScrollTop() {
    const saved = parseInt(localStorage.getItem(LS.monthlyPnlScrollTop) || '0', 10);
    return Number.isNaN(saved) ? 0 : Math.max(0, saved);
  }

  function readSavedZHeatmapScrollTop() {
    const saved = parseInt(localStorage.getItem(LS.zHeatmapScrollTop) || '0', 10);
    return Number.isNaN(saved) ? 0 : Math.max(0, saved);
  }

  function readSavedTradesScrollTop() {
    const saved = parseInt(localStorage.getItem(LS.tradesScrollTop) || '0', 10);
    return Number.isNaN(saved) ? 0 : Math.max(0, saved);
  }

  function readSavedTradesScrollLeft() {
    const saved = parseInt(localStorage.getItem(LS.tradesScrollLeft) || '0', 10);
    return Number.isNaN(saved) ? 0 : Math.max(0, saved);
  }

  function withSuppressedScrollSave(fn) {
    suppressScrollSave = true;
    try {
      fn();
    } finally {
      // scroll-событие при clamp/layout может прийти с задержкой после set scroll*
      if (suppressScrollSaveTimer) clearTimeout(suppressScrollSaveTimer);
      suppressScrollSaveTimer = setTimeout(() => {
        suppressScrollSave = false;
        suppressScrollSaveTimer = null;
      }, 250);
    }
  }

  /** True when the scrollport can hold the preferred offset (or preferred is 0). */
  function scrollOffsetFit(el, axis, preferred) {
    if (!el || !(preferred > 0)) return true;
    if (axis === 'left') {
      return el.scrollWidth - el.clientWidth >= preferred - 1;
    }
    return el.scrollHeight - el.clientHeight >= preferred - 1;
  }

  /** Re-apply all pane sizes from localStorage (display clamp only; never writes LS). */
  function reapplyLayoutFromStorage() {
    applyTradesPanelWidth(loadTradesPanelWidth());
    if (!isTradesTableHidden()) applyTradesScrollHeight(loadTradesScrollHeight());
    if (!isRiskCompareHidden()) applyRiskCompareHeight(loadRiskCompareHeight());
    if (!isMonthlyPnlHidden()) applyMonthlyPnlHeight(loadMonthlyPnlHeight());
    if (!isZHeatmapHidden()) applyZHeatmapHeight(loadZHeatmapHeight());
    if (!isDeltaPaneHidden()) applyDeltaChartHeight(loadDeltaChartHeight());
    if (!isPnlPaneHidden()) applyPnlChartHeight(loadPnlChartHeight());
  }

  /** Restore vertical scroll after layout exists (#app must not be display:none). */
  function applyTradesSummaryScrollTop(preferredTop) {
    const scrollEl = $('tradesSummaryScroll');
    if (!scrollEl) return;
    const top = preferredTop == null ? readSavedTradesSummaryScrollTop() : preferredTop;
    withSuppressedScrollSave(() => { scrollEl.scrollTop = top; });
    if (!summaryScrollRestored && scrollEl.clientHeight > 0 && scrollOffsetFit(scrollEl, 'top', top)) {
      summaryScrollRestored = true;
    }
  }

  function applyRiskCompareScrollTop(preferredTop) {
    const scrollEl = $('riskCompareScroll');
    if (!scrollEl) return;
    const top = preferredTop == null ? readSavedRiskCompareScrollTop() : preferredTop;
    withSuppressedScrollSave(() => { scrollEl.scrollTop = top; });
    if (!riskCompareScrollRestored && scrollEl.clientHeight > 0 && scrollOffsetFit(scrollEl, 'top', top)) {
      riskCompareScrollRestored = true;
    }
  }

  function applyMonthlyPnlScrollTop(preferredTop) {
    const scrollEl = $('tradesMonthlyPnlScroll');
    if (!scrollEl) return;
    const top = preferredTop == null ? readSavedMonthlyPnlScrollTop() : preferredTop;
    withSuppressedScrollSave(() => { scrollEl.scrollTop = top; });
    if (!monthlyPnlScrollRestored && scrollEl.clientHeight > 0 && scrollOffsetFit(scrollEl, 'top', top)) {
      monthlyPnlScrollRestored = true;
    }
  }

  function applyZHeatmapScrollTop(preferredTop) {
    const scrollEl = $('tradesZHeatmap');
    if (!scrollEl) {
      zHeatmapScrollRestored = true;
      return;
    }
    const top = preferredTop == null ? readSavedZHeatmapScrollTop() : preferredTop;
    withSuppressedScrollSave(() => { scrollEl.scrollTop = top; });
    if (!zHeatmapScrollRestored && scrollEl.clientHeight > 0 && scrollOffsetFit(scrollEl, 'top', top)) {
      zHeatmapScrollRestored = true;
    }
  }

  function restoreTradesSummaryScrollAfterVisible() {
    if (summaryScrollRestored && riskCompareScrollRestored && monthlyPnlScrollRestored && zHeatmapScrollRestored) return;
    requestAnimationFrame(() => {
      applyTradesSummaryScrollTop(readSavedTradesSummaryScrollTop());
      applyRiskCompareScrollTop(readSavedRiskCompareScrollTop());
      applyMonthlyPnlScrollTop(readSavedMonthlyPnlScrollTop());
      applyZHeatmapScrollTop(readSavedZHeatmapScrollTop());
      if (!summaryScrollRestored || !riskCompareScrollRestored || !monthlyPnlScrollRestored || !zHeatmapScrollRestored) {
        requestAnimationFrame(() => {
          applyTradesSummaryScrollTop(readSavedTradesSummaryScrollTop());
          applyRiskCompareScrollTop(readSavedRiskCompareScrollTop());
          applyMonthlyPnlScrollTop(readSavedMonthlyPnlScrollTop());
          applyZHeatmapScrollTop(readSavedZHeatmapScrollTop());
        });
      }
    });
  }

  /** Capital-relative % like «Доходность»: +2.9% / −12.0% (unicode minus). */
  function formatCapitalPct(pct) {
    return `${pct >= 0 ? '+' : '−'}${Math.abs(pct).toFixed(1)}%`;
  }

  function formatRubWithCapitalPct(rub, notional) {
    const pct = notional > 0 ? (rub / notional) * 100 : 0;
    return `${formatRub(rub)} (${formatCapitalPct(pct)})`;
  }

  let riskCompareCache = { key: '', html: '' };
  let riskCompareTimer = null;
  let enrichAfterParamsTimer = null;
  let deferRiskCompareOnce = false;
  let skipTradeExtrasOnce = false;

  function roundZ(v) {
    return Math.round(Number(v) * 100) / 100;
  }

  function isTipSpreadLevelsHeatmap() {
    return true;
  }

  function hmBand() {
    const active = document.querySelector('#hmBandChips .chip.active');
    const b = active?.getAttribute('data-hm-band') || localStorage.getItem(LS.hmBand) || 'wide';
    return b === 'narrow' ? 'narrow' : 'wide';
  }

  function migrateHmSpreadEnterWide61() {
    if (localStorage.getItem(LS.hmSpreadEnterWideV61) === '1') return;
    const raw = localStorage.getItem(LS.hmSpreadEnterWide);
    const n = parseFloat(String(raw || '').replace(',', '.'));
    if (raw == null || raw === '' || (Number.isFinite(n) && Math.abs(n - 6.2) < 1e-9)) {
      localStorage.setItem(LS.hmSpreadEnterWide, String(HM_PROD_WIDE.enter));
    }
    localStorage.setItem(LS.hmSpreadEnterWideV61, '1');
  }

  function readHmSelectedSpreadLevels() {
    migrateHmSpreadEnterWide61();
    const ew = parseFloat(localStorage.getItem(LS.hmSpreadEnterWide) || String(HM_PROD_WIDE.enter));
    const xw = parseFloat(localStorage.getItem(LS.hmSpreadExitWide) || String(HM_PROD_WIDE.exit));
    const en = parseFloat(localStorage.getItem(LS.hmSpreadEnterNarrow) || String(HM_PROD_NARROW.enter));
    const xn = parseFloat(localStorage.getItem(LS.hmSpreadExitNarrow) || String(HM_PROD_NARROW.exit));
    return {
      enter_wide: Number.isFinite(ew) ? ew : HM_PROD_WIDE.enter,
      exit_wide: Number.isFinite(xw) ? xw : HM_PROD_WIDE.exit,
      enter_narrow: Number.isFinite(en) ? en : HM_PROD_NARROW.enter,
      exit_narrow: Number.isFinite(xn) ? xn : HM_PROD_NARROW.exit,
    };
  }

  function writeHmSelectedSpreadLevels(partial) {
    const cur = readHmSelectedSpreadLevels();
    const next = { ...cur, ...partial };
    saveSetting(LS.hmSpreadEnterWide, next.enter_wide);
    saveSetting(LS.hmSpreadExitWide, next.exit_wide);
    saveSetting(LS.hmSpreadEnterNarrow, next.enter_narrow);
    saveSetting(LS.hmSpreadExitNarrow, next.exit_narrow);
    return next;
  }

  function hmSGridMigKey(band) {
    return band === 'narrow' ? LS.hmSGridDefaultsNarrowV1 : LS.hmSGridDefaultsWideV1;
  }

  function applyHmSGridDefaultsForBand(band, { force = false } = {}) {
    const d = band === 'narrow' ? HM_S_NARROW_DEFAULTS : HM_S_WIDE_DEFAULTS;
    const migKey = hmSGridMigKey(band);
    if (!force && localStorage.getItem(migKey) === '1') return;
    if (!force) {
      const entryMax = parseFloat(localStorage.getItem(LS.hmEntryMax) || $('hmEntryMax')?.value || '0');
      const globalMig = localStorage.getItem(LS.hmSGridDefaultsV1) === '1';
      // Уже на spread-сетке или сохранённые значения — не затирать при каждом sync.
      if (globalMig || entryMax > 3) {
        saveSetting(migKey, '1');
        return;
      }
    }
    if ($('hmEntryMin')) $('hmEntryMin').value = String(d.entryMin);
    if ($('hmEntryMax')) $('hmEntryMax').value = String(d.entryMax);
    if ($('hmExitMin')) $('hmExitMin').value = String(d.exitMin);
    if ($('hmExitMax')) $('hmExitMax').value = String(d.exitMax);
    if ($('hmStep')) $('hmStep').value = String(d.step);
    saveSetting(LS.hmEntryMin, d.entryMin);
    saveSetting(LS.hmEntryMax, d.entryMax);
    saveSetting(LS.hmExitMin, d.exitMin);
    saveSetting(LS.hmExitMax, d.exitMax);
    saveSetting(LS.hmStep, d.step);
    saveSetting(migKey, '1');
    saveSetting(LS.hmSGridDefaultsV1, '1');
  }

  function syncZHeatmapModeUi() {
    const sMode = isTipSpreadLevelsHeatmap();
    const bandRow = $('zHeatmapBandControls');
    if (bandRow) bandRow.hidden = !sMode;
    const exitMaxLab = $('hmExitMaxLabel');
    if (exitMaxLab) exitMaxLab.hidden = !sMode;
    const hmNumIds = ['hmEntryMin', 'hmEntryMax', 'hmExitMin', 'hmExitMax'];
    hmNumIds.forEach((id) => {
      const el = $(id);
      if (!el) return;
      if (sMode) {
        el.min = '0.5';
        el.max = '12';
        el.step = '0.1';
      } else if (id === 'hmEntryMin') {
        el.min = '0.3';
        el.max = '5';
        el.step = '0.1';
      } else if (id === 'hmEntryMax') {
        el.min = '0.5';
        el.max = '5';
        el.step = '0.1';
      } else if (id === 'hmExitMin') {
        el.min = '0.1';
        el.max = '4';
        el.step = '0.1';
      } else if (id === 'hmExitMax') {
        el.min = '0.1';
        el.max = '10';
        el.step = '0.1';
      }
    });
    const stepEl = $('hmStep');
    if (stepEl) {
      stepEl.min = sMode ? '0.1' : '0.05';
      stepEl.max = sMode ? '0.5' : '0.5';
      stepEl.step = sMode ? '0.1' : '0.05';
    }
  }

  function readZHeatmapGridParams() {
    const sMode = isTipSpreadLevelsHeatmap();
    const band = hmBand();
    const defs = sMode
      ? (band === 'narrow' ? HM_S_NARROW_DEFAULTS : HM_S_WIDE_DEFAULTS)
      : HM_Z_DEFAULTS;
    const entryMin = roundZ(parseFloat($('hmEntryMin')?.value ?? String(defs.entryMin)));
    const entryMax = roundZ(parseFloat($('hmEntryMax')?.value ?? String(defs.entryMax)));
    const exitMin = roundZ(parseFloat($('hmExitMin')?.value ?? String(defs.exitMin)));
    const exitMaxRaw = parseFloat($('hmExitMax')?.value ?? String(defs.exitMax ?? defs.entryMax));
    let step = roundZ(parseFloat($('hmStep')?.value ?? String(defs.step)));
    if (!Number.isFinite(step) || step < (sMode ? 0.1 : 0.05)) step = defs.step;
    if (step > 0.5) step = 0.5;
    if (sMode) {
      const lo = Number.isFinite(entryMin) ? Math.max(0.5, entryMin) : defs.entryMin;
      const hi = Number.isFinite(entryMax) ? Math.min(12, Math.max(lo, entryMax)) : defs.entryMax;
      const xLo = Number.isFinite(exitMin) ? Math.max(0.5, exitMin) : defs.exitMin;
      const xHi = Number.isFinite(exitMaxRaw) ? Math.min(12, Math.max(xLo, exitMaxRaw)) : (defs.exitMax || hi);
      return {
        entryMin: lo,
        entryMax: hi,
        exitMin: xLo,
        exitMax: xHi,
        step,
        spreadMode: true,
        band,
        triangle: band === 'wide' ? 'enter_gt_exit' : 'enter_lt_exit',
      };
    }
    const lo = Number.isFinite(entryMin) ? Math.max(0.3, entryMin) : 0.5;
    const hi = Number.isFinite(entryMax) ? Math.min(5, Math.max(lo, entryMax)) : 2.5;
    const xLo = Number.isFinite(exitMin) ? Math.max(0.1, exitMin) : 0.3;
    return {
      entryMin: lo,
      entryMax: hi,
      exitMin: xLo,
      exitMax: null,
      step,
      spreadMode: false,
      band: null,
      triangle: 'enter_gt_exit',
    };
  }

  function buildZHeatmapAxes(grid) {
    const entries = [];
    for (let e = grid.entryMin; e <= grid.entryMax + 1e-9; e = roundZ(e + grid.step)) {
      entries.push(roundZ(e));
    }
    const exitHi = grid.exitMax != null
      ? grid.exitMax
      : (grid.entryMax - grid.step);
    const exits = [];
    for (let x = grid.exitMin; x <= exitHi + 1e-9; x = roundZ(x + grid.step)) {
      exits.push(roundZ(x));
    }
    const pairs = [];
    const enterGt = grid.triangle !== 'enter_lt_exit';
    for (const entry of entries) {
      for (const exit of exits) {
        if (enterGt) {
          if (exit < entry - 1e-9) pairs.push({ entry, exit });
        } else if (entry < exit - 1e-9) {
          pairs.push({ entry, exit });
        }
      }
    }
    return { entries, exits, pairs };
  }

  /** End bar for heatmap sweep: full loaded series (not replay cursor). */
  function zHeatmapSimEndIndex() {
    if (!allPoints.length) return -1;
    return allPoints.length - 1;
  }

  /** Short CSV lookback label for heatmap status (phone vs desktop localStorage often differ). */
  function zHeatmapCsvPeriodLabel() {
    const sel = $('csvSel');
    const optText = sel?.selectedOptions?.[0]?.textContent?.trim();
    if (optText) return optText;
    const csv = String(sel?.value || '');
    if (csv.includes('1095')) return '3 года';
    if (csv.includes('365')) return '365д';
    if (csv.includes('255')) return '255д';
    return csv || 'CSV';
  }

  function zHeatmapCacheKey(grid) {
    const t = thresholds();
    const slip = typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0;
    const lv = readHmSelectedSpreadLevels();
    return [
      getEdgeMode(),
      grid.spreadMode ? 's%' : 'z',
      grid.band || '-',
      grid.entryMin, grid.entryMax, grid.exitMin, grid.exitMax ?? '-', grid.step,
      lv.enter_wide, lv.exit_wide, lv.enter_narrow, lv.exit_narrow,
      getSimNotionalRub(), getSimCompound() ? 1 : 0, slip,
      t.takeProfitPct || 0,
      holdParamsKey(),
      isTip1mMode() ? 'risk-ignored' : riskExitSelectionKey(),
      computeMinCursor(), zHeatmapSimEndIndex(), allPoints.length,
      $('csvSel')?.value || '',
      $('startDate')?.value || '',
      readWindowEndYmd(),
      isTip1mMode() ? 1 : 0,
    ].join('|');
  }

  /** Relative red→yellow→green: worst cell = red, best = green (even if all PnL > 0). */
  function zHeatmapColor(pnl, pnlMin, pnlMax) {
    const red = [220, 38, 38];      // яркий красный
    const yellow = [250, 204, 21];
    const green = [22, 163, 74];    // яркий зелёный
    if (!Number.isFinite(pnl) || !Number.isFinite(pnlMin) || !Number.isFinite(pnlMax)) {
      return `rgba(${yellow[0]}, ${yellow[1]}, ${yellow[2]}, 0.45)`;
    }
    const span = pnlMax - pnlMin;
    if (span < 1e-6) {
      // все клетки одинаковые — нейтральный жёлтый
      return `rgba(${yellow[0]}, ${yellow[1]}, ${yellow[2]}, 0.55)`;
    }
    // 0 = худшая (красн.), 1 = лучшая (зел.); степень <1 → сильнее контраст у середины
    let u = (pnl - pnlMin) / span;
    u = Math.max(0, Math.min(1, u));
    const t = Math.pow(u, 0.65);
    let rgb;
    if (t <= 0.5) {
      const w = t / 0.5;
      rgb = [
        Math.round(red[0] + (yellow[0] - red[0]) * w),
        Math.round(red[1] + (yellow[1] - red[1]) * w),
        Math.round(red[2] + (yellow[2] - red[2]) * w),
      ];
    } else {
      const w = (t - 0.5) / 0.5;
      rgb = [
        Math.round(yellow[0] + (green[0] - yellow[0]) * w),
        Math.round(yellow[1] + (green[1] - yellow[1]) * w),
        Math.round(yellow[2] + (green[2] - yellow[2]) * w),
      ];
    }
    // почти непрозрачные — визуально ярче при малом разбросе PnL
    const edge = Math.abs(u - 0.5) * 2; // 0 центр, 1 края
    const a = 0.72 + 0.26 * edge;
    return `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, ${a.toFixed(3)})`;
  }

  function formatZHeatmapCell(pnl) {
    if (!Number.isFinite(pnl)) return '—';
    if (Math.abs(pnl) < 0.5) return '0';
    const sign = pnl > 0 ? '+' : '−';
    const abs = Math.abs(pnl);
    if (abs >= 1_000_000) return `${sign}${(abs / 1_000_000).toFixed(1)}M`;
    if (abs >= 1000) {
      return `${sign}${abs >= 10000 ? Math.round(abs / 1000) : (abs / 1000).toFixed(1)}k`;
    }
    return `${sign}${Math.round(abs)}`;
  }

  /** Min/max PnL клетки сетки (при ничьей — первая по обходу entry↑ exit↑). */
  function findZHeatmapExtremes(cells) {
    let minC = null;
    let maxC = null;
    for (const c of cells || []) {
      if (!Number.isFinite(c?.pnl)) continue;
      if (!minC || c.pnl < minC.pnl - 1e-9
        || (Math.abs(c.pnl - minC.pnl) < 1e-9
          && (c.entry < minC.entry - 1e-9
            || (Math.abs(c.entry - minC.entry) < 1e-9 && c.exit < minC.exit - 1e-9)))) {
        minC = c;
      }
      if (!maxC || c.pnl > maxC.pnl + 1e-9
        || (Math.abs(c.pnl - maxC.pnl) < 1e-9
          && (c.entry < maxC.entry - 1e-9
            || (Math.abs(c.entry - maxC.entry) < 1e-9 && c.exit < maxC.exit - 1e-9)))) {
        maxC = c;
      }
    }
    return { min: minC, max: maxC };
  }

  function setZHeatmapExtremes(cells, grid) {
    const el = $('zHeatmapExtremes');
    if (!el) return;
    const { min, max } = findZHeatmapExtremes(cells);
    if (!min || !max) {
      el.innerHTML = '';
      return;
    }
    const sMode = !!(grid && grid.spreadMode);
    const fmtPair = (c) => sMode
      ? `S ${Number(c.entry).toFixed(1)} / ${Number(c.exit).toFixed(1)}`
      : `±${Number(c.entry).toFixed(1)} / ±${Number(c.exit).toFixed(1)}`;
    el.innerHTML = [
      `<span class="zh-ext zh-ext-max" title="Лучшая клетка сетки (макс. PnL)">`
        + `макс <b>${formatZHeatmapCell(max.pnl)}</b>`
        + ` <span class="zh-ext-pair">${fmtPair(max)}</span>`
        + `</span>`,
      `<span class="zh-ext zh-ext-min" title="Худшая клетка сетки (мин. PnL)">`
        + `мин <b>${formatZHeatmapCell(min.pnl)}</b>`
        + ` <span class="zh-ext-pair">${fmtPair(min)}</span>`
        + `</span>`,
    ].join('');
  }

  function renderZHeatmapHtml(cells, grid, curEntry, curExit, prodMark) {
    const { entries, exits } = buildZHeatmapAxes(grid);
    const map = new Map();
    let pnlMin = Infinity;
    let pnlMax = -Infinity;
    for (const c of cells) {
      map.set(`${c.entry}|${c.exit}`, c);
      if (Number.isFinite(c.pnl)) {
        if (c.pnl < pnlMin) pnlMin = c.pnl;
        if (c.pnl > pnlMax) pnlMax = c.pnl;
      }
    }
    if (!Number.isFinite(pnlMin)) {
      pnlMin = 0;
      pnlMax = 0;
    }
    const { min: extMin, max: extMax } = findZHeatmapExtremes(cells);
    const sMode = !!(grid && grid.spreadMode);
    const enterGt = grid.triangle !== 'enter_lt_exit';
    const head = exits.map((x) => (
      `<th title="${sMode ? 'S% выход' : 'выход'} ${x}">${x.toFixed(1)}</th>`
    )).join('');
    const body = entries.map((entry) => {
      const cellsHtml = exits.map((exit) => {
        const valid = enterGt ? (exit < entry - 1e-9) : (entry < exit - 1e-9);
        if (!valid) {
          return '<td class="zh-na">·</td>';
        }
        const c = map.get(`${entry}|${exit}`);
        const pnl = c?.pnl ?? 0;
        const n = c?.n ?? 0;
        const isCur = Math.abs(entry - curEntry) < 1e-6 && Math.abs(exit - curExit) < 1e-6;
        const isMax = extMax && Math.abs(entry - extMax.entry) < 1e-6 && Math.abs(exit - extMax.exit) < 1e-6;
        const isMin = extMin && Math.abs(entry - extMin.entry) < 1e-6 && Math.abs(exit - extMin.exit) < 1e-6;
        const isProd = prodMark
          && Math.abs(entry - Number(prodMark.entry)) < 1e-6
          && Math.abs(exit - Number(prodMark.exit)) < 1e-6;
        const title = (sMode
          ? `S вх ${entry} · S вых ${exit}`
          : `вход ±${entry} · выход ±${exit}`)
          + ` · PnL ${formatRub(pnl)} · N=${n}`
          + (isMax ? ' · МАКС' : '')
          + (isMin ? ' · МИН' : '')
          + (isProd ? ' · Prod default' : '')
          + (isCur ? ' · текущие' : ' · клик — применить');
        const cls = [
          'zh-cell',
          isCur ? 'is-current' : '',
          isMax ? 'is-hm-max' : '',
          isMin ? 'is-hm-min' : '',
          isProd ? 'is-hm-prod' : '',
        ].filter(Boolean).join(' ');
        return (
          `<td class="${cls}"`
          + ` data-entry="${entry}" data-exit="${exit}"`
          + ` style="background:${zHeatmapColor(pnl, pnlMin, pnlMax)}"`
          + ` title="${title}">${formatZHeatmapCell(pnl)}</td>`
        );
      }).join('');
      return `<tr><th class="zh-y" title="${sMode ? 'S% вход' : 'вход'} ${entry}">${entry.toFixed(1)}</th>${cellsHtml}</tr>`;
    }).join('');
    const corner = sMode ? 'S\\вых' : 'в\\вых';
    return (
      `<table class="z-heatmap-table">`
      + `<thead><tr><th class="zh-corner" title="${sMode ? 'строки = S% вход, столбцы = S% выход' : 'строки = вход Z, столбцы = выход Z'}">${corner}</th>${head}</tr></thead>`
      + `<tbody>${body}</tbody></table>`
    );
  }

  function setZHeatmapStatus(text) {
    const el = $('zHeatmapStatus');
    if (el) el.textContent = text || '';
  }

  function scheduleZHeatmapUpdate(_cursorIndexIgnored, { immediate = false } = {}) {
    if (zHeatmapTimer) {
      clearTimeout(zHeatmapTimer);
      zHeatmapTimer = null;
    }
  }

  async function updateZHeatmap() {
    return;
    const host = $('tradesZHeatmap');
    const scrollEl = $('tradesZHeatmap');
    if (!host) return;
    const prevScrollTop = scrollEl ? scrollEl.scrollTop : 0;
    if (isZHeatmapHidden()) return;
    syncZHeatmapModeUi();
    const endIdx = zHeatmapSimEndIndex();
    if (!allPoints.length || endIdx < 0) {
      host.innerHTML = '<div class="z-heatmap-empty">Нет данных</div>';
      setZHeatmapStatus('');
      setZHeatmapExtremes(null);
      return;
    }
    const grid = readZHeatmapGridParams();
    const key = zHeatmapCacheKey(grid);
    const cur = thresholds();
    const lv = readHmSelectedSpreadLevels();
    const curEntry = grid.spreadMode
      ? (grid.band === 'narrow' ? lv.enter_narrow : lv.enter_wide)
      : cur.entry;
    const curExit = grid.spreadMode
      ? (grid.band === 'narrow' ? lv.exit_narrow : lv.exit_wide)
      : cur.exit;
    const prodMark = grid.spreadMode
      ? (grid.band === 'narrow' ? HM_PROD_NARROW : HM_PROD_WIDE)
      : null;
    if (key === zHeatmapCache.key && zHeatmapCache.cells) {
      host.innerHTML = renderZHeatmapHtml(zHeatmapCache.cells, grid, curEntry, curExit, prodMark);
      applyZHeatmapScrollTop(zHeatmapScrollRestored ? prevScrollTop : readSavedZHeatmapScrollTop());
      setZHeatmapExtremes(zHeatmapCache.cells, grid);
      const slip = typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0;
      setZHeatmapStatus(
        `${zHeatmapCache.cells.length} клеток · ${zHeatmapCsvPeriodLabel()} · весь ряд · slip ${slip}`
        + ` · кап. ${getSimNotionalRub()}₽`,
      );
      return;
    }
    if (playing && zHeatmapCache.cells) {
      host.innerHTML = renderZHeatmapHtml(
        zHeatmapCache.cells, zHeatmapCache.grid || grid, curEntry, curExit, prodMark,
      );
      applyZHeatmapScrollTop(zHeatmapScrollRestored ? prevScrollTop : readSavedZHeatmapScrollTop());
      setZHeatmapExtremes(zHeatmapCache.cells, zHeatmapCache.grid || grid);
      return;
    }
    // Same key already computing — don't cancel/restart (refreshUi would otherwise leave zeros).
    if (key === zHeatmapCache.inFlightKey) {
      const stuckMs = zHeatmapInFlightSince ? (Date.now() - zHeatmapInFlightSince) : 0;
      const limitMs = tip1mSimTimeoutMs() + 5000;
      if (stuckMs < limitMs) return;
      zHeatmapCache = { ...zHeatmapCache, inFlightKey: '' };
      zHeatmapInFlightSince = 0;
    }

    const axes = buildZHeatmapAxes(grid);
    if (!axes.pairs.length) {
      host.innerHTML = grid.triangle === 'enter_lt_exit'
        ? '<div class="z-heatmap-empty">Пустая сетка (нужно enter &lt; exit)</div>'
        : '<div class="z-heatmap-empty">Пустая сетка (нужно exit &lt; entry)</div>';
      setZHeatmapStatus('');
      setZHeatmapExtremes(null);
      return;
    }

    const jobId = ++zHeatmapJobId;
    zHeatmapInFlightSince = Date.now();
    zHeatmapCache = { ...zHeatmapCache, inFlightKey: key };

    if (isTip1mMode()) {
      const modeRu = grid.spreadMode
        ? (grid.band === 'narrow' ? 'S% узкий Long' : 'S% шир. Short')
        : 'Z±';
      const timeoutMs = tip1mSimTimeoutMs();
      const timeoutSec = Math.round(timeoutMs / 1000);
      const t0 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
      const tickStatus = () => {
        const sec = Math.max(0, Math.round((Date.now() - zHeatmapInFlightSince) / 1000));
        setZHeatmapStatus(
          `Считаю heatmap касания 1м (${modeRu}, ${axes.pairs.length} клеток)… ${sec} с`
          + ` · сервер :8765 · лимит ${timeoutSec} с`,
        );
      };
      tickStatus();
      const tickTimer = setInterval(tickStatus, 500);
      try {
        const data = await fetchTipHeatmap(grid, { timeoutMs });
        if (jobId !== zHeatmapJobId) return;
        const cells = (data.cells || []).map((c) => ({
          entry: c.entry,
          exit: c.exit,
          pnl: c.pnl,
          n: c.n,
        }));
        const mark = data.meta?.prodMark || prodMark;
        zHeatmapCache = { key, cells, grid, inFlightKey: '' };
        zHeatmapInFlightSince = 0;
        host.innerHTML = renderZHeatmapHtml(cells, grid, curEntry, curExit, mark);
        applyZHeatmapScrollTop(zHeatmapScrollRestored ? prevScrollTop : readSavedZHeatmapScrollTop());
        setZHeatmapExtremes(cells, grid);
        const slip = typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0;
        const t1 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
        const sec = Math.max(0.01, (t1 - t0) / 1000);
        const hmSec = data.meta?.heatmapSec != null ? data.meta.heatmapSec : sec.toFixed(1);
        const cacheNote = data.meta?.heatmapCacheHit ? ' · кэш' : '';
        const tierNote = data.meta?.cacheTier === 'build' ? ' · 1м сборка' : '';
        setZHeatmapStatus(
          `${cells.length} клеток · касание 1м · ${modeRu} · сервер ${hmSec}с${cacheNote}${tierNote}`
          + ` · ${zHeatmapCsvPeriodLabel()} · slip ${slip}`
          + ` · кап. ${getSimNotionalRub()}₽ · клик = уровни теста`,
        );
      } catch (e) {
        if (jobId !== zHeatmapJobId) return;
        zHeatmapCache = { ...zHeatmapCache, inFlightKey: '' };
        zHeatmapInFlightSince = 0;
        const timedOut = e && e.name === 'AbortError';
        const msg = timedOut
          ? `таймаут ${timeoutSec} с — сервер :8765 не ответил (смотрите python в диспетчере задач / перезапуск replay)`
          : ((e && e.message) ? String(e.message).slice(0, 160) : String(e));
        host.innerHTML = `<div class="z-heatmap-empty">Heatmap 1м: ${msg}</div>`;
        setZHeatmapStatus('heatmap касания 1м — ошибка сервера');
        setZHeatmapExtremes(null);
      } finally {
        clearInterval(tickTimer);
        if (zHeatmapCache.inFlightKey === key) {
          zHeatmapCache = { ...zHeatmapCache, inFlightKey: '' };
          zHeatmapInFlightSince = 0;
        }
      }
      return;
    }

    // Risk opts use end-of-data (same window as heatmap), not replay cursor.
    const simOpts = buildActiveSimOpts(endIdx);
    const useFast = !grid.spreadMode
      && typeof prepareHeatmapSeries === 'function' && typeof simOptsAreHeatmapFast === 'function'
      && simOptsAreHeatmapFast(simOpts);
    let prepared = null;
    if (useFast) {
      if (
        heatmapSeriesCache.series
        && heatmapSeriesCache.endIdx === endIdx
        && heatmapSeriesCache.len === allPoints.length
      ) {
        prepared = heatmapSeriesCache.series;
      } else {
        prepared = prepareHeatmapSeries(allPoints, endIdx);
        heatmapSeriesCache = { endIdx, len: allPoints.length, series: prepared };
      }
    }
    const slowWhy = (!prepared && typeof heatmapSlowReason === 'function')
      ? heatmapSlowReason(simOpts)
      : '';
    const tpOn = !!(simOpts && simOpts.takeProfitPct);
    setZHeatmapStatus(
      prepared
        ? `Считаю ${axes.pairs.length} клеток (быстро${tpOn ? '+TP' : ''})…`
        : `Считаю ${axes.pairs.length} клеток (медленно${slowWhy ? `: ${slowWhy}` : ''})…`,
    );
    const cells = [];
    // Быстрый путь: крупные чанки / почти без пауз
    const CHUNK = prepared ? 200 : 3;
    const t0 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
    for (let i = 0; i < axes.pairs.length; i++) {
      if (jobId !== zHeatmapJobId) return;
      const { entry, exit } = axes.pairs[i];
      let sum;
      if (grid.spreadMode) {
        const base = typeof readHmSelectedSpreadLevels === 'function'
          ? readHmSelectedSpreadLevels()
          : {};
        const lv = grid.band === 'narrow'
          ? { ...base, enter_narrow: entry, exit_narrow: exit }
          : { ...base, enter_wide: entry, exit_wide: exit };
        const eng = new BarReplayEngine(allPoints, 0, 0, endIdx, { ...simOpts, spreadLevels: lv });
        sum = sumClosedNetFromEdges(eng.edges);
      } else {
        sum = typeof heatmapCellNetPnl === 'function'
          ? heatmapCellNetPnl(allPoints, entry, exit, endIdx, simOpts, prepared)
          : (() => {
            const eng = new BarReplayEngine(allPoints, entry, exit, endIdx, simOpts);
            return sumClosedNetFromEdges(eng.edges);
          })();
      }
      cells.push({ entry, exit, pnl: sum.totalPnl, n: sum.closedCount });
      if (i % CHUNK === CHUNK - 1) {
        setZHeatmapStatus(
          prepared
            ? `Считаю ${i + 1}/${axes.pairs.length}…`
            : `Считаю ${i + 1}/${axes.pairs.length} (медленно${slowWhy ? `: ${slowWhy}` : ''})…`,
        );
        await Promise.resolve();
      }
    }
    if (jobId !== zHeatmapJobId) return;
    zHeatmapCache = { key, cells, grid, inFlightKey: '' };
    host.innerHTML = renderZHeatmapHtml(cells, grid, cur.entry, cur.exit, null);
    applyZHeatmapScrollTop(zHeatmapScrollRestored ? prevScrollTop : readSavedZHeatmapScrollTop());
    setZHeatmapExtremes(cells, grid);
    const slip = typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0;
    const nMax = cells.reduce((m, c) => Math.max(m, c.n || 0), 0);
    const t1 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
    const sec = Math.max(0.01, (t1 - t0) / 1000);
    setZHeatmapStatus(
      `${cells.length} клеток · ${zHeatmapCsvPeriodLabel()} · весь ряд · ${sec < 1 ? sec.toFixed(2) : sec.toFixed(1)}с`
      + (prepared ? ` · быстро${tpOn ? '+TP' : ''}` : (slowWhy ? ` · медленно (${slowWhy})` : ' · медленно'))
      + ` · slip ${slip} · кап. ${getSimNotionalRub()}₽`
      + (nMax > 0 ? ' · клик по клетке = пороги теста' : ' · N=0 — проверьте дату старта / данные'),
    );
  }

  function updateRiskCompare(_cursorIndex) {
    /* Таблица сравнения Risk exit скрыта (T48/H48/Money неактуальны). */
    const el = $('riskCompare');
    if (el) el.innerHTML = '';
  }

  const TAG_SHARE_SPEC = [
    { key: 'base', api: 'main', label: 'База', color: '#22d3ee' },
    { key: 'addon', api: 'addon', label: 'добор', color: '#c084fc' },
    { key: 'extra', api: 'extra', label: 'экстра', color: '#a78bfa' },
    { key: 'shelf', api: 'shelf_ff', label: 'полка', color: '#7c3aed' },
    { key: 'weekend', api: 'weekend', label: 'выходные', color: '#f59e0b' },
  ];

  function emptyTagSharePnl() {
    return { base: 0, addon: 0, extra: 0, shelf: 0, weekend: 0 };
  }

  function isWeekendChipEntry(entryDate) {
    const s = String(entryDate || '').replace('T', ' ').trim();
    if (s.length < 10 || s[4] !== '-' || s[7] !== '-') return false;
    const y = Number(s.slice(0, 4));
    const mo = Number(s.slice(5, 7));
    const d = Number(s.slice(8, 10));
    if (!y || !mo || !d) return false;
    const dow = new Date(Date.UTC(y, mo - 1, d, 12, 0, 0)).getUTCDay();
    if (dow !== 0 && dow !== 6) return false;
    if (s.length < 16) return true;
    const hm = s.slice(11, 16);
    return hm >= '10:00' && hm < '19:00';
  }

  function parseByTagPnl(summary) {
    const raw = summary && (summary.by_tag || summary.byTag);
    if (!raw || typeof raw !== 'object') return null;
    const out = emptyTagSharePnl();
    let any = false;
    for (const spec of TAG_SHARE_SPEC) {
      const cell = raw[spec.api] != null ? raw[spec.api] : raw[spec.key];
      if (cell == null) continue;
      const pnl = typeof cell === 'number' ? Number(cell) : Number(cell.pnlRub ?? cell.pnl ?? 0);
      if (Number.isFinite(pnl)) {
        out[spec.key] = pnl;
        any = true;
      }
    }
    return any ? out : null;
  }

  function collectTagShareFromRows(rows) {
    const out = emptyTagSharePnl();
    const weekendOn = typeof isWeekendTradingMode === 'function' && isWeekendTradingMode();
    for (const r of rows || []) {
      if (!r) continue;
      const k = normalizeTradeSrcFilterKey(r);
      if (k !== 'base' && k !== 'addon' && k !== 'extra' && k !== 'shelf') continue;
      let pnl;
      if (r.status === 'Открыта') {
        pnl = Number(r.openMtm != null ? r.openMtm : r.mtm);
      } else if (r.status === 'Закрыта') {
        pnl = Number(r.netValue);
      } else {
        continue;
      }
      if (!Number.isFinite(pnl)) continue;
      if (weekendOn && isWeekendChipEntry(r.entryDate)) out.weekend += pnl;
      else out[k] += pnl;
    }
    return out;
  }

  function formatTagShareRub(rub) {
    const n = Number(rub) || 0;
    const sign = n > 0 ? '+' : '';
    const body = typeof formatAccountRub === 'function'
      ? formatAccountRub(n)
      : String(Math.round(n));
    return `${sign}${body} ₽`;
  }

  function formatTagSharePct(pct, rub) {
    if (!Number.isFinite(pct)) return '0%';
    if (Math.abs(Number(rub) || 0) < 1e-9 && Math.abs(pct) < 0.5) return '0%';
    if (Math.abs(pct) < 0.5 && Math.abs(Number(rub) || 0) >= 1e-9) return '<1%';
    return `${Math.round(pct)}%`;
  }

  function renderTagShareDonut() {
    const host = $('tagShareDonut');
    if (!host) return;
    const tipRows = tipSimCache.rows
      ? (typeof tipRowsUpToCursor === 'function'
        ? tipRowsUpToCursor(tipSimCache.rows)
        : tipSimCache.rows)
      : [];
    const fromRows = collectTagShareFromRows(tipRows);
    const fromApi = parseByTagPnl(tipSimCache.summary);
    const tagMode = String(tipSimCache.summary?.by_tag_mode || '');
    const pnl = { ...(fromApi || fromRows) };
    if (typeof isWeekendTradingMode === 'function' && !isWeekendTradingMode()) {
      pnl.weekend = 0;
    }
    if (tagMode !== 'chip_delta') {
      const rowShelf = Number(fromRows.shelf) || 0;
      const apiShelf = Number(pnl.shelf) || 0;
      if (Math.abs(apiShelf) < 1e-9 && Math.abs(rowShelf) >= 1e-9) {
        pnl.shelf = rowShelf;
        pnl.base = (Number(pnl.base) || 0) - rowShelf;
      }
    }
    const notional = typeof getSimNotionalRub === 'function' ? getSimNotionalRub() : 0;
    const slices = TAG_SHARE_SPEC.map((spec) => {
      const val = Number(pnl[spec.key]) || 0;
      const pct = notional > 0 ? (val / notional) * 100 : 0;
      return { ...spec, val, pct };
    });
    const maxAbs = slices.reduce((m, s) => Math.max(m, Math.abs(s.val)), 0);
    const bars = slices.map((s) => {
      const h = maxAbs > 0 ? (Math.abs(s.val) / maxAbs) * 100 : 0;
      const barH = h > 0 && h < 4 ? 4 : h;
      return (
        `<div class="tag-share-bar-col" title="${s.label}: вклад чипа в итог (вкл − выкл)">`
        + `<div class="tag-share-bar${s.val < 0 ? ' is-neg' : ''}"`
        + ` style="height:${barH.toFixed(1)}%;background:${s.color}"></div>`
        + `</div>`
      );
    }).join('');
    const legend = slices.map((s) => {
      const cls = pnlClass(s.val);
      return (
        `<div class="tag-share-row" title="${s.label}: ${formatTagSharePct(s.pct, s.val)} от капитала · ${formatTagShareRub(s.val)}">`
        + `<span class="tag-share-dot" style="background:${s.color}"></span>`
        + `<span class="tag-share-name">${s.label}</span>`
        + `<span class="tag-share-pct">${formatTagSharePct(s.pct, s.val)}</span>`
        + `<span class="tag-share-rub ${cls}">${formatTagShareRub(s.val)}</span>`
        + `</div>`
      );
    }).join('');
    host.innerHTML =
      `<div class="tag-share-bars" aria-hidden="true">${bars}</div>`
      + `<div class="tag-share-legend">${legend}</div>`;
  }

  function updateTradesSummary(allRows, visibleRows) {
    const grid = $('tradesSummaryGrid');
    const scrollEl = $('tradesSummaryScroll');
    if (!grid) return;
    const prevScrollTop = scrollEl ? scrollEl.scrollTop : 0;
    const notional = getSimNotionalRub();
    const summary = buildTradeSimSummary(visibleRows, notional);
    const allSummary = buildTradeSimSummary(allRows, notional);
    lastMaxDd = summary.maxDd;
    lastMaxDdPct = summary.maxDdPct || 0;
    lastTotalPnl = summary.totalPnl || 0;
    updatePnlLabel(summary.maxDd, lastMaxDdPct, lastTotalPnl);
    const hiddenRed = riskFilter === 'no-red'
      ? allSummary.redCount
      : 0;
    const profitRub = Number.isFinite(summary.profitRub)
      ? summary.profitRub
      : ((summary.totalPnl || 0) + (summary.openMtm || 0));
    const profitPct = Number.isFinite(summary.retPct)
      ? summary.retPct
      : (notional > 0 ? (profitRub / notional) * 100 : 0);
    const profitText = typeof formatProfitAccountRub === 'function'
      ? formatProfitAccountRub(profitRub)
      : formatRub(profitRub);
    const items = [
      {
        label: 'Итого PnL',
        value: profitText,
        cls: pnlClass(profitRub),
        wide: true,
        hint: 'Чистая прибыль: закрытый net + MTM открытых. Без депозита и без тела вложений.',
      },
      { label: 'Доходность', value: formatCapitalPct(profitPct), cls: pnlClass(profitPct) },
    ];
    items.push(
      {
        label: 'Макс. просадка',
        value: summary.maxDd > 0
          ? `${formatRub(-summary.maxDd)} (${formatCapitalPct(-lastMaxDdPct)})`
          : '0',
        cls: summary.maxDd > 0 ? 'pnl-neg' : '',
      },
      { label: 'Сделки', value: `${summary.closedCount}${summary.openCount ? ` +${summary.openCount} откр.` : ''}` },
      { label: 'Win rate', value: `${summary.winCount}W / ${summary.lossCount}L · ${summary.winRate.toFixed(0)}%` },
      { label: 'Средний PnL', value: formatRubWithCapitalPct(summary.avgTrade, notional), cls: pnlClass(summary.avgTrade) },
      { label: 'Ср. win / loss', value: `${formatRub(summary.avgWin)} / ${formatRub(summary.avgLoss)}` },
      { label: 'Long', value: `${summary.longCount} · ${formatRub(summary.longPnl)}`, cls: pnlClass(summary.longPnl) },
      { label: 'Short', value: `${summary.shortCount} · ${formatRub(summary.shortPnl)}`, cls: pnlClass(summary.shortPnl) },
      { label: 'Profit factor', value: formatPf(summary.profitFactor) },
      { label: 'Красная зона', value: `${allSummary.redCount} из ${allSummary.closedCount}` },
    );
    grid.innerHTML = items.map((it) => {
      const hint = it.hint
        ? ` title="${String(it.hint).replace(/"/g, '&quot;')}"`
        : '';
      return (
        `<div class="trades-summary-item${it.wide ? ' wide' : ''}"${hint}`
        + `${it.wide ? ` data-profit-rub="${profitRub}"` : ''}>`
        + `<span class="ts-label">${it.label}</span>`
        + `<span class="ts-value ${it.cls || ''}">${it.value}</span>`
        + `</div>`
      );
    }).join('');
    if (hiddenRed > 0) {
      grid.insertAdjacentHTML(
        'beforeend',
        `<div class="trades-summary-note wide">Скрыто ${hiddenRed} в красной зоне (score ≥ 4).</div>`,
      );
    } else if (riskFilter === 'only-red' && allSummary.redCount === 0 && allSummary.closedCount > 0) {
      grid.insertAdjacentHTML(
        'beforeend',
        `<div class="trades-summary-note wide">Нет сделок в красной зоне.</div>`,
      );
    } else if (riskFilter === 'hit1' || riskFilter === 'hit2' || riskFilter === 'hit3') {
      const pct = riskFilter === 'hit1' ? '1' : (riskFilter === 'hit2' ? '2' : '3');
      const n = visibleRows.filter((r) => r.status === 'Закрыта' || r.status === 'Открыта').length;
      grid.insertAdjacentHTML(
        'beforeend',
        `<div class="trades-summary-note wide">Фильтр ≥${pct}% MTM: ${n} сделок (достигали порога в удержании).</div>`,
      );
    }
    if (srcFilter !== 'all') {
      const label = SRC_FILTER_LABELS[srcFilter] || srcFilter;
      const n = visibleRows.filter((r) => r.status === 'Закрыта' || r.status === 'Открыта').length;
      grid.insertAdjacentHTML(
        'beforeend',
        `<div class="trades-summary-note wide">Фильтр Src «${label}»: ${n} сделок.</div>`,
      );
    }
    renderMonthlyPnl(visibleRows);
    if (engine) updateRiskCompare(engine.cursor);
    // Prefer in-session position after risk-filter / table rebuild; LS only until first visible restore.
    applyTradesSummaryScrollTop(summaryScrollRestored ? prevScrollTop : readSavedTradesSummaryScrollTop());
    renderTagShareDonut();
  }

  /**
   * Monthly hist %:
   * - Кап. OFF → PnL / initial notional
   * - Кап. ON  → PnL / equity at month start (compound)
   * Plot scale uses monthly % so Y-axis matches bar labels; ₽ stay on footers / «ср.».
   */
  function formatMonthlyHistPctValue(pct) {
    if (!Number.isFinite(pct)) return '—%';
    return `${pct >= 0 ? '+' : '−'}${Math.abs(pct).toFixed(0)}%`;
  }

  function formatMonthlyHistAbs(rub) {
    return `(${formatRub(rub)})`;
  }

  /** Compact abs for Y ticks: +50k / +5.0k (chart-style). */
  function formatMonthlyYAbs(rub) {
    if (typeof rub !== 'number' || Number.isNaN(rub)) return '—';
    const sign = rub > 0 ? '+' : rub < 0 ? '−' : '';
    const abs = Math.abs(rub);
    if (abs >= 1_000_000) return `${sign}${(abs / 1_000_000).toFixed(1)}M`;
    if (abs >= 1000) {
      return `${sign}${abs >= 10000 ? Math.round(abs / 1000) : (abs / 1000).toFixed(1)}k`;
    }
    return `${sign}${Math.round(abs)}`;
  }

  /** Y tick: always %; with fixed notional also show matching ₽ (compound has no single ₽↔% map). */
  function formatMonthlyYTick(pct, notional, compound) {
    if (!Number.isFinite(pct) || Math.abs(pct) < 1e-9) return '0%';
    const rounded = Math.round(pct);
    const pctText = `${rounded >= 0 ? '+' : '−'}${Math.abs(rounded)}%`;
    if (compound || !(notional > 0)) return pctText;
    const rub = (rounded / 100) * notional;
    return `${pctText} (${formatMonthlyYAbs(rub)})`;
  }

  /**
   * «ср.» = arithmetic mean of monthly % (same basis as bars / Y),
   * plus mean monthly ₽ in parentheses.
   */
  function formatMonthlyMeanLabel(meanPct, meanRub) {
    const pctText = formatMonthlyHistPctValue(meanPct);
    return `ср. ${pctText} ${formatMonthlyHistAbs(meanRub)}`;
  }

  /** Nice round %-ticks covering [minPct, maxPct]; always includes 0. */
  function monthlyPnlYTicks(minPct, maxPct) {
    let lo = Math.min(0, minPct);
    let hi = Math.max(0, maxPct);
    if (!Number.isFinite(lo) || !Number.isFinite(hi) || hi - lo < 1e-9) {
      return [0];
    }
    const span = hi - lo;
    const rough = span / 4;
    const niceSteps = [1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000];
    let step = niceSteps[niceSteps.length - 1];
    for (const s of niceSteps) {
      if (s >= rough) {
        step = s;
        break;
      }
    }
    const start = Math.floor(lo / step) * step;
    const end = Math.ceil(hi / step) * step;
    const ticks = [];
    for (let v = start; v <= end + step * 0.5; v += step) {
      const t = Math.round(v * 1000) / 1000;
      if (t >= start - 1e-9 && t <= end + 1e-9) ticks.push(t);
    }
    if (!ticks.includes(0)) ticks.push(0);
    ticks.sort((a, b) => a - b);
    return ticks;
  }

  function markMonthlyPnlPending() {
    const el = $('tradesMonthlyPnl');
    if (!el) return;
    el.classList.add('is-pending');
    el.dataset.pending = '1';
    el.removeAttribute('data-month-keys');
    el.removeAttribute('data-mean-pct');
    el.removeAttribute('data-mean-rub');
    el.innerHTML = (
      `<div class="trades-monthly-title">`
      + `<span class="tm-title-text">PnL по месяцам</span>`
      + `<span class="tm-mean-badge">пересчёт…</span>`
      + `</div>`
      + `<div class="trades-monthly-empty">пересчёт сделок…</div>`
    );
  }

  function renderMonthlyPnl(visibleRows) {
    const el = $('tradesMonthlyPnl');
    const scrollEl = $('tradesMonthlyPnlScroll');
    if (!el) return;
    const prevScrollTop = scrollEl ? scrollEl.scrollTop : 0;
    const notional = getSimNotionalRub();
    const compound = getSimCompound();
    const months = typeof buildMonthlyPnl === 'function'
      ? buildMonthlyPnl(visibleRows, { notional, compound })
      : [];
    el.classList.remove('is-pending');
    el.dataset.pending = '0';
    if (!months.length) {
      el.dataset.monthKeys = '';
      el.dataset.meanPct = '';
      el.dataset.meanRub = '';
      el.innerHTML = (
        `<div class="trades-monthly-title">PnL по месяцам</div>`
        + `<div class="trades-monthly-empty">Нет закрытых сделок</div>`
      );
      applyMonthlyPnlScrollTop(monthlyPnlScrollRestored ? prevScrollTop : readSavedMonthlyPnlScrollTop());
      return;
    }
    const dense = months.length > 8;
    const histCls = `trades-monthly-hist${dense ? ' dense' : ''}`;

    let minPct = 0;
    let maxPct = 0;
    let sumPct = 0;
    let sumPnl = 0;
    for (const m of months) {
      const pct = Number.isFinite(m.pct) ? m.pct : 0;
      if (pct < minPct) minPct = pct;
      if (pct > maxPct) maxPct = pct;
      sumPct += pct;
      sumPnl += m.pnl;
    }
    const meanPct = sumPct / months.length;
    const meanPnl = sumPnl / months.length;
    el.dataset.monthKeys = months.map((m) => m.key).join(',');
    el.dataset.meanPct = String(meanPct);
    el.dataset.meanRub = String(meanPnl);
    const yMin = Math.min(0, minPct);
    const yMax = Math.max(0, maxPct);

    const yTicks = monthlyPnlYTicks(yMin, yMax);
    // Expand plot range to nice tick extents so top/bottom ticks sit on edges.
    const tickMinPct = Math.min(...yTicks, 0);
    const tickMaxPct = Math.max(...yTicks, 0);
    const plotMin = Math.min(yMin, tickMinPct);
    const plotMax = Math.max(yMax, tickMaxPct);
    const plotRange = plotMax - plotMin || 1;
    const zeroBottomPlot = ((0 - plotMin) / plotRange) * 100;
    const meanBottomPlot = ((meanPct - plotMin) / plotRange) * 100;

    const yLabelsHtml = yTicks.map((t) => {
      const bottom = ((t - plotMin) / plotRange) * 100;
      return `<span class="tm-y-label" style="bottom:${bottom.toFixed(2)}%">${formatMonthlyYTick(t, notional, compound)}</span>`;
    }).join('');
    const gridHtml = yTicks.map((t) => {
      const bottom = ((t - plotMin) / plotRange) * 100;
      const isZero = Math.abs(t) < 1e-9;
      return `<span class="tm-grid-line${isZero ? ' tm-grid-zero' : ''}" style="bottom:${bottom.toFixed(2)}%"></span>`;
    }).join('');

    const meanCls = meanPct > 0 ? 'pos' : meanPct < 0 ? 'neg' : '';
    /* Mean text lives in the title (outside bar hover / native title tip zone) */
    const meanLabel = formatMonthlyMeanLabel(meanPct, meanPnl);
    const meanTitle = compound
      ? 'Среднее месячных %: PnL месяца / эквити на начало месяца; ₽ — среднее месячных PnL'
      : 'Среднее месячных %: PnL месяца / начальный капитал; ₽ — среднее месячных PnL';
    const titleHint = compound
      ? 'PnL по месяцам · % от эквити на начало месяца (капит.)'
      : 'PnL по месяцам · % от начального капитала';

    el.innerHTML = (
      `<div class="trades-monthly-title" title="${titleHint}">`
      + `<span class="tm-title-text">PnL по месяцам</span>`
      + `<span class="tm-mean-badge ${meanCls}" title="${meanTitle}">${meanLabel}</span>`
      + `</div>`
      + `<div class="${histCls}">`
      + `<div class="tm-hist-scale">`
      + `<div class="tm-hist-y-col" aria-hidden="true">`
      + `<div class="tm-hist-y">${yLabelsHtml}</div>`
      + `<div class="tm-hist-y-spacer"></div>`
      + `</div>`
      + `<div class="tm-hist-plot">`
      + `<div class="tm-hist-grid">`
      + gridHtml
      + `<div class="tm-mean-line ${meanCls}" style="bottom:${meanBottomPlot.toFixed(2)}%"></div>`
      + `</div>`
      + `<div class="tm-hist-bars">`
      + months.map((m, mi) => {
        const pct = Number.isFinite(m.pct) ? m.pct : 0;
        const barCls = pct > 0 ? 'pos' : pct < 0 ? 'neg' : 'flat';
        const barH = Math.max(0, (Math.abs(pct) / plotRange) * 100);
        let barStyle;
        if (pct >= 0) {
          barStyle = `bottom:${zeroBottomPlot.toFixed(2)}%;height:${barH.toFixed(2)}%`;
        } else {
          barStyle = `bottom:${(zeroBottomPlot - barH).toFixed(2)}%;height:${barH.toFixed(2)}%`;
        }
        const pctLabel = formatMonthlyHistPctValue(pct);
        const absLabel = formatMonthlyHistAbs(m.pnl);
        const basisHint = compound
          ? `эквити ${formatRub(m.equityStart)}`
          : `кап. ${formatRub(notional)}`;
        const monthYear = String(m.key || '').slice(0, 4);
        const prevYear = mi > 0 ? String(months[mi - 1].key || '').slice(0, 4) : null;
        const monthLabel = dense
          ? (prevYear && prevYear === monthYear
            ? String(m.label || '').replace(/\s+\d+$/, '')
            : m.label)
          : m.label;
        return (
          `<div class="tm-col" title="${m.label} · ${pctLabel} ${absLabel} · ${basisHint} · ${m.count} сд.">`
          + `<span class="tm-bar-track">`
          + `<span class="tm-bar ${barCls}" style="${barStyle}"></span>`
          + `</span>`
          + `<span class="tm-foot">`
          + `<span class="tm-pnl ${pnlClass(m.pnl)}">`
          + `<span class="tm-pnl-pct">${pctLabel}</span>`
          + `<span class="tm-pnl-abs">${absLabel}</span>`
          + `</span>`
          + `<span class="tm-month">${monthLabel}</span>`
          + `</span>`
          + `</div>`
        );
      }).join('')
      + `</div></div></div></div>`
    );
    applyMonthlyPnlScrollTop(monthlyPnlScrollRestored ? prevScrollTop : readSavedMonthlyPnlScrollTop());
  }

  function syncRiskFilterChips() {
    document.querySelectorAll('#tradesRiskFilters .risk-filter').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.riskFilter === riskFilter);
    });
    document.querySelectorAll('#tradesRiskFilters .src-filter').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.srcFilter === srcFilter);
    });
  }

  function ensureTradeMetricTip() {
    if (tradeMetricTipEl) return tradeMetricTipEl;
    const el = document.createElement('div');
    el.id = 'tradeMetricTip';
    el.className = 'trade-metric-tip hidden';
    el.setAttribute('role', 'tooltip');
    document.body.appendChild(el);
    tradeMetricTipEl = el;
    return el;
  }

  function hideTradeMetricTip() {
    if (tradeMetricTipHideTimer) {
      clearTimeout(tradeMetricTipHideTimer);
      tradeMetricTipHideTimer = null;
    }
    const tip = tradeMetricTipEl;
    if (tip) tip.classList.add('hidden');
  }

  function positionTradeMetricTip(tip, anchorEl) {
    const rect = anchorEl.getBoundingClientRect();
    const tipRect = tip.getBoundingClientRect();
    const pad = 8;
    let left = rect.left + rect.width / 2 - tipRect.width / 2;
    let top = rect.top - tipRect.height - 10;
    if (top < pad) top = rect.bottom + 10;
    if (left < pad) left = pad;
    if (left + tipRect.width > window.innerWidth - pad) {
      left = window.innerWidth - tipRect.width - pad;
    }
    if (top + tipRect.height > window.innerHeight - pad) {
      top = Math.max(pad, window.innerHeight - tipRect.height - pad);
    }
    tip.style.left = `${Math.round(left)}px`;
    tip.style.top = `${Math.round(top)}px`;
  }

  function showTradeMetricTip(cell) {
    if (!columnTipsEnabled) return;
    if (cell.classList.contains('risk-tip-cell')) {
      showTradeRiskTip(cell);
      return;
    }
    const colKey = cell.dataset.metric;
    if (!colKey || !isTradeMetricColumn(colKey)) return;
    const raw = Number(cell.dataset.value);
    if (!Number.isFinite(raw)) return;
    const dist = tradeMetricDists[colKey];
    if (!dist || typeof buildMetricDistTipHtml !== 'function') return;

    const col = TRADE_COLUMNS.find((c) => c.key === colKey);
    const title = col ? col.title : colKey;
    const display = cell.textContent || formatTradeMetricStat(colKey, raw);
    const tip = ensureTradeMetricTip();
    tip.innerHTML = buildMetricDistTipHtml({
      title,
      display,
      value: raw,
      dist,
      colKey,
      formatStat: (v) => formatTradeMetricStat(colKey, v),
      spreadWidthRegime: colKey === 'SpreadEntry' || colKey === 'SpreadExit',
    });
    tip.classList.remove('hidden');
    positionTradeMetricTip(tip, cell);
  }

  function showTradeRiskTip(cell) {
    if (!columnTipsEnabled || typeof buildTradeRiskTipHtml !== 'function') return;
    const flags = cell.dataset.riskFlags || cell.textContent || '';
    if (!flags || flags === '—') return;
    const html = buildTradeRiskTipHtml({
      risk: flags,
      riskScore: Number(cell.dataset.riskScore) || 0,
      riskLevel: cell.dataset.riskLevel || 'None',
      riskRed: cell.dataset.riskRed === '1',
    });
    if (!html) return;
    const tip = ensureTradeMetricTip();
    tip.innerHTML = html;
    tip.classList.remove('hidden');
    positionTradeMetricTip(tip, cell);
  }

  /** Lazy: только при наведении на Z/спред в статус-баре (не на каждый seek). */
  function ensureStatusBarMetricDists(cursorIndex) {
    if (!columnTipsEnabled || typeof computeNumericDistribution !== 'function') {
      statusBarMetricDists = { z: null, spread: null, cursor: -1 };
      return;
    }
    const idx = Math.max(0, Math.min(cursorIndex | 0, allPoints.length - 1));
    if (statusBarMetricDists.cursor === idx && statusBarMetricDists.z) return;
    const zs = [];
    const sps = [];
    for (let i = 0; i <= idx; i++) {
      const p = allPoints[i];
      const z = p?.zScore != null ? Number(p.zScore) : NaN;
      const sp = p?.spreadPercent != null ? Number(p.spreadPercent) : NaN;
      if (Number.isFinite(z)) zs.push(z);
      if (Number.isFinite(sp)) sps.push(sp);
    }
    statusBarMetricDists = {
      z: computeNumericDistribution(zs),
      spread: computeNumericDistribution(sps),
      cursor: idx,
    };
  }

  function formatStatusBarMetricStat(metric, value) {
    if (value == null || !Number.isFinite(value)) return '—';
    if (metric === 'z') {
      const sign = value > 0 ? '+' : '';
      return `${sign}${value.toFixed(2)}`;
    }
    if (metric === 'spread') return `${value.toFixed(2)}%`;
    return String(value);
  }

  function statusMetricHoverValue(metric, value, display) {
    if (value == null || !Number.isFinite(Number(value))) return display;
    return (
      `<span class="metric-hover" data-metric="${metric}" data-value="${Number(value)}">`
      + `${display}</span>`
    );
  }

  function showStatusMetricTip(anchor) {
    if (!columnTipsEnabled || typeof buildMetricDistTipHtml !== 'function') return;
    const metric = anchor.dataset.metric;
    const value = Number(anchor.dataset.value);
    if ((metric !== 'z' && metric !== 'spread') || !Number.isFinite(value)) return;
    ensureStatusBarMetricDists(engine?.cursor ?? 0);
    const dist = statusBarMetricDists[metric];
    if (!dist) return;
    const title = metric === 'z' ? 'Z' : 'Спред';
    const display = anchor.textContent.trim() || formatStatusBarMetricStat(metric, value);
    const tip = ensureTradeMetricTip();
    tip.innerHTML = buildMetricDistTipHtml({
      title,
      display,
      value,
      dist,
      colKey: metric,
      formatStat: (v) => formatStatusBarMetricStat(metric, v),
      spreadWidthRegime: metric === 'spread',
    });
    tip.classList.remove('hidden');
    positionTradeMetricTip(tip, anchor);
  }

  /** Y-axis tick counts for the metric histogram tip (0 … maxBin). */
  function tradeMetricCountTicks(maxCount) {
    return typeof metricDistCountTicks === 'function'
      ? metricDistCountTicks(maxCount)
      : [0, Math.max(1, maxCount)];
  }

  /** Compare ISO-ish tradeDate strings (T or space). */
  function compareTradeDateStr(a, b) {
    const na = String(a || '').replace('T', ' ').trim();
    const nb = String(b || '').replace('T', ' ').trim();
    if (na < nb) return -1;
    if (na > nb) return 1;
    return 0;
  }

  /** Drop 1%/2%/3% hit fields that occurred after the replay cursor. */
  function tipClipHitsToCursor(r, cursorDate) {
    const clip = (date, ms, text, pnl) => {
      if (!date || compareTradeDateStr(date, cursorDate) > 0) {
        return { date: null, ms: null, text: '—', pnl: false };
      }
      return { date, ms: ms ?? null, text: text || '—', pnl: !!pnl };
    };
    const h1 = clip(r.hit1Date, r.hit1Ms, r.hit1, r.hitPnl1);
    const h2 = clip(r.hit2Date, r.hit2Ms, r.hit2, r.hitPnl2);
    const h3 = clip(r.hit3Date, r.hit3Ms, r.hit3, r.hitPnl3);
    return {
      hit1Date: h1.date,
      hit1Ms: h1.ms,
      hit1: h1.text,
      hitPnl1: h1.pnl,
      hit2Date: h2.date,
      hit2Ms: h2.ms,
      hit2: h2.text,
      hitPnl2: h2.pnl,
      hit3Date: h3.date,
      hit3Ms: h3.ms,
      hit3: h3.text,
      hitPnl3: h3.pnl,
    };
  }

  function tipSimActiveKey() {
    return tipSimCache.key || tipSimRequestKey();
  }

  function clearTipManualOverrides() {
    tipForceCloses = [];
    tipManualOpens = [];
  }

  function upsertTipForceClose(entry) {
    if (!entry) return;
    tipForceCloses = tipForceCloses.filter((fc) => (
      fc.cacheKey !== entry.cacheKey
      || fc.index !== entry.index
      || fc.entryDate !== entry.entryDate
    ));
    tipForceCloses.push(entry);
  }

  function applyTipForceCloseToRow(r) {
    if (!tipForceCloses.length) return r;
    const activeKey = tipSimActiveKey();
    let out = r;
    for (const fc of tipForceCloses) {
      if (fc.cacheKey !== activeKey) continue;
      if (out.index !== fc.index || out.entryDate !== fc.entryDate) continue;
      const origExit = out.exitDate;
      const forceExit = fc.exitDate;
      if (
        !origExit
        || origExit === '—'
        || compareTradeDateStr(origExit, forceExit) > 0
      ) {
        out = fc.row;
      }
    }
    return out;
  }

  /** Apply tip1m manual flat overrides (same cache key only). */
  function tipRowsWithForceClose(rows) {
    if (!rows || !rows.length) return rows || [];
    if (!tipForceCloses.length) return rows;
    return rows.map((r) => applyTipForceCloseToRow(r));
  }

  function tipNextManualIndex(rows) {
    let max = 0;
    for (const r of rows || []) {
      const n = Number(r.index);
      if (Number.isFinite(n) && n > max) max = n;
    }
    for (const m of tipManualOpens) {
      const n = Number(m.row?.index);
      if (Number.isFinite(n) && n > max) max = n;
    }
    return max + 1;
  }

  function tipNotionalForManualEntry(rows) {
    const base = getSimNotionalRub();
    if (!getSimCompound()) return base;
    let last = null;
    for (const r of rows || []) {
      if (r.status !== 'Закрыта') continue;
      const v = r.accountAfterValue;
      if (v != null && Number.isFinite(v) && v > 0) last = v;
    }
    return last != null ? last : base;
  }

  /**
   * tip1m «Long» / «Short»: открыть ручную сделку на текущем баре
   * (серверный sim не трогаем — локальный оверлей, как «Закрыть все»).
   */
  function openTip1mManualAtCursor(direction) {
    if (!isTip1mMode() || !engine || !allPoints.length) {
      return { ok: false, reason: 'no-engine' };
    }
    if (typeof simEntrySpread !== 'function' || typeof tipTradeToRow !== 'function') {
      return { ok: false, reason: 'no-sim' };
    }
    const idx = Math.max(0, Math.min(engine.cursor | 0, allPoints.length - 1));
    const bar = allPoints[idx];
    if (!bar?.tradeDate) return { ok: false, reason: 'no-bar' };

    const dir = direction === 'Short' ? 'Short' : 'Long';
    let visible = tipRowsUpToCursor(tipSimCache.rows || []);
    let pos = tipPositionFromRows(visible);
    const sameSide = pos === dir || pos === `${dir}+` || String(pos).startsWith(`${dir}×`);
    if (sameSide) return { ok: false, reason: 'already', direction: dir };

    if (pos !== 'Flat') {
      const closed = closeTip1mOpenAtCursor();
      visible = tipRowsUpToCursor(tipSimCache.rows || []);
      pos = tipPositionFromRows(visible);
      if (pos !== 'Flat' && !closed) return { ok: false, reason: 'close-failed' };
    }

    const entryTh = thresholds().entry;
    const entrySpread = simEntrySpread(bar.spreadPercent ?? 0, dir);
    const slip = typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0.02;
    const index = tipNextManualIndex([...(tipSimCache.rows || []), ...visible]);
    const notional = tipNotionalForManualEntry(visible);
    const row = tipTradeToRow({
      index,
      direction: dir,
      source: 'ручн.',
      tag: 'manual',
      entryDate: bar.tradeDate,
      exitDate: '—',
      entryZ: bar.zScore != null ? Number(bar.zScore) : null,
      exitZ: null,
      entrySpread,
      exitSpread: null,
      entrySlip: slip,
      status: 'Открыта',
      notional,
    }, entryTh);
    row.source = 'ручн.';

    const cacheKey = tipSimActiveKey();
    tipManualOpens = tipManualOpens.filter((m) => (
      m.cacheKey !== cacheKey || m.row.entryDate !== bar.tradeDate || m.row.direction !== dir
    ));
    tipManualOpens.push({ cacheKey, row });
    return { ok: true, direction: dir, row };
  }

  /**
   * Tip-1m server sim is full-window; slice to M15 cursor like local M15 edgesSoFar.
   * Trades that enter after cursor are dropped; exit after cursor → «Открыта».
   */
  function tipRowsUpToCursor(rows) {
    if (!rows || !rows.length) return rows || [];
    rows = tipRowsWithForceClose(rows);
    if (!engine || !allPoints.length) return rows;
    const idx = Math.max(0, Math.min(engine.cursor | 0, allPoints.length - 1));
    const cursorDate = allPoints[idx]?.tradeDate;
    if (!cursorDate) return rows;
    const out = [];
    for (const r of rows) {
      if (!r.entryDate || compareTradeDateStr(r.entryDate, cursorDate) > 0) continue;
      const exit = r.exitDate;
      const closedAfterCursor = !!(
        exit
        && exit !== '—'
        && compareTradeDateStr(exit, cursorDate) > 0
      );
      if (closedAfterCursor) {
        out.push({
          ...r,
          ...tipClipHitsToCursor(r, cursorDate),
          exitDate: '—',
          exitZ: null,
          exitSpread: null,
          pnlPts: null,
          gross: null,
          commission: null,
          overnight: null,
          net: null,
          accountBefore: null,
          accountAfter: null,
          status: 'Открыта',
        });
      } else {
        out.push(r);
      }
    }
    const activeKey = tipSimActiveKey();
    for (const m of tipManualOpens) {
      if (m.cacheKey !== activeKey) continue;
      let r = applyTipForceCloseToRow(m.row);
      if (!r.entryDate || compareTradeDateStr(r.entryDate, cursorDate) > 0) continue;
      const exit = r.exitDate;
      const closedAfterCursor = !!(
        exit
        && exit !== '—'
        && compareTradeDateStr(exit, cursorDate) > 0
      );
      if (closedAfterCursor) {
        out.push({
          ...r,
          ...tipClipHitsToCursor(r, cursorDate),
          exitDate: '—',
          exitZ: null,
          exitSpread: null,
          pnlPts: null,
          gross: null,
          commission: null,
          overnight: null,
          net: null,
          accountBefore: null,
          accountAfter: null,
          status: 'Открыта',
        });
      } else {
        out.push(r);
      }
    }
    return out;
  }

  /**
   * tip1m «Закрыть все»: сразу закрыть открытую sim-сделку на текущем баре
   * (в т.ч. последний бар / 100% — без шага +1).
   */
  function closeTip1mOpenAtCursor() {
    if (!isTip1mMode() || !tipSimCache.rows || !engine || !allPoints.length) return false;
    if (typeof openTradeNetRub !== 'function' || typeof simExitSpread !== 'function') {
      return false;
    }
    const idx = Math.max(0, Math.min(engine.cursor | 0, allPoints.length - 1));
    const bar = allPoints[idx];
    if (!bar?.tradeDate) return false;

    const visible = tipRowsUpToCursor(tipSimCache.rows);
    const open = [...visible].reverse().find((r) => r.status === 'Открыта');
    if (!open) return false;
    if (compareTradeDateStr(open.entryDate, bar.tradeDate) > 0) return false;

    const dir = open.direction === 'Short' ? 'Short' : 'Long';
    const entrySpread = open.spreadEntryValue != null
      ? Number(open.spreadEntryValue)
      : Number(open.entrySpread);
    if (!Number.isFinite(entrySpread)) return false;

    const notional = Number(open.notional) > 0
      ? Number(open.notional)
      : getSimNotionalRub();
    const constants = typeof simPnlConstants === 'function'
      ? simPnlConstants(notional)
      : null;
    if (!constants) return false;

    const exitSpread = simExitSpread(bar.spreadPercent ?? 0, dir);
    const pnlPts = dir === 'Long'
      ? exitSpread - entrySpread
      : entrySpread - exitSpread;
    const net = openTradeNetRub(bar, dir, entrySpread, open.entryDate, constants, true);
    const { effNotional, commPerSide, overnightPerDay } = constants;
    const gross = typeof spreadPnlToRub === 'function'
      ? spreadPnlToRub(pnlPts, effNotional)
      : net;
    const ovn = overnightPerDay * (
      typeof overnightDays === 'function'
        ? overnightDays(open.entryDate, bar.tradeDate)
        : 0
    );
    const commTotal = commPerSide * 2;

    let pnlMin = open.pnlMinValue;
    let pnlMax = open.pnlMaxValue;
    if (pnlMin == null || !Number.isFinite(pnlMin)) pnlMin = net;
    else pnlMin = Math.min(pnlMin, net);
    if (pnlMax == null || !Number.isFinite(pnlMax)) pnlMax = net;
    else pnlMax = Math.max(pnlMax, net);

    const entryTh = thresholds().entry;
    const closedRaw = {
      index: open.index,
      direction: dir,
      entryDate: open.entryDate,
      exitDate: bar.tradeDate,
      entryZ: open.entryZ,
      exitZ: bar.zScore != null ? Number(bar.zScore) : null,
      entrySpread,
      exitSpread,
      entrySlip: open.slipValue,
      pnlPts,
      gross,
      commission: commTotal,
      overnight: ovn,
      net,
      modelNet: net,
      netFromAccount: false,
      accountBefore: null,
      accountAfter: null,
      pnlMin,
      pnlMax,
      hit1Date: open.hit1Date,
      hit2Date: open.hit2Date,
      hit3Date: open.hit3Date,
      status: 'Закрыта',
      notional,
    };
    upsertTipForceClose({
      cacheKey: tipSimActiveKey(),
      index: open.index,
      entryDate: open.entryDate,
      exitDate: bar.tradeDate,
      row: tipTradeToRow(closedRaw, entryTh),
    });
    return true;
  }

  /** tip1m «Закрыть все» — все открытые строки на текущем баре. */
  function closeAllTip1mOpensAtCursor() {
    if (!isTip1mMode() || !tipSimCache.rows || !engine || !allPoints.length) return false;
    let closedAny = false;
    for (let guard = 0; guard < 64; guard += 1) {
      const visible = tipRowsUpToCursor(tipSimCache.rows);
      const hasOpen = visible.some((r) => r.status === 'Открыта');
      if (!hasOpen) break;
      if (!closeTip1mOpenAtCursor()) break;
      closedAny = true;
    }
    return closedAny;
  }

  function tipManualStatusNote(result) {
    if (!result) return '';
    if (result.ok) return `ручной ${result.direction} на баре`;
    if (result.reason === 'already') return `уже ${result.direction}`;
    if (result.reason === 'close-failed') return 'не удалось закрыть перед разворотом';
    if (result.reason === 'no-bar') return 'нет бара на курсоре';
    return '';
  }

  function tipPointForDate(td, zFallback, spFallback) {
    if (!td || td === '—') return null;
    const idx = findIndexByTradeDate(td);
    if (idx >= 0) return allPoints[idx];
    const sec = typeof labelToUnixSec === 'function' ? labelToUnixSec(td) : null;
    const ms = (typeof sec === 'number' && Number.isFinite(sec) && sec > 0)
      ? sec * 1000
      : NaN;
    return {
      tradeDate: td,
      timestampMs: Number.isFinite(ms) ? ms : 0,
      zScore: zFallback != null ? Number(zFallback) : 0,
      spreadPercent: spFallback != null ? Number(spFallback) : 0,
    };
  }

  /** Pseudo-edges from tip-1m trade rows for markers / equity / overlay. */
  function tipRowsToSignalEdges(rows) {
    const edges = [];
    if (!rows || !rows.length) return edges;
    for (const r of rows) {
      const isLong = r.direction === 'Long';
      const isManual = r.source === 'ручн.' || r.source === 'manual';
      const entrySp = r.entrySpread ?? r.spreadEntryValue;
      const entryBar = tipPointForDate(r.entryDate, r.entryZ, entrySp);
      if (!entryBar) continue;
      const isAddon = r.source === 'добор' || r.tag === 'addon';
      const isExtra = r.source === 'экстра' || r.tag === 'extra';
      const tradeNo = r.index;
      edges.push({
        signal: isLong ? 'EnterLong' : 'EnterShort',
        bar: entryBar,
        tradeNo,
        positionBefore: 'Flat',
        positionAfter: isLong ? 'Long' : 'Short',
        manual: isManual,
        addon: isAddon,
        extra: isExtra,
        source: r.source || null,
      });
      if (r.exitDate && r.exitDate !== '—' && r.status !== 'Открыта') {
        const exitSp = r.exitSpread ?? r.spreadExitValue;
        const exitBar = tipPointForDate(r.exitDate, r.exitZ, exitSp) || entryBar;
        edges.push({
          signal: isLong ? 'ExitLong' : 'ExitShort',
          bar: exitBar,
          tradeNo,
          positionBefore: isLong ? 'Long' : 'Short',
          positionAfter: 'Flat',
          manual: isManual,
          addon: isAddon,
          extra: isExtra,
          source: r.source || null,
        });
      }
    }
    return edges;
  }

  function tipPositionFromRows(rows) {
    if (!rows || !rows.length) return 'Flat';
    const open = [];
    for (let i = 0; i < rows.length; i++) {
      if (rows[i].status === 'Открыта') open.push(rows[i]);
    }
    if (!open.length) return 'Flat';
    const dir = open[open.length - 1].direction || 'Flat';
    if (open.length >= 2) return `${dir}×${open.length}`;
    if (open[0].source === 'добор') return `${dir}+`;
    return dir;
  }

  /**
   * Календ. дни для HTTP графика tip1m (не для сима).
   * Полное окно 3г (~700k баров) валит /api/bars1m на минуты — сим остаётся
   * на сервере за start→end; график — хвост не длиннее TIP1M_CHART_MAX_DAYS.
   */
  const TIP1M_CHART_MAX_DAYS = 90;
  function tip1mChartDaysWanted() {
    const start = $('startDate')?.value || '';
    const startMs = startYmdToMs(start);
    let days;
    if (Number.isFinite(startMs)) {
      const endYmd = readWindowEndYmd();
      const endMs = Number.isFinite(endYmdToMs(endYmd)) ? endYmdToMs(endYmd) : Date.now();
      days = Math.ceil((endMs - startMs) / 86_400_000) + 1;
    } else {
      const csv = String($('csvSel')?.value || '');
      if (csv.includes('1095') || csv.includes('365') || visibleDays >= 400) {
        days = TIP1M_CHART_MAX_DAYS;
      } else if (csv.includes('255')) {
        days = Math.min(255, TIP1M_CHART_MAX_DAYS);
      } else {
        days = Math.max(1, Number(visibleDays) || TIP1M_CHART_MAX_DAYS);
      }
    }
    return Math.max(1, Math.min(days, TIP1M_CHART_MAX_DAYS));
  }

  function tip1mLoadedSpanDays() {
    if (!allPoints || allPoints.length < 2) return 0;
    const a = barTimestampMs(allPoints[0]);
    const b = barTimestampMs(allPoints[allPoints.length - 1]);
    if (!Number.isFinite(a) || !Number.isFinite(b) || b <= a) return 0;
    return (b - a) / 86_400_000;
  }

  function tip1mChartNeedsReload() {
    if (!isTip1mMode()) return false;
    const want = tip1mChartDaysWanted();
    const have = tip1mLoadedSpanDays();
    if (have <= 0) return true;
    return have < want * 0.7;
  }

  /** Последняя точка в корзине N минут — чтобы год не рисовать 200k+ минутных свечей. */
  function aggregateTipBarsByMinutes(bars, minutes) {
    const bucketMs = Math.max(1, Number(minutes) || 1) * 60 * 1000;
    const out = [];
    let curKey = null;
    for (let i = 0; i < bars.length; i++) {
      const b = bars[i];
      const ms = Number(b && b.timestampMs);
      if (!Number.isFinite(ms)) continue;
      const key = Math.floor(ms / bucketMs);
      if (curKey !== key) {
        out.push(b);
        curKey = key;
      } else {
        out[out.length - 1] = b;
      }
    }
    return out;
  }

  const TIP1M_CHART_MAX_POINTS = 150000;

  function thinTip1mBarsForChart(bars) {
    const raw = Array.isArray(bars) ? bars : [];
    const n = raw.length;
    if (n <= TIP1M_CHART_MAX_POINTS) {
      return { bars: raw, stepMin: 1 };
    }
    let minutes = 5;
    if (Math.ceil(n / 5) > TIP1M_CHART_MAX_POINTS) minutes = 15;
    if (Math.ceil(n / 15) > TIP1M_CHART_MAX_POINTS) minutes = 30;
    return { bars: aggregateTipBarsByMinutes(raw, minutes), stepMin: minutes };
  }

  function tip1mBarsRequestKey() {
    return [
      $('csvSel')?.value || '',
      $('startDate')?.value || '',
      readWindowEndYmd(),
      String(tip1mChartDaysWanted()),
      'final',
    ].join('|');
  }

  function tip1mWindowSpanDays() {
    const start = $('startDate')?.value || '';
    const startMs = startYmdToMs(start);
    if (!Number.isFinite(startMs)) return 0;
    const endYmd = readWindowEndYmd();
    const endMs = Number.isFinite(endYmdToMs(endYmd)) ? endYmdToMs(endYmd) : Date.now();
    return Math.max(1, Math.ceil((endMs - startMs) / 86_400_000) + 1);
  }

  function tip1mFetchTimeoutMs(csv, chartDays) {
    const name = String(csv || '');
    const d = Number(chartDays) || 0;
    const span = d > 0 ? d : tip1mWindowSpanDays();
    // CSV «3 года» + окно 3мес не должен брать 90с refresh MOEX.
    if (span > 0 && span < 200) return 180000;
    if (name.includes('1095') || /_3y/i.test(name) || span >= 900) return 90000;
    if (name.includes('365') || span >= 300) return 60000;
    return 45000;
  }

  /** Сим / heatmap: не общий 90с refresh MOEX. 3мес и 3г могут идти минуты. */
  function tip1mSimTimeoutMs() {
    return 600000;
  }

  async function ensureTestDeskCorridor({ force = false } = {}) {
    if (!TEST_DRAW_CORRIDOR) return null;
    const now = Date.now();
    if (!force && testDeskCorridor && (now - testDeskCorridorTs) < 60000) {
      return testDeskCorridor;
    }
    try {
      const res = await fetch('/api/trade/desk?days=30&lite=1', { signal: AbortSignal.timeout(20000) });
      if (!res.ok) return testDeskCorridor;
      const data = await res.json();
      if (data && data.corridor) {
        testDeskCorridor = data.corridor;
        testDeskCorridorTs = Date.now();
      }
    } catch (_) { /* ignore */ }
    return testDeskCorridor;
  }

  async function loadTip1mChartBars({ force = false } = {}) {
    const key = tip1mBarsRequestKey();
    if (!force && key === tip1mBarsCacheKey && tip1mChartMeta && allPoints.length
      && tip1mChartMeta._active) {
      return { bars: allPoints, meta: tip1mChartMeta, fromCache: true };
    }
    const jobId = ++tip1mChartJobId;
    const csv = $('csvSel')?.value || 'm15_tatn_255d.csv';
    const start = $('startDate')?.value || '';
    const end = readWindowEndYmd();
    const chartDays = tip1mChartDaysWanted();
    let url = `/api/bars1m?csv=${encodeURIComponent(csv)}&chartDays=${chartDays}`;
    if (start) url += `&start=${encodeURIComponent(start)}`;
    if (end) url += `&end=${encodeURIComponent(end)}`;
    const timeoutMs = tip1mFetchTimeoutMs(csv, chartDays);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    let res;
    try {
      res = await fetch(url, { signal: controller.signal });
    } catch (e) {
      if (e && e.name === 'AbortError') {
        throw new Error(
          `Таймаут графика 1м (${Math.round(timeoutMs / 1000)} с). Обновите страницу или перезапустите сервис.`,
        );
      }
      throw e;
    } finally {
      clearTimeout(timer);
    }
    if (!res.ok) {
      const errText = await res.text();
      throw new Error(errText || res.statusText);
    }
    const data = await res.json();
    if (jobId !== tip1mChartJobId) return null;
    if (!data.ok && !(data.bars && data.bars.length)) {
      throw new Error(data.hintRu || 'Нет 1м баров для графика');
    }
    const rawBars = data.bars || [];
    const thinned = thinTip1mBarsForChart(rawBars);
    const stepMin = Number(data.displayStepMin) > 1
      ? Number(data.displayStepMin)
      : thinned.stepMin;
    const appliedDays = data.chartDays != null ? data.chartDays : chartDays;
    let hintRu = data.hintRu || null;
    if (stepMin > 1) {
      hintRu = (
        `График за выбранное окно (~${appliedDays}д), шаг ${stepMin} мин `
        + `(минутки не рисуем: ${rawBars.length} точек слишком тяжело для экрана). `
        + `Симуляция на сервере — полный период.`
      );
    }
    tip1mBarsCacheKey = key;
    tip1mChartMeta = {
      count: thinned.bars.length,
      rawCount: rawBars.length,
      fullTipCount: data.fullTipCount,
      chartLimited: !!data.chartLimited,
      chartDays: appliedDays,
      displayStepMin: stepMin,
      hintRu,
      first: data.first,
      last: data.last,
      corridor: TEST_DRAW_CORRIDOR ? (data.corridor || null) : null,
      corridor_wide: TEST_DRAW_WIDE_CORRIDOR ? (data.corridor_wide || null) : null,
      _active: true,
    };
    if (TEST_DRAW_CORRIDOR && !tip1mChartMeta.corridor) {
      ensureTestDeskCorridor({ force: true }).then((c) => {
        if (c && tip1mChartMeta) {
          tip1mChartMeta.corridor = tip1mChartMeta.corridor || c;
          if (isChartsHidden()) {
            pendingChartRepaint = true;
            return;
          }
          try { refreshUi({ light: true }); } catch (_) { /* ignore */ }
        }
      });
    }
    return { bars: thinned.bars, meta: tip1mChartMeta, fromCache: false };
  }

  function stashM15IfNeeded() {
    if (m15PointsStash == null && allPoints.length && !tip1mChartMeta?._active) {
      m15PointsStash = allPoints;
    }
  }

  async function activateTip1mChart() {
    stashM15IfNeeded();
    const st = $('status');
    if (st) st.textContent = `касание 1м · гружу график за ${tip1mChartDaysWanted()}д…`;
    let loaded;
    try {
      loaded = await loadTip1mChartBars({ force: true });
    } catch (e) {
      const msg = (e && e.message) ? String(e.message).slice(0, 180) : String(e);
      if (st) st.textContent = `касание 1м · ошибка графика: ${msg}`;
      return;
    }
    if (!loaded || !isTip1mMode()) return;
    allPoints = loaded.bars;
    chartFocusIndex = null;
    selectedTradeId = null;
    updateMoexLastBarHint(allPoints.length ? allPoints[allPoints.length - 1]?.tradeDate : null);
    const csvSafe = String($('csvSel')?.value || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const stepMin = Number(tip1mChartMeta?.displayStepMin) || 1;
    const daysLab = tip1mChartMeta?.chartDays != null ? `${tip1mChartMeta.chartDays}д` : '';
    const hintEsc = String(tip1mChartMeta?.hintRu || '').replace(/"/g, '&quot;');
    let lim;
    if (stepMin > 1) {
      lim = ` · <span class="badge-quiet" title="${hintEsc}">график ${daysLab} ${stepMin}м</span>`;
    } else if (tip1mChartMeta?.chartLimited) {
      lim = ` · <span class="badge-quiet" title="${hintEsc}">1м график ${daysLab}</span>`;
    } else {
      lim = ` · <span class="badge-online" title="${hintEsc}">1м tip${daysLab ? ` · ${daysLab}` : ''}</span>`;
    }
    if ($('meta')) {
      $('meta').innerHTML =
        `TATN/TATNP · ${allPoints.length} бар. 1м · ${csvSafe}${lim}`;
    }
    rebuildEngine();
    chart?.followReplayEdge(true);
    if (engine) engine.seekToEnd();
    pendingFitFullChart = visibleDays >= 400;
    refreshUi({ afterParams: true });
  }

  function activateM15Chart() {
    tip1mChartMeta = tip1mChartMeta ? { ...tip1mChartMeta, _active: false } : null;
    tip1mBarsCacheKey = '';
    if (m15PointsStash && m15PointsStash.length) {
      allPoints = m15PointsStash;
      m15PointsStash = null;
      chartFocusIndex = null;
      selectedTradeId = null;
      updateMoexLastBarHint(allPoints.length ? allPoints[allPoints.length - 1]?.tradeDate : null);
      const csvSafe = String($('csvSel')?.value || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      if ($('meta')) {
        $('meta').innerHTML = `TATN/TATNP · ${allPoints.length} баров · ${csvSafe} · M15`;
      }
      rebuildEngine();
      chart?.followReplayEdge(true);
      if (engine) engine.seekToEnd();
      refreshUi({ afterParams: true });
      return;
    }
    // Fallback: reload M15 from API
    const csv = $('csvSel')?.value || 'm15_tatn_255d.csv';
    bootstrap(csv, { background: true, keepSelection: false }).catch(() => {});
  }

  function refreshTradesTable() {
    if (!engine && !isTip1mMode()) return;
    const tradesScrollEl = $('tradesScroll');
    const prevLeft = tradesScrollEl ? tradesScrollEl.scrollLeft : 0;
    const prevTop = tradesScrollEl ? tradesScrollEl.scrollTop : 0;
    const { entry } = thresholds();
    let rows;
    if (isTip1mMode()) {
      if (!tipSimCache.rows) {
        const tbody = $('tradesBody');
        if (tbody) tbody.innerHTML = '';
        const head = $('tradesHead');
        if (head && !head.children.length) {
          /* waiting for server */
        }
        const grid = $('tradesSummaryGrid');
        if (grid) {
          grid.innerHTML =
            '<div class="trades-summary-note wide">касание 1м · считаю сделки на сервере…</div>';
        }
        markMonthlyPnlPending();
        return;
      }
      rows = tipRowsUpToCursor(tipSimCache.rows);
    } else {
      if (!engine) return;
      const frame = engine.frameAtCursor();
      const skipExtras = skipTradeExtrasOnce;
      if (skipTradeExtrasOnce) skipTradeExtrasOnce = false;
      rows = buildTradeRows(
        frame.signalEdgesSoFar,
        entry,
        allPoints,
        frame.cursorIndex,
        {
          skipExtras,
          spreadLevels: typeof readHmSelectedSpreadLevels === 'function'
            ? readHmSelectedSpreadLevels()
            : undefined,
        },
      );
    }
    const filtered = filterTradeRows(rows);
    const sortedRows = [...filtered].sort(
      (a, b) => compareTradeRows(a, b, tradeSortColumn, tradeSortDir),
    );

    const head = $('tradesHead');
    head.innerHTML = '';
    const visibleCols = resolveVisibleTradeColumns(visibleTradeColumns);
    tradeMetricDists = columnTipsEnabled
      ? buildTradeMetricDistributions(sortedRows, visibleCols.map((c) => c.key))
      : {};
    for (const col of visibleCols) {
      const th = document.createElement('th');
      th.className = 'sortable';
      if (tradeSortColumn === col.key) {
        th.classList.add(tradeSortDir === 'asc' ? 'sorted-asc' : 'sorted-desc');
      }
      const label = document.createElement('span');
      label.textContent = col.title;
      th.appendChild(label);
      if (tradeSortColumn === col.key) {
        const mark = document.createElement('span');
        mark.className = 'sort-mark';
        mark.textContent = tradeSortDir === 'asc' ? '▲' : '▼';
        th.appendChild(mark);
      }
      th.title = col.hint ? `${col.hint} · сортировка` : 'Сортировка';
      th.style.minWidth = `${col.width}px`;
      bindTableHeaderDrag(
        th,
        col.key,
        () => visibleTradeColumns,
        (keys) => { visibleTradeColumns = keys; persistTradeColumns(); },
        () => { renderColumnPicker(); refreshTradesTable(); },
        (e) => {
          e.stopPropagation();
          if (tradeSortColumn === col.key) {
            tradeSortDir = tradeSortDir === 'asc' ? 'desc' : 'asc';
          } else {
            tradeSortColumn = col.key;
            tradeSortDir = ['Index', 'Entry', 'Exit', 'EntryZ', 'ExitZ', 'Duration', 'Hit1', 'Hit2', 'Hit3'].includes(col.key) ? 'asc' : 'desc';
          }
          saveSetting(LS.tradeSortCol, tradeSortColumn);
          saveSetting(LS.tradeSortDir, tradeSortDir);
          refreshTradesTable();
        },
      );
      head.appendChild(th);
    }

    const tbody = $('tradesBody');
    tbody.innerHTML = '';
    const frag = document.createDocumentFragment();
    for (const row of sortedRows) {
      const tr = document.createElement('tr');
      const badge = tradeBadgeForRow(row);
      tr.dataset.tradeId = badge;
      if (badge === selectedTradeId) tr.classList.add('trade-row-selected');
      if (row.riskRed) tr.classList.add('trade-row-red');
      tr.addEventListener('click', () => {
        focusChartsOnTrade(row, { seek: false });
      });
      tr.addEventListener('dblclick', (e) => {
        e.preventDefault();
        jumpChartsToTrade(row);
      });
      for (const col of visibleCols) {
        const td = document.createElement('td');
        td.textContent = tradeCellValue(row, col.key);
        const cls = tradeCellClass(row, col.key);
        if (cls) td.className = cls;
        if (isTradeMetricColumn(col.key)) {
          const raw = tradeMetricRawValue(row, col.key);
          if (raw != null) {
            td.dataset.metric = col.key;
            td.dataset.value = String(raw);
            td.classList.add('metric-cell');
          }
        } else if (col.key === 'Risk' && row.risk && row.risk !== '—') {
          td.dataset.riskFlags = row.risk;
          td.dataset.riskScore = String(row.riskScore ?? 0);
          td.dataset.riskLevel = row.riskLevel || 'None';
          td.dataset.riskRed = row.riskRed ? '1' : '0';
          td.classList.add('risk-tip-cell');
        }
        tr.appendChild(td);
      }
      frag.appendChild(tr);
    }
    tbody.appendChild(frag);

    updateTradesSummary(rows, filtered);
    syncRiskFilterChips();
    hideTradeMetricTip();

    const tradesScrollEl2 = $('tradesScroll');
    if (tradesScrollEl2) {
      if (!scrollRestored) {
        const left = readSavedTradesScrollLeft();
        const top = readSavedTradesScrollTop();
        withSuppressedScrollSave(() => {
          tradesScrollEl2.scrollLeft = left;
          tradesScrollEl2.scrollTop = top;
        });
        // Wait until content can hold saved offsets — empty table must not finalize restore.
        if (
          tradesScrollEl2.clientHeight > 0
          && scrollOffsetFit(tradesScrollEl2, 'top', top)
          && scrollOffsetFit(tradesScrollEl2, 'left', left)
        ) {
          scrollRestored = true;
        }
      } else {
        withSuppressedScrollSave(() => {
          tradesScrollEl2.scrollLeft = prevLeft;
          tradesScrollEl2.scrollTop = prevTop;
        });
      }
    }
  }

  function refreshUi(opts = {}) {
    if (!engine || !chart) return;
    const light = !!opts.light;
    const afterParams = !!opts.afterParams;
    const skipChartPaint = isChartsHidden();
    if (skipChartPaint) pendingChartRepaint = true;
    const forceChartPaint = !skipChartPaint && (pendingChartRepaint || afterParams);
    if (forceChartPaint) pendingChartRepaint = false;
    const frame = engine.frameAtCursor();
    const tipMode = isTip1mMode();
    const tipRowsCursor = tipMode && tipSimCache.rows
      ? tipRowsUpToCursor(tipSimCache.rows)
      : null;
    const chartEdges = tipMode
      ? tipRowsToSignalEdges(tipRowsCursor || [])
      : frame.signalEdgesSoFar;
    const tipPos = tipMode ? tipPositionFromRows(tipRowsCursor || []) : frame.position;
    const edgesLen = tipMode
      ? (tipRowsCursor ? tipRowsCursor.length : -1)
      : frame.signalEdgesSoFar.length;
    const range = barReplayVisibleIndexRange(
      allPoints,
      frame.cursorIndex,
      visibleDays,
      chartFocusIndex,
    );
    const windowPoints = skipChartPaint
      ? []
      : allPoints.slice(range.start, range.end + 1);
    const spreadChartMode = isTipSpreadLevelsHeatmap();
    const currentPoint = frame.currentPoint
      || (allPoints.length ? allPoints[frame.cursorIndex] : null);

    const edgesChanged = edgesLen !== uiSeriesCache.edgesLen;
    const prevCursor = uiSeriesCache.cursor;
    const cursorChanged = frame.cursorIndex !== prevCursor;

    if (!skipChartPaint) {
      const candles = spreadChartMode
        ? buildSpreadCandles(windowPoints)
        : buildZCandles(windowPoints);
      const skipMilestones = (light && !edgesChanged && !forceChartPaint)
        || afterParams
        || tipMode
        || forceChartPaint;

      let markers;
      let markersChanged = true;
      if (light && !edgesChanged && !forceChartPaint && uiSeriesCache.markers) {
        markers = uiSeriesCache.markers;
        markersChanged = false;
      } else {
        markers = [
          ...buildMarkers(chartEdges, windowPoints),
          // milestones дорогие (~50ms) — на light/смене порогов пропускаем
          ...(skipMilestones ? [] : buildPnlMilestoneMarkers(
            chartEdges,
            allPoints,
            windowPoints,
            frame.cursorIndex,
          )),
        ].sort((a, b) => a.time - b.time || String(a.text).localeCompare(String(b.text)));
      }

      let cascadeVlines = uiSeriesCache.cascadeVlines || [];
      let cascadeVlinesChanged = false;
      // Каскад — по дням; зоны — по окну графика (эпизоды ≥60 бар).
      const showCascade = isShowCascadeEnabled();
      const showZoneBands = isShowZoneBandsEnabled();
      const cascadeCursorKey = `${allPoints.length}|${allPoints[frame.cursorIndex]?.tradeDate?.slice(0, 10) || ''}|${range.start}|${range.end}|c${showCascade ? 1 : 0}|z${showZoneBands ? 1 : 0}`;
      if (forceChartPaint || !light || cascadeCursorKey !== uiSeriesCache.cascadeCursorKey
        || !uiSeriesCache.cascadeVlines) {
        let cascadeOnly = [];
        if (showCascade && typeof buildCascadeChartVlines === 'function' && allPoints.length && frame.cursorIndex >= 0) {
          cascadeOnly = buildCascadeChartVlines(allPoints.slice(0, frame.cursorIndex + 1));
        }
        let zoneOnly = [];
        if (showZoneBands && typeof buildSpreadZoneChartVlines === 'function' && allPoints.length && frame.cursorIndex >= 0
          && windowPoints.length) {
          const t0 = typeof labelToUnixSec === 'function'
            ? labelToUnixSec(windowPoints[0].tradeDate)
            : null;
          const t1 = typeof labelToUnixSec === 'function'
            ? labelToUnixSec(windowPoints[windowPoints.length - 1].tradeDate)
            : null;
          const lookback = Math.max(0, range.start - 3000);
          const zoneBars = allPoints.slice(lookback, frame.cursorIndex + 1);
          zoneOnly = buildSpreadZoneChartVlines(zoneBars, {
            minBars: 60,
            maxLines: 40,
            tMin: t0 != null ? t0 : undefined,
            tMax: t1 != null ? t1 : undefined,
          });
        }
        cascadeVlines = [...cascadeOnly, ...zoneOnly];
        cascadeVlinesChanged = true;
      }

      const canReuseSeries = light && !afterParams && !forceChartPaint && !edgesChanged
        && frame.cursorIndex === uiSeriesCache.cursor
        && range.start === uiSeriesCache.rangeStart
        && range.end === uiSeriesCache.rangeEnd
        && uiSeriesCache.pnlMode === pnlMode
        && uiSeriesCache.equityTotal
        && uiSeriesCache.deltaPp
        && uiSeriesCache.trades;

      let trades;
      let equityTotal;
      let equity;
      let deltaPp;
      if (canReuseSeries) {
        trades = uiSeriesCache.trades;
        equityTotal = uiSeriesCache.equityTotal;
        equity = uiSeriesCache.equity;
        deltaPp = uiSeriesCache.deltaPp;
      } else {
        trades = buildTradeSegments(chartEdges, currentPoint).map((t) => ({
          id: t.id,
          entryTime: t.entryTime,
          entryZ: t.entryZ,
          exitTime: t.exitTime,
          exitZ: t.exitZ,
          entrySpread: t.entrySpread,
          exitSpread: t.exitSpread,
          open: t.open,
        }));
        // tip1m: график часто урезан до ~90д, а % сверху — за всё окно сима.
        // Кривую «Счёт» строим по net сделок tip (с seed до начала окна), иначе конец ≈10к при +142%.
        equityTotal = (tipMode && tipRowsCursor && tipRowsCursor.length
          && typeof buildTipAccountEquitySeries === 'function')
          ? buildTipAccountEquitySeries(tipRowsCursor, windowPoints)
          : buildEquitySeries(
            allPoints,
            chartEdges,
            windowPoints,
            frame.cursorIndex,
          );
        equity = pnlMode === 'trade'
          ? buildPerTradeEquitySeries(
            allPoints,
            chartEdges,
            windowPoints,
            frame.cursorIndex,
          )
          : equityTotal;
        deltaPp = buildDeltaPpSeries(
          allPoints,
          chartEdges,
          windowPoints,
          frame.cursorIndex,
        );
      }

      uiSeriesCache = {
        cursor: frame.cursorIndex,
        edgesLen,
        rangeStart: range.start,
        rangeEnd: range.end,
        markers,
        equityTotal,
        equity,
        deltaPp,
        trades,
        cascadeVlines,
        cascadeCursorKey,
        pnlMode,
      };

      const fitFull = pendingFitFullChart;
      if (pendingFitFullChart) pendingFitFullChart = false;
      const payload = buildChartPayload(
        candles,
        thresholds().entry,
        thresholds().exit,
        markers,
        trades,
        playing,
        {
          maxVisibleBars: visibleBarsOnScreen(visibleDays, { tip1m: tipMode }),
          equity,
          deltaPp,
          pnlBasisRub: resolvePnlPctBasisRub(equityTotal),
          pnlChartMode: pnlMode,
          accountBaseRub: getSimNotionalRub(),
          fitFull,
          // afterParams / показ после скрытия меняет всю кривую — нельзя «дописать хвост»
          light: light && !afterParams && !forceChartPaint,
          markersChanged: markersChanged || afterParams || forceChartPaint,
          cascadeVlines,
          cascadeVlinesChanged: cascadeVlinesChanged || afterParams || forceChartPaint || !light,
          primaryMetric: spreadChartMode ? 'spread' : 'z',
          spreadLevels: spreadChartMode ? readHmSelectedSpreadLevels() : null,
          stickyShelf: (TEST_DRAW_CORRIDOR && spreadChartMode && isAdaptCorridorMode())
            ? { lo: -0.25, hi: 1.0 }
            : null,
          corridor: (TEST_DRAW_WIDE_CORRIDOR && spreadChartMode && isShowWideShelvesEnabled())
            ? wideCorridorAtCursor(
              tip1mChartMeta?.corridor_wide || null,
              currentPoint?.tradeDate || frame.barLabel,
              allPoints[0]?.tradeDate,
            )
            : ((TEST_DRAW_CORRIDOR && spreadChartMode && !isAdaptCorridorMode())
              ? (tip1mChartMeta?.corridor || testDeskCorridor || null)
              : null),
        },
      );
      try {
        chart.setReplay(payload);
      } catch (e) {
        console.warn('chart.setReplay skipped bad time', e && e.message);
      }
      const chartLab = $('testPrimaryChartLabel');
      if (chartLab) {
        chartLab.textContent = tipMode ? 'Спред % · tip1m' : 'Спред %';
      }
      if (fitFull && typeof chart.fitFullRange === 'function') {
        requestAnimationFrame(() => chart.fitFullRange());
      } else if (selectedTradeId) {
        chart.selectTrade(selectedTradeId);
      }

      updateOpenTradeOverlay(chartEdges, currentPoint, tipPos);
    } else {
      // График скрыт: только курсор/сделки для таблиц, без свечей/маркеров/PnL-серий.
      uiSeriesCache = {
        cursor: frame.cursorIndex,
        edgesLen,
        rangeStart: -1,
        rangeEnd: -1,
        markers: null,
        equityTotal: null,
        equity: null,
        deltaPp: null,
        trades: null,
        cascadeVlines: null,
        cascadeCursorKey: '',
        pnlMode: null,
      };
      const overlay = $('openTradeOverlay');
      if (overlay) overlay.classList.add('hidden');
    }

    const lastPt = currentPoint;
    const z = lastPt?.zScore ?? null;
    const spread = lastPt?.spreadPercent ?? null;
    const zText = formatStatusBarMetricStat('z', z != null && Number.isFinite(z) ? z : null);
    const spText = formatStatusBarMetricStat(
      'spread',
      spread != null && Number.isFinite(spread) ? spread : null,
    );
    const zHover = statusMetricHoverValue('z', z, zText);
    const spHover = statusMetricHoverValue('spread', spread, spText);
    const tp = thresholds().takeProfitPct;
    const tpText = tp > 0 ? ` · TP ${tp}%` : '';
    const hold = typeof readHoldParams === 'function' ? readHoldParams() : null;
    const holdText = (() => {
      if (!isTip1mMode() || !hold) return '';
      const bits = [];
      if (hold.maxHoldDaysNoExitTrend > 0) bits.push(`нет хода ${hold.maxHoldDaysNoExitTrend}д`);
      if (hold.maxHoldDaysIfLosing > 0) bits.push(`в минусе ${hold.maxHoldDaysIfLosing}д`);
      return bits.length ? ` · ${bits.join(' · ')}` : '';
    })();
    const riskText = (() => {
      const short = formatRiskExitSelectionShort(selectedRiskExitGroups());
      return short !== 'выкл' ? ` · Risk ${short}` : '';
    })();
    const capText = getSimCompound() ? ' · капит.' : '';
    // Режим — по последним ~7 барам (дешёво). Каскад/зона — тяжёлые, кэш по дню курсора.
    let regimeHtml = '';
    if (typeof classifySpreadRegime === 'function' && allPoints.length && frame.cursorIndex >= 0) {
      const from = Math.max(0, frame.cursorIndex - 7);
      const regimeBars = allPoints.slice(from, frame.cursorIndex + 1);
      const r = classifySpreadRegime(regimeBars);
      if (r && r.key !== 'na') {
        const title = r.title ? ` title="${String(r.title).replace(/"/g, '&quot;')}"` : '';
        regimeHtml =
          ` <span class="badge-regime badge-regime-${r.key}"${title}>`
          + `${r.label}</span>`;
      }
    }
    const badgeDay = allPoints[frame.cursorIndex]?.tradeDate?.slice(0, 10) || '';
    const badgeKey = `${allPoints.length}|${badgeDay}`;
    let cascadeHtml = '';
    let zoneHtml = '';
    if (statusBadgeCache.key === badgeKey) {
      cascadeHtml = statusBadgeCache.cascadeHtml;
      zoneHtml = statusBadgeCache.zoneHtml;
    } else {
      if (typeof detectSpreadRegimeCascade === 'function' && allPoints.length && frame.cursorIndex >= 0) {
        const cascadeBars = allPoints.slice(0, frame.cursorIndex + 1);
        const c = detectSpreadRegimeCascade(cascadeBars);
        if (c && c.on) {
          const title = c.title ? ` title="${String(c.title).replace(/"/g, '&quot;')}"` : '';
          cascadeHtml =
            ` <span class="badge-cascade badge-cascade-${c.key}"${title}>`
            + `${c.label}</span>`;
        }
      }
      if (typeof detectSpreadMapZone === 'function' && allPoints.length && frame.cursorIndex >= 0) {
        const zoneBars = allPoints.slice(0, frame.cursorIndex + 1);
        const zz = detectSpreadMapZone(zoneBars);
        if (zz && zz.on) {
          const title = zz.title ? ` title="${String(zz.title).replace(/"/g, '&quot;')}"` : '';
          const wallCls = zz.nearWall ? ' badge-zone-wall' : '';
          zoneHtml =
            ` <span class="badge-zone badge-zone-${zz.key}${wallCls}"${title}>`
            + `${zz.badgeText || zz.shortLabel}</span>`;
        }
      }
      statusBadgeCache = { key: badgeKey, regimeHtml: '', zoneHtml, cascadeHtml };
    }
    const slipPts = typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0;
    const slipText = slipPts > 0 ? ` · slip ${slipPts}` : '';
    const modeText = isTip1mMode()
      ? ' · <span class="badge-online" title="Касание 1м · уровни спреда как Prod · пауза +10с">касание 1м</span>'
      : '';
    const baseText = isBaseMode()
      ? ' · <span class="badge-base" title="База AUTO: Short 6.1/5.8 · Long 3.2/4.0. Выкл — новые базовые ноги не открываются. Не Prod AUTO.">база</span>'
      : '';
    const addonText = isAddon27Mode()
      ? (isTip1mMode()
        ? ' · <span class="badge-addon27" title="Вариант 2: база + добор на 2% (Long→3.2) и 7% (Short→6.1). Капит. учитывается. Без базы добор не входит. Не Prod AUTO.">добор 2/7</span>'
        : ' · <span class="badge-addon27" title="Добор 2/7 считается в режиме «касание 1м»">добор 2/7</span>')
      : '';
    const extraText = isExtremeAddonMode()
      ? (isTip1mMode()
        ? ' · <span class="badge-extra19" title="Экстра: Long в зоне ≤1→2 · Short ≥9→7, если уже есть база или добор. На том же баре, что другие ноги. Не от касания 3,2/6,1.">экстра 1/9</span>'
        : ' · <span class="badge-extra19" title="Экстра 1/9 считается в режиме «касание 1м»">экстра 1/9</span>')
      : '';
    const weekendText = isWeekendTradingMode()
      ? ' · <span class="badge-online" title="Суббота и воскресенье 10:00–18:59 МСК (как Prod AUTO). Чип только для прогона Теста.">выходные</span>'
      : '';
    const swingText = isZoneSwingMode()
      ? (isTip1mMode()
        ? ' · <span class="badge-zoneswing" title="Качалка зоны: ping-pong в коричневой зоне L вых…S вых после выхода базы. Не Prod AUTO.">качалка зоны</span>'
        : ' · <span class="badge-zoneswing" title="Качалка зоны считается в режиме «касание 1м»">качалка зоны</span>')
      : '';
    const adaptText = (TEST_DRAW_CORRIDOR && isAdaptCorridorMode())
      ? (isTip1mMode()
        ? ' · <span class="badge-adaptcor" title="База + добавка липкая полка S% −0.25…1 (без пересечения с базой). Не Prod AUTO.">адапт. коридор+</span>'
        : ' · <span class="badge-adaptcor" title="Адапт. коридор считается в режиме «касание 1м»">адапт. коридор+</span>')
      : '';
    const shelfFfText = isShelfFloorCeilingMode()
      ? ' · <span class="badge-addon27" title="Добавка: пол–потолок по каузальной широкой полке. Не гасит добор и не подменяет AUTO 3.2/6.1. Цель и «нет хода» — как на панели.">пол–потолок</span>'
      : '';
    let tipNote = '';
    if (isTip1mMode()) {
      if (!tipSimCache.rows) {
        tipNote = '   ·   tip считает…';
      } else {
        const tipRows = tipRowsCursor || tipRowsUpToCursor(tipSimCache.rows);
        const tipSum = buildTradeSimSummary(tipRows, getSimNotionalRub());
        const s = tipSimCache.summary || {};
        const nTrades = tipSum.closedCount != null ? tipSum.closedCount : (Number(s.trades) || 0);
        const retPct = Number.isFinite(tipSum.retPct) ? tipSum.retPct : Number(s.retPct) || 0;
        const pnlRub = Number.isFinite(tipSum.totalPnl)
          ? tipSum.totalPnl
          : (Number(s.pnlRub) || 0);
        const profitRub = Number.isFinite(tipSum.profitRub)
          ? tipSum.profitRub
          : (pnlRub + (Number.isFinite(tipSum.openMtm) ? tipSum.openMtm : 0));
        const profitPct = Number.isFinite(tipSum.retPct) ? tipSum.retPct : retPct;
        const rubText = typeof formatProfitAccountRub === 'function'
          ? formatProfitAccountRub(profitRub)
          : formatRub(profitRub);
        const pnlCls = pnlClass(profitRub) || pnlClass(profitPct);
        tipNote =
          `   ·   tip ${nTrades} сд.`
          + ` · <span class="${pnlCls}" title="Чистая прибыль (закрытый net + MTM открытых), без депозита">${formatCapitalPct(profitPct)}</span>`
          + ` · <span class="${pnlCls}">${rubText}</span>`;
      }
      if (!skipChartPaint) {
        if (tip1mChartMeta?.displayStepMin > 1) {
          tipNote += `   ·   график ${tip1mChartMeta.chartDays}д ${tip1mChartMeta.displayStepMin}м`;
        } else if (tip1mChartMeta?.chartLimited) {
          tipNote += `   ·   график ${tip1mChartMeta.chartDays}д 1м`;
        } else if (tip1mChartMeta?.chartDays) {
          tipNote += `   ·   график ${tip1mChartMeta.chartDays}д 1м`;
        }
      } else {
        tipNote += '   ·   график скрыт';
      }
      if (tipSimCache.rows) {
        const tipRowsForNote = tipRowsCursor || tipRowsUpToCursor(tipSimCache.rows || []);
        const n = tipRowsForNote.length;
        const first = tipSimCache.rows?.[0]?.entryDate;
        const last = tipSimCache.rows?.length
          ? tipSimCache.rows[tipSimCache.rows.length - 1]?.entryDate
          : null;
        const openN = tipRowsForNote.filter((r) => r.status === 'Открыта').length;
        if (first && last && n > 0) {
          tipNote += `   ·   входы ${String(first).slice(0, 10)}…${String(last).slice(0, 10)}`;
          if (openN) tipNote += ` (+${openN} откр.)`;
        } else if (openN) {
          tipNote += `   ·   +${openN} откр.`;
        }
      }
    }
    const sigCount = tipMode ? chartEdges.length : frame.signalEdgesSoFar.length;
    const th = thresholds();
    let threshText;
    if (spreadChartMode || tipMode) {
      const lv = readHmSelectedSpreadLevels();
      const f1 = (v) => Number(v).toLocaleString('ru-RU', { maximumFractionDigits: 1 });
      threshText =
        `S ${f1(lv.enter_wide)}/${f1(lv.exit_wide)} · L ${f1(lv.enter_narrow)}/${f1(lv.exit_narrow)}`;
    } else {
      threshText = `пороги ±${th.entry} / ±${th.exit}`;
    }
    const zBit = '';
    $('status').innerHTML =
      `${frame.barLabel}   ·   ${zBit}Спред ${spHover}${regimeHtml}${zoneHtml}${cascadeHtml}   ·   ${tipPos}   ·   сигн. ${sigCount}   ·   ${threshText}${tpText}${holdText}${riskText}${capText}${slipText}${modeText}${baseText}${addonText}${extraText}${weekendText}${swingText}${adaptText}${shelfFfText}${tipNote}`;

    const pct = Math.round(engine.progressFraction * 100);
    $('progress').textContent = `${pct}%`;
    if (!scrubbing) $('scrub').value = Math.round(engine.progressFraction * 1000);

    // Таблица: seek/параметры — всегда; light ±1 бар — сигнал / open / tip1m (срез по курсору)
    const cursorDelta = prevCursor < 0 ? 999 : Math.abs(frame.cursorIndex - prevCursor);
    const bigSeek = cursorChanged && !playing && cursorDelta > 1;
    const forceTrades = !light || edgesChanged || afterParams || bigSeek
      || (cursorChanged && !playing && isTip1mMode());
    if (forceTrades) {
      // Большой seek: сначала лёгкие строки (без Min/Max/Hit), extras — чуть позже
      if (bigSeek && !afterParams && !isTip1mMode()) {
        skipTradeExtrasOnce = true;
        refreshTradesTable();
        if (enrichAfterParamsTimer) clearTimeout(enrichAfterParamsTimer);
        enrichAfterParamsTimer = setTimeout(() => {
          enrichAfterParamsTimer = null;
          if (!engine) return;
          skipTradeExtrasOnce = false;
          refreshTradesTable();
        }, 120);
      } else {
        refreshTradesTable();
      }
    } else if (frame.position !== 'Flat' || isTip1mMode()) {
      if (tradesRefreshTimer) clearTimeout(tradesRefreshTimer);
      tradesRefreshTimer = setTimeout(() => {
        tradesRefreshTimer = null;
        refreshTradesTable();
      }, 120);
    }
  }

  function forceFinalBarsSourceOnce() {
    // Testing UI: always geometric final CSV; ignore legacy «как Прод» / as_live prefs.
    if (localStorage.getItem(LS.barsSourceFinalOnlyV3)) {
      if (localStorage.getItem(LS.barsSource) !== 'final') {
        localStorage.setItem(LS.barsSource, 'final');
      }
      return;
    }
    localStorage.setItem(LS.barsSourceFinalOnlyV3, '1');
    localStorage.setItem(LS.barsSource, 'final');
  }

  function getEdgeMode() {
    return 'tip1m';
  }

  function isTip1mMode() {
    return true;
  }

  function setEdgeMode(_mode) {
    localStorage.setItem(LS.edgeMode, 'tip1m');
    syncEdgeModeChips();
    return 'tip1m';
  }

  function syncEdgeModeChips() {
    document.body.classList.add('edge-tip1m');
    document.body.classList.remove('edge-m15');
    if (localStorage.getItem(LS.transitionSwingMode) === '1') {
      localStorage.setItem(LS.transitionSwingMode, '');
    }
    syncBaseModeChip();
    syncAddon27Chip();
    syncExtra19Chip();
    syncWeekendTradingChip();
    syncZoneSwingChip();
    syncAdaptCorridorChip();
    syncShelfFloorCeilingChip();
    syncChartOverlayChips();
  }

  /** База AUTO 3.2/6.1: по умолчанию ВКЛ (ключ отсутствует или не «0»). */
  function isBaseMode() {
    return localStorage.getItem(LS.baseMode) !== '0';
  }

  function isAddon27Mode() {
    return localStorage.getItem(LS.addonMode27) === '1';
  }

  function isExtremeAddonMode() {
    return localStorage.getItem(LS.extremeAddonMode) === '1';
  }

  function isWeekendTradingMode() {
    return localStorage.getItem(LS.weekendTrading) === '1';
  }

  function isZoneSwingMode() {
    return false;
  }

  function isAdaptCorridorMode() {
    if (!TEST_DRAW_CORRIDOR) return false;
    return localStorage.getItem(LS.adaptiveCorridorMode) === '1';
  }

  function isShelfFloorCeilingMode() {
    return localStorage.getItem(LS.shelfFloorCeilingMode) === '1';
  }

  function setBaseMode(on) {
    localStorage.setItem(LS.baseMode, on ? '1' : '0');
    syncBaseModeChip();
    return !!on;
  }

  function setAddon27Mode(on) {
    localStorage.setItem(LS.addonMode27, on ? '1' : '');
    if (on) {
      localStorage.setItem(LS.transitionSwingMode, '');
    }
    syncAddon27Chip();
    syncExtra19Chip();
    syncZoneSwingChip();
    syncAdaptCorridorChip();
    return !!on;
  }

  function setExtremeAddonMode(on) {
    localStorage.setItem(LS.extremeAddonMode, on ? '1' : '');
    if (on) {
      localStorage.setItem(LS.transitionSwingMode, '');
    }
    syncExtra19Chip();
    syncAddon27Chip();
    syncZoneSwingChip();
    syncAdaptCorridorChip();
    return !!on;
  }

  function setWeekendTradingMode(on) {
    localStorage.setItem(LS.weekendTrading, on ? '1' : '');
    const url = new URL(window.location.href);
    if (on) url.searchParams.set('weekend_trading', 'true');
    else url.searchParams.delete('weekend_trading');
    window.history.replaceState(window.history.state, '', url);
    syncWeekendTradingChip();
    return !!on;
  }

  function setZoneSwingMode(on) {
    localStorage.setItem(LS.transitionSwingMode, on ? '1' : '');
    if (on) {
      localStorage.setItem(LS.addonMode27, '');
      localStorage.setItem(LS.extremeAddonMode, '');
      localStorage.setItem(LS.adaptiveCorridorMode, '');
    }
    syncZoneSwingChip();
    syncAddon27Chip();
    syncExtra19Chip();
    syncAdaptCorridorChip();
    return !!on;
  }

  function setAdaptCorridorMode(on) {
    localStorage.setItem(LS.adaptiveCorridorMode, on ? '1' : '');
    if (on) {
      localStorage.setItem(LS.transitionSwingMode, '');
    }
    syncAdaptCorridorChip();
    syncAddon27Chip();
    syncExtra19Chip();
    syncZoneSwingChip();
    return !!on;
  }

  function setShelfFloorCeilingMode(on) {
    localStorage.setItem(LS.shelfFloorCeilingMode, on ? '1' : '');
    syncShelfFloorCeilingChip();
    return !!on;
  }

  function syncBaseModeChip() {
    const on = isBaseMode();
    const btn = $('btnBaseMode');
    if (btn) {
      btn.classList.toggle('active', on);
      btn.setAttribute('aria-pressed', on ? 'true' : 'false');
    }
  }

  function syncAddon27Chip() {
    const on = isAddon27Mode();
    const btn = $('btnAddon27');
    if (btn) {
      btn.classList.toggle('active', on);
      btn.setAttribute('aria-pressed', on ? 'true' : 'false');
    }
  }

  function syncExtra19Chip() {
    const on = isExtremeAddonMode();
    const btn = $('btnExtra19');
    if (btn) {
      btn.classList.toggle('active', on);
      btn.setAttribute('aria-pressed', on ? 'true' : 'false');
    }
  }

  function syncWeekendTradingChip() {
    const on = isWeekendTradingMode();
    const btn = $('btnWeekendTrading');
    if (btn) {
      btn.classList.toggle('active', on);
      btn.setAttribute('aria-pressed', on ? 'true' : 'false');
    }
  }

  function syncZoneSwingChip() {
    const on = isZoneSwingMode();
    const btn = $('btnZoneSwing');
    if (btn) {
      btn.classList.toggle('active', on);
      btn.setAttribute('aria-pressed', on ? 'true' : 'false');
    }
  }

  function syncShelfFloorCeilingChip() {
    const on = isShelfFloorCeilingMode();
    const btn = $('btnShelfFloorCeil');
    if (btn) {
      btn.classList.toggle('active', on);
      btn.setAttribute('aria-pressed', on ? 'true' : 'false');
    }
  }

  function syncAdaptCorridorChip() {
    const btn = $('btnAdaptCorridor');
    if (!btn) return;
    if (!TEST_DRAW_CORRIDOR) {
      btn.hidden = true;
      btn.setAttribute('aria-hidden', 'true');
      btn.classList.remove('active');
      btn.setAttribute('aria-pressed', 'false');
      return;
    }
    const on = isAdaptCorridorMode();
    btn.hidden = false;
    btn.removeAttribute('aria-hidden');
    btn.classList.toggle('active', on);
    btn.setAttribute('aria-pressed', on ? 'true' : 'false');
  }

  function isShowCascadeEnabled() {
    return localStorage.getItem(LS.testShowCascade) === '1';
  }

  function isShowZoneBandsEnabled() {
    return localStorage.getItem(LS.testShowZoneBands) === '1';
  }

  /** Полки на графике Теста: вкл по умолчанию (выкл только явным «0»). */
  function isShowWideShelvesEnabled() {
    if (!TEST_DRAW_WIDE_CORRIDOR) return false;
    return localStorage.getItem(LS.testShowWideShelves) !== '0';
  }

  function syncChartOverlayChips() {
    const cascadeOn = isShowCascadeEnabled();
    const zoneOn = isShowZoneBandsEnabled();
    const shelvesOn = isShowWideShelvesEnabled();
    const btnCascade = $('btnShowCascade');
    const btnZone = $('btnShowZoneBands');
    const btnShelves = $('btnShowWideShelves');
    if (btnCascade) {
      btnCascade.classList.toggle('active', cascadeOn);
      btnCascade.setAttribute('aria-pressed', cascadeOn ? 'true' : 'false');
    }
    if (btnZone) {
      btnZone.classList.toggle('active', zoneOn);
      btnZone.setAttribute('aria-pressed', zoneOn ? 'true' : 'false');
    }
    if (btnShelves) {
      btnShelves.hidden = !TEST_DRAW_WIDE_CORRIDOR;
      btnShelves.setAttribute('aria-hidden', TEST_DRAW_WIDE_CORRIDOR ? 'false' : 'true');
      btnShelves.classList.toggle('active', shelvesOn);
      btnShelves.setAttribute('aria-pressed', shelvesOn ? 'true' : 'false');
    }
  }

  function invalidateChartOverlayCache() {
    uiSeriesCache.cascadeVlines = null;
    uiSeriesCache.cascadeCursorKey = '';
    pendingChartRepaint = true;
  }

  function setShowCascade(on) {
    saveSetting(LS.testShowCascade, on ? '1' : '');
    syncChartOverlayChips();
    invalidateChartOverlayCache();
    refreshUi();
    return !!on;
  }

  function setShowZoneBands(on) {
    saveSetting(LS.testShowZoneBands, on ? '1' : '');
    syncChartOverlayChips();
    invalidateChartOverlayCache();
    refreshUi();
    return !!on;
  }

  function setShowWideShelves(on) {
    saveSetting(LS.testShowWideShelves, on ? '1' : '0');
    syncChartOverlayChips();
    pendingChartRepaint = true;
    refreshUi({ afterParams: true });
    return !!on;
  }

  function edgeModeLabel() {
    return 'касание 1м';
  }

  function tipSimRequestKey() {
    const t = thresholds();
    const slip = typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0;
    const lv = readHmSelectedSpreadLevels();
    return [
      'v13baseMode',
      $('csvSel')?.value || '',
      $('startDate')?.value || '',
      readWindowEndYmd(),
      t.entry, t.exit, t.takeProfitPct || 0,
      holdParamsKey(),
      getSimNotionalRub(), getSimCompound() ? 1 : 0, slip,
      'final',
      isTip1mMode() ? 1 : 0,
      isBaseMode() ? 1 : 0,
      isAddon27Mode() ? 1 : 0,
      isExtremeAddonMode() ? 1 : 0,
      isWeekendTradingMode() ? 1 : 0,
      isZoneSwingMode() ? 1 : 0,
      isAdaptCorridorMode() ? 1 : 0,
      isShelfFloorCeilingMode() ? 1 : 0,
      lv.enter_wide, lv.exit_wide, lv.enter_narrow, lv.exit_narrow,
    ].join('|');
  }

  function tipTradeToRow(t, entryThreshold) {
    const lv = typeof readHmSelectedSpreadLevels === 'function'
      ? readHmSelectedSpreadLevels()
      : null;
    const risk = typeof assessTradeRisk === 'function'
      ? assessTradeRisk(t.entryDate, t.exitDate, t.entryZ, t.overnight, entryThreshold, {
        direction: t.direction,
        entrySpread: t.entrySpread,
        exitSpread: t.exitSpread,
        levels: lv,
      })
      : null;
    const pnlMin = t.pnlMin != null && Number.isFinite(Number(t.pnlMin)) ? Number(t.pnlMin) : null;
    const pnlMax = t.pnlMax != null && Number.isFinite(Number(t.pnlMax)) ? Number(t.pnlMax) : null;
    let accountBefore = t.accountBefore != null && Number.isFinite(Number(t.accountBefore))
      ? Number(t.accountBefore)
      : (t.account_before_rub != null && Number.isFinite(Number(t.account_before_rub))
        ? Number(t.account_before_rub)
        : null);
    const accountAfter = t.accountAfter != null && Number.isFinite(Number(t.accountAfter))
      ? Number(t.accountAfter)
      : null;
    const tipNet = t.net != null && Number.isFinite(Number(t.net)) ? Number(t.net) : null;
    if (accountBefore == null && accountAfter != null && tipNet != null) {
      accountBefore = accountAfter - tipNet;
    }
    const hit1Date = t.hit1Date || null;
    const hit2Date = t.hit2Date || null;
    const hit3Date = t.hit3Date || null;
    const parseMs = typeof parseTradeMs === 'function' ? parseTradeMs : () => null;
    const modelNet = t.modelNet != null && Number.isFinite(Number(t.modelNet))
      ? Number(t.modelNet)
      : (t.model_net != null && Number.isFinite(Number(t.model_net)) ? Number(t.model_net) : null);
    const reason = String(t.exitReason || t.exit_reason || '');
    const exitTitle = reason === 'hold_losing'
      ? 'стоп: дни в минусе'
      : reason === 'hold_no_trend'
        ? 'стоп: нет хода к выходу'
        : reason === 'tp'
          ? 'тейк-профит'
          : reason === 'spread_exit'
            ? 'выход по уровню спреда'
            : reason === 'extra_exit'
              ? (String(t.direction) === 'Short'
                ? 'экстра: выход 7'
                : 'экстра: выход 2')
            : reason === 'addon_exit'
              ? (String(t.direction) === 'Short'
                ? 'добор: выход 6.1'
                : 'добор: выход 3.2')
              : (t.exitTitle || '');
    const tag = String(t.tag || t.source || '');
    const source = tag === 'addon' || tag === 'добор'
      ? 'добор'
      : (tag === 'extra' || tag === 'экстра' || tag === 'extreme')
        ? 'экстра'
      : (isShelfFloorCeilingSrc(t) || tag === 'shelf_ff' || tag === 'пол–потолок' || tag === 'пол-потолок')
        ? 'пол–потолок'
      : (tag === 'adapt_corridor' || tag === 'адапт.коридор' || tag.indexOf('адапт') >= 0)
        ? 'адапт.коридор'
        : (tag === 'swing' || tag === 'качалка' || tag === 'коридор')
          ? 'качалка'
          : (tag === 'main' || tag === 'база' ? 'база' : (t.source || null));
    return makeTradeRow({
      index: t.index,
      direction: t.direction,
      tag: t.tag || (source === 'пол–потолок' ? 'shelf_ff' : null),
      exitReason: t.exitReason || t.exit_reason || reason || null,
      source,
      entryDate: t.entryDate,
      exitDate: t.exitDate,
      entryZ: t.entryZ,
      exitZ: t.exitZ,
      entrySpread: t.entrySpread,
      exitSpread: t.exitSpread,
      entrySlip: t.entrySlip,
      pnlPts: t.pnlPts,
      gross: t.gross,
      commission: t.commission,
      overnight: t.overnight,
      net: t.net,
      modelNet,
      mtm: t.mtm,
      openMtm: t.openMtm != null ? t.openMtm : t.mtm,
      netFromAccount: !!(t.netFromAccount || t.net_from_account),
      accountBefore,
      accountAfter,
      pnlMin,
      pnlMax,
      hit1Date,
      hit2Date,
      hit3Date,
      hit1Ms: hit1Date ? parseMs(hit1Date) : null,
      hit2Ms: hit2Date ? parseMs(hit2Date) : null,
      hit3Ms: hit3Date ? parseMs(hit3Date) : null,
      status: t.status || 'Закрыта',
      entryThreshold,
      exitTitle,
      _risk: risk,
      notional: t.notional,
      netRef100k: t.netRef100k != null && Number.isFinite(Number(t.netRef100k))
        ? Number(t.netRef100k)
        : null,
    });
  }

  function scheduleTipSimFetch({ immediate = false } = {}) {
    if (!isTip1mMode()) return;
    if (tipSimTimer) clearTimeout(tipSimTimer);
    // Debounce param tweaks; seek/cursor uses tipRowsUpToCursor (no server POST).
    const delay = immediate ? 0 : 160;
    tipSimTimer = setTimeout(() => {
      tipSimTimer = null;
      fetchTipSim().catch(() => {});
    }, delay);
  }

  async function fetchTipSim() {
    if (!isTip1mMode()) return;
    const key = tipSimRequestKey();
    if (key === tipSimCache.key && tipSimCache.rows) {
      refreshTradesTable();
      return;
    }
    const jobId = ++tipSimJobId;
    markMonthlyPnlPending();
    const st = $('status');
    if (st && !tipSimCache.rows) {
      st.textContent = `${isWeekendTradingMode() ? 'выходные · ' : ''}касание 1м · считаю на сервере…`;
    }
    const t = thresholds();
    const csv = $('csvSel')?.value || 'm15_tatn_255d.csv';
    const body = {
      csv,
      start: $('startDate')?.value || null,
      end: readWindowEndYmd() || null,
      entry: t.entry,
      exit: t.exit,
      slip: typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0.02,
      notional: getSimNotionalRub(),
      compound: getSimCompound(),
      takeProfitPct: t.takeProfitPct || 0,
      maxHoldDaysNoExitTrend: readHoldParams().maxHoldDaysNoExitTrend,
      maxHoldDaysIfLosing: readHoldParams().maxHoldDaysIfLosing,
      as_live: 0,
      regime_z_mode: 0,
      spread_level_mode: 1,
      spread_levels: readHmSelectedSpreadLevels(),
      base: isBaseMode(),
      enable_base: isBaseMode() ? 1 : 0,
      baseMode: isBaseMode() ? 1 : 0,
      addon_mode: isAddon27Mode() ? 1 : 0,
      extreme_addon_mode: isExtremeAddonMode() ? 1 : 0,
      weekend_trading: isWeekendTradingMode(),
      transition_swing_mode: isZoneSwingMode() ? 1 : 0,
      adaptive_corridor_mode: isAdaptCorridorMode() ? 1 : 0,
      shelf_floor_ceiling_mode: isShelfFloorCeilingMode() ? 1 : 0,
    };
    const timeoutMs = tip1mSimTimeoutMs();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const started = Date.now();
    const timeoutSec = Math.round(timeoutMs / 1000);
    let busyLive = true;
    const tickBusy = async () => {
      if (!busyLive || jobId !== tipSimJobId) return;
      const sec = Math.round((Date.now() - started) / 1000);
      let phase = '';
      try {
        const br = await fetch('/api/tip/busy', { signal: AbortSignal.timeout(800) });
        if (br.ok) {
          const b = await br.json();
          if (b && b.phaseRu && b.phase !== 'idle') {
            phase = ` · ${b.phaseRu}`;
            if (b.tipBuildLock) phase += ' · ждёт лок 3г';
          }
        }
      } catch (_) { /* health/busy не должен ронять сим */ }
      const line = `${isWeekendTradingMode() ? 'выходные · ' : ''}касание 1м · считаю на сервере… ${sec}/${timeoutSec} с${phase}`;
      if (st && !tipSimCache.rows) st.textContent = line;
      const grid = $('tradesSummaryGrid');
      if (grid && !tipSimCache.rows) {
        grid.innerHTML = `<div class="trades-summary-note wide">${line}</div>`;
      }
    };
    tickBusy();
    const tickTimer = setInterval(tickBusy, 500);
    const stopBusyTicks = () => {
      busyLive = false;
      clearInterval(tickTimer);
    };
    try {
      const res = await fetch('/api/sim/tip1m', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      if (!res.ok) {
        const errText = await res.text();
        throw new Error(errText || res.statusText);
      }
      const data = await res.json();
      if (jobId !== tipSimJobId) return;
      const entryTh = t.entry;
      const rows = (data.trades || []).map((tr) => tipTradeToRow(tr, entryTh));
      if (key !== tipSimCache.key) clearTipManualOverrides();
      tipSimCache = {
        key,
        rows,
        meta: data.meta || null,
        summary: data.summary || null,
      };
      stopBusyTicks();
      refreshTradesTable();
      refreshUi({ afterParams: true });
    } catch (e) {
      if (jobId !== tipSimJobId) return;
      const timedOut = e && e.name === 'AbortError';
      const msg = timedOut
        ? `таймаут ${Math.round(timeoutMs / 1000)} с — сервер не ответил (перезапустите сервис / F5)`
        : ((e && e.message) ? String(e.message).slice(0, 180) : String(e));
      if (!timedOut) {
        tipSimCache = { key: '', rows: null, meta: null, summary: null };
        clearTipManualOverrides();
      }
      if (st) {
        st.textContent = `${isWeekendTradingMode() ? 'выходные · ' : ''}касание 1м · ошибка: ${msg}`;
      }
      const grid = $('tradesSummaryGrid');
      if (grid) {
        grid.innerHTML =
          `<div class="trades-summary-note wide">касание 1м · ошибка: ${msg}</div>`;
      }
      if (!timedOut) alert('Касание 1м (сервер): ' + msg);
    } finally {
      stopBusyTicks();
      clearTimeout(timer);
    }
  }

  async function fetchTipHeatmap(_grid, { timeoutMs } = {}) {
    return { cells: [], meta: { heatmapDisabled: true, heatmapSec: 0 } };
  }

  function barsLoadTimeoutMs(csv) {
    const name = String(csv || '');
    // 3y (~50k bars / ~8MB JSON): allow longer parse; server no longer blocks on MOEX.
    if (name.includes('1095') || /_3y/i.test(name)) return 120000;
    if (name.includes('365')) return 75000;
    return 45000;
  }

  async function loadBars(csv) {
    const startDate = $('startDate').value;
    let url = `/api/bars?csv=${encodeURIComponent(csv)}`;
    if (startDate) url += `&start=${encodeURIComponent(startDate)}`;
    const title = $('loadingTitle');
    const sub = $('loadingSub');
    const timeoutMs = barsLoadTimeoutMs(csv);
    const timeoutSec = Math.round(timeoutMs / 1000);
    if (title) title.textContent = `Загрузка ${csv}…`;
    if (sub) {
      sub.textContent =
        `запрос /api/bars · лимит ${timeoutSec} с`;
    }
    const started = Date.now();
    const tick = setInterval(() => {
      if (sub) {
        sub.textContent =
          `запрос /api/bars · ${Math.round((Date.now() - started) / 1000)} / ${timeoutSec} с`;
      }
    }, 500);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const res = await fetch(url, { signal: controller.signal });
      if (!res.ok) throw new Error(await res.text());
      if (sub) sub.textContent = 'разбор JSON…';
      const data = await res.json();
      if (sub) sub.textContent = `готово: ${data.count || 0} баров за ${Math.round((Date.now() - started) / 1000)} с`;
      return data;
    } catch (e) {
      if (e.name === 'AbortError') {
        throw new Error(
          `Таймаут загрузки (${timeoutSec} с). Перезапустите run-replay-web.bat или проверьте локальный CSV/SQLite.`,
        );
      }
      throw e;
    } finally {
      clearInterval(tick);
      clearTimeout(timer);
    }
  }

  async function bootstrap(csv, opts = {}) {
    const background = !!opts.background;
    const title = $('loadingTitle');
    const sub = $('loadingSub');
    const tip1m = isTip1mMode();
    try {
      if (!background) {
        $('loading').classList.remove('hidden');
        $('app').classList.add('hidden');
      }
      if (tip1m) {
        tipSimCache = { key: '', rows: null, meta: null, summary: null };
        clearTipManualOverrides();
        if (!background && title) title.textContent = 'Касание 1м…';
        if (!background && sub) {
          sub.textContent = 'сделки и график 90д сразу, M15 в фоне';
        }
        if (!chart) {
          chart = new ReplayChart($('chart'), {
            onSelectionChange: (id) => {
              selectedTradeId = id;
              refreshTradesTable();
            },
          });
        }
        scheduleTipSimFetch({ immediate: true });
        if (isChartsHidden()) pendingChartRepaint = true;
        else activateTip1mChart().catch((e) => console.error(e));
        if (!background) {
          $('loading').classList.add('hidden');
          $('app').classList.remove('hidden');
          requestAnimationFrame(() => {
            reapplyLayoutFromStorage();
            chart?.resize();
          });
        }
      }
      const data = await loadBars(csv);
      const windowBars = sliceBarsToEnd(data.bars || [], readWindowEndYmd());
      if (!background) {
        if (title) title.textContent = 'Инициализация графика…';
        if (sub) sub.textContent = `${windowBars.length} баров`;
      }
      // Кэш хранит ряд от start до хвоста CSV; конец окна режем локально.
      rememberBarsCache(csv, data.bars);
      const tipChartActive = !!(tip1m && tip1mChartMeta && tip1mChartMeta._active);
      if (tipChartActive) {
        m15PointsStash = windowBars;
      } else {
        allPoints = windowBars;
      }
      updateMoexLastBarHint(allPoints.length ? allPoints[allPoints.length - 1]?.tradeDate : data.last);
      if (!opts.keepSelection) {
        chartFocusIndex = null;
        selectedTradeId = null;
      }
      const src = data.source === 'sqlite' ? 'SQLite' : 'CSV';
      const netBadge = data.online
        ? (data.refreshed
          ? '<span class="badge-online">MOEX tail</span>'
          : '<span class="badge-online">online</span>')
        : '<span class="badge-offline">offline</span>';
      const csvSafe = String(data.csv || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      if (!tipChartActive) {
        $('meta').innerHTML = `TATN/TATNP · ${allPoints.length} баров · ${csvSafe} · ${src} · ${netBadge}`;
        rebuildEngine();
        // Full reload of bars window: jump to end so СДЕЛКИ/PnL cover the whole period
        // (keepSelection=true for MOEX tail refresh — preserve scrubber).
        if (!opts.keepSelection && engine) engine.seekToEnd();
        chart?.followReplayEdge(true);
      }
      reapplyLayoutFromStorage();
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
      if (!chart) {
        chart = new ReplayChart($('chart'), {
          onSelectionChange: (id) => {
            selectedTradeId = id;
            refreshTradesTable();
          },
        });
      } else {
        chart.resize();
      }
      if (!background && title) title.textContent = 'Построение сделок…';
      if (!background && sub) {
        sub.textContent = `${data.count || 0} баров · касание 1м…`;
      }
      try {
        refreshUi();
      } catch (paintErr) {
        console.error(paintErr);
      }
      if (isTip1mMode()) {
        if (!tipSimCache.rows) {
          scheduleTipSimFetch({ immediate: true });
        }
        if (isChartsHidden()) {
          pendingChartRepaint = true;
        } else if (!(tip1mChartMeta && tip1mChartMeta._active)) {
          // Не держим чёрный экран на JSON минуток: 1м догружается после показа UI.
          activateTip1mChart().catch((e) => console.error(e));
        }
      }
      setTimeout(() => chart?.resize(), 100);
      if (!background) {
        $('loading').classList.add('hidden');
        $('app').classList.remove('hidden');
        // Layout + scroll restore must run after #app is visible (display:none zeroes sizes).
        reapplyLayoutFromStorage();
        restoreTradesSummaryScrollAfterVisible();
        requestAnimationFrame(() => {
          reapplyLayoutFromStorage();
          chart?.resize();
        });
      }
      return data;
    } catch (e) {
      if (background) {
        console.error(e);
        return null;
      }
      if (title) title.textContent = 'Ошибка загрузки';
      if (sub) sub.textContent = e.message || String(e);
      else $('loading').textContent = `Ошибка: ${e.message}`;
      console.error(e);
      return null;
    }
  }

  function formatMoexLastBarLabel(tradeDate) {
    if (!tradeDate) return '';
    return String(tradeDate).replace('T', ' ').trim().slice(0, 16);
  }

  function updateMoexLastBarHint(tradeDate) {
    const el = $('moexLastBarTs');
    if (!el) return;
    const last = tradeDate
      || (allPoints.length ? allPoints[allPoints.length - 1]?.tradeDate : null);
    el.textContent = formatMoexLastBarLabel(last);
  }

  function setMoexRefreshStatus(text, kind) {
    const el = $('moexRefreshStatus');
    if (!el) return;
    el.textContent = text || '';
    el.classList.remove('is-ok', 'is-err', 'is-pending');
    if (kind === 'ok') el.classList.add('is-ok');
    else if (kind === 'err') el.classList.add('is-err');
    else if (kind === 'pending') el.classList.add('is-pending');
  }

  function fetchErrorMessageRu(e, timeoutMs) {
    if (e && (e.name === 'AbortError' || /aborted/i.test(String(e && e.message || '')))) {
      return `таймаут ${Math.round(timeoutMs / 1000)} с — сервер не ответил (перезапустите сервис / Ctrl+F5)`;
    }
    const raw = String((e && e.message) || e || '');
    if (/Failed to fetch|NetworkError|Load failed|network/i.test(raw)) {
      return 'связь с сервером оборвалась (рестарт службы или таймаут). Подождите и повторите';
    }
    return raw.slice(0, 180);
  }

  async function fetchWithTimeout(url, opts, timeoutMs) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await fetch(url, { ...(opts || {}), signal: controller.signal });
    } finally {
      clearTimeout(timer);
    }
  }

  async function pingHealthOk() {
    try {
      const res = await fetchWithTimeout('/api/health', {}, 5000);
      return !!(res && res.ok);
    } catch (_) {
      return false;
    }
  }

  /** Дотянуть хвост MOEX (как Trade) и перезалить бары в движок без полного сброса UI. */
  async function refreshMoexForTesting() {
    const btn = $('btnRefreshMoex');
    const csv = $('csvSel')?.value || 'm15_tatn_255d.csv';
    const timeoutMs = 90000;
    if (btn) btn.disabled = true;
    setMoexRefreshStatus('Обновление…', 'pending');
    const postRefresh = () => fetchWithTimeout(
      `/api/markets/refresh?days=7&csv=${encodeURIComponent(csv)}`,
      { method: 'POST' },
      timeoutMs,
    );
    try {
      let syncRes;
      try {
        syncRes = await postRefresh();
      } catch (e1) {
        const healthOk = await pingHealthOk();
        if (!healthOk) {
          throw new Error(fetchErrorMessageRu(e1, timeoutMs));
        }
        setMoexRefreshStatus('Повтор…', 'pending');
        await new Promise((r) => setTimeout(r, 800));
        try {
          syncRes = await postRefresh();
        } catch (e2) {
          throw new Error(fetchErrorMessageRu(e2, timeoutMs));
        }
      }
      if (!syncRes.ok) {
        const errText = await syncRes.text();
        throw new Error(errText || `HTTP ${syncRes.status}`);
      }
      const data = await bootstrap(csv, { background: true, keepSelection: true });
      if (!data) throw new Error('Не удалось перезагрузить бары');
      const count = data.count ?? allPoints.length;
      const last = data.last || (allPoints.length ? allPoints[allPoints.length - 1]?.tradeDate : null);
      updateMoexLastBarHint(last);
      setMoexRefreshStatus(count ? `ОК · ${count} бар.` : 'ОК', 'ok');
      setTimeout(() => setMoexRefreshStatus('', null), 2500);
    } catch (e) {
      console.error(e);
      setMoexRefreshStatus(`Ошибка: ${fetchErrorMessageRu(e, timeoutMs)}`, 'err');
    } finally {
      if (btn) btn.disabled = false;
    }
  }

  function setPlayButtonLabel(isPlaying) {
    const btn = $('btnPlay');
    if (!btn) return;
    btn.textContent = isPlaying ? '⏸' : '▶';
    btn.title = isPlaying ? 'Pause' : 'Play';
  }

  function stopTimer() {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }

  function startTimer() {
    stopTimer();
    timer = setInterval(() => {
      if (scrubbing) return;
      const next = engine.stepForward();
      if (!next) {
        playing = false;
        setPlayButtonLabel(false);
        stopTimer();
        refreshUi(); // полный кадр при остановке
        return;
      }
      refreshUi({ light: true });
    }, barReplayDelayMs(speed));
  }

  function snapChartsToReplayEdge() {
    chartFocusIndex = null;
    chart?.followReplayEdge(true);
  }

  function togglePlay() {
    if (playing) {
      playing = false;
      engine.pause();
      setPlayButtonLabel(false);
      stopTimer();
    } else {
      snapChartsToReplayEdge();
      engine.play();
      playing = true;
      setPlayButtonLabel(true);
      startTimer();
    }
    refreshUi();
  }

  function pausePlayback() {
    playing = false;
    stopTimer();
    setPlayButtonLabel(false);
    engine?.pause();
  }

  function stepBackOne() {
    playing = false;
    stopTimer();
    setPlayButtonLabel(false);
    snapChartsToReplayEdge();
    engine.stepBackward();
    refreshUi({ light: true });
  }

  function stepFwdOne() {
    playing = false;
    stopTimer();
    setPlayButtonLabel(false);
    snapChartsToReplayEdge();
    engine.pause();
    engine.stepForward();
    refreshUi({ light: true });
  }

  function jumpToStart() {
    playing = false;
    stopTimer();
    setPlayButtonLabel(false);
    snapChartsToReplayEdge();
    engine.seekToStart();
    refreshUi();
  }

  function jumpToEnd() {
    if (!engine) return;
    playing = false;
    stopTimer();
    setPlayButtonLabel(false);
    snapChartsToReplayEdge();
    engine.seekToEnd();
    refreshUi();
  }

  function isReplayViewActive() {
    return $('app')?.dataset.view === 'replay';
  }

  function isEditableKeyTarget(el) {
    if (!el || el === document.body) return false;
    const tag = (el.tagName || '').toUpperCase();
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return true;
    if (el.isContentEditable) return true;
    return false;
  }

  function bindReplayKeyboard() {
    document.addEventListener('keydown', (e) => {
      if (!isReplayViewActive() || !engine) return;
      if (isEditableKeyTarget(e.target)) return;

      if (e.code === 'Space') {
        if (e.repeat) return;
        e.preventDefault();
        togglePlay();
        return;
      }

      if (e.key === 'ArrowRight') {
        e.preventDefault();
        if (e.ctrlKey) jumpToEnd();
        else stepFwdOne();
        return;
      }

      if (e.key === 'ArrowLeft') {
        e.preventDefault();
        if (e.ctrlKey) jumpToStart();
        else stepBackOne();
      }
    });
  }

  function isMobileLayout() {
    return window.matchMedia('(max-width: 900px)').matches;
  }

  function setReplayParamsOpen(open) {
    const toolbar = document.querySelector('.toolbar');
    const btn = $('btnMobileReplayParams');
    if (!toolbar) return;
    toolbar.classList.toggle('is-replay-params-open', !!open);
    if (btn) btn.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  let mobileReplayPriorityApplied = false;
  function applyMobileReplayChartPriority() {
    if (!isMobileLayout() || mobileReplayPriorityApplied) return;
    mobileReplayPriorityApplied = true;
    // Free vertical space for Z chart; user can re-expand via pane buttons.
    let changed = false;
    if (!isDeltaPaneHidden()) {
      setPaneCollapsed('deltaPane', true);
      changed = true;
    }
    if (!isPnlPaneHidden()) {
      setPaneCollapsed('pnlPane', true);
      changed = true;
    }
    if (changed) syncPaneCollapseUi();
  }

  window.__moexSetReplayParamsOpen = setReplayParamsOpen;
  window.__moexApplyMobileReplayLayout = applyMobileReplayChartPriority;

  function bindControls() {
    $('btnPlay').addEventListener('click', togglePlay);
    $('btnBack').addEventListener('click', stepBackOne);
    $('btnFwd').addEventListener('click', stepFwdOne);
    $('btnStart').addEventListener('click', jumpToStart);
    $('btnEnd').addEventListener('click', jumpToEnd);
    bindReplayKeyboard();

    $('btnMobileReplayParams')?.addEventListener('click', () => {
      const toolbar = document.querySelector('.toolbar');
      const open = !toolbar?.classList.contains('is-replay-params-open');
      setReplayParamsOpen(open);
      requestAnimationFrame(() => {
        if (typeof window.__moexReplayResize === 'function') window.__moexReplayResize();
      });
    });

    $('btnLong').addEventListener('click', () => {
      if (!engine) return;
      pausePlayback();
      const status = $('status');
      if (isTip1mMode()) {
        const r = openTip1mManualAtCursor('Long');
        refreshTradesTable();
        refreshUi();
        const note = tipManualStatusNote(r);
        if (status && note) {
          const base = status.textContent.split('·')[0].trim();
          status.textContent = r.ok
            ? `${base} · ${note}`
            : `${base} · ${note}`;
        }
        return;
      }
      engine.manualLong();
      refreshUi();
    });
    $('btnShort').addEventListener('click', () => {
      if (!engine) return;
      pausePlayback();
      const status = $('status');
      if (isTip1mMode()) {
        const r = openTip1mManualAtCursor('Short');
        refreshTradesTable();
        refreshUi();
        const note = tipManualStatusNote(r);
        if (status && note) {
          const base = status.textContent.split('·')[0].trim();
          status.textContent = `${base} · ${note}`;
        }
        return;
      }
      engine.manualShort();
      refreshUi();
    });
    $('btnCloseAll').addEventListener('click', () => {
      if (!engine) return;
      pausePlayback();
      const status = $('status');
      if (isTip1mMode()) {
        // tip1m СДЕЛКИ = server sim rows, not M15 engine.manualEdges
        const before = tipPositionFromRows(tipRowsUpToCursor(tipSimCache.rows || []));
        const closed = closeAllTip1mOpensAtCursor();
        refreshTradesTable();
        refreshUi();
        const after = tipPositionFromRows(tipRowsUpToCursor(tipSimCache.rows || []));
        if (status && before !== 'Flat') {
          status.textContent = closed || after === 'Flat'
            ? `${status.textContent.split('·')[0]}· закрыто вручную (${before}→Flat)`
            : `${status.textContent} · не удалось закрыть (${before})`;
        }
        return;
      }
      const before = engine.openSideFromEdges();
      const closed = engine.closeAllTrades();
      refreshUi();
      const after = engine.openSideFromEdges();
      if (status && before !== 'Flat') {
        status.textContent = closed || after === 'Flat'
          ? `${status.textContent.split('·')[0]}· закрыто вручную (${before}→Flat)`
          : `${status.textContent} · не удалось закрыть (${before})`;
      }
    });

    $('scrub').addEventListener('input', () => {
      scrubbing = true;
      playing = false;
      stopTimer();
      setPlayButtonLabel(false);
      snapChartsToReplayEdge();
      scrubPendingFrac = $('scrub').value / 1000;
      if (scrubRaf) return;
      scrubRaf = requestAnimationFrame(() => {
        scrubRaf = 0;
        if (scrubPendingFrac == null || !engine) return;
        const frac = scrubPendingFrac;
        scrubPendingFrac = null;
        const minC = engine.minCursor;
        const span = Math.max(0, engine.lastIndex - minC);
        engine.seekTo(Math.round(minC + frac * span));
        refreshUi();
      });
    });
    $('scrub').addEventListener('change', () => {
      scrubbing = false;
      if (scrubRaf) {
        cancelAnimationFrame(scrubRaf);
        scrubRaf = 0;
      }
      if (scrubPendingFrac != null && engine) {
        const frac = scrubPendingFrac;
        scrubPendingFrac = null;
        const minC = engine.minCursor;
        const span = Math.max(0, engine.lastIndex - minC);
        engine.seekTo(Math.round(minC + frac * span));
        refreshUi();
      }
    });

    $('entrySel').addEventListener('change', () => {
      saveSetting(LS.entry, $('entrySel').value);
      applyStrategyParamsChange();
    });
    $('exitSel').addEventListener('change', () => {
      saveSetting(LS.exit, $('exitSel').value);
      applyStrategyParamsChange();
    });

    const tpSel = $('tpSel');
    if (tpSel) {
      tpSel.addEventListener('change', () => {
        saveSetting(LS.takeProfit, tpSel.value);
        applyStrategyParamsChange();
      });
    }
    const holdNoTrendSel = $('holdNoTrendSel');
    if (holdNoTrendSel) {
      holdNoTrendSel.addEventListener('change', () => {
        saveSetting(LS.holdNoTrendDays, holdNoTrendSel.value);
        applyStrategyParamsChange();
      });
    }
    const holdLosingSel = $('holdLosingSel');
    if (holdLosingSel) {
      holdLosingSel.addEventListener('change', () => {
        saveSetting(LS.holdLosingDays, holdLosingSel.value);
        applyStrategyParamsChange();
      });
    }

    if (typeof RISK_EXIT_GROUPS !== 'undefined') {
      for (const g of RISK_EXIT_GROUPS) {
        const sel = $(g.selId);
        if (!sel) continue;
        sel.addEventListener('change', () => {
          saveSetting(g.lsKey, sel.value);
          riskCompareCache = { key: '', html: '' };
          applyStrategyParamsChange();
        });
      }
    }

    const compoundChk = $('compoundChk');
    if (compoundChk) {
      compoundChk.addEventListener('change', () => {
        setSimCompound(compoundChk.checked);
        saveSetting(LS.compound, compoundChk.checked ? '1' : '');
        applyStrategyParamsChange();
      });
    }
    const columnTipsChk = $('columnTipsChk');
    if (columnTipsChk) {
      columnTipsChk.addEventListener('change', () => {
        columnTipsEnabled = !!columnTipsChk.checked;
        saveSetting(LS.columnTips, columnTipsEnabled ? '1' : '0');
        if (!columnTipsEnabled) hideTradeMetricTip();
      });
    }

    const copyBtn = $('btnCopyLiveThresh');
    if (copyBtn) copyBtn.addEventListener('click', () => copyThresholdsFromLive());

    const applyNotionalFromUi = () => {
      const v = setSimNotionalRub($('notionalSel').value);
      $('notionalSel').value = String(v);
      saveSetting(LS.notionalRub, v);
      applyStrategyParamsChange();
    };
    $('notionalSel').addEventListener('change', applyNotionalFromUi);
    $('notionalSel').addEventListener('blur', applyNotionalFromUi);

    const applySlipFromUi = () => {
      if (typeof setSimSlippageSpreadPts !== 'function') return;
      const v = setSimSlippageSpreadPts($('slipSel')?.value ?? 0.05);
      if ($('slipSel')) $('slipSel').value = String(v);
      if ($('hmSlip')) $('hmSlip').value = String(v);
      saveSetting(LS.slippageSpreadPts, v);
      applyStrategyParamsChange();
    };
    $('slipSel')?.addEventListener('change', applySlipFromUi);
    $('slipSel')?.addEventListener('blur', applySlipFromUi);

    const persistHmGrid = () => {
      const g = readZHeatmapGridParams();
      if ($('hmEntryMin')) $('hmEntryMin').value = String(g.entryMin);
      if ($('hmEntryMax')) $('hmEntryMax').value = String(g.entryMax);
      if ($('hmExitMin')) $('hmExitMin').value = String(g.exitMin);
      if ($('hmExitMax') && g.exitMax != null) $('hmExitMax').value = String(g.exitMax);
      if ($('hmStep')) $('hmStep').value = String(g.step);
      saveSetting(LS.hmEntryMin, g.entryMin);
      saveSetting(LS.hmEntryMax, g.entryMax);
      saveSetting(LS.hmExitMin, g.exitMin);
      if (g.exitMax != null) saveSetting(LS.hmExitMax, g.exitMax);
      saveSetting(LS.hmStep, g.step);
      zHeatmapCache = { key: '', cells: null, grid: null, inFlightKey: '' };
      zHeatmapJobId += 1;
      scheduleZHeatmapUpdate(null, { immediate: true });
    };
    ['hmEntryMin', 'hmEntryMax', 'hmExitMin', 'hmExitMax', 'hmStep'].forEach((id) => {
      $(id)?.addEventListener('change', persistHmGrid);
      $(id)?.addEventListener('blur', persistHmGrid);
    });
    document.querySelectorAll('#hmBandChips .chip[data-hm-band]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const band = btn.getAttribute('data-hm-band') === 'narrow' ? 'narrow' : 'wide';
        document.querySelectorAll('#hmBandChips .chip').forEach((b) => {
          b.classList.toggle('active', b.getAttribute('data-hm-band') === band);
        });
        saveSetting(LS.hmBand, band);
        applyHmSGridDefaultsForBand(band, { force: true });
        syncZHeatmapModeUi();
        zHeatmapCache = { key: '', cells: null, grid: null, inFlightKey: '' };
        zHeatmapJobId += 1;
        scheduleZHeatmapUpdate(null, { immediate: true });
      });
    });
    $('btnHmRecompute')?.addEventListener('click', () => {
      zHeatmapCache = { key: '', cells: null, grid: null, inFlightKey: '' };
      // Bump jobId so a stale in-flight run cannot overwrite the new result.
      zHeatmapJobId += 1;
      scheduleZHeatmapUpdate(null, { immediate: true });
    });

    const applyHmSlip = () => {
      if (hmStrategySyncLock) return;
      if (typeof setSimSlippageSpreadPts !== 'function') return;
      const v = setSimSlippageSpreadPts($('hmSlip')?.value ?? 0.12);
      if ($('hmSlip')) $('hmSlip').value = String(v);
      if ($('slipSel')) $('slipSel').value = String(v);
      saveSetting(LS.slippageSpreadPts, v);
      applyStrategyParamsChange();
    };
    $('hmSlip')?.addEventListener('change', applyHmSlip);
    $('hmSlip')?.addEventListener('blur', applyHmSlip);

    $('hmEntrySel')?.addEventListener('change', () => {
      if (hmStrategySyncLock) return;
      const v = $('hmEntrySel').value;
      populateThresholdSelect($('entrySel'), 0.5, v);
      saveSetting(LS.entry, v);
      applyStrategyParamsChange();
    });
    $('hmExitSel')?.addEventListener('change', () => {
      if (hmStrategySyncLock) return;
      const v = $('hmExitSel').value;
      populateThresholdSelect($('exitSel'), 0.3, v);
      saveSetting(LS.exit, v);
      applyStrategyParamsChange();
    });
    $('hmTpSel')?.addEventListener('change', () => {
      if (hmStrategySyncLock) return;
      if ($('tpSel')) $('tpSel').value = $('hmTpSel').value;
      saveSetting(LS.takeProfit, $('hmTpSel').value);
      applyStrategyParamsChange();
    });
    $('hmHoldNoTrendSel')?.addEventListener('change', () => {
      if (hmStrategySyncLock) return;
      if ($('holdNoTrendSel')) $('holdNoTrendSel').value = $('hmHoldNoTrendSel').value;
      saveSetting(LS.holdNoTrendDays, $('hmHoldNoTrendSel').value);
      applyStrategyParamsChange();
    });
    $('hmHoldLosingSel')?.addEventListener('change', () => {
      if (hmStrategySyncLock) return;
      if ($('holdLosingSel')) $('holdLosingSel').value = $('hmHoldLosingSel').value;
      saveSetting(LS.holdLosingDays, $('hmHoldLosingSel').value);
      applyStrategyParamsChange();
    });
    $('btnHmCopyLive')?.addEventListener('click', () => copyThresholdsFromLive());

    if (typeof RISK_EXIT_GROUPS !== 'undefined') {
      for (const g of RISK_EXIT_GROUPS) {
        const hmSel = $(`hm_${g.selId}`);
        if (!hmSel) continue;
        hmSel.addEventListener('change', () => {
          if (hmStrategySyncLock) return;
          const primary = $(g.selId);
          if (primary) primary.value = hmSel.value;
          saveSetting(g.lsKey, hmSel.value);
          riskCompareCache = { key: '', html: '' };
          applyStrategyParamsChange();
        });
      }
    }

    const applyHmNotional = () => {
      if (hmStrategySyncLock) return;
      const v = setSimNotionalRub($('hmNotionalSel')?.value ?? $('notionalSel')?.value);
      if ($('hmNotionalSel')) $('hmNotionalSel').value = String(v);
      if ($('notionalSel')) $('notionalSel').value = String(v);
      saveSetting(LS.notionalRub, v);
      applyStrategyParamsChange();
    };
    $('hmNotionalSel')?.addEventListener('change', applyHmNotional);
    $('hmNotionalSel')?.addEventListener('blur', applyHmNotional);

    $('hmCompoundChk')?.addEventListener('change', () => {
      if (hmStrategySyncLock) return;
      const on = !!$('hmCompoundChk').checked;
      if ($('compoundChk')) $('compoundChk').checked = on;
      setSimCompound(on);
      saveSetting(LS.compound, on ? '1' : '');
      applyStrategyParamsChange();
    });

    syncHmStrategyControlsFromToolbar();

    $('tradesZHeatmap')?.addEventListener('click', (e) => {
      const cell = e.target.closest('td.zh-cell[data-entry][data-exit]');
      if (!cell || !$('tradesZHeatmap').contains(cell)) return;
      const entry = cell.dataset.entry;
      const exit = cell.dataset.exit;
      if (entry == null || exit == null) return;
      if (isTipSpreadLevelsHeatmap()) {
        const band = hmBand();
        if (band === 'narrow') {
          writeHmSelectedSpreadLevels({
            enter_narrow: Number(entry),
            exit_narrow: Number(exit),
          });
        } else {
          writeHmSelectedSpreadLevels({
            enter_wide: Number(entry),
            exit_wide: Number(exit),
          });
        }
        zHeatmapCache = { key: '', cells: null, grid: null, inFlightKey: '' };
        if (isTip1mMode()) {
          tipSimCache = { key: '', rows: null, meta: null, summary: null };
    clearTipManualOverrides();
          tipSimJobId += 1;
          runTip1mSim().catch(() => {});
        } else {
          applyStrategyParamsChange();
        }
        scheduleZHeatmapUpdate(null, { immediate: true });
        return;
      }
      populateThresholdSelect($('entrySel'), 0.5, entry);
      populateThresholdSelect($('exitSel'), 0.3, exit);
      saveSetting(LS.entry, entry);
      saveSetting(LS.exit, exit);
      applyStrategyParamsChange();
    });

    $('startDate').addEventListener('change', async () => {
      await applyWindowAndReload($('startDate').value, readWindowEndYmd());
    });

    $('endDate')?.addEventListener('change', async () => {
      const start = $('startDate')?.value;
      if (!start) return;
      await applyWindowAndReload(start, readWindowEndYmd());
    });

    document.querySelectorAll('#startPresetChips .chip[data-start-preset]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const ymd = startDateForPreset(btn.dataset.startPreset);
        // Пресет: начало по интервалу, конец = сегодня (МСК) — как раньше «→ сейчас».
        applyWindowAndReload(ymd, mskTodayYmd()).catch(() => {});
      });
    });

    $('csvSel').addEventListener('change', async () => {
      saveSetting(LS.csv, $('csvSel').value);
      $('loading').classList.remove('hidden');
      $('app').classList.add('hidden');
      scrollRestored = false;
      summaryScrollRestored = false;
      riskCompareScrollRestored = false;
      monthlyPnlScrollRestored = false;
      zHeatmapScrollRestored = false;
      await bootstrap($('csvSel').value);
    });

    $('btnRefreshMoex')?.addEventListener('click', () => {
      refreshMoexForTesting().catch(() => {});
    });

    $('btnBaseMode')?.addEventListener('click', () => {
      const next = !isBaseMode();
      setBaseMode(next);
      applyStrategyParamsChange();
      const st = $('status');
      if (st) {
        st.textContent = next
          ? 'база · пересчёт сделок…'
          : 'база выкл · только усилители · пересчёт…';
      }
    });

    $('btnAddon27')?.addEventListener('click', () => {
      const next = !isAddon27Mode();
      setAddon27Mode(next);
      applyStrategyParamsChange();
    });

    $('btnExtra19')?.addEventListener('click', () => {
      const next = !isExtremeAddonMode();
      setExtremeAddonMode(next);
      applyStrategyParamsChange();
      if (next) {
        const st = $('status');
        if (st) st.textContent = 'экстра 1/9 · пересчёт сделок…';
      }
    });

    $('btnWeekendTrading')?.addEventListener('click', () => {
      const next = !isWeekendTradingMode();
      setWeekendTradingMode(next);
      applyStrategyParamsChange();
      if (next) {
        const st = $('status');
        if (st) st.textContent = 'выходные 10:00–18:59 МСК · пересчёт сделок…';
      }
    });

    $('btnAdaptCorridor')?.addEventListener('click', () => {
      if (!TEST_DRAW_CORRIDOR) return;
      const next = !isAdaptCorridorMode();
      setAdaptCorridorMode(next);
      // Сразу пересчёт сделок липкой полки −0.25…1 (касание 1м всегда вкл.).
      applyStrategyParamsChange();
      if (next) {
        const st = $('status');
        if (st) {
          st.textContent = 'адапт. коридор+ · база + полка −0,25…1 · пересчёт…';
        }
      }
    });

    $('btnShelfFloorCeil')?.addEventListener('click', () => {
      const next = !isShelfFloorCeilingMode();
      setShelfFloorCeilingMode(next);
      applyStrategyParamsChange();
      if (next) {
        const st = $('status');
        if (st) st.textContent = 'пол–потолок (полка) · пересчёт сделок…';
      }
    });

    $('btnShowCascade')?.addEventListener('click', () => {
      setShowCascade(!isShowCascadeEnabled());
    });

    $('btnShowZoneBands')?.addEventListener('click', () => {
      setShowZoneBands(!isShowZoneBandsEnabled());
    });

    $('btnShowWideShelves')?.addEventListener('click', () => {
      setShowWideShelves(!isShowWideShelvesEnabled());
    });

    document.querySelectorAll('#periodChips .chip[data-days]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const days = parseInt(btn.dataset.days, 10);
        setVisibleDaysPeriod(days, { fitFull: days >= 400 });
        chartFocusIndex = null;
        // Масштаб только для графика: при скрытом графике не грузим tip1m и не перерисовываем.
        if (isChartsHidden()) {
          pendingChartRepaint = true;
          return;
        }
        if (days >= 400 && isTip1mMode() && tip1mChartNeedsReload()) {
          const st = $('status');
          if (st) st.textContent = 'касание 1м · гружу окно графика…';
          activateTip1mChart().catch(() => {}).then(() => {
            fitLoadedChartRange();
          });
          return;
        }
        refreshUi();
      });
    });

    $('btnFitFullChart')?.addEventListener('click', (ev) => {
      ev.preventDefault();
      ev.stopPropagation();
      showFullSimulationChart();
    });

    $('btnColumns').addEventListener('click', () => {
      $('columnPicker').classList.toggle('hidden');
    });

    document.querySelectorAll('#tradesRiskFilters .risk-filter').forEach((btn) => {
      btn.addEventListener('click', () => {
        const next = btn.dataset.riskFilter || 'all';
        const allowed = ['all', 'no-red', 'only-red', 'hit1', 'hit2', 'hit3'];
        riskFilter = allowed.includes(next) ? next : 'all';
        saveSetting(LS.riskFilter, riskFilter);
        if (next === 'all') {
          srcFilter = 'all';
          saveSetting(LS.srcFilter, srcFilter);
        }
        refreshTradesTable();
      });
    });

    document.querySelectorAll('#tradesRiskFilters .src-filter').forEach((btn) => {
      btn.addEventListener('click', () => {
        const next = btn.dataset.srcFilter || 'all';
        const allowed = ['all', 'base', 'addon', 'extra', 'shelf', 'manual'];
        srcFilter = allowed.includes(next) ? next : 'all';
        saveSetting(LS.srcFilter, srcFilter);
        refreshTradesTable();
      });
    });

    const tradesBody = $('tradesBody');
    if (tradesBody) {
      tradesBody.addEventListener('pointerover', (e) => {
        const cell = e.target.closest('td.metric-cell, td.risk-tip-cell');
        if (!cell || !tradesBody.contains(cell)) return;
        if (tradeMetricTipHideTimer) {
          clearTimeout(tradeMetricTipHideTimer);
          tradeMetricTipHideTimer = null;
        }
        showTradeMetricTip(cell);
      });
      tradesBody.addEventListener('pointerout', (e) => {
        const cell = e.target.closest('td.metric-cell, td.risk-tip-cell');
        if (!cell || !tradesBody.contains(cell)) return;
        const related = e.relatedTarget;
        if (related && cell.contains(related)) return;
        tradeMetricTipHideTimer = setTimeout(hideTradeMetricTip, 80);
      });
    }
    const statusBar = $('status');
    if (statusBar) {
      statusBar.addEventListener('pointerover', (e) => {
        const cell = e.target.closest('.metric-hover');
        if (!cell || !statusBar.contains(cell)) return;
        if (tradeMetricTipHideTimer) {
          clearTimeout(tradeMetricTipHideTimer);
          tradeMetricTipHideTimer = null;
        }
        showStatusMetricTip(cell);
      });
      statusBar.addEventListener('pointerout', (e) => {
        const cell = e.target.closest('.metric-hover');
        if (!cell || !statusBar.contains(cell)) return;
        const related = e.relatedTarget;
        if (related && (cell.contains(related) || related.closest?.('#tradeMetricTip'))) return;
        tradeMetricTipHideTimer = setTimeout(hideTradeMetricTip, 80);
      });
    }
    $('tradesScroll')?.addEventListener('scroll', () => {
      hideTradeMetricTip();
    }, { passive: true });
    window.addEventListener('blur', hideTradeMetricTip);

    let tradesScrollSaveTimer = null;
    $('tradesScroll').addEventListener('scroll', () => {
      if (suppressScrollSave || !scrollRestored) return;
      if (tradesScrollSaveTimer) clearTimeout(tradesScrollSaveTimer);
      tradesScrollSaveTimer = setTimeout(() => {
        tradesScrollSaveTimer = null;
        if (suppressScrollSave) return;
        const el = $('tradesScroll');
        if (!el) return;
        saveSetting(LS.tradesScrollLeft, el.scrollLeft);
        saveSetting(LS.tradesScrollTop, el.scrollTop);
      }, 80);
    });

    let summaryScrollSaveTimer = null;
    $('tradesSummaryScroll').addEventListener('scroll', () => {
      if (suppressScrollSave || !summaryScrollRestored) return;
      if (summaryScrollSaveTimer) clearTimeout(summaryScrollSaveTimer);
      summaryScrollSaveTimer = setTimeout(() => {
        summaryScrollSaveTimer = null;
        if (suppressScrollSave) return;
        const el = $('tradesSummaryScroll');
        if (!el) return;
        saveSetting(LS.tradesSummaryScrollTop, el.scrollTop);
      }, 80);
    });

    let riskCompareScrollSaveTimer = null;
    const riskScroll = $('riskCompareScroll');
    if (riskScroll) {
      riskScroll.addEventListener('scroll', () => {
        if (suppressScrollSave || !riskCompareScrollRestored) return;
        if (riskCompareScrollSaveTimer) clearTimeout(riskCompareScrollSaveTimer);
        riskCompareScrollSaveTimer = setTimeout(() => {
          riskCompareScrollSaveTimer = null;
          if (suppressScrollSave) return;
          const el = $('riskCompareScroll');
          if (!el) return;
          saveSetting(LS.riskCompareScrollTop, el.scrollTop);
        }, 80);
      });
    }

    let monthlyPnlScrollSaveTimer = null;
    const monthlyScroll = $('tradesMonthlyPnlScroll');
    if (monthlyScroll) {
      monthlyScroll.addEventListener('scroll', () => {
        if (suppressScrollSave || !monthlyPnlScrollRestored) return;
        if (monthlyPnlScrollSaveTimer) clearTimeout(monthlyPnlScrollSaveTimer);
        monthlyPnlScrollSaveTimer = setTimeout(() => {
          monthlyPnlScrollSaveTimer = null;
          if (suppressScrollSave) return;
          const el = $('tradesMonthlyPnlScroll');
          if (!el) return;
          saveSetting(LS.monthlyPnlScrollTop, el.scrollTop);
        }, 80);
      });
    }

    let zHeatmapScrollSaveTimer = null;
    const heatmapScroll = $('tradesZHeatmap');
    if (heatmapScroll) {
      heatmapScroll.addEventListener('scroll', () => {
        if (suppressScrollSave || !zHeatmapScrollRestored) return;
        if (zHeatmapScrollSaveTimer) clearTimeout(zHeatmapScrollSaveTimer);
        zHeatmapScrollSaveTimer = setTimeout(() => {
          zHeatmapScrollSaveTimer = null;
          if (suppressScrollSave) return;
          const el = $('tradesZHeatmap');
          if (!el) return;
          saveSetting(LS.zHeatmapScrollTop, el.scrollTop);
        }, 80);
      });
    }

    const speedContainer = $('speedChips');
    BAR_REPLAY_SPEEDS.forEach((s) => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'chip' + (s === 1 ? ' active' : '');
      btn.textContent = `${s}×`;
      btn.addEventListener('click', () => {
        speed = s;
        speedContainer.querySelectorAll('.chip').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        if (playing) startTimer();
      });
      speedContainer.appendChild(btn);
    });
  }

  function startReplayUi() {
    loadSettings();
    migrateHmSpreadEnterWide61();
    forceFinalBarsSourceOnce();
    localStorage.setItem(LS.edgeMode, 'tip1m');
    syncEdgeModeChips();
    syncStartPresetChips();
    syncZHeatmapModeUi();
    if (isTipSpreadLevelsHeatmap()) applyHmSGridDefaultsForBand(hmBand());
    // Коридор на Тесте пока не рисуем — desk не дергаем.
    if (TEST_DRAW_CORRIDOR) {
      ensureTestDeskCorridor({ force: false }).then(() => {
        try {
          const view = (window.MoexLive && MoexLive.getSavedView && MoexLive.getSavedView()) || 'trade';
          if (view === 'replay' && engine && chart) refreshUi({ light: true });
        } catch (_) { /* ignore */ }
      });
    }
    renderColumnPicker();
    bindSplitDivider();
    bindChartVerticalSplit();
    bindTradesPanelVerticalSplits();
    bindPaneCollapse();
    bindPnlModeToggle();
    bindControls();
    window.__moexReplayResize = () => {
      reapplyLayoutFromStorage();
      chart?.resize();
      restoreTradesSummaryScrollAfterVisible();
    };
    const savedView = (window.MoexLive && MoexLive.getSavedView()) || 'trade';
    // Не держим чёрный экран на /api/bars: оболочка сразу, бары догружаются.
    $('loading').classList.add('hidden');
    $('app').classList.remove('hidden');
    if (window.MoexLive) MoexLive.setViewMode(savedView);
    bootstrap($('csvSel').value, { background: true });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', startReplayUi);
  } else {
    startReplayUi();
  }
})();
