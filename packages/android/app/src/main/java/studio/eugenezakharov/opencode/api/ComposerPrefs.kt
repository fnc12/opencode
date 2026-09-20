package studio.eugenezakharov.opencode.api

import android.content.Context

/**
 * Remembers the last-used model/agent across sessions/launches (mirrors iOS
 * @AppStorage). An interface so tests can drive [SessionViewModel] with an
 * in-memory fake instead of a real Context-backed SharedPreferences.
 */
interface ComposerPrefs {
    var providerID: String
    var modelID: String
    var agent: String
    fun setModel(providerID: String, modelID: String)
}

/** The production, SharedPreferences-backed [ComposerPrefs]. */
class AndroidComposerPrefs(context: Context) : ComposerPrefs {
    private val prefs = context.getSharedPreferences("opencode_composer", Context.MODE_PRIVATE)

    override var providerID: String
        get() = prefs.getString(KEY_PROVIDER, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PROVIDER, value).apply() }

    override var modelID: String
        get() = prefs.getString(KEY_MODEL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MODEL, value).apply() }

    /** The last-used agent (build / plan / custom). */
    override var agent: String
        get() = prefs.getString(KEY_AGENT, "build") ?: "build"
        set(value) { prefs.edit().putString(KEY_AGENT, value).apply() }

    override fun setModel(providerID: String, modelID: String) {
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
