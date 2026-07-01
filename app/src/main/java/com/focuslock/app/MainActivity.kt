package com.focuslock.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focuslock.app.data.AppInfo
import com.focuslock.app.data.MainViewModel
import com.focuslock.app.ui.*
import kotlinx.coroutines.delay

class FocusLockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "focuslock", "FocusLock", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    val expected = "${context.packageName}/${BlockingService::class.java.canonicalName}"
    val enabled = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabled)
    while (splitter.hasNext()) {
        if (splitter.next().equals(expected, ignoreCase = true)) return true
    }
    return false
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FocusLockTheme {
                val isBlocking by viewModel.isBlocking.collectAsState()
                val endTime by viewModel.endTime.collectAsState()
                Surface(modifier = Modifier.fillMaxSize(), color = DeepBlack) {
                    Crossfade(targetState = isBlocking, label = "nav") { blocking ->
                        if (blocking) {
                            ActiveBlockScreen(endTime = endTime)
                        } else {
                            MainScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val apps by viewModel.installedApps.collectAsState()
    val selected by viewModel.selectedPackages.collectAsState()
    var search by remember { mutableStateOf("") }
    var durationMinutes by remember { mutableStateOf(30) }
    var showAccessibilityDialog by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<Int?>(null) }

    val filtered = remember(apps, search) {
        if (search.isBlank()) apps else apps.filter { it.label.contains(search, ignoreCase = true) }
    }

    LaunchedEffect(countdown) {
        val c = countdown ?: return@LaunchedEffect
        if (c > 0) {
            delay(1000)
            countdown = c - 1
        } else {
            viewModel.startBlocking(durationMinutes * 60_000L)
            val intent = Intent(context, OverlayService::class.java)
            context.startForegroundService(intent)
            countdown = null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(ScreenGradient)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text(
                "FocusLock",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Заблокируй отвлечения и сфокусируйся",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            // Duration selector
            Row(
                modifier = Modifier.fillMaxWidth().glassCard(16).padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Время блокировки", color = TextSecondary, fontSize = 12.sp)
                    Text("$durationMinutes мин", color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 120).forEach { m ->
                        DurationChip(m, m == durationMinutes) { durationMinutes = m }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

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

            Spacer(Modifier.height(12.dp))

            Text(
                "Выбрано: ${selected.size}",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    AppRow(app, app.packageName in selected) { viewModel.toggleApp(app.packageName) }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        val pulse = rememberInfiniteTransition(label = "pulse")
        val scale by pulse.animateFloat(
            initialValue = 1f, targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale"
        )

        Button(
            onClick = {
                if (selected.isEmpty()) return@Button
                if (!isAccessibilityServiceEnabled(context)) {
                    showAccessibilityDialog = true
                } else {
                    countdown = 3
                }
            },
            enabled = selected.isNotEmpty() && countdown == null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(20.dp)
                .fillMaxWidth()
                .height(58.dp)
                .graphicsLayerScale(scale),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black)
        ) {
            Icon(Icons.Default.Lock, null)
            Spacer(Modifier.width(8.dp))
            Text("Начать блокировку", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        countdown?.let { c ->
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(targetState = c, label = "countdown") { value ->
                    Text(
                        text = if (value > 0) "$value" else "🔒",
                        color = Cyan,
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (showAccessibilityDialog) {
            AlertDialog(
                onDismissRequest = { showAccessibilityDialog = false },
                containerColor = CardBlack,
                title = { Text("Нужен доступ", color = TextPrimary) },
                text = {
                    Text(
                        "Чтобы блокировка работала, включите службу «FocusLock» в Специальных возможностях.",
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showAccessibilityDialog = false
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("Открыть настройки", color = Cyan) }
                },
                dismissButton = {
                    TextButton(onClick = { showAccessibilityDialog = false }) {
                        Text("Отмена", color = TextSecondary)
                    }
                }
            )
        }
    }
}

fun Modifier.graphicsLayerScale(scale: Float): Modifier = this.then(
    Modifier.graphicsLayer(scaleX = scale, scaleY = scale)
)

@Composable
fun DurationChip(minutes: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Cyan else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) Cyan else TextSecondary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            "$minutes",
            color = if (selected) Color.Black else TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun AppRow(app: AppInfo, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(14)
            .clickable { onToggle() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        app.icon?.let { drawable ->
            Image(
                bitmap = drawable.toBitmap(96, 96).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
            )
        } ?: Box(modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(CyanDim))
        Spacer(Modifier.width(12.dp))
        Text(app.label, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = Cyan, uncheckedColor = TextSecondary)
        )
    }
}

@Composable
fun ActiveBlockScreen(endTime: Long) {
    var remaining by remember { mutableStateOf(((endTime - System.currentTimeMillis()) / 1000).coerceAtLeast(0)) }

    LaunchedEffect(endTime) {
        while (remaining > 0) {
            delay(1000)
            remaining = ((endTime - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        }
    }

    BackHandler(enabled = true) { /* блокировка активна — назад не работает */ }

    Box(
        modifier = Modifier.fillMaxSize().background(ScreenGradient),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, null, tint = Cyan, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            Text("Фокус активен", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            val h = remaining / 3600
            val m = (remaining % 3600) / 60
            val s = remaining % 60
            Text(
                String.format("%02d:%02d:%02d", h, m, s),
                color = Cyan,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Заблокированные приложения недоступны до конца таймера",
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp)
            )
        }
    }
}
