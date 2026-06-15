package com.example.moexmvp

/**
 * T‑Invest REST: SandboxService для виртуальных счетов — хост песочницы (`sandbox-invest-public-api`),
 * не продовый `invest-public-api` (другой контур).
 * Док: developer.tinkoff.ru → «Песочница и prod» (различие URL).
 *
 * Важно: путь к методу — **точками**, как в официальной доке, например
 * `/rest/tinkoff.public.invest.api.contract.v1.SandboxService/OpenSandboxAccount`.
 * Вариант с `/v1/SandboxService/` даёт **HTTP 404** на обоих хостах.
 */
private const val SBX_HOST_TBANK = "https://sandbox-invest-public-api.tbank.ru/rest"
private const val SBX_HOST_TINKOFF = "https://sandbox-invest-public-api.tinkoff.ru/rest"
private const val PROD_HOST_TBANK = "https://invest-public-api.tbank.ru/rest"
private const val PROD_HOST_TINKOFF = "https://invest-public-api.tinkoff.ru/rest"

private const val SBX_SANDBOX_SERVICE = "tinkoff.public.invest.api.contract.v1.SandboxService"
private const val SBX_INSTRUMENTS_SERVICE = "tinkoff.public.invest.api.contract.v1.InstrumentsService"
private const val PROD_ORDERS_SERVICE = "tinkoff.public.invest.api.contract.v1.OrdersService"
private const val PROD_INSTRUMENTS_SERVICE = "tinkoff.public.invest.api.contract.v1.InstrumentsService"
private const val PROD_OPERATIONS_SERVICE = "tinkoff.public.invest.api.contract.v1.OperationsService"
private const val PROD_USERS_SERVICE = "tinkoff.public.invest.api.contract.v1.UsersService"

internal val TINVEST_SANDBOX_REST_PREFIXES: List<String> = listOf(
    "$SBX_HOST_TBANK/$SBX_SANDBOX_SERVICE",
    "$SBX_HOST_TINKOFF/$SBX_SANDBOX_SERVICE",
)

internal val TINVEST_SANDBOX_INSTRUMENTS_PREFIXES: List<String> = listOf(
    "$SBX_HOST_TBANK/$SBX_INSTRUMENTS_SERVICE",
    "$SBX_HOST_TINKOFF/$SBX_INSTRUMENTS_SERVICE",
)

internal val TINVEST_PROD_ORDERS_PREFIXES: List<String> = listOf(
    "$PROD_HOST_TBANK/$PROD_ORDERS_SERVICE",
    "$PROD_HOST_TINKOFF/$PROD_ORDERS_SERVICE",
)

internal val TINVEST_PROD_INSTRUMENTS_PREFIXES: List<String> = listOf(
    "$PROD_HOST_TBANK/$PROD_INSTRUMENTS_SERVICE",
    "$PROD_HOST_TINKOFF/$PROD_INSTRUMENTS_SERVICE",
)

internal val TINVEST_PROD_OPERATIONS_PREFIXES: List<String> = listOf(
    "$PROD_HOST_TBANK/$PROD_OPERATIONS_SERVICE",
    "$PROD_HOST_TINKOFF/$PROD_OPERATIONS_SERVICE",
)

internal val TINVEST_PROD_USERS_PREFIXES: List<String> = listOf(
    "$PROD_HOST_TBANK/$PROD_USERS_SERVICE",
    "$PROD_HOST_TINKOFF/$PROD_USERS_SERVICE",
)
