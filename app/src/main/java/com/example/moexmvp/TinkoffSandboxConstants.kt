package com.example.moexmvp

/**
 * T‑Invest REST: SandboxService для виртуальных счетов — хост песочницы (`sandbox-invest-public-api`),
 * не продовый `invest-public-api` (другой контур).
 * Док: developer.tinkoff.ru → «Песочница и prod» (различие URL).
 */
internal val TINVEST_SANDBOX_REST_PREFIXES: List<String> = listOf(
    "https://sandbox-invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.SandboxService",
    // Запасной DNS (старые инструкции / миграции домена)
    "https://sandbox-invest-public-api.tinkoff.ru/rest/tinkoff.public.invest.api.contract.v1.SandboxService",
)
