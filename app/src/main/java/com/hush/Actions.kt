package com.hush

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.widget.Toast

/** Runs a phrase's action with direct APIs, not by opening other apps (D-23). TTS is pre-warmed (D-21). */
class Actions(private val context: Context) {
    private val tts = TextToSpeech(context) {}

    fun run(p: Phrase) {
        when (p.action) {
            Action.SPEAK -> tts.speak(p.text, TextToSpeech.QUEUE_FLUSH, null, p.id)
            Action.SMS -> {
                @Suppress("DEPRECATION") // ponytail: getDefault() is fine for single-SIM demo phones
                SmsManager.getDefault().sendTextMessage(p.target, null, p.message.ifBlank { p.text }, null, null)
                toast("Message sent to ${p.target}")
            }
            // Emergency calls a contact you set (e.g. a carer), never 112/911: a misread must not reach real services.
            Action.CALL, Action.EMERGENCY -> start(Intent(Intent.ACTION_CALL, Uri.parse("tel:${p.target}")))
            Action.TIMER -> start(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, (p.target.toIntOrNull() ?: 1) * 60)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, p.text)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            )
            Action.ALARM -> {
                val (h, m) = p.target.split(":").map { it.trim().toIntOrNull() ?: 0 } + listOf(0, 0)
                start(
                    Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, p.text)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
                )
            }
        }
    }

    /** One-tap undo for instant actions: stop speech, or open the timers to cancel. */
    fun stop(p: Phrase) {
        when (p.action) {
            Action.SPEAK -> tts.stop()
            Action.TIMER -> start(Intent(AlarmClock.ACTION_SHOW_TIMERS))
            else -> {}
        }
    }

    private fun start(i: Intent) = try {
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast("Couldn't run that: ${e.message}")
    }

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

    fun close() = tts.shutdown()
}
