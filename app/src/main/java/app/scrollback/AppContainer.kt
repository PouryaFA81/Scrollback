package app.scrollback

import android.content.Context
import app.scrollback.data.db.AppDatabase
import app.scrollback.data.prefs.SettingsStore
import app.scrollback.data.repo.DuplicateRepository
import app.scrollback.data.repo.LibraryRepository
import app.scrollback.data.source.MediaSourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** In-memory lock state for this process. */
data class Session(
    val appUnlocked: Boolean = false,
    val hiddenRevealed: Boolean = false,
)

/** Manual dependency container (kept simple on purpose; no DI framework). */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(context)
    val settings = SettingsStore(context)
    val credentials = app.scrollback.accounts.CredentialStore(context)
    val accounts = app.scrollback.accounts.AccountStore(context, credentials)
    val auth = app.scrollback.remote.RemoteAuth()
    val http = app.scrollback.remote.Http.client(context, auth)
    val dav = app.scrollback.remote.webdav.WebDavClient(http)
    val servers = app.scrollback.accounts.ServerManager(accounts, auth, http, appScope)
    val sources = MediaSourceRegistry(context, accounts, dav)
    /** v0.7: SFTP + SMB connections (one live connection per account, opened on demand). */
    val remoteStorage = app.scrollback.remote.fs.RemoteStorage(accounts)
    val library = LibraryRepository(context, db, sources, appScope)
    val playback = app.scrollback.player.Playback(context, http, auth, remoteStorage)
    val downloader = app.scrollback.remote.Downloader(context, http, auth)
    val sync = app.scrollback.sync.SyncEngine(db, accounts, dav)
    val duplicates = DuplicateRepository(db, library)
    val files = app.scrollback.files.FileRepository(context, remoteStorage)
    /** In-app updates: GitHub Releases, only on demand (Settings → Check for update). */
    val updater = app.scrollback.update.Updater(context, http, credentials)
    val vert = app.scrollback.convert.VertService(context, http, credentials)
    /** v0.10 office suite: document copies for the viewers, recent documents. */
    val office = app.scrollback.office.OfficeIo(context, files)
    /** v0.11 nearby sharing (LocalSend v2); runs only while its screen is open or a transfer is active. */
    val nearby = app.scrollback.nearby.NearbyManager(context, http, credentials, files, appScope)

    private val _session = MutableStateFlow(Session())
    val session: StateFlow<Session> = _session.asStateFlow()

    /** Without a PIN there is nothing to hide behind, so hidden items are always shown. */
    val hasPin: StateFlow<Boolean> = settings.settings.map { it.hasPin }.stateIn(appScope, SharingStarted.Eagerly, false)

    init {
        for (t in listOf(app.scrollback.data.source.SourceType.SFTP, app.scrollback.data.source.SourceType.SMB)) {
            sources.factories[t] = { e ->
                e.accountId?.let { acc ->
                    app.scrollback.remote.fs.RemoteFsMediaSource(e.id, t, acc, e.uri, remoteStorage, sources.includeAudio)
                }
            }
        }
        appScope.launch {
            hasPin.collect { has -> if (!has) _session.update { it.copy(hiddenRevealed = true) } }
        }
        appScope.launch { library.ensurePlaylistUuids() }
    }

    /** Subsonic/OpenSubsonic and Jellyfin clients. */
    val musicServers = app.scrollback.accounts.MusicServers(accounts, servers, auth, http, sources, library, BuildConfigInfo.versionName(context))
    val musicServersReady = true

    /** v0.12 Watch: Jellyfin movies and shows (same accounts as Jellyfin music). */
    val watch = app.scrollback.watch.Watch(context, accounts, http, BuildConfigInfo.versionName(context), appScope)

    suspend fun signInMusicServer(type: app.scrollback.accounts.AccountType, url: String, user: String, password: String) {
        musicServers.signIn(type, url, user, password)
    }

    suspend fun addMusicServerSource(accountId: String) = musicServers.addSource(accountId)

    /** UI-side handle on the background music service. */
    val music = app.scrollback.player.MusicPlayer(context, appScope, library)

    private var lastSync = 0L

    /** Background library sync with Nextcloud (on app start/stop and "Sync now"); never blocks the UI. */
    fun syncSoon(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < 60_000) return
        lastSync = now
        appScope.launch { sync.syncAll() }
    }

    fun unlockApp() = _session.update { it.copy(appUnlocked = true) }
    fun revealHidden(reveal: Boolean) = _session.update { it.copy(hiddenRevealed = reveal || !hasPin.value) }
    fun lockAll() { _session.value = Session(appUnlocked = false, hiddenRevealed = !hasPin.value) }
}
