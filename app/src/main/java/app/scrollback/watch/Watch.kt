// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.watch

import android.content.Context
import app.scrollback.accounts.AccountStore
import app.scrollback.accounts.AccountType
import app.scrollback.accounts.ServerAccount
import app.scrollback.remote.jellyfin.JellyfinVideoApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient

/**
 * v0.12 Watch: movies and shows from the Jellyfin accounts added in Servers (the same sign-in and
 * token as Jellyfin music). One account is active at a time; the choice is remembered.
 */
class Watch(
    context: Context,
    private val accounts: AccountStore,
    private val http: OkHttpClient,
    private val appVersion: String,
    scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private val selected = MutableStateFlow(prefs.getString("account", null))

    val jellyfin: StateFlow<List<ServerAccount>> = accounts.accounts
        .map { list -> list.filter { it.type == AccountType.JELLYFIN } }
        .stateIn(scope, SharingStarted.Eagerly, accounts.accounts.value.filter { it.type == AccountType.JELLYFIN })

    /** The active account (the remembered one, else the first Jellyfin account). */
    val active: StateFlow<ServerAccount?> = combine(jellyfin, selected) { list, id -> list.firstOrNull { it.id == id } ?: list.firstOrNull() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun select(id: String) { prefs.edit().putString("account", id).apply(); selected.value = id }

    private val apis = HashMap<String, JellyfinVideoApi>()

    fun api(account: ServerAccount): JellyfinVideoApi = synchronized(apis) {
        apis.getOrPut(account.id + account.baseUrl + account.userId) {
            JellyfinVideoApi(http, account.baseUrl, account.userId, account.id, appVersion) { accounts.secret(account.id) }
        }
    }

    fun api(): JellyfinVideoApi? = active.value?.let(::api)

    /** Bumped after played/favorite changes or playback so lists refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    fun changed() { _version.value++ }
}
