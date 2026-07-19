/** MOEX Bar Replay — UI + TradingView chart + localStorage */
(function () {
  const LS = {
    startDate: 'moexReplay.startDate',
    entry: 'moexReplay.entry',
    exit: 'moexReplay.exit',
    period: 'moexReplay.period',
    csv: 'moexReplay.csv',
    tradesScrollLeft: 'moexReplay.tradesScrollLeft',
    tradesSummaryScrollTop: 'moexReplay.tradesSummaryScrollTop',
    tradeColumns: 'moexReplay.tradeColumns',
    tradesPanelWidth: 'moexReplay.tradesPanelWidth',
    pnlPaneHeight: 'moexReplay.pnlPaneHeight',
    deltaPaneHeight: 'moexReplay.deltaPaneHeight',
    pnlMode: 'moexReplay.pnlMode',
    notionalRub: 'moexReplay.notionalRub',
    tradeSortCol: 'moexReplay.tradeSortCol',
    tradeSortDir: 'moexReplay.tradeSortDir',
    riskFilter: 'moexReplay.riskFilter',
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

  let allPoints = [];
  let engine = null;
  let chart = null;
  let playing = false;
  let speed = 1;
  let visibleDays = 30;
  let timer = null;
  let scrubbing = false;
  let visibleTradeColumns = [...TRADE_COLUMNS_DEFAULT];
  let scrollRestored = false;
  let summaryScrollRestored = false;
  let selectedTradeId = null;
  let pnlMode = 'total';
  let tradeSortColumn = 'Index';
  let tradeSortDir = 'asc';
  /** all | no-red | only-red */
  let riskFilter = 'all';
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
    visibleTradeColumns = decodeTradeColumns(cols);
    const notional = localStorage.getItem(LS.notionalRub);
    if (notional) $('notionalSel').value = notional;
    setSimNotionalRub($('notionalSel').value);
    const sortCol = localStorage.getItem(LS.tradeSortCol);
    const sortDir = localStorage.getItem(LS.tradeSortDir);
    if (sortCol && TRADE_COLUMN_KEYS.includes(sortCol)) tradeSortColumn = sortCol;
    if (sortDir === 'asc' || sortDir === 'desc') tradeSortDir = sortDir;
    const rf = localStorage.getItem(LS.riskFilter);
    if (rf === 'all' || rf === 'no-red' || rf === 'only-red') riskFilter = rf;
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

  function secondaryPanesHeadHeight(stackEl) {
    const heads = stackEl.querySelectorAll('.pnl-head');
    let h = 0;
    heads.forEach((el) => { h += el.offsetHeight || 28; });
    return h || 56;
  }

  function secondaryChartHeightBounds(stackEl, otherHeight) {
    const headH = secondaryPanesHeadHeight(stackEl);
    const total = stackEl.clientHeight - 2 * CHART_SPLITTER_HEIGHT - headH;
    const min = Math.min(PNL_CHART_MIN, DELTA_CHART_MIN);
    const reserved = Math.max(min, otherHeight || 0);
    const max = Math.max(min, total - Z_PANE_MIN - reserved);
    return { min, max };
  }

  function applyPnlChartHeight(heightPx) {
    const stack = $('chartStack');
    if (!stack) return PNL_CHART_DEFAULT;
    const deltaH = parseInt(getComputedStyle(stack).getPropertyValue('--delta-chart-height'), 10)
      || loadDeltaChartHeight();
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
    const pnlH = parseInt(getComputedStyle(stack).getPropertyValue('--pnl-chart-height'), 10)
      || loadPnlChartHeight();
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

  function updatePnlModeChips() {
    document.querySelectorAll('#pnlModeChips .chip').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.pnlMode === pnlMode);
    });
    const label = $('pnlLabel');
    if (label) {
      label.textContent = pnlMode === 'trade'
        ? 'PnL сделки · % от вложения'
        : 'PnL общий · % от вложения';
    }
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
      const h = applyHeight(defaultHeight);
      saveSetting(saveKey, h);
    });

    window.addEventListener('resize', () => {
      const cssVar = chartElId === 'deltaChart' ? '--delta-chart-height' : '--pnl-chart-height';
      const current = parseInt(getComputedStyle(stack).getPropertyValue(cssVar), 10) || loadHeight();
      const h = applyHeight(current);
      saveSetting(saveKey, h);
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
      const current = parseInt(getComputedStyle(main).getPropertyValue('--trades-panel-width'), 10)
        || loadTradesPanelWidth();
      const w = applyTradesPanelWidth(current);
      saveSetting(LS.tradesPanelWidth, w);
    });
  }

  function thresholds() {
    return {
      entry: parseFloat($('entrySel').value),
      exit: parseFloat($('exitSel').value),
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

  function rebuildEngine() {
    const { entry, exit } = thresholds();
    const savedManual = engine?.manualEdges ? [...engine.manualEdges] : [];
    const savedSeq = engine?.manualSeq ?? 0;
    const savedCursor = engine?.cursor;
    engine = new BarReplayEngine(allPoints, entry, exit, computeMinCursor());
    engine.manualEdges = savedManual;
    engine.manualSeq = savedSeq;
    if (typeof savedCursor === 'number') {
      engine.seekTo(savedCursor);
    } else {
      engine.rebuildStateToCursor(engine.cursor);
    }
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

  /** Двойной клик по строке: центрировать графики на сделке без отката курсора (таблица не сжимается). */
  function jumpChartsToTrade(row) {
    if (!engine || !chart || !row) return;
    pausePlayback();
    const entryIdx = findIndexByTradeDate(row.entryDate);
    if (entryIdx < 0) return;
    const exitIdx = row.exitDate && row.exitDate !== '—'
      ? findIndexByTradeDate(row.exitDate)
      : entryIdx;
    const endIdx = exitIdx >= 0 ? exitIdx : entryIdx;
    const mid = Math.round((entryIdx + endIdx) / 2);
    chartFocusIndex = mid;

    // Курсор только вперёд — чтобы сделка уже была в edges; назад не откатываем
    if (engine.cursor < endIdx) {
      engine.seekTo(endIdx);
    }

    selectedTradeId = tradeBadgeForRow(row);
    chart.followReplayEdge(false);
    refreshUi();
    chart.selectTrade(selectedTradeId);
    chart.centerOnTrade(selectedTradeId);
    requestAnimationFrame(() => chart.centerOnTrade(selectedTradeId));
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

  function updateTradesSummary(allRows, visibleRows) {
    const grid = $('tradesSummaryGrid');
    const scrollEl = $('tradesSummaryScroll');
    if (!grid) return;
    const prevScrollTop = scrollEl ? scrollEl.scrollTop : 0;
    const summary = buildTradeSimSummary(visibleRows, getSimNotionalRub());
    const allSummary = buildTradeSimSummary(allRows, getSimNotionalRub());
    const hiddenRed = riskFilter === 'no-red'
      ? allSummary.redCount
      : 0;
    const items = [
      { label: 'Итого PnL', value: formatRub(summary.totalPnl), cls: pnlClass(summary.totalPnl), wide: true },
      { label: 'Доходность', value: `${summary.retPct >= 0 ? '+' : '−'}${Math.abs(summary.retPct).toFixed(1)}%`, cls: pnlClass(summary.retPct) },
      { label: 'Макс. просадка', value: summary.maxDd > 0 ? formatRub(-summary.maxDd) : '0', cls: summary.maxDd > 0 ? 'pnl-neg' : '' },
      { label: 'Сделки', value: `${summary.closedCount}${summary.openCount ? ` +${summary.openCount} откр.` : ''}` },
      { label: 'Win rate', value: `${summary.winCount}W / ${summary.lossCount}L · ${summary.winRate.toFixed(0)}%` },
      { label: 'Средний PnL', value: formatRub(summary.avgTrade), cls: pnlClass(summary.avgTrade) },
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
    }
    if (scrollEl) {
      if (!summaryScrollRestored) {
        const saved = parseInt(localStorage.getItem(LS.tradesSummaryScrollTop) || '0', 10);
        scrollEl.scrollTop = Number.isNaN(saved) ? 0 : saved;
        summaryScrollRestored = true;
      } else {
        scrollEl.scrollTop = prevScrollTop;
      }
    }
  }

  function syncRiskFilterChips() {
    document.querySelectorAll('#tradesRiskFilters .risk-filter').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.riskFilter === riskFilter);
    });
  }

  function refreshTradesTable() {
    if (!engine) return;
    const frame = engine.frameAtCursor();
    const { entry } = thresholds();
    const rows = buildTradeRows(frame.signalEdgesSoFar, entry, allPoints, frame.cursorIndex);
    const filtered = filterRowsByRisk(rows);
    const sortedRows = [...filtered].sort(
      (a, b) => compareTradeRows(a, b, tradeSortColumn, tradeSortDir),
    );

    const head = $('tradesHead');
    head.innerHTML = '';
    const visibleCols = TRADE_COLUMNS.filter((c) => visibleTradeColumns.includes(c.key));
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
      th.title = 'Сортировка';
      th.style.minWidth = `${col.width}px`;
      th.addEventListener('click', (e) => {
        e.stopPropagation();
        if (tradeSortColumn === col.key) {
          tradeSortDir = tradeSortDir === 'asc' ? 'desc' : 'asc';
        } else {
          tradeSortColumn = col.key;
          tradeSortDir = ['Index', 'Entry', 'Exit', 'Duration'].includes(col.key) ? 'asc' : 'desc';
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
      tr.title = 'Клик — выделить · двойной клик — перейти к сделке на графике';
      if (badge === selectedTradeId) tr.classList.add('trade-row-selected');
      if (row.riskRed) tr.classList.add('trade-row-red');
      tr.addEventListener('click', () => {
        selectedTradeId = badge;
        chart?.selectTrade(badge);
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
        tr.appendChild(td);
      }
      tbody.appendChild(tr);
    }

    updateTradesSummary(rows, filtered);
    syncRiskFilterChips();

    if (!scrollRestored) {
      const scrollEl = $('tradesScroll');
      const saved = parseInt(localStorage.getItem(LS.tradesScrollLeft) || '0', 10);
      scrollEl.scrollLeft = Number.isNaN(saved) ? 0 : saved;
      scrollRestored = true;
    }
  }

  function refreshUi() {
    if (!engine || !chart) return;
    const frame = engine.frameAtCursor();
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
    const markers = buildMarkers(frame.signalEdgesSoFar, windowPoints);
    const trades = buildTradeSegments(frame.signalEdgesSoFar, currentPoint).map((t) => ({
      id: t.id,
      entryTime: t.entryTime,
      entryZ: t.entryZ,
      exitTime: t.exitTime,
      exitZ: t.exitZ,
      open: t.open,
    }));
    const equity = pnlMode === 'trade'
      ? buildPerTradeEquitySeries(
        allPoints,
        frame.signalEdgesSoFar,
        windowPoints,
        frame.cursorIndex,
      )
      : buildEquitySeries(
        allPoints,
        frame.signalEdgesSoFar,
        windowPoints,
        frame.cursorIndex,
      );
    const deltaPp = buildDeltaPpSeries(
      allPoints,
      frame.signalEdgesSoFar,
      windowPoints,
      frame.cursorIndex,
    );
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
        pnlBasisRub: getSimNotionalRub(),
      },
    );
    chart.setReplay(payload);
    if (selectedTradeId) chart.selectTrade(selectedTradeId);

    updateOpenTradeOverlay(frame.signalEdgesSoFar, currentPoint, frame.position);

    const z = frame.visiblePoints.length ? frame.visiblePoints[frame.visiblePoints.length - 1].zScore : null;
    const zText = z != null ? (z >= 0 ? `+${z.toFixed(2)}` : z.toFixed(2)) : '—';
    $('status').textContent =
      `${frame.barLabel}   ·   Z ${zText}   ·   ${frame.position}   ·   сигн. ${frame.signalEdgesSoFar.length}   ·   пороги ±${thresholds().entry} / ±${thresholds().exit}`;

    const pct = Math.round(engine.progressFraction * 100);
    $('progress').textContent = `${pct}%`;
    if (!scrubbing) $('scrub').value = Math.round(engine.progressFraction * 1000);

    refreshTradesTable();
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
      chartFocusIndex = null;
      selectedTradeId = null;
      const src = data.source === 'sqlite' ? 'SQLite' : 'CSV';
      const net = data.online ? (data.refreshed ? ' · MOEX tail' : ' · online') : ' · offline';
      $('meta').textContent = `TATN/TATNP · ${data.count} баров · ${data.csv} · ${src}${net}`;
      rebuildEngine();
      chart?.followReplayEdge(true);
      applyTradesPanelWidth(loadTradesPanelWidth());
      applyDeltaChartHeight(loadDeltaChartHeight());
      applyPnlChartHeight(loadPnlChartHeight());
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
      }
    } catch (e) {
      if (background) {
        console.error(e);
        return;
      }
      if (title) title.textContent = 'Ошибка загрузки';
      if (sub) sub.textContent = e.message || String(e);
      else $('loading').textContent = `Ошибка: ${e.message}`;
      console.error(e);
    }
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
        $('btnPlay').textContent = '▶ Play';
        stopTimer();
      }
      refreshUi();
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
      $('btnPlay').textContent = '▶ Play';
      stopTimer();
    } else {
      snapChartsToReplayEdge();
      engine.play();
      playing = true;
      $('btnPlay').textContent = '⏸ Pause';
      startTimer();
    }
    refreshUi();
  }

  function pausePlayback() {
    playing = false;
    stopTimer();
    $('btnPlay').textContent = '▶ Play';
    engine?.pause();
  }

  function bindControls() {
    $('btnPlay').addEventListener('click', togglePlay);
    $('btnBack').addEventListener('click', () => {
      playing = false;
      stopTimer();
      $('btnPlay').textContent = '▶ Play';
      snapChartsToReplayEdge();
      engine.stepBackward();
      refreshUi();
    });
    $('btnFwd').addEventListener('click', () => {
      playing = false;
      stopTimer();
      $('btnPlay').textContent = '▶ Play';
      snapChartsToReplayEdge();
      engine.pause();
      engine.stepForward();
      refreshUi();
    });
    $('btnStart').addEventListener('click', () => {
      playing = false;
      stopTimer();
      $('btnPlay').textContent = '▶ Play';
      snapChartsToReplayEdge();
      engine.seekToStart();
      refreshUi();
    });
    $('btnEnd').addEventListener('click', () => {
      playing = false;
      stopTimer();
      $('btnPlay').textContent = '▶ Play';
      snapChartsToReplayEdge();
      engine.seekToEnd();
      refreshUi();
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
      $('btnPlay').textContent = '▶ Play';
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
      chartFocusIndex = null;
      rebuildEngine();
      refreshUi();
    });
    $('exitSel').addEventListener('change', () => {
      saveSetting(LS.exit, $('exitSel').value);
      chartFocusIndex = null;
      rebuildEngine();
      refreshUi();
    });

    const applyNotionalFromUi = () => {
      const v = setSimNotionalRub($('notionalSel').value);
      $('notionalSel').value = String(v);
      saveSetting(LS.notionalRub, v);
      refreshUi();
    };
    $('notionalSel').addEventListener('change', applyNotionalFromUi);
    $('notionalSel').addEventListener('blur', applyNotionalFromUi);

    $('startDate').addEventListener('change', async () => {
      saveSetting(LS.startDate, $('startDate').value);
      $('loading').classList.remove('hidden');
      $('app').classList.add('hidden');
      scrollRestored = false;
      summaryScrollRestored = false;
      await bootstrap($('csvSel').value);
    });

    $('csvSel').addEventListener('change', async () => {
      saveSetting(LS.csv, $('csvSel').value);
      $('loading').classList.remove('hidden');
      $('app').classList.add('hidden');
      scrollRestored = false;
      summaryScrollRestored = false;
      await bootstrap($('csvSel').value);
    });

    document.querySelectorAll('#periodChips .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('#periodChips .chip').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        visibleDays = parseInt(btn.dataset.days, 10);
        saveSetting(LS.period, visibleDays);
        chartFocusIndex = null;
        refreshUi();
      });
    });

    $('btnColumns').addEventListener('click', () => {
      $('columnPicker').classList.toggle('hidden');
    });

    document.querySelectorAll('#tradesRiskFilters .risk-filter').forEach((btn) => {
      btn.addEventListener('click', () => {
        riskFilter = btn.dataset.riskFilter || 'all';
        saveSetting(LS.riskFilter, riskFilter);
        refreshTradesTable();
      });
    });

    $('tradesScroll').addEventListener('scroll', () => {
      saveSetting(LS.tradesScrollLeft, $('tradesScroll').scrollLeft);
    });

    $('tradesSummaryScroll').addEventListener('scroll', () => {
      saveSetting(LS.tradesSummaryScrollTop, $('tradesSummaryScroll').scrollTop);
    });

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
    bindPnlModeToggle();
    bindControls();
    window.__moexReplayResize = () => chart?.resize();
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
