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
    this.pnlZeroLine = null;
    this.pnlMinLine = null;
    this.pnlMaxLine = null;
    this.deltaZeroLine = null;
    this.deltaMinLine = null;
    this.deltaMaxLine = null;
    this._lastEquityPct = [];
    this._lastEquityRub = [];
    this._lastDeltaPp = [];
    this._pnlBasisRub = 10_000;
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

    this.pnlSeries.applyOptions({ priceFormat: pctFmt });
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

  _setPnlGuideLine(refKey, price, color, label) {
    if (!this.pnlSeries) return;
    const title = `${label} ${this._formatPnlPctLabel(price)}`;
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
    if (!this._lastEquityPct.length) {
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

    let slice = this._lastEquityPct;
    if (visibleRange) {
      const from = Math.max(0, Math.floor(visibleRange.from));
      const to = Math.min(this._lastEquityPct.length - 1, Math.ceil(visibleRange.to));
      if (to >= from) slice = this._lastEquityPct.slice(from, to + 1);
    }
    if (!slice.length) return;

    let min = slice[0].value;
    let max = slice[0].value;
    for (const p of slice) {
      if (p.value < min) min = p.value;
      if (p.value > max) max = p.value;
    }

    this._setPnlGuideLine('pnlMinLine', min, TV.down, 'min эквити');
    this._setPnlGuideLine('pnlMaxLine', max, TV.up, 'max эквити');
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
    const set = new Set(this.lastCandleTimes);
    return markers.map((m) => {
      if (set.has(m.time)) return m;
      let best = this.lastCandleTimes[0];
      let bestD = Math.abs(best - m.time);
      for (const t of this.lastCandleTimes) {
        const d = Math.abs(t - m.time);
        if (d < bestD) {
          best = t;
          bestD = d;
        }
      }
      return { ...m, time: best };
    });
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
    const exitZ = trade.exitZ ?? trade.entryZ;
    const data = [
      { time: trade.entryTime, value: trade.entryZ },
      { time: exitTime, value: exitZ },
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
    this._scheduleRefreshMarkers();
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
      open: !!t.open,
    }));

    if (prevTradeId && !this._tradeById(prevTradeId)) {
      this.selectedTradeId = null;
      this.selectedMarkerKey = null;
    }
    this.hoverTradeId = null;

    const candleFp = `${payload.candles.length}|${payload.candles[0]?.time}|${payload.candles[payload.candles.length - 1]?.time}`;
    const sameCandles = candleFp === this._candleFp;
    const light = !!payload.light;
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

    if (!light || (!sameCandles && !canAppend)) {
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
      this._pnlBasisRub = payload.pnlBasisRub > 0 ? payload.pnlBasisRub : 10_000;
      if ((canAppend || (light && sameCandles)) && payload.equity?.length) {
        const lastE = payload.equity[payload.equity.length - 1];
        const lastC = payload.candles[payload.candles.length - 1];
        const rub = { time: lastC.time, value: typeof lastE.value === 'number' ? lastE.value : 0 };
        const pct = { time: lastC.time, value: this._rubToPnlPct(rub.value) };
        try {
          this.pnlSeries.update(pct);
          if (this.pnlRubSeries) this.pnlRubSeries.update(rub);
          if (this._lastEquityRub) {
            if (canAppend) {
              this._lastEquityRub.push(rub);
              this._lastEquityPct.push(pct);
            } else {
              this._lastEquityRub[this._lastEquityRub.length - 1] = rub;
              this._lastEquityPct[this._lastEquityPct.length - 1] = pct;
            }
          }
        } catch (_) {
          const byTimeRub = new Map();
          for (const p of payload.equity || []) byTimeRub.set(p.time, p.value);
          const equityRub = payload.candles.map((c) => ({
            time: c.time,
            value: byTimeRub.has(c.time) ? byTimeRub.get(c.time) : 0,
          }));
          const equityPct = equityRub.map((p) => ({ time: p.time, value: this._rubToPnlPct(p.value) }));
          this._lastEquityRub = equityRub;
          this._lastEquityPct = equityPct;
          this.pnlSeries.setData(equityPct);
          if (this.pnlRubSeries) this.pnlRubSeries.setData(equityRub);
        }
      } else {
        const byTimeRub = new Map();
        for (const p of payload.equity || []) {
          byTimeRub.set(p.time, p.value);
        }
        const equityRub = payload.candles.map((c) => ({
          time: c.time,
          value: byTimeRub.has(c.time) ? byTimeRub.get(c.time) : 0,
        }));
        const equityPct = equityRub.map((p) => ({
          time: p.time,
          value: this._rubToPnlPct(p.value),
        }));
        this._lastEquityRub = equityRub;
        this._lastEquityPct = equityPct;
        this.pnlSeries.setData(equityPct);
        if (this.pnlRubSeries) this.pnlRubSeries.setData(equityRub);
        this._applyPnlScaleFormatters();
      }
    }

    const followEdge = this._followRightEdge || !!payload.playing;
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
  }
}
