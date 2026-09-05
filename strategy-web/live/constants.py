"""T‑Invest hosts and sizing constants (parity with MoexConstants / TinkoffSandboxConstants)."""

from __future__ import annotations

SBX_HOST_TBANK = "https://sandbox-invest-public-api.tbank.ru/rest"
SBX_HOST_TINKOFF = "https://sandbox-invest-public-api.tinkoff.ru/rest"
PROD_HOST_TBANK = "https://invest-public-api.tbank.ru/rest"
PROD_HOST_TINKOFF = "https://invest-public-api.tinkoff.ru/rest"

SBX_SANDBOX_SERVICE = "tinkoff.public.invest.api.contract.v1.SandboxService"
SBX_INSTRUMENTS_SERVICE = "tinkoff.public.invest.api.contract.v1.InstrumentsService"
PROD_ORDERS_SERVICE = "tinkoff.public.invest.api.contract.v1.OrdersService"
PROD_INSTRUMENTS_SERVICE = "tinkoff.public.invest.api.contract.v1.InstrumentsService"
PROD_USERS_SERVICE = "tinkoff.public.invest.api.contract.v1.UsersService"
PROD_MARKETDATA_SERVICE = "tinkoff.public.invest.api.contract.v1.MarketDataService"

SANDBOX_PREFIXES = [
    f"{SBX_HOST_TBANK}/{SBX_SANDBOX_SERVICE}",
    f"{SBX_HOST_TINKOFF}/{SBX_SANDBOX_SERVICE}",
]
SANDBOX_INSTRUMENTS_PREFIXES = [
    f"{SBX_HOST_TBANK}/{SBX_INSTRUMENTS_SERVICE}",
    f"{SBX_HOST_TINKOFF}/{SBX_INSTRUMENTS_SERVICE}",
]
PROD_ORDERS_PREFIXES = [
    f"{PROD_HOST_TBANK}/{PROD_ORDERS_SERVICE}",
    f"{PROD_HOST_TINKOFF}/{PROD_ORDERS_SERVICE}",
]
PROD_INSTRUMENTS_PREFIXES = [
    f"{PROD_HOST_TBANK}/{PROD_INSTRUMENTS_SERVICE}",
    f"{PROD_HOST_TINKOFF}/{PROD_INSTRUMENTS_SERVICE}",
]
PROD_USERS_PREFIXES = [
    f"{PROD_HOST_TBANK}/{PROD_USERS_SERVICE}",
    f"{PROD_HOST_TINKOFF}/{PROD_USERS_SERVICE}",
]
PROD_MARKETDATA_PREFIXES = [
    f"{PROD_HOST_TBANK}/{PROD_MARKETDATA_SERVICE}",
    f"{PROD_HOST_TINKOFF}/{PROD_MARKETDATA_SERVICE}",
]
# Sandbox MarketData (same service name under sandbox hosts).
SANDBOX_MARKETDATA_PREFIXES = [
    f"{SBX_HOST_TBANK}/{PROD_MARKETDATA_SERVICE}",
    f"{SBX_HOST_TINKOFF}/{PROD_MARKETDATA_SERVICE}",
]

TATN_FALLBACK_ID = "TATN_TQBR"
TATNP_FALLBACK_ID = "TATNP_TQBR"

DEFAULT_Z_ENTRY = 1.3
DEFAULT_Z_EXIT = 1.2
# Classic tip1m defaults when regime_z_mode OFF (also «широкий» pair).
CLASSIC_Z_ENTRY = 1.6
CLASSIC_Z_EXIT = 1.3
# Legacy tip1m Z-by-regime (узкий/переход/широкий). OFF by default — spread levels are primary.
DEFAULT_REGIME_Z_MODE = False
# Primary AUTO: absolute spread-% levels (no rolling μ/σ Z). See live.spread_levels.
DEFAULT_SPREAD_LEVEL_MODE = True
# Mean-reversion levels on tip1m spread_percent (TATN/TATNP−1)×100.
# Cuts stay 3.5 / 5.5 in live.spread_regime; hysteresis around the cuts:
SPREAD_ENTER_WIDE = 6.1  # Short: cross up through (Prod/Test; 6.2 was too conservative)
SPREAD_EXIT_WIDE = 5.8  # Short: cross down through
SPREAD_ENTER_NARROW = 3.2  # Long: cross down through
SPREAD_EXIT_NARROW = 4.0  # Long: cross up through
DEFAULT_SPREAD_ENTER_WIDE = SPREAD_ENTER_WIDE
DEFAULT_SPREAD_EXIT_WIDE = SPREAD_EXIT_WIDE
DEFAULT_SPREAD_ENTER_NARROW = SPREAD_ENTER_NARROW
DEFAULT_SPREAD_EXIT_NARROW = SPREAD_EXIT_NARROW
# Prod signal path: tip1m Mode B (Testing «касание 1м»). M15 settle unused for AUTO.
SIGNAL_MODE_TIP1M = "tip1m"
# Take-profit % of deposit (0=off). Same options as Testing tip1m.
DEFAULT_TAKE_PROFIT_PCT = 0.0
TAKE_PROFIT_PCT_CHOICES = (0.0, 1.0, 2.0, 3.0)

SPREAD_LOT_RESERVE_CASH_FRACTION = 0.25
SPREAD_LOT_RESERVE_MIN_RUB = 2_000.0
SPREAD_LOT_MARGIN_RATE_PER_LEG = 0.30
SPREAD_LOT_COMMISSION_BUFFER_FRACTION = 0.002
SPREAD_LOT_MIN_LOTS = 1
# Не потолок сделок: размер по плечу (пустой счёт) / запасу маржи (уже в позиции).
# 80 снимали 31.08; после checkout ветки снова зажался — лоты = депозит×плечо/пара.
SPREAD_LOT_MAX_LOTS = 10_000_000
SPREAD_LOT_MARGIN_PAIR_FRACTION = 0.50
SPREAD_LOT_PROD_DEFAULT_LEVERAGE = 7.0

# tip1m: poll often enough to catch minute edges; catchup fills gaps.
MONITOR_INTERVAL_SEC = 20.0
# Макс. рёбер AUTO-догона за тик (consecutive 1м после last_proc).
MONITOR_CATCHUP_MAX_EDGES = 90
# Окно ISS 1м для tip-Z хвоста монитора.
MONITOR_TIP1M_HOURS = 6.0
# Sync ISS в тике монитора — не блокировать надолго.
MONITOR_SYNC_TIMEOUT_SEC = 12.0
# Heartbeat в live_events, чтобы видеть «дыры».
MONITOR_HEARTBEAT_SEC = 300.0
# Watchdog soft: тик старше N сек при monitor_wanted → stale (probe каждые 60 с).
# Запас ≥3× INTERVAL: медленный ISS/SQLite ночью не должен ронять монитор.
MONITOR_STALE_SEC = 180.0
# Legacy M15 settle (M15 close path / overlay only — NOT tip1m AUTO).
MONITOR_BAR_SETTLE_SEC = 90.0
# tip1m AUTO: after 1m bar close wait this long for ISS tip to stabilize (≪ M15 90s).
MONITOR_TIP1M_SETTLE_SEC = 10.0
# Late revise: M15-only path; tip1m completed minutes are final (revise skipped).
MONITOR_Z_REVISE_MIN_DELTA = 0.08
USER_AGENT = "MOEX-MVP-Web-Replay"
