package com.focuslock.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focuslock.app.data.*
import com.focuslock.app.ui.*
import java.util.Calendar
import kotlinx.coroutines.delay

// ===== Локальная навигация внутри вкладки (список ↔ создание/редактирование) =====
// Это не полноценный back stack, а простое локальное состояние — вкладка
// "Расписания" не должна ничего знать о MainActivity/AppTab, а MainActivity
// не должна ничего знать о внутренней структуре этой вкладки.
private sealed class SchedulesNav {
    object List : SchedulesNav()
    object Create : SchedulesNav()
    data class Edit(val schedule: Schedule) : SchedulesNav()
}

@Composable
fun SchedulesScreen(viewModel: MainViewModel) {
    val schedules by viewModel.schedules.collectAsState()
    val apps by viewModel.installedApps.collectAsState()
    var nav by remember { mutableStateOf<SchedulesNav>(SchedulesNav.List) }

    // Без этого системная кнопка "назад" во время заполнения формы закрыла бы
    // всё приложение вместо возврата к списку расписаний.
    BackHandler(enabled = nav != SchedulesNav.List) { nav = SchedulesNav.List }

    Crossfade(targetState = nav, label = "schedulesNav") { current ->
        when (current) {
            is SchedulesNav.List -> SchedulesListScreen(
                schedules = schedules,
                apps = apps,
                onAdd = { nav = SchedulesNav.Create },
                onEdit = { schedule -> nav = SchedulesNav.Edit(schedule) },
                onToggle = { id, enabled -> viewModel.setScheduleEnabled(id, enabled) },
                onDelete = { id -> viewModel.deleteSchedule(id) }
            )
            is SchedulesNav.Create -> ScheduleEditScreen(
                existing = null,
                allApps = apps,
                onSave = { schedule -> viewModel.saveSchedule(schedule); nav = SchedulesNav.List },
                onCancel = { nav = SchedulesNav.List },
                onDelete = null
            )
            is SchedulesNav.Edit -> ScheduleEditScreen(
                existing = current.schedule,
                allApps = apps,
                onSave = { schedule -> viewModel.saveSchedule(schedule); nav = SchedulesNav.List },
                onCancel = { nav = SchedulesNav.List },
                onDelete = {
                    viewModel.deleteSchedule(current.schedule.id)
                    nav = SchedulesNav.List
                }
            )
        }
    }
}

// ===================== Список расписаний =====================

@Composable
private fun SchedulesListScreen(
    schedules: kotlin.collections.List<Schedule>,
    apps: kotlin.collections.List<AppInfo>,
    onAdd: () -> Unit,
    onEdit: (Schedule) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    val appsByPackage = remember(apps) { apps.associateBy { it.packageName } }

    // Живой статус ("блокирует / отдых / ждёт окно") обновляем раз в 30с —
    // этого достаточно для читаемости и не требует частых перерисовок.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    OrganicBackground {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Расписания", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Блокировка запускается автоматически",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                IconButton(
                    onClick = onAdd,
                    modifier = Modifier.size(46.dp).clip(CircleShape).background(Cyan)
                ) {
                    Icon(Icons.Default.Add, null, tint = Color.Black)
                }
            }
            Spacer(Modifier.height(20.dp))

            if (schedules.isEmpty()) {
                EmptySchedulesState(onAdd = onAdd)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(schedules, key = { it.id }) { schedule ->
                        ScheduleCard(
                            schedule = schedule,
                            appsByPackage = appsByPackage,
                            now = now,
                            onEdit = { onEdit(schedule) },
                            onToggle = { enabled -> onToggle(schedule.id, enabled) },
                            onDelete = { onDelete(schedule.id) }
                        )
                    }
                    item { Spacer(Modifier.height(90.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EmptySchedulesState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().glassCard(20).padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GlassIconBadge(Icons.Default.DateRange, sizeDp = 56)
        Spacer(Modifier.height(16.dp))
        Text("Пока нет расписаний", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Настройте автоматическую блокировку по времени или в стиле Pomodoro",
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("Новое расписание", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ScheduleCard(
    schedule: Schedule,
    appsByPackage: Map<String, AppInfo>,
    now: Long,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val displayName = schedule.name.ifBlank { defaultScheduleName(schedule.type) }

    Column(modifier = Modifier.fillMaxWidth().glassCard(18).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconBadge(
                icon = if (schedule.type == ScheduleType.TIME_BASED) Icons.Default.DateRange else Icons.Default.Refresh,
                sizeDp = 38,
                accent = if (schedule.enabled) Cyan else TextSecondary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(displayName, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(scheduleSummary(schedule), color = TextSecondary, fontSize = 12.sp, maxLines = 1)
            }
            Switch(
                checked = schedule.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = Cyan, checkedThumbColor = Color.White)
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconsRow(schedule.packages, appsByPackage)
            Spacer(Modifier.weight(1f))
            Text(
                scheduleStatusText(schedule, now),
                color = if (schedule.enabled) Cyan else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Изменить", color = TextSecondary, fontSize = 13.sp)
            }
            TextButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Default.Delete, null, tint = DangerRed, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Удалить", color = DangerRed, fontSize = 13.sp)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = CardBlack,
            title = { Text("Удалить расписание?", color = TextPrimary) },
            text = { Text("«$displayName» будет удалено без возможности восстановления.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("Удалить", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена", color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun AppIconsRow(packages: Set<String>, appsByPackage: Map<String, AppInfo>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        packages.take(4).forEach { pkg ->
            val icon = appsByPackage[pkg]?.icon
            if (icon != null) {
                Image(
                    bitmap = icon.toBitmap(64, 64).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp).size(22.dp).clip(RoundedCornerShape(6.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(CyanDim)
                )
            }
        }
        if (packages.size > 4) {
            Text("+${packages.size - 4}", color = TextSecondary, fontSize = 11.sp)
        }
    }
}

// ===================== Создание / редактирование =====================

@Composable
private fun ScheduleEditScreen(
    existing: Schedule?,
    allApps: kotlin.collections.List<AppInfo>,
    onSave: (Schedule) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val base = remember { existing ?: Schedule.blank() }
    var name by remember { mutableStateOf(base.name) }
    var type by remember { mutableStateOf(base.type) }
    var selectedPackages by remember { mutableStateOf(base.packages) }
    var daysOfWeek by remember { mutableStateOf(base.daysOfWeek) }
    var startMinute by remember { mutableStateOf(base.startMinute) }
    var endMinute by remember { mutableStateOf(base.endMinute) }
    var workMinutes by remember { mutableStateOf(base.workMinutes) }
    var restMinutes by remember { mutableStateOf(base.restMinutes) }
    var repeatMode by remember { mutableStateOf(base.repeatMode) }
    var repeatCount by remember { mutableStateOf(base.repeatCount) }
    var rangeStartMinute by remember { mutableStateOf(base.rangeStartMinute) }
    var rangeEndMinute by remember { mutableStateOf(base.rangeEndMinute) }
    var search by remember { mutableStateOf("") }

    val filteredApps = remember(allApps, search) {
        if (search.isBlank()) allApps else allApps.filter { it.label.contains(search, ignoreCase = true) }
    }

    val isValid = selectedPackages.isNotEmpty() && when (type) {
        ScheduleType.TIME_BASED -> daysOfWeek.isNotEmpty() && startMinute != endMinute
        ScheduleType.CYCLIC -> repeatMode != CyclicRepeatMode.TIME_RANGE || rangeStartMinute != rangeEndMinute
    }

    OrganicBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, null, tint = TextSecondary)
                }
                Text(
                    if (existing == null) "Новое расписание" else "Изменить расписание",
                    color = TextPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (existing == null) {
                    item {
                        Column {
                            SectionLabel("Шаблон")
                            Spacer(Modifier.height(8.dp))
                            PresetsRow { preset ->
                                name = preset.name
                                type = preset.type
                                daysOfWeek = preset.daysOfWeek
                                startMinute = preset.startMinute
                                endMinute = preset.endMinute
                                workMinutes = preset.workMinutes
                                restMinutes = preset.restMinutes
                                repeatMode = preset.repeatMode
                                rangeStartMinute = preset.rangeStartMinute
                                rangeEndMinute = preset.rangeEndMinute
                            }
                        }
                    }
                }

                item {
                    Column {
                        SectionLabel("Название")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = { Text(defaultScheduleName(type), color = TextSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan,
                                unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = Cyan
                            ),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }

                item {
                    Column {
                        SectionLabel("Тип расписания")
                        Spacer(Modifier.height(8.dp))
                        TypeSelector(selected = type, onSelect = { type = it })
                    }
                }

                if (type == ScheduleType.TIME_BASED) {
                    item {
                        Column {
                            SectionLabel("Дни недели")
                            Spacer(Modifier.height(8.dp))
                            QuickDayButtons(onSelect = { daysOfWeek = it })
                            Spacer(Modifier.height(10.dp))
                            DaysOfWeekSelector(
                                selected = daysOfWeek,
                                onToggle = { day -> daysOfWeek = if (day in daysOfWeek) daysOfWeek - day else daysOfWeek + day }
                            )
                        }
                    }
                    item {
                        Column {
                            SectionLabel("Время блокировки")
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TimePickerField("Начало", startMinute, { startMinute = it }, Modifier.weight(1f))
                                TimePickerField("Конец", endMinute, { endMinute = it }, Modifier.weight(1f))
                            }
                            if (endMinute <= startMinute) {
                                Text(
                                    "Диапазон переходит через полночь",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Column {
                            SectionLabel("Ритм")
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                                MinutesStepper("Работа", workMinutes, { workMinutes = it })
                                MinutesStepper("Отдых", restMinutes, { restMinutes = it }, step = 1, min = 0)
                            }
                        }
                    }
                    item {
                        Column {
                            SectionLabel("Повтор")
                            Spacer(Modifier.height(8.dp))
                            RepeatModeSelector(selected = repeatMode, onSelect = { repeatMode = it })
                            if (repeatMode != CyclicRepeatMode.INFINITE) {
                                Spacer(Modifier.height(12.dp))
                                if (repeatMode == CyclicRepeatMode.COUNT) {
                                    NumberStepper("Количество циклов", repeatCount, { repeatCount = it }, min = 1, max = 50)
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        TimePickerField("С", rangeStartMinute, { rangeStartMinute = it }, Modifier.weight(1f))
                                        TimePickerField("До", rangeEndMinute, { rangeEndMinute = it }, Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Column {
                        val countSuffix = if (selectedPackages.isNotEmpty()) " · ${selectedPackages.size}" else ""
                        SectionLabel("Приложения$countSuffix")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            placeholder = { Text("Поиск приложений", color = TextSecondary) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan,
                                unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = Cyan
                            ),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }

                items(filteredApps, key = { it.packageName }) { app ->
                    AppRow(app, app.packageName in selectedPackages) {
                        selectedPackages = if (app.packageName in selectedPackages) {
                            selectedPackages - app.packageName
                        } else {
                            selectedPackages + app.packageName
                        }
                    }
                }

                item { Spacer(Modifier.height(4.dp)) }
            }

            // Кнопки — обычный элемент потока внизу экрана, а не наложение
            // поверх прокручиваемой формы (тот же принцип, что и на главном
            // экране): список приложений никогда не может на них "наехать".
            Column(modifier = Modifier.padding(20.dp)) {
                Button(
                    onClick = {
                        val trimmedName = name.trim()
                        val toSave = base.copy(
                            name = trimmedName.ifBlank { defaultScheduleName(type) },
                            type = type,
                            packages = selectedPackages,
                            daysOfWeek = daysOfWeek,
                            startMinute = startMinute,
                            endMinute = endMinute,
                            workMinutes = workMinutes.coerceAtLeast(1),
                            restMinutes = restMinutes.coerceAtLeast(0),
                            repeatMode = repeatMode,
                            repeatCount = repeatCount.coerceAtLeast(1),
                            rangeStartMinute = rangeStartMinute,
                            rangeEndMinute = rangeEndMinute,
                            anchorTime = System.currentTimeMillis()
                        )
                        onSave(toSave)
                    },
                    enabled = isValid,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black)
                ) {
                    Text(if (existing == null) "Создать" else "Сохранить", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                if (onDelete != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text("Удалить расписание", color = DangerRed)
                    }
                }
            }
        }
    }
}

// ===================== Переиспользуемые кусочки формы =====================

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

private data class Preset(
    val label: String,
    val name: String,
    val type: ScheduleType,
    val daysOfWeek: Set<Int> = WEEKDAYS,
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 18 * 60,
    val workMinutes: Int = 25,
    val restMinutes: Int = 5,
    val repeatMode: CyclicRepeatMode = CyclicRepeatMode.INFINITE,
    val rangeStartMinute: Int = 9 * 60,
    val rangeEndMinute: Int = 18 * 60
)

private val PRESETS = listOf(
    Preset(label = "Pomodoro 25/5", name = "Pomodoro 25/5", type = ScheduleType.CYCLIC, workMinutes = 25, restMinutes = 5),
    Preset(label = "Pomodoro 50/10", name = "Pomodoro 50/10", type = ScheduleType.CYCLIC, workMinutes = 50, restMinutes = 10),
    Preset(
        label = "Рабочий день", name = "Рабочий день", type = ScheduleType.TIME_BASED,
        daysOfWeek = WEEKDAYS, startMinute = 9 * 60, endMinute = 18 * 60
    ),
    Preset(
        label = "Учёба", name = "Учёба", type = ScheduleType.TIME_BASED,
        daysOfWeek = WEEKDAYS, startMinute = 16 * 60, endMinute = 19 * 60
    ),
    Preset(
        label = "Своё расписание", name = "", type = ScheduleType.TIME_BASED,
        daysOfWeek = WEEKDAYS, startMinute = 9 * 60, endMinute = 18 * 60
    )
)

@Composable
private fun PresetsRow(onSelect: (Preset) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(PRESETS) { preset ->
            PresetChip(preset.label) { onSelect(preset) }
        }
    }
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardBlack)
            .border(1.dp, TextSecondary.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TypeSelector(selected: ScheduleType, onSelect: (ScheduleType) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().glassCard(16).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TypeOption("По времени", selected == ScheduleType.TIME_BASED, Modifier.weight(1f)) { onSelect(ScheduleType.TIME_BASED) }
        TypeOption("Циклическое", selected == ScheduleType.CYCLIC, Modifier.weight(1f)) { onSelect(ScheduleType.CYCLIC) }
    }
}

@Composable
private fun TypeOption(label: String, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Cyan else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (isSelected) Color.Black else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

private val DAY_LABELS = listOf(
    Calendar.MONDAY to "Пн", Calendar.TUESDAY to "Вт", Calendar.WEDNESDAY to "Ср",
    Calendar.THURSDAY to "Чт", Calendar.FRIDAY to "Пт", Calendar.SATURDAY to "Сб", Calendar.SUNDAY to "Вс"
)

@Composable
private fun QuickDayButtons(onSelect: (Set<Int>) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickDayButton("Каждый день") { onSelect(ALL_DAYS) }
        QuickDayButton("Будни") { onSelect(WEEKDAYS) }
        QuickDayButton("Выходные") { onSelect(WEEKEND_DAYS) }
    }
}

@Composable
private fun QuickDayButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CardBlack)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DaysOfWeekSelector(selected: Set<Int>, onToggle: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        DAY_LABELS.forEach { (value, label) ->
            val isSelected = value in selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(if (isSelected) Cyan else Color.Transparent)
                    .border(1.dp, if (isSelected) Cyan else TextSecondary.copy(alpha = 0.3f), CircleShape)
                    .clickable { onToggle(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (isSelected) Color.Black else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun TimePickerField(label: String, minuteOfDay: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CardBlack)
            .border(1.dp, TextSecondary.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .clickable {
                android.app.TimePickerDialog(
                    context,
                    { _, hour, minute -> onChange(hour * 60 + minute) },
                    h, m, true
                ).show()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Text(String.format("%02d:%02d", h, m), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MinutesStepper(label: String, minutes: Int, onChange: (Int) -> Unit, step: Int = 5, min: Int = 1, max: Int = 180) {
    Column {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onChange((minutes - step).coerceAtLeast(min)) },
                modifier = Modifier.size(34.dp).clip(CircleShape).background(CardBlack)
            ) { Icon(Icons.Default.Remove, null, tint = Cyan, modifier = Modifier.size(16.dp)) }
            Text(
                "$minutes мин",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(76.dp)
            )
            IconButton(
                onClick = { onChange((minutes + step).coerceAtMost(max)) },
                modifier = Modifier.size(34.dp).clip(CircleShape).background(CardBlack)
            ) { Icon(Icons.Default.Add, null, tint = Cyan, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun NumberStepper(label: String, value: Int, onChange: (Int) -> Unit, min: Int = 1, max: Int = 99) {
    Column {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onChange((value - 1).coerceAtLeast(min)) },
                modifier = Modifier.size(34.dp).clip(CircleShape).background(CardBlack)
            ) { Icon(Icons.Default.Remove, null, tint = Cyan, modifier = Modifier.size(16.dp)) }
            Text(
                "$value",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(50.dp)
            )
            IconButton(
                onClick = { onChange((value + 1).coerceAtMost(max)) },
                modifier = Modifier.size(34.dp).clip(CircleShape).background(CardBlack)
            ) { Icon(Icons.Default.Add, null, tint = Cyan, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun RepeatModeSelector(selected: CyclicRepeatMode, onSelect: (CyclicRepeatMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RepeatModeOption(
            "Бесконечно", "Пока вы не выключите расписание",
            selected == CyclicRepeatMode.INFINITE
        ) { onSelect(CyclicRepeatMode.INFINITE) }
        RepeatModeOption(
            "Количество циклов", "Остановится само после N повторов",
            selected == CyclicRepeatMode.COUNT
        ) { onSelect(CyclicRepeatMode.COUNT) }
        RepeatModeOption(
            "Временной диапазон", "Повторяется только в пределах часов",
            selected == CyclicRepeatMode.TIME_RANGE
        ) { onSelect(CyclicRepeatMode.TIME_RANGE) }
    }
}

@Composable
private fun RepeatModeOption(title: String, subtitle: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) Cyan.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (isSelected) Cyan else TextSecondary.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (isSelected) {
            Icon(Icons.Default.CheckCircle, null, tint = Cyan, modifier = Modifier.size(20.dp))
        }
    }
}

// ===================== Форматирование и статус =====================

private fun defaultScheduleName(type: ScheduleType): String =
    if (type == ScheduleType.TIME_BASED) "Расписание" else "Циклическое расписание"

private fun formatClock(millis: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = millis
    return String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

private fun formatMinuteOfDay(minute: Int): String = String.format("%02d:%02d", minute / 60, minute % 60)

private fun daysSummary(days: Set<Int>): String {
    return when {
        days.isEmpty() -> "дни не выбраны"
        days == ALL_DAYS -> "каждый день"
        days == WEEKDAYS -> "будни"
        days == WEEKEND_DAYS -> "выходные"
        else -> DAY_LABELS.filter { it.first in days }.joinToString(" ") { it.second }
    }
}

private fun scheduleSummary(schedule: Schedule): String {
    return if (schedule.type == ScheduleType.TIME_BASED) {
        "${daysSummary(schedule.daysOfWeek)} · ${formatMinuteOfDay(schedule.startMinute)}–${formatMinuteOfDay(schedule.endMinute)}"
    } else {
        val cycle = "${schedule.workMinutes}/${schedule.restMinutes} мин"
        val mode = when (schedule.repeatMode) {
            CyclicRepeatMode.INFINITE -> "бесконечно"
            CyclicRepeatMode.COUNT -> "${schedule.repeatCount} циклов"
            CyclicRepeatMode.TIME_RANGE ->
                "${formatMinuteOfDay(schedule.rangeStartMinute)}–${formatMinuteOfDay(schedule.rangeEndMinute)}"
        }
        "$cycle · $mode"
    }
}

private fun scheduleStatusText(schedule: Schedule, now: Long): String {
    if (!schedule.enabled) return "Выключено"
    val segmentEnd = Scheduler.evaluate(schedule, now)
    return when {
        segmentEnd != null -> "Блокирует · до ${formatClock(segmentEnd)}"
        schedule.type == ScheduleType.CYCLIC && Scheduler.isInRestPhase(schedule, now) -> "Отдых"
        else -> "Ожидает своё окно"
    }
}
