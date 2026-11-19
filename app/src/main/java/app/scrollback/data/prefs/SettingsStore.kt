package app.scrollback.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.scrollback.core.security.PinHasher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    val pinSalt: String? = null,
    val pinHash: String? = null,
    val requirePinOnLaunch: Boolean = false,
    val secureScreen: Boolean = false,
    val defaultSpeed: Float = 1f,
    /** true = loop the current video; false = auto-advance to the next one. */
    val loopOne: Boolean = true,
    val savedNewestFirst: Boolean = true,
    val picturesNewestFirst: Boolean = true,
    val filesGrid: Boolean = false,
    /** "name", "date", "size" or "type". */
    val filesSort: String = "name",
    val filesAscending: Boolean = true,
) {
    val hasPin: Boolean get() = pinSalt != null && pinHash != null
}

class SettingsStore(private val context: Context) {
    private object K {
        val pinSalt = stringPreferencesKey("pin_salt")
        val pinHash = stringPreferencesKey("pin_hash")
        val requirePin = booleanPreferencesKey("require_pin_on_launch")
        val secure = booleanPreferencesKey("secure_screen")
        val speed = floatPreferencesKey("default_speed")
        val loopOne = booleanPreferencesKey("loop_one")
        val savedNewest = booleanPreferencesKey("saved_newest_first")
        val picturesNewest = booleanPreferencesKey("pictures_newest_first")
        val filesGrid = booleanPreferencesKey("files_grid")
        val filesSort = stringPreferencesKey("files_sort")
        val filesAsc = booleanPreferencesKey("files_ascending")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            pinSalt = p[K.pinSalt],
            pinHash = p[K.pinHash],
            requirePinOnLaunch = p[K.requirePin] ?: false,
            secureScreen = p[K.secure] ?: false,
            defaultSpeed = p[K.speed] ?: 1f,
            loopOne = p[K.loopOne] ?: true,
            savedNewestFirst = p[K.savedNewest] ?: true,
            picturesNewestFirst = p[K.picturesNewest] ?: true,
            filesGrid = p[K.filesGrid] ?: false,
            filesSort = p[K.filesSort] ?: "name",
            filesAscending = p[K.filesAsc] ?: true,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setPin(pin: String) {
        val h = PinHasher.hash(pin)
        context.dataStore.edit { it[K.pinSalt] = h.salt; it[K.pinHash] = h.hash }
    }

    suspend fun clearPin() = context.dataStore.edit {
        it.remove(K.pinSalt); it.remove(K.pinHash); it[K.requirePin] = false
    }

    suspend fun verifyPin(pin: String): Boolean {
        val s = current()
        return s.hasPin && PinHasher.verify(pin, s.pinSalt!!, s.pinHash!!)
    }

    suspend fun setRequirePinOnLaunch(v: Boolean) = context.dataStore.edit { it[K.requirePin] = v }
    suspend fun setSecureScreen(v: Boolean) = context.dataStore.edit { it[K.secure] = v }
    suspend fun setDefaultSpeed(v: Float) = context.dataStore.edit { it[K.speed] = v }
    suspend fun setLoopOne(v: Boolean) = context.dataStore.edit { it[K.loopOne] = v }
    suspend fun setSavedNewestFirst(v: Boolean) = context.dataStore.edit { it[K.savedNewest] = v }
    suspend fun setPicturesNewestFirst(v: Boolean) = context.dataStore.edit { it[K.picturesNewest] = v }
    suspend fun setFilesGrid(v: Boolean) = context.dataStore.edit { it[K.filesGrid] = v }
    suspend fun setFilesSort(by: String, ascending: Boolean) = context.dataStore.edit { it[K.filesSort] = by; it[K.filesAsc] = ascending }
}
