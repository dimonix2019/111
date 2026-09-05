/**
 * Backward-compatible entry. Prefer split scripts in index.html:
 *   replay-sim.js → replay-equity.js → replay-signals.js → replay-engine-core.js
 */
(function () {
  if (typeof BarReplayEngine === 'function' && typeof TRADE_COLUMNS !== 'undefined') return;
  console.error('[moex] Load replay-sim / replay-equity / replay-signals / replay-engine-core before app.js');
})();
