package com.example.moexmvp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "TinkoffSandbox"
private const val PREFS_FILE = "tinkoff_sandbox_secure"
private const val PLAIN_PREFS_FILE = "tinkoff_sandbox_plain"
/** Дублирует токен и accountId в открытом виде — только для песочницы; переживает сбой Keystore/EncryptedPrefs после обновления APK. */
private const val PUBLIC_MIRROR_PREFS = "tinkoff_sandbox_public_mirror"
private const val KEY_TOKEN = "sandbox_api_token"
private const val KEY_ACCOUNT_ID = "sandbox_account_id"
private const val KEY_PROD_TOKEN = "prod_api_token"
private const val KEY_PROD_ACCOUNT_ID = "prod_account_id"
private const val KEY_EXECUTION_MODE = "execution_mode"
/** When true, «Принять» на карточке сигнала шлёт рыночные заявки в SandboxService. */
private const val KEY_EXECUTE_SIGNALS_ON_SANDBOX = "execute_signals_on_sandbox"
/** Legacy: раньше один переключатель; теперь разделён на «авто-заявки на демо» и «фильтр портфеля». */
private const val KEY_SANDBOX_ENTRY_MODE = "sandbox_entry_mode"
/** Обратные заявки сразу после сигнала входа (без карточки «Принять»); настраивается только миграцией со старых версий или вручную в prefs. */
private const val KEY_SANDBOX_AUTO_EXECUTE_SPREAD = "sandbox_auto_execute_spread"
/** Какие входы попадают в расчёт списка на «Портфеле»: false = только после «Принять», true = только авто-исполнения из журнала. */
private const val KEY_PORTFOLIO_LEDGER_INCLUDE_AUTO = "portfolio_ledger_include_auto"
private const val KEY_SANDBOX_PORTFOLIO_MODE_SPLIT_MIGRATED = "sandbox_portfolio_mode_split_migrated_v1"
private const val KEY_SANDBOX_NOTIFY_LEVERAGE = "sandbox_notify_leverage"
private const val KEY_PORTFOLIO_TRADE_AMOUNT_RUB = "portfolio_trade_amount_rub"

internal object TinkoffSandboxStorage {

    private val lock = Any()
    @Volatile
    private var prefsInstance: SharedPreferences? = null

    private fun mirrorPrefs(app: Context): SharedPreferences =
        app.getSharedPreferences(PUBLIC_MIRROR_PREFS, Context.MODE_PRIVATE)

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun prefs(context: Context): SharedPreferences {
        prefsInstance?.let { return it }
        synchronized(lock) {
            prefsInstance?.let { return it }
            val app = context.applicationContext
            prefsInstance = try {
                createEncryptedPrefs(app)
            } catch (e: Exception) {
                Log.e(TAG, "EncryptedSharedPreferences недоступны, используем обычные prefs (только песочница)", e)
                app.getSharedPreferences(PLAIN_PREFS_FILE, Context.MODE_PRIVATE)
            }
            return prefsInstance!!
        }
    }

    private fun normalizeStoredCredential(raw: String): String = normalizeInvestToken(raw)

    fun getToken(context: Context): String? {
        val app = context.applicationContext
        val primary = prefs(context).getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
        if (primary != null) {
            val norm = normalizeStoredCredential(primary)
            if (norm != primary) setToken(context, norm.takeIf { it.isNotEmpty() })
            if (norm.isEmpty()) return null
            val m = mirrorPrefs(app)
            if (m.getString(KEY_TOKEN, null) != norm) {
                m.edit().putString(KEY_TOKEN, norm).apply()
            }
            return norm
        }
        val mirrored = mirrorPrefs(app).getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val norm = normalizeStoredCredential(mirrored)
        if (norm != mirrored) setToken(context, norm.takeIf { it.isNotEmpty() })
        if (norm.isEmpty()) return null
        runCatching {
            prefs(app).edit().putString(KEY_TOKEN, norm).apply()
        }
        return norm
    }

    fun setToken(context: Context, token: String?) {
        val t = normalizeInvestToken(token?.trim().orEmpty())
        val app = context.applicationContext
        prefs(context).edit().apply {
            if (t.isEmpty()) remove(KEY_TOKEN) else putString(KEY_TOKEN, t)
        }.apply()
        mirrorPrefs(app).edit().apply {
            if (t.isEmpty()) remove(KEY_TOKEN) else putString(KEY_TOKEN, t)
        }.apply()
    }

    fun getAccountId(context: Context): String? {
        val app = context.applicationContext
        val primary = prefs(context).getString(KEY_ACCOUNT_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
        if (primary != null) {
            val m = mirrorPrefs(app)
            if (m.getString(KEY_ACCOUNT_ID, null) != primary) {
                m.edit().putString(KEY_ACCOUNT_ID, primary).apply()
            }
            return primary
        }
        val mirrored = mirrorPrefs(app).getString(KEY_ACCOUNT_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        runCatching {
            prefs(app).edit().putString(KEY_ACCOUNT_ID, mirrored).apply()
        }
        return mirrored
    }

    fun setAccountId(context: Context, accountId: String?) {
        val id = accountId?.trim().orEmpty()
        val app = context.applicationContext
        prefs(context).edit().apply {
            if (id.isEmpty()) remove(KEY_ACCOUNT_ID) else putString(KEY_ACCOUNT_ID, id)
        }.apply()
        mirrorPrefs(app).edit().apply {
            if (id.isEmpty()) remove(KEY_ACCOUNT_ID) else putString(KEY_ACCOUNT_ID, id)
        }.apply()
    }

    fun getProdToken(context: Context): String? =
        prefs(context).getString(KEY_PROD_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setProdToken(context: Context, token: String?) {
        val t = normalizeInvestToken(token?.trim().orEmpty())
        prefs(context).edit().apply {
            if (t.isEmpty()) remove(KEY_PROD_TOKEN) else putString(KEY_PROD_TOKEN, t)
        }.apply()
    }

    fun getProdAccountId(context: Context): String? =
        prefs(context).getString(KEY_PROD_ACCOUNT_ID, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setProdAccountId(context: Context, accountId: String?) {
        val id = accountId?.trim().orEmpty()
        prefs(context).edit().apply {
            if (id.isEmpty()) remove(KEY_PROD_ACCOUNT_ID) else putString(KEY_PROD_ACCOUNT_ID, id)
        }.apply()
    }

    fun getExecutionMode(context: Context): TinkoffExecutionMode {
        val raw = prefs(context).getString(KEY_EXECUTION_MODE, TinkoffExecutionMode.Sandbox.name)
            ?.trim()
            .orEmpty()
        return runCatching { TinkoffExecutionMode.valueOf(raw) }.getOrDefault(TinkoffExecutionMode.Sandbox)
    }

    fun setExecutionMode(context: Context, mode: TinkoffExecutionMode) {
        prefs(context).edit().putString(KEY_EXECUTION_MODE, mode.name).apply()
    }

    fun getActiveToken(context: Context, mode: TinkoffExecutionMode = getExecutionMode(context)): String? =
        when (mode) {
            TinkoffExecutionMode.Sandbox -> getToken(context)
            TinkoffExecutionMode.Prod -> getProdToken(context)
        }

    fun getActiveAccountId(context: Context, mode: TinkoffExecutionMode = getExecutionMode(context)): String? =
        when (mode) {
            TinkoffExecutionMode.Sandbox -> getAccountId(context)
            TinkoffExecutionMode.Prod -> getProdAccountId(context)
        }

    private fun prefsWithMigration(context: Context): SharedPreferences {
        val p = prefs(context)
        if (p.getBoolean(KEY_SANDBOX_PORTFOLIO_MODE_SPLIT_MIGRATED, false)) return p
        synchronized(lock) {
            val p2 = prefs(context)
            if (p2.getBoolean(KEY_SANDBOX_PORTFOLIO_MODE_SPLIT_MIGRATED, false)) return p2
            val legacyAuto = p2.getString(KEY_SANDBOX_ENTRY_MODE, "MANUAL") == "AUTO"
            p2.edit()
                .putBoolean(KEY_SANDBOX_AUTO_EXECUTE_SPREAD, legacyAuto)
                .putBoolean(KEY_PORTFOLIO_LEDGER_INCLUDE_AUTO, legacyAuto)
                .putBoolean(KEY_SANDBOX_PORTFOLIO_MODE_SPLIT_MIGRATED, true)
                .apply()
        }
        return prefs(context)
    }

    /** В портфель включать сделки по авто‑исполнению (true) или только по «Принять» (false). На песочницу заявки не влияет. */
    fun isPortfolioLedgerIncludeAuto(context: Context): Boolean =
        prefsWithMigration(context).getBoolean(KEY_PORTFOLIO_LEDGER_INCLUDE_AUTO, false)

    fun setPortfolioLedgerIncludeAuto(context: Context, includeAutoTrades: Boolean) {
        setPortfolioDemoEntryMode(context, includeAutoTrades)
    }

    /**
     * Режим входа на «Портфеле»: false — карточка «Принять»; true — сразу 2 заявки на демо.
     * Синхронно задаёт фильтр списка сделок (ручное / авто демо).
     */
    fun setPortfolioDemoEntryMode(context: Context, auto: Boolean) {
        prefsWithMigration(context).edit()
            .putBoolean(KEY_PORTFOLIO_LEDGER_INCLUDE_AUTO, auto)
            .putBoolean(KEY_SANDBOX_AUTO_EXECUTE_SPREAD, auto)
            .apply()
    }

    /** Сразу отправлять две заявки на демо без «Принять». */
    fun isSandboxSpreadAutoExecute(context: Context): Boolean =
        isPortfolioLedgerIncludeAuto(context)

    fun setSandboxSpreadAutoExecute(context: Context, auto: Boolean) {
        setPortfolioDemoEntryMode(context, auto)
    }

    fun isExecuteSignalsOnSandbox(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXECUTE_SIGNALS_ON_SANDBOX, true)

    fun setExecuteSignalsOnSandbox(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXECUTE_SIGNALS_ON_SANDBOX, enabled).apply()
    }

    /** Плечо для текста в уведомлениях о сделках (по умолчанию как в портфеле). */
    fun getSandboxNotifyLeverage(context: Context): Double =
        prefs(context).getFloat(KEY_SANDBOX_NOTIFY_LEVERAGE, 7f).toDouble().coerceIn(1.0, 30.0)

    fun setSandboxNotifyLeverage(context: Context, leverage: Double) {
        prefs(context).edit()
            .putFloat(KEY_SANDBOX_NOTIFY_LEVERAGE, leverage.toFloat().coerceIn(1f, 30f))
            .apply()
    }

    /** Целевая сумма в одной сделке на «Портфеле» (лимит лот-сайзинга). */
    fun getPortfolioTradeAmountRub(context: Context): Double =
        prefs(context).getFloat(
            KEY_PORTFOLIO_TRADE_AMOUNT_RUB,
            DEFAULT_PORTFOLIO_TRADE_AMOUNT_RUB.toFloat(),
        ).toDouble().coerceIn(STRATEGY_TEST_ACCOUNT_RUB_MIN, STRATEGY_TEST_ACCOUNT_RUB_MAX)

    fun setPortfolioTradeAmountRub(context: Context, rub: Double) {
        prefs(context).edit()
            .putFloat(
                KEY_PORTFOLIO_TRADE_AMOUNT_RUB,
                rub.toFloat().coerceIn(
                    STRATEGY_TEST_ACCOUNT_RUB_MIN.toFloat(),
                    STRATEGY_TEST_ACCOUNT_RUB_MAX.toFloat(),
                ),
            )
            .apply()
    }

    fun resolveExecUiState(
        context: Context,
        mode: TinkoffExecutionMode = getExecutionMode(context),
    ): SandboxExecUiState {
        val t = getActiveToken(context, mode)
        val a = getActiveAccountId(context, mode)
        if (t.isNullOrBlank() || a.isNullOrBlank()) return SandboxExecUiState.MissingCredentials
        return SandboxExecUiState.Ready
    }

    /**
     * Читает токен и счёт с диска; при пустых prefs подставляет значения из [BuildConfig] (sandbox-token.properties),
     * если они заданы при сборке. Безопасно вызывать с главного потока (внутри — [Dispatchers.IO]).
     */
    suspend fun hydrateCredentialsForUi(
        context: Context,
        mode: TinkoffExecutionMode = getExecutionMode(context),
    ): Pair<String, String> =
        withContext(Dispatchers.IO) {
            when (mode) {
                TinkoffExecutionMode.Sandbox -> {
                    val embedTok = BuildConfig.SANDBOX_TOKEN_EMBED.trim()
                    val embedAcc = BuildConfig.SANDBOX_ACCOUNT_EMBED.trim()
                    var tok = getToken(context)
                    if (tok.isNullOrEmpty() && embedTok.isNotEmpty()) {
                        setToken(context, embedTok)
                        tok = getToken(context)
                    }
                    var acc = getAccountId(context)
                    if (acc.isNullOrEmpty() && embedAcc.isNotEmpty()) {
                        setAccountId(context, embedAcc)
                        acc = getAccountId(context)
                    }
                    Pair(tok.orEmpty(), acc.orEmpty())
                }
                TinkoffExecutionMode.Prod -> Pair(
                    getProdToken(context).orEmpty(),
                    getProdAccountId(context).orEmpty(),
                )
            }
        }
}
