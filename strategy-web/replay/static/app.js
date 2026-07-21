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
    tradesScrollHeight: 'moexReplay.tradesScrollHeight',
    riskCompareHeight: 'moexReplay.riskCompareHeight',
    monthlyPnlHeight: 'moexReplay.monthlyPnlHeight',
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
    pnlMode: 'moexReplay.pnlMode',
    notionalRub: 'moexReplay.notionalRub',
    slippageSpreadPts: 'moexReplay.slippageSpreadPts',
    tradeSortCol: 'moexReplay.tradeSortCol',
    tradeSortDir: 'moexReplay.tradeSortDir',
    riskFilter: 'moexReplay.riskFilter',
    takeProfit: 'moexReplay.takeProfit',
    riskExit: 'moexReplay.riskExit', // legacy single preset
    compound: 'moexReplay.compound',
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
  const TRADES_SUMMARY_MIN = 120;

  let allPoints = [];
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
  let statusBarMetricDists = { z: null, spread: null };
  let tradeMetricTipEl = null;
  let tradeMetricTipHideTimer = null;
  let timer = null;
  let scrubbing = false;
  let visibleTradeColumns = [...TRADE_COLUMNS_DEFAULT];
  let scrollRestored = false;
  let summaryScrollRestored = false;
  let riskCompareScrollRestored = false;
  let monthlyPnlScrollRestored = false;
  /** Пока true — не писать scroll в localStorage (восстановление / layout). */
  let suppressScrollSave = false;
  let suppressScrollSaveTimer = null;
  let selectedTradeId = null;
  let pnlMode = 'total';
  let tradeSortColumn = 'Index';
  let tradeSortDir = 'asc';
  /** all | no-red | only-red */
  let riskFilter = 'all';
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
      saveSetting(LS.slippageSpreadPts, slipV);
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

  function computeMoneyStopDynamic(cursorIndex) {
    const { entry, exit } = thresholds();
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
    const metricsMin = isTradesSummaryHidden() ? 0 : 48;
    const monthlyH = isMonthlyPnlHidden()
      ? 0
      : readCssPxVar(summaryEl, '--monthly-pnl-height', loadMonthlyPnlHeight());
    const used = (riskHead?.offsetHeight || 0)
      + (summaryHead?.offsetHeight || 0) + metricsMin
      + (isTradesSummaryHidden() ? 0 : CHART_SPLITTER_HEIGHT)
      + (monthlyHead?.offsetHeight || 0) + monthlyH
      + (isMonthlyPnlHidden() ? 0 : CHART_SPLITTER_HEIGHT)
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
    const used = (filters?.offsetHeight || 0)
      + riskH + CHART_SPLITTER_HEIGHT
      + metricsMin + CHART_SPLITTER_HEIGHT + 16;
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

  function readCssPxVar(el, prop, fallback) {
    if (!el) return fallback;
    const n = parseInt(getComputedStyle(el).getPropertyValue(prop), 10);
    return Number.isFinite(n) && n > 0 ? n : fallback;
  }

  function bindTradesPanelVerticalSplits() {
    applyTradesScrollHeight(loadTradesScrollHeight());
    applyRiskCompareHeight(loadRiskCompareHeight());
    applyMonthlyPnlHeight(loadMonthlyPnlHeight());

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
      readHeight: () => readCssPxVar($('tradesSummary'), '--monthly-pnl-height', loadMonthlyPnlHeight()),
      applyHeight: applyMonthlyPnlHeight,
      saveKey: LS.monthlyPnlHeight,
      defaultHeight: MONTHLY_PNL_HEIGHT_DEFAULT,
      invertDy: true,
      isHidden: isMonthlyPnlHidden,
    });

    // Re-clamp for display only. Never write back to LS — early/hidden layout
    // would clamp to mins and wipe user prefs on Ctrl+F5.
    window.addEventListener('resize', () => {
      if (!isTradesTableHidden()) applyTradesScrollHeight(loadTradesScrollHeight());
      if (!isRiskCompareHidden()) applyRiskCompareHeight(loadRiskCompareHeight());
      if (!isMonthlyPnlHidden()) applyMonthlyPnlHeight(loadMonthlyPnlHeight());
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

    if (!deltaHidden) applyDeltaChartHeight(loadDeltaChartHeight());
    if (!pnlHidden) applyPnlChartHeight(loadPnlChartHeight());
    if (!tradesHidden) applyTradesScrollHeight(loadTradesScrollHeight());
    if (!riskHidden) applyRiskCompareHeight(loadRiskCompareHeight());
    if (!monthlyHidden) applyMonthlyPnlHeight(loadMonthlyPnlHeight());

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

    const bindToggle = (btnId, paneId, lsKey, isHidden) => {
      $(btnId)?.addEventListener('click', () => {
        const next = !isHidden();
        setPaneCollapsed(paneId, next);
        saveSetting(lsKey, next ? '1' : '');
        syncPaneCollapseUi();
      });
    };
    bindToggle('btnCollapseDelta', 'deltaPane', LS.deltaPaneHidden, isDeltaPaneHidden);
    bindToggle('btnCollapsePnl', 'pnlPane', LS.pnlPaneHidden, isPnlPaneHidden);
    bindToggle('btnCollapseTradesTable', 'tradesTablePane', LS.tradesTableHidden, isTradesTableHidden);
    bindToggle('btnCollapseRiskCompare', 'riskComparePane', LS.riskCompareHidden, isRiskCompareHidden);
    bindToggle('btnCollapseTradesSummary', 'tradesSummaryPane', LS.tradesSummaryHidden, isTradesSummaryHidden);
    bindToggle('btnCollapseMonthlyPnl', 'monthlyPnlPane', LS.monthlyPnlHidden, isMonthlyPnlHidden);

    syncPaneCollapseUi();
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
      buildActiveSimOpts(cursorForOpts),
    );
    engine.manualEdges = savedManual;
    engine.manualSeq = savedSeq;
    riskCompareCache = { key: '', html: '' };
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
    refreshUi({ afterParams: true });
    enrichAfterParamsTimer = setTimeout(() => {
      enrichAfterParamsTimer = null;
      if (!engine) return;
      // Досчитать Min/Max, Hit1/2/3 и risk-compare в фоне
      skipTradeExtrasOnce = false;
      refreshTradesTable();
    }, 120);
  }

  function renderColumnPicker() {
    const picker = $('columnPicker');
    picker.innerHTML = '';
    const allBtn = document.createElement('button');
    allBtn.type = 'button';
    allBtn.className = 'chip col-chip';
    allBtn.textContent = 'Все';
    allBtn.addEventListener('click', () => {
      visibleTradeColumns = [...TRADE_COLUMNS_DEFAULT];
      saveSetting(LS.tradeColumns, encodeTradeColumns(visibleTradeColumns));
      renderColumnPicker();
      refreshTradesTable();
    });
    picker.appendChild(allBtn);

    TRADE_COLUMNS.forEach((col) => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'chip col-chip' + (visibleTradeColumns.includes(col.key) ? ' active' : '');
      btn.textContent = col.title;
      btn.title = col.key;
      btn.addEventListener('click', () => {
        const set = new Set(visibleTradeColumns);
        if (set.has(col.key)) {
          if (set.size <= 1) return;
          set.delete(col.key);
        } else {
          set.add(col.key);
        }
        visibleTradeColumns = TRADE_COLUMN_KEYS.filter((k) => set.has(k));
        saveSetting(LS.tradeColumns, encodeTradeColumns(visibleTradeColumns));
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

  function restoreTradesSummaryScrollAfterVisible() {
    if (summaryScrollRestored && riskCompareScrollRestored && monthlyPnlScrollRestored) return;
    requestAnimationFrame(() => {
      applyTradesSummaryScrollTop(readSavedTradesSummaryScrollTop());
      applyRiskCompareScrollTop(readSavedRiskCompareScrollTop());
      applyMonthlyPnlScrollTop(readSavedMonthlyPnlScrollTop());
      if (!summaryScrollRestored || !riskCompareScrollRestored || !monthlyPnlScrollRestored) {
        requestAnimationFrame(() => {
          applyTradesSummaryScrollTop(readSavedTradesSummaryScrollTop());
          applyRiskCompareScrollTop(readSavedRiskCompareScrollTop());
          applyMonthlyPnlScrollTop(readSavedMonthlyPnlScrollTop());
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
    // Prefer in-session position after risk-filter / table rebuild; LS only until first visible restore.
    applyTradesSummaryScrollTop(summaryScrollRestored ? prevScrollTop : readSavedTradesSummaryScrollTop());
  }

  /** Monthly hist % — same deposit basis as «Доходность» (`getSimNotionalRub`). */
  function monthlyPnlPct(rub, notional) {
    return notional > 0 ? (rub / notional) * 100 : 0;
  }

  function formatMonthlyHistPct(rub, notional) {
    const pct = monthlyPnlPct(rub, notional);
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

  function formatMonthlyYTick(pct, notional) {
    if (!Number.isFinite(pct) || Math.abs(pct) < 1e-9) return '0%';
    const rounded = Math.round(pct);
    const rub = notional > 0 ? (rounded / 100) * notional : 0;
    const pctText = `${rounded >= 0 ? '+' : '−'}${Math.abs(rounded)}%`;
    return `${pctText} (${formatMonthlyYAbs(rub)})`;
  }

  function formatMonthlyMeanLabel(meanRub, notional) {
    const pctText = formatMonthlyHistPct(meanRub, notional);
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
    const months = typeof buildMonthlyPnl === 'function'
      ? buildMonthlyPnl(visibleRows)
      : [];
    if (!months.length) {
      el.innerHTML = (
        `<div class="trades-monthly-title">PnL по месяцам</div>`
        + `<div class="trades-monthly-empty">Нет закрытых сделок</div>`
      );
      applyMonthlyPnlScrollTop(monthlyPnlScrollRestored ? prevScrollTop : readSavedMonthlyPnlScrollTop());
      return;
    }
    const notional = getSimNotionalRub();
    const dense = months.length > 8;
    const histCls = `trades-monthly-hist${dense ? ' dense' : ''}`;

    let minPnl = 0;
    let maxPnl = 0;
    let sumPnl = 0;
    for (const m of months) {
      if (m.pnl < minPnl) minPnl = m.pnl;
      if (m.pnl > maxPnl) maxPnl = m.pnl;
      sumPnl += m.pnl;
    }
    const meanPnl = sumPnl / months.length;
    const yMin = Math.min(0, minPnl);
    const yMax = Math.max(0, maxPnl);

    const minPct = monthlyPnlPct(yMin, notional);
    const maxPct = monthlyPnlPct(yMax, notional);
    const yTicks = monthlyPnlYTicks(minPct, maxPct);
    // Expand plot range to nice tick extents so top/bottom ticks sit on edges.
    const tickMinPct = Math.min(...yTicks, 0);
    const tickMaxPct = Math.max(...yTicks, 0);
    const tickMinRub = notional > 0 ? (tickMinPct / 100) * notional : yMin;
    const tickMaxRub = notional > 0 ? (tickMaxPct / 100) * notional : yMax;
    const plotMin = Math.min(yMin, tickMinRub);
    const plotMax = Math.max(yMax, tickMaxRub);
    const plotRange = plotMax - plotMin || 1;
    const zeroBottomPlot = ((0 - plotMin) / plotRange) * 100;
    const meanBottomPlot = ((meanPnl - plotMin) / plotRange) * 100;

    const yLabelsHtml = yTicks.map((t) => {
      const rub = notional > 0 ? (t / 100) * notional : 0;
      const bottom = ((rub - plotMin) / plotRange) * 100;
      return `<span class="tm-y-label" style="bottom:${bottom.toFixed(2)}%">${formatMonthlyYTick(t, notional)}</span>`;
    }).join('');
    const gridHtml = yTicks.map((t) => {
      const rub = notional > 0 ? (t / 100) * notional : 0;
      const bottom = ((rub - plotMin) / plotRange) * 100;
      const isZero = Math.abs(t) < 1e-9;
      return `<span class="tm-grid-line${isZero ? ' tm-grid-zero' : ''}" style="bottom:${bottom.toFixed(2)}%"></span>`;
    }).join('');

    const meanCls = meanPnl > 0 ? 'pos' : meanPnl < 0 ? 'neg' : '';
    /* Mean text lives in the title (outside bar hover / native title tip zone) */
    const meanLabel = formatMonthlyMeanLabel(meanPnl, notional);

    el.innerHTML = (
      `<div class="trades-monthly-title">`
      + `<span class="tm-title-text">PnL по месяцам</span>`
      + `<span class="tm-mean-badge ${meanCls}">${meanLabel}</span>`
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
        const barCls = m.pnl > 0 ? 'pos' : m.pnl < 0 ? 'neg' : 'flat';
        const barH = Math.max(0, (Math.abs(m.pnl) / plotRange) * 100);
        let barStyle;
        if (m.pnl >= 0) {
          barStyle = `bottom:${zeroBottomPlot.toFixed(2)}%;height:${barH.toFixed(2)}%`;
        } else {
          barStyle = `bottom:${(zeroBottomPlot - barH).toFixed(2)}%;height:${barH.toFixed(2)}%`;
        }
        const pctLabel = formatMonthlyHistPct(m.pnl, notional);
        const absLabel = formatMonthlyHistAbs(m.pnl);
        const monthLabel = dense
          ? String(m.label || '').replace(/\s+\d+$/, '')
          : m.label;
        return (
          `<div class="tm-col" title="${m.label} · ${pctLabel} ${absLabel} · ${m.count} сд.">`
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
    });
    tip.classList.remove('hidden');
    positionTradeMetricTip(tip, cell);
  }

  function rebuildStatusBarMetricDists(points) {
    if (typeof computeNumericDistribution !== 'function') {
      statusBarMetricDists = { z: null, spread: null };
      return;
    }
    const zs = [];
    const sps = [];
    for (const p of points || []) {
      const z = p.zScore != null ? Number(p.zScore) : NaN;
      const sp = p.spreadPercent != null ? Number(p.spreadPercent) : NaN;
      if (Number.isFinite(z)) zs.push(z);
      if (Number.isFinite(sp)) sps.push(sp);
    }
    statusBarMetricDists = {
      z: computeNumericDistribution(zs),
      spread: computeNumericDistribution(sps),
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
    if (typeof buildMetricDistTipHtml !== 'function') return;
    const metric = anchor.dataset.metric;
    const value = Number(anchor.dataset.value);
    if ((metric !== 'z' && metric !== 'spread') || !Number.isFinite(value)) return;
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

  function refreshTradesTable() {
    if (!engine) return;
    const tradesScrollEl = $('tradesScroll');
    const prevLeft = tradesScrollEl ? tradesScrollEl.scrollLeft : 0;
    const prevTop = tradesScrollEl ? tradesScrollEl.scrollTop : 0;
    const frame = engine.frameAtCursor();
    const { entry } = thresholds();
    const skipExtras = skipTradeExtrasOnce;
    if (skipTradeExtrasOnce) skipTradeExtrasOnce = false;
    const rows = buildTradeRows(
      frame.signalEdgesSoFar,
      entry,
      allPoints,
      frame.cursorIndex,
      { skipExtras },
    );
    const filtered = filterRowsByRisk(rows);
    const sortedRows = [...filtered].sort(
      (a, b) => compareTradeRows(a, b, tradeSortColumn, tradeSortDir),
    );

    const head = $('tradesHead');
    head.innerHTML = '';
    const visibleCols = TRADE_COLUMNS.filter((c) => visibleTradeColumns.includes(c.key));
    tradeMetricDists = buildTradeMetricDistributions(sortedRows, visibleCols.map((c) => c.key));
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
      th.addEventListener('click', (e) => {
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
      });
      head.appendChild(th);
    }

    const tbody = $('tradesBody');
    tbody.innerHTML = '';
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
      tbody.appendChild(tr);
    }

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
    const edgesLen = frame.signalEdgesSoFar.length;
    const range = barReplayVisibleIndexRange(
      allPoints,
      frame.cursorIndex,
      visibleDays,
      chartFocusIndex,
    );
    const windowPoints = allPoints.slice(range.start, range.end + 1);
    const candles = buildZCandles(windowPoints);
    const currentPoint = frame.visiblePoints.length
      ? frame.visiblePoints[frame.visiblePoints.length - 1]
      : null;

    const edgesChanged = edgesLen !== uiSeriesCache.edgesLen;
    const skipMilestones = (light && !edgesChanged) || afterParams;

    let markers;
    let markersChanged = true;
    if (light && !edgesChanged && uiSeriesCache.markers) {
      markers = uiSeriesCache.markers;
      markersChanged = false;
    } else {
      markers = [
        ...buildMarkers(frame.signalEdgesSoFar, windowPoints),
        // milestones дорогие (~50ms) — на light/смене порогов пропускаем
        ...(skipMilestones ? [] : buildPnlMilestoneMarkers(
          frame.signalEdgesSoFar,
          allPoints,
          windowPoints,
          frame.cursorIndex,
        )),
      ].sort((a, b) => a.time - b.time || String(a.text).localeCompare(String(b.text)));
    }

    const trades = buildTradeSegments(frame.signalEdgesSoFar, currentPoint).map((t) => ({
      id: t.id,
      entryTime: t.entryTime,
      entryZ: t.entryZ,
      exitTime: t.exitTime,
      exitZ: t.exitZ,
      open: t.open,
    }));

    const equityTotal = buildEquitySeries(
      allPoints,
      frame.signalEdgesSoFar,
      windowPoints,
      frame.cursorIndex,
    );
    const equity = pnlMode === 'trade'
      ? buildPerTradeEquitySeries(
        allPoints,
        frame.signalEdgesSoFar,
        windowPoints,
        frame.cursorIndex,
      )
      : equityTotal;
    const deltaPp = buildDeltaPpSeries(
      allPoints,
      frame.signalEdgesSoFar,
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
        maxVisibleBars: visibleBarsOnScreen(visibleDays),
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

    updateOpenTradeOverlay(frame.signalEdgesSoFar, currentPoint, frame.position);

    const lastPt = frame.visiblePoints.length
      ? frame.visiblePoints[frame.visiblePoints.length - 1]
      : null;
    const z = lastPt?.zScore ?? null;
    const spread = lastPt?.spreadPercent ?? null;
    const zText = formatStatusBarMetricStat('z', z != null && Number.isFinite(z) ? z : null);
    const spText = formatStatusBarMetricStat(
      'spread',
      spread != null && Number.isFinite(spread) ? spread : null,
    );
    rebuildStatusBarMetricDists(frame.visiblePoints);
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
    if (typeof classifySpreadRegime === 'function' && frame.visiblePoints?.length) {
      const r = classifySpreadRegime(frame.visiblePoints);
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
    $('status').innerHTML =
      `${frame.barLabel}   ·   Z ${zHover} · Спред ${spHover}${regimeHtml}   ·   ${frame.position}   ·   сигн. ${frame.signalEdgesSoFar.length}   ·   пороги ±${thresholds().entry} / ±${thresholds().exit}${tpText}${riskText}${capText}${slipText}`;

    const pct = Math.round(engine.progressFraction * 100);
    $('progress').textContent = `${pct}%`;
    if (!scrubbing) $('scrub').value = Math.round(engine.progressFraction * 1000);

    // Таблица сделок — самый тяжёлый DOM; на ±1 бар только при новом сигнале / открытой позиции (throttle)
    if (!light || edgesChanged || afterParams) {
      refreshTradesTable();
    } else if (frame.position !== 'Flat') {
      if (tradesRefreshTimer) clearTimeout(tradesRefreshTimer);
      tradesRefreshTimer = setTimeout(() => {
        tradesRefreshTimer = null;
        refreshTradesTable();
      }, 120);
    }
  }

  async function loadBars(csv) {
    const startDate = $('startDate').value;
    let url = `/api/bars?csv=${encodeURIComponent(csv)}`;
    if (startDate) url += `&start=${encodeURIComponent(startDate)}`;
    const title = $('loadingTitle');
    const sub = $('loadingSub');
    if (title) title.textContent = `Загрузка ${csv}…`;
    if (sub) sub.textContent = 'запрос /api/bars';
    const started = Date.now();
    const tick = setInterval(() => {
      if (sub) sub.textContent = `запрос /api/bars · ${Math.round((Date.now() - started) / 1000)} с`;
    }, 500);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 45000);
    try {
      const res = await fetch(url, { signal: controller.signal });
      if (!res.ok) throw new Error(await res.text());
      if (sub) sub.textContent = 'разбор JSON…';
      const data = await res.json();
      if (sub) sub.textContent = `готово: ${data.count || 0} баров за ${Math.round((Date.now() - started) / 1000)} с`;
      return data;
    } catch (e) {
      if (e.name === 'AbortError') {
        throw new Error('Таймаут загрузки (45 с). Перезапустите run-replay-web.bat или проверьте MOEX/сеть.');
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
      $('meta').innerHTML = `TATN/TATNP · ${data.count} баров · ${csvSafe} · ${src} · ${netBadge}`;
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
      setMoexRefreshStatus(
        last ? `ОК · ${count} бар. · ${last}` : `ОК · ${count} бар.`,
        'ok',
      );
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
      const frac = $('scrub').value / 1000;
      const minC = engine.minCursor;
      const span = Math.max(0, engine.lastIndex - minC);
      engine.seekTo(Math.round(minC + frac * span));
      refreshUi();
    });
    $('scrub').addEventListener('change', () => { scrubbing = false; });

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
      saveSetting(LS.slippageSpreadPts, v);
      applyStrategyParamsChange();
    };
    $('slipSel')?.addEventListener('change', applySlipFromUi);
    $('slipSel')?.addEventListener('blur', applySlipFromUi);

    $('startDate').addEventListener('change', async () => {
      saveSetting(LS.startDate, $('startDate').value);
      $('loading').classList.remove('hidden');
      $('app').classList.add('hidden');
      scrollRestored = false;
      summaryScrollRestored = false;
      riskCompareScrollRestored = false;
      monthlyPnlScrollRestored = false;
      await bootstrap($('csvSel').value);
    });

    $('csvSel').addEventListener('change', async () => {
      saveSetting(LS.csv, $('csvSel').value);
      $('loading').classList.remove('hidden');
      $('app').classList.add('hidden');
      scrollRestored = false;
      summaryScrollRestored = false;
      riskCompareScrollRestored = false;
      monthlyPnlScrollRestored = false;
      await bootstrap($('csvSel').value);
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
