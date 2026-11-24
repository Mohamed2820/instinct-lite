package dev.momolite

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * User-entered API credentials, stored encrypted on the device only.
 * The key is never logged, never written to any other file, and never leaves
 * the device except as an Authorization header to the chosen provider,
 * and only after the user explicitly enables the provider.
 *
 * Note: EncryptedSharedPreferences is deprecated upstream in favor of plain
 * SharedPreferences, but it is still functional in security-crypto 1.1.0 and
 * is what the user asked for. The app sets allowBackup=false, covering the
 * class's auto-backup warning.
 */
object ApiSettings {
    private const val FILE = "api_settings_encrypted"
    private const val KEY_TOKEN = "cf_api_token"
    private const val KEY_ACCOUNT = "cf_account_id"
    private const val KEY_ENABLED = "provider_enabled"

    private fun prefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    fun saveCredentials(ctx: Context, token: String, accountId: String) {
        prefs(ctx).edit().putString(KEY_TOKEN, token).putString(KEY_ACCOUNT, accountId).apply()
    }

    fun token(ctx: Context): String? = prefs(ctx).getString(KEY_TOKEN, null)
    fun accountId(ctx: Context): String? = prefs(ctx).getString(KEY_ACCOUNT, null)
    fun hasCredentials(ctx: Context): Boolean =
        !token(ctx).isNullOrBlank() && !accountId(ctx).isNullOrBlank()

    fun setEnabled(ctx: Context, enabled: Boolean) { prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply() }
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun clear(ctx: Context) { prefs(ctx).edit().clear().apply() }
}
