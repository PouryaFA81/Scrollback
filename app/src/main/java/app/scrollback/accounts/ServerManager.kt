// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.accounts

import app.scrollback.remote.DirectAddress
import app.scrollback.remote.Explain
import app.scrollback.remote.HttpException
import app.scrollback.remote.ProbeKind
import app.scrollback.remote.RemoteAuth
import app.scrollback.remote.ServerProbe
import app.scrollback.remote.nextcloud.LoginFlowV2
import app.scrollback.remote.nextcloud.LoginUrls
import app.scrollback.remote.nextcloud.NextcloudPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** One line of a "Test connection" report. [ok] null = information only. */
data class ReportLine(val ok: Boolean?, val title: String, val detail: String)

/** Result of "Test connection": plain-words lines, plus an address worth saving when redirects point elsewhere. */
data class ConnectionReport(val lines: List<ReportLine>, val suggestedAddress: String?, val allGood: Boolean)

/**
 * Signs server accounts in and out and keeps [RemoteAuth] in sync with the stored accounts, so every
 * request to a known server (player, thumbnails, WebDAV, downloads) carries its credentials.
 */
class ServerManager(
    private val accounts: AccountStore,
    private val auth: RemoteAuth,
    private val http: OkHttpClient,
    scope: CoroutineScope,
) {
    /** Per-type hooks registered by music clients (Subsonic/Jellyfin) to install resolvers/headers. */
    private val installers = HashMap<AccountType, (ServerAccount, String) -> Unit>()

    /** Per-type signed-in checks against one exact address (throws on failure). */
    private val checkers = HashMap<AccountType, (ServerAccount, String) -> Unit>()

    init {
        scope.launch(Dispatchers.IO) {
            var known = emptySet<String>()
            accounts.accounts.collect { list ->
                list.forEach { install(it) }
                (known - list.map { it.baseUrl }.toSet()).forEach(auth::clearHeaders)
                known = list.map { it.baseUrl }.toSet()
            }
        }
        checkers[AccountType.NEXTCLOUD] = { a, base ->
            val req = Request.Builder().url("$base/ocs/v2.php/cloud/user?format=json").header("OCS-APIRequest", "true")
                .tag(DirectAddress::class.java, DirectAddress).build()
            http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw HttpException(r.code, "Nextcloud user check failed") }
        }
    }

    fun registerInstaller(type: AccountType, installer: (ServerAccount, String) -> Unit) {
        installers[type] = installer
        accounts.accounts.value.filter { it.type == type }.forEach { install(it) }
    }

    fun registerChecker(type: AccountType, checker: (ServerAccount, String) -> Unit) { checkers[type] = checker }

    private fun install(a: ServerAccount) {
        if (!a.type.isNetworkStorage) auth.setAlternate(a.baseUrl, a.altUrl, a.preferAlt)
        val secret = accounts.secret(a.id) ?: return
        when (a.type) {
            AccountType.NEXTCLOUD -> auth.setHeaders(a.baseUrl, NextcloudPaths.authHeader(a.loginName, secret))
            else -> installers[a.type]?.invoke(a, secret)
        }
    }

    val loginFlow = LoginFlowV2(http)

    /**
     * Completes a Nextcloud Login Flow v2 result into a stored account. The address that worked for
     * the sign-in stays the main address; if Nextcloud calls itself by another host name (its
     * overwrite.cli.url, often a LAN or VPN name), that one is saved as the alternate address.
     */
    suspend fun addNextcloud(result: LoginFlowV2.Result, workingBase: String): ServerAccount = withContext(Dispatchers.IO) {
        val (base, alt) = LoginUrls.accountAddresses(ServerAccount.normaliseUrl(workingBase), result.server)
        val headers = NextcloudPaths.authHeader(result.loginName, result.appPassword)
        val (userId, display) = loginFlow.currentUser(base, headers)
        val existing = accounts.accounts.value.firstOrNull { it.type == AccountType.NEXTCLOUD && it.baseUrl == base && it.userId == userId }
        val account = (existing ?: ServerAccount(type = AccountType.NEXTCLOUD, baseUrl = base, loginName = result.loginName))
            .copy(
                loginName = result.loginName, userId = userId, displayName = display,
                syncEnabled = existing?.syncEnabled ?: accounts.accounts.value.none { it.syncEnabled },
                altUrl = existing?.altUrl ?: alt,
            )
        accounts.upsert(account, result.appPassword)
        install(account)
        account
    }

    fun add(account: ServerAccount, secret: String) {
        accounts.upsert(account, secret)
        install(account)
    }

    fun remove(id: String) {
        accounts.get(id)?.let { auth.clearHeaders(it.baseUrl) }
        accounts.remove(id)
    }

    /** Saves (or clears) the alternate address. */
    fun setAlternate(id: String, alt: String?, prefer: Boolean = false) {
        val clean = alt?.trim()?.takeIf { it.isNotEmpty() }?.let { ServerAccount.normaliseUrl(it) }
        accounts.update(id) { it.copy(altUrl = clean, preferAlt = prefer && clean != null) }
    }

    /** Validates an address typed for a new server: follows redirects and returns the base URL to save. */
    fun resolveNewAddress(kind: ProbeKind, typed: String): ServerProbe.Result =
        ServerProbe.probe(http, kind, ServerAccount.normaliseUrl(typed))

    /** "Test connection": each address, its redirects, whether the server is what we expect and whether the sign-in works. */
    suspend fun testConnection(a: ServerAccount): ConnectionReport = withContext(Dispatchers.IO) {
        val kind = probeKind(a.type) ?: return@withContext ConnectionReport(listOf(ReportLine(null, "Not supported", "This kind of server has no connection test.")), null, false)
        val lines = ArrayList<ReportLine>()
        var suggestion: String? = null
        var anyWorks = false
        val addresses = listOfNotNull("Main address" to a.baseUrl, a.altUrl?.let { "Alternate address" to it })
        for ((label, addr) in addresses) {
            val p = ServerProbe.probe(http, kind, addr)
            lines += ReportLine(p.recognised, "$label · ${addr.substringAfter("://")}", if (p.recognised) "Reachable: ${p.serverInfo}" else p.problem ?: "Not reachable")
            if (p.hops.isNotEmpty()) {
                lines += ReportLine(null, "Redirects (${p.hops.size})", (p.hops.map(Explain::hop) + listOfNotNull(Explain.chain(p.hops))).joinToString("\n"))
            }
            if (!p.recognised) continue
            val canonical = p.canonical
            if (canonical != null && suggestion == null && canonical != a.altUrl) {
                val ch = canonical.toHttpUrlOrNull()?.host
                val ah = addr.toHttpUrlOrNull()?.host
                // Same-host https upgrades already keep the sign-in; other hosts need the user's OK.
                if (ch != null && !ch.equals(ah, ignoreCase = true)) suggestion = canonical
            }
            val check = checkers[a.type]
            if (check == null) continue
            val host = addr.toHttpUrlOrNull()
            val signedIn = runCatching { check(a, addr) }
            signedIn.onSuccess { anyWorks = true; lines += ReportLine(true, "Signed in", "Your saved sign-in works at this address.") }
            signedIn.onFailure { e -> lines += ReportLine(false, "Sign-in", Explain.error(e, host?.host ?: addr, host?.port ?: 443)) }
        }
        auth.route(a.baseUrl)?.let { r ->
            lines += ReportLine(null, "Currently using", if (r.preferAlternate) "the alternate address (the main one failed last time)" else "the main address, falling back to the alternate one")
        }
        if (a.altUrl == null) lines += ReportLine(null, "Tip", "Add an alternate address (for example your LAN or VPN address) and Scrollback switches between the two automatically.")
        ConnectionReport(lines, suggestion, anyWorks)
    }

    companion object {
        fun probeKind(type: AccountType): ProbeKind? = when (type) {
            AccountType.NEXTCLOUD -> ProbeKind.NEXTCLOUD
            AccountType.SUBSONIC -> ProbeKind.SUBSONIC
            AccountType.JELLYFIN -> ProbeKind.JELLYFIN
            else -> null
        }
    }
}
