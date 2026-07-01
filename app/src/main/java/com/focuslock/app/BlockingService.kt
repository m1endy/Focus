package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.focuslock.app.data.LockRepository

class BlockingService : AccessibilityService() {

    private var lastBlockedPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        startForegroundNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val (isBlocking, endTime, blockedPackages) = LockRepository.readBlockingState(this)

        if (!isBlocking) {
            lastBlockedPackage = null
            return
        }

        if (System.currentTimeMillis() >= endTime) {
            LockRepository.clearBlockingState(this)
            stopService(Intent(this, OverlayService::class.java))
            lastBlockedPackage = null
            return
        }

        val shouldBlock = pkg in blockedPackages || pkg == "com.android.settings"
        if (shouldBlock && pkg != lastBlockedPackage) {
            lastBlockedPackage = pkg
            val intent = Intent(this, OverlayService::class.java)
            startForegroundService(intent)
        } else if (!shouldBlock) {
            lastBlockedPackage = null
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
        // AccessibilityService не является Service с foregroundServiceType напрямую в этом методе,
        // уведомление публикуется через NotificationManager для видимости пользователю.
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1001, notification)
    }
}
