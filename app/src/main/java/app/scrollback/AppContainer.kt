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
    val library = LibraryRepository(context, db, sources)
    val playback = app.scrollback.player.Playback(context, http, auth)
    val downloader = app.scrollback.remote.Downloader(context, http, auth)
    val sync = app.scrollback.sync.SyncEngine(db, accounts, dav)
    val duplicates = DuplicateRepository(db, library)
    val files = app.scrollback.files.FileRepository(context)

    private val _session = MutableStateFlow(Session())
    val session: StateFlow<Session> = _session.asStateFlow()

    /** Without a PIN there is nothing to hide behind, so hidden items are always shown. */
    val hasPin: StateFlow<Boolean> = settings.settings.map { it.hasPin }.stateIn(appScope, SharingStarted.Eagerly, false)

    init {
        appScope.launch {
            hasPin.collect { has -> if (!has) _session.update { it.copy(hiddenRevealed = true) } }
        }
        appScope.launch { library.ensurePlaylistUuids() }
    }

    /** Subsonic/Jellyfin clients (phase 4). */
    val musicServersReady = false

    suspend fun signInMusicServer(type: app.scrollback.accounts.AccountType, url: String, user: String, password: String): Unit =
        throw UnsupportedOperationException("Music servers arrive in v0.4")

    suspend fun addMusicServerSource(accountId: String): Unit = Unit

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
