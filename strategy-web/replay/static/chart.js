/**
 * TradingView lightweight-charts — обёртка setReplayCursor (parity z_chart.html).
 */
const TV = {
  bg: '#131722',
  grid: '#2a2e39',
  text: '#d1d4dc',
  up: '#089981',
  down: '#f23645',
};

class ReplayChart {
  constructor(container) {
    this.container = container;
    this.chart = null;
    this.series = null;
    this.markersPlugin = null;
    this.priceLines = [];
    this.replayCursorLine = null;
    this._init();
    window.addEventListener('resize', () => this.resize());
  }

  _init() {
    if (typeof LightweightCharts === 'undefined') {
      this.container.innerHTML = '<div class="chart-error">lightweight-charts не загружен</div>';
      return;
    }
    const rect = this.container.getBoundingClientRect();
    this.chart = LightweightCharts.createChart(this.container, {
      width: Math.max(300, rect.width),
      height: Math.max(200, rect.height),
      layout: { background: { color: TV.bg }, textColor: TV.text },
      grid: { vertLines: { color: TV.grid }, horzLines: { color: TV.grid } },
      rightPriceScale: { borderColor: TV.grid },
      timeScale: { borderColor: TV.grid, timeVisible: true, secondsVisible: false },
      crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
    });
    this.series = this.chart.addCandlestickSeries({
      upColor: TV.up,
      downColor: TV.down,
      borderVisible: false,
      wickUpColor: TV.up,
      wickDownColor: TV.down,
    });
  }

  resize() {
    if (!this.chart) return;
    const rect = this.container.getBoundingClientRect();
    this.chart.applyOptions({
      width: Math.max(300, rect.width),
      height: Math.max(200, rect.height),
    });
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
    this.series.setData(payload.candles);
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
    this.chart.timeScale().fitContent();
  }
}
