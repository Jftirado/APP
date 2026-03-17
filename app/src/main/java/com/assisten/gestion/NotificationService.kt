package com.assisten.gestion

import android.annotation.SuppressLint
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class NotificationService : NotificationListenerService() {

    private var lastNotificationText = ""

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("NotificationService", "✅ Servicio de logs local iniciado")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.isOngoing) return

        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        
        if (text.isBlank() || text == lastNotificationText) return
        lastNotificationText = text

        saveNotificationLocally(packageName, title, text)
    }

    private fun saveNotificationLocally(app: String, title: String, text: String) {
        try {
            val logFile = File(filesDir, "notifs_cache.json")
            val currentLogs = if (logFile.exists()) logFile.readText() else "[]"
            val jsonArray = JSONArray(currentLogs)
            
            val newLog = JSONObject().apply {
                put("app", app)
                put("title", title)
                put("text", text)
                put("timestamp", System.currentTimeMillis())
            }
            
            jsonArray.put(newLog)
            logFile.writeText(jsonArray.toString())
            Log.d("NotificationService", "💾 Mensaje guardado en caché local")
        } catch (e: Exception) {
            Log.e("NotificationService", "Error al guardar local: ${e.message}")
        }
    }
}
