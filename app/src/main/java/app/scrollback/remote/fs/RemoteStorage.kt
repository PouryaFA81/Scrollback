package app.scrollback.remote.fs

import app.scrollback.accounts.AccountStore
import app.scrollback.accounts.AccountType
import app.scrollback.accounts.ServerAccount
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Where an SFTP/SMB account points. Stored in [ServerAccount.baseUrl] as `sftp://host:port/root` or `smb://host:port/share/root`. */
data class RemoteEndpoint(val type: AccountType, val host: String, val port: Int, val share: String, val root: String) {
    fun toUrl(): String = when (type) {
        AccountType.SMB -> "smb://$host:$port/$share" + RemotePaths.normalize(root).let { if (it == "/") "" else it }
        else -> "sftp://$host:$port" + RemotePaths.normalize(root).let { if (it == "/") "" else it }
    }

    val label: String get() = if (type == AccountType.SMB) "\\\\$host\\$share" else "$host${if (port != 22) ":$port" else ""}"

    companion object {
        fun parse(type: AccountType, url: String): RemoteEndpoint? {
            val scheme = if (type == AccountType.SMB) "smb://" else "sftp://"
            if (!url.startsWith(scheme)) return null
            val rest = url.removePrefix(scheme)
            val hostPort = rest.substringBefore('/')
            val path = "/" + rest.substringAfter('/', "")
            val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBeforeLast(':', hostPort)
            val port = hostPort.removePrefix(host).removePrefix(":").toIntOrNull() ?: if (type == AccountType.SMB) 445 else 22
            if (host.isBlank()) return null
            return if (type == AccountType.SMB) {
                val share = path.trim('/').substringBefore('/')
                if (share.isBlank()) null
                else RemoteEndpoint(type, host, port, share, RemotePaths.normalize(path.trim('/').substringAfter('/', "")))
            } else RemoteEndpoint(type, host, port, "", RemotePaths.normalize(path))
        }
    }
}

/** Secret part of an SFTP/SMB account, stored as JSON in the encrypted CredentialStore. */
data class RemoteSecret(val password: String = "", val privateKey: String? = null, val passphrase: String? = null) {
    fun toJson(): String = JSONObject().put("password", password)
        .put("privateKey", privateKey ?: JSONObject.NULL).put("passphrase", passphrase ?: JSONObject.NULL).toString()

    companion object {
        fun parse(json: String?): RemoteSecret = runCatching {
            val o = JSONObject(json ?: "{}")
            RemoteSecret(
                password = o.optString("password"),
                privateKey = if (o.isNull("privateKey")) null else o.optString("privateKey").ifBlank { null },
                passphrase = if (o.isNull("passphrase")) null else o.optString("passphrase").ifBlank { null },
            )
        }.getOrDefault(RemoteSecret())
    }
}

/**
 * Keeps one live connection per SFTP/SMB account, opened lazily and re-opened when it drops.
 * Credentials are read from the encrypted store only at connect time and never leave this class.
 */
class RemoteStorage(private val accounts: AccountStore) {
    private val live = ConcurrentHashMap<String, RemoteFs>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun accountsList(): List<ServerAccount> = accounts.accounts.value.filter { it.type.isNetworkStorage }

    fun endpoint(account: ServerAccount): RemoteEndpoint? = RemoteEndpoint.parse(account.type, account.baseUrl)

    /** Opens a fresh connection without caching it (used to test a new account before saving). */
    fun connect(account: ServerAccount, secret: RemoteSecret): RemoteFs {
        val ep = endpoint(account) ?: throw IOException("Invalid server address")
        return when (account.type) {
            AccountType.SFTP -> SftpFs(
                ep.host, ep.port, account.loginName,
                secret.privateKey?.let { SftpAuth.Key(KeyText.normalize(it), secret.passphrase) } ?: SftpAuth.Password(secret.password),
                account.hostKey,
            )
            AccountType.SMB -> {
                val (domain, user) = splitDomain(account.loginName)
                SmbFs(ep.host, ep.port, ep.share, user, secret.password, domain)
            }
            else -> throw IOException("${account.type.label} is not network storage")
        }
    }

    fun fs(accountId: String): RemoteFs = synchronized(locks.getOrPut(accountId) { Any() }) {
        live[accountId]?.takeIf { it.isConnected }?.let { return it }
        live.remove(accountId)?.let { runCatching { it.close() } }
        val account = accounts.get(accountId) ?: throw IOException("Server account was removed")
        connect(account, RemoteSecret.parse(accounts.secret(accountId))).also { live[accountId] = it }
    }

    /** Runs [block] on the account's connection, reconnecting once if the connection went stale. */
    fun <T> use(accountId: String, block: (RemoteFs) -> T): T = try {
        block(fs(accountId))
    } catch (e: IOException) {
        if (e is UnknownHostKeyException || e is HostKeyMismatchException) throw e
        disconnect(accountId)
        block(fs(accountId))
    }

    fun disconnect(accountId: String) { live.remove(accountId)?.let { runCatching { it.close() } } }

    fun disconnectAll() { live.keys.toList().forEach(::disconnect) }

    companion object {
        /** "WORKGROUP\\alice" or "alice@corp" → (domain, user). */
        fun splitDomain(login: String): Pair<String?, String> = when {
            '\\' in login -> login.substringBefore('\\') to login.substringAfter('\\')
            else -> null to login
        }
    }
}

/** Private-key text clean-up: keys pasted through some apps lose their line breaks. Pure, unit-tested. */
object KeyText {
    fun normalize(raw: String): String {
        val t = raw.trim().replace("\r\n", "\n")
        if ('\n' in t || !t.startsWith("-----BEGIN")) return t
        val header = Regex("^-----BEGIN [A-Z0-9 ]+-----").find(t)?.value ?: return t
        val footer = Regex("-----END [A-Z0-9 ]+-----$").find(t)?.value ?: return t
        val body = t.removePrefix(header).removeSuffix(footer).filterNot { it.isWhitespace() }
        return (listOf(header) + body.chunked(70) + footer).joinToString("\n") + "\n"
    }
}
