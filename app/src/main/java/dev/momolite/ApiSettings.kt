package dev.momolite

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * User-entered provider settings, stored encrypted on the device only
 * (EncryptedSharedPreferences backed by the Android Keystore).
 * The key is never logged, never written to any other file, and never leaves the
 * device except as an Authorization header to the provider the user chose,
 * and only after the user turned the provider on.
 *
 * EncryptedSharedPreferences is deprecated upstream but still works in security-crypto 1.1.0
 * and matches what the owner asked for. allowBackup is false, so the encrypted file is never
 * copied to a cloud backup (where the Keystore key would not exist anyway).
 */
object ApiSettings {
    private const val FILE = "api_settings_encrypted"
    private const val K_TYPE = "type"
    private const val K_KEY = "api_key"
    private const val K_MODEL = "model"
    private const val K_ACCOUNT = "cf_account_id"
    private const val K_ADDRESS = "address"
    private const val K_ENABLED = "provider_enabled"

    /** Null when the Keystore or the stored file is unusable: callers treat that as "no credentials". */
    private fun prefs(ctx: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(ctx.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx.applicationContext, FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (e: Exception) { null }

    fun storageAvailable(ctx: Context) = prefs(ctx) != null

    /** Saves everything except the key when [apiKey] is blank (so editing the model never wipes the key). */
    fun save(ctx: Context, type: ProviderType, apiKey: String, model: String, accountId: String, address: String): Boolean {
        val p = prefs(ctx) ?: return false
        val e = p.edit().putString(K_TYPE, type.name).putString(K_MODEL, model.trim())
            .putString(K_ACCOUNT, accountId.trim()).putString(K_ADDRESS, address.trim())
        if (apiKey.isNotBlank()) e.putString(K_KEY, apiKey.trim())
        return e.commit()
    }

    /** Full config for use in a request, or null when nothing is stored. */
    fun config(ctx: Context): ProviderConfig? {
        val p = prefs(ctx) ?: return null
        return ProviderConfig(
            ProviderType.parse(p.getString(K_TYPE, null)),
            p.getString(K_KEY, "").orEmpty(),
            p.getString(K_MODEL, "").orEmpty(),
            p.getString(K_ACCOUNT, "").orEmpty(),
            p.getString(K_ADDRESS, "").orEmpty())
    }

    /** What the Settings screen may show back: everything except the key itself. */
    fun hasKey(ctx: Context): Boolean = !prefs(ctx)?.getString(K_KEY, "").isNullOrBlank()

    fun setEnabled(ctx: Context, enabled: Boolean) { prefs(ctx)?.edit()?.putBoolean(K_ENABLED, enabled)?.commit() }
    fun isEnabled(ctx: Context): Boolean = prefs(ctx)?.getBoolean(K_ENABLED, false) ?: false

    fun clear(ctx: Context) {
        prefs(ctx)?.edit()?.clear()?.commit()
        ctx.applicationContext.deleteSharedPreferences(FILE)
    }
}
