package com.hush

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.File

sealed interface Screen {
    data object Home : Screen
    /** [sure] = passed the ratio rule. Not sure: show the top guesses as equal choices instead of giving up. */
    data class Confirm(val phrase: Phrase, val others: List<Phrase>, val clip: Clip, val sure: Boolean) : Screen
    data class Instant(val phrase: Phrase) : Screen
    data object Phrases : Screen
    data object Teach : Screen
    data object Record : Screen
}

/** All app state. Lip frames arrive on the camera thread; everything else runs on the main thread. */
class HushState(private val store: PhraseStore, val recordingsDir: File) {
    var screen by mutableStateOf<Screen>(Screen.Home)
    val phrases = mutableStateListOf<Phrase>().apply { addAll(store.load()) }
    var faceVisible by mutableStateOf(false)
    var fps by mutableStateOf(0f)

    /** D-46: accept the best guess only if best ÷ second-best ≤ ratio. Lower = stricter. */
    var ratio by mutableStateOf(0.8f)

    // Push-to-talk capture. null frames = no face that frame (kept for record mode, dropped for matching).
    @Volatile private var capture: MutableList<FloatArray?>? = null
    var capturedFrames by mutableStateOf(0)

    fun onFrame(dots: FloatArray?) {
        faceVisible = dots != null
        capture?.let { synchronized(it) { it.add(dots) }; capturedFrames = it.size }
    }

    fun startCapture() {
        capturedFrames = 0
        capture = mutableListOf()
    }

    /** Button released = end of utterance (D-22). */
    fun stopCapture(): List<FloatArray?> {
        val c = capture ?: return emptyList()
        capture = null
        return synchronized(c) { c.toList() }
    }

    /** Rank every taught phrase and decide what to show. */
    fun recognise(raw: List<FloatArray?>): Screen? {
        val clip = raw.filterNotNull() // ponytail: drop no-face frames instead of interpolating; a phone held close rarely loses the face
        if (clip.size < MIN_FRAMES || phrases.isEmpty()) return null
        val ranked = Dtw.rank(clip, phrases.associate { it.id to it.takes })
        val byId = phrases.associateBy { it.id }
        val accepted = Dtw.accept(ranked, ratio)
        // One line per attempt, to measure real-world precision: adb logcat -s Hush
        Log.i("Hush", "recognised ${clip.size} frames, accepted=$accepted ratio=%.2f top3=%s".format(
            if (ranked.size > 1) ranked[0].distance / ranked[1].distance else 0f,
            ranked.take(3).joinToString { "${byId.getValue(it.phraseId).text}:%.3f".format(it.distance) },
        ))
        val best = byId.getValue(ranked[0].phraseId)
        val others = ranked.drop(1).take(2).map { byId.getValue(it.phraseId) } // D-27 chips
        // D-48: unsure (e.g. "call mom" vs "call dad") -> ask, never silently drop. Only a sure instant phrase runs untouched.
        return if (accepted && !best.action.needsConfirm) Screen.Instant(best) else Screen.Confirm(best, others, clip, accepted)
    }

    /** D-48: a clip the user explicitly confirmed becomes a new take, so phrases adapt to new days, light and angles. */
    fun learn(phraseId: String, clip: Clip) {
        val p = phrases.firstOrNull { it.id == phraseId } ?: return
        Log.i("Hush", "confirmed ${p.text}") // with the line above it: live accuracy from the log
        save(p.copy(takes = (p.takes + listOf(clip)).takeLast(MAX_TAKES)))
    }

    fun save(p: Phrase) {
        phrases.removeAll { it.id == p.id }
        phrases.add(p)
        store.save(phrases)
    }

    fun delete(p: Phrase) {
        phrases.remove(p)
        store.save(phrases)
    }

    companion object {
        const val MIN_FRAMES = 4
        const val MIN_TAKES = 5
        const val MAX_TAKES = 15 // oldest takes drop out as confirmed ones come in
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var state: HushState
    private lateinit var tracker: LipTracker
    private lateinit var actions: Actions
    private lateinit var previewView: PreviewView

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) startCamera()
        else Toast.makeText(this, "Hush needs the camera to see your lips", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        state = HushState(PhraseStore(File(filesDir, "phrases.json")), File(getExternalFilesDir(null), "recordings"))
        actions = Actions(this)
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        tracker = LipTracker(this) { dots ->
            state.onFrame(dots)
            state.fps = tracker.fps
        }
        // Camera, MediaPipe and TTS all start now, not on first button press (D-21).
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startCamera()
        else permissions.launch(needed)

        setContent {
            HushTheme {
                BackHandler(state.screen != Screen.Home) { state.screen = Screen.Home }
                HushApp(state, previewView, actions)
            }
        }
    }

    private fun startCamera() = tracker.start(this, previewView.surfaceProvider)

    override fun onDestroy() {
        tracker.close()
        actions.close()
        super.onDestroy()
    }
}
