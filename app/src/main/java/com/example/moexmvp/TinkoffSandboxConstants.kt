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

private const val SBX_SANDBOX_SERVICE = "tinkoff.public.invest.api.contract.v1.SandboxService"
private const val SBX_INSTRUMENTS_SERVICE = "tinkoff.public.invest.api.contract.v1.InstrumentsService"

internal val TINVEST_SANDBOX_REST_PREFIXES: List<String> = listOf(
    "$SBX_HOST_TBANK/$SBX_SANDBOX_SERVICE",
    "$SBX_HOST_TINKOFF/$SBX_SANDBOX_SERVICE",
)

internal val TINVEST_SANDBOX_INSTRUMENTS_PREFIXES: List<String> = listOf(
    "$SBX_HOST_TBANK/$SBX_INSTRUMENTS_SERVICE",
    "$SBX_HOST_TINKOFF/$SBX_INSTRUMENTS_SERVICE",
)
