// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import app.scrollback.BuildConfigInfo
import app.scrollback.accounts.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val current: String, val latest: String) : UpdateState
    data class Available(val release: ReleaseInfo, val asset: ReleaseAsset, val current: String) : UpdateState
    data class NoApk(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data object NeedsInstallPermission : UpdateState
    data object Installing : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * In-app updates from GitHub Releases. Runs only when the user taps "Check for update": there is
 * no background polling, no scheduled job and no network traffic otherwise.
 *
 * The repository is private for now, so the anonymous API answers 404. An optional personal
 * access token (read-only "Contents" scope is enough) can be saved; it is stored encrypted in
 * [CredentialStore] and only ever sent to api.github.com.
 */
class Updater(private val context: Context, shared: OkHttpClient, private val credentials: CredentialStore) {
    /** No disk cache: release APKs are large and must never be served stale. */
    private val http = shared.newBuilder().cache(null).build()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val tokenFlow = MutableStateFlow(credentials.get(TOKEN_ID) != null)
    val hasToken: StateFlow<Boolean> = tokenFlow.asStateFlow()

    val currentVersion: String get() = BuildConfigInfo.versionName(context)
    val isDebugBuild: Boolean get() = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun setToken(token: String?) {
        if (token.isNullOrBlank()) credentials.remove(TOKEN_ID) else credentials.put(TOKEN_ID, token.trim())
        tokenFlow.value = !token.isNullOrBlank()
    }

    fun reset() { _state.value = UpdateState.Idle }

    private fun Request.Builder.github(accept: String): Request.Builder {
        header("Accept", accept)
        header("X-GitHub-Api-Version", "2022-11-28")
        credentials.get(TOKEN_ID)?.let { header("Authorization", "Bearer $it") }
        return this
    }

    suspend fun check() = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        _state.value = try {
            val req = Request.Builder().url(GitHubReleases.LATEST_URL).cacheControl(CacheControl.FORCE_NETWORK)
                .github("application/vnd.github+json").build()
            http.newCall(req).execute().use { res ->
                when (res.code) {
                    200 -> {
                        val release = GitHubReleases.parse(res.body.string())
                        val current = currentVersion
                        when {
                            !GitHubReleases.isNewer(release, current) -> UpdateState.UpToDate(current, release.tag)
                            else -> GitHubReleases.pickApk(release, isDebugBuild, android.os.Build.SUPPORTED_ABIS.toList())
                                ?.let { UpdateState.Available(release, it, current) }
                                ?: UpdateState.NoApk(release)
                        }
                    }
                    404 -> UpdateState.Error(
                        if (hasToken.value) "No release found, or the token can't read ${GitHubReleases.REPO}."
                        else "No public release found. The repository is private for now: add a GitHub token below, or try again once it is public."
                    )
                    401 -> UpdateState.Error("GitHub rejected the token. Replace or remove it.")
                    403, 429 -> UpdateState.Error("GitHub rate limit reached. Try again later.")
                    else -> UpdateState.Error("GitHub answered HTTP ${res.code}.")
                }
            }
        } catch (e: IOException) {
            UpdateState.Error("Couldn't reach GitHub (${e.javaClass.simpleName}).")
        } catch (e: Exception) {
            UpdateState.Error("Unexpected answer from GitHub.")
        }
    }

    /** Downloads the APK, checks it is this app signed with the same key, then hands it to PackageInstaller. */
    suspend fun downloadAndInstall(available: UpdateState.Available) = withContext(Dispatchers.IO) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsInstallPermission
            return@withContext
        }
        val release = available.release
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val apk = File(dir, "update.apk")
        try {
            _state.value = UpdateState.Downloading(release, 0f)
            val req = Request.Builder().url(available.asset.apiUrl).github("application/octet-stream").build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                val total = res.body.contentLength().takeIf { it > 0 } ?: available.asset.size
                res.body.byteStream().use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0 && done - lastEmit > 256 * 1024) {
                                lastEmit = done
                                _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            verify(apk)?.let { _state.value = UpdateState.Error(it); apk.delete(); return@withContext }
            _state.value = UpdateState.Installing
            install(apk)
        } catch (e: IOException) {
            apk.delete()
            _state.value = UpdateState.Error("Download failed (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    /** Returns an error message when the APK is not an update of this exact app. */
    private fun verify(apk: File): String? {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive: PackageInfo = pm.getPackageArchiveInfo(apk.path, flags) ?: return "The downloaded file isn't a valid APK."
        if (archive.packageName != context.packageName) return "The APK is for ${archive.packageName}, not this app."
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun PackageInfo.certs() = signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        if (archive.certs().isEmpty() || archive.certs() != installed.certs()) {
            return "The update is signed with a different key than this install, so Android would refuse it. " +
                "Uninstall this copy first (back up anything you need), then install the release APK."
        }
        return null
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Error("Update cancelled.")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Error("Android refused the update (signature or version conflict).")
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Error("Not enough storage to install the update.")
            else -> UpdateState.Error("Install failed${message?.let { ": $it" } ?: "."}")
        }
    }

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        const val TOKEN_ID = "github.token"
    }
}

/** Receives PackageInstaller callbacks: shows the system confirm screen, then reports the result. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(confirm)
            return
        }
        val app = context.applicationContext as? app.scrollback.ScrollbackApp ?: return
        app.container.updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
