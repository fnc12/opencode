package studio.eugenezakharov.opencode.api

import android.content.Context

/** Remembers the last-browsed server folder across launches (mirrors iOS @AppStorage("openFolder.lastPath")). */
class FolderPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("opencode_folder", Context.MODE_PRIVATE)

    var lastPath: String
        get() = prefs.getString(KEY_LAST_PATH, "/") ?: "/"
        set(value) { prefs.edit().putString(KEY_LAST_PATH, value).apply() }

    companion object {
        private const val KEY_LAST_PATH = "lastPath"
    }
}
