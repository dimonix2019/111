package com.example.moexmvp

/**
 * T‑Invest REST: SandboxService для виртуальных счетов — хост песочницы (`sandbox-invest-public-api`),
 * не продовый `invest-public-api` (другой контур).
 * Док: developer.tinkoff.ru → «Песочница и prod» (различие URL).
 */
private const val SBX_HOST_TBANK = "https://sandbox-invest-public-api.tbank.ru/rest"
private const val SBX_HOST_TINKOFF = "https://sandbox-invest-public-api.tinkoff.ru/rest"
private const val CONTRACT = "tinkoff.public.invest.api.contract.v1"

internal val TINVEST_SANDBOX_REST_PREFIXES: List<String> = listOf(
    "$SBX_HOST_TBANK/$CONTRACT/SandboxService",
    "$SBX_HOST_TINKOFF/$CONTRACT/SandboxService",
)

internal val TINVEST_SANDBOX_INSTRUMENTS_PREFIXES: List<String> = listOf(
    "$SBX_HOST_TBANK/$CONTRACT/InstrumentsService",
    "$SBX_HOST_TINKOFF/$CONTRACT/InstrumentsService",
)
