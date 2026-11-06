package com.mysocial.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var root: LinearLayout
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var currentMessageId: String? = null
    private var currentCanReply = false
    private var currentTitle = ""
    private var currentText = ""
    private val messages = mutableListOf<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MyNotificationListener.ACTION_MESSAGE) return
            val id = intent.getStringExtra(MyNotificationListener.EXTRA_ID) ?: return
            val title = intent.getStringExtra(MyNotificationListener.EXTRA_TITLE).orEmpty()
            val body = intent.getStringExtra(MyNotificationListener.EXTRA_TEXT).orEmpty()
            val canReply = intent.getBooleanExtra("can_reply", false)
            runOnUiThread {
                currentMessageId = id
                currentTitle = title
                currentText = body
                currentCanReply = canReply
                messages.add(0, "$title: $body")
                render()
                readMessage()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        render()
        val filter = IntentFilter(MyNotificationListener.ACTION_MESSAGE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts.language = Locale.getDefault()
    }

    private fun render() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 42, 32, 24) }
        root.addView(TextView(this).apply { text = "MySocial"; textSize = 28f; setTextColor(0xFF075E54.toInt()) })
        root.addView(TextView(this).apply { text = "WhatsApp voice assistant · approval required"; textSize = 14f })
        root.addView(Button(this).apply {
            text = "Enable WhatsApp notification access"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        root.addView(TextView(this).apply {
            text = "When a supported WhatsApp notification arrives, MySocial reads it aloud. Dictate a reply, listen to the draft, then approve before sending."
            textSize = 15f; setPadding(0, 18, 0, 18)
        })
        root.addView(TextView(this).apply {
            text = if (currentText.isBlank()) "Waiting for a WhatsApp notification…" else "$currentTitle\n\n$currentText"
            textSize = 18f; setPadding(0, 12, 0, 12)
        })
        root.addView(Button(this).apply { text = "Read message again"; setOnClickListener { readMessage() } })
        root.addView(Button(this).apply {
            text = "🎙️ Dictate reply"
            isEnabled = currentMessageId != null && currentCanReply
            setOnClickListener { startDictation() }
        })
        root.addView(TextView(this).apply {
            text = if (currentMessageId == null) "No message selected." else if (currentCanReply) "A WhatsApp inline-reply action is available." else "This notification has no supported inline-reply action. You can read it, but cannot reply from this notification."
            textSize = 13f; setPadding(0, 12, 0, 12)
        })
        root.addView(TextView(this).apply { text = "Recent notifications (session only)"; textSize = 16f; setPadding(0, 18, 0, 8) })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        messages.take(8).forEach { line -> list.addView(TextView(this).apply { text = line; textSize = 13f; setPadding(0, 6, 0, 6) }) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun readMessage() {
        if (currentText.isBlank()) return
        if (ttsReady) tts.speak("$currentTitle says. $currentText", TextToSpeech.QUEUE_FLUSH, null, "mysocial-message")
        else Toast.makeText(this, currentText, Toast.LENGTH_LONG).show()
    }

    private fun startDictation() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your WhatsApp reply")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        try { startActivityForResult(intent, 41) }
        catch (_: Exception) { Toast.makeText(this, "Speech recognition is not available on this phone.", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Android speech recognizer result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK) return
        val draft = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (draft.isBlank()) return
        if (ttsReady) tts.speak("Your reply is: $draft", TextToSpeech.QUEUE_FLUSH, null, "mysocial-draft")
        AlertDialog.Builder(this)
            .setTitle("Approve WhatsApp reply?")
            .setMessage("To: $currentTitle\n\n$draft\n\nSend this reply now?")
            .setNegativeButton("Edit / cancel", null)
            .setPositiveButton("Approve and send") { _, _ ->
                val id = currentMessageId ?: return@setPositiveButton
                val sent = MyNotificationListener.sendApprovedReply(this, id, draft)
                Toast.makeText(this, if (sent) "Reply action submitted to WhatsApp." else "Could not send: notification reply action expired or is unavailable.", Toast.LENGTH_LONG).show()
                currentCanReply = false
                render()
            }.show()
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }
}
