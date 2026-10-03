package com.hush

import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.util.UUID

@Composable
fun HushApp(state: HushState, preview: PreviewView, actions: Actions) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            when (val s = state.screen) {
                Screen.Home -> Home(state, preview, actions)
                is Screen.Confirm -> Confirm(state, s, actions)
                is Screen.Instant -> Instant(state, s.phrase, actions)
                Screen.NotSure -> NotSure(state)
                Screen.Phrases -> PhraseList(state)
                Screen.Teach -> Teach(state, preview)
                Screen.Record -> Record(state, preview)
            }
        }
    }
}

// ---------- shared pieces ----------

@Composable
private fun Camera(state: HushState, preview: PreviewView, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
        // One PreviewView moves between screens; detach it from its old parent first.
        AndroidView({ (preview.parent as? ViewGroup)?.removeView(preview); preview }, Modifier.fillMaxSize())
        Row(
            Modifier.padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (state.faceVisible) Color(0xFF34C759) else Amber))
            Text(
                if (state.faceVisible) "  Lips found" else "  No face, move closer",
                color = Color.White, style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(
            "%.0f fps".format(state.fps), color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )
    }
}

/** Push-to-talk (D-11): press starts capture, release ends the utterance (D-22). */
@Composable
private fun HoldButton(idle: String, held: String, enabled: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        down -> Amber
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(44.dp)).background(bg)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    down = true
                    onPress()
                    tryAwaitRelease()
                    down = false
                    onRelease()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (down) held else idle, color = Color.White, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true) =
    Button(
        onClick, Modifier.fillMaxWidth().heightIn(min = 64.dp), enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color), shape = RoundedCornerShape(32.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }

@Composable
private fun SecondButton(text: String, onClick: () -> Unit) =
    OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(28.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }

private fun detail(p: Phrase) = when (p.action) {
    Action.SPEAK -> "Speak aloud"
    Action.SMS -> "Text ${p.target}: “${p.message.ifBlank { p.text }}”"
    Action.CALL -> "Call ${p.target}"
    Action.EMERGENCY -> "Emergency call to ${p.target}"
    Action.TIMER -> "Timer, ${p.target} min"
    Action.ALARM -> "Alarm at ${p.target}"
}

// ---------- screens ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.Home(state: HushState, preview: PreviewView, actions: Actions) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Long-press the title for the hidden record mode (data collection, D-36).
        Text("Hush", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { state.screen = Screen.Record }))
        Spacer(Modifier.weight(1f))
        TextButton({ state.screen = Screen.Phrases }, Modifier.heightIn(min = 48.dp)) {
            Text("Phrases (${state.phrases.size})", style = MaterialTheme.typography.labelLarge)
        }
    }
    Spacer(Modifier.height(12.dp))
    Camera(state, preview, Modifier.weight(1f).fillMaxWidth())
    Spacer(Modifier.height(16.dp))
    if (state.phrases.isEmpty()) {
        Text("No phrases yet. Tap Phrases to teach your first one.", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
    }
    HoldButton("Hold and mouth a phrase", "Listening… ${state.capturedFrames}", state.phrases.isNotEmpty(), state::startCapture) {
        when (val next = state.recognise(state.stopCapture())) {
            null -> Toast.makeText(ctx, "Too short. Hold the button while you mouth.", Toast.LENGTH_SHORT).show()
            is Screen.Instant -> { actions.run(next.phrase); state.screen = next } // D-24: no confirm
            else -> state.screen = next
        }
    }
}

@Composable
private fun ColumnScope.Confirm(state: HushState, s: Screen.Confirm, actions: Actions) {
    val p = s.phrase
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
        Text(p.text + "?", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(detail(p), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.others.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text("Or did you mean", style = MaterialTheme.typography.titleMedium)
            s.others.forEach { o ->
                // D-27: one tap fixes a misread.
                OutlinedButton(
                    {
                        if (o.action.needsConfirm) state.screen = Screen.Confirm(o, listOf(p))
                        else { actions.run(o); state.screen = Screen.Instant(o) }
                    },
                    Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 56.dp), shape = RoundedCornerShape(28.dp),
                ) { Text(o.text, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
    BigButton(
        "Yes, ${p.action.label.lowercase()}", { actions.run(p); state.screen = Screen.Home },
        if (p.action == Action.EMERGENCY) Emergency else MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(12.dp))
    SecondButton("No") { state.screen = Screen.Home }
}

@Composable
private fun ColumnScope.Instant(state: HushState, p: Phrase, actions: Actions) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
        Text(if (p.action == Action.SPEAK) "Speaking" else "Done", style = MaterialTheme.typography.titleMedium, color = Amber)
        Spacer(Modifier.height(8.dp))
        Text(p.text, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(detail(p), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    BigButton(if (p.action == Action.SPEAK) "Stop" else "Undo", { actions.stop(p); state.screen = Screen.Home })
    Spacer(Modifier.height(12.dp))
    SecondButton("Done") { state.screen = Screen.Home }
}

@Composable
private fun ColumnScope.NotSure(state: HushState) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
        Text("Didn't catch that", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text("Nothing was a clear match. Try again, facing the camera.", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    BigButton("Try again", { state.screen = Screen.Home })
}

@Composable
private fun ColumnScope.PhraseList(state: HushState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Phrases", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.weight(1f))
        TextButton({ state.screen = Screen.Home }, Modifier.heightIn(min = 48.dp)) { Text("Back", style = MaterialTheme.typography.labelLarge) }
    }
    LazyColumn(Modifier.weight(1f)) {
        items(state.phrases, key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.text, style = MaterialTheme.typography.titleLarge,
                        color = if (p.action == Action.EMERGENCY) Emergency else MaterialTheme.colorScheme.onSurface)
                    Text("${detail(p)} · ${p.takes.size} takes", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ state.delete(p) }, Modifier.heightIn(min = 48.dp)) { Text("Delete") }
            }
            HorizontalDivider()
        }
    }
    // The one setting (D-25): how sure Hush must be before acting (D-46 ratio).
    Text("Sensitivity", style = MaterialTheme.typography.titleMedium)
    Slider(state.ratio, { state.ratio = it }, valueRange = 0.6f..0.95f)
    Row {
        Text("Stricter", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        Text("Accepts more", style = MaterialTheme.typography.labelMedium)
    }
    Spacer(Modifier.height(12.dp))
    BigButton("Teach a new phrase", { state.screen = Screen.Teach })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Teach(state: HushState, preview: PreviewView) {
    var text by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(Action.SPEAK) }
    var target by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val takes = remember { mutableStateListOf<Clip>() }
    val ctx = LocalContext.current
    val targetOk = when (action) {
        Action.SPEAK -> true
        Action.TIMER -> target.toIntOrNull()?.let { it > 0 } == true
        Action.ALARM -> Regex("""\d{1,2}:\d{2}""").matches(target)
        else -> target.isNotBlank()
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Teach a phrase", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.weight(1f))
        TextButton({ state.screen = Screen.Phrases }, Modifier.heightIn(min = 48.dp)) { Text("Cancel", style = MaterialTheme.typography.labelLarge) }
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Phrase, e.g. I need water") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action.entries.forEach { a -> FilterChip(action == a, { action = a; target = "" }, { Text(a.label) }, Modifier.heightIn(min = 48.dp)) }
        }
        when (action) {
            Action.SPEAK -> {}
            Action.TIMER -> OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), label = { Text("Minutes") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            Action.ALARM -> OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), label = { Text("Time, e.g. 07:00") }, singleLine = true)
            else -> OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), label = { Text("Phone number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
        }
        if (action == Action.SMS) {
            OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text("Message (default: the phrase)") })
        }
        Spacer(Modifier.height(12.dp))
        Camera(state, preview, Modifier.fillMaxWidth().height(240.dp))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Takes: ${takes.size} of ${HushState.MIN_TAKES}+", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (takes.isNotEmpty()) TextButton({ takes.removeAt(takes.lastIndex) }, Modifier.heightIn(min = 48.dp)) { Text("Undo last take") }
        }
    }
    Spacer(Modifier.height(8.dp))
    HoldButton("Hold and mouth it", "Recording… ${state.capturedFrames}", true, state::startCapture) {
        val clip = state.stopCapture().filterNotNull()
        if (clip.size >= HushState.MIN_FRAMES) takes.add(clip)
        else Toast.makeText(ctx, "Too short, or no face seen. Try again.", Toast.LENGTH_SHORT).show()
    }
    Spacer(Modifier.height(12.dp))
    BigButton("Save phrase", {
        state.save(Phrase(UUID.randomUUID().toString(), text.trim(), action, target.trim(), message.trim(), takes.toList()))
        state.screen = Screen.Phrases
    }, enabled = text.isNotBlank() && targetOk && takes.size >= HushState.MIN_TAKES)
}

/** Hidden data-collection mode: saves raw clips as .npy, same format as training/extract.py. */
@Composable
private fun ColumnScope.Record(state: HushState, preview: PreviewView) {
    var label by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(state.recordingsDir.listFiles()?.size ?: 0) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Record mode", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.weight(1f))
        TextButton({ state.screen = Screen.Home }, Modifier.heightIn(min = 48.dp)) { Text("Back", style = MaterialTheme.typography.labelLarge) }
    }
    OutlinedTextField(label, { label = it.filter { c -> c.isLetterOrDigit() || c == '_' } }, Modifier.fillMaxWidth(),
        label = { Text("person_phrase_session, e.g. ronel_p04_s1") }, singleLine = true)
    Spacer(Modifier.height(12.dp))
    Camera(state, preview, Modifier.weight(1f).fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Text("$saved clips saved. Copy with:\nadb pull /sdcard/Android/data/com.hush/files/recordings data/own/",
        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start)
    Spacer(Modifier.height(12.dp))
    HoldButton("Hold to record one take", "Recording… ${state.capturedFrames}", label.isNotBlank(), state::startCapture) {
        val clip = state.stopCapture()
        if (clip.isNotEmpty()) {
            writeNpy(File(state.recordingsDir, "${label}_${System.currentTimeMillis()}.npy"), clip)
            saved++
        }
    }
}
