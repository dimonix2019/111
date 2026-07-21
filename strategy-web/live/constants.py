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

TATN_FALLBACK_ID = "TATN_TQBR"
TATNP_FALLBACK_ID = "TATNP_TQBR"

DEFAULT_Z_ENTRY = 1.3
DEFAULT_Z_EXIT = 1.2

SPREAD_LOT_RESERVE_CASH_FRACTION = 0.25
SPREAD_LOT_RESERVE_MIN_RUB = 2_000.0
SPREAD_LOT_MARGIN_RATE_PER_LEG = 0.30
SPREAD_LOT_COMMISSION_BUFFER_FRACTION = 0.002
SPREAD_LOT_MIN_LOTS = 1
SPREAD_LOT_MAX_LOTS = 80
SPREAD_LOT_MARGIN_PAIR_FRACTION = 0.50
SPREAD_LOT_PROD_DEFAULT_LEVERAGE = 7.0

MONITOR_INTERVAL_SEC = 45.0
# Макс. рёбер в плане; >1 → skip_gap без AUTO-реплея.
MONITOR_CATCHUP_MAX_EDGES = 8
# Sync ISS в тике монитора — не блокировать надолго.
MONITOR_SYNC_TIMEOUT_SEC = 12.0
# Heartbeat в live_events, чтобы видеть «дыры».
MONITOR_HEARTBEAT_SEC = 300.0
# Watchdog: тик старше N сек при monitor_wanted → stale (soft→hard restart).
MONITOR_STALE_SEC = 180.0
USER_AGENT = "MOEX-MVP-Web-Replay"
