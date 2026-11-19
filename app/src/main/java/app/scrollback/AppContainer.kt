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
    val sources = MediaSourceRegistry(context)
    val library = LibraryRepository(context, db, sources)
    val duplicates = DuplicateRepository(db, library)

    private val _session = MutableStateFlow(Session())
    val session: StateFlow<Session> = _session.asStateFlow()

    /** Without a PIN there is nothing to hide behind, so hidden items are always shown. */
    val hasPin: StateFlow<Boolean> = settings.settings.map { it.hasPin }.stateIn(appScope, SharingStarted.Eagerly, false)

    init {
        appScope.launch {
            hasPin.collect { has -> if (!has) _session.update { it.copy(hiddenRevealed = true) } }
        }
    }

    fun unlockApp() = _session.update { it.copy(appUnlocked = true) }
    fun revealHidden(reveal: Boolean) = _session.update { it.copy(hiddenRevealed = reveal || !hasPin.value) }
    fun lockAll() { _session.value = Session(appUnlocked = false, hiddenRevealed = !hasPin.value) }
}
