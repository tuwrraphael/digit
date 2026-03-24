package at.tuwrraphael.digit

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.content.Intent

class WhatsAppNotificationListenerService : NotificationListenerService() {
    private fun updateWhatsAppCountAndSync() {
        val active = activeNotifications?.count { sbn ->
            sbn.packageName == "com.whatsapp" &&
            sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0
        } ?: 0
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val lastCount = prefs.getInt("whatsapp_unread_count", -1)
        if (active != lastCount) {
            prefs.edit().putInt("whatsapp_unread_count", active).apply()
            Log.d("WAListener", "WhatsApp ungelesene Nachrichten (scan): $active")
            val intent = Intent(applicationContext, SyncForegroundService::class.java)
            applicationContext.startForegroundService(intent)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == "com.whatsapp") {
            updateWhatsAppCountAndSync()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == "com.whatsapp") {
            updateWhatsAppCountAndSync()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("WAListener", "NotificationListenerService verbunden!")
    }
}
