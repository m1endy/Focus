package com.focuslock.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

val Context.dataStore by preferencesDataStore(name = "focuslock_prefs")

object Keys {
    val BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_packages")
    val END_TIME = longPreferencesKey("end_time")
    val IS_BLOCKING = booleanPreferencesKey("is_blocking")
    val DEBUG_LAST_EVENT = stringPreferencesKey("debug_last_event")
    val CURRENTLY_ON_BLOCKED_APP = booleanPreferencesKey("currently_on_blocked_app")
}

class LockRepository(private val context: Context) {
    val blockedPackages: Flow<Set<String>> = context.dataStore.data.map {
        it[Keys.BLOCKED_PACKAGES] ?: emptySet()
    }
    val endTime: Flow<Long> = context.dataStore.data.map { it[Keys.END_TIME] ?: 0L }
    val isBlocking: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_BLOCKING] ?: false }
    val debugLastEvent: Flow<String> = context.dataStore.data.map { it[Keys.DEBUG_LAST_EVENT] ?: "пока нет событий" }

    suspend fun saveSelection(packages: Set<String>) {
        context.dataStore.edit { it[Keys.BLOCKED_PACKAGES] = packages }
    }

    suspend fun startBlock(durationMillis: Long) {
        context.dataStore.edit {
            it[Keys.END_TIME] = System.currentTimeMillis() + durationMillis
            it[Keys.IS_BLOCKING] = true
            it[Keys.CURRENTLY_ON_BLOCKED_APP] = false
        }
    }

    suspend fun stopBlock() {
        context.dataStore.edit {
            it[Keys.IS_BLOCKING] = false
            it[Keys.END_TIME] = 0L
        }
    }

    companion object {
        // Синхронный доступ для службы (без корутин-контекста Compose)
        fun readBlockingState(context: Context): Triple<Boolean, Long, Set<String>> {
            return kotlinx.coroutines.runBlocking {
                val prefs = context.dataStore.data.first()
                Triple(
                    prefs[Keys.IS_BLOCKING] ?: false,
                    prefs[Keys.END_TIME] ?: 0L,
                    prefs[Keys.BLOCKED_PACKAGES] ?: emptySet()
                )
            }
        }

        fun clearBlockingState(context: Context) {
            kotlinx.coroutines.runBlocking {
                context.dataStore.edit {
                    it[Keys.IS_BLOCKING] = false
                    it[Keys.END_TIME] = 0L
                    it[Keys.CURRENTLY_ON_BLOCKED_APP] = false
                }
            }
        }

        // Пишет короткий след последнего события — видно в приложении на экране
        // диагностики, без adb и логов. Только для отладки блокировки.
        fun writeDebugEvent(context: Context, message: String) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            kotlinx.coroutines.runBlocking {
                context.dataStore.edit { it[Keys.DEBUG_LAST_EVENT] = "$message · $time" }
            }
        }

        // Отражает, является ли ПРЯМО СЕЙЧАС открытое приложение заблокированным.
        // В отличие от isBlocking (которое держится весь срок таймера), это
        // значение меняется при каждом переключении между приложениями и решает,
        // должен ли оверлей быть показан именно в этот момент.
        fun setCurrentlyOnBlockedApp(context: Context, value: Boolean) {
            kotlinx.coroutines.runBlocking {
                context.dataStore.edit { it[Keys.CURRENTLY_ON_BLOCKED_APP] = value }
            }
        }

        fun isCurrentlyOnBlockedApp(context: Context): Boolean {
            return kotlinx.coroutines.runBlocking {
                context.dataStore.data.first()[Keys.CURRENTLY_ON_BLOCKED_APP] ?: false
            }
        }
    }
}

fun loadInstalledApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val selfPackage = context.packageName
    @Suppress("DEPRECATION")
    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
    return apps.filter { app ->
        val isSystemNonSettings = app.packageName.startsWith("com.android.") &&
            app.packageName != "com.android.settings"
        val hasLauncherIntent = pm.getLaunchIntentForPackage(app.packageName) != null
        app.packageName != selfPackage && !isSystemNonSettings && hasLauncherIntent &&
            (app.flags and ApplicationInfo.FLAG_SYSTEM == 0 || app.packageName == "com.android.settings")
    }.map { app ->
        AppInfo(
            packageName = app.packageName,
            label = pm.getApplicationLabel(app).toString(),
            icon = try { pm.getApplicationIcon(app.packageName) } catch (e: Exception) { null }
        )
    }.sortedBy { it.label.lowercase() }
}

class MainViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = LockRepository(app)

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    val selectedPackages: StateFlow<Set<String>> = _selectedPackages.asStateFlow()

    val isBlocking: StateFlow<Boolean> = repo.isBlocking.stateIn(
        viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false
    )
    val endTime: StateFlow<Long> = repo.endTime.stateIn(
        viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, 0L
    )
    val debugLastEvent: StateFlow<String> = repo.debugLastEvent.stateIn(
        viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "пока нет событий"
    )

    init {
        viewModelScope.launch {
            _installedApps.value = loadInstalledApps(app)
        }
        viewModelScope.launch {
            repo.blockedPackages.collect { _selectedPackages.value = it }
        }
    }

    fun toggleApp(packageName: String) {
        val current = _selectedPackages.value.toMutableSet()
        if (!current.add(packageName)) current.remove(packageName)
        _selectedPackages.value = current
        viewModelScope.launch { repo.saveSelection(current) }
    }

    fun startBlocking(durationMillis: Long) {
        viewModelScope.launch { repo.startBlock(durationMillis) }
    }

    // Подстраховка: если оверлей ни разу не сработал (например, служба не
    // получает события), таймер внутри самого приложения всё равно должен
    // сам снять блокировку по истечении времени, а не зависать на 00:00.
    fun stopBlocking() {
        viewModelScope.launch { repo.stopBlock() }
    }
}
