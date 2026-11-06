package com.mysocial.companion

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.app.RemoteInput
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MyNotificationListener : NotificationListenerService(), TextToSpeech.OnInitListener {
    private var reader: TextToSpeech? = null
    private var readerReady = false

    override fun onCreate() {
        super.onCreate()
        reader = TextToSpeech(applicationContext, this)
    }

    override fun onInit(status: Int) {
        readerReady = status == TextToSpeech.SUCCESS
        if (readerReady) reader?.language = Locale.getDefault()
    }

    companion object {
        const val ACTION_MESSAGE = "com.mysocial.companion.NEW_MESSAGE"
        const val EXTRA_ID = "message_id"
        const val EXTRA_TITLE = "message_title"
        const val EXTRA_TEXT = "message_text"
        private data class ReplyTarget(val pendingIntent: PendingIntent, val remoteInputs: Array<RemoteInput>)
        private val replyTargets = ConcurrentHashMap<String, ReplyTarget>()

        fun sendApprovedReply(context: Context, id: String, text: String): Boolean {
            val target = replyTargets[id] ?: return false
            return try {
                val fillIn = Intent()
                val results = Bundle()
                target.remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
                RemoteInput.addResultsToIntent(target.remoteInputs, fillIn, results)
                target.pendingIntent.send(context, 0, fillIn)
                replyTargets.remove(id)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isBlank() || text.isBlank()) return

        val replyAction = n.actions?.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true && action.actionIntent != null
        }
        val id = UUID.randomUUID().toString()
        if (replyAction != null) {
            @Suppress("DEPRECATION")
            replyTargets[id] = ReplyTarget(replyAction.actionIntent, replyAction.remoteInputs!!)
        }
        val event = Intent(ACTION_MESSAGE).setPackage(packageName).apply {
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
            putExtra("can_reply", replyAction != null)
        }
        // Speak directly from the listener service so reading can work while the
        // companion screen is not open. The user can revoke Notification Access.
        if (readerReady) reader?.speak("$title says. $text", TextToSpeech.QUEUE_ADD, null, id)
        sendBroadcast(event)
    }

    override fun onDestroy() {
        reader?.shutdown()
        reader = null
        super.onDestroy()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Reply targets are short-lived in-memory references; no message content is persisted.
    }
}
