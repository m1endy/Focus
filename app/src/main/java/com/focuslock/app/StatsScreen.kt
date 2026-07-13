package com.focuslock.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focuslock.app.data.AppInfo
import com.focuslock.app.data.MainViewModel
import com.focuslock.app.data.StatsPeriod
import com.focuslock.app.data.computeStats
import com.focuslock.app.ui.Cyan
import com.focuslock.app.ui.CyanDim
import com.focuslock.app.ui.GlassIconBadge
import com.focuslock.app.ui.OrganicBackground
import com.focuslock.app.ui.TextPrimary
import com.focuslock.app.ui.TextSecondary
import com.focuslock.app.ui.glassCard
import com.focuslock.app.ui.swipeToChange

@Composable
fun StatsScreen(viewModel: MainViewModel) {
    val sessions by viewModel.sessionHistory.collectAsState()
    val apps by viewModel.installedApps.collectAsState()
    val appsByPackage = remember(apps) { apps.associateBy { it.packageName } }
    var period by remember { mutableStateOf(StatsPeriod.TODAY) }
    val haptics = LocalHapticFeedback.current

    fun changePeriod(next: StatsPeriod) {
        if (next != period) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            period = next
        }
    }

    OrganicBackground {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text("Статистика", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "Сколько времени приложения были недоступны",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            PeriodSelector(selected = period, onSelect = { changePeriod(it) })
            Spacer(Modifier.height(16.dp))

            // Кнопки выше продолжают работать как раньше; вдобавок весь блок
            // ниже можно пролистать свайпом влево/вправо — как страницы между
            // периодами, с анимацией скольжения в нужную сторону.
            AnimatedContent(
                targetState = period,
                modifier = Modifier
                    .weight(1f)
                    .swipeToChange(
                        onSwipeLeft = {
                            StatsPeriod.values().getOrNull(period.ordinal + 1)?.let { changePeriod(it) }
                        },
                        onSwipeRight = {
                            StatsPeriod.values().getOrNull(period.ordinal - 1)?.let { changePeriod(it) }
                        }
                    ),
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    if (forward) {
                        (slideInHorizontally(initialOffsetX = { it }) + fadeIn())
                            .togetherWith(slideOutHorizontally(targetOffsetX = { -it }) + fadeOut())
                    } else {
                        (slideInHorizontally(initialOffsetX = { -it }) + fadeIn())
                            .togetherWith(slideOutHorizontally(targetOffsetX = { it }) + fadeOut())
                    }
                },
                label = "statsPeriodContent"
            ) { p ->
                val stats = remember(sessions, p) { computeStats(sessions, p) }
                Column(modifier = Modifier.fillMaxSize()) {
                    TotalStatCard(totalMinutes = stats.totalMinutes, period = p)
                    Spacer(Modifier.height(20.dp))

                    if (stats.perApp.isEmpty()) {
                        EmptyStatsState()
                    } else {
                        Text(
                            "По приложениям",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        val maxMinutes = stats.perApp.first().minutes.coerceAtLeast(1)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(stats.perApp, key = { it.packageName }) { stat ->
                                AppStatRow(
                                    appInfo = appsByPackage[stat.packageName],
                                    packageName = stat.packageName,
                                    minutes = stat.minutes,
                                    fraction = stat.minutes.toFloat() / maxMinutes.toFloat()
                                )
                            }
                            item { Spacer(Modifier.height(90.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodSelector(selected: StatsPeriod, onSelect: (StatsPeriod) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().glassCard(16).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatsPeriod.values().forEach { p ->
            val isSelected = p == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) Cyan else Color.Transparent)
                    .clickable { onSelect(p) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    p.label,
                    color = if (isSelected) Color.Black else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun TotalStatCard(totalMinutes: Long, period: StatsPeriod) {
    Row(
        modifier = Modifier.fillMaxWidth().glassCard(20).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassIconBadge(Icons.Default.BarChart, sizeDp = 46)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(formatMinutes(totalMinutes), color = Cyan, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                "заблокировано \u00b7 ${period.label.lowercase()}",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun AppStatRow(appInfo: AppInfo?, packageName: String, minutes: Long, fraction: Float) {
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0.03f, 1f),
        animationSpec = tween(500),
        label = "statBar"
    )
    Column(
        modifier = Modifier.fillMaxWidth().glassCard(14).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            appInfo?.icon?.let { drawable ->
                Image(
                    bitmap = drawable.toBitmap(96, 96).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                )
            } ?: Box(modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(CyanDim))
            Spacer(Modifier.width(12.dp))
            Text(
                appInfo?.label ?: packageName,
                color = TextPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(formatMinutes(minutes), color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(TextSecondary.copy(alpha = 0.15f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedFraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(CyanDim, Cyan)))
            )
        }
    }
}

@Composable
private fun EmptyStatsState() {
    Column(
        modifier = Modifier.fillMaxWidth().glassCard(16).padding(vertical = 36.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GlassIconBadge(Icons.Default.BarChart, sizeDp = 54, accent = CyanDim)
        Spacer(Modifier.height(14.dp))
        Text("Пока нет данных", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Здесь появится статистика после первых сессий блокировки",
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

private fun formatMinutes(totalMinutes: Long): String {
    if (totalMinutes <= 0) return "0 мин"
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "$h ч $m мин"
        h > 0 -> "$h ч"
        else -> "$m мин"
    }
}
