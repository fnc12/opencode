package studio.eugenezakharov.opencode.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json

/**
 * Persists the [ConnectionConfig] in EncryptedSharedPreferences — the Android
 * analog of the iOS Keychain. The config holds a pairing token, so it is
 * encrypted at rest rather than living in plain SharedPreferences.
 *
 * Falls back to plain SharedPreferences only if the crypto provider fails to
 * initialize (e.g. a corrupted keystore on some emulators), so the app never
 * crashes on launch over storage.
 */
class ConnectionStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "opencode_connection",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", it)
        context.getSharedPreferences("opencode_connection_plain", Context.MODE_PRIVATE)
    }

    fun save(config: ConnectionConfig) {
        runCatching {
            prefs.edit().putString(KEY_CONFIG, json.encodeToString(ConnectionConfig.serializer(), config)).apply()
        }.onFailure { Log.e(TAG, "save failed", it) }
    }

    fun load(): ConnectionConfig? {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return null
        return runCatching { json.decodeFromString(ConnectionConfig.serializer(), raw) }.getOrNull()
    }

    fun clear() {
        prefs.edit().remove(KEY_CONFIG).apply()
    }

    companion object {
        private const val TAG = "ConnectionStore"
        private const val KEY_CONFIG = "connection-config"
    }
}
