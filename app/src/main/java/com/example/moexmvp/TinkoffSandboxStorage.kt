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

    fun getToken(context: Context): String? {
        val app = context.applicationContext
        val primary = prefs(context).getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
        if (primary != null) {
            val m = mirrorPrefs(app)
            if (m.getString(KEY_TOKEN, null) != primary) {
                m.edit().putString(KEY_TOKEN, primary).apply()
            }
            return primary
        }
        val mirrored = mirrorPrefs(app).getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        runCatching {
            prefs(app).edit().putString(KEY_TOKEN, mirrored).apply()
        }
        return mirrored
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

    fun resolveExecUiState(context: Context): SandboxExecUiState {
        val t = getToken(context)
        val a = getAccountId(context)
        if (t.isNullOrBlank() || a.isNullOrBlank()) return SandboxExecUiState.MissingCredentials
        return SandboxExecUiState.Ready
    }

    /**
     * Читает токен и счёт с диска; при пустых prefs подставляет значения из [BuildConfig] (sandbox-token.properties),
     * если они заданы при сборке. Безопасно вызывать с главного потока (внутри — [Dispatchers.IO]).
     */
    suspend fun hydrateCredentialsForUi(context: Context): Pair<String, String> =
        withContext(Dispatchers.IO) {
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
}
