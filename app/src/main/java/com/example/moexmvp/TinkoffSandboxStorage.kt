package com.example.moexmvp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val TAG = "TinkoffSandbox"
private const val PREFS_FILE = "tinkoff_sandbox_secure"
private const val PLAIN_PREFS_FILE = "tinkoff_sandbox_plain"
private const val KEY_TOKEN = "sandbox_api_token"
private const val KEY_ACCOUNT_ID = "sandbox_account_id"
/** When true, «Принять» на карточке сигнала шлёт рыночные заявки в SandboxService. */
private const val KEY_EXECUTE_SIGNALS_ON_SANDBOX = "execute_signals_on_sandbox"

internal object TinkoffSandboxStorage {

    private val lock = Any()
    @Volatile
    private var prefsInstance: SharedPreferences? = null

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

    fun getToken(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setToken(context: Context, token: String?) {
        val t = normalizeInvestToken(token?.trim().orEmpty())
        prefs(context).edit().apply {
            if (t.isEmpty()) remove(KEY_TOKEN) else putString(KEY_TOKEN, t)
        }.apply()
    }

    fun getAccountId(context: Context): String? =
        prefs(context).getString(KEY_ACCOUNT_ID, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setAccountId(context: Context, accountId: String?) {
        val id = accountId?.trim().orEmpty()
        prefs(context).edit().apply {
            if (id.isEmpty()) remove(KEY_ACCOUNT_ID) else putString(KEY_ACCOUNT_ID, id)
        }.apply()
    }

    fun isExecuteSignalsOnSandbox(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXECUTE_SIGNALS_ON_SANDBOX, true)

    fun setExecuteSignalsOnSandbox(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXECUTE_SIGNALS_ON_SANDBOX, enabled).apply()
    }

    fun resolveExecUiState(context: Context): SandboxExecUiState {
        if (!isExecuteSignalsOnSandbox(context)) return SandboxExecUiState.Off
        val t = getToken(context)
        val a = getAccountId(context)
        if (t.isNullOrBlank() || a.isNullOrBlank()) return SandboxExecUiState.MissingCredentials
        return SandboxExecUiState.Ready
    }
}
