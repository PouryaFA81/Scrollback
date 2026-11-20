// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.scrollback.core.FeedPos
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

private val Context.feedStore: DataStore<Preferences> by preferencesDataStore(name = "feed_state")

/**
 * v0.15: the Feed tab's place, kept on disk (separate from settings, so scrolling doesn't
 * recompose every screen that reads settings): Random or Saved, Random's shuffle seed and the
 * current item of each feed. Survives tab switches, screen lock, and Android closing the app.
 */
class FeedStateStore(private val context: Context) {
    private object K {
        val mode = intPreferencesKey("feed_mode")
        val seed = longPreferencesKey("random_seed")
        fun pos(key: String) = stringPreferencesKey("pos_$key")
    }

    data class Tab(val mode: Int, val seed: Long)

    private val cache = ConcurrentHashMap<String, FeedPos>()

    /** Random/Saved and the shuffle seed; creates and saves a seed the first time. */
    suspend fun tab(): Tab {
        val p = context.feedStore.data.first()
        val seed = p[K.seed] ?: System.currentTimeMillis().also { s -> context.feedStore.edit { it[K.seed] = s } }
        return Tab(p[K.mode] ?: 0, seed)
    }

    suspend fun setMode(mode: Int) { context.feedStore.edit { it[K.mode] = mode } }
    suspend fun setSeed(seed: Long) { context.feedStore.edit { it[K.seed] = seed } }

    fun cached(key: String): FeedPos? = cache[key]

    suspend fun position(key: String): FeedPos? =
        cache[key] ?: FeedPos.decode(context.feedStore.data.first()[K.pos(key)])?.also { cache[key] = it }

    suspend fun savePosition(key: String, pos: FeedPos) {
        if (cache.put(key, pos) == pos) return
        context.feedStore.edit { it[K.pos(key)] = pos.encode() }
    }
}
