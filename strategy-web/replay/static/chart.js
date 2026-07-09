/**
 * TradingView lightweight-charts — replay window pan (parity chart-frame setReplayCursor).
 */
const TV = {
  bg: '#131722',
  grid: '#2a2e39',
  text: '#d1d4dc',
  up: '#089981',
  down: '#f23645',
};

const CHART_RIGHT_OFFSET_BARS = 18;
const CHART_INITIAL_RIGHT_MARGIN_IN_WINDOW = 0.18;

/** Показать хвост окна: widthFrac=1 → весь slice, курсор справа с отступом. */
function applyReplayVisibleWindow(chart, candleCount, windowWidth = 1) {
  if (!chart || candleCount < 1) return;
  const n = candleCount;
  const maxIndex = n - 1;
  const widthFrac = Math.max(0.05, Math.min(1, windowWidth || 1));
  const visibleBars = Math.max(2, Math.ceil(widthFrac * n));
  const startFrac = Math.max(0, 1 - widthFrac);
  const startIdx = Math.floor(startFrac * maxIndex);
  const from = Math.max(0, startIdx);
  const to = Math.min(maxIndex, from + visibleBars - 1);
  const rightGapBars = Math.max(2, Math.ceil(visibleBars * CHART_INITIAL_RIGHT_MARGIN_IN_WINDOW));
  chart.timeScale().setVisibleLogicalRange({ from, to: to + rightGapBars });
}

class ReplayChart {
  constructor(container) {
    this.container = container;
    this.chart = null;
    this.series = null;
    this.markersPlugin = null;
    this.priceLines = [];
    this.replayCursorLine = null;
    this._resizeObserver = null;
    this._lastWindowWidth = 1;
    this._lastCandleCount = 0;
    this._init();
  }

  _measure() {
    const parent = this.container.parentElement;
    const w = Math.max(
      this.container.clientWidth,
      parent?.clientWidth ?? 0,
      400,
    );
    const h = Math.max(
      this.container.clientHeight,
      parent?.clientHeight ?? 0,
      320,
    );
    return { w, h };
  }

  _init() {
    if (typeof LightweightCharts === 'undefined') {
      this.container.innerHTML = '<div class="chart-error">lightweight-charts не загружен</div>';
      return;
    }
    const opts = {
      autoSize: true,
      layout: { background: { color: TV.bg }, textColor: TV.text },
      grid: { vertLines: { color: TV.grid }, horzLines: { color: TV.grid } },
      rightPriceScale: { borderColor: TV.grid },
      timeScale: {
        borderColor: TV.grid,
        timeVisible: true,
        secondsVisible: false,
        rightOffset: CHART_RIGHT_OFFSET_BARS,
      },
      crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
    };
    this.chart = LightweightCharts.createChart(this.container, opts);
    this.series = this.chart.addCandlestickSeries({
      upColor: TV.up,
      downColor: TV.down,
      borderVisible: false,
      wickUpColor: TV.up,
      wickDownColor: TV.down,
    });
    this._bindResize();
  }

  _bindResize() {
    const onResize = () => this.resize();
    window.addEventListener('resize', onResize);
    if (typeof ResizeObserver !== 'undefined') {
      this._resizeObserver = new ResizeObserver(() => {
        requestAnimationFrame(() => this.resize());
      });
      const target = this.container.parentElement || this.container;
      this._resizeObserver.observe(target);
    }
  }

  _reapplyVisibleWindow() {
    if (!this.chart || this._lastCandleCount < 1) return;
    applyReplayVisibleWindow(this.chart, this._lastCandleCount, this._lastWindowWidth);
  }

  resize() {
    if (!this.chart) return;
    const { w, h } = this._measure();
    this.chart.applyOptions({ width: w, height: h });
    this._reapplyVisibleWindow();
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
    const { w, h } = this._measure();
    if (w > 0 && h > 0) this.chart.applyOptions({ width: w, height: h });

    this.series.setData(payload.candles);
    this._lastCandleCount = payload.candles.length;
    this._lastWindowWidth = typeof payload.windowWidth === 'number' ? payload.windowWidth : 1;

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
    const last = payload.candles[payload.candles.length - 1];
    this._clearReplayLine();
    this.replayCursorLine = this.series.createPriceLine({
      price: last.close,
      color: '#FACC15',
      lineWidth: 1,
      lineStyle: LightweightCharts.LineStyle.Solid,
      axisLabelVisible: true,
      title: 'replay',
    });
    const markers = (payload.markers || []).map((m) => ({
      time: m.time,
      position: m.position,
      color: m.color,
      shape: m.shape,
      text: m.text || '',
      size: m.size || 2,
    }));
    if (this.markersPlugin) {
      try { this.markersPlugin.detach(); } catch (_) {}
      this.markersPlugin = null;
    }
    if (markers.length && LightweightCharts.createSeriesMarkers) {
      try {
        this.markersPlugin = LightweightCharts.createSeriesMarkers(this.series, markers);
      } catch (e) {
        console.warn('markers', e);
      }
    }
    this.chart.timeScale().applyOptions({ rightOffset: CHART_RIGHT_OFFSET_BARS });
    applyReplayVisibleWindow(this.chart, this._lastCandleCount, this._lastWindowWidth);
  }
}
