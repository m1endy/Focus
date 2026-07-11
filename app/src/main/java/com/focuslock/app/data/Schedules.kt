package com.focuslock.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Calendar
import java.util.UUID

/**
 * ===== Расписания =====
 *
 * Расписание НЕ хранит собственный таймер — оно лишь отвечает на вопрос
 * "должна ли блокировка быть активна ПРЯМО СЕЙЧАС?" как чистая функция от
 * текущего времени и своих настроек (см. Scheduler.evaluate). Единая точка
 * (LockRepository.readEffectiveBlockState) объединяет ответ всех включённых
 * расписаний с быстрой блокировкой: если блокировки требует хоть один
 * источник — блокируем; список заблокированных пакетов — объединение
 * пакетов всех источников, активных прямо сейчас.
 *
 * Такой подход "бесплатно" переживает перезапуск приложения, смену даты и
 * времени и переход через полночь: всё пересчитывается заново из сохранённых
 * настроек и текущего System.currentTimeMillis() на каждом тике, без
 * хрупкого промежуточного состояния таймеров. Само вычисление вызывается на
 * уже существующем 600мс опросе BlockingService — отдельного WorkManager/
 * AlarmManager не заводим, чтобы не плодить параллельные механизмы и не
 * расходовать лишнюю батарею.
 */

enum class ScheduleType { TIME_BASED, CYCLIC }
enum class CyclicRepeatMode { INFINITE, COUNT, TIME_RANGE }

val WEEKDAYS = setOf(
    Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY
)
val WEEKEND_DAYS = setOf(Calendar.SATURDAY, Calendar.SUNDAY)
val ALL_DAYS = WEEKDAYS + WEEKEND_DAYS

data class Schedule(
    val id: String,
    val name: String,
    val type: ScheduleType,
    val packages: Set<String>,
    val enabled: Boolean,
    // По времени:
    val daysOfWeek: Set<Int>,      // Calendar.DAY_OF_WEEK: ВС=1 ... СБ=7
    val startMinute: Int,          // минут от полуночи, 0..1439
    val endMinute: Int,
    // Циклическое (Pomodoro):
    val workMinutes: Int,
    val restMinutes: Int,
    val repeatMode: CyclicRepeatMode,
    val repeatCount: Int,
    val rangeStartMinute: Int,
    val rangeEndMinute: Int,
    // Точка отсчёта цикла — обновляется при каждом сохранении и при каждом
    // включении тумблера, чтобы расписание всегда стартовало "с нуля", а не
    // подхватывало случайную фазу прежнего цикла.
    val anchorTime: Long,
    val createdAt: Long
) {
    companion object {
        fun blank(): Schedule {
            val now = System.currentTimeMillis()
            return Schedule(
                id = UUID.randomUUID().toString(),
                name = "",
                type = ScheduleType.TIME_BASED,
                packages = emptySet(),
                enabled = true,
                daysOfWeek = WEEKDAYS,
                startMinute = 9 * 60,
                endMinute = 18 * 60,
                workMinutes = 25,
                restMinutes = 5,
                repeatMode = CyclicRepeatMode.INFINITE,
                repeatCount = 4,
                rangeStartMinute = 9 * 60,
                rangeEndMinute = 18 * 60,
                anchorTime = now,
                createdAt = now
            )
        }
    }
}

data class EffectiveBlockState(val active: Boolean, val blockedPackages: Set<String>, val segmentEnd: Long)

// ===== JSON (тот же стиль, что и SESSION_HISTORY в AppData.kt) =====

private fun Schedule.toJson(): org.json.JSONObject {
    val obj = org.json.JSONObject()
    obj.put("id", id)
    obj.put("name", name)
    obj.put("type", type.name)
    val pkgArray = org.json.JSONArray()
    packages.forEach { pkgArray.put(it) }
    obj.put("packages", pkgArray)
    obj.put("enabled", enabled)
    val daysArray = org.json.JSONArray()
    daysOfWeek.forEach { daysArray.put(it) }
    obj.put("daysOfWeek", daysArray)
    obj.put("startMinute", startMinute)
    obj.put("endMinute", endMinute)
    obj.put("workMinutes", workMinutes)
    obj.put("restMinutes", restMinutes)
    obj.put("repeatMode", repeatMode.name)
    obj.put("repeatCount", repeatCount)
    obj.put("rangeStartMinute", rangeStartMinute)
    obj.put("rangeEndMinute", rangeEndMinute)
    obj.put("anchorTime", anchorTime)
    obj.put("createdAt", createdAt)
    return obj
}

private fun scheduleFromJson(obj: org.json.JSONObject): Schedule? {
    return try {
        val id = obj.getString("id")
        val name = obj.optString("name", "Расписание")
        val type = ScheduleType.valueOf(obj.optString("type", ScheduleType.TIME_BASED.name))
        val pkgArray = obj.optJSONArray("packages")
        val packages = if (pkgArray != null) {
            (0 until pkgArray.length()).mapNotNull { pkgArray.optString(it, "").ifBlank { null } }.toSet()
        } else emptySet()
        val daysArray = obj.optJSONArray("daysOfWeek")
        val daysOfWeek = if (daysArray != null) {
            (0 until daysArray.length()).map { daysArray.optInt(it) }.toSet()
        } else emptySet()
        val repeatMode = CyclicRepeatMode.valueOf(obj.optString("repeatMode", CyclicRepeatMode.INFINITE.name))
        Schedule(
            id = id,
            name = name,
            type = type,
            packages = packages,
            enabled = obj.optBoolean("enabled", true),
            daysOfWeek = daysOfWeek,
            startMinute = obj.optInt("startMinute", 0),
            endMinute = obj.optInt("endMinute", 0),
            workMinutes = obj.optInt("workMinutes", 25),
            restMinutes = obj.optInt("restMinutes", 5),
            repeatMode = repeatMode,
            repeatCount = obj.optInt("repeatCount", 4),
            rangeStartMinute = obj.optInt("rangeStartMinute", 0),
            rangeEndMinute = obj.optInt("rangeEndMinute", 0),
            anchorTime = obj.optLong("anchorTime", System.currentTimeMillis()),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        )
    } catch (e: Exception) {
        null
    }
}

fun parseSchedules(json: String): List<Schedule> {
    return try {
        val array = org.json.JSONArray(json)
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { scheduleFromJson(it) } }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun serializeSchedules(schedules: List<Schedule>): String {
    val array = org.json.JSONArray()
    schedules.forEach { array.put(it.toJson()) }
    return array.toString()
}

// ===== Репозиторий =====

class ScheduleRepository(private val context: Context) {
    val schedules: Flow<List<Schedule>> = context.dataStore.data.map { parseSchedules(it[Keys.SCHEDULES] ?: "[]") }

    // Возвращает false, если изменение отклонено, потому что ХРАНИМАЯ СЕЙЧАС
    // версия расписания в этот момент находится в фазе блокировки — лазейка
    // "снять уже начавшуюся блокировку через редактирование" закрыта именно
    // здесь: проверяется не то, что пришло в параметре schedule (его можно
    // сформировать как угодно), а то, что реально лежит в хранилище прямо
    // сейчас, внутри одной atomic-транзакции DataStore. Создание НОВОГО
    // расписания (которого ещё нет в списке) всегда разрешено.
    suspend fun upsert(schedule: Schedule): Boolean {
        var applied = false
        context.dataStore.edit { prefs ->
            val current = parseSchedules(prefs[Keys.SCHEDULES] ?: "[]").toMutableList()
            val index = current.indexOfFirst { it.id == schedule.id }
            val storedVersion = if (index >= 0) current[index] else null
            if (storedVersion != null && Scheduler.isCurrentlyBlocking(storedVersion)) {
                applied = false
            } else {
                if (index >= 0) current[index] = schedule else current.add(schedule)
                prefs[Keys.SCHEDULES] = serializeSchedules(current)
                applied = true
            }
        }
        return applied
    }

    // Возвращает false, если удаление отклонено по той же причине. Если
    // расписания с таким id уже нет — считаем это успехом (искомое
    // постусловие "расписания больше нет в списке" и так выполнено).
    suspend fun delete(id: String): Boolean {
        var applied = true
        context.dataStore.edit { prefs ->
            val current = parseSchedules(prefs[Keys.SCHEDULES] ?: "[]")
            val storedVersion = current.firstOrNull { it.id == id }
            if (storedVersion != null && Scheduler.isCurrentlyBlocking(storedVersion)) {
                applied = false
            } else {
                prefs[Keys.SCHEDULES] = serializeSchedules(current.filterNot { it.id == id })
                applied = true
            }
        }
        return applied
    }

    // Возвращает false, если попытка ВЫКЛЮЧИТЬ отклонена, потому что
    // расписание сейчас в фазе блокировки. Включить обратно можно всегда:
    // пока расписание выключено, evaluate() для него по определению уже
    // возвращает null, так что эта проверка для enabled=true никогда не
    // сработает — отдельно её обрабатывать не нужно.
    suspend fun setEnabled(id: String, enabled: Boolean): Boolean {
        var applied = false
        context.dataStore.edit { prefs ->
            val current = parseSchedules(prefs[Keys.SCHEDULES] ?: "[]")
            val storedVersion = current.firstOrNull { it.id == id }
            if (storedVersion == null) {
                applied = false
            } else if (!enabled && Scheduler.isCurrentlyBlocking(storedVersion)) {
                applied = false
            } else {
                val updated = current.map { s ->
                    if (s.id == id) {
                        s.copy(enabled = enabled, anchorTime = if (enabled) System.currentTimeMillis() else s.anchorTime)
                    } else s
                }
                prefs[Keys.SCHEDULES] = serializeSchedules(updated)
                applied = true
            }
        }
        return applied
    }

    companion object {
        fun readSchedulesSync(context: Context): List<Schedule> {
            return kotlinx.coroutines.runBlocking {
                parseSchedules(context.dataStore.data.first()[Keys.SCHEDULES] ?: "[]")
            }
        }

        // Отключает расписания с исчерпанным количеством циклов (repeatMode=COUNT).
        // Дешёвая проверка на каждом тике опроса; запись происходит только один
        // раз — в момент, когда расписание реально завершилось.
        fun disableCompletedCountSchedules(context: Context, now: Long) {
            kotlinx.coroutines.runBlocking {
                context.dataStore.edit { prefs ->
                    val current = parseSchedules(prefs[Keys.SCHEDULES] ?: "[]")
                    var changed = false
                    val updated = current.map { s ->
                        if (s.enabled && s.type == ScheduleType.CYCLIC && s.repeatMode == CyclicRepeatMode.COUNT) {
                            val cycleMs = (s.workMinutes.coerceAtLeast(1) + s.restMinutes.coerceAtLeast(0)) * 60_000L
                            val elapsed = (now - s.anchorTime).coerceAtLeast(0) / cycleMs
                            if (elapsed >= s.repeatCount) {
                                changed = true
                                s.copy(enabled = false)
                            } else s
                        } else s
                    }
                    if (changed) prefs[Keys.SCHEDULES] = serializeSchedules(updated)
                }
            }
        }
    }
}

// ===== Планировщик: чистая функция "активно ли расписание сейчас" =====

object Scheduler {
    /** Если расписание сейчас должно блокировать — момент окончания текущего
     *  сегмента блокировки (для обратного отсчёта на оверлее). Иначе null. */
    fun evaluate(schedule: Schedule, now: Long): Long? {
        if (!schedule.enabled || schedule.packages.isEmpty()) return null
        return when (schedule.type) {
            ScheduleType.TIME_BASED -> evaluateTimeBased(schedule, now)
            ScheduleType.CYCLIC -> evaluateCyclic(schedule, now)
        }
    }

    /** Сейчас ли расписание в фазе блокировки — единственный источник истины
     *  для защиты от лазейки "отключить/изменить/удалить уже начавшуюся
     *  блокировку" (см. ScheduleRepository). Тонкая обёртка над evaluate —
     *  не дублирует логику определения активности, а переиспользует её. */
    fun isCurrentlyBlocking(schedule: Schedule, now: Long = System.currentTimeMillis()): Boolean =
        evaluate(schedule, now) != null

    /** Сейчас идёт фаза "отдыха" у циклического расписания (для статуса в списке). */
    fun isInRestPhase(schedule: Schedule, now: Long): Boolean {
        if (!schedule.enabled || schedule.type != ScheduleType.CYCLIC) return false
        val workMs = schedule.workMinutes.coerceAtLeast(1) * 60_000L
        val restMs = schedule.restMinutes.coerceAtLeast(0) * 60_000L
        val cycleMs = workMs + restMs
        if (cycleMs <= 0L) return false

        val anchor = if (schedule.repeatMode == CyclicRepeatMode.TIME_RANGE) {
            activeWindowContaining(schedule.rangeStartMinute, schedule.rangeEndMinute, now)?.first ?: return false
        } else {
            schedule.anchorTime
        }
        if (now < anchor) return false
        if (schedule.repeatMode == CyclicRepeatMode.COUNT) {
            val elapsed = (now - anchor).coerceAtLeast(0) / cycleMs
            if (elapsed >= schedule.repeatCount) return false
        }
        return (now - anchor) % cycleMs >= workMs
    }

    private fun evaluateTimeBased(schedule: Schedule, now: Long): Long? {
        if (schedule.daysOfWeek.isEmpty()) return null
        val (windowStart, windowEnd) = activeWindowContaining(schedule.startMinute, schedule.endMinute, now)
            ?: return null
        val cal = Calendar.getInstance()
        cal.timeInMillis = windowStart
        return if (cal.get(Calendar.DAY_OF_WEEK) in schedule.daysOfWeek) windowEnd else null
    }

    private fun evaluateCyclic(schedule: Schedule, now: Long): Long? {
        val workMs = schedule.workMinutes.coerceAtLeast(1) * 60_000L
        val restMs = schedule.restMinutes.coerceAtLeast(0) * 60_000L
        val cycleMs = workMs + restMs

        return when (schedule.repeatMode) {
            CyclicRepeatMode.INFINITE -> cyclePhaseEnd(schedule.anchorTime, now, workMs, cycleMs)
            CyclicRepeatMode.COUNT -> {
                val elapsedCycles = (now - schedule.anchorTime).coerceAtLeast(0) / cycleMs
                if (elapsedCycles >= schedule.repeatCount) null
                else cyclePhaseEnd(schedule.anchorTime, now, workMs, cycleMs)
            }
            CyclicRepeatMode.TIME_RANGE -> {
                val window = activeWindowContaining(schedule.rangeStartMinute, schedule.rangeEndMinute, now)
                if (window == null) {
                    null
                } else {
                    val (windowStart, windowEnd) = window
                    val phaseEnd = cyclePhaseEnd(windowStart, now, workMs, cycleMs)
                    if (phaseEnd == null) null else minOf(phaseEnd, windowEnd)
                }
            }
        }
    }

    // Конец текущей фазы "работа", если прямо сейчас идёт именно она (а не отдых).
    private fun cyclePhaseEnd(anchor: Long, now: Long, workMs: Long, cycleMs: Long): Long? {
        if (cycleMs <= 0L || now < anchor) return null
        val intoCycle = (now - anchor) % cycleMs
        return if (intoCycle < workMs) (now - intoCycle) + workMs else null
    }

    // [start, end) в миллисекундах для суточного окна (rangeStart..rangeEnd в
    // минутах от полуночи), которое покрывает now — с учётом того, что окно
    // могло начаться вчера и продолжаться через полночь.
    private fun activeWindowContaining(rangeStartMinute: Int, rangeEndMinute: Int, now: Long): Pair<Long, Long>? {
        if (rangeStartMinute == rangeEndMinute) return null
        val durationMinutes = ((rangeEndMinute - rangeStartMinute + 1440) % 1440).let { if (it == 0) 1440 else it }
        val durationMs = durationMinutes * 60_000L

        val todayStart = startOfDay(now) + rangeStartMinute * 60_000L
        if (now in todayStart until (todayStart + durationMs)) return todayStart to (todayStart + durationMs)

        val yesterdayStart = todayStart - 24L * 60 * 60_000L
        if (now in yesterdayStart until (yesterdayStart + durationMs)) return yesterdayStart to (yesterdayStart + durationMs)

        return null
    }
}
