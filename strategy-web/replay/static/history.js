/** История — закрытые сделки (таблица как в Тесте, но столбцы/сортировка независимы) */
(function () {
  const LS_COLS = 'moexReplay.historyTradeColumns';
  const LS_COLS_MIG = 'moexReplay.historyTradeColumnsMig';
  const LS_SORT_COL = 'moexReplay.historyTradeSortCol';
  const LS_SORT_DIR = 'moexReplay.historyTradeSortDir';
  const LS_SCROLL_TOP = 'moexReplay.historyScrollTop';
  const LS_SCROLL_LEFT = 'moexReplay.historyScrollLeft';
  const LS_DATE_FROM = 'moexReplay.historyDateFrom';
  const LS_DATE_TO = 'moexReplay.historyDateTo';
  const LS_DATE_PRESET = 'moexReplay.historyDatePreset';

  const HISTORY_PRESETS = new Set(['today', 'week', 'month', '3m', '6m', 'year', 'all', 'custom']);

  /** @type {string} */
  let dateFrom = '';
  /** @type {string} */
  let dateTo = '';
  /** @type {string} */
  let datePreset = 'week';
  let pollTimer = null;
  let refreshAbort = null;
  let refreshSeq = 0;
  let visibleTradeColumns = [...TRADE_COLUMNS_DEFAULT];
  let tradeSortColumn = 'Index';
  let tradeSortDir = 'desc';
  let lastRows = [];
  let applyingPreset = false;

  const $ = (id) => document.getElementById(id);

  function pad2(n) {
    return String(n).padStart(2, '0');
  }

  /** Локальная дата YYYY-MM-DD (МСК-машина пользователя). */
  function formatYmd(d) {
    return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
  }

  function startOfWeekMonday(d) {
    const x = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    const day = x.getDay(); // 0=Sun … 6=Sat
    const diff = day === 0 ? -6 : 1 - day;
    x.setDate(x.getDate() + diff);
    return x;
  }

  /** Диапазон для пресета. all → пустые даты. */
  function rangeForPreset(preset, now = new Date()) {
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const to = formatYmd(today);
    switch (preset) {
      case 'today':
        return { from: to, to };
      case 'week': {
        // Rolling last 7 calendar days (today inclusive). Calendar Mon→today
        // collapses to a single day on Mondays and hid the week's trades.
        const from = new Date(today);
        from.setDate(from.getDate() - 6);
        return { from: formatYmd(from), to };
      }
      case 'month':
        return { from: formatYmd(new Date(today.getFullYear(), today.getMonth(), 1)), to };
      case '3m':
        return { from: formatYmd(new Date(today.getFullYear(), today.getMonth() - 2, 1)), to };
      case '6m':
        return { from: formatYmd(new Date(today.getFullYear(), today.getMonth() - 5, 1)), to };
      case 'year':
        return { from: formatYmd(new Date(today.getFullYear(), 0, 1)), to };
      case 'all':
        return { from: '', to: '' };
      default:
        return { from: dateFrom, to: dateTo };
    }
  }

  function persistDateFilter() {
    saveSetting(LS_DATE_FROM, dateFrom || '');
    saveSetting(LS_DATE_TO, dateTo || '');
    saveSetting(LS_DATE_PRESET, datePreset || 'custom');
  }

  function syncDateInputs() {
    const fromEl = $('historyDateFrom');
    const toEl = $('historyDateTo');
    if (fromEl) fromEl.value = dateFrom || '';
    if (toEl) toEl.value = dateTo || '';
  }

  function syncPresetChips() {
    document.querySelectorAll('#historyPeriodChips .chip').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.preset === datePreset);
    });
  }

  function applyPreset(preset, { refreshNow = true } = {}) {
    if (!HISTORY_PRESETS.has(preset) || preset === 'custom') {
      datePreset = 'custom';
      persistDateFilter();
      syncPresetChips();
      if (refreshNow) refresh().catch((e) => alert(e.message));
      return;
    }
    applyingPreset = true;
    datePreset = preset;
    const r = rangeForPreset(preset);
    dateFrom = r.from;
    dateTo = r.to;
    syncDateInputs();
    syncPresetChips();
    persistDateFilter();
    applyingPreset = false;
    if (refreshNow) refresh().catch((e) => alert(e.message));
  }

  function onManualDateChange() {
    if (applyingPreset) return;
    const fromEl = $('historyDateFrom');
    const toEl = $('historyDateTo');
    dateFrom = (fromEl && fromEl.value) || '';
    dateTo = (toEl && toEl.value) || '';
    if (dateFrom && dateTo && dateFrom > dateTo) {
      const tmp = dateFrom;
      dateFrom = dateTo;
      dateTo = tmp;
      syncDateInputs();
    }
    datePreset = 'custom';
    syncPresetChips();
    persistDateFilter();
    refresh().catch((e) => alert(e.message));
  }

  function periodLabel() {
    if (datePreset === 'all' || (!dateFrom && !dateTo)) return 'всё';
    if (dateFrom && dateTo && dateFrom === dateTo) return dateFrom;
    if (dateFrom && dateTo) return `${dateFrom} → ${dateTo}`;
    if (dateFrom) return `с ${dateFrom}`;
    if (dateTo) return `по ${dateTo}`;
    return '—';
  }

  /** Как migrateTradeColumnsOnce, но свой LS — не трогаем настройки Теста. */
  function migrateHistoryColumnsOnce(keys) {
    let next = [...keys];
    const insertAfter = (arr, key, after) => {
      if (arr.includes(key)) return arr;
      if (!TRADE_COLUMN_KEYS.includes(key)) return arr;
      const i = arr.indexOf(after);
      const out = [...arr];
      out.splice(i >= 0 ? i + 1 : out.length, 0, key);
      return out;
    };
    const ver = parseInt(localStorage.getItem(LS_COLS_MIG) || '0', 10) || 0;
    if (ver >= TRADE_COLUMNS_MIG_VERSION) return next;
    if (ver < 5) {
      next = insertAfter(next, 'Lots', 'Direction');
      next = insertAfter(next, 'Source', 'Lots');
    }
    if (ver < 6) {
      next = insertAfter(next, 'Slip', 'SpreadDelta');
    }
    if (ver < 7) {
      next = insertAfter(next, 'AccountAfter', 'Net');
    }
    if (ver < 8) {
      next = regroupTradeColumnKeys(next);
    }
    if (ver < 9) {
      next = insertAfter(next, 'AccountBefore', 'Overnight');
      next = insertAfter(next, 'AccountAfter', 'AccountBefore');
      next = regroupTradeColumnKeys(next);
    }
    if (ver < 10) {
      // ModelNet («Оц.») больше нет — не вставляем
    }
    if (ver < 11) {
      next = next.filter((k) => k !== 'ModelNet');
      next = regroupTradeColumnKeys(next);
    }
    if (ver < 12) {
      next = insertAfter(next, 'Invest', 'Overnight');
      next = regroupTradeColumnKeys(next);
    }
    try { localStorage.setItem(LS_COLS_MIG, String(TRADE_COLUMNS_MIG_VERSION)); } catch (_) { /* */ }
    return next;
  }

  function loadPrefs() {
    try {
      const cols = localStorage.getItem(LS_COLS);
      visibleTradeColumns = migrateHistoryColumnsOnce(decodeTradeColumns(cols));
      if (cols !== encodeTradeColumns(visibleTradeColumns)) {
        localStorage.setItem(LS_COLS, encodeTradeColumns(visibleTradeColumns));
      }
      const sortCol = localStorage.getItem(LS_SORT_COL);
      const sortDir = localStorage.getItem(LS_SORT_DIR);
      if (sortCol && TRADE_COLUMN_KEYS.includes(sortCol)) tradeSortColumn = sortCol;
      if (sortDir === 'asc' || sortDir === 'desc') tradeSortDir = sortDir;

      const savedPreset = localStorage.getItem(LS_DATE_PRESET) || '';
      const savedFrom = localStorage.getItem(LS_DATE_FROM);
      const savedTo = localStorage.getItem(LS_DATE_TO);
      if (savedPreset && HISTORY_PRESETS.has(savedPreset) && savedPreset !== 'custom') {
        datePreset = savedPreset;
        const r = rangeForPreset(savedPreset);
        dateFrom = r.from;
        dateTo = r.to;
      } else if (savedFrom != null || savedTo != null || savedPreset === 'custom') {
        datePreset = 'custom';
        dateFrom = savedFrom || '';
        dateTo = savedTo || '';
      } else {
        datePreset = 'week';
        const r = rangeForPreset('week');
        dateFrom = r.from;
        dateTo = r.to;
      }
      syncDateInputs();
      syncPresetChips();
    } catch (_) { /* private mode */ }
  }

  function saveSetting(key, value) {
    try { localStorage.setItem(key, String(value)); } catch (_) { /* */ }
  }

  async function api(path, opts = {}) {
    const res = await fetch(path, opts);
    const text = await res.text();
    let data;
    try { data = text ? JSON.parse(text) : {}; } catch { data = { detail: text }; }
    if (!res.ok) throw new Error(typeof data.detail === 'string' ? data.detail : (data.detail || text));
    return data;
  }

  function persistColumns() {
    saveSetting(LS_COLS, encodeTradeColumns(visibleTradeColumns));
  }

  function renderColumnPicker() {
    const picker = $('historyColumnPicker');
    if (!picker) return;
    picker.innerHTML = '';
    const allBtn = document.createElement('button');
    allBtn.type = 'button';
    allBtn.className = 'chip col-chip';
    allBtn.textContent = 'Все';
    allBtn.title = 'Сбросить порядок и показать все столбцы';
    allBtn.addEventListener('click', () => {
      visibleTradeColumns = [...TRADE_COLUMNS_DEFAULT];
      persistColumns();
      renderColumnPicker();
      paintTable(lastRows);
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
          (keys) => { visibleTradeColumns = keys; persistColumns(); },
          () => { renderColumnPicker(); paintTable(lastRows); },
        );
      }
      btn.addEventListener('click', () => {
        visibleTradeColumns = toggleTradeColumnKey(visibleTradeColumns, col.key);
        persistColumns();
        renderColumnPicker();
        paintTable(lastRows);
      });
      picker.appendChild(btn);
    });
  }

  function paintTable(rows) {
    const head = $('historyClosedHead');
    const tbody = $('historyClosedBody');
    const tfoot = $('historyClosedFoot');
    if (!head || !tbody) return;

    const scrollEl = $('historyScroll');
    const prevTop = scrollEl ? scrollEl.scrollTop : 0;
    const prevLeft = scrollEl ? scrollEl.scrollLeft : 0;

    const sortedRows = [...rows].sort(
      (a, b) => compareTradeRows(a, b, tradeSortColumn, tradeSortDir),
    );
    const visibleCols = resolveVisibleTradeColumns(visibleTradeColumns);

    head.innerHTML = '';
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
        (keys) => { visibleTradeColumns = keys; persistColumns(); },
        () => { renderColumnPicker(); paintTable(lastRows); },
        (e) => {
          e.stopPropagation();
          if (tradeSortColumn === col.key) {
            tradeSortDir = tradeSortDir === 'asc' ? 'desc' : 'asc';
          } else {
            tradeSortColumn = col.key;
            tradeSortDir = ['Index', 'Entry', 'Exit', 'EntryZ', 'ExitZ', 'Duration', 'Hit1', 'Hit2', 'Hit3', 'Lots', 'Source'].includes(col.key)
              ? 'asc'
              : 'desc';
          }
          saveSetting(LS_SORT_COL, tradeSortColumn);
          saveSetting(LS_SORT_DIR, tradeSortDir);
          paintTable(lastRows);
        },
      );
      head.appendChild(th);
    }

    tbody.innerHTML = '';
    if (!sortedRows.length) {
      const tr = document.createElement('tr');
      const td = document.createElement('td');
      td.colSpan = Math.max(1, visibleCols.length);
      td.textContent = 'пусто';
      tr.appendChild(td);
      tbody.appendChild(tr);
    } else {
      for (const row of sortedRows) {
        const tr = document.createElement('tr');
        if (row.riskRed) tr.classList.add('trade-row-red');
        for (const col of visibleCols) {
          const td = document.createElement('td');
          td.textContent = tradeCellValue(row, col.key);
          const cls = tradeCellClass(row, col.key);
          if (cls) td.className = cls;
          const tip = typeof tradeCellTitle === 'function' ? tradeCellTitle(row, col.key) : '';
          if (tip) td.title = tip;
          else if (col.hint) td.title = col.hint;
          tr.appendChild(td);
        }
        tbody.appendChild(tr);
      }
    }

    if (tfoot) {
      tfoot.innerHTML = '';
      const aggTr = document.createElement('tr');
      aggTr.className = 'history-agg-row';
      const summary = typeof buildTradeColumnSummary === 'function'
        ? buildTradeColumnSummary(sortedRows, visibleCols.map((c) => c.key))
        : [];
      for (let i = 0; i < visibleCols.length; i++) {
        const col = visibleCols[i];
        const cell = summary[i] || { text: '', cls: '', title: '', mode: null };
        const td = document.createElement('td');
        td.textContent = cell.text || '';
        if (cell.cls) td.className = cell.cls;
        const modeHint = cell.mode === 'sum'
          ? 'сумма'
          : cell.mode === 'avg'
            ? 'среднее'
            : cell.mode === 'count'
              ? 'количество'
              : cell.mode === 'last'
                ? 'последнее'
                : '';
        td.title = [col.title, modeHint, cell.title].filter(Boolean).join(' · ');
        if (cell.mode === 'sum') td.classList.add('history-agg-sum');
        if (cell.mode === 'avg') td.classList.add('history-agg-avg');
        if (cell.mode === 'count') td.classList.add('history-agg-count');
        if (cell.mode === 'last') td.classList.add('history-agg-last');
        td.style.minWidth = `${col.width}px`;
        aggTr.appendChild(td);
      }
      tfoot.appendChild(aggTr);
    }

    if (scrollEl) {
      requestAnimationFrame(() => {
        scrollEl.scrollTop = prevTop;
        scrollEl.scrollLeft = prevLeft;
      });
    }
  }

  function render(data) {
    const closed = data.closed || [];
    const settings = data.settings || {};
    const entry = Number(settings.entry_z);
    const entryThreshold = Number.isFinite(entry) && entry > 0 ? entry : 1.3;

    // Хронологический номер сделки (#) по времени входа; API отдаёт id DESC
    const chronological = [...closed].sort((a, b) => {
      const am = parseTradeMs(a.entry_time) || 0;
      const bm = parseTradeMs(b.entry_time) || 0;
      return am - bm;
    });
    lastRows = chronological.map((t, i) => liveClosedToTradeRow(t, i + 1, entryThreshold, settings));
    if (typeof applyAccountDeltaNetToRows === 'function') {
      applyAccountDeltaNetToRows(lastRows);
    }

    const withNet = lastRows.filter((r) => r.netValue != null && Number.isFinite(r.netValue));
    const wins = withNet.filter((r) => r.netValue > 0).length;
    const losses = withNet.filter((r) => r.netValue < 0).length;
    const flat = withNet.length - wins - losses;
    const sumNet = withNet.reduce((s, r) => s + r.netValue, 0);
    const sumText = typeof formatRub === 'function' ? formatRub(sumNet) : `${Math.round(sumNet)} ₽`;
    const wrText = withNet.length
      ? ` · ${wins}+/${losses}−` + (flat ? `/${flat}=` : '') + ` · Σ ${sumText}`
      : '';

    $('historyStatus').textContent =
      `Закрытых (${periodLabel()}): ${closed.length}` +
      wrText +
      (data.closed_total != null ? ` · всего ${data.closed_total}` : '') +
      (data.position ? ` · сейчас ${data.position}` : '');
    $('historyMeta').textContent = `История · ${periodLabel()}`;
    $('historyCount').textContent =
      `${closed.length} сделок` +
      (withNet.length ? ` · ${wins}+/${losses}−` : '') +
      (data.closed_total != null ? ` (из ${data.closed_total})` : '');

    paintTable(lastRows);
  }

  function buildPortfolioQuery() {
    const q = new URLSearchParams();
    q.set('lite', '1');
    if (dateFrom || dateTo) {
      if (dateFrom) q.set('date_from', dateFrom);
      if (dateTo) q.set('date_to', dateTo);
    } else {
      q.set('days', '0');
    }
    return q.toString();
  }

  async function refresh() {
    if (refreshAbort) {
      try { refreshAbort.abort(); } catch (_) { /* */ }
    }
    const ac = new AbortController();
    refreshAbort = ac;
    const seq = ++refreshSeq;
    try {
      const data = await api(`/api/portfolio?${buildPortfolioQuery()}`, { signal: ac.signal });
      if (seq !== refreshSeq) return;
      render(data);
    } catch (e) {
      if (e && (e.name === 'AbortError' || String(e.message || '').includes('abort'))) return;
      throw e;
    }
  }

  function startPoll() {
    stopPoll();
    pollTimer = setInterval(() => { refresh().catch(() => {}); }, 20000);
  }

  function stopPoll() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  }

  function restoreScroll() {
    const el = $('historyScroll');
    if (!el) return;
    const top = parseInt(localStorage.getItem(LS_SCROLL_TOP) || '0', 10) || 0;
    const left = parseInt(localStorage.getItem(LS_SCROLL_LEFT) || '0', 10) || 0;
    el.scrollTop = top;
    el.scrollLeft = left;
  }

  function onShow() {
    loadPrefs();
    renderColumnPicker();
    refresh()
      .then(() => {
        restoreScroll();
        startPoll();
      })
      .catch((e) => {
        $('historyStatus').textContent = `Ошибка: ${e.message}`;
      });
  }

  function onHide() { stopPoll(); }

  function bind() {
    loadPrefs();
    document.querySelectorAll('#historyPeriodChips .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        applyPreset(btn.dataset.preset || 'custom');
      });
    });
    $('historyDateFrom')?.addEventListener('change', onManualDateChange);
    $('historyDateTo')?.addEventListener('change', onManualDateChange);
    $('historyBtnColumns')?.addEventListener('click', () => {
      $('historyColumnPicker')?.classList.toggle('hidden');
    });
    const scrollEl = $('historyScroll');
    if (scrollEl && scrollEl.dataset.scrollBound !== '1') {
      scrollEl.dataset.scrollBound = '1';
      let timer = 0;
      scrollEl.addEventListener('scroll', () => {
        if (timer) clearTimeout(timer);
        timer = setTimeout(() => {
          saveSetting(LS_SCROLL_TOP, scrollEl.scrollTop | 0);
          saveSetting(LS_SCROLL_LEFT, scrollEl.scrollLeft | 0);
        }, 80);
      }, { passive: true });
    }
  }

  window.MoexHistory = { onShow, onHide, refresh, bind };
  window.MoexPortfolio = window.MoexHistory;

  document.addEventListener('DOMContentLoaded', bind);
})();
