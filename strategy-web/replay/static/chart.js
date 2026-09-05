/**
 * TradingView lightweight-charts — replay window pan + trade markers (parity z_chart.html).
 */
const TV = {
  bg: '#131722',
  grid: '#2a2e39',
  text: '#d1d4dc',
  muted: '#787b86',
  up: '#089981',
  down: '#f23645',
  selected: '#FACC15',
  pnlLine: '#60A5FA',
  deltaLine: '#26A69A',
  deltaTop: 'rgba(38, 166, 154, 0.45)',
  deltaBottom: 'rgba(38, 166, 154, 0.02)',
};

/** Зоны спреда — как trade.js SPREAD_REGIME_BAND_COLORS. */
const SPREAD_BAND_COLORS = {
  narrow: 'rgba(0, 188, 212, 0.20)',
  transition: 'rgba(183, 110, 45, 0.20)',
  wide: 'rgba(136, 14, 79, 0.22)',
};

const CHART_RIGHT_OFFSET_BARS = 18;
const CHART_INITIAL_RIGHT_MARGIN_IN_WINDOW = 0.18;
/** Одинаковые поля шкалы — иначе Z / Δпп / PnL расходятся по горизонтали. */
const CHART_SCALE_LEFT_W = 72;
const CHART_SCALE_RIGHT_W = 112;
const MARKER_HIT_RADIUS_PX = 48;
const MARKER_HIT_RADIUS_X_PX = 44;
const MARKER_ENTRY_HIT_RADIUS_X_PX = 56;

/** Панорама/зум как в TradingView (ЛКМ + drag, колесо). */
const CHART_SCROLL_SCALE = {
  handleScroll: {
    mouseWheel: true,
    pressedMouseMove: true,
    horzTouchDrag: true,
    vertTouchDrag: true,
  },
  handleScale: {
    axisPressedMouseMove: true,
    axisDoubleClickReset: true,
    mouseWheel: true,
    pinch: true,
  },
};

/** Почти безлимитный zoom-out: уместить весь период симуляции на экран. */
const CHART_TIME_SCALE_ZOOM = {
  minBarSpacing: 0.001,
  maxBarSpacing: 64,
};

/** Подписи оси/кроссхейра в MSK — как Trade / chart-frame (unix без сдвига данных). */
const CHART_TZ_MSK = 'Europe/Moscow';

function formatChartTickMsk(time) {
  if (typeof time !== 'number') return '';
  return new Date(time * 1000).toLocaleString('ru-RU', {
    timeZone: CHART_TZ_MSK,
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

function chartTimeOptsMsk(extraTimeScale = {}) {
  return {
    timeScale: {
      borderColor: TV.grid,
      timeVisible: true,
      secondsVisible: false,
      tickMarkFormatter: formatChartTickMsk,
      ...extraTimeScale,
    },
    localization: {
      locale: 'ru-RU',
      timeFormatter: formatChartTickMsk,
    },
  };
}

function applyReplayVisibleWindow(chart, candleCount, maxVisibleBars = 200) {
  if (!chart || candleCount < 1) return;
  const n = candleCount;
  const maxIndex = n - 1;
  const visibleBars = Math.min(Math.max(2, maxVisibleBars), n);
  const from = Math.max(0, maxIndex - visibleBars + 1);
  const to = maxIndex;
  const rightGapBars = Math.max(2, Math.ceil(visibleBars * CHART_INITIAL_RIGHT_MARGIN_IN_WINDOW));
  chart.timeScale().setVisibleLogicalRange({ from, to: to + rightGapBars });
}

class ReplayChart {
  constructor(container, opts = {}) {
    this.container = container;
    this.pnlContainer = opts.pnlContainer || document.getElementById('pnlChart');
    this.deltaContainer = opts.deltaContainer || document.getElementById('deltaChart');
    this.onSelectionChange = opts.onSelectionChange || null;
    this.chart = null;
    this.pnlChart = null;
    this.deltaChart = null;
    this.series = null;
    this.pnlSeries = null;
    this.pnlRubSeries = null;
    this.deltaSeries = null;
    this.highlightSeries = null;
    this.markersPlugin = null;
    this.deltaMarkersPlugin = null;
    this.priceLines = [];
    /** Baseline fills for spread-level mode (parity Trade desk). */
    this.spreadBands = { narrow: null, transition: null, wide: null };
    /** Линии коридора S (forming / formed) — как Trade desk. */
    this._corridorSeries = [];
    this._corridorDataFp = '';
    this._corridorWindowFp = '';
    this._primaryMetric = 'z';
    this._lastSpreadBandTimes = [];
    this._cascadeVlines = [];
    this._vlineLayer = null;
    this.pnlZeroLine = null;
    this.pnlMinLine = null;
    this.pnlMaxLine = null;
    this.deltaZeroLine = null;
    this.deltaMinLine = null;
    this.deltaMaxLine = null;
    this._lastEquityPct = [];
    this._lastEquityRub = [];
    this._equityFp = '';
    this._lastDeltaPp = [];
    this._pnlBasisRub = 10_000;
    /** total | trade | account — что рисовать на нижнем графике. */
    this._pnlChartMode = 'account';
    this._accountBaseRub = 10_000;
    this.replayCursorLine = null;
    this._resizeObserver = null;
    this._lastMaxVisibleBars = 200;
    this._lastCandleCount = 0;
    this._syncingRange = false;
    this._secondarySyncBound = false;
    /** Следовать правому краю (replay); после ручного pan — false. */
    this._followRightEdge = true;
    this._lastLogicalRange = null;

    this.lastMarkers = [];
    this.lastTrades = [];
    this.lastCandleTimes = [];
    this.selectedTradeId = null;
    this.selectedMarkerKey = null;
    this.hoverTradeId = null;
    this._clickBound = false;
    this._crosshairBound = false;
    this._crosshairSyncBound = false;
    this._crosshairSyncing = false;
    this._crosshairActiveSource = null;
    /** Unix-сек последнего активного перекрестия (для reapply после zoom/resize). */
    this._crosshairSyncTime = null;
    this._crosshairReapplyRaf = 0;
    this._spreadPriceByTime = new Map();
    this._pnlPriceByTime = new Map();
    this._deltaPriceByTime = new Map();
    this._pnlHoverDateBound = false;
    this._pnlHoverDateEl = null;
    this._refreshMarkersTimer = 0;
    this._guideLinesRaf = 0;
    this._pendingGuideRange = null;
    /** True while user is actively panning/zooming (range events streaming). */
    this._viewportInteracting = false;

    this._init();
  }

  _wrapWidth() {
    const wrap = this.container.closest('.chart-wrap');
    return Math.max(wrap?.clientWidth ?? 0, this.container.clientWidth, 400);
  }

  _measureZ() {
    const w = this._wrapWidth();
    const rect = this.container.getBoundingClientRect();
    const h = Math.max(1, Math.floor(rect.height));
    return { w, h };
  }

  _measurePnl() {
    if (!this.pnlContainer) return { w: 0, h: 0 };
    const w = this._wrapWidth();
    const rect = this.pnlContainer.getBoundingClientRect();
    const h = Math.max(1, Math.floor(rect.height));
    return { w, h };
  }

  _measureDelta() {
    if (!this.deltaContainer) return { w: 0, h: 0 };
    const w = this._wrapWidth();
    const rect = this.deltaContainer.getBoundingClientRect();
    const h = Math.max(1, Math.floor(rect.height));
    return { w, h };
  }

  _addSeriesOn(chart, seriesType, options) {
    if (typeof chart.addSeries === 'function') {
      return chart.addSeries(seriesType, options);
    }
    if (seriesType === LightweightCharts.CandlestickSeries && typeof chart.addCandlestickSeries === 'function') {
      return chart.addCandlestickSeries(options);
    }
    if (seriesType === LightweightCharts.LineSeries && typeof chart.addLineSeries === 'function') {
      return chart.addLineSeries(options);
    }
    if (seriesType === LightweightCharts.BaselineSeries && typeof chart.addBaselineSeries === 'function') {
      return chart.addBaselineSeries(options);
    }
    if (seriesType === LightweightCharts.AreaSeries && typeof chart.addAreaSeries === 'function') {
      return chart.addAreaSeries(options);
    }
    throw new Error('Unsupported lightweight-charts series API');
  }

  _addSeries(seriesType, options) {
    return this._addSeriesOn(this.chart, seriesType, options);
  }

  _init() {
    if (typeof LightweightCharts === 'undefined') {
      this.container.innerHTML = '<div class="chart-error">lightweight-charts не загружен</div>';
      return;
    }
    const opts = {
      autoSize: true,
      layout: {
        background: { type: LightweightCharts.ColorType?.Solid ?? undefined, color: TV.bg },
        textColor: TV.text,
        attributionLogo: false,
      },
      grid: { vertLines: { color: TV.grid }, horzLines: { color: TV.grid } },
      leftPriceScale: {
        visible: true,
        borderVisible: false,
        borderColor: TV.grid,
        minimumWidth: CHART_SCALE_LEFT_W,
      },
      rightPriceScale: {
        borderColor: TV.grid,
        minimumWidth: CHART_SCALE_RIGHT_W,
      },
      ...chartTimeOptsMsk({
        rightOffset: CHART_RIGHT_OFFSET_BARS,
        ...CHART_TIME_SCALE_ZOOM,
      }),
      crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
      ...CHART_SCROLL_SCALE,
    };
    if (opts.layout.background.type === undefined) {
      delete opts.layout.background.type;
    }
    this.chart = LightweightCharts.createChart(this.container, opts);
    // Bands first → under candles (как Trade spread pane).
    this._ensureSpreadBands({
      enter_wide: 6.1, exit_wide: 5.8, enter_narrow: 3.2, exit_narrow: 4.0,
    });
    this._ensureVlineOverlay();
    this.series = this._addSeries(LightweightCharts.CandlestickSeries, {
      upColor: TV.up,
      downColor: TV.down,
      borderUpColor: TV.up,
      borderDownColor: TV.down,
      borderVisible: true,
      wickUpColor: TV.up,
      wickDownColor: TV.down,
      wickVisible: true,
    });
    this._zLeftSpacer = this._addSeries(LightweightCharts.LineSeries, {
      color: 'rgba(0,0,0,0)',
      lineWidth: 0,
      priceScaleId: 'left',
      priceLineVisible: false,
      lastValueVisible: false,
      crosshairMarkerVisible: false,
    });
    this._zLeftSpacer.setData([]);
    this.highlightSeries = this._addSeries(LightweightCharts.LineSeries, {
      color: TV.selected,
      lineWidth: 2,
      priceLineVisible: false,
      lastValueVisible: false,
      crosshairMarkerVisible: false,
    });
    this.highlightSeries.setData([]);
    this._initDeltaChart();
    this._initPnlChart();
    this._bindResize();
    this._bindInteractions();
    window.__replayChart = this;
  }

  _resolveSpreadBandBounds(levels) {
    const lv = levels || {};
    const num = (v, fb) => {
      const n = Number(v);
      return Number.isFinite(n) ? n : fb;
    };
    const enterW = num(lv.enter_wide, 6.1);
    const exitW = num(lv.exit_wide, 5.8);
    const enterN = num(lv.enter_narrow, 3.2);
    const exitN = num(lv.exit_narrow, 4.0);
    const loHi = (a, b) => (a <= b ? { lo: a, hi: b } : { lo: b, hi: a });
    const narrow = loHi(enterN, exitN);
    const wide = loHi(exitW, enterW);
    const trans = loHi(exitN, exitW);
    return {
      narrowLo: narrow.lo,
      narrowHi: narrow.hi,
      transLo: trans.lo,
      transHi: trans.hi,
      wideLo: wide.lo,
      wideHi: wide.hi,
    };
  }

  _makeSpreadBand(basePrice, fillColor) {
    if (!this.chart || !LightweightCharts.BaselineSeries) return null;
    try {
      return this._addSeries(LightweightCharts.BaselineSeries, {
        baseValue: { type: 'price', price: basePrice },
        topLineColor: 'rgba(0,0,0,0)',
        topFillColor1: fillColor,
        topFillColor2: fillColor,
        bottomLineColor: 'rgba(0,0,0,0)',
        bottomFillColor1: 'rgba(0,0,0,0)',
        bottomFillColor2: 'rgba(0,0,0,0)',
        lineWidth: 1,
        lastValueVisible: false,
        priceLineVisible: false,
        crosshairMarkerVisible: false,
        autoscaleInfoProvider: () => null,
      });
    } catch (_) {
      return null;
    }
  }

  _ensureSpreadBands(levels) {
    if (!this.chart) return;
    const b = this._resolveSpreadBandBounds(levels);
    if (!this.spreadBands.narrow) {
      this.spreadBands.narrow = this._makeSpreadBand(b.narrowLo, SPREAD_BAND_COLORS.narrow);
      this.spreadBands.transition = this._makeSpreadBand(b.transLo, SPREAD_BAND_COLORS.transition);
      this.spreadBands.wide = this._makeSpreadBand(b.wideLo, SPREAD_BAND_COLORS.wide);
      return;
    }
    if (!this.spreadBands.narrow) return;
    try {
      this.spreadBands.narrow.applyOptions({
        baseValue: { type: 'price', price: b.narrowLo },
        topFillColor1: SPREAD_BAND_COLORS.narrow,
        topFillColor2: SPREAD_BAND_COLORS.narrow,
      });
      this.spreadBands.transition.applyOptions({
        baseValue: { type: 'price', price: b.transLo },
        topFillColor1: SPREAD_BAND_COLORS.transition,
        topFillColor2: SPREAD_BAND_COLORS.transition,
      });
      this.spreadBands.wide.applyOptions({
        baseValue: { type: 'price', price: b.wideLo },
        topFillColor1: SPREAD_BAND_COLORS.wide,
        topFillColor2: SPREAD_BAND_COLORS.wide,
      });
    } catch (_) { /* */ }
  }

  _updateSpreadBands(candles, levels, enabled) {
    if (!enabled) {
      try {
        this.spreadBands.narrow?.setData([]);
        this.spreadBands.transition?.setData([]);
        this.spreadBands.wide?.setData([]);
      } catch (_) { /* */ }
      this._lastSpreadBandTimes = [];
      return;
    }
    this._ensureSpreadBands(levels);
    const times = (candles || []).map((c) => c.time).filter((t) => t != null);
    this._lastSpreadBandTimes = times;
    const b = this._resolveSpreadBandBounds(levels);
    const mk = (value) => times.map((time) => ({ time, value }));
    try {
      this.spreadBands.narrow?.setData(times.length ? mk(b.narrowHi) : []);
      this.spreadBands.transition?.setData(times.length ? mk(b.transHi) : []);
      this.spreadBands.wide?.setData(times.length ? mk(b.wideHi) : []);
    } catch (_) { /* */ }
  }

  _clearCorridorSeries() {
    if (!this.chart) {
      this._corridorSeries = [];
      return;
    }
    for (const s of this._corridorSeries) {
      try { this.chart.removeSeries(s); } catch (_) { /* ignore */ }
    }
    this._corridorSeries = [];
  }

  _corridorLineStyle(raw) {
    const LS = LightweightCharts.LineStyle;
    if (raw === 1 || raw === 'dotted') return LS?.Dotted ?? 1;
    if (raw === 2 || raw === 'dashed') return LS?.Dashed ?? 2;
    if (raw === 3 || raw === 'largeDashed') return LS?.LargeDashed ?? 3;
    return LS?.Solid ?? 0;
  }

  _corridorLineTypeSteps() {
    return LightweightCharts.LineType?.WithSteps ?? 1;
  }

  _sanitizeCorridorPoints(pts) {
    const out = [];
    for (const p of pts || []) {
      if (!p || p.time == null || !Number.isFinite(Number(p.value))) continue;
      const t = Number(p.time);
      const v = Number(p.value);
      if (!out.length) {
        out.push({ time: t, value: v });
        continue;
      }
      const prev = out[out.length - 1];
      if (t < prev.time) continue;
      if (t === prev.time) {
        prev.value = v;
        continue;
      }
      out.push({ time: t, value: v });
    }
    return out;
  }

  _buildDayRangeMap(candlePts) {
    const map = new Map();
    for (const p of candlePts || []) {
      if (!p || p.time == null) continue;
      const d = new Date(p.time * 1000).toLocaleDateString('sv-SE', { timeZone: CHART_TZ_MSK });
      const prev = map.get(d);
      if (!prev) map.set(d, { from: p.time, to: p.time });
      else {
        if (p.time < prev.from) prev.from = p.time;
        if (p.time > prev.to) prev.to = p.time;
      }
    }
    return map;
  }

  _buildCorridorBoundPoints(rows, dayRangeMap, key, chartFirstSec, chartLastSec, liveVal, extendToLive) {
    const pts = [];
    const chartStart = Number(chartFirstSec);
    for (const row of rows || []) {
      const dkey = String(row.date || '').slice(0, 10);
      const range = dayRangeMap && dayRangeMap.get(dkey);
      if (!range) continue;
      if (range.to < chartStart) continue;
      const val = Number(row[key]);
      if (!Number.isFinite(val)) continue;
      let t0 = range.from;
      let t1 = range.to;
      if (t0 < chartStart) t0 = chartStart;
      if (t1 < t0) t1 = t0;
      pts.push({ time: t0, value: val });
      if (t1 > t0) pts.push({ time: t1, value: val });
    }
    if (extendToLive && Number.isFinite(liveVal) && Number.isFinite(chartLastSec)) {
      if (!pts.length) {
        pts.push({ time: chartFirstSec, value: liveVal });
        pts.push({ time: chartLastSec, value: liveVal });
      } else if (pts[pts.length - 1].time < chartLastSec) {
        pts.push({ time: chartLastSec, value: liveVal });
      } else {
        pts[pts.length - 1].value = liveVal;
      }
    }
    return this._sanitizeCorridorPoints(pts);
  }

  _corridorRowsForChart(history) {
    const out = [];
    let formedRun = 0;
    for (const row of history || []) {
      const ph = row && row.phase;
      if (ph !== 'forming' && ph !== 'formed') {
        formedRun = 0;
        continue;
      }
      let draw = ph;
      if (ph === 'formed') {
        formedRun += 1;
        if (formedRun <= 2) draw = 'forming';
      } else {
        formedRun = 0;
        draw = 'forming';
      }
      out.push(Object.assign({}, row, { phase: draw }));
    }
    return out;
  }

  _splitCorridorHistory(history) {
    const segments = [];
    let cur = null;
    for (const row of history || []) {
      const ph = row && row.phase;
      const segmentId = row && row.segment_id;
      if (ph !== 'forming' && ph !== 'formed') {
        if (cur) { segments.push(cur); cur = null; }
        continue;
      }
      if (!cur || cur.phase !== ph
        || (segmentId != null && cur.segmentId != null && segmentId !== cur.segmentId)) {
        if (cur) segments.push(cur);
        cur = { phase: ph, segmentId, rows: [] };
      }
      cur.rows.push(row);
    }
    if (cur) segments.push(cur);
    return segments;
  }

  _applyCorridorAutoscale(lo, hi, candles) {
    if (!this.series) return;
    const vals = [];
    for (const c of candles || []) {
      if (!c) continue;
      for (const k of ['low', 'high', 'close']) {
        const v = Number(c[k]);
        if (Number.isFinite(v)) vals.push(v);
      }
    }
    // 3 года = сотни тысяч значений; spread-аргументы переполняют стек JS.
    let minV = Number.isFinite(lo) ? lo : 0;
    let maxV = Number.isFinite(hi) ? hi : 1;
    if (vals.length) {
      minV = vals[0];
      maxV = vals[0];
      for (let i = 1; i < vals.length; i += 1) {
        if (vals[i] < minV) minV = vals[i];
        if (vals[i] > maxV) maxV = vals[i];
      }
    }
    if (Number.isFinite(lo) && Number.isFinite(hi)) {
      minV = Math.min(minV, lo, hi);
      maxV = Math.max(maxV, lo, hi);
    }
    const span = Math.max(maxV - minV, 0.5);
    const pad = Math.max(0.25, span * 0.08);
    try {
      this.series.applyOptions({
        autoscaleInfoProvider: () => ({
          priceRange: {
            minValue: minV - pad,
            maxValue: maxV + pad,
          },
        }),
      });
    } catch (_) { /* ignore */ }
  }

  /**
   * Коридор S на графике Теста — как Trade: оранжевый пунктир (формируется),
   * тонкая зелёная сплошная (сформирован).
   */
  _updateCorridorOnChart(corridor, candles, enabled) {
    this._clearCorridorSeries();
    if (!enabled || !this.chart || !candles || !candles.length) {
      try {
        this.series?.applyOptions({ autoscaleInfoProvider: undefined });
      } catch (_) { /* ignore */ }
      return;
    }
    const phase = String(corridor && corridor.phase || '');
    const liveLo = Number(corridor && corridor.lo);
    const liveHi = Number(corridor && corridor.hi);
    const currentActive = (phase === 'forming' || phase === 'formed')
      && Number.isFinite(liveLo) && Number.isFinite(liveHi);
    const sourceHistory = (corridor && corridor.history) || [];
    // Старые коридоры должны оставаться видимыми, даже если на последнем
    // баре текущего набора фаза уже none/broken.
    if (!currentActive && !sourceHistory.length) return;

    const styles = {
      forming: { color: '#fbbf24', width: 1, lineStyle: 1, marker: 'Ф' },
      formed: { color: '#34d399', width: 1, lineStyle: 0, marker: 'С' },
      wide: { color: '#fbbf24', width: 1, lineStyle: 2, marker: '' },
    };

    const firstSec = candles[0].time;
    const lastSec = candles[candles.length - 1].time;
    const dayRangeMap = this._buildDayRangeMap(candles);
    let rows = [...sourceHistory];
    if (currentActive) {
      const today = corridor.last_date
        || new Date().toLocaleDateString('sv-SE', { timeZone: CHART_TZ_MSK });
      const lastRow = rows[rows.length - 1];
      const todayRow = {
        date: today,
        lo: liveLo,
        hi: liveHi,
        phase,
        segment_id: lastRow && lastRow.segment_id,
      };
      if (lastRow && lastRow.date === today) rows[rows.length - 1] = todayRow;
      else rows.push(todayRow);
    }

    const isWide = String(corridor && corridor.kind || '') === 'wide';
    const segments = this._splitCorridorHistory(
      isWide ? rows : this._corridorRowsForChart(rows),
    );
    if (!segments.length) return;

    const paint = (style, data) => {
      if (!data || data.length < 2) return null;
      const s = this._addSeries(LightweightCharts.LineSeries, {
        color: style.color,
        lineWidth: style.width,
        lineStyle: this._corridorLineStyle(style.lineStyle),
        lineType: this._corridorLineTypeSteps(),
        lastValueVisible: false,
        priceLineVisible: false,
        crosshairMarkerVisible: false,
        autoscaleInfoProvider: () => null,
      });
      if (!s) return null;
      try {
        s.setData(data);
      } catch (err) {
        try { this.chart.removeSeries(s); } catch (_) { /* ignore */ }
        console.warn('test corridor setData failed', err);
        return null;
      }
      this._corridorSeries.push(s);
      return s;
    };

    let anyOk = false;
    segments.forEach((seg, idx) => {
      const style = isWide ? styles.wide : styles[seg.phase];
      if (!style) return;
      const isLast = currentActive && idx === segments.length - 1;
      const loData = this._buildCorridorBoundPoints(
        seg.rows, dayRangeMap, 'lo', firstSec, lastSec, liveLo, isLast,
      );
      const hiData = this._buildCorridorBoundPoints(
        seg.rows, dayRangeMap, 'hi', firstSec, lastSec, liveHi, isLast,
      );
      const loS = paint(style, loData);
      const hiS = paint(style, hiData);
      if (loS || hiS) anyOk = true;
      if (loS && loData[0] && idx === 0) {
        try {
          loS.setMarkers([{
            time: loData[0].time,
            position: 'aboveBar',
            color: style.color,
            shape: 'circle',
            text: style.marker || '',
          }]);
        } catch (_) { /* ignore */ }
      }
    });

    if (anyOk && currentActive) this._applyCorridorAutoscale(liveLo, liveHi, candles);
    try {
      window.__testCorridorChartDebug = {
        phase,
        currentActive,
        liveLo,
        liveHi,
        history: sourceHistory.length,
        series: this._corridorSeries.length,
        ts: Date.now(),
      };
    } catch (_) { /* ignore */ }
  }

  _initDeltaChart() {
    if (!this.deltaContainer) return;
    const bgType = LightweightCharts.ColorType?.Solid;
    const layout = {
      background: bgType ? { type: bgType, color: TV.bg } : { color: TV.bg },
      textColor: TV.muted,
      attributionLogo: false,
    };
    this.deltaChart = LightweightCharts.createChart(this.deltaContainer, {
      autoSize: false,
      layout,
      grid: {
        vertLines: { visible: false },
        horzLines: { color: TV.grid },
      },
      rightPriceScale: {
        visible: true,
        borderVisible: false,
        borderColor: TV.grid,
        minimumWidth: CHART_SCALE_RIGHT_W,
      },
      leftPriceScale: {
        visible: true,
        borderColor: TV.grid,
        minimumWidth: CHART_SCALE_LEFT_W,
      },
      ...chartTimeOptsMsk({
        visible: false,
        borderVisible: false,
        rightOffset: CHART_RIGHT_OFFSET_BARS,
        ...CHART_TIME_SCALE_ZOOM,
      }),
      crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
      ...CHART_SCROLL_SCALE,
    });

    const histType = LightweightCharts.HistogramSeries;
    if (typeof this.deltaChart.addSeries === 'function' && histType) {
      this.deltaSeries = this._addSeriesOn(this.deltaChart, histType, {
        color: TV.deltaLine,
        base: 0,
        priceScaleId: 'left',
        priceLineVisible: false,
        lastValueVisible: true,
        priceFormat: {
          type: 'custom',
          minMove: 0.01,
          formatter: (v) => ReplayChart._formatDeltaPp(v),
          tickmarksFormatter: (prices) => prices.map((p) => ReplayChart._formatDeltaPp(p)),
        },
      });
    } else {
      this.deltaSeries = this._addSeriesOn(this.deltaChart, LightweightCharts.AreaSeries, {
        lineColor: TV.deltaLine,
        topColor: TV.deltaTop,
        bottomColor: TV.deltaBottom,
        lineWidth: 2,
        priceScaleId: 'left',
        priceLineVisible: false,
        lastValueVisible: true,
        priceFormat: {
          type: 'custom',
          minMove: 0.01,
          formatter: (v) => ReplayChart._formatDeltaPp(v),
          tickmarksFormatter: (prices) => prices.map((p) => ReplayChart._formatDeltaPp(p)),
        },
      });
    }

    this.deltaZeroLine = this.deltaSeries.createPriceLine({
      price: 0,
      color: '#616161',
      lineWidth: 1,
      lineStyle: LightweightCharts.LineStyle.Dashed,
      axisLabelVisible: true,
      title: '0.00',
    });
    this.deltaSeries.setData([]);
    this._deltaRightSpacer = this._addSeriesOn(this.deltaChart, LightweightCharts.LineSeries, {
      color: 'rgba(0,0,0,0)',
      lineWidth: 0,
      priceScaleId: 'right',
      priceLineVisible: false,
      lastValueVisible: false,
      crosshairMarkerVisible: false,
    });
    this._deltaRightSpacer.setData([]);
  }

  /** Формат как столбец Δпп в таблице: +0.36 */
  static _formatDeltaPp(v) {
    if (typeof v !== 'number' || Number.isNaN(v)) return '—';
    if (Math.abs(v) < 1e-9) return '0.00';
    const sign = v > 0 ? '+' : '−';
    return `${sign}${Math.abs(v).toFixed(2)}`;
  }

  _initPnlChart() {
    if (!this.pnlContainer) return;
    const bgType = LightweightCharts.ColorType?.Solid;
    const layout = {
      background: bgType ? { type: bgType, color: TV.bg } : { color: TV.bg },
      textColor: TV.muted,
      attributionLogo: false,
    };
    this.pnlChart = LightweightCharts.createChart(this.pnlContainer, {
      autoSize: false,
      layout,
      grid: {
        vertLines: { visible: false },
        horzLines: { color: TV.grid },
      },
      rightPriceScale: {
        borderColor: TV.grid,
        minimumWidth: CHART_SCALE_RIGHT_W,
      },
      leftPriceScale: {
        visible: true,
        borderColor: TV.grid,
        minimumWidth: CHART_SCALE_LEFT_W,
      },
      ...chartTimeOptsMsk({
        visible: false,
        borderVisible: false,
        rightOffset: CHART_RIGHT_OFFSET_BARS,
        ...CHART_TIME_SCALE_ZOOM,
      }),
      crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
      ...CHART_SCROLL_SCALE,
    });
    this.pnlSeries = this._addSeriesOn(this.pnlChart, LightweightCharts.LineSeries, {
      color: TV.pnlLine,
      lineWidth: 2,
      priceScaleId: 'right',
      priceLineVisible: false,
      lastValueVisible: true,
      crosshairMarkerVisible: true,
      priceFormat: {
        type: 'custom',
        minMove: 1,
        formatter: (pct) => this._formatPnlPctLabel(pct),
        tickmarksFormatter: (prices) => prices.map((p) => this._formatPnlPctLabel(p)),
      },
    });
    this.pnlRubSeries = this._addSeriesOn(this.pnlChart, LightweightCharts.LineSeries, {
      color: 'rgba(0,0,0,0)',
      lineWidth: 0,
      priceScaleId: 'left',
      priceLineVisible: false,
      lastValueVisible: false,
      crosshairMarkerVisible: false,
      priceFormat: {
        type: 'custom',
        minMove: 1,
        formatter: (rub) => ReplayChart._formatPnlRubAxis(rub),
        tickmarksFormatter: (prices) => prices.map((p) => ReplayChart._formatPnlRubAxis(p)),
      },
    });
    this.pnlZeroLine = this.pnlSeries.createPriceLine({
      price: 0,
      color: '#616161',
      lineWidth: 1,
      lineStyle: LightweightCharts.LineStyle.Dashed,
      axisLabelVisible: true,
      title: '0% (0₽)',
    });
    this.pnlSeries.setData([]);
    this.pnlRubSeries.setData([]);
    this._bindSecondaryTimeSync();
    this._bindPnlHoverDate();
    this._bindCrosshairSync();
  }

  /** Дата под курсором на PnL (timeScale скрыт — встроенная подпись оси не видна). */
  _ensurePnlHoverDateEl() {
    if (this._pnlHoverDateEl || !this.pnlContainer) return;
    const el = document.createElement('div');
    el.className = 'pnl-crosshair-date';
    el.hidden = true;
    el.setAttribute('aria-hidden', 'true');
    this.pnlContainer.appendChild(el);
    this._pnlHoverDateEl = el;
  }

  _bindPnlHoverDate() {
    if (!this.pnlChart || this._pnlHoverDateBound) return;
    this._ensurePnlHoverDateEl();
    this._pnlHoverDateBound = true;
  }

  _syncPanes() {
    return [
      { id: 'z', chart: this.chart, series: this.series, map: this._spreadPriceByTime },
      { id: 'delta', chart: this.deltaChart, series: this.deltaSeries, map: this._deltaPriceByTime },
      { id: 'pnl', chart: this.pnlChart, series: this.pnlSeries, map: this._pnlPriceByTime },
    ].filter((p) => p.chart && p.series);
  }

  /** Ближайшее значение ряда по unix-сек (tip1m↔m15 ≈ 450 с). */
  _nearestSeriesPrice(map, time, maxDeltaSec = 450) {
    if (!map || time == null) return null;
    const t = Number(time);
    if (!Number.isFinite(t)) return null;
    if (map.has(t)) return map.get(t);
    let best = null;
    let bestD = Infinity;
    for (const [ts, v] of map) {
      const d = Math.abs(ts - t);
      if (d < bestD) {
        bestD = d;
        best = v;
      }
    }
    return bestD <= maxDeltaSec ? best : null;
  }

  _setCrosshairSyncPassive(dstChart, dstSeries, time, price) {
    if (!dstChart || !dstSeries || time == null || price == null) return;
    if (typeof dstChart.setCrosshairPosition !== 'function') return;
    try {
      dstChart.applyOptions({
        crosshair: {
          horzLine: { visible: false, labelVisible: false },
          vertLine: { visible: true, labelVisible: true },
        },
      });
      dstChart.setCrosshairPosition(price, time, dstSeries);
    } catch (_) { /* ignore */ }
  }

  _clearCrosshairSyncPassive(dstChart) {
    if (!dstChart) return;
    try {
      if (typeof dstChart.clearCrosshairPosition === 'function') {
        dstChart.clearCrosshairPosition();
      }
      dstChart.applyOptions({
        crosshair: {
          horzLine: { visible: true, labelVisible: true },
          vertLine: { visible: true, labelVisible: true },
        },
      });
    } catch (_) { /* ignore */ }
  }

  _onCrosshairSyncMove(source, param) {
    if (this._crosshairSyncing || this._syncingRange) return;
    const panes = this._syncPanes();
    const srcPane = panes.find((p) => p.id === source);
    if (!srcPane) return;
    const out =
      !param?.time
      || !param?.point
      || param.point.x < 0
      || param.point.y < 0;
    if (out) {
      if (this._crosshairActiveSource === source) {
        this._crosshairSyncing = true;
        try {
          for (const p of panes) {
            if (p.id !== source) this._clearCrosshairSyncPassive(p.chart);
          }
        } finally {
          this._crosshairSyncing = false;
        }
        this._crosshairActiveSource = null;
        this._crosshairSyncTime = null;
      }
      return;
    }
    this._crosshairActiveSource = source;
    this._crosshairSyncTime = param.time;
    if (this._viewportInteracting) return;
    this._crosshairSyncing = true;
    try {
      srcPane.chart.applyOptions({
        crosshair: {
          horzLine: { visible: true, labelVisible: true },
          vertLine: { visible: true, labelVisible: true },
        },
      });
      for (const p of panes) {
        if (p.id === source) continue;
        const price = this._nearestSeriesPrice(p.map, param.time);
        if (price == null) this._clearCrosshairSyncPassive(p.chart);
        else this._setCrosshairSyncPassive(p.chart, p.series, param.time, price);
      }
    } catch (_) { /* ignore */ }
    this._crosshairSyncing = false;
  }

  _rebuildCrosshairPriceMaps(candles) {
    this._spreadPriceByTime = new Map();
    for (const c of candles || []) {
      if (c?.time == null) continue;
      const close = Number(c.close);
      if (Number.isFinite(close)) this._spreadPriceByTime.set(Number(c.time), close);
    }
    this._pnlPriceByTime = new Map();
    const pts = this._pnlChartMode === 'account' ? this._lastEquityRub : this._lastEquityPct;
    for (const p of pts || []) {
      if (p?.time == null) continue;
      const v = Number(p.value);
      if (Number.isFinite(v)) this._pnlPriceByTime.set(Number(p.time), v);
    }
    this._deltaPriceByTime = new Map();
    for (const p of this._lastDeltaPp || []) {
      if (p?.time == null) continue;
      const v = Number(p.value);
      if (Number.isFinite(v)) this._deltaPriceByTime.set(Number(p.time), v);
    }
  }

  _bindCrosshairSync() {
    if (this._crosshairSyncBound || !this.chart) return;
    this._crosshairSyncBound = true;
    this._ensurePnlHoverDateEl();
    this.chart.subscribeCrosshairMove((param) => this._onCrosshairSyncMove('z', param));
    if (this.deltaChart) {
      this.deltaChart.subscribeCrosshairMove((param) => this._onCrosshairSyncMove('delta', param));
    }
    if (this.pnlChart) {
      this.pnlChart.subscribeCrosshairMove((param) => {
        this._onPnlCrosshairMove(param);
        this._onCrosshairSyncMove('pnl', param);
      });
    }
  }

  /** Перевыставить перекрестие на всех графиках по сохранённому time (после zoom/resize). */
  _reapplyCrosshairSync() {
    const time = this._crosshairSyncTime;
    if (time == null || this._crosshairSyncing) return;
    const panes = this._syncPanes();
    if (!panes.length) return;
    const srcId = this._crosshairActiveSource || 'z';
    this._crosshairSyncing = true;
    try {
      const fullCrosshair = {
        horzLine: { visible: true, labelVisible: true },
        vertLine: { visible: true, labelVisible: true },
      };
      const vertOnlyCrosshair = {
        horzLine: { visible: false, labelVisible: false },
        vertLine: { visible: true, labelVisible: true },
      };
      for (const p of panes) {
        const price = this._nearestSeriesPrice(p.map, time);
        if (price == null || typeof p.chart.setCrosshairPosition !== 'function') continue;
        p.chart.applyOptions({
          crosshair: p.id === srcId ? fullCrosshair : vertOnlyCrosshair,
        });
        p.chart.setCrosshairPosition(price, time, p.series);
      }
    } catch (_) { /* ignore */ }
    this._crosshairSyncing = false;
  }

  /** После смены visible range / resize — дождаться layout и перевыставить перекрестие. */
  _scheduleCrosshairReapply() {
    if (this._crosshairSyncTime == null) return;
    if (this._crosshairReapplyRaf) cancelAnimationFrame(this._crosshairReapplyRaf);
    this._crosshairReapplyRaf = requestAnimationFrame(() => {
      this._crosshairReapplyRaf = 0;
      requestAnimationFrame(() => {
        try {
          this._equalizePriceScales();
          this._reapplyCrosshairSync();
        } catch (_) { /* ignore */ }
      });
    });
  }

  /** Одинаковая ширина полей шкал — иначе оси времени расходятся по X. */
  _equalizePriceScales() {
    const charts = [this.chart, this.deltaChart, this.pnlChart].filter(Boolean);
    if (!charts.length) return;
    let maxLeft = CHART_SCALE_LEFT_W;
    let maxRight = CHART_SCALE_RIGHT_W;
    for (const c of charts) {
      try {
        const lw = c.priceScale('left')?.width?.();
        const rw = c.priceScale('right')?.width?.();
        if (Number.isFinite(lw) && lw > maxLeft) maxLeft = lw;
        if (Number.isFinite(rw) && rw > maxRight) maxRight = rw;
      } catch (_) { /* ignore */ }
    }
    const leftOpts = {
      visible: true,
      minimumWidth: maxLeft,
      borderColor: TV.grid,
    };
    const rightOpts = {
      visible: true,
      minimumWidth: maxRight,
      borderColor: TV.grid,
    };
    for (const c of charts) {
      try {
        c.priceScale('left').applyOptions(leftOpts);
        c.priceScale('right').applyOptions(rightOpts);
      } catch (_) { /* ignore */ }
    }
  }

  /** После setData/resize LC асинхронно сбрасывает окно — повторно выровнять шкалы и time-range. */
  _forceSyncAfterPaint() {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        try {
          this._equalizePriceScales();
          const range = this.chart?.timeScale()?.getVisibleLogicalRange() || this._lastLogicalRange;
          if (range) this._syncLogicalRangeToCharts(range, 'z');
        } catch (_) { /* ignore */ }
      });
    });
  }

  _onPnlCrosshairMove(param) {
    const el = this._pnlHoverDateEl;
    if (!el || !this.pnlContainer) return;
    const point = param?.point;
    const time = param?.time;
    if (
      time == null
      || !point
      || point.x < 0
      || point.y < 0
    ) {
      el.hidden = true;
      el.textContent = '';
      return;
    }
    const label = formatChartTickMsk(typeof time === 'number' ? time : Number(time));
    if (!label) {
      el.hidden = true;
      el.textContent = '';
      return;
    }
    el.textContent = label;
    el.hidden = false;
    const w = this.pnlContainer.clientWidth || 0;
    const half = Math.max(12, el.offsetWidth / 2);
    const minX = CHART_SCALE_LEFT_W + half;
    const maxX = Math.max(minX, w - CHART_SCALE_RIGHT_W - half);
    const x = Math.min(maxX, Math.max(minX, point.x));
    el.style.left = `${x}px`;
  }

  _formatPnlPctLabel(pct) {
    if (typeof pct !== 'number' || Number.isNaN(pct)) return '—';
    const rounded = Math.round(pct);
    if (rounded === 0) return '0% (0₽)';
    const pctSign = rounded > 0 ? '+' : '−';
    const basis = this._pnlBasisRub > 0 ? this._pnlBasisRub : 10_000;
    const rub = (rounded / 100) * basis;
    const rubStr = ReplayChart._formatPnlRubParen(rub);
    return `${pctSign}${Math.abs(rounded)}% (${rubStr}₽)`;
  }

  _applyPnlScaleFormatters() {
    if (!this.pnlChart || !this.pnlSeries) return;
    const pctLabel = (pct) => this._formatPnlPctLabel(pct);
    const rubLabel = (rub) => ReplayChart._formatPnlRubAxis(rub);
    const accountLabel = (rub) => ReplayChart._formatAccountRubAxis(rub);
    const pctFmt = {
      type: 'custom',
      minMove: 1,
      formatter: pctLabel,
      tickmarksFormatter: (prices) => prices.map((p) => pctLabel(p)),
    };
    const rubFmt = {
      type: 'custom',
      minMove: 1,
      formatter: rubLabel,
      tickmarksFormatter: (prices) => prices.map((p) => rubLabel(p)),
    };
    const accountFmt = {
      type: 'custom',
      minMove: 1,
      formatter: accountLabel,
      tickmarksFormatter: (prices) => prices.map((p) => accountLabel(p)),
    };

    if (this._pnlChartMode === 'account') {
      this.pnlSeries.applyOptions({
        priceFormat: accountFmt,
        color: TV.pnlLine,
        lineWidth: 2,
        lastValueVisible: true,
      });
      try {
        this.pnlChart.priceScale('left').applyOptions({
          visible: true,
          minimumWidth: CHART_SCALE_LEFT_W,
          borderColor: TV.grid,
        });
        this.pnlChart.priceScale('right').applyOptions({
          visible: true,
          minimumWidth: CHART_SCALE_RIGHT_W,
          borderColor: TV.grid,
        });
      } catch (_) {}
      if (this.pnlRubSeries) {
        this.pnlRubSeries.applyOptions({
          color: 'rgba(0,0,0,0)',
          lineWidth: 0,
          priceScaleId: 'left',
          priceLineVisible: false,
          lastValueVisible: false,
          crosshairMarkerVisible: false,
          autoscaleInfoProvider: () => ({
            priceRange: { minValue: 0, maxValue: 1 },
          }),
        });
        const spacer = (this._lastEquityRub || []).map((p) => ({
          time: p.time,
          value: 0.5,
        }));
        this.pnlRubSeries.setData(spacer);
      }
      if (this._lastEquityRub.length) this.pnlSeries.setData(this._lastEquityRub);
      return;
    }

    this.pnlSeries.applyOptions({ priceFormat: pctFmt, color: TV.pnlLine, lineWidth: 2 });
    if (this.pnlRubSeries) {
      this.pnlRubSeries.applyOptions({ priceFormat: rubFmt });
    }

    try {
        this.pnlChart.priceScale('left').applyOptions({
          visible: true,
          minimumWidth: CHART_SCALE_LEFT_W,
          borderColor: TV.grid,
        });
        this.pnlChart.priceScale('right').applyOptions({
          visible: true,
          minimumWidth: CHART_SCALE_RIGHT_W,
          borderColor: TV.grid,
        });
    } catch (_) {}

    if (this._lastEquityPct.length) this.pnlSeries.setData(this._lastEquityPct);
    if (this.pnlRubSeries && this._lastEquityRub.length) {
      this.pnlRubSeries.setData(this._lastEquityRub);
    }
  }

  static _formatPnlPct(pct) {
    if (typeof pct !== 'number' || Number.isNaN(pct)) return '—';
    const sign = pct > 0 ? '+' : '';
    return `${sign}${Math.round(pct)}%`;
  }

  /** PnL ₽ для подписи в скобках на шкале %. */
  static _formatPnlRubParen(rub) {
    if (typeof rub !== 'number' || Number.isNaN(rub)) return '—';
    const sign = rub > 0 ? '+' : rub < 0 ? '−' : '';
    const abs = Math.abs(rub);
    if (abs >= 1_000_000) return `${sign}${(abs / 1_000_000).toFixed(1)}M`;
    if (abs >= 1000) return `${sign}${abs >= 10000 ? Math.round(abs / 1000) : (abs / 1000).toFixed(1)}k`;
    return `${sign}${Math.round(abs)}`;
  }

  static _formatPnlRubAxis(rub) {
    if (typeof rub !== 'number' || Number.isNaN(rub)) return '—';
    const sign = rub > 0 ? '+' : rub < 0 ? '−' : '';
    const abs = Math.abs(rub);
    if (abs >= 1_000_000) return `${sign}${(abs / 1_000_000).toFixed(1)}M₽`;
    if (abs >= 1000) return `${sign}${abs >= 10000 ? Math.round(abs / 1000) : (abs / 1000).toFixed(1)}k₽`;
    return `${sign}${Math.round(abs)}₽`;
  }

  /** Абсолютный баланс счёта на оси (без «+» как у PnL). */
  static _formatAccountRubAxis(rub) {
    if (typeof rub !== 'number' || Number.isNaN(rub)) return '—';
    const sign = rub < 0 ? '−' : '';
    const abs = Math.abs(rub);
    if (abs >= 1_000_000) return `${sign}${(abs / 1_000_000).toFixed(1)}M₽`;
    if (abs >= 1000) return `${sign}${abs >= 10000 ? Math.round(abs / 1000) : (abs / 1000).toFixed(1)}k₽`;
    return `${sign}${Math.round(abs)}₽`;
  }

  static _formatPnlRub(rub) {
    if (typeof rub !== 'number' || Number.isNaN(rub)) return '—';
    const sign = rub > 0 ? '+' : rub < 0 ? '−' : '';
    const abs = Math.abs(rub);
    if (abs >= 1_000_000) return `${sign}${(abs / 1_000_000).toFixed(1)}M`;
    if (abs >= 1000) return `${sign}${(abs / 1000).toFixed(1)}k`;
    return `${sign}${abs.toFixed(0)}`;
  }

  _rubToPnlPct(rub) {
    const basis = this._pnlBasisRub > 0 ? this._pnlBasisRub : 10_000;
    return (rub / basis) * 100;
  }

  /** Unix-секунды: не смешивать мс и секунды на одной оси. */
  static _unixSec(t) {
    const n = Number(t);
    if (!Number.isFinite(n)) return null;
    if (n > 1e12) return Math.floor(n / 1000);
    if (n > 1e9) return Math.floor(n);
    return n;
  }

  /**
   * PnL по свечам: догон по времени.
   * Одна точка на свечу — иначе pane «Счёт» короче свечей, логический диапазон
   * шкалы схлопывает кривую в вертикальный скачок слева.
   */
  _fillEquitySeries(candles, equityPoints) {
    const pts = [];
    for (const p of equityPoints || []) {
      if (!p || p.time == null) continue;
      const time = ReplayChart._unixSec(p.time);
      const v = Number(p.value);
      if (time == null || !Number.isFinite(v)) continue;
      pts.push({ time, value: v });
    }
    pts.sort((a, b) => a.time - b.time);
    let i = 0;
    let lastVal = 0;
    const filled = [];
    for (const c of candles || []) {
      const ct = ReplayChart._unixSec(c.time);
      if (ct == null) continue;
      while (i < pts.length && pts[i].time <= ct) {
        lastVal = pts[i].value;
        i += 1;
      }
      filled.push({ time: ct, value: lastVal });
    }
    return filled;
  }

  _applyPnlChartData(payload, { appendLast = false, canAppend = false } = {}) {
    if (!this.pnlChart || !this.pnlSeries) return;
    this._pnlBasisRub = payload.pnlBasisRub > 0 ? payload.pnlBasisRub : 10_000;
    this._pnlChartMode = payload.pnlChartMode || 'account';
    this._accountBaseRub = payload.accountBaseRub > 0 ? payload.accountBaseRub : 10_000;

    const pnlFilled = this._fillEquitySeries(payload.candles, payload.equity);
    const equityPct = pnlFilled.map((p) => ({
      time: p.time,
      value: this._rubToPnlPct(p.value),
    }));
    const accountData = pnlFilled.map((p) => ({
      time: p.time,
      value: this._accountBaseRub + p.value,
    }));

    const useAccount = this._pnlChartMode === 'account';
    const primary = useAccount ? accountData : equityPct;
    const secondary = useAccount ? [] : pnlFilled;

    let appended = false;
    if (appendLast && primary.length) {
      const last = primary[primary.length - 1];
      try {
        this.pnlSeries.update(last);
        if (!useAccount && this.pnlRubSeries && secondary.length) {
          this.pnlRubSeries.update(secondary[secondary.length - 1]);
        }
        if (this._lastEquityRub) {
          if (canAppend) {
            this._lastEquityRub.push(useAccount ? last : secondary[secondary.length - 1]);
            this._lastEquityPct.push(useAccount ? pnlFilled[pnlFilled.length - 1] : last);
          } else {
            this._lastEquityRub[this._lastEquityRub.length - 1] = useAccount ? last : secondary[secondary.length - 1];
            this._lastEquityPct[this._lastEquityPct.length - 1] = useAccount ? pnlFilled[pnlFilled.length - 1] : last;
          }
        }
        appended = true;
      } catch (_) {
        appended = false;
      }
    }

    if (!appended) {
      this._lastEquityRub = useAccount ? accountData : pnlFilled;
      this._lastEquityPct = useAccount ? pnlFilled : equityPct;
      this.pnlSeries.setData(primary);
      if (this.pnlRubSeries) {
        this.pnlRubSeries.setData(useAccount ? [] : secondary);
      }
      this._applyPnlScaleFormatters();
      this._updatePnlReferenceLine();
      try {
        this.pnlChart.priceScale('right').applyOptions({ autoScale: true });
      } catch (_) { /* ignore */ }
    }
    this._updatePnlMinMaxLines(this._lastLogicalRange);
  }

  _updatePnlReferenceLine() {
    if (!this.pnlSeries) return;
    if (this._pnlChartMode === 'account') {
      const dep = this._accountBaseRub;
      const title = `старт ${ReplayChart._formatPnlRubParen(dep)}₽`;
      if (this.pnlZeroLine) {
        this.pnlZeroLine.applyOptions({ price: dep, color: '#616161', title });
        return;
      }
      this.pnlZeroLine = this.pnlSeries.createPriceLine({
        price: dep,
        color: '#616161',
        lineWidth: 1,
        lineStyle: LightweightCharts.LineStyle.Dashed,
        axisLabelVisible: true,
        title,
      });
      return;
    }
    if (this.pnlZeroLine) {
      this.pnlZeroLine.applyOptions({ price: 0, color: '#616161', title: '0% (0₽)' });
      return;
    }
    this.pnlZeroLine = this.pnlSeries.createPriceLine({
      price: 0,
      color: '#616161',
      lineWidth: 1,
      lineStyle: LightweightCharts.LineStyle.Dashed,
      axisLabelVisible: true,
      title: '0% (0₽)',
    });
  }

  _setPnlGuideLine(refKey, price, color, label) {
    if (!this.pnlSeries) return;
    const title = this._pnlChartMode === 'account'
      ? `${label} ${ReplayChart._formatAccountRubAxis(price)}`
      : `${label} ${this._formatPnlPctLabel(price)}`;
    if (this[refKey]) {
      this[refKey].applyOptions({ price, color, title });
      return;
    }
    this[refKey] = this.pnlSeries.createPriceLine({
      price,
      color,
      lineWidth: 1,
      lineStyle: LightweightCharts.LineStyle.Dashed,
      axisLabelVisible: true,
      title,
    });
  }

  _updatePnlMinMaxLines(visibleRange) {
    if (!this.pnlSeries) return;
    const useAccount = this._pnlChartMode === 'account';
    const src = useAccount ? this._lastEquityRub : this._lastEquityPct;
    if (!src.length) {
      if (this.pnlMinLine) {
        try { this.pnlSeries.removePriceLine(this.pnlMinLine); } catch (_) {}
        this.pnlMinLine = null;
      }
      if (this.pnlMaxLine) {
        try { this.pnlSeries.removePriceLine(this.pnlMaxLine); } catch (_) {}
        this.pnlMaxLine = null;
      }
      return;
    }

    let slice = src;
    if (visibleRange) {
      const from = Math.max(0, Math.floor(visibleRange.from));
      const to = Math.min(src.length - 1, Math.ceil(visibleRange.to));
      if (to >= from) slice = src.slice(from, to + 1);
    }
    if (!slice.length) return;

    let min = slice[0].value;
    let max = slice[0].value;
    for (const p of slice) {
      if (p.value < min) min = p.value;
      if (p.value > max) max = p.value;
    }

    const minLabel = useAccount ? 'min счёт' : 'min эквити';
    const maxLabel = useAccount ? 'max счёт' : 'max эквити';
    this._setPnlGuideLine('pnlMinLine', min, TV.down, minLabel);
    this._setPnlGuideLine('pnlMaxLine', max, TV.up, maxLabel);
  }

  _setDeltaGuideLine(refKey, price, color, label) {
    if (!this.deltaSeries) return;
    const title = `${label} ${ReplayChart._formatDeltaPp(price)}`;
    if (this[refKey]) {
      this[refKey].applyOptions({ price, color, title });
      return;
    }
    this[refKey] = this.deltaSeries.createPriceLine({
      price,
      color,
      lineWidth: 1,
      lineStyle: LightweightCharts.LineStyle.Dashed,
      axisLabelVisible: true,
      title,
    });
  }

  _updateDeltaMinMaxLines(visibleRange) {
    if (!this.deltaSeries) return;
    if (!this._lastDeltaPp.length) {
      if (this.deltaMinLine) {
        try { this.deltaSeries.removePriceLine(this.deltaMinLine); } catch (_) {}
        this.deltaMinLine = null;
      }
      if (this.deltaMaxLine) {
        try { this.deltaSeries.removePriceLine(this.deltaMaxLine); } catch (_) {}
        this.deltaMaxLine = null;
      }
      return;
    }

    let slice = this._lastDeltaPp;
    if (visibleRange) {
      const from = Math.max(0, Math.floor(visibleRange.from));
      const to = Math.min(this._lastDeltaPp.length - 1, Math.ceil(visibleRange.to));
      if (to >= from) slice = this._lastDeltaPp.slice(from, to + 1);
    }
    if (!slice.length) return;

    let min = slice[0].value;
    let max = slice[0].value;
    for (const p of slice) {
      if (p.value < min) min = p.value;
      if (p.value > max) max = p.value;
    }

    this._setDeltaGuideLine('deltaMinLine', min, TV.down, 'min');
    this._setDeltaGuideLine('deltaMaxLine', max, TV.up, 'max');
  }

  _bindSecondaryTimeSync() {
    if (!this.chart || this._secondarySyncBound) return;
    this._secondarySyncBound = true;
    this.chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      this._onLogicalRangeChanged('z', range);
    });
    if (this.deltaChart) {
      this.deltaChart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
        this._onLogicalRangeChanged('delta', range);
      });
    }
    if (this.pnlChart) {
      this.pnlChart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
        this._onLogicalRangeChanged('pnl', range);
      });
    }
  }

  _onLogicalRangeChanged(source, range) {
    if (!range || this._syncingRange) return;
    this._viewportInteracting = true;
    this._followRightEdge = false;
    this._lastLogicalRange = { from: range.from, to: range.to };
    this._syncLogicalRangeToCharts(range, source);
    // Markers resize with viewport — refresh only after pan/zoom settles.
    this._scheduleRefreshMarkers();
    this._drawCascadeVlines();
  }

  _syncLogicalRangeToCharts(range, source = null) {
    if (!range) return;
    this._syncingRange = true;
    try {
      if (source !== 'z' && this.chart) {
        this.chart.timeScale().setVisibleLogicalRange(range);
      }
      if (source !== 'delta' && this.deltaChart) {
        this.deltaChart.timeScale().setVisibleLogicalRange(range);
      }
      if (source !== 'pnl' && this.pnlChart) {
        this.pnlChart.timeScale().setVisibleLogicalRange(range);
      }
    } catch (_) {}
    this._syncingRange = false;
    this._scheduleGuideLineUpdate(range);
    this._equalizePriceScales();
    this._scheduleCrosshairReapply();
  }

  _scheduleGuideLineUpdate(range) {
    this._pendingGuideRange = range ? { from: range.from, to: range.to } : null;
    if (this._guideLinesRaf) return;
    this._guideLinesRaf = requestAnimationFrame(() => {
      this._guideLinesRaf = 0;
      const r = this._pendingGuideRange;
      this._pendingGuideRange = null;
      this._updateDeltaMinMaxLines(r);
      this._updatePnlMinMaxLines(r);
    });
  }

  _syncLogicalRangeToSecondary(range) {
    this._syncLogicalRangeToCharts(range, 'z');
  }

  _applyFollowRightEdgeWindow() {
    if (!this.chart || this._lastCandleCount < 1) return;
    this._syncingRange = true;
    try {
      applyReplayVisibleWindow(this.chart, this._lastCandleCount, this._lastMaxVisibleBars);
      const range = this.chart.timeScale().getVisibleLogicalRange();
      if (range) {
        this._lastLogicalRange = { from: range.from, to: range.to };
        if (this.deltaChart) this.deltaChart.timeScale().setVisibleLogicalRange(range);
        if (this.pnlChart) this.pnlChart.timeScale().setVisibleLogicalRange(range);
      }
    } catch (_) {}
    this._syncingRange = false;
    try {
      const range = this.chart.timeScale().getVisibleLogicalRange();
      this._updateDeltaMinMaxLines(range);
      this._updatePnlMinMaxLines(range);
    } catch (_) {
      this._updateDeltaMinMaxLines(null);
      this._updatePnlMinMaxLines(null);
    }
  }

  /** Вернуть привязку видимого окна к правому краю (Play / Seek). */
  followReplayEdge(enable = true) {
    this._followRightEdge = !!enable;
  }

  _bindResize() {
    window.addEventListener('resize', () => this.resize());
    if (typeof ResizeObserver !== 'undefined') {
      this._resizeObserver = new ResizeObserver(() => {
        requestAnimationFrame(() => this.resize());
      });
      const target = this.container.closest('.chart-stack')
        || this.container.closest('.chart-wrap')
        || this.container.parentElement
        || this.container;
      this._resizeObserver.observe(target);
      if (this.container.parentElement && this.container.parentElement !== target) {
        this._resizeObserver.observe(this.container.parentElement);
      }
      if (this.pnlContainer) {
        this._resizeObserver.observe(this.pnlContainer);
      }
      if (this.deltaContainer) {
        this._resizeObserver.observe(this.deltaContainer);
      }
    }
  }

  _bindInteractions() {
    if (!this.chart || this._clickBound) return;
    this.chart.subscribeClick((param) => this._onChartClick(param));
    this.chart.subscribeCrosshairMove((param) => this._onCrosshairMove(param));
    this._clickBound = true;
    this._crosshairBound = true;
    // Marker refresh on range is owned by _onLogicalRangeChanged (all panes).
    // Do not also subscribe on Z timeScale — that doubled setMarkers work vs Delta.
  }

  _markerPersistKey(m) {
    const id = m.tradeId || m.text || '';
    const kind = m.milestone != null ? `m${m.milestone}` : (m.isEntry ? 'e' : 'x');
    return `${id}:${kind}:${m.time}`;
  }

  _tradeById(id) {
    return this.lastTrades.find((t) => t.id === id) || null;
  }

  _markerChartPrice(m) {
    const trade = this._tradeById(m.tradeId || m.text);
    if (!trade) return 0;
    const useSpread = this._primaryMetric === 'spread';
    if (useSpread) {
      if (m.isEntry) {
        return trade.entrySpread != null ? trade.entrySpread : (trade.entryZ ?? 0);
      }
      return trade.exitSpread != null
        ? trade.exitSpread
        : (trade.entrySpread != null ? trade.entrySpread : (trade.exitZ ?? trade.entryZ ?? 0));
    }
    return m.isEntry ? trade.entryZ : (trade.exitZ ?? trade.entryZ);
  }

  _markerScreenPosition(m) {
    if (!this.chart || !this.series) return null;
    const x = this.chart.timeScale().timeToCoordinate(m.time);
    if (x == null) return null;
    const price = this._markerChartPrice(m);
    let y = this.series.priceToCoordinate(price);
    if (y == null) return null;
    const yOffset = m.isEntry ? (m.position === 'belowBar' ? 18 : -18) : 0;
    return { x, y: y + yOffset };
  }

  _findNearestMarkerAtPoint(point) {
    if (!this.lastMarkers.length) return null;
    let bestPixel = null;
    for (const m of this.lastMarkers) {
      const pos = this._markerScreenPosition(m);
      const x = pos?.x ?? this.chart.timeScale().timeToCoordinate(m.time);
      if (x == null) continue;
      const dx = Math.abs(point.x - x);
      const hitRadiusXPx = m.isEntry ? MARKER_ENTRY_HIT_RADIUS_X_PX : MARKER_HIT_RADIUS_X_PX;
      let pixelHit = false;
      let pixelScore = Number.POSITIVE_INFINITY;
      if (pos) {
        const dy = Math.abs(point.y - pos.y);
        const dist = Math.hypot(dx, dy);
        pixelHit = dist <= MARKER_HIT_RADIUS_PX || dx <= hitRadiusXPx;
        pixelScore = dx * 1.5 + dy;
      } else {
        pixelHit = dx <= hitRadiusXPx;
        pixelScore = dx;
      }
      if (pixelHit && (!bestPixel || pixelScore < bestPixel.score)) {
        bestPixel = { marker: m, score: pixelScore };
      }
    }
    return bestPixel?.marker ?? null;
  }

  _visibleBarCount() {
    if (!this.chart) return Math.max(this.lastCandleTimes.length, 1);
    try {
      const range = this.chart.timeScale().getVisibleLogicalRange();
      if (range) return Math.max(2, Math.ceil(range.to - range.from + 1));
    } catch (_) {}
    return Math.max(this.lastCandleTimes.length, 1);
  }

  _markerSizeForViewport(requested) {
    const base = requested || 2;
    const n = this._visibleBarCount();
    return Math.max(base, Math.min(4, 360 / n));
  }

  _buildMarkerRenderData(markers, selectedKey, activeTradeId) {
    return markers.map((m) => {
      const key = this._markerPersistKey(m);
      const isSelected = !!activeTradeId && (
        key === selectedKey || (!!m.tradeId && m.tradeId === activeTradeId)
      );
      const baseSize = m.size || 2;
      return {
        time: m.time,
        position: m.position,
        color: isSelected ? TV.selected : m.color,
        shape: isSelected && !m.isEntry ? 'square' : m.shape,
        text: m.text || '',
        size: isSelected ? this._markerSizeForViewport(2.2) : this._markerSizeForViewport(baseSize),
      };
    });
  }

  _snapMarkersToCandles(markers) {
    if (!this.lastCandleTimes.length || !markers.length) return markers;
    const times = this.lastCandleTimes;
    const set = new Set(times);
    let step = 60;
    if (times.length >= 2) {
      const diffs = [];
      const start = Math.max(1, times.length - 80);
      for (let i = start; i < times.length; i++) {
        const d = times[i] - times[i - 1];
        if (d > 0 && d < 7200) diffs.push(d);
      }
      if (diffs.length) {
        diffs.sort((a, b) => a - b);
        step = diffs[Math.floor(diffs.length / 2)] || 60;
      }
    }
    const maxDelta = Math.max(90, Math.round(step * 1.5));
    const out = [];
    for (const m of markers) {
      if (set.has(m.time)) {
        out.push(m);
        continue;
      }
      let best = times[0];
      let bestD = Math.abs(best - m.time);
      for (const t of times) {
        const d = Math.abs(t - m.time);
        if (d < bestD) {
          best = t;
          bestD = d;
        }
      }
      // Drop far markers — do not stack weekend/off-window trades on the first candle.
      if (bestD > maxDelta) continue;
      out.push({ ...m, time: best });
    }
    return out;
  }

  _effectiveTradeId() {
    return this.hoverTradeId || this.selectedTradeId;
  }

  _updateHighlightLine() {
    if (!this.highlightSeries) return;
    const tradeId = this._effectiveTradeId();
    if (!tradeId) {
      this.highlightSeries.setData([]);
      return;
    }
    const trade = this._tradeById(tradeId);
    if (!trade) {
      this.highlightSeries.setData([]);
      return;
    }
    const exitTime = trade.exitTime ?? trade.entryTime;
    const useSpread = this._primaryMetric === 'spread'
      && (trade.entrySpread != null || trade.exitSpread != null);
    const entryV = useSpread
      ? (trade.entrySpread ?? trade.exitSpread ?? trade.entryZ)
      : trade.entryZ;
    const exitV = useSpread
      ? (trade.exitSpread ?? trade.entrySpread ?? trade.exitZ ?? trade.entryZ)
      : (trade.exitZ ?? trade.entryZ);
    const data = [
      { time: trade.entryTime, value: entryV },
      { time: exitTime, value: exitV },
    ].sort((a, b) => a.time - b.time);
    this.highlightSeries.setData(data);
  }

  _applyMarkerSelection(marker, sticky = false) {
    if (!marker) {
      if (sticky) {
        this.selectedTradeId = null;
        this.selectedMarkerKey = null;
        if (this.onSelectionChange) this.onSelectionChange(null);
      }
      this.hoverTradeId = null;
    } else {
      const tradeId = marker.tradeId || marker.text || null;
      if (sticky) {
        this.selectedTradeId = tradeId;
        this.selectedMarkerKey = this._markerPersistKey(marker);
        if (this.onSelectionChange) this.onSelectionChange(tradeId);
      } else {
        this.hoverTradeId = tradeId;
      }
    }
    this._updateHighlightLine();
    this._refreshMarkers();
  }

  _onChartClick(param) {
    if (!param.point || param.point.x < 0 || param.point.y < 0) {
      this._applyMarkerSelection(null, true);
      return;
    }
    const marker = this._findNearestMarkerAtPoint(param.point);
    if (!marker || !this._tradeById(marker.tradeId || marker.text)) {
      this._applyMarkerSelection(null, true);
      return;
    }
    this._applyMarkerSelection(marker, true);
    const tradeId = marker.tradeId || marker.text;
    if (tradeId) {
      this.centerOnTrade(tradeId);
      requestAnimationFrame(() => this.centerOnTrade(tradeId));
    }
  }

  _onCrosshairMove(param) {
    // During pan/zoom, crosshair floods every frame — never rebuild markers here.
    if (this._viewportInteracting || this._syncingRange) return;

    let nextHover = null;
    if (param.point && param.point.x >= 0 && param.point.y >= 0) {
      const marker = this._findNearestMarkerAtPoint(param.point);
      if (marker && this._tradeById(marker.tradeId || marker.text)) {
        nextHover = marker.tradeId || marker.text || null;
      }
    }
    if (nextHover === this.hoverTradeId) return;

    this.hoverTradeId = nextHover;
    this._updateHighlightLine();
    this._scheduleRefreshMarkers();
  }

  _scheduleRefreshMarkers() {
    if (this._refreshMarkersTimer) clearTimeout(this._refreshMarkersTimer);
    this._refreshMarkersTimer = setTimeout(() => {
      this._refreshMarkersTimer = 0;
      this._viewportInteracting = false;
      requestAnimationFrame(() => {
        this._refreshMarkers();
        this._updateHighlightLine();
        this._scheduleCrosshairReapply();
      });
    }, 100);
  }

  _applyMarkers(markerData) {
    this._applyMarkersToSeries(this.series, 'markersPlugin', markerData);
    this._applyMarkersToSeries(this.deltaSeries, 'deltaMarkersPlugin', markerData);
  }

  _applyMarkersToSeries(series, pluginKey, markerData) {
    if (!series) return;
    if (!markerData.length) {
      if (this[pluginKey]) {
        try {
          if (typeof this[pluginKey].setMarkers === 'function') {
            this[pluginKey].setMarkers([]);
          } else {
            this[pluginKey].detach();
            this[pluginKey] = null;
          }
        } catch (_) {
          this[pluginKey] = null;
        }
      }
      if (typeof series.setMarkers === 'function') {
        try { series.setMarkers([]); } catch (_) {}
      }
      return;
    }
    if (LightweightCharts.createSeriesMarkers) {
      try {
        if (this[pluginKey] && typeof this[pluginKey].setMarkers === 'function') {
          this[pluginKey].setMarkers(markerData);
          return;
        }
        if (this[pluginKey]) {
          try { this[pluginKey].detach(); } catch (_) {}
          this[pluginKey] = null;
        }
        this[pluginKey] = LightweightCharts.createSeriesMarkers(series, markerData);
        return;
      } catch (e) {
        console.warn('createSeriesMarkers', e);
      }
    }
    if (typeof series.setMarkers === 'function') {
      try {
        series.setMarkers(markerData);
      } catch (e) {
        console.warn('setMarkers', e);
      }
    }
  }

  _refreshMarkers() {
    if (!this.series && !this.deltaSeries) return;
    const activeId = this._effectiveTradeId();
    const markerData = this._buildMarkerRenderData(
      this._snapMarkersToCandles(this.lastMarkers),
      this.selectedMarkerKey,
      activeId,
    );
    this._applyMarkers(markerData);
  }

  selectTrade(tradeId) {
    if (!tradeId || !this._tradeById(tradeId)) {
      this._applyMarkerSelection(null, true);
      return;
    }
    const marker = this.lastMarkers.find((m) => (m.tradeId || m.text) === tradeId);
    if (marker) {
      this._applyMarkerSelection(marker, true);
    } else {
      this.selectedTradeId = tradeId;
      this.selectedMarkerKey = null;
      this.hoverTradeId = null;
      if (this.onSelectionChange) this.onSelectionChange(tradeId);
      this._updateHighlightLine();
      this._refreshMarkers();
    }
  }

  /**
   * Подгоняет Z / Δпп / PnL под сделку: logical range entry→exit с небольшим padding.
   * Открытая сделка: exitTime = текущий курсор (см. buildTradeSegments).
   * Курсор replay не двигает — только timeScale (+ автомасштаб цены по видимым барам).
   */
  centerOnTrade(tradeId) {
    if (!this.chart || !tradeId || !this.lastCandleTimes.length) return false;
    const trade = this._tradeById(tradeId);
    if (!trade) return false;

    const findIdx = (t) => {
      if (t == null) return -1;
      let best = -1;
      let bestDist = Infinity;
      for (let i = 0; i < this.lastCandleTimes.length; i++) {
        const d = Math.abs(this.lastCandleTimes[i] - t);
        if (d < bestDist) {
          bestDist = d;
          best = i;
        }
      }
      return best;
    };

    const entryIdx = findIdx(trade.entryTime);
    if (entryIdx < 0) return false;
    // Открытая / без exit: до правого края данных (курсор replay в окне)
    let exitIdx = trade.exitTime != null
      ? findIdx(trade.exitTime)
      : this.lastCandleTimes.length - 1;
    if (exitIdx < 0) exitIdx = entryIdx;

    const lo = Math.min(entryIdx, exitIdx);
    const hi = Math.max(entryIdx, exitIdx);
    const n = this.lastCandleTimes.length;
    const tradeBars = Math.max(1, hi - lo + 1);
    // ~12% с каждой стороны, минимум 2 бара; короткие сделки — не меньше ~12 баров
    const pad = Math.max(2, Math.ceil(tradeBars * 0.12));
    const minSpan = Math.min(n, Math.max(12, tradeBars + pad * 2));

    let from = lo - pad;
    let to = hi + pad;
    if (to - from + 1 < minSpan) {
      const mid = (lo + hi) / 2;
      from = mid - minSpan / 2;
      to = mid + minSpan / 2;
    }
    if (from < 0) {
      to = Math.min(n - 1, to - from);
      from = 0;
    }
    if (to > n - 1) {
      from = Math.max(0, from - (to - (n - 1)));
      to = n - 1;
    }

    try {
      this._followRightEdge = false;
      const range = { from, to };
      this._lastLogicalRange = range;
      this._syncingRange = true;
      try {
        this.chart.timeScale().setVisibleLogicalRange(range);
        if (this.deltaChart) this.deltaChart.timeScale().setVisibleLogicalRange(range);
        if (this.pnlChart) this.pnlChart.timeScale().setVisibleLogicalRange(range);
      } finally {
        this._syncingRange = false;
      }
      // Вертикально: автомасштаб по видимым барам (сделка + padding)
      try {
        this.series?.priceScale()?.applyOptions?.({ autoScale: true });
      } catch (_) {}
      this._updateDeltaMinMaxLines(range);
      this._updatePnlMinMaxLines(range);
      return true;
    } catch (_) {
      this._syncingRange = false;
      return false;
    }
  }

  _reapplyVisibleWindow() {
    if (!this.chart || this._lastCandleCount < 1) return;
    this._applyFollowRightEdgeWindow();
  }

  /**
   * Показать все загруженные бары (от начала до конца текущего окна симуляции).
   * Снимает follow-right-edge, чтобы смена параметров не прыгала к хвосту.
   */
  fitFullRange() {
    if (!this.chart || this._lastCandleCount < 1) return false;
    const n = this._lastCandleCount;
    const range = { from: 0, to: Math.max(0, n - 1) };
    this._followRightEdge = false;
    this._lastLogicalRange = range;
    this._syncingRange = true;
    try {
      this.chart.timeScale().setVisibleLogicalRange(range);
      if (this.deltaChart) this.deltaChart.timeScale().setVisibleLogicalRange(range);
      if (this.pnlChart) this.pnlChart.timeScale().setVisibleLogicalRange(range);
    } catch (_) {
      this._syncingRange = false;
      return false;
    }
    this._syncingRange = false;
    try {
      this.series?.priceScale()?.applyOptions?.({ autoScale: true });
    } catch (_) {}
    this._updateDeltaMinMaxLines(range);
    this._updatePnlMinMaxLines(range);
    this._scheduleRefreshMarkers();
    return true;
  }

  resize() {
    if (!this.chart) return;
    const { w: zw, h: zh } = this._measureZ();
    if (zw > 0 && zh > 0) this.chart.applyOptions({ width: zw, height: zh });
    if (this.deltaChart && this.deltaContainer) {
      const { w: dw, h: dh } = this._measureDelta();
      if (dw > 0 && dh > 0) this.deltaChart.applyOptions({ width: dw, height: dh });
    }
    if (this.pnlChart && this.pnlContainer) {
      const { w: pw, h: ph } = this._measurePnl();
      if (pw > 0 && ph > 0) this.pnlChart.applyOptions({ width: pw, height: ph });
    }
    // Не сбрасывать панораму — только синхронизировать шкалы после resize
    try {
      const range = this.chart.timeScale().getVisibleLogicalRange() || this._lastLogicalRange;
      if (range) this._syncLogicalRangeToCharts(range, 'z');
    } catch (_) {}
    this._equalizePriceScales();
    this._scheduleRefreshMarkers();
    this._drawCascadeVlines();
    this._scheduleCrosshairReapply();
  }

  _clearPriceLines() {
    for (const pl of this.priceLines) {
      try { this.series.removePriceLine(pl); } catch (_) {}
    }
    this.priceLines = [];
  }

  _clearReplayLine() {
    if (this.replayCursorLine) {
      try { this.series.removePriceLine(this.replayCursorLine); } catch (_) {}
      this.replayCursorLine = null;
    }
  }

  _ensureVlineOverlay() {
    if (this._vlineLayer || !this.container) return;
    const cs = window.getComputedStyle(this.container);
    if (cs.position === 'static' || !cs.position) {
      this.container.style.position = 'relative';
    }
    const layer = document.createElement('div');
    layer.className = 'chart-vline-layer';
    layer.setAttribute('aria-hidden', 'true');
    this.container.appendChild(layer);
    this._vlineLayer = layer;
  }

  _setCascadeVlines(vlines) {
    this._cascadeVlines = Array.isArray(vlines) ? vlines : [];
    this._drawCascadeVlines();
  }

  _drawCascadeVlines() {
    this._ensureVlineOverlay();
    const layer = this._vlineLayer;
    if (!layer) return;
    layer.innerHTML = '';
    if (!this.chart || !this._cascadeVlines.length) return;
    const ts = this.chart.timeScale();
    for (const v of this._cascadeVlines) {
      const t = Number(v.time);
      if (!Number.isFinite(t)) continue;
      let x;
      try {
        x = ts.timeToCoordinate(t);
      } catch (_) {
        x = null;
      }
      if (x == null || !Number.isFinite(x)) continue;
      const isZone = v.family === 'zone' || (!!v.zone && v.direction !== 'up' && v.direction !== 'down');
      const kind = v.kind === 'end' ? 'end' : 'start';
      const el = document.createElement('div');
      if (isZone) {
        const zk = String(v.zone || v.direction || 'middle');
        el.className = `chart-vline chart-vline-zone chart-vline-zone-${zk} chart-vline-${kind}`;
      } else {
        const dir = v.direction === 'up' ? 'up' : 'down';
        el.className = `chart-vline chart-vline-${kind} chart-vline-${dir}`;
      }
      el.style.left = `${Math.round(x)}px`;
      if (v.title) el.title = String(v.title);
      const lab = document.createElement('span');
      lab.className = 'chart-vline-label';
      lab.textContent = String(
        v.label
        || (isZone
          ? (kind === 'start' ? 'зона' : 'зона · выход')
          : (kind === 'start' ? 'каскад · начало' : 'каскад · конец')),
      );
      el.appendChild(lab);
      layer.appendChild(el);
    }
  }

  setReplay(payload) {
    if (!this.series || !payload?.candles?.length) return;
    const { w: zw, h: zh } = this._measureZ();
    if (zw > 0 && zh > 0) this.chart.applyOptions({ width: zw, height: zh });
    if (this.pnlChart && this.pnlContainer) {
      const { w: pw, h: ph } = this._measurePnl();
      if (pw > 0 && ph > 0) this.pnlChart.applyOptions({ width: pw, height: ph });
    }
    if (this.deltaChart && this.deltaContainer) {
      const { w: dw, h: dh } = this._measureDelta();
      if (dw > 0 && dh > 0) this.deltaChart.applyOptions({ width: dw, height: dh });
    }

    const prevTradeId = this.selectedTradeId;
    this.lastMarkers = (payload.markers || []).map((m) => ({
      time: m.time,
      position: m.position,
      color: m.color,
      shape: m.shape,
      text: m.text || '',
      size: m.size || 2,
      tradeId: m.tradeId || m.text || '',
      isEntry: !!m.isEntry,
    }));
    this.lastTrades = (payload.trades || []).map((t) => ({
      id: t.id,
      entryTime: t.entryTime,
      entryZ: t.entryZ,
      exitTime: t.exitTime == null ? null : t.exitTime,
      exitZ: t.exitZ == null ? null : t.exitZ,
      entrySpread: t.entrySpread == null ? null : t.entrySpread,
      exitSpread: t.exitSpread == null ? null : t.exitSpread,
      open: !!t.open,
    }));

    if (prevTradeId && !this._tradeById(prevTradeId)) {
      this.selectedTradeId = null;
      this.selectedMarkerKey = null;
    }
    this.hoverTradeId = null;

    const primaryMetric = payload.primaryMetric === 'spread' ? 'spread' : 'z';
    const metricChanged = primaryMetric !== this._primaryMetric;
    this._primaryMetric = primaryMetric;

    const lastC = payload.candles[payload.candles.length - 1];
    const candleFp = [
      primaryMetric,
      payload.candles.length,
      payload.candles[0]?.time,
      lastC?.time,
      lastC?.close,
    ].join('|');
    const sameCandles = candleFp === this._candleFp && !metricChanged;
    const light = !!payload.light;
    const eq = payload.equity || [];
    const equityFp = `${eq.length}|${eq[0]?.time}|${eq[0]?.value}|${eq[eq.length - 1]?.time}|${eq[eq.length - 1]?.value}`;
    const equityChanged = equityFp !== this._equityFp;
    this._equityFp = equityFp;
    const canAppend = light
      && this._lastCandleCount > 0
      && payload.candles.length === this._lastCandleCount + 1
      && this.lastCandleTimes[0] === payload.candles[0]?.time
      && this.lastCandleTimes[this._lastCandleCount - 1] === payload.candles[payload.candles.length - 2]?.time;

    if (canAppend) {
      const lastC = payload.candles[payload.candles.length - 1];
      try {
        this.series.update(lastC);
        if (this._zLeftSpacer) this._zLeftSpacer.update({ time: lastC.time, value: 0 });
      } catch (_) {
        this.series.setData(payload.candles);
      }
      this.lastCandleTimes.push(lastC.time);
      this._lastCandleCount = payload.candles.length;
      this._candleFp = candleFp;
    } else if (!sameCandles) {
      this.series.setData(payload.candles);
      if (this._zLeftSpacer) {
        this._zLeftSpacer.setData(payload.candles.map((c) => ({ time: c.time, value: 0 })));
      }
      this.lastCandleTimes = payload.candles.map((c) => c.time);
      this._lastCandleCount = payload.candles.length;
      this._candleFp = candleFp;
    } else if (payload.candles.length) {
      try {
        this.series.update(payload.candles[payload.candles.length - 1]);
      } catch (_) {
        this.series.setData(payload.candles);
      }
    }
    this._lastMaxVisibleBars = typeof payload.maxVisibleBars === 'number'
      ? payload.maxVisibleBars
      : 200;

    if (!light || (!sameCandles && !canAppend) || metricChanged) {
      this._clearPriceLines();
      for (const hl of payload.hlines || []) {
        this.priceLines.push(
          this.series.createPriceLine({
            price: hl.value,
            color: hl.color,
            lineWidth: 1,
            lineStyle: LightweightCharts.LineStyle.Dashed,
            axisLabelVisible: true,
            title: hl.title || '',
          }),
        );
      }
      this._updateSpreadBands(
        payload.candles,
        payload.spreadLevels || null,
        primaryMetric === 'spread',
      );
    } else if (primaryMetric === 'spread' && !sameCandles) {
      this._updateSpreadBands(payload.candles, payload.spreadLevels || null, true);
    } else if (canAppend && primaryMetric === 'spread' && payload.candles.length) {
      // Append: extend band fills to new bar time
      this._updateSpreadBands(payload.candles, payload.spreadLevels || null, true);
    }

    // Широкая полка: Тест передаёт payload.corridor (kind=wide). Боевой стол — свой график.
    const corridorEnabled = primaryMetric === 'spread' && !!payload.corridor;
    const corridorDataFp = corridorEnabled
      ? [
        payload.corridor.kind || '',
        payload.corridor.phase,
        payload.corridor.lo,
        payload.corridor.hi,
        (payload.corridor.history || []).length,
        payload.corridor.last_date,
        (payload.corridor.history && payload.corridor.history[0] && payload.corridor.history[0].date) || '',
        (payload.corridor.history && payload.corridor.history.length
          && payload.corridor.history[payload.corridor.history.length - 1].date) || '',
      ].join('|')
      : 'off';
    const corridorWindowFp = corridorEnabled
      ? [
        payload.candles[0]?.time,
        payload.candles[payload.candles.length - 1]?.time,
        payload.candles.length,
      ].join('|')
      : '';
    // Не пересоздавать серии на каждый light-кадр replay — из-за этого зависал Тест.
    const corridorNeedsPaint = corridorDataFp !== this._corridorDataFp
      || (!light && corridorEnabled && corridorWindowFp !== this._corridorWindowFp)
      || metricChanged;
    if (corridorNeedsPaint) {
      this._corridorDataFp = corridorDataFp;
      this._corridorWindowFp = corridorWindowFp;
      if (!corridorEnabled) this._updateCorridorOnChart(null, null, false);
      else this._updateCorridorOnChart(payload.corridor, payload.candles, true);
    }
    const last = payload.candles[payload.candles.length - 1];
    if (this.replayCursorLine) {
      try {
        this.replayCursorLine.applyOptions({ price: last.close });
      } catch (_) {
        this._clearReplayLine();
        this.replayCursorLine = this.series.createPriceLine({
          price: last.close,
          color: '#FACC15',
          lineWidth: 1,
          lineStyle: LightweightCharts.LineStyle.Solid,
          axisLabelVisible: true,
          title: 'replay',
        });
      }
    } else {
      this.replayCursorLine = this.series.createPriceLine({
        price: last.close,
        color: '#FACC15',
        lineWidth: 1,
        lineStyle: LightweightCharts.LineStyle.Solid,
        axisLabelVisible: true,
        title: 'replay',
      });
    }

    if (!light) {
      this.chart.timeScale().applyOptions({ rightOffset: CHART_RIGHT_OFFSET_BARS });
    }
    if (this.deltaChart) {
      if (!light) {
        this.deltaChart.timeScale().applyOptions({ rightOffset: CHART_RIGHT_OFFSET_BARS });
      }
      if ((canAppend || (light && sameCandles)) && payload.deltaPp?.length) {
        const lastD = payload.deltaPp[payload.deltaPp.length - 1];
        const lastC = payload.candles[payload.candles.length - 1];
        const pt = {
          time: lastC.time,
          value: typeof lastD.value === 'number' ? lastD.value : 0,
          color: (lastD.value || 0) >= 0 ? TV.up : TV.down,
        };
        try {
          this.deltaSeries.update(pt);
          if (this._lastDeltaPp) {
            if (canAppend) this._lastDeltaPp.push({ time: pt.time, value: pt.value });
            else this._lastDeltaPp[this._lastDeltaPp.length - 1] = { time: pt.time, value: pt.value };
          }
        } catch (_) {
          /* fall through to full below */
          const byTime = new Map();
          for (const p of payload.deltaPp || []) {
            byTime.set(p.time, typeof p.value === 'number' ? p.value : 0);
          }
          const deltaPp = payload.candles.map((c) => {
            const value = byTime.has(c.time) ? byTime.get(c.time) : 0;
            return { time: c.time, value, color: value >= 0 ? TV.up : TV.down };
          });
          this._lastDeltaPp = deltaPp.map((p) => ({ time: p.time, value: p.value }));
          this.deltaSeries.setData(deltaPp);
        }
      } else {
        const byTime = new Map();
        for (const p of payload.deltaPp || []) {
          byTime.set(p.time, typeof p.value === 'number' ? p.value : 0);
        }
        const deltaPp = payload.candles.map((c) => {
          const value = byTime.has(c.time) ? byTime.get(c.time) : 0;
          return {
            time: c.time,
            value,
            color: value >= 0 ? TV.up : TV.down,
          };
        });
        this._lastDeltaPp = deltaPp.map((p) => ({ time: p.time, value: p.value }));
        this.deltaSeries.setData(deltaPp);
        if (this._deltaRightSpacer) {
          this._deltaRightSpacer.setData(deltaPp.map((p) => ({ time: p.time, value: 0 })));
        }
      }
    }
    if (this.pnlChart) {
      if (!light) {
        this.pnlChart.timeScale().applyOptions({ rightOffset: CHART_RIGHT_OFFSET_BARS });
      }
      const canPnlAppend = (canAppend || (light && sameCandles))
        && payload.equity?.length
        && !equityChanged;
      this._applyPnlChartData(payload, {
        appendLast: !!canPnlAppend,
        canAppend: !!canAppend,
      });
    }

    // Play включает follow через followReplayEdge(true), но ручная прокрутка
    // (_onLogicalRangeChanged) должна его отключать даже во время воспроизведения.
    const followEdge = this._followRightEdge;
    if (payload.fitFull) {
      this.fitFullRange();
    } else if (followEdge) {
      this._applyFollowRightEdgeWindow();
    } else if (!light) {
      const keep = this._lastLogicalRange;
      if (keep) {
        try {
          this._syncingRange = true;
          this.chart.timeScale().setVisibleLogicalRange(keep);
          if (this.deltaChart) this.deltaChart.timeScale().setVisibleLogicalRange(keep);
          if (this.pnlChart) this.pnlChart.timeScale().setVisibleLogicalRange(keep);
          this._syncingRange = false;
          this._updateDeltaMinMaxLines(keep);
          this._updatePnlMinMaxLines(keep);
        } catch (_) {
          this._syncingRange = false;
          this._applyFollowRightEdgeWindow();
        }
      } else {
        this._applyFollowRightEdgeWindow();
      }
    }
    if (light) {
      if (payload.markersChanged) this._refreshMarkers();
      this._updateHighlightLine();
    } else {
      this._refreshMarkers();
      this._updateHighlightLine();
      this._scheduleRefreshMarkers();
      setTimeout(() => this._refreshMarkers(), 120);
    }
    if (!light || payload.cascadeVlinesChanged !== false) {
      this._setCascadeVlines(payload.cascadeVlines || []);
    } else {
      this._drawCascadeVlines();
    }
    this._rebuildCrosshairPriceMaps(payload.candles);
    this._equalizePriceScales();
    this._forceSyncAfterPaint();
  }
}

window.ReplayChart = ReplayChart;
