package studio.eugenezakharov.opencode.api

import android.content.Context

/** Remembers the last-used model across sessions/launches (mirrors iOS @AppStorage). */
class ComposerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("opencode_composer", Context.MODE_PRIVATE)

    var providerID: String
        get() = prefs.getString(KEY_PROVIDER, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PROVIDER, value).apply() }

    var modelID: String
        get() = prefs.getString(KEY_MODEL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MODEL, value).apply() }

    /** The last-used agent (build / plan / custom). */
    var agent: String
        get() = prefs.getString(KEY_AGENT, "build") ?: "build"
        set(value) { prefs.edit().putString(KEY_AGENT, value).apply() }

    fun setModel(providerID: String, modelID: String) {
        prefs.edit()
            .putString(KEY_PROVIDER, providerID)
            .putString(KEY_MODEL, modelID)
            .apply()
    }

    companion object {
        private const val KEY_PROVIDER = "providerID"
        private const val KEY_MODEL = "modelID"
        private const val KEY_AGENT = "agent"
    }
}
