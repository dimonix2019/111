/** MOEX Bar Replay — UI + TradingView chart */
(function () {
  let allPoints = [];
  let engine = null;
  let chart = null;
  let playing = false;
  let speed = 1;
  let visibleDays = 30;
  let timer = null;
  let scrubbing = false;

  const $ = (id) => document.getElementById(id);

  async function loadBars(csv) {
    const res = await fetch(`/api/bars?csv=${encodeURIComponent(csv)}`);
    if (!res.ok) throw new Error(await res.text());
    return res.json();
  }

  function thresholds() {
    return {
      entry: parseFloat($('entrySel').value),
      exit: parseFloat($('exitSel').value),
    };
  }

  function rebuildEngine() {
    const { entry, exit } = thresholds();
    engine = new BarReplayEngine(allPoints, entry, exit, Z_SCORE_ROLLING_MIN_BARS);
  }

  function refreshUi() {
    if (!engine || !chart) return;
    const frame = engine.frameAtCursor();
    const range = barReplayVisibleIndexRange(allPoints, frame.cursorIndex, visibleDays);
    const windowPoints = allPoints.slice(range.start, range.end + 1);
    const candles = buildZCandles(windowPoints);
    const markers = buildMarkers(frame.signalEdgesSoFar, allPoints);
    const payload = buildChartPayload(
      candles,
      thresholds().entry,
      thresholds().exit,
      markers,
      [],
      playing,
    );
    chart.setReplay(payload);

    const z = frame.visiblePoints.length ? frame.visiblePoints[frame.visiblePoints.length - 1].zScore : null;
    const zText = z != null ? (z >= 0 ? `+${z.toFixed(2)}` : z.toFixed(2)) : '—';
    $('status').textContent =
      `${frame.barLabel}   ·   Z ${zText}   ·   ${frame.position}   ·   сигн. ${frame.signalEdgesSoFar.length}   ·   пороги ±${thresholds().entry} / ±${thresholds().exit}`;

    const pct = Math.round(engine.progressFraction * 100);
    $('progress').textContent = `${pct}%`;
    if (!scrubbing) $('scrub').value = Math.round(engine.progressFraction * 1000);

    const tbody = $('tradesBody');
    tbody.innerHTML = '';
    for (const row of buildTradeRows(frame.signalEdgesSoFar)) {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td>${row.id}</td>
        <td class="${row.side === 'Long' ? 'side-long' : 'side-short'}">${row.side}</td>
        <td>${row.entryTime}</td>
        <td>${row.entryZ >= 0 ? '+' : ''}${row.entryZ.toFixed(2)}</td>
        <td>${row.exitTime}</td>
        <td>${row.exitZ != null ? (row.exitZ >= 0 ? '+' : '') + row.exitZ.toFixed(2) : '—'}</td>
        <td>${row.status}</td>`;
      tbody.appendChild(tr);
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
      const minC = Math.min(Z_SCORE_ROLLING_MIN_BARS, engine.lastIndex);
      const span = Math.max(0, engine.lastIndex - minC);
      engine.seekTo(Math.round(minC + frac * span));
      refreshUi();
    });
    $('scrub').addEventListener('change', () => { scrubbing = false; });

    $('entrySel').addEventListener('change', () => { rebuildEngine(); refreshUi(); });
    $('exitSel').addEventListener('change', () => { rebuildEngine(); refreshUi(); });

    $('csvSel').addEventListener('change', async () => {
      $('loading').classList.remove('hidden');
      $('app').classList.add('hidden');
      await bootstrap($('csvSel').value);
    });

    document.querySelectorAll('#periodChips .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('#periodChips .chip').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        visibleDays = parseInt(btn.dataset.days, 10);
        refreshUi();
      });
    });

    const speedContainer = $('speedChips');
    BAR_REPLAY_SPEEDS.forEach((s) => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'chip' + (s === 1 ? ' active' : '');
      btn.textContent = s < 1 ? `${s}×` : `${s}×`;
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
      $('meta').textContent = `TATN/TATNP · ${data.count} баров · ${data.csv}`;
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
    bindControls();
    bootstrap($('csvSel').value);
  });
})();
