// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.accounts

import app.scrollback.data.repo.LibraryRepository
import app.scrollback.data.source.MediaSourceRegistry
import app.scrollback.data.source.SourceType
import app.scrollback.remote.DirectAddress
import app.scrollback.remote.HttpException
import app.scrollback.remote.RemoteAuth
import app.scrollback.remote.music.JellyfinAuth
import app.scrollback.remote.music.JellyfinClient
import app.scrollback.remote.music.JellyfinMediaSource
import app.scrollback.remote.music.SubsonicClient
import app.scrollback.remote.music.SubsonicMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Subsonic/OpenSubsonic and Jellyfin accounts behind the same MediaSource abstraction.
 * Songs are stored as `subsonic:<account>/<id>` / `jellyfin:<account>/<id>` and resolved to real
 * stream/cover URLs only when fetched, so tokens never reach the database or cache keys.
 */
class MusicServers(
    private val accounts: AccountStore,
    private val servers: ServerManager,
    private val auth: RemoteAuth,
    private val http: OkHttpClient,
    registry: MediaSourceRegistry,
    private val library: LibraryRepository,
    private val appVersion: String,
) {
    private val subsonic = ConcurrentHashMap<String, SubsonicClient>()

    init {
        servers.registerInstaller(AccountType.SUBSONIC) { a, secret -> subsonic[a.id] = SubsonicClient(http, a, secret) }
        servers.registerInstaller(AccountType.JELLYFIN) { a, token -> auth.setHeaders(a.baseUrl, JellyfinAuth.authHeaders(a.id, appVersion, token)) }
        servers.registerChecker(AccountType.SUBSONIC) { a, base ->
            val secret = accounts.secret(a.id) ?: throw HttpException(401, "No saved password")
            SubsonicClient(http, a.copy(baseUrl = base), secret, direct = true).ping()
        }
        servers.registerChecker(AccountType.JELLYFIN) { a, base ->
            val req = okhttp3.Request.Builder().url("$base/Users/${a.userId}").tag(DirectAddress::class.java, DirectAddress).build()
            http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw HttpException(r.code, "Jellyfin user check failed") }
        }
        auth.setResolver("subsonic") { u -> parse(u)?.let { (acc, id) -> subsonicClient(acc)?.streamUrl(id) } }
        auth.setResolver("subsonic-cover") { u -> parse(u)?.let { (acc, id) -> subsonicClient(acc)?.coverUrl(id) } }
        auth.setResolver("jellyfin") { u -> parse(u)?.let { (acc, id) -> accounts.get(acc)?.let { JellyfinClient(http, it).streamUrl(id) } } }
        auth.setResolver("jellyfin-img") { u -> parse(u)?.let { (acc, id) -> accounts.get(acc)?.let { JellyfinClient(http, it).imageUrl(id) } } }
        registry.factories[SourceType.SUBSONIC] = { e ->
            accounts.get(e.accountId)?.let { a -> subsonicClient(a.id)?.let { SubsonicMediaSource(e.id, a, it, http) } }
        }
        registry.factories[SourceType.JELLYFIN] = { e ->
            accounts.get(e.accountId)?.let { a -> JellyfinMediaSource(e.id, a, JellyfinClient(http, a), http) }
        }
    }

    /** "subsonic:acc/id" -> (acc, id) */
    private fun parse(uri: String): Pair<String, String>? {
        val rest = uri.substringAfter(':', "")
        val acc = rest.substringBefore('/', "")
        val id = rest.substringAfter('/', "")
        return if (acc.isEmpty() || id.isEmpty()) null else acc to id
    }

    private fun subsonicClient(accountId: String): SubsonicClient? {
        val a = accounts.get(accountId) ?: run { subsonic.remove(accountId); return null }
        return subsonic[accountId] ?: accounts.secret(a.id)?.let { SubsonicClient(http, a, it).also { c -> subsonic[a.id] = c } }
    }

    /** Validates the credentials, stores the account (password/token encrypted) and indexes its library. */
    suspend fun signIn(type: AccountType, url: String, user: String, password: String): ServerAccount = withContext(Dispatchers.IO) {
        val base = canonicalBase(type, url)
        val account = when (type) {
            AccountType.SUBSONIC -> {
                val a = ServerAccount(type = type, baseUrl = base, loginName = user)
                SubsonicClient(http, a, password).ping()
                servers.add(a, password)
                a
            }
            AccountType.JELLYFIN -> {
                val draft = ServerAccount(type = type, baseUrl = base, loginName = user)
                val s = JellyfinAuth.authenticate(http, base, user, password, draft.id, appVersion)
                val a = draft.copy(userId = s.userId, displayName = s.userName)
                servers.add(a, s.token) // only the token is kept, never the password
                a
            }
            AccountType.NEXTCLOUD -> error("Use Login Flow v2 for Nextcloud")
            AccountType.SFTP, AccountType.SMB -> error("Use Files → Network storage")
        }
        addSource(account.id)
        account
    }

    /**
     * The address to save for a new server: follows redirects first (http to https, bare domain to
     * www, sub-paths) so the stored address is the one the server really answers on.
     */
    private fun canonicalBase(type: AccountType, typed: String): String {
        val kind = ServerManager.probeKind(type) ?: return ServerAccount.normaliseUrl(typed)
        val p = servers.resolveNewAddress(kind, typed)
        if (!p.reachable) throw java.io.IOException(p.problem ?: "Server not reachable")
        return if (p.recognised) p.canonical ?: p.address else p.address
    }

    suspend fun addSource(accountId: String) {
        val a = accounts.get(accountId) ?: return
        val type = if (a.type == AccountType.SUBSONIC) SourceType.SUBSONIC else SourceType.JELLYFIN
        library.addRemoteSource(type, "${type.id}:${a.id}/library", "♫ ${a.displayName}", a.id)
    }
}
