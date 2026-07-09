/** MOEX Bar Replay — UI + TradingView chart + localStorage */
(function () {
  const LS = {
    startDate: 'moexReplay.startDate',
    entry: 'moexReplay.entry',
    exit: 'moexReplay.exit',
    period: 'moexReplay.period',
    csv: 'moexReplay.csv',
    tradesScrollLeft: 'moexReplay.tradesScrollLeft',
    tradeColumns: 'moexReplay.tradeColumns',
  };

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

  const $ = (id) => document.getElementById(id);

  function loadSettings() {
    const entry = localStorage.getItem(LS.entry);
    const exit = localStorage.getItem(LS.exit);
    const period = localStorage.getItem(LS.period);
    const csv = localStorage.getItem(LS.csv);
    const startDate = localStorage.getItem(LS.startDate);
    const cols = localStorage.getItem(LS.tradeColumns);

    if (entry) $('entrySel').value = entry;
    if (exit) $('exitSel').value = exit;
    if (csv) $('csvSel').value = csv;
    if (startDate) $('startDate').value = startDate;
    if (period) {
      visibleDays = parseInt(period, 10) || 30;
      document.querySelectorAll('#periodChips .chip').forEach((b) => {
        b.classList.toggle('active', parseInt(b.dataset.days, 10) === visibleDays);
      });
    }
    visibleTradeColumns = decodeTradeColumns(cols);
  }

  function saveSetting(key, value) {
    if (value == null || value === '') localStorage.removeItem(key);
    else localStorage.setItem(key, String(value));
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
    engine = new BarReplayEngine(allPoints, entry, exit, computeMinCursor());
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

  function refreshTradesTable() {
    if (!engine) return;
    const frame = engine.frameAtCursor();
    const { entry } = thresholds();
    const rows = buildTradeRows(frame.signalEdgesSoFar, entry);

    const head = $('tradesHead');
    head.innerHTML = '';
    const visibleCols = TRADE_COLUMNS.filter((c) => visibleTradeColumns.includes(c.key));
    for (const col of visibleCols) {
      const th = document.createElement('th');
      th.textContent = col.title;
      th.style.minWidth = `${col.width}px`;
      head.appendChild(th);
    }

    const tbody = $('tradesBody');
    tbody.innerHTML = '';
    for (const row of rows) {
      const tr = document.createElement('tr');
      for (const col of visibleCols) {
        const td = document.createElement('td');
        td.textContent = tradeCellValue(row, col.key);
        const cls = tradeCellClass(row, col.key);
        if (cls) td.className = cls;
        tr.appendChild(td);
      }
      tbody.appendChild(tr);
    }

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
    const range = barReplayVisibleIndexRange(allPoints, frame.cursorIndex, visibleDays);
    const windowPoints = allPoints.slice(range.start, range.end + 1);
    const candles = buildZCandles(windowPoints);
    const markers = buildMarkers(frame.signalEdgesSoFar, windowPoints);
    const payload = buildChartPayload(
      candles,
      thresholds().entry,
      thresholds().exit,
      markers,
      [],
      playing,
      { windowWidth: 1 },
    );
    chart.setReplay(payload);

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
    const res = await fetch(url);
    if (!res.ok) throw new Error(await res.text());
    return res.json();
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

  function togglePlay() {
    if (playing) {
      playing = false;
      engine.pause();
      $('btnPlay').textContent = '▶ Play';
      stopTimer();
    } else {
      engine.play();
      playing = true;
      $('btnPlay').textContent = '⏸ Pause';
      startTimer();
    }
    refreshUi();
  }

  function bindControls() {
    $('btnPlay').addEventListener('click', togglePlay);
    $('btnBack').addEventListener('click', () => {
      playing = false;
      stopTimer();
      $('btnPlay').textContent = '▶ Play';
      engine.stepBackward();
      refreshUi();
    });
    $('btnFwd').addEventListener('click', () => {
      playing = false;
      stopTimer();
      $('btnPlay').textContent = '▶ Play';
      engine.pause();
      engine.stepForward();
      refreshUi();
    });
    $('btnStart').addEventListener('click', () => {
      playing = false;
      stopTimer();
      $('btnPlay').textContent = '▶ Play';
      engine.seekToStart();
      refreshUi();
    });
    $('btnEnd').addEventListener('click', () => {
      playing = false;
      stopTimer();
      $('btnPlay').textContent = '▶ Play';
      engine.seekToEnd();
      refreshUi();
    });

    $('scrub').addEventListener('input', () => {
      scrubbing = true;
      playing = false;
      stopTimer();
      $('btnPlay').textContent = '▶ Play';
      const frac = $('scrub').value / 1000;
      const minC = engine.minCursor;
      const span = Math.max(0, engine.lastIndex - minC);
      engine.seekTo(Math.round(minC + frac * span));
      refreshUi();
    });
    $('scrub').addEventListener('change', () => { scrubbing = false; });

    $('entrySel').addEventListener('change', () => {
      saveSetting(LS.entry, $('entrySel').value);
      rebuildEngine();
      refreshUi();
    });
    $('exitSel').addEventListener('change', () => {
      saveSetting(LS.exit, $('exitSel').value);
      rebuildEngine();
      refreshUi();
    });

    $('startDate').addEventListener('change', async () => {
      saveSetting(LS.startDate, $('startDate').value);
      $('loading').classList.remove('hidden');
      $('app').classList.add('hidden');
      scrollRestored = false;
      await bootstrap($('csvSel').value);
    });

    $('csvSel').addEventListener('change', async () => {
      saveSetting(LS.csv, $('csvSel').value);
      $('loading').classList.remove('hidden');
      $('app').classList.add('hidden');
      scrollRestored = false;
      await bootstrap($('csvSel').value);
    });

    document.querySelectorAll('#periodChips .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('#periodChips .chip').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        visibleDays = parseInt(btn.dataset.days, 10);
        saveSetting(LS.period, visibleDays);
        refreshUi();
      });
    });

    $('btnColumns').addEventListener('click', () => {
      $('columnPicker').classList.toggle('hidden');
    });

    $('tradesScroll').addEventListener('scroll', () => {
      saveSetting(LS.tradesScrollLeft, $('tradesScroll').scrollLeft);
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

  async function bootstrap(csv) {
    try {
      const data = await loadBars(csv);
      allPoints = data.bars;
      const src = data.source === 'sqlite' ? 'SQLite' : 'CSV';
      const net = data.online ? (data.refreshed ? ' · MOEX tail' : ' · online') : ' · offline';
      $('meta').textContent = `TATN/TATNP · ${data.count} баров · ${data.csv} · ${src}${net}`;
      rebuildEngine();
      $('loading').classList.add('hidden');
      $('app').classList.remove('hidden');
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
      if (!chart) {
        chart = new ReplayChart($('chart'));
      } else {
        chart.resize();
      }
      refreshUi();
      setTimeout(() => chart?.resize(), 100);
    } catch (e) {
      $('loading').textContent = `Ошибка: ${e.message}`;
      console.error(e);
    }
  }

  document.addEventListener('DOMContentLoaded', () => {
    loadSettings();
    renderColumnPicker();
    bindControls();
    bootstrap($('csvSel').value);
  });
})();
