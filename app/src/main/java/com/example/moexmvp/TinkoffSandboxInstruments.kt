package com.example.moexmvp

/**
 * Fallback MOEX identifiers if [tinkoffResolveShareInstrumentId] fails.
 * Prefer UID/FIGI from FindInstrument (see PostOrderRequest.instrument_id).
 */
internal const val TINKOFF_MOEX_TATN_INSTRUMENT_ID = "TATN_TQBR"
internal const val TINKOFF_MOEX_TATNP_INSTRUMENT_ID = "TATNP_TQBR"
