// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.accounts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Kinds of server account. Each one backs a [app.scrollback.data.source.MediaSource] type. */
enum class AccountType(val id: String, val label: String) {
    NEXTCLOUD("nextcloud", "Nextcloud"),
    SUBSONIC("subsonic", "Subsonic / OpenSubsonic"),
    JELLYFIN("jellyfin", "Jellyfin"),
    SFTP("sftp", "SFTP"),
    SMB("smb", "SMB (Windows / Samba share)");

    val isNetworkStorage: Boolean get() = this == SFTP || this == SMB

    companion object {
        fun from(id: String) = entries.firstOrNull { it.id == id } ?: NEXTCLOUD
    }
}

/**
 * A server account. Holds only non-secret metadata; the password / app password / access token
 * lives in [CredentialStore], encrypted with an Android Keystore key, and is never logged.
 */
data class ServerAccount(
    val id: String = UUID.randomUUID().toString(),
    val type: AccountType,
    /** Normalised base URL without a trailing slash, e.g. https://cloud.example.com */
    val baseUrl: String,
    /** The name the user logs in with. */
    val loginName: String,
    /** Server-side user id (Nextcloud user id for WebDAV paths, Jellyfin user id). */
    val userId: String = loginName,
    val displayName: String = loginName,
    /** Use this account for library sync (Nextcloud only). */
    val syncEnabled: Boolean = false,
    val lastSyncAt: Long = 0,
    val lastSyncError: String? = null,
    /** SFTP: pinned host key fingerprint (`SHA256:…`), set when the user trusts it on first connect. */
    val hostKey: String? = null,
    /** v0.14: optional second address (LAN, VPN, Tailscale…); requests fall back between the two. */
    val altUrl: String? = null,
    /** v0.14: try [altUrl] first (set by "Use this address" in Test connection). */
    val preferAlt: Boolean = false,
) {
    val title: String get() = "$displayName · ${baseUrl.removePrefix("https://").removePrefix("http://")}"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.id).put("baseUrl", baseUrl).put("loginName", loginName)
        .put("userId", userId).put("displayName", displayName).put("syncEnabled", syncEnabled)
        .put("lastSyncAt", lastSyncAt).put("lastSyncError", lastSyncError ?: JSONObject.NULL)
        .put("hostKey", hostKey ?: JSONObject.NULL)
        .put("altUrl", altUrl ?: JSONObject.NULL)
        .put("preferAlt", preferAlt)

    companion object {
        fun fromJson(o: JSONObject) = ServerAccount(
            id = o.getString("id"),
            type = AccountType.from(o.getString("type")),
            baseUrl = o.getString("baseUrl"),
            loginName = o.getString("loginName"),
            userId = o.optString("userId", o.getString("loginName")),
            displayName = o.optString("displayName", o.getString("loginName")),
            syncEnabled = o.optBoolean("syncEnabled", false),
            lastSyncAt = o.optLong("lastSyncAt", 0),
            lastSyncError = if (o.isNull("lastSyncError")) null else o.optString("lastSyncError"),
            hostKey = if (o.isNull("hostKey")) null else o.optString("hostKey").ifBlank { null },
            altUrl = if (o.isNull("altUrl")) null else o.optString("altUrl").ifBlank { null },
            preferAlt = o.optBoolean("preferAlt", false),
        )

        /** "cloud.example.com/" -> "https://cloud.example.com". Plain http only when typed explicitly. */
        fun normaliseUrl(input: String): String {
            var u = input.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            u = u.removeSuffix("/index.php").removeSuffix("/remote.php/dav").removeSuffix("/web")
            return u.trimEnd('/')
        }
    }
}

/** Account metadata, persisted as JSON in private SharedPreferences. */
class AccountStore(context: Context, private val credentials: CredentialStore) {
    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)
    private val _accounts = MutableStateFlow(load())
    val accounts: StateFlow<List<ServerAccount>> = _accounts.asStateFlow()

    private fun load(): List<ServerAccount> = runCatching {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).map { ServerAccount.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(list: List<ServerAccount>) {
        prefs.edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
        _accounts.value = list
    }

    fun get(id: String?): ServerAccount? = _accounts.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(account: ServerAccount, secret: String? = null) {
        if (secret != null) credentials.put(account.id, secret)
        save(_accounts.value.filterNot { it.id == account.id } + account)
    }

    @Synchronized
    fun update(id: String, change: (ServerAccount) -> ServerAccount) {
        save(_accounts.value.map { if (it.id == id) change(it) else it })
    }

    @Synchronized
    fun remove(id: String) {
        credentials.remove(id)
        save(_accounts.value.filterNot { it.id == id })
    }

    fun secret(id: String): String? = credentials.get(id)
}

/**
 * Secrets encrypted with AES-256-GCM using a non-exportable Android Keystore key.
 * (EncryptedSharedPreferences is deprecated; this is the same idea without the extra library.)
 * Ciphertext + IV are stored in private SharedPreferences; plaintext never touches disk or logs.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private val alias = "scrollback.credentials.v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun put(id: String, secret: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(secret.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(id, blob).apply()
    }

    @Synchronized
    fun get(id: String): String? {
        val blob = prefs.getString(id, null) ?: return null
        return runCatching {
            val (iv, ct) = blob.split(':').let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun remove(id: String) = prefs.edit().remove(id).apply()
}
