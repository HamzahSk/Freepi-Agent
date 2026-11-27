package rofil.movie.app.data

import android.content.Context

/** Persisted choice between independent catalog providers. */
class SourceSelection(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("movie_sources", Context.MODE_PRIVATE)

    val selectedId: String get() = preferences.getString(KEY, "lk21")?.takeIf { it in SOURCE_IDS } ?: "lk21"

    fun select(id: String) {
        require(id in SOURCE_IDS) { "Unknown movie source" }
        preferences.edit().putString(KEY, id).apply()
    }

    companion object {
        const val LK21 = "lk21"
        const val MOVIEBOX = "moviebox"
        val SOURCE_IDS = setOf(LK21, MOVIEBOX)
        private const val KEY = "selected_source"
    }
}
