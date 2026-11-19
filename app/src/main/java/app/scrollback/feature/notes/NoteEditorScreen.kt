package app.scrollback.feature.notes

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.scrollback.files.FileNode
import app.scrollback.notes.MdEdit
import app.scrollback.notes.NoteText
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.GlassIconButton
import app.scrollback.ui.components.SegmentedToggle
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Markdown / plain-text editor with live preview and autosave. Works on local, SAF and SFTP/SMB files. */
@Composable
fun NoteEditorScreen(file: FileNode, onBack: () -> Unit) {
    val container = LocalContainer.current
    val files = container.files
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val markdown = NoteText.isMarkdown(file.name)
    var loaded by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var saved by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    val dirty = loaded != null && value.text != saved

    LaunchedEffect(file.key) {
        val t = files.readText(file)
        if (t == null) failed = "This file couldn't be opened as text (too large or unreadable)."
        else { loaded = t; saved = t; value = TextFieldValue(t); preview = markdown && t.isNotBlank() }
    }

    suspend fun save(): Boolean {
        val text = value.text
        if (text == saved) return true
        saving = true
        val ok = files.writeText(file, text)
        saving = false
        if (ok) saved = text else Toast.makeText(ctx, "Couldn't save “${file.name}”", Toast.LENGTH_LONG).show()
        return ok
    }

    // Autosave 1.2 s after typing stops, and once more when leaving.
    LaunchedEffect(value.text) { if (dirty) { delay(1200); save() } }
    val latest by rememberUpdatedState(value.text)
    DisposableEffect(Unit) {
        onDispose { if (loaded != null && latest != saved) container.appScope.launch { files.writeText(file, latest) } }
    }
    BackHandler { scope.launch { save(); onBack() } }

    fun apply(e: MdEdit.Edit) { value = TextFieldValue(e.text, TextRange(e.start, e.end)) }
    val sel = value.selection

    Column(Modifier.fillMaxSize().background(SbColors.Background).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = SbSpace.s, vertical = SbSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", { scope.launch { save(); onBack() } }, size = 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = SbSpace.m)) {
                Text(file.name, style = MaterialTheme.typography.titleMedium, color = SbColors.TextPrimary, maxLines = 1)
                Text(
                    when { saving -> "Saving…"; dirty -> "Edited"; loaded != null -> "Saved · ${NoteText.words(value.text)} words"; else -> "" },
                    style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary,
                )
            }
            if (markdown) SegmentedToggle(listOf("Edit", "Preview"), if (preview) 1 else 0, { preview = it == 1 })
            Spacer(Modifier.size(SbSpace.s))
            GlassIconButton(Icons.Outlined.Save, "Save", { scope.launch { if (save()) Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show() } }, size = 40.dp,
                tint = if (dirty) SbColors.TextPrimary else SbColors.TextTertiary)
        }
        when {
            failed != null -> EmptyState(Icons.Outlined.Title, "Can't edit this file", failed!!)
            loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = SbColors.TextSecondary) }
            preview -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                MarkdownView(value.text, Modifier.padding(horizontal = SbSpace.l, vertical = SbSpace.m).navigationBarsPadding())
            }
            else -> {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = SbColors.TextPrimary,
                        fontFamily = if (markdown) FontFamily.Default else FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(SbColors.Accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = SbSpace.l, vertical = SbSpace.m),
                    decorationBox = { inner ->
                        if (value.text.isEmpty()) Text(if (markdown) "Start writing… Markdown works: # heading, **bold**, - list, - [ ] task" else "Start writing…",
                            style = MaterialTheme.typography.bodyLarge, color = SbColors.TextTertiary)
                        inner()
                    },
                )
                if (markdown) Row(
                    Modifier.fillMaxWidth().background(SbColors.SurfaceHigh).horizontalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = SbSpace.xs),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    @Composable fun tool(icon: ImageVector, label: String, edit: () -> MdEdit.Edit) =
                        IconButton(onClick = { apply(edit()) }) { Icon(icon, label, tint = SbColors.TextPrimary) }
                    tool(Icons.Outlined.Title, "Heading") { MdEdit.prefixLines(value.text, sel.start, sel.end, "## ") }
                    tool(Icons.Outlined.FormatBold, "Bold") { MdEdit.wrap(value.text, sel.start, sel.end, "**") }
                    tool(Icons.Outlined.FormatItalic, "Italic") { MdEdit.wrap(value.text, sel.start, sel.end, "*") }
                    tool(Icons.Outlined.FormatStrikethrough, "Strikethrough") { MdEdit.wrap(value.text, sel.start, sel.end, "~~") }
                    tool(Icons.AutoMirrored.Outlined.FormatListBulleted, "List") { MdEdit.prefixLines(value.text, sel.start, sel.end, "- ") }
                    tool(Icons.Outlined.CheckBox, "Task") { MdEdit.prefixLines(value.text, sel.start, sel.end, "- [ ] ") }
                    tool(Icons.Outlined.FormatQuote, "Quote") { MdEdit.prefixLines(value.text, sel.start, sel.end, "> ") }
                    tool(Icons.Outlined.Code, "Code") { MdEdit.wrap(value.text, sel.start, sel.end, "`", "code") }
                    tool(Icons.Outlined.Link, "Link") { MdEdit.link(value.text, sel.start, sel.end) }
                }
            }
        }
    }
}
