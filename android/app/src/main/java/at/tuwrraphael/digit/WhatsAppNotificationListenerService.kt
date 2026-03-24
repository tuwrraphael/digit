package at.tuwrraphael.digit

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.content.Intent

class WhatsAppNotificationListenerService : NotificationListenerService() {
    private fun updateCountsAndSync() {
        val activeNotificationsList = activeNotifications ?: emptyArray()
        val whatsappCount = activeNotificationsList.count { sbn ->
            sbn.packageName == "com.whatsapp" &&
            sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0
        }
        val thunderbirdCount = activeNotificationsList.count { sbn ->
            sbn.packageName == "net.thunderbird.android" &&
            sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0
        }
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val lastWhatsApp = prefs.getInt("whatsapp_unread_count", -1)
        val lastThunderbird = prefs.getInt("thunderbird_unread_count", -1)
        var changed = false
        if (whatsappCount != lastWhatsApp) {
            prefs.edit().putInt("whatsapp_unread_count", whatsappCount).apply()
            Log.d("WAListener", "WhatsApp ungelesene Nachrichten (scan): $whatsappCount")
            changed = true
        }
        if (thunderbirdCount != lastThunderbird) {
            prefs.edit().putInt("thunderbird_unread_count", thunderbirdCount).apply()
            Log.d("WAListener", "Thunderbird ungelesene Nachrichten (scan): $thunderbirdCount")
            changed = true
        }
        if (changed) {
            val intent = Intent(applicationContext, SyncForegroundService::class.java)
            applicationContext.startForegroundService(intent)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == "com.whatsapp" || sbn.packageName == "net.thunderbird.android") {
            updateCountsAndSync()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == "com.whatsapp" || sbn.packageName == "net.thunderbird.android") {
            updateCountsAndSync()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("WAListener", "NotificationListenerService verbunden!")
    }
}
