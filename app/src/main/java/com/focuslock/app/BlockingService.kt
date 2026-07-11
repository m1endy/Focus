package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.focuslock.app.data.LockRepository

class BlockingService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            pollForegroundApp()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        startForegroundNotification()
        LockRepository.writeDebugEvent(this, "Служба подключена")
        handler.post(pollRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        evaluate(pkg)
    }

    // Резервный опрос поверх событий: TYPE_WINDOW_STATE_CHANGED иногда не
    // срабатывает при возврате в уже открытое приложение через список
    // недавних — окно просто меняет фокус без "пересоздания", и система не
    // шлёт событие. Из-за этого раньше оверлей не поднимался повторно, и
    // заблокированным приложением можно было пользоваться. Поэтому параллельно
    // с событиями периодически напрямую спрашиваем, какое окно сейчас в фокусе.
    private fun pollForegroundApp() {
        val effective = LockRepository.readEffectiveBlockState(this)
        if (!effective.active) return
        val activeWindow = try {
            windows?.firstOrNull { it.isFocused }
        } catch (e: Exception) {
            null
        } ?: return
        val pkg = activeWindow.root?.packageName?.toString() ?: return
        evaluate(pkg)
    }

    private fun evaluate(pkg: String) {
        if (pkg == packageName) return

        // readEffectiveBlockState объединяет быструю блокировку и все включённые
        // расписания (см. data/Schedules.kt) и попутно закрывает истёкшую сессию
        // быстрой блокировки/исчерпанные циклические расписания — отдельно это
        // здесь делать не нужно.
        val effective = LockRepository.readEffectiveBlockState(this)
        if (!effective.active) {
            // Раньше при !isBlocking функция просто выходила: это было безопасно,
            // потому что единственный путь к isBlocking=false уже сам снимал
            // оверлей. Для расписаний такого "события" нет — фаза блокировки
            // заканчивается сама по себе, когда истекает время, поэтому здесь
            // нужно самим проверить и снять оверлей, если он всё ещё висит.
            if (LockRepository.isCurrentlyOnBlockedApp(this)) {
                LockRepository.setCurrentlyOnBlockedApp(this, false)
                LockRepository.writeDebugEvent(this, "Блокировок нет → снимаю оверлей")
                stopService(Intent(this, OverlayService::class.java))
            }
            return
        }

        val shouldBlock = pkg in effective.blockedPackages || pkg == "com.android.settings"
        val wasBlocked = LockRepository.isCurrentlyOnBlockedApp(this)
        if (shouldBlock == wasBlocked) return // ничего не поменялось — не дёргаем службу зря

        LockRepository.setCurrentlyOnBlockedApp(this, shouldBlock)

        if (shouldBlock) {
            LockRepository.writeDebugEvent(this, "Блокирую: $pkg → запускаю оверлей")
            // Безопасно вызывать многократно: OverlayService сам не пересоздаёт
            // окно, если оно уже показано (см. onStartCommand).
            startForegroundService(Intent(this, OverlayService::class.java))
        } else {
            LockRepository.writeDebugEvent(this, "Не заблокировано: $pkg → снимаю оверлей")
            // Пользователь ушёл из заблокированного приложения (например, на
            // Home) — оверлей больше не нужен именно сейчас, хотя таймер
            // фокус-сессии продолжает идти в фоне.
            stopService(Intent(this, OverlayService::class.java))
        }
    }

    override fun onInterrupt() {}

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val existing = nm.getNotificationChannel("focuslock")
            if (existing == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        "focuslock", "FocusLock", NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        val notification: Notification = NotificationCompat.Builder(this, "focuslock")
            .setContentTitle("FocusLock активен")
            .setContentText("Служба следит за заблокированными приложениями")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1001, notification)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 600L
    }
}
