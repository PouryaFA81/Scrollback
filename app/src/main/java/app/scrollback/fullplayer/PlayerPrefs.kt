// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.fullplayer

import android.content.Context
import java.security.MessageDigest

/** Full-player preferences (kept apart from the feed's settings) and resume points for files the library doesn't index. */
class PlayerPrefs(context: Context) {
    private val p = context.getSharedPreferences("full_player", Context.MODE_PRIVATE)
    private val resume = context.getSharedPreferences("full_player_resume", Context.MODE_PRIVATE)

    var hardwareDecoding: Boolean
        get() = p.getBoolean("hw", true)
        set(v) = p.edit().putBoolean("hw", v).apply()

    var backgroundAudio: Boolean
        get() = p.getBoolean("bg", false)
        set(v) = p.edit().putBoolean("bg", v).apply()

    var aspect: AspectMode
        get() = runCatching { AspectMode.valueOf(p.getString("aspect", null) ?: "FIT") }.getOrDefault(AspectMode.FIT)
        set(v) = p.edit().putString("aspect", v.name).apply()

    /** Subtitle text scale in percent (VLC --sub-text-scale). */
    var subtitleScale: Int
        get() = p.getInt("sub_scale", 100)
        set(v) = p.edit().putInt("sub_scale", v.coerceIn(50, 250)).apply()

    /** 0xRRGGBB. */
    var subtitleColor: Int
        get() = p.getInt("sub_color", 0xFFFFFF)
        set(v) = p.edit().putInt("sub_color", v).apply()

    var subtitleBackground: Boolean
        get() = p.getBoolean("sub_bg", false)
        set(v) = p.edit().putBoolean("sub_bg", v).apply()

    /** Turn on the first subtitle track when a file has subtitles but none is marked default. */
    var autoSubtitles: Boolean
        get() = p.getBoolean("auto_subs", true)
        set(v) = p.edit().putBoolean("auto_subs", v).apply()

    var autoPip: Boolean
        get() = p.getBoolean("auto_pip", true)
        set(v) = p.edit().putBoolean("auto_pip", v).apply()

    private fun key(uri: String): String =
        MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    fun resumeFor(uri: String): Long = resume.getLong(key(uri), 0)

    fun saveResume(uri: String, positionMs: Long) {
        val e = resume.edit()
        if (positionMs <= 0) e.remove(key(uri)) else e.putLong(key(uri), positionMs)
        e.apply()
        // Keep the store bounded.
        if (resume.all.size > 1000) resume.edit().clear().apply()
    }
}
