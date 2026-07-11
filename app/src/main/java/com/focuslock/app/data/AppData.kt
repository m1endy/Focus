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
    val START_TIME = longPreferencesKey("start_time")
    val END_TIME = longPreferencesKey("end_time")
    val IS_BLOCKING = booleanPreferencesKey("is_blocking")
    val DEBUG_LAST_EVENT = stringPreferencesKey("debug_last_event")
    val CURRENTLY_ON_BLOCKED_APP = booleanPreferencesKey("currently_on_blocked_app")
    val SESSION_HISTORY = stringPreferencesKey("session_history")
    val SCHEDULES = stringPreferencesKey("schedules")
}

// ===== История сессий блокировки (для вкладки "Статистика") =====
// Каждая завершённая сессия — это { start, end, packages } в JSON-массиве
// внутри одного preference-ключа. Отдельной БД не заводим: истории немного
// (одна запись на сессию), а org.json уже есть в платформе — без новых
// зависимостей.

data class BlockSession(val start: Long, val end: Long, val packages: Set<String>)

fun parseSessionHistory(json: String): List<BlockSession> {
    return try {
        val array = org.json.JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val start = obj.optLong("start", -1L)
            val end = obj.optLong("end", -1L)
            if (start < 0L || end <= start) return@mapNotNull null
            val pkgArray = obj.optJSONArray("packages")
            val pkgs = if (pkgArray != null) {
                (0 until pkgArray.length()).mapNotNull { j -> pkgArray.optString(j, "").ifBlank { null } }.toSet()
            } else emptySet()
            if (pkgs.isEmpty()) return@mapNotNull null
            BlockSession(start, end, pkgs)
        }
    } catch (e: Exception) {
        emptyList()
    }
}

private const val MAX_SESSION_HISTORY_ENTRIES = 4000
private const val SESSION_HISTORY_MAX_AGE_MS = 370L * 24 * 60 * 60 * 1000 // с запасом на "год" в статистике

private fun appendSession(historyJson: String, start: Long, end: Long, packages: Set<String>): String {
    val array = try { org.json.JSONArray(historyJson) } catch (e: Exception) { org.json.JSONArray() }

    val entry = org.json.JSONObject()
    entry.put("start", start)
    entry.put("end", end)
    val pkgArray = org.json.JSONArray()
    packages.forEach { pkgArray.put(it) }
    entry.put("packages", pkgArray)
    array.put(entry)

    // Не даём истории расти бесконечно: оставляем не больше последнего года
    // данных и не больше MAX_SESSION_HISTORY_ENTRIES записей.
    val cutoff = end - SESSION_HISTORY_MAX_AGE_MS
    val startIndex = (array.length() - MAX_SESSION_HISTORY_ENTRIES).coerceAtLeast(0)
    val trimmed = org.json.JSONArray()
    for (i in startIndex until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        if (obj.optLong("end", 0L) >= cutoff) {
            trimmed.put(obj)
        }
    }
    return trimmed.toString()
}

class LockRepository(private val context: Context) {
    val blockedPackages: Flow<Set<String>> = context.dataStore.data.map {
        it[Keys.BLOCKED_PACKAGES] ?: emptySet()
    }
    val endTime: Flow<Long> = context.dataStore.data.map { it[Keys.END_TIME] ?: 0L }
    val isBlocking: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_BLOCKING] ?: false }
    val debugLastEvent: Flow<String> = context.dataStore.data.map { it[Keys.DEBUG_LAST_EVENT] ?: "пока нет событий" }
    val sessionHistory: Flow<String> = context.dataStore.data.map { it[Keys.SESSION_HISTORY] ?: "[]" }

    suspend fun saveSelection(packages: Set<String>) {
        context.dataStore.edit { it[Keys.BLOCKED_PACKAGES] = packages }
    }

    suspend fun startBlock(durationMillis: Long) {
        val now = System.currentTimeMillis()
        context.dataStore.edit {
            it[Keys.START_TIME] = now
            it[Keys.END_TIME] = now + durationMillis
            it[Keys.IS_BLOCKING] = true
            it[Keys.CURRENTLY_ON_BLOCKED_APP] = false
        }
    }

    suspend fun stopBlock() {
        finalizeSession(context)
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
            kotlinx.coroutines.runBlocking { finalizeSession(context) }
        }

        // ===== Единый механизм принятия решения "блокировать ли сейчас" =====
        // Объединяет быструю блокировку и все включённые расписания. Правило
        // конфликтов: если блокировки требует хотя бы один активный источник —
        // блокируем; список пакетов — объединение пакетов всех активных сейчас
        // источников. segmentEnd — ближайший момент, когда СЕЙЧАС активный сегмент
        // закончится (используется только для обратного отсчёта на оверлее; если
        // после этого блокировка должна продолжиться по другой причине, следующий
        // тик опроса это обнаружит и поднимет оверлей заново — см. BlockingService).
        //
        // Вызывается на каждом тике уже существующего 600мс опроса
        // BlockingService, поэтому попутно (дёшево) выполняет два вида
        // обслуживания, которым иначе понадобился бы отдельный таймер:
        //  - закрывает истёкшую по времени сессию быстрой блокировки;
        //  - выключает циклические расписания с исчерпанным числом циклов.
        fun readEffectiveBlockState(context: Context): EffectiveBlockState {
            val now = System.currentTimeMillis()

            var (quickBlocking, quickEnd, quickPackages) = readBlockingState(context)
            if (quickBlocking && now >= quickEnd) {
                clearBlockingState(context)
                quickBlocking = false
                quickEnd = 0L
                quickPackages = emptySet()
            }
            ScheduleRepository.disableCompletedCountSchedules(context, now)

            val packages = mutableSetOf<String>()
            val ends = mutableListOf<Long>()
            if (quickBlocking && now < quickEnd) {
                packages += quickPackages
                ends += quickEnd
            }
            for (schedule in ScheduleRepository.readSchedulesSync(context)) {
                val segmentEnd = Scheduler.evaluate(schedule, now) ?: continue
                packages += schedule.packages
                ends += segmentEnd
            }

            return if (packages.isEmpty()) EffectiveBlockState(false, emptySet(), 0L)
            else EffectiveBlockState(true, packages, ends.min())
        }

        // Единая точка завершения сессии блокировки — используется и из
        // suspend-контекста (ViewModel.stopBlock), и синхронно из служб/ресивера
        // (OverlayService, BlockingService, BootReceiver), у которых нет
        // естественного suspend-контекста. Идемпотентна: оверлей и служба
        // слежения могут независимо друг от друга заметить истечение таймера,
        // но записать сессию в историю (и сбросить флаги) успеет только тот
        // вызов, что придёт первым — остальные увидят IS_BLOCKING уже false
        // внутри той же atomic-транзакции DataStore и ничего не продублируют.
        private suspend fun finalizeSession(context: Context) {
            context.dataStore.edit { prefs ->
                if (prefs[Keys.IS_BLOCKING] == true) {
                    val start = prefs[Keys.START_TIME] ?: 0L
                    val plannedEnd = prefs[Keys.END_TIME] ?: 0L
                    val now = System.currentTimeMillis()
                    val rawEnd = if (plannedEnd > start) plannedEnd else now
                    val end = rawEnd.coerceAtMost(now).coerceAtLeast(start)
                    val packages = prefs[Keys.BLOCKED_PACKAGES] ?: emptySet()
                    if (start > 0L && end > start && packages.isNotEmpty()) {
                        val history = prefs[Keys.SESSION_HISTORY] ?: "[]"
                        prefs[Keys.SESSION_HISTORY] = appendSession(history, start, end, packages)
                    }
                }
                prefs[Keys.IS_BLOCKING] = false
                prefs[Keys.END_TIME] = 0L
                prefs[Keys.CURRENTLY_ON_BLOCKED_APP] = false
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

// ===== Агрегация статистики по периодам =====

enum class StatsPeriod(val label: String) {
    TODAY("Сегодня"),
    WEEK("Неделя"),
    MONTH("Месяц"),
    YEAR("Год")
}

data class AppStat(val packageName: String, val minutes: Long)
data class StatsResult(val totalMinutes: Long, val perApp: List<AppStat>)

private const val DAY_MS = 24L * 60 * 60 * 1000

// Используется и статистикой (ниже), и Scheduler'ом (Schedules.kt, тот же
// пакет) — единая точка расчёта "начала суток" по локальному времени устройства.
fun startOfDay(now: Long): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun periodStartFor(period: StatsPeriod, now: Long): Long = when (period) {
    StatsPeriod.TODAY -> startOfDay(now)
    StatsPeriod.WEEK -> now - 7L * DAY_MS
    StatsPeriod.MONTH -> now - 30L * DAY_MS
    StatsPeriod.YEAR -> now - 365L * DAY_MS
}

private fun msToMinutes(ms: Long): Long = Math.round(ms / 60000.0)

// Считаем не по "чьей сессии сколько было", а по фактическому пересечению
// каждой сессии с окном периода — так сессия, начавшаяся вчера вечером и
// закончившаяся сегодня ночью, корректно распределяется между "сегодня" и
// более широкими периодами, а не засчитывается целиком в одну корзину.
fun computeStats(sessions: List<BlockSession>, period: StatsPeriod, now: Long = System.currentTimeMillis()): StatsResult {
    val periodStart = periodStartFor(period, now)
    val totals = LinkedHashMap<String, Long>()
    var totalMs = 0L
    for (session in sessions) {
        val overlapMs = (minOf(session.end, now) - maxOf(session.start, periodStart)).coerceAtLeast(0L)
        if (overlapMs <= 0L) continue
        totalMs += overlapMs
        for (pkg in session.packages) {
            totals[pkg] = (totals[pkg] ?: 0L) + overlapMs
        }
    }
    val perApp = totals.entries
        .map { AppStat(it.key, msToMinutes(it.value)) }
        .filter { it.minutes > 0L }
        .sortedByDescending { it.minutes }
    return StatsResult(msToMinutes(totalMs), perApp)
}

class MainViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = LockRepository(app)
    private val scheduleRepo = ScheduleRepository(app)

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
    val sessionHistory: StateFlow<List<BlockSession>> = repo.sessionHistory
        .map { parseSessionHistory(it) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
    val schedules: StateFlow<List<Schedule>> = scheduleRepo.schedules
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

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

    // suspend + Boolean (а не fire-and-forget, как раньше): экран должен
    // узнать, была ли операция отклонена репозиторием, потому что расписание
    // прямо сейчас в фазе блокировки (см. ScheduleRepository/Scheduler).
    suspend fun trySaveSchedule(schedule: Schedule): Boolean = scheduleRepo.upsert(schedule)

    suspend fun tryDeleteSchedule(id: String): Boolean = scheduleRepo.delete(id)

    suspend fun trySetScheduleEnabled(id: String, enabled: Boolean): Boolean = scheduleRepo.setEnabled(id, enabled)
}
