package rofil.movie.app.data

import android.content.Context

/** Small persisted playback preference surface shared by settings and players. */
class PlaybackPreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("playback_preferences", Context.MODE_PRIVATE)

    var autoPlayInline: Boolean
        get() = preferences.getBoolean(KEY_AUTO_PLAY, true)
        set(value) { preferences.edit().putBoolean(KEY_AUTO_PLAY, value).apply() }

    companion object {
        private const val KEY_AUTO_PLAY = "auto_play_inline"
    }
}
