package app.scrollback.feature.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.scrollback.feature.files.FileRoute
import app.scrollback.feature.library.ListRow
import app.scrollback.files.FileNode
import app.scrollback.files.FileRepository
import app.scrollback.notes.NoteText
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.LocalHazeState
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.FloatingNavReserve
import app.scrollback.ui.components.GlassIconButton
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.SectionCard
import app.scrollback.ui.components.TextInputDialog
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.launch
import java.io.File

/** Where notes live: a folder the user picked (e.g. an Obsidian vault) or the app's own Notes folder. */
object NotesFolder {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notes", Context.MODE_PRIVATE)
    fun appFolder(ctx: Context): FileNode = FileNode.raw(File(ctx.getExternalFilesDir(null), "Notes").apply { mkdirs() }).copy(name = "Scrollback notes")
    fun isCustom(ctx: Context) = prefs(ctx).getString("root", null) != null
    fun get(ctx: Context, files: FileRepository): FileNode {
        val raw = prefs(ctx).getString("root", null) ?: return appFolder(ctx)
        val node = FileRoute.decode(raw) ?: return appFolder(ctx)
        // A revoked SAF grant falls back to the app folder.
        if (node.kind == app.scrollback.files.NodeKind.SAF && files.grantedTrees().none { it.treeUri == node.treeUri }) return appFolder(ctx)
        return node
    }
    fun set(ctx: Context, node: FileNode?) {
        prefs(ctx).edit().apply { if (node == null) remove("root") else putString("root", Uri.decode(FileRoute.encode(node))) }.apply()
    }
}

@Composable
fun NotesScreen(onBack: () -> Unit, onOpen: (FileNode) -> Unit) {
    val container = LocalContainer.current
    val files = container.files
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var root by remember { mutableStateOf(NotesFolder.get(ctx, files)) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload++ }

    val notes by produceState<List<Pair<FileNode, String>>?>(null, root.key, reload) { value = files.listNotes(root) }
    val shown = notes.orEmpty().filter { query.isBlank() || it.first.name.contains(query.trim(), ignoreCase = true) || it.second.contains(query.trim(), true) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            val node = files.grantedTrees().firstOrNull { it.treeUri == uri.toString() }
            if (node != null) { NotesFolder.set(ctx, node); root = node } else Toast.makeText(ctx, "Couldn't use that folder", Toast.LENGTH_SHORT).show()
        }
    }

    GlassScreen(
        title = "Notes", collapsed = collapsed, onBack = onBack,
        actions = {
            val haze = LocalHazeState.current
            GlassIconButton(Icons.Outlined.Search, "Search", { searching = !searching; query = "" }, size = 40.dp, hazeState = haze)
            GlassIconButton(Icons.Outlined.Add, "New note", { creating = true }, size = 40.dp, hazeState = haze)
        },
    ) { mod ->
        LazyColumn(
            modifier = mod, state = list,
            contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingNavReserve),
        ) {
            item { LargeTitle("Notes", "Markdown and text files in ${root.name}") }
            if (searching) item {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, shape = SbRadius.Pill, placeholder = { Text("Search notes") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = SbSpace.l, vertical = SbSpace.s),
                )
            }
            item {
                SectionCard {
                    ListRow(
                        title = if (NotesFolder.isCustom(ctx)) "Notes folder: ${root.name}" else "Choose a notes folder",
                        subtitle = if (NotesFolder.isCustom(ctx)) "Tap to pick another folder, e.g. your Obsidian vault"
                        else "Notes are in the app's own folder now and are removed if you uninstall. Pick a folder (an Obsidian vault works) to keep them.",
                        leading = { Icon(Icons.Outlined.FolderOpen, null, tint = SbColors.TextPrimary) },
                        onClick = { pickFolder.launch(null) },
                    )
                }
            }
            when {
                notes == null -> Unit
                shown.isEmpty() -> item {
                    EmptyState(Icons.AutoMirrored.Outlined.Notes, if (query.isBlank()) "No notes yet" else "No matches",
                        if (query.isBlank()) "Tap + to write one. .md and .txt files anywhere in Files open here too." else "Try another word.")
                }
                else -> item {
                    SectionCard(title = "${shown.size} note${if (shown.size == 1) "" else "s"}") {
                        shown.forEach { (n, folder) ->
                            ListRow(
                                title = n.name.substringBeforeLast('.'),
                                subtitle = listOfNotNull(
                                    folder.ifEmpty { null },
                                    if (n.modified > 0) DateUtils.getRelativeTimeSpanString(n.modified).toString() else null,
                                    n.extension,
                                ).joinToString(" · "),
                                leading = { Icon(Icons.Outlined.Description, null, tint = SbColors.TextSecondary) },
                                onClick = { onOpen(n) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        TextInputDialog("New note", "Untitled", "Create", onConfirm = { title ->
            scope.launch {
                val name = NoteText.fileName(title)
                val heading = if (NoteText.isMarkdown(name)) "# ${name.substringBeforeLast('.')}\n\n" else ""
                val node = files.createTextFile(root, name, heading)
                if (node != null) onOpen(node.copy(isDir = false)) else Toast.makeText(ctx, "Couldn't create the note", Toast.LENGTH_SHORT).show()
            }
        }, onDismiss = { creating = false })
    }
}
