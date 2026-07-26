/** MOEX Bar Replay — UI + TradingView chart + localStorage */
(function () {
  const LS = {
    startDate: 'moexReplay.startDate',
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
    hmEntryMin: 'moexReplay.hmEntryMin',
    hmEntryMax: 'moexReplay.hmEntryMax',
    hmExitMin: 'moexReplay.hmExitMin',
    hmStep: 'moexReplay.hmStep',
    pnlMode: 'moexReplay.pnlMode',
    notionalRub: 'moexReplay.notionalRub',
    slippageSpreadPts: 'moexReplay.slippageSpreadPts',
    tradeSortCol: 'moexReplay.tradeSortCol',
    tradeSortDir: 'moexReplay.tradeSortDir',
    riskFilter: 'moexReplay.riskFilter',
    takeProfit: 'moexReplay.takeProfit',
    riskExit: 'moexReplay.riskExit', // legacy single preset
    compound: 'moexReplay.compound',
    columnTips: 'moexReplay.columnTips',
    barsSource: 'moexReplay.barsSource',
    edgeMode: 'moexReplay.edgeMode',
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

  let allPoints = [];
  /** Meta from last /api/bars (as_live coverage). */
  let lastBarsMeta = { as_live: true, locked_count: 0, bars_count: 0, coverage: 0, locked_from: null, locked_to: null };
  /** Полная серия по CSV (для быстрых пресетов start без повторного /api/bars). */
  const barsCacheByCsv = Object.create(null);
  let engine = null;
  let chart = null;
  let playing = false;
  let speed = 1;
  let visibleDays = 30;
  /** После смены параметров / «Всё» — подогнать график под весь период симуляции. */
  let pendingFitFullChart = false;
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
  /** Reuse typed arrays across heatmap recalcs (same bars / endIdx). */
  let heatmapSeriesCache = { endIdx: -1, len: 0, series: null };
  let zHeatmapTimer = null;
  /** Server tip-1m sim (Mode B) for Testing «касание 1м». */
  let tipSimCache = { key: '', rows: null, meta: null, summary: null };
  let tipSimJobId = 0;
  let tipSimTimer = null;
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
  let pnlMode = 'total';
  let tradeSortColumn = 'Index';
  let tradeSortDir = 'asc';
  /** all | no-red | only-red */
  let riskFilter = 'all';
  /** Hover histogram tips on metric columns (Длит., Чист., Min…). Default ON. */
  let columnTipsEnabled = true;
  /** Last max drawdown from trades summary — same metric as «Макс. просадка». */
  let lastMaxDd = 0;
  let lastMaxDdPct = 0;
  /** Индекс бара для расширения окна графика (двойной клик по сделке). Не двигает курсор replay назад. */
  let chartFocusIndex = null;

  const $ = (id) => document.getElementById(id);

  function loadSettings() {
    const entry = localStorage.getItem(LS.entry);
    const exit = localStorage.getItem(LS.exit);
    const period = localStorage.getItem(LS.period);
    const csv = localStorage.getItem(LS.csv);
    const startDate = localStorage.getItem(LS.startDate);
    const cols = localStorage.getItem(LS.tradeColumns);

    if (entry) populateThresholdSelect($('entrySel'), 0.5, entry);
    else populateThresholdSelect($('entrySel'), 0.5, '0.7');
    if (exit) populateThresholdSelect($('exitSel'), 0.3, exit);
    else populateThresholdSelect($('exitSel'), 0.3, '0.5');
    if (csv) $('csvSel').value = csv;
    if (startDate) $('startDate').value = startDate;
    if (period) {
      visibleDays = parseInt(period, 10) || 30;
      document.querySelectorAll('#periodChips .chip').forEach((b) => {
        b.classList.toggle('active', parseInt(b.dataset.days, 10) === visibleDays);
      });
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
    const hmStep = localStorage.getItem(LS.hmStep);
    if (hmEntryMin && $('hmEntryMin')) $('hmEntryMin').value = hmEntryMin;
    if (hmEntryMax && $('hmEntryMax')) $('hmEntryMax').value = hmEntryMax;
    if (hmExitMin && $('hmExitMin')) $('hmExitMin').value = hmExitMin;
    if (hmStep && $('hmStep')) $('hmStep').value = hmStep;
    const sortCol = localStorage.getItem(LS.tradeSortCol);
    const sortDir = localStorage.getItem(LS.tradeSortDir);
    if (sortCol && TRADE_COLUMN_KEYS.includes(sortCol)) tradeSortColumn = sortCol;
    if (sortDir === 'asc' || sortDir === 'desc') tradeSortDir = sortDir;
    const rf = localStorage.getItem(LS.riskFilter);
    if (rf === 'all' || rf === 'no-red' || rf === 'only-red'
      || rf === 'hit1' || rf === 'hit2' || rf === 'hit3') {
      riskFilter = rf;
    }
    const tp = localStorage.getItem(LS.takeProfit);
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
      out[g.id] = sel?.value || 'off';
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
    const eng = new BarReplayEngine(allPoints, entry, exit, computeMinCursor(), {});
    eng.seekTo(idx);
    const rows = buildTradeRows(eng.edges, entry, allPoints, idx);
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
    const heatmapH = isZHeatmapHidden()
      ? 0
      : readCssPxVar(summaryEl, '--z-heatmap-height', loadZHeatmapHeight());
    const used = (riskHead?.offsetHeight || 0)
      + (summaryHead?.offsetHeight || 0) + metricsMin
      + (isTradesSummaryHidden() ? 0 : CHART_SPLITTER_HEIGHT)
      + (monthlyHead?.offsetHeight || 0) + monthlyH
      + (isMonthlyPnlHidden() ? 0 : CHART_SPLITTER_HEIGHT)
      + (heatmapHead?.offsetHeight || 0) + heatmapH
      + (isZHeatmapHidden() ? 0 : CHART_SPLITTER_HEIGHT)
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
    const heatmapH = isZHeatmapHidden()
      ? 0
      : readCssPxVar(summaryEl, '--z-heatmap-height', loadZHeatmapHeight());
    const used = (filters?.offsetHeight || 0)
      + riskH + CHART_SPLITTER_HEIGHT
      + metricsMin + CHART_SPLITTER_HEIGHT
      + (isZHeatmapHidden() ? 0 : CHART_SPLITTER_HEIGHT + (heatmapHead?.offsetHeight || 0) + heatmapH)
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
    return saved === 'trade' ? 'trade' : 'total';
  }

  function formatPnlDrawdownLabel(maxDd = lastMaxDd, maxDdPct = lastMaxDdPct) {
    if (!(maxDd > 0)) return 'Просадка 0% (0 ₽)';
    return `Просадка −${maxDdPct.toFixed(1)}% (${formatRub(-maxDd)} ₽)`;
  }

  function updatePnlLabel(maxDd = lastMaxDd, maxDdPct = lastMaxDdPct) {
    const label = $('pnlLabel');
    if (!label) return;
    const base = pnlMode === 'trade'
      ? 'PnL сделки · % от пика капитала'
      : 'PnL общий · % от пика капитала';
    label.textContent = `${base} · ${formatPnlDrawdownLabel(maxDd, maxDdPct)}`;
  }

  function updatePnlModeChips() {
    document.querySelectorAll('#pnlModeChips .chip').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.pnlMode === pnlMode);
    });
    updatePnlLabel(lastMaxDd);
  }

  function bindPnlModeToggle() {
    pnlMode = loadPnlMode();
    updatePnlModeChips();
    document.querySelectorAll('#pnlModeChips .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        pnlMode = btn.dataset.pnlMode === 'trade' ? 'trade' : 'total';
        saveSetting(LS.pnlMode, pnlMode);
        updatePnlModeChips();
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

    syncPaneCollapseUi();
  }

  function isZHeatmapFullscreen() {
    return !!$('zHeatmapPane')?.classList.contains('is-fullscreen');
  }

  function setZHeatmapFullscreen(on) {
    const pane = $('zHeatmapPane');
    const btn = $('btnExpandZHeatmap');
    if (!pane) return;
    const next = !!on;
    if (next && isZHeatmapHidden()) {
      setPaneCollapsed('zHeatmapPane', false);
      saveSetting(LS.zHeatmapHidden, '');
      syncPaneCollapseUi();
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

  function barTimestampMs(p) {
    const ms = p?.timestampMs;
    if (ms != null && Number.isFinite(Number(ms))) return Number(ms);
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

  function applyPointsLocally(points, { csv, sourceLabel } = {}) {
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
    refreshUi();
    setTimeout(() => chart?.resize(), 100);
    reapplyLayoutFromStorage();
    requestAnimationFrame(() => {
      reapplyLayoutFromStorage();
      chart?.resize();
    });
  }

  async function applyStartDateAndReload(ymd) {
    if (!ymd || !$('startDate')) return;
    $('startDate').value = ymd;
    saveSetting(LS.startDate, ymd);
    document.querySelectorAll('#startPresetChips .chip[data-start-preset]').forEach((b) => {
      b.classList.toggle('active', startDateForPreset(b.dataset.startPreset) === ymd);
    });
    scrollRestored = false;
    summaryScrollRestored = false;
    riskCompareScrollRestored = false;
    monthlyPnlScrollRestored = false;
    zHeatmapScrollRestored = false;

    const csv = $('csvSel')?.value || 'm15_tatn_255d.csv';
    // Tip1m chart cache is start-dependent — drop stash so M15 reload is clean.
    tip1mBarsCacheKey = '';
    tip1mChartMeta = null;
    m15PointsStash = null;
    const cache = barsCacheByCsv[csv];
    if (barsCacheCoversStart(cache, ymd)) {
      const sliced = sliceBarsFromStart(cache, ymd);
      applyPointsLocally(sliced, { csv, sourceLabel: 'cache' });
      if (isTip1mMode()) {
        try {
          await activateTip1mChart();
        } catch (e) {
          console.error(e);
        }
        scheduleTipSimFetch({ immediate: true });
      }
      return;
    }

    $('loading').classList.remove('hidden');
    $('app').classList.add('hidden');
    await bootstrap(csv);
  }

  function syncStartPresetChips() {
    const ymd = $('startDate')?.value;
    document.querySelectorAll('#startPresetChips .chip[data-start-preset]').forEach((b) => {
      b.classList.toggle('active', !!ymd && startDateForPreset(b.dataset.startPreset) === ymd);
    });
  }

  function setVisibleDaysPeriod(days, { fitFull = false } = {}) {
    visibleDays = days;
    saveSetting(LS.period, visibleDays);
    document.querySelectorAll('#periodChips .chip').forEach((b) => {
      b.classList.toggle('active', parseInt(b.dataset.days, 10) === visibleDays);
    });
    if (fitFull || visibleDays >= 400) pendingFitFullChart = true;
  }

  /** Показать весь период симуляции на графике (данные + zoom). */
  function showFullSimulationChart() {
    setVisibleDaysPeriod(400, { fitFull: true });
    chartFocusIndex = null;
    if (chart && typeof chart.followReplayEdge === 'function') {
      chart.followReplayEdge(false);
    }
    refreshUi();
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
    if (!engine || !chart || !row) return;
    const seek = !!opts.seek;
    if (seek) pausePlayback();

    const entryIdx = findIndexByTradeDate(row.entryDate);
    if (entryIdx < 0) return;
    // Закрытая: exitDate; открытая: текущий бар курсора
    const exitIdx = row.exitDate && row.exitDate !== '—'
      ? findIndexByTradeDate(row.exitDate)
      : engine.cursor;
    const endIdx = exitIdx >= 0 ? exitIdx : entryIdx;
    const mid = Math.round((entryIdx + endIdx) / 2);
    // Расширить slice данных, чтобы entry/exit попали в lastCandleTimes
    chartFocusIndex = mid;

    // Курсор только вперёд при dblclick — чтобы сделка уже была в edges; назад не откатываем
    if (seek && engine.cursor < endIdx) {
      engine.seekTo(endIdx);
    }

    selectedTradeId = tradeBadgeForRow(row);
    chart.followReplayEdge(false);
    refreshUi();
    chart.selectTrade(selectedTradeId);
    chart.centerOnTrade(selectedTradeId);
    requestAnimationFrame(() => chart.centerOnTrade(selectedTradeId));
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
      `<div class="ot-z">${overlay.zLine}</div>`,
      `<div class="ot-trade">${overlay.tradePrefix} <span class="ot-pnl ${pnlClass}">${overlay.netText}</span></div>`,
      `<div class="ot-spread">${overlay.spreadLine}</div>`,
      `<div class="ot-duration">${overlay.duration}</div>`,
    ].join('');
  }

  function filterRowsByRisk(rows) {
    if (riskFilter === 'no-red') return rows.filter((r) => !r.riskRed);
    if (riskFilter === 'only-red') return rows.filter((r) => r.riskRed);
    if (riskFilter === 'hit1') return rows.filter((r) => r.hitPnl1 || r.hit1Ms != null);
    if (riskFilter === 'hit2') return rows.filter((r) => r.hitPnl2 || r.hit2Ms != null);
    if (riskFilter === 'hit3') return rows.filter((r) => r.hitPnl3 || r.hit3Ms != null);
    return rows;
  }

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
    if (!scrollEl) return;
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

  function readZHeatmapGridParams() {
    const entryMin = roundZ(parseFloat($('hmEntryMin')?.value ?? '0.5'));
    const entryMax = roundZ(parseFloat($('hmEntryMax')?.value ?? '2.5'));
    const exitMin = roundZ(parseFloat($('hmExitMin')?.value ?? '0.3'));
    let step = roundZ(parseFloat($('hmStep')?.value ?? '0.1'));
    if (!Number.isFinite(step) || step < 0.05) step = 0.1;
    if (step > 0.5) step = 0.5;
    const lo = Number.isFinite(entryMin) ? Math.max(0.3, entryMin) : 0.5;
    const hi = Number.isFinite(entryMax) ? Math.min(5, Math.max(lo, entryMax)) : 2.5;
    const xLo = Number.isFinite(exitMin) ? Math.max(0.1, exitMin) : 0.3;
    return { entryMin: lo, entryMax: hi, exitMin: xLo, step };
  }

  function buildZHeatmapAxes(grid) {
    const entries = [];
    for (let e = grid.entryMin; e <= grid.entryMax + 1e-9; e = roundZ(e + grid.step)) {
      entries.push(roundZ(e));
    }
    const exits = [];
    for (let x = grid.exitMin; x <= grid.entryMax - grid.step + 1e-9; x = roundZ(x + grid.step)) {
      exits.push(roundZ(x));
    }
    const pairs = [];
    for (const entry of entries) {
      for (const exit of exits) {
        if (exit < entry - 1e-9) pairs.push({ entry, exit });
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
    return [
      getEdgeMode(),
      grid.entryMin, grid.entryMax, grid.exitMin, grid.step,
      getSimNotionalRub(), getSimCompound() ? 1 : 0, slip,
      t.takeProfitPct || 0,
      isTip1mMode() ? 'risk-ignored' : riskExitSelectionKey(),
      computeMinCursor(), zHeatmapSimEndIndex(), allPoints.length,
      $('csvSel')?.value || '',
      $('startDate')?.value || '',
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

  function setZHeatmapExtremes(cells) {
    const el = $('zHeatmapExtremes');
    if (!el) return;
    const { min, max } = findZHeatmapExtremes(cells);
    if (!min || !max) {
      el.innerHTML = '';
      return;
    }
    const fmtPair = (c) => `±${Number(c.entry).toFixed(1)} / ±${Number(c.exit).toFixed(1)}`;
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

  function renderZHeatmapHtml(cells, grid, curEntry, curExit) {
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
    const head = exits.map((x) => `<th title="выход ±${x}">${x.toFixed(1)}</th>`).join('');
    const body = entries.map((entry) => {
      const cellsHtml = exits.map((exit) => {
        if (exit >= entry - 1e-9) {
          return '<td class="zh-na">·</td>';
        }
        const c = map.get(`${entry}|${exit}`);
        const pnl = c?.pnl ?? 0;
        const n = c?.n ?? 0;
        const isCur = Math.abs(entry - curEntry) < 1e-6 && Math.abs(exit - curExit) < 1e-6;
        const isMax = extMax && Math.abs(entry - extMax.entry) < 1e-6 && Math.abs(exit - extMax.exit) < 1e-6;
        const isMin = extMin && Math.abs(entry - extMin.entry) < 1e-6 && Math.abs(exit - extMin.exit) < 1e-6;
        const title = `вход ±${entry} · выход ±${exit} · PnL ${formatRub(pnl)} · N=${n}`
          + (isMax ? ' · МАКС' : '')
          + (isMin ? ' · МИН' : '')
          + (isCur ? ' · текущие пороги' : ' · клик — применить');
        const cls = [
          'zh-cell',
          isCur ? 'is-current' : '',
          isMax ? 'is-hm-max' : '',
          isMin ? 'is-hm-min' : '',
        ].filter(Boolean).join(' ');
        return (
          `<td class="${cls}"`
          + ` data-entry="${entry}" data-exit="${exit}"`
          + ` style="background:${zHeatmapColor(pnl, pnlMin, pnlMax)}"`
          + ` title="${title}">${formatZHeatmapCell(pnl)}</td>`
        );
      }).join('');
      return `<tr><th class="zh-y" title="вход ±${entry}">${entry.toFixed(1)}</th>${cellsHtml}</tr>`;
    }).join('');
    return (
      `<table class="z-heatmap-table">`
      + `<thead><tr><th class="zh-corner" title="строки = вход Z, столбцы = выход Z">в\\вых</th>${head}</tr></thead>`
      + `<tbody>${body}</tbody></table>`
    );
  }

  function setZHeatmapStatus(text) {
    const el = $('zHeatmapStatus');
    if (el) el.textContent = text || '';
  }

  function scheduleZHeatmapUpdate(_cursorIndexIgnored, { immediate = false } = {}) {
    if (zHeatmapTimer) clearTimeout(zHeatmapTimer);
    const delay = immediate ? 0 : 320;
    zHeatmapTimer = setTimeout(() => {
      zHeatmapTimer = null;
      updateZHeatmap();
    }, delay);
  }

  async function updateZHeatmap() {
    const host = $('tradesZHeatmap');
    const scrollEl = $('tradesZHeatmap');
    if (!host) return;
    const prevScrollTop = scrollEl ? scrollEl.scrollTop : 0;
    if (isZHeatmapHidden()) return;
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
    if (key === zHeatmapCache.key && zHeatmapCache.cells) {
      host.innerHTML = renderZHeatmapHtml(zHeatmapCache.cells, grid, cur.entry, cur.exit);
      applyZHeatmapScrollTop(zHeatmapScrollRestored ? prevScrollTop : readSavedZHeatmapScrollTop());
      setZHeatmapExtremes(zHeatmapCache.cells);
      const slip = typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0;
      setZHeatmapStatus(
        `${zHeatmapCache.cells.length} клеток · ${zHeatmapCsvPeriodLabel()} · весь ряд · slip ${slip}`
        + ` · кап. ${getSimNotionalRub()}₽ · ${barsSourceLabel()}`,
      );
      return;
    }
    if (playing && zHeatmapCache.cells) {
      host.innerHTML = renderZHeatmapHtml(zHeatmapCache.cells, zHeatmapCache.grid || grid, cur.entry, cur.exit);
      applyZHeatmapScrollTop(zHeatmapScrollRestored ? prevScrollTop : readSavedZHeatmapScrollTop());
      setZHeatmapExtremes(zHeatmapCache.cells);
      return;
    }
    // Same key already computing — don't cancel/restart (refreshUi would otherwise leave zeros).
    if (key === zHeatmapCache.inFlightKey) return;

    const axes = buildZHeatmapAxes(grid);
    if (!axes.pairs.length) {
      host.innerHTML = '<div class="z-heatmap-empty">Пустая сетка (нужно exit &lt; entry)</div>';
      setZHeatmapStatus('');
      setZHeatmapExtremes(null);
      return;
    }

    const jobId = ++zHeatmapJobId;
    zHeatmapCache = { ...zHeatmapCache, inFlightKey: key };

    if (isTip1mMode()) {
      setZHeatmapStatus(`Считаю heatmap касания 1м на сервере (${axes.pairs.length} клеток)…`);
      try {
        const t0 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
        const data = await fetchTipHeatmap(grid);
        if (jobId !== zHeatmapJobId) return;
        const cells = (data.cells || []).map((c) => ({
          entry: c.entry,
          exit: c.exit,
          pnl: c.pnl,
          n: c.n,
        }));
        zHeatmapCache = { key, cells, grid, inFlightKey: '' };
        host.innerHTML = renderZHeatmapHtml(cells, grid, cur.entry, cur.exit);
        applyZHeatmapScrollTop(zHeatmapScrollRestored ? prevScrollTop : readSavedZHeatmapScrollTop());
        setZHeatmapExtremes(cells);
        const slip = typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0;
        const t1 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
        const sec = Math.max(0.01, (t1 - t0) / 1000);
        const hmSec = data.meta?.heatmapSec != null ? data.meta.heatmapSec : sec.toFixed(1);
        setZHeatmapStatus(
          `${cells.length} клеток · касание 1м · сервер ${hmSec}с`
          + ` · ${zHeatmapCsvPeriodLabel()} · slip ${slip}`
          + ` · кап. ${getSimNotionalRub()}₽ · клик = пороги теста`,
        );
      } catch (e) {
        if (jobId !== zHeatmapJobId) return;
        zHeatmapCache = { ...zHeatmapCache, inFlightKey: '' };
        const msg = (e && e.message) ? String(e.message).slice(0, 160) : String(e);
        host.innerHTML = `<div class="z-heatmap-empty">Heatmap 1м: ${msg}</div>`;
        setZHeatmapStatus('heatmap касания 1м — ошибка сервера');
        setZHeatmapExtremes(null);
      }
      return;
    }

    // Risk opts use end-of-data (same window as heatmap), not replay cursor.
    const simOpts = buildActiveSimOpts(endIdx);
    const useFast = typeof prepareHeatmapSeries === 'function' && typeof simOptsAreHeatmapFast === 'function'
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
      const sum = typeof heatmapCellNetPnl === 'function'
        ? heatmapCellNetPnl(allPoints, entry, exit, endIdx, simOpts, prepared)
        : (() => {
          const eng = new BarReplayEngine(allPoints, entry, exit, endIdx, simOpts);
          return sumClosedNetFromEdges(eng.edges);
        })();
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
    host.innerHTML = renderZHeatmapHtml(cells, grid, cur.entry, cur.exit);
    applyZHeatmapScrollTop(zHeatmapScrollRestored ? prevScrollTop : readSavedZHeatmapScrollTop());
    setZHeatmapExtremes(cells);
    const slip = typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0;
    const nMax = cells.reduce((m, c) => Math.max(m, c.n || 0), 0);
    const t1 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
    const sec = Math.max(0.01, (t1 - t0) / 1000);
    setZHeatmapStatus(
      `${cells.length} клеток · ${zHeatmapCsvPeriodLabel()} · весь ряд · ${sec < 1 ? sec.toFixed(2) : sec.toFixed(1)}с`
      + (prepared ? ` · быстро${tpOn ? '+TP' : ''}` : (slowWhy ? ` · медленно (${slowWhy})` : ' · медленно'))
      + ` · slip ${slip} · кап. ${getSimNotionalRub()}₽ · ${barsSourceLabel()}`
      + (nMax > 0 ? ' · клик по клетке = пороги теста' : ' · N=0 — проверьте дату старта / данные'),
    );
  }

  function resolveMergedSimOpts(rawOpts, moneyStopDynamic) {
    const raw = { ...rawOpts };
    if (raw.maxLossRubDynamic) {
      raw.maxLossRub = moneyStopDynamic;
      delete raw.maxLossRubDynamic;
    }
    return normalizeSimExitOpts(raw);
  }

  function simulateRiskOpts(optsRaw, cursorIndex, moneyStopDynamic, meta) {
    const { entry, exit } = thresholds();
    const eng = new BarReplayEngine(
      allPoints,
      entry,
      exit,
      computeMinCursor(),
      resolveMergedSimOpts(optsRaw, moneyStopDynamic),
    );
    eng.seekTo(cursorIndex);
    const rows = buildTradeRows(eng.edges, entry, allPoints, cursorIndex, { skipExtras: true });
    const summary = buildTradeSimSummary(rows, getSimNotionalRub());
    return { ...meta, summary };
  }

  /**
   * Сравнение: для каждой группы — варианты этой группы при фиксированных остальных.
   * База = все группы выкл.
   */
  function buildRiskCompareHtml(cursorIndex) {
    const current = selectedRiskExitGroups();
    const moneyStopDynamic = computeMoneyStopDynamic(cursorIndex);
    const baseline = simulateRiskOpts({}, cursorIndex, moneyStopDynamic, {
      id: 'baseline',
      label: 'база (всё выкл)',
      groupId: null,
    });
    const baselinePnl = baseline.summary.totalPnl;

    let sections = '';
    for (const g of RISK_EXIT_GROUPS) {
      const scenarios = g.options.map((opt) => {
        const sel = { ...current, [g.id]: opt.id };
        const allOff = Object.values(sel).every((v) => v === 'off');
        return simulateRiskOpts(
          mergeRiskExitGroupOpts(sel),
          cursorIndex,
          moneyStopDynamic,
          {
            id: `${g.id}:${opt.id}`,
            label: opt.short || opt.label,
            groupId: g.id,
            optionId: opt.id,
            isActive: (current[g.id] || 'off') === opt.id,
            isBaselineRow: allOff,
          },
        );
      });
      const bestPnl = Math.max(...scenarios.map((s) => s.summary.totalPnl));
      const bestDd = Math.min(...scenarios.map((s) => s.summary.maxDd));
      const rowsHtml = scenarios.map((s) => {
        const st = s.summary;
        const delta = st.totalPnl - baselinePnl;
        const active = s.isActive ? ' is-active' : '';
        const pnlBest = st.totalPnl === bestPnl && scenarios.length > 1 ? ' best' : '';
        const ddBest = st.maxDd === bestDd ? ' best' : '';
        const deltaCls = delta > 0 ? 'delta-pos' : (delta < 0 ? 'delta-neg' : '');
        const deltaText = s.isBaselineRow
          ? '—'
          : `${delta >= 0 ? '+' : ''}${formatRub(delta)}`;
        return (
          `<tr class="${active.trim()}" data-group="${g.id}" data-opt="${s.optionId}">`
          + `<td>${s.label}</td>`
          + `<td>${st.closedCount}</td>`
          + `<td class="${pnlClass(st.totalPnl)}${pnlBest}">${formatRub(st.totalPnl)}</td>`
          + `<td class="${deltaCls}">${deltaText}</td>`
          + `<td class="pnl-neg${ddBest}">${st.maxDd > 0 ? formatRub(-st.maxDd) : '0'}</td>`
          + `<td>${st.lossCount}</td>`
          + `</tr>`
        );
      }).join('');
      sections += (
        `<div class="tp-compare-title">${g.label}</div>`
        + `<table><thead><tr>`
        + `<th>опция</th><th>N</th><th>PnL</th><th>Δ к базе</th><th>maxDD</th><th>убыт</th>`
        + `</tr></thead><tbody>${rowsHtml}</tbody></table>`
      );
    }

    const dynHint = moneyStopDynamic > 0
      ? ` · M×3.5avg=${formatRub(moneyStopDynamic)}`
      : '';
    const cur = formatRiskExitSelectionShort(current);
    return (
      `<div class="tp-compare-title">Risk exit по группам (до курсора) · сейчас: ${cur}${dynHint}</div>`
      + sections
    );
  }

  function updateRiskCompare(cursorIndex) {
    const el = $('riskCompare');
    const scrollEl = $('riskCompareScroll');
    const prevScrollTop = scrollEl ? scrollEl.scrollTop : 0;
    if (!el || !allPoints.length || cursorIndex < 0) {
      if (el) el.innerHTML = '';
      return;
    }
    if (deferRiskCompareOnce) {
      deferRiskCompareOnce = false;
      el.innerHTML = '<div class="meta" style="padding:8px">Сравнение правил…</div>';
      if (riskCompareTimer) clearTimeout(riskCompareTimer);
      const idx = cursorIndex;
      riskCompareTimer = setTimeout(() => {
        riskCompareTimer = null;
        updateRiskCompare(idx);
      }, 50);
      return;
    }
    const t = thresholds();
    const key = [
      t.entry, t.exit, getSimNotionalRub(), getSimCompound() ? 1 : 0,
      riskExitSelectionKey(),
      computeMinCursor(), cursorIndex, allPoints.length,
    ].join('|');
    if (key === riskCompareCache.key && riskCompareCache.html) {
      el.innerHTML = riskCompareCache.html;
      const cur = selectedRiskExitGroups();
      el.querySelectorAll('tbody tr[data-group]').forEach((tr) => {
        const g = tr.dataset.group;
        const opt = tr.dataset.opt;
        tr.classList.toggle('is-active', (cur[g] || 'off') === opt);
      });
      applyRiskCompareScrollTop(riskCompareScrollRestored ? prevScrollTop : readSavedRiskCompareScrollTop());
      return;
    }
    if (playing && riskCompareCache.html) {
      el.innerHTML = riskCompareCache.html;
      applyRiskCompareScrollTop(riskCompareScrollRestored ? prevScrollTop : readSavedRiskCompareScrollTop());
      return;
    }
    const html = buildRiskCompareHtml(cursorIndex);
    riskCompareCache = { key, html };
    el.innerHTML = html;
    applyRiskCompareScrollTop(riskCompareScrollRestored ? prevScrollTop : readSavedRiskCompareScrollTop());
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
    updatePnlLabel(summary.maxDd, lastMaxDdPct);
    const hiddenRed = riskFilter === 'no-red'
      ? allSummary.redCount
      : 0;
    const items = [
      { label: 'Итого PnL', value: formatRub(summary.totalPnl), cls: pnlClass(summary.totalPnl), wide: true },
      { label: 'Доходность', value: formatCapitalPct(summary.retPct), cls: pnlClass(summary.retPct) },
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
    ];
    grid.innerHTML = items.map((it) => (
      `<div class="trades-summary-item${it.wide ? ' wide' : ''}">`
      + `<span class="ts-label">${it.label}</span>`
      + `<span class="ts-value ${it.cls || ''}">${it.value}</span>`
      + `</div>`
    )).join('');
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
    renderMonthlyPnl(visibleRows);
    if (engine) updateRiskCompare(engine.cursor);
    // Не перезапускать heatmap на каждый refreshUi, если ключ уже посчитан
    if (engine && !playing && !isZHeatmapHidden()) {
      const g = readZHeatmapGridParams();
      const hk = zHeatmapCacheKey(g);
      if (hk === zHeatmapCache.key && zHeatmapCache.cells) {
        const host = $('tradesZHeatmap');
        if (host && !host.querySelector('.z-heatmap-table')) {
          const cur = thresholds();
          host.innerHTML = renderZHeatmapHtml(zHeatmapCache.cells, g, cur.entry, cur.exit);
          setZHeatmapExtremes(zHeatmapCache.cells);
        } else if (host) {
          // только подсветка текущих порогов
          const cur = thresholds();
          host.querySelectorAll('td.zh-cell.is-current').forEach((el) => el.classList.remove('is-current'));
          const cell = host.querySelector(
            `td.zh-cell[data-entry="${cur.entry}"][data-exit="${cur.exit}"]`,
          );
          if (cell) cell.classList.add('is-current');
        }
      } else {
        scheduleZHeatmapUpdate(engine.cursor);
      }
    }
    // Prefer in-session position after risk-filter / table rebuild; LS only until first visible restore.
    applyTradesSummaryScrollTop(summaryScrollRestored ? prevScrollTop : readSavedTradesSummaryScrollTop());
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
    if (!months.length) {
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
      + months.map((m) => {
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
        const monthLabel = dense
          ? String(m.label || '').replace(/\s+\d+$/, '')
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
      formatStat: (v) => formatTradeMetricStat(colKey, v),
      spreadWidthRegime: colKey === 'SpreadEntry' || colKey === 'SpreadExit',
    });
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

  /**
   * Tip-1m server sim is full-window; slice to M15 cursor like local M15 edgesSoFar.
   * Trades that enter after cursor are dropped; exit after cursor → «Открыта».
   */
  function tipRowsUpToCursor(rows) {
    if (!rows || !rows.length) return rows || [];
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
          accountAfter: null,
          status: 'Открыта',
        });
      } else {
        out.push(r);
      }
    }
    return out;
  }

  function tipPointForDate(td, zFallback, spFallback) {
    if (!td || td === '—') return null;
    const idx = findIndexByTradeDate(td);
    if (idx >= 0) return allPoints[idx];
    const ms = typeof labelToUnixSec === 'function'
      ? labelToUnixSec(td) * 1000
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
      const entryBar = tipPointForDate(r.entryDate, r.entryZ, r.entrySpread);
      if (!entryBar) continue;
      edges.push({
        signal: isLong ? 'EnterLong' : 'EnterShort',
        bar: entryBar,
        positionBefore: 'Flat',
        positionAfter: isLong ? 'Long' : 'Short',
        manual: false,
      });
      if (r.exitDate && r.exitDate !== '—' && r.status !== 'Открыта') {
        const exitBar = tipPointForDate(r.exitDate, r.exitZ, r.exitSpread);
        if (!exitBar) continue;
        edges.push({
          signal: isLong ? 'ExitLong' : 'ExitShort',
          bar: exitBar,
          positionBefore: isLong ? 'Long' : 'Short',
          positionAfter: 'Flat',
          manual: false,
        });
      }
    }
    return edges;
  }

  function tipPositionFromRows(rows) {
    if (!rows || !rows.length) return 'Flat';
    for (let i = rows.length - 1; i >= 0; i--) {
      if (rows[i].status === 'Открыта') return rows[i].direction || 'Flat';
    }
    return 'Flat';
  }

  function tip1mBarsRequestKey() {
    return [
      $('csvSel')?.value || '',
      $('startDate')?.value || '',
      '90',
    ].join('|');
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
    let url = `/api/bars1m?csv=${encodeURIComponent(csv)}&chartDays=90`;
    if (start) url += `&start=${encodeURIComponent(start)}`;
    const res = await fetch(url);
    if (!res.ok) {
      const errText = await res.text();
      throw new Error(errText || res.statusText);
    }
    const data = await res.json();
    if (jobId !== tip1mChartJobId) return null;
    if (!data.ok && !(data.bars && data.bars.length)) {
      throw new Error(data.hintRu || 'Нет 1м баров для графика');
    }
    tip1mBarsCacheKey = key;
    tip1mChartMeta = {
      count: data.count,
      fullTipCount: data.fullTipCount,
      chartLimited: !!data.chartLimited,
      chartDays: data.chartDays,
      hintRu: data.hintRu || null,
      first: data.first,
      last: data.last,
      _active: true,
    };
    return { bars: data.bars || [], meta: tip1mChartMeta, fromCache: false };
  }

  function stashM15IfNeeded() {
    if (m15PointsStash == null && allPoints.length && !tip1mChartMeta?._active) {
      m15PointsStash = allPoints;
    }
  }

  async function activateTip1mChart() {
    stashM15IfNeeded();
    const st = $('status');
    if (st) st.textContent = 'касание 1м · гружу 1м свечи…';
    const loaded = await loadTip1mChartBars({ force: true });
    if (!loaded || !isTip1mMode()) return;
    allPoints = loaded.bars;
    chartFocusIndex = null;
    selectedTradeId = null;
    updateMoexLastBarHint(allPoints.length ? allPoints[allPoints.length - 1]?.tradeDate : null);
    const csvSafe = String($('csvSel')?.value || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const lim = tip1mChartMeta?.chartLimited
      ? ` · <span class="badge-quiet" title="${String(tip1mChartMeta.hintRu || '').replace(/"/g, '&quot;')}">1м график ${tip1mChartMeta.chartDays}д</span>`
      : ' · <span class="badge-online">1м tip-Z</span>';
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
        { skipExtras },
      );
    }
    const filtered = filterRowsByRisk(rows);
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
    const windowPoints = allPoints.slice(range.start, range.end + 1);
    const candles = buildZCandles(windowPoints);
    const currentPoint = frame.currentPoint
      || (allPoints.length ? allPoints[frame.cursorIndex] : null);

    const edgesChanged = edgesLen !== uiSeriesCache.edgesLen;
    const prevCursor = uiSeriesCache.cursor;
    const cursorChanged = frame.cursorIndex !== prevCursor;
    const skipMilestones = (light && !edgesChanged) || afterParams || tipMode;

    let markers;
    let markersChanged = true;
    if (light && !edgesChanged && uiSeriesCache.markers) {
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

    const trades = buildTradeSegments(chartEdges, currentPoint).map((t) => ({
      id: t.id,
      entryTime: t.entryTime,
      entryZ: t.entryZ,
      exitTime: t.exitTime,
      exitZ: t.exitZ,
      open: t.open,
    }));

    const equityTotal = buildEquitySeries(
      allPoints,
      chartEdges,
      windowPoints,
      frame.cursorIndex,
    );
    const equity = pnlMode === 'trade'
      ? buildPerTradeEquitySeries(
        allPoints,
        chartEdges,
        windowPoints,
        frame.cursorIndex,
      )
      : equityTotal;
    const deltaPp = buildDeltaPpSeries(
      allPoints,
      chartEdges,
      windowPoints,
      frame.cursorIndex,
    );

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
        fitFull,
        // afterParams меняет всю equity-кривую — нельзя «дописать хвост»
        light: light && !afterParams,
        markersChanged: markersChanged || afterParams,
      },
    );
    chart.setReplay(payload);
    if (fitFull && typeof chart.fitFullRange === 'function') {
      requestAnimationFrame(() => chart.fitFullRange());
    }
    if (selectedTradeId) chart.selectTrade(selectedTradeId);

    updateOpenTradeOverlay(chartEdges, currentPoint, tipPos);

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
    const riskText = (() => {
      const short = formatRiskExitSelectionShort(selectedRiskExitGroups());
      return short !== 'выкл' ? ` · Risk ${short}` : '';
    })();
    const capText = getSimCompound() ? ' · капит.' : '';
    let regimeHtml = '';
    if (typeof classifySpreadRegime === 'function' && allPoints.length && frame.cursorIndex >= 0) {
      const from = Math.max(0, frame.cursorIndex - 7);
      const regimeBars = allPoints.slice(from, frame.cursorIndex + 1);
      const r = classifySpreadRegime(regimeBars);
      if (r && r.key !== 'na') {
        const zPart = r.zLabel
          ? ` <span class="badge-quiet">· ${r.zLabel}</span>`
          : '';
        const title = r.title ? ` title="${String(r.title).replace(/"/g, '&quot;')}"` : '';
        regimeHtml =
          ` <span class="badge-regime badge-regime-${r.key}"${title}>`
          + `${r.label}${zPart}</span>`;
      }
    }
    const slipPts = typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0;
    const slipText = slipPts > 0 ? ` · slip ${slipPts}` : '';
    const modeText = isTip1mMode()
      ? ' · <span class="badge-online" title="Mode B: tip-Z на 1м">касание 1м</span>'
      : '';
    let tipNote = '';
    if (isTip1mMode()) {
      if (!tipSimCache.rows) {
        tipNote = '   ·   tip считает…';
      } else {
        const tipRows = tipRowsCursor || tipRowsUpToCursor(tipSimCache.rows);
        const tipSum = buildTradeSimSummary(tipRows, getSimNotionalRub());
        tipNote =
          `   ·   tip ${tipSum.closedCount} сд. / ${formatCapitalPct(tipSum.retPct)}`;
      }
      if (tip1mChartMeta?.chartLimited) {
        tipNote += `   ·   график ${tip1mChartMeta.chartDays}д 1м`;
      }
    }
    const sigCount = tipMode ? chartEdges.length : frame.signalEdgesSoFar.length;
    $('status').innerHTML =
      `${frame.barLabel}   ·   Z ${zHover} · Спред ${spHover}${regimeHtml}   ·   ${tipPos}   ·   сигн. ${sigCount}   ·   пороги ±${thresholds().entry} / ±${thresholds().exit}${tpText}${riskText}${capText}${slipText}${modeText}   ·   ${barsSourceLabel()}${tipNote}`;

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

  function getBarsSource() {
    const v = localStorage.getItem(LS.barsSource);
    return v === 'final' ? 'final' : 'as_live';
  }

  function setBarsSource(src) {
    const next = src === 'final' ? 'final' : 'as_live';
    localStorage.setItem(LS.barsSource, next);
    syncBarsSourceChips();
    return next;
  }

  function syncBarsSourceChips() {
    const cur = getBarsSource();
    document.querySelectorAll('#barsSourceChips .chip').forEach((btn) => {
      btn.classList.toggle('active', btn.getAttribute('data-bars-source') === cur);
    });
  }

  function barsSourceLabel() {
    return getBarsSource() === 'final' ? 'финальный CSV' : 'как Прод';
  }

  function getEdgeMode() {
    const v = localStorage.getItem(LS.edgeMode);
    return v === 'tip1m' ? 'tip1m' : 'm15';
  }

  function isTip1mMode() {
    return getEdgeMode() === 'tip1m';
  }

  function setEdgeMode(mode) {
    const next = mode === 'tip1m' ? 'tip1m' : 'm15';
    localStorage.setItem(LS.edgeMode, next);
    syncEdgeModeChips();
    return next;
  }

  function syncEdgeModeChips() {
    const cur = getEdgeMode();
    document.querySelectorAll('#edgeModeChips .chip').forEach((btn) => {
      btn.classList.toggle('active', btn.getAttribute('data-edge-mode') === cur);
    });
  }

  function edgeModeLabel() {
    return isTip1mMode() ? 'касание 1м' : 'M15 закрытие';
  }

  function tipSimRequestKey() {
    const t = thresholds();
    const slip = typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0;
    return [
      $('csvSel')?.value || '',
      $('startDate')?.value || '',
      t.entry, t.exit, t.takeProfitPct || 0,
      getSimNotionalRub(), getSimCompound() ? 1 : 0, slip,
    ].join('|');
  }

  function tipTradeToRow(t, entryThreshold) {
    const risk = typeof assessTradeRisk === 'function'
      ? assessTradeRisk(t.entryDate, t.exitDate, t.entryZ, t.overnight, entryThreshold)
      : null;
    const pnlMin = t.pnlMin != null && Number.isFinite(Number(t.pnlMin)) ? Number(t.pnlMin) : null;
    const pnlMax = t.pnlMax != null && Number.isFinite(Number(t.pnlMax)) ? Number(t.pnlMax) : null;
    const accountAfter = t.accountAfter != null && Number.isFinite(Number(t.accountAfter))
      ? Number(t.accountAfter)
      : null;
    const hit1Date = t.hit1Date || null;
    const hit2Date = t.hit2Date || null;
    const hit3Date = t.hit3Date || null;
    const parseMs = typeof parseTradeMs === 'function' ? parseTradeMs : () => null;
    return makeTradeRow({
      index: t.index,
      direction: t.direction,
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
      _risk: risk,
    });
  }

  function scheduleTipSimFetch({ immediate = false } = {}) {
    if (!isTip1mMode()) return;
    if (tipSimTimer) clearTimeout(tipSimTimer);
    // Debounce param tweaks; seek/cursor uses tipRowsUpToCursor (no server POST).
    const delay = immediate ? 40 : 280;
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
    const st = $('status');
    if (st && !tipSimCache.rows) {
      st.textContent = 'касание 1м · считаю на сервере…';
    }
    const t = thresholds();
    const body = {
      csv: $('csvSel')?.value || 'm15_tatn_255d.csv',
      start: $('startDate')?.value || null,
      entry: t.entry,
      exit: t.exit,
      slip: typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0.02,
      notional: getSimNotionalRub(),
      compound: getSimCompound(),
      takeProfitPct: t.takeProfitPct || 0,
    };
    try {
      const res = await fetch('/api/sim/tip1m', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok) {
        const errText = await res.text();
        throw new Error(errText || res.statusText);
      }
      const data = await res.json();
      if (jobId !== tipSimJobId) return;
      const entryTh = t.entry;
      const rows = (data.trades || []).map((tr) => tipTradeToRow(tr, entryTh));
      tipSimCache = {
        key,
        rows,
        meta: data.meta || null,
        summary: data.summary || null,
      };
      refreshTradesTable();
      refreshUi({ afterParams: true });
      if (!isZHeatmapHidden()) scheduleZHeatmapUpdate(engine?.cursor, { immediate: true });
    } catch (e) {
      if (jobId !== tipSimJobId) return;
      tipSimCache = { key: '', rows: null, meta: null, summary: null };
      const msg = (e && e.message) ? String(e.message).slice(0, 180) : String(e);
      if (st) st.textContent = `касание 1м · ошибка: ${msg}`;
      const host = $('tradesBody');
      if (host) {
        // leave table; summary note via status
      }
      alert('Касание 1м (сервер): ' + msg);
    }
  }

  async function fetchTipHeatmap(grid) {
    const t = thresholds();
    const body = {
      csv: $('csvSel')?.value || 'm15_tatn_255d.csv',
      start: $('startDate')?.value || null,
      entryMin: grid.entryMin,
      entryMax: grid.entryMax,
      exitMin: grid.exitMin,
      step: grid.step,
      slip: typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0.02,
      notional: getSimNotionalRub(),
      compound: getSimCompound(),
      takeProfitPct: t.takeProfitPct || 0,
    };
    const res = await fetch('/api/sim/tip1m/heatmap', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!res.ok) {
      const errText = await res.text();
      throw new Error(errText || res.statusText);
    }
    return res.json();
  }

  function updateBarsCoverageWarn(meta) {
    const el = $('barsCoverageWarn');
    if (!el) return;
    if (!meta || !meta.as_live) {
      el.textContent = '';
      el.title = '';
      return;
    }
    const cov = Number(meta.coverage) || 0;
    const locked = Number(meta.locked_count) || 0;
    const n = Number(meta.bars_count) || 0;
    if (n <= 0) {
      el.textContent = '';
      return;
    }
    const fromRaw = meta.locked_from || '';
    const fromHm = fromRaw.length >= 16 ? fromRaw.slice(11, 16) : (fromRaw || '—');
    // Low / partial coverage: freeze only where decision_bars exist.
    if (cov < 0.15 || (n >= 50 && locked < Math.max(5, Math.floor(n * 0.05)))) {
      el.textContent =
        `⚠ заморозка с ${fromHm}; сделки до этой метки на финальном CSV (${locked}/${n})`;
      el.title =
        `Заморозка decision_bars с ${fromRaw || '—'}. ` +
        'Бары без freeze = финальный CSV (возможны фантомные сделки). ' +
        'Частичный overlay всё же подменяет Z там, где freeze есть.';
    } else if (cov < 0.85) {
      el.textContent =
        `⚠ заморозка с ${fromHm}; до метки — финальный CSV · 🔒 ${locked}/${n} (${(cov * 100).toFixed(0)}%)`;
      el.title =
        `Заморожено ${meta.locked_from || ''} … ${meta.locked_to || ''}. ` +
        'Сделки до начала заморозки считаются по финальному CSV.';
    } else {
      el.textContent = `🔒 ${locked}/${n} (${(cov * 100).toFixed(0)}%)`;
      el.title = `Заморожено decision_bars: ${meta.locked_from || ''} … ${meta.locked_to || ''}`;
    }
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
    if (getBarsSource() === 'as_live') url += '&as_live=1';
    const title = $('loadingTitle');
    const sub = $('loadingSub');
    const timeoutMs = barsLoadTimeoutMs(csv);
    const timeoutSec = Math.round(timeoutMs / 1000);
    if (title) title.textContent = `Загрузка ${csv}…`;
    if (sub) {
      sub.textContent =
        `запрос /api/bars (${barsSourceLabel()}) · лимит ${timeoutSec} с`;
    }
    const started = Date.now();
    const tick = setInterval(() => {
      if (sub) {
        sub.textContent =
          `запрос /api/bars (${barsSourceLabel()}) · ${Math.round((Date.now() - started) / 1000)} / ${timeoutSec} с`;
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
    try {
      if (!background) {
        $('loading').classList.remove('hidden');
        $('app').classList.add('hidden');
      }
      const data = await loadBars(csv);
      if (!background) {
        if (title) title.textContent = 'Инициализация графика…';
        if (sub) sub.textContent = `${data.count} баров`;
      }
      allPoints = data.bars;
      lastBarsMeta = {
        as_live: !!data.as_live,
        locked_count: Number(data.locked_count) || 0,
        bars_count: Number(data.bars_count != null ? data.bars_count : data.count) || 0,
        coverage: Number(data.coverage) || 0,
        locked_from: data.locked_from || null,
        locked_to: data.locked_to || null,
      };
      updateBarsCoverageWarn(lastBarsMeta);
      rememberBarsCache(csv, data.bars);
      updateMoexLastBarHint(data.last);
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
      const modeBadge = data.as_live
        ? '<span class="badge-online" title="decision_bars overlay">как Прод</span>'
        : '<span class="badge-quiet" title="финальный CSV/SQLite">финальный CSV</span>';
      const csvSafe = String(data.csv || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      $('meta').innerHTML = `TATN/TATNP · ${data.count} баров · ${csvSafe} · ${src} · ${modeBadge} · ${netBadge}`;
      rebuildEngine();
      chart?.followReplayEdge(true);
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
      refreshUi();
      if (isTip1mMode()) {
        tipSimCache = { key: '', rows: null, meta: null, summary: null };
        try {
          await activateTip1mChart();
        } catch (e) {
          console.error(e);
        }
        scheduleTipSimFetch({ immediate: true });
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

  /** Дотянуть хвост MOEX (как Trade) и перезалить бары в движок без полного сброса UI. */
  async function refreshMoexForTesting() {
    const btn = $('btnRefreshMoex');
    const csv = $('csvSel')?.value || 'm15_tatn_255d.csv';
    if (btn) btn.disabled = true;
    setMoexRefreshStatus('Обновление…', 'pending');
    try {
      const syncRes = await fetch(
        `/api/markets/refresh?days=7&csv=${encodeURIComponent(csv)}`,
        { method: 'POST' },
      );
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
      setMoexRefreshStatus(`Ошибка: ${e.message || e}`, 'err');
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
      engine.manualLong();
      refreshUi();
    });
    $('btnShort').addEventListener('click', () => {
      if (!engine) return;
      pausePlayback();
      engine.manualShort();
      refreshUi();
    });
    $('btnCloseAll').addEventListener('click', () => {
      if (!engine) return;
      pausePlayback();
      const before = engine.openSideFromEdges();
      const closed = engine.closeAllTrades();
      refreshUi();
      const after = engine.openSideFromEdges();
      const status = $('status');
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
      if ($('hmStep')) $('hmStep').value = String(g.step);
      saveSetting(LS.hmEntryMin, g.entryMin);
      saveSetting(LS.hmEntryMax, g.entryMax);
      saveSetting(LS.hmExitMin, g.exitMin);
      saveSetting(LS.hmStep, g.step);
      zHeatmapCache = { key: '', cells: null, grid: null, inFlightKey: '' };
      zHeatmapJobId += 1;
      scheduleZHeatmapUpdate(null, { immediate: true });
    };
    ['hmEntryMin', 'hmEntryMax', 'hmExitMin', 'hmStep'].forEach((id) => {
      $(id)?.addEventListener('change', persistHmGrid);
      $(id)?.addEventListener('blur', persistHmGrid);
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
      populateThresholdSelect($('entrySel'), 0.5, entry);
      populateThresholdSelect($('exitSel'), 0.3, exit);
      saveSetting(LS.entry, entry);
      saveSetting(LS.exit, exit);
      applyStrategyParamsChange();
    });

    $('startDate').addEventListener('change', async () => {
      await applyStartDateAndReload($('startDate').value);
    });

    document.querySelectorAll('#startPresetChips .chip[data-start-preset]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const ymd = startDateForPreset(btn.dataset.startPreset);
        applyStartDateAndReload(ymd).catch(() => {});
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

    document.querySelectorAll('#barsSourceChips .chip[data-bars-source]').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const src = btn.getAttribute('data-bars-source') || 'as_live';
        if (src === getBarsSource()) return;
        setBarsSource(src);
        // Different OHLC/Z — drop cached series for this CSV.
        Object.keys(barsCacheByCsv).forEach((k) => { delete barsCacheByCsv[k]; });
        zHeatmapCache = { key: null, cells: null, grid: null, inFlightKey: null };
        $('loading').classList.remove('hidden');
        $('app').classList.add('hidden');
        await bootstrap($('csvSel').value);
      });
    });

    document.querySelectorAll('#edgeModeChips .chip[data-edge-mode]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const mode = btn.getAttribute('data-edge-mode') || 'm15';
        if (mode === getEdgeMode()) return;
        setEdgeMode(mode);
        tipSimCache = { key: '', rows: null, meta: null, summary: null };
        tipSimJobId += 1;
        zHeatmapCache = { key: '', cells: null, grid: null, inFlightKey: '' };
        zHeatmapJobId += 1;
        if (mode === 'tip1m') {
          activateTip1mChart()
            .then(() => {
              scheduleTipSimFetch({ immediate: true });
            })
            .catch((e) => {
              console.error(e);
              const st = $('status');
              if (st) st.textContent = `касание 1м · ошибка графика: ${e.message || e}`;
            });
          return;
        }
        activateM15Chart();
        applyStrategyParamsChange();
      });
    });

    $('btnRefreshMoex')?.addEventListener('click', () => {
      refreshMoexForTesting().catch(() => {});
    });

    document.querySelectorAll('#periodChips .chip[data-days]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const days = parseInt(btn.dataset.days, 10);
        setVisibleDaysPeriod(days, { fitFull: days >= 400 });
        chartFocusIndex = null;
        refreshUi();
      });
    });

    $('btnFitFullChart')?.addEventListener('click', () => {
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
        refreshTradesTable();
      });
    });

    const tradesBody = $('tradesBody');
    if (tradesBody) {
      tradesBody.addEventListener('pointerover', (e) => {
        const cell = e.target.closest('td.metric-cell');
        if (!cell || !tradesBody.contains(cell)) return;
        if (tradeMetricTipHideTimer) {
          clearTimeout(tradeMetricTipHideTimer);
          tradeMetricTipHideTimer = null;
        }
        showTradeMetricTip(cell);
      });
      tradesBody.addEventListener('pointerout', (e) => {
        const cell = e.target.closest('td.metric-cell');
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

  document.addEventListener('DOMContentLoaded', () => {
    loadSettings();
    syncBarsSourceChips();
    syncEdgeModeChips();
    syncStartPresetChips();
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
    if (savedView === 'replay') {
      if (window.MoexLive) MoexLive.setViewMode('replay');
      bootstrap($('csvSel').value);
    } else {
      $('loading').classList.add('hidden');
      $('app').classList.remove('hidden');
      if (window.MoexLive) MoexLive.setViewMode(savedView);
      bootstrap($('csvSel').value, { background: true });
    }
  });
})();
