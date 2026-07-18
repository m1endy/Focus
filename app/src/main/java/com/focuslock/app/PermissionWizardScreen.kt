package com.focuslock.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focuslock.app.ui.Cyan
import com.focuslock.app.ui.GlassIconBadge
import com.focuslock.app.ui.OrganicBackground
import com.focuslock.app.ui.TextPrimary
import com.focuslock.app.ui.TextSecondary
import com.focuslock.app.ui.glassCard

private enum class WizardStep { OVERLAY, BATTERY, ACCESSIBILITY }

/**
 * Мастер первого запуска. Пока хоть одно из трёх разрешений не выдано,
 * MainActivity показывает этот экран вместо вкладок — по одному шагу за раз,
 * с объяснением и прямой кнопкой в нужный системный экран. Порядок шагов
 * фиксированный (Overlay → Battery → Accessibility): специальные возможности
 * идут последними как самый содержательный шаг — так его завершение
 * ощущается как окончание всей настройки.
 */
@Composable
fun PermissionWizardScreen(
    accessibilityGranted: Boolean,
    overlayGranted: Boolean,
    batteryOptGranted: Boolean
) {
    val context = LocalContext.current

    val steps = remember(overlayGranted, batteryOptGranted, accessibilityGranted) {
        buildList {
            if (!overlayGranted) add(WizardStep.OVERLAY)
            if (!batteryOptGranted) add(WizardStep.BATTERY)
            if (!accessibilityGranted) add(WizardStep.ACCESSIBILITY)
        }
    }
    // Все три уже выданы — родитель (MainActivity) в следующей же перекомпозиции
    // уберёт этот экран сам; здесь просто ничего не рисуем на переходный кадр.
    val current = steps.firstOrNull() ?: return
    val doneCount = 3 - steps.size

    OrganicBackground(contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Настройка FocusLock",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Шаг ${doneCount + 1} из 3",
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            StepDots(total = 3, doneCount = doneCount)

            Spacer(Modifier.weight(1f))

            GlassIconBadge(iconFor(current), sizeDp = 76)
            Spacer(Modifier.height(24.dp))
            Text(
                titleFor(current),
                color = TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                explanationFor(current),
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // Ограниченные настройки (Restricted settings) — с Android 13 система
            // по умолчанию блокирует включение специальных возможностей у
            // приложений, установленных не из магазина. Без явного объяснения
            // пользователь просто увидит серый неактивный переключатель и не
            // поймёт, что делать. Прямого Intent на этот конкретный экран не
            // существует (он спрятан в меню "⋮" экрана информации о приложении),
            // поэтому даём точные пошаговые указания и кнопку в ближайший
            // экран, откуда до него две секунды.
            if (current == WizardStep.ACCESSIBILITY && Build.VERSION.SDK_INT >= 33) {
                Spacer(Modifier.height(18.dp))
                Column(modifier = Modifier.fillMaxWidth().glassCard(20).padding(18.dp)) {
                    Text(
                        "Если переключатель серый или не включается",
                        color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        "Откройте информацию о приложении кнопкой ниже",
                        "Нажмите значок ⋮ в правом верхнем углу",
                        "Выберите «Разрешить ограниченные настройки»",
                        "Вернитесь назад и включите службу FocusLock"
                    ).forEachIndexed { i, line ->
                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(
                                "${i + 1}.",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.width(20.dp)
                            )
                            Text(line, color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = { openAppInfo(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                    ) {
                        Text("Открыть информацию о приложении")
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { openSettingsFor(context, current) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black)
            ) {
                Text(buttonTextFor(current), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun StepDots(total: Int, doneCount: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            i < doneCount -> Cyan
                            i == doneCount -> Cyan.copy(alpha = 0.55f)
                            else -> TextSecondary.copy(alpha = 0.25f)
                        }
                    )
            )
        }
    }
}

private fun iconFor(step: WizardStep): ImageVector = when (step) {
    WizardStep.OVERLAY -> Icons.Default.Layers
    WizardStep.BATTERY -> Icons.Default.BatteryChargingFull
    WizardStep.ACCESSIBILITY -> Icons.Default.Accessibility
}

private fun titleFor(step: WizardStep): String = when (step) {
    WizardStep.OVERLAY -> "Отображение поверх других приложений"
    WizardStep.BATTERY -> "Работа без ограничений по батарее"
    WizardStep.ACCESSIBILITY -> "Специальные возможности"
}

private fun explanationFor(step: WizardStep): String = when (step) {
    WizardStep.OVERLAY ->
        "Чтобы экран блокировки появлялся поверх заблокированных приложений, разрешите FocusLock отображаться поверх других окон."
    WizardStep.BATTERY ->
        "Система может «замораживать» FocusLock в фоне без этого разрешения — тогда блокировка способна оборваться раньше времени."
    WizardStep.ACCESSIBILITY ->
        "FocusLock использует службу специальных возможностей, чтобы видеть, какое приложение открыто сейчас, и вовремя показывать экран блокировки."
}

private fun buttonTextFor(step: WizardStep): String = when (step) {
    WizardStep.OVERLAY -> "Открыть настройки"
    WizardStep.BATTERY -> "Открыть настройки"
    WizardStep.ACCESSIBILITY -> "Открыть специальные возможности"
}

private fun openAppInfo(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    } catch (e: Exception) { }
}

private fun openSettingsFor(context: Context, step: WizardStep) {
    val primary = when (step) {
        WizardStep.OVERLAY -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")
        )
        WizardStep.BATTERY -> Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")
        )
        WizardStep.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
    try {
        context.startActivity(primary)
    } catch (e: Exception) {
        // Нужный экран недоступен на этой прошивке/сборке — открываем
        // ближайший гарантированно существующий (информация о приложении).
        // Объяснение и дальнейшие шаги уже на экране, так что пользователь не
        // остаётся без указаний, даже если конкретный системный экран не открылся.
        openAppInfo(context)
    }
}
