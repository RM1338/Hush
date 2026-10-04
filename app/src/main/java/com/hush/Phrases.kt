package com.hush

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

typealias Clip = List<FloatArray>

enum class Action(val label: String, val needsConfirm: Boolean) {
    SPEAK("Speak aloud", false), // D-24: instant, with one-tap stop
    TIMER("Timer", false),
    SMS("Text message", true),
    CALL("Call", true),
    EMERGENCY("Emergency call", true),
    ALARM("Alarm", true),
}

/**
 * A taught phrase. [target] is a phone number (SMS, call, emergency), minutes (timer) or "HH:MM" (alarm);
 * [message] is the SMS text. [takes] are the cleaned lip-dot clips from teaching, not fingerprints (D-37).
 */
data class Phrase(
    val id: String,
    val text: String,
    val action: Action,
    val target: String = "",
    val message: String = "",
    val takes: List<Clip> = emptyList(),
)

/** All phrases in one JSON file in app-private storage (D-17). */
class PhraseStore(private val file: File) {
    fun load(): List<Phrase> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val takes = o.getJSONArray("takes")
            Phrase(
                id = o.getString("id"),
                text = o.getString("text"),
                action = Action.valueOf(o.getString("action")),
                target = o.optString("target"),
                message = o.optString("message"),
                takes = (0 until takes.length()).map { t ->
                    val frames = takes.getJSONArray(t)
                    (0 until frames.length()).map { f ->
                        val a = frames.getJSONArray(f)
                        FloatArray(a.length()) { k -> a.getDouble(k).toFloat() }
                    }
                },
            )
        }
    }

    fun save(phrases: List<Phrase>) {
        val arr = JSONArray()
        phrases.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id).put("text", p.text).put("action", p.action.name)
                    .put("target", p.target).put("message", p.message)
                    .put("takes", JSONArray(p.takes.map { clip -> JSONArray(clip.map { f -> JSONArray(f.map { Math.round(it * 1e5) / 1e5 }) }) })),
            )
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file) // atomic: a crash mid-write never loses taught phrases
    }
}

/**
 * Record mode: save a clip as .npy float32 (frames, 40, 2), NaN rows where no face was found —
 * the same format as training/extract.py, so recordings go straight into training.
 */
fun writeNpy(file: File, clip: List<FloatArray?>) {
    val header = "{'descr': '<f4', 'fortran_order': False, 'shape': (${clip.size}, ${LipDots.N}, 2), }"
    val pad = 64 - (10 + header.length + 1) % 64
    val h = header + " ".repeat(pad % 64) + "\n"
    val buf = ByteBuffer.allocate(10 + h.length + clip.size * LipDots.N * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
    buf.put(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte(), 1, 0))
    buf.putShort(h.length.toShort())
    buf.put(h.toByteArray(Charsets.US_ASCII))
    clip.forEach { f -> for (k in 0 until 2 * LipDots.N) buf.putFloat(f?.get(k) ?: Float.NaN) }
    file.parentFile?.mkdirs()
    file.writeBytes(buf.array())
}
