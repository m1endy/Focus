package com.focuslock.app

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.focuslock.app.data.LockRepository
import com.focuslock.app.ui.Cyan
import com.focuslock.app.ui.CyanDim
import com.focuslock.app.ui.DeepBlack
import com.focuslock.app.ui.TextSecondary
import kotlinx.coroutines.delay

/** Минимальный владелец жизненного цикла для ComposeView вне Activity */
private class OverlayLifecycleOwner :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    fun performRestore() {
        savedStateController.performRestore(null)
    }
}

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null) showOverlay()
        return START_STICKY
    }

    private fun showOverlay() {
        val (isBlocking, endTime, _) = LockRepository.readBlockingState(this)
        if (!isBlocking) {
            stopSelf()
            return
        }

        val owner = OverlayLifecycleOwner()
        owner.performRestore()
        owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        lifecycleOwner = owner

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        // Без FLAG_NOT_FOCUSABLE и FLAG_NOT_TOUCH_MODAL: оверлей перехватывает
        // все касания и клавиши (в т.ч. Back), не пропуская их дальше.
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                OverlayContent(endTime = endTime, onExpired = {
                    LockRepository.clearBlockingState(this@OverlayService)
                    stopSelf()
                })
            }
        }
        overlayView = view

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        // Самовосстановление: если блокировка ещё активна, а оверлей уничтожен — поднимаем снова
        val (isBlocking, endTime, _) = LockRepository.readBlockingState(this)
        if (isBlocking && System.currentTimeMillis() < endTime) {
            val restart = Intent(this, OverlayService::class.java)
            startForegroundService(restart)
        }
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try { windowManager?.removeView(view) } catch (e: Exception) {}
        }
        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        overlayView = null
        lifecycleOwner = null
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel("focuslock") == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        "focuslock", "FocusLock", NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        val notification = NotificationCompat.Builder(this, "focuslock")
            .setContentTitle("FocusLock: блокировка активна")
            .setContentText("Заблокированные приложения недоступны")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setSilent(true)
            .build()
        startForeground(1002, notification)
    }
}

@Composable
fun OverlayContent(endTime: Long, onExpired: () -> Unit) {
    var remaining by remember { mutableStateOf(((endTime - System.currentTimeMillis()) / 1000).coerceAtLeast(0)) }
    val totalDuration = remember { ((endTime - System.currentTimeMillis()) / 1000).coerceAtLeast(1) }

    LaunchedEffect(endTime) {
        while (true) {
            val left = ((endTime - System.currentTimeMillis()) / 1000)
            remaining = left.coerceAtLeast(0)
            if (left <= 0) {
                onExpired()
                break
            }
            delay(1000)
        }
    }

    val progress = (remaining.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    val infinite = rememberInfiniteTransition(label = "glow")
    val glow by infinite.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "glowAlpha"
    )

    Surface(modifier = Modifier.fillMaxSize(), color = DeepBlack) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 14.dp.toPx()
                    drawArc(
                        color = CyanDim.copy(alpha = 0.25f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = stroke)
                    )
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Cyan, CyanDim, Cyan)),
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )
                }
                val h = remaining / 3600
                val m = (remaining % 3600) / 60
                val s = remaining % 60
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = String.format("%02d:%02d:%02d", h, m, s),
                        color = Cyan.copy(alpha = glow),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "🔒 Заблокировано",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val (isBlocking, endTime, _) = LockRepository.readBlockingState(context)
        if (isBlocking && System.currentTimeMillis() < endTime) {
            val serviceIntent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else if (isBlocking) {
            LockRepository.clearBlockingState(context)
        }
    }
}
