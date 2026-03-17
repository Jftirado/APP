package com.assisten.gestion

import android.annotation.SuppressLint
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

class NotificationService : NotificationListenerService() {

    private var lastNotificationText = ""

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("NotificationService", "✅ Servicio conectado")
    }

    @SuppressLint("HardwareIds")
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.isOngoing) return // Ignorar notificaciones persistentes (música, llamadas, etc.)

        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        
        if (text.isBlank() || text == lastNotificationText) return
        lastNotificationText = text

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val log = mapOf(
            "app" to packageName,
            "title" to title,
            "text" to text,
            "timestamp" to System.currentTimeMillis()
        )

        FirebaseDatabase.getInstance().reference
            .child("notifs").child(deviceId).push().setValue(log)
    }
}
