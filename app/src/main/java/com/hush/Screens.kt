package com.hush

import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.util.UUID

@Composable
fun HushApp(state: HushState, preview: PreviewView, actions: Actions) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            when (val s = state.screen) {
                Screen.Home -> Home(state, preview, actions)
                is Screen.Confirm -> Confirm(state, s, actions)
                is Screen.Instant -> Instant(state, s.phrase, actions)
                Screen.Phrases -> PhraseList(state)
                is Screen.PhraseDetail -> PhraseDetail(state, s.id)
                is Screen.Teach -> Teach(state, preview, s.id)
                Screen.Settings -> Settings(state)
                Screen.Record -> Record(state, preview)
            }
        }
    }
}

// ---------- shared pieces: one look for every screen ----------

@Composable
private fun TopBar(title: String, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.width(4.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit) =
    IconButton(onClick, Modifier.size(48.dp)) { Icon(icon, label) }

@Composable
private fun Camera(state: HushState, preview: PreviewView, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
        // One PreviewView moves between screens; detach it from its old parent first.
        AndroidView({ (preview.parent as? ViewGroup)?.removeView(preview); preview }, Modifier.fillMaxSize())
        // Quiet when all is well (a small green dot); only speaks up when the lips are lost.
        Row(
            Modifier.padding(12.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (state.faceVisible) Color(0xFF34C759) else Amber))
            if (!state.faceVisible) Text("  Move closer", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
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
        Text(if (down) held else idle, style = MaterialTheme.typography.titleLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                down -> Color.White
                else -> MaterialTheme.colorScheme.onPrimary // same as every other main button
            })
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true) =
    Button(
        onClick, Modifier.fillMaxWidth().heightIn(min = 64.dp), enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color), shape = RoundedCornerShape(32.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }

@Composable
private fun QuietButton(text: String, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.primary) =
    TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }

@Composable
private fun Choice(text: String, onClick: () -> Unit) =
    OutlinedButton(onClick, Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 64.dp), shape = RoundedCornerShape(32.dp)) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

private fun detail(p: Phrase) = when (p.action) {
    Action.SPEAK -> "Speaks it aloud"
    Action.SMS -> "Texts ${p.target}: “${p.message.ifBlank { p.text }}”"
    Action.CALL -> "Calls ${p.target}"
    Action.EMERGENCY -> "Emergency call to ${p.target}"
    Action.TIMER -> "${p.target}-minute timer"
    Action.ALARM -> "Alarm at ${p.target}"
}

// ---------- the main loop: Home -> (Instant | Confirm) ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.Home(state: HushState, preview: PreviewView, actions: Actions) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        // Long-press the title for the hidden record mode (data collection, D-36).
        Text("Hush", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = { state.screen = Screen.Record }))
        IconAction(Icons.AutoMirrored.Filled.List, "Phrases") { state.screen = Screen.Phrases }
    }
    Camera(state, preview, Modifier.weight(1f).fillMaxWidth())
    Spacer(Modifier.height(16.dp))
    if (state.phrases.isEmpty()) Hint("Teach your first phrase from the list at the top.", Modifier.padding(bottom = 12.dp))
    HoldButton("Hold and mouth a phrase", "Listening…", state.phrases.isNotEmpty(), state::startCapture) {
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

    /** The user picked [o]: learn from the clip (D-48), then act, or ask Yes/No first if [o] leaves the phone. */
    fun pick(o: Phrase) {
        if (o.action.needsConfirm) {
            state.screen = Screen.Confirm(o, emptyList(), s.clip, sure = true)
        } else {
            state.learn(o.id, s.clip)
            actions.run(o)
            state.screen = Screen.Instant(o)
        }
    }

    if (!s.sure) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text("Did you mean…", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(16.dp))
            (listOf(p) + s.others).forEach { o -> Choice(o.text) { pick(o) } }
        }
        QuietButton("None of these", { state.screen = Screen.Home })
        return
    }

    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
        Text(p.text + "?", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Hint(detail(p))
        if (s.others.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Hint("Or did you mean")
            s.others.forEach { o -> Choice(o.text) { pick(o) } } // D-27: one tap fixes a misread
        }
    }
    PrimaryButton(
        "Yes, ${p.action.label.lowercase()}", { state.learn(p.id, s.clip); actions.run(p); state.screen = Screen.Home },
        if (p.action == Action.EMERGENCY) Emergency else MaterialTheme.colorScheme.primary,
    )
    QuietButton("No", { state.screen = Screen.Home })
}

@Composable
private fun ColumnScope.Instant(state: HushState, p: Phrase, actions: Actions) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
        Text(if (p.action == Action.SPEAK) "Speaking" else "Done", style = MaterialTheme.typography.titleMedium, color = Amber)
        Spacer(Modifier.height(8.dp))
        Text(p.text, style = MaterialTheme.typography.displaySmall)
        if (p.action != Action.SPEAK) {
            Spacer(Modifier.height(8.dp))
            Hint(detail(p))
        }
    }
    PrimaryButton(if (p.action == Action.SPEAK) "Stop" else "Undo", { actions.stop(p); state.screen = Screen.Home })
    QuietButton("Done", { state.screen = Screen.Home })
}

// ---------- managing phrases ----------

@Composable
private fun ColumnScope.PhraseList(state: HushState) {
    TopBar("Phrases", { state.screen = Screen.Home }) { IconAction(Icons.Filled.Settings, "Settings") { state.screen = Screen.Settings } }
    if (state.phrases.isEmpty()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) { Hint("No phrases yet. Teach one, then mouth it on the home screen.") }
    } else {
        LazyColumn(Modifier.weight(1f)) {
            items(state.phrases, key = { it.id }) { p ->
                Column(
                    Modifier.fillMaxWidth().clickable { state.screen = Screen.PhraseDetail(p.id) }.padding(vertical = 14.dp),
                ) {
                    Text(p.text, style = MaterialTheme.typography.titleLarge,
                        color = if (p.action == Action.EMERGENCY) Emergency else MaterialTheme.colorScheme.onSurface)
                    Text(detail(p), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    PrimaryButton("New phrase", { state.screen = Screen.Teach() })
}

@Composable
private fun ColumnScope.PhraseDetail(state: HushState, id: String) {
    val p = state.phrases.firstOrNull { it.id == id } ?: run { state.screen = Screen.Phrases; return }
    var confirmDelete by remember { mutableStateOf(false) }
    TopBar(p.text, { state.screen = Screen.Phrases })
    Column(Modifier.weight(1f).padding(top = 8.dp)) {
        Hint(detail(p))
        Spacer(Modifier.height(8.dp))
        Hint("${p.takes.size} takes. Hush adds one each time you confirm this phrase, and adding takes on another day or in other light makes it more reliable.")
    }
    PrimaryButton("Add more takes", { state.screen = Screen.Teach(p.id) })
    QuietButton(if (confirmDelete) "Tap again to delete" else "Delete phrase", {
        if (confirmDelete) { state.delete(p); state.screen = Screen.Phrases } else confirmDelete = true
    }, Emergency)
}

@Composable
private fun ColumnScope.Settings(state: HushState) {
    TopBar("Settings", { state.screen = Screen.Phrases })
    Column(Modifier.weight(1f).padding(top = 8.dp)) {
        // The one setting (D-25): the D-46 ratio. Higher = acts on its own more often.
        Text("When to ask", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Hint("How sure Hush must be before speaking or starting a timer without asking. Messages and calls always ask.")
        Spacer(Modifier.height(16.dp))
        Slider(state.ratio, { state.ratio = it }, valueRange = 0.6f..0.95f)
        Row {
            Text("Asks more", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text("Acts more", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ColumnScope.Teach(state: HushState, preview: PreviewView, existingId: String?) {
    val existing = existingId?.let { id -> state.phrases.firstOrNull { it.id == id } }
    var text by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(Action.SPEAK) }
    var target by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(existing != null) } // step 2; adding takes skips step 1
    val takes = remember { mutableStateListOf<Clip>() }
    val ctx = LocalContext.current
    val needed = if (existing != null) 1 else HushState.MIN_TAKES

    if (!recording) {
        // Step 1: what it is and what it does.
        val targetOk = when (action) {
            Action.SPEAK -> true
            Action.TIMER -> target.toIntOrNull()?.let { it > 0 } == true
            Action.ALARM -> Regex("""\d{1,2}:\d{2}""").matches(target)
            else -> target.isNotBlank()
        }
        TopBar("New phrase", { state.screen = Screen.Phrases })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Phrase, e.g. I need water") }, singleLine = true)
            Spacer(Modifier.height(20.dp))
            Text("What should it do?", style = MaterialTheme.typography.titleMedium)
            Action.entries.forEach { a ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(action == a, role = Role.RadioButton) { action = a; target = "" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(action == a, null)
                    Text("  ${a.label}", style = MaterialTheme.typography.bodyLarge,
                        color = if (a == Action.EMERGENCY) Emergency else MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(8.dp))
            when (action) {
                Action.SPEAK -> {}
                Action.TIMER -> OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), label = { Text("Minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Action.ALARM -> OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), label = { Text("Time, e.g. 07:00") }, singleLine = true)
                else -> OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), label = { Text("Phone number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
            }
            if (action == Action.SMS) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text("Message (default: the phrase)") })
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Next", { recording = true }, enabled = text.isNotBlank() && targetOk)
        return
    }

    // Step 2: mouth it a few times.
    TopBar(
        if (existing != null) "Add takes" else "Mouth it $needed times",
        { if (existing != null) state.screen = Screen.PhraseDetail(existing.id) else recording = false },
    )
    Text("“${existing?.text ?: text.trim()}”", style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(12.dp))
    Camera(state, preview, Modifier.weight(1f).fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(maxOf(needed, takes.size)) { i ->
            Box(Modifier.padding(end = 8.dp).size(14.dp).clip(CircleShape)
                .background(if (i < takes.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
        }
        Spacer(Modifier.weight(1f))
        if (takes.isNotEmpty()) TextButton({ takes.removeAt(takes.lastIndex) }, Modifier.heightIn(min = 48.dp)) { Text("Undo") }
    }
    Spacer(Modifier.height(8.dp))
    HoldButton("Hold and mouth it", "Recording…", true, state::startCapture) {
        val clip = state.stopCapture().filterNotNull()
        if (clip.size >= HushState.MIN_FRAMES) takes.add(clip)
        else Toast.makeText(ctx, "Too short, or no face seen. Try again.", Toast.LENGTH_SHORT).show()
    }
    if (takes.size >= needed) {
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Save", {
            if (existing != null) {
                state.addTakes(existing.id, takes.toList())
                state.screen = Screen.PhraseDetail(existing.id)
            } else {
                state.save(Phrase(UUID.randomUUID().toString(), text.trim(), action, target.trim(), message.trim(), takes.toList()))
                state.screen = Screen.Phrases
            }
        })
    }
}

/** Hidden data-collection mode: saves raw clips as .npy, same format as training/extract.py. */
@Composable
private fun ColumnScope.Record(state: HushState, preview: PreviewView) {
    var label by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(state.recordingsDir.listFiles()?.size ?: 0) }
    TopBar("Record mode", { state.screen = Screen.Home })
    OutlinedTextField(label, { label = it.filter { c -> c.isLetterOrDigit() || c == '_' } }, Modifier.fillMaxWidth(),
        label = { Text("person_phrase_session, e.g. ronel_p04_s1") }, singleLine = true)
    Spacer(Modifier.height(12.dp))
    Camera(state, preview, Modifier.weight(1f).fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Hint("$saved clips saved. Copy with: adb pull /sdcard/Android/data/com.hush/files/recordings data/own/")
    Spacer(Modifier.height(12.dp))
    HoldButton("Hold to record one take", "Recording…", label.isNotBlank(), state::startCapture) {
        val clip = state.stopCapture()
        if (clip.isNotEmpty()) {
            writeNpy(File(state.recordingsDir, "${label}_${System.currentTimeMillis()}.npy"), clip)
            saved++
        }
    }
}
