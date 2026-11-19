package app.scrollback.accounts

import app.scrollback.remote.RemoteAuth
import app.scrollback.remote.nextcloud.LoginFlowV2
import app.scrollback.remote.nextcloud.NextcloudPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

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

    init {
        scope.launch(Dispatchers.IO) {
            var known = emptySet<String>()
            accounts.accounts.collect { list ->
                list.forEach { install(it) }
                (known - list.map { it.baseUrl }.toSet()).forEach(auth::clearHeaders)
                known = list.map { it.baseUrl }.toSet()
            }
        }
    }

    fun registerInstaller(type: AccountType, installer: (ServerAccount, String) -> Unit) {
        installers[type] = installer
        accounts.accounts.value.filter { it.type == type }.forEach { install(it) }
    }

    private fun install(a: ServerAccount) {
        val secret = accounts.secret(a.id) ?: return
        when (a.type) {
            AccountType.NEXTCLOUD -> auth.setHeaders(a.baseUrl, NextcloudPaths.authHeader(a.loginName, secret))
            else -> installers[a.type]?.invoke(a, secret)
        }
    }

    val loginFlow = LoginFlowV2(http)

    /** Completes a Nextcloud Login Flow v2 result into a stored account. */
    suspend fun addNextcloud(result: LoginFlowV2.Result): ServerAccount = withContext(Dispatchers.IO) {
        val base = ServerAccount.normaliseUrl(result.server)
        val headers = NextcloudPaths.authHeader(result.loginName, result.appPassword)
        val (userId, display) = loginFlow.currentUser(base, headers)
        val existing = accounts.accounts.value.firstOrNull { it.type == AccountType.NEXTCLOUD && it.baseUrl == base && it.userId == userId }
        val account = (existing ?: ServerAccount(type = AccountType.NEXTCLOUD, baseUrl = base, loginName = result.loginName))
            .copy(loginName = result.loginName, userId = userId, displayName = display, syncEnabled = existing?.syncEnabled ?: accounts.accounts.value.none { it.syncEnabled })
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
}
