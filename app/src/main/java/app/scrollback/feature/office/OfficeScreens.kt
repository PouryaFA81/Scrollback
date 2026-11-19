package app.scrollback.feature.office

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.feature.files.openWith
import app.scrollback.feature.library.ListRow
import app.scrollback.files.FileNode
import app.scrollback.office.Csv
import app.scrollback.office.DocBlock
import app.scrollback.office.Docx
import app.scrollback.office.OfficeFormat
import app.scrollback.office.OoxmlPackage
import app.scrollback.office.Pptx
import app.scrollback.office.Sheet
import app.scrollback.office.Xlsx
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.FloatingNavReserve
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.SectionCard
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val bottomPad @Composable get() = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingNavReserve
private val topPad @Composable get() = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp

/** Files → Tools → Office: open a document, start a CSV, recent documents. */
@Composable
fun OfficeToolScreen(onBack: () -> Unit, onOpen: (FileNode) -> Unit) {
    val container = LocalContainer.current
    val office = container.office
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    val recent by office.recent.collectAsStateWithLifecycle()

    fun openUri(uri: Uri) {
        val node = office.nodeFor(uri)
        if (OfficeFormat.detect(node.name) == null) { Toast.makeText(ctx, "“${node.name}” isn't a supported document", Toast.LENGTH_LONG).show(); return }
        office.remember(uri, node.name)
        onOpen(node)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::openUri) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        container.appScope.launch {
            office.writeTo(uri, "A,B,C\n,,\n,,\n")
            withContext(Dispatchers.Main) { openUri(uri) }
        }
    }

    GlassScreen(title = "Office", collapsed = collapsed, onBack = onBack) { mod ->
        LazyColumn(modifier = mod, state = list, contentPadding = PaddingValues(bottom = bottomPad)) {
            item { LargeTitle("Office", "PDF, Word, Excel, PowerPoint, CSV and text. Works offline.") }
            item {
                SectionCard {
                    ListRow("Open a document…", subtitle = "PDF, DOCX, XLSX, PPTX, CSV, TSV, TXT, MD",
                        leading = { Icon(Icons.Outlined.FolderOpen, null, tint = SbColors.TextPrimary) },
                        onClick = { pick.launch(OfficeFormat.MIME_TYPES + "application/octet-stream") })
                    ListRow("New spreadsheet (CSV)", subtitle = "A small table you can edit and save",
                        leading = { Icon(Icons.Outlined.Add, null, tint = SbColors.TextPrimary) },
                        onClick = { create.launch("Table.csv") })
                }
            }
            if (recent.isNotEmpty()) item {
                SectionCard(title = "Recent") {
                    recent.forEach { r ->
                        ListRow(r.name, subtitle = android.text.format.DateUtils.getRelativeTimeSpanString(r.opened).toString(),
                            leading = { Icon(iconFor(OfficeFormat.detect(r.name)), null, tint = SbColors.TextPrimary) },
                            onClick = {
                                val uri = Uri.parse(r.uri)
                                val readable = runCatching { ctx.contentResolver.openInputStream(uri)?.close(); true }.getOrDefault(false)
                                if (readable) openUri(uri) else { office.forget(r.uri); Toast.makeText(ctx, "“${r.name}” is no longer available", Toast.LENGTH_SHORT).show() }
                            })
                    }
                }
            }
            item {
                SectionCard(title = "Also") {
                    ListRow("Tap a document in Files", subtitle = "Opens here; use the open-in icon for another app",
                        leading = { Icon(Icons.Outlined.TouchApp, null, tint = SbColors.TextSecondary) })
                    ListRow("Open with Scrollback", subtitle = "Scrollback shows up in other apps' “Open with” for these files",
                        leading = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = SbColors.TextSecondary) })
                    ListRow("Viewing only for Word, Excel and PowerPoint", subtitle = "Text, headings, lists, tables, sheets and slide images. Layout, fonts and formulas aren't rendered. CSV and text files can be edited.",
                        leading = { Icon(Icons.Outlined.Description, null, tint = SbColors.TextSecondary) })
                }
            }
        }
    }
}

fun iconFor(f: OfficeFormat?) = when (f) {
    OfficeFormat.PDF -> Icons.Outlined.PictureAsPdf
    OfficeFormat.XLSX -> Icons.Outlined.TableChart
    OfficeFormat.CSV, OfficeFormat.TSV -> Icons.Outlined.GridOn
    OfficeFormat.PPTX -> Icons.Outlined.Slideshow
    OfficeFormat.TEXT -> Icons.AutoMirrored.Outlined.Notes
    else -> Icons.Outlined.Description
}

/** Opens any supported document. Text files go to the notes editor. */
@Composable
fun DocumentScreen(node: FileNode, onBack: () -> Unit) {
    val format = OfficeFormat.detect(node.name) ?: OfficeFormat.fromMime(node.mime)
    if (format == OfficeFormat.TEXT) { app.scrollback.feature.notes.NoteEditorScreen(node, onBack); return }
    if (format == OfficeFormat.CSV || format == OfficeFormat.TSV) { CsvEditorScreen(node, format == OfficeFormat.TSV, onBack); return }
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val local by produceState<Result<File>?>(null, node.key) { value = runCatching { container.office.localFile(node) } }
    val openElsewhere: @Composable () -> Unit = {
        IconButton(onClick = { local?.getOrNull()?.let { f -> openWith(ctx, if (node.isRemote) FileNode.raw(f).copy(mime = node.mime) else node) } }) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open in another app", tint = SbColors.TextPrimary)
        }
    }
    when (val r = local) {
        null -> Box(Modifier.fillMaxSize().background(SbColors.Background), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = SbColors.TextSecondary) }
        else -> r.fold(
            onFailure = { DocMessage(node.name, it.message ?: "Couldn't open this file", onBack) },
            onSuccess = { file ->
                when (format) {
                    OfficeFormat.PDF -> PdfViewer(file, node.name, onBack, openElsewhere)
                    OfficeFormat.DOCX -> DocxScreen(file, node.name, onBack, openElsewhere)
                    OfficeFormat.XLSX -> XlsxScreen(file, node.name, onBack, openElsewhere)
                    OfficeFormat.PPTX -> PptxScreen(file, node.name, onBack, openElsewhere)
                    else -> DocMessage(node.name, "This file type isn't supported", onBack)
                }
            },
        )
    }
}

@Composable
fun DocMessage(title: String, message: String, onBack: () -> Unit) {
    GlassScreen(title = title, collapsed = false, onBack = onBack) { mod ->
        Column(mod.padding(top = topPad)) {
            LargeTitle(title)
            SectionCard { ListRow(message, leading = { Icon(Icons.Outlined.Description, null, tint = SbColors.TextSecondary) }) }
        }
    }
}

/** Parses an OOXML file off the main thread; shows errors in place. */
@Composable
private fun <T> rememberParsed(file: File, parse: (OoxmlPackage) -> T): Result<T>? =
    produceState<Result<T>?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { OoxmlPackage(file).use(parse) }.recoverCatching { t ->
            throw IllegalStateException(if (t is java.util.zip.ZipException) "This file is damaged or isn't an Office Open XML file (old .doc/.xls/.ppt aren't supported)." else t.message ?: "Couldn't read this file")
        } }
    }.value

@Composable
private fun DocxScreen(file: File, name: String, onBack: () -> Unit, actions: @Composable () -> Unit) {
    val doc = rememberParsed(file) { Docx.parse(it) }
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    doc?.exceptionOrNull()?.let { DocMessage(name, it.message.orEmpty(), onBack); return }
    GlassScreen(title = name, collapsed = collapsed, onBack = onBack, actions = { actions() }) { mod ->
        val blocks = doc?.getOrNull()?.blocks
        SelectionContainer(mod) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = SbSpace.screenH, end = SbSpace.screenH, top = topPad, bottom = bottomPad)) {
                if (blocks == null) item { CircularProgressIndicator(color = SbColors.TextSecondary) }
                else if (blocks.isEmpty()) item { Text("This document has no text.", color = SbColors.TextSecondary) }
                else {
                    // Numbering per list level; resets when a non-list block interrupts.
                    val counters = IntArray(10)
                    val numbers = blocks.map { b ->
                        val li = (b as? DocBlock.Paragraph)?.list
                        if (li == null) { counters.fill(0); 0 } else {
                            val l = li.level.coerceIn(0, 9); counters[l]++; for (i in l + 1 until 10) counters[i] = 0; counters[l]
                        }
                    }
                    itemsIndexed(blocks) { i, b -> DocBlockView(b, numbers[i]) }
                }
            }
        }
    }
}

@Composable
private fun DocBlockView(b: DocBlock, number: Int) {
    when (b) {
        is DocBlock.Paragraph -> {
            val style = when (b.heading) {
                1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                3 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                in 4..6 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                else -> MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
            }
            val text = buildAnnotatedString {
                b.runs.forEach { r ->
                    withStyle(SpanStyle(fontWeight = if (r.bold) FontWeight.Bold else null, fontStyle = if (r.italic) FontStyle.Italic else null)) { append(r.text) }
                }
            }
            val top = if (b.heading > 0) SbSpace.l else if (b.list != null) 2.dp else SbSpace.s
            if (b.list != null) Row(Modifier.padding(start = (b.list.level * 20).dp, top = top)) {
                Text(if (b.list.ordered) "$number." else if (b.list.level % 2 == 0) "•" else "◦", Modifier.width(26.dp), color = SbColors.TextSecondary, style = style)
                Text(text, color = SbColors.TextPrimary, style = style)
            } else Text(text, Modifier.padding(top = top), color = SbColors.TextPrimary, style = style)
        }
        is DocBlock.Table -> Box(Modifier.padding(vertical = SbSpace.s).horizontalScroll(rememberScrollState())) {
            Column(Modifier.border(1.dp, SbColors.Divider, SbRadius.Small)) {
                b.rows.forEachIndexed { ri, row ->
                    Row(Modifier.background(if (ri == 0) SbColors.SurfaceHigh else SbColors.Surface)) {
                        row.forEach { cell ->
                            Text(cell, Modifier.width(140.dp).border(0.5.dp, SbColors.Divider).padding(SbSpace.s), color = SbColors.TextPrimary,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun XlsxScreen(file: File, name: String, onBack: () -> Unit, actions: @Composable () -> Unit) {
    val wb = rememberParsed(file) { Xlsx.parse(it) }
    wb?.exceptionOrNull()?.let { DocMessage(name, it.message.orEmpty(), onBack); return }
    var sheetIndex by remember { mutableIntStateOf(0) }
    GlassScreen(title = name, collapsed = true, onBack = onBack, actions = { actions() }) { mod ->
        val sheets = wb?.getOrNull()?.sheets
        Column(mod.padding(top = topPad)) {
            if (sheets == null) CircularProgressIndicator(Modifier.padding(SbSpace.xl), color = SbColors.TextSecondary)
            else if (sheets.isEmpty()) Text("This workbook has no sheets.", Modifier.padding(SbSpace.screenH), color = SbColors.TextSecondary)
            else {
                if (sheets.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = SbSpace.m, vertical = SbSpace.s), horizontalArrangement = Arrangement.spacedBy(SbSpace.s)) {
                    sheets.forEachIndexed { i, s -> GlassPill(s.name, selected = i == sheetIndex, onClick = { sheetIndex = i }) }
                }
                val sheet = sheets[sheetIndex.coerceIn(0, sheets.lastIndex)]
                if (sheet.truncated) Text("Showing the first ${Xlsx.MAX_ROWS} rows and ${Xlsx.MAX_COLS} columns.", Modifier.padding(horizontal = SbSpace.screenH), color = SbColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (sheet.rows.isEmpty()) Text("This sheet is empty.", Modifier.padding(SbSpace.screenH), color = SbColors.TextSecondary)
                else TableGrid(sheet.rows, sheet.columns, key = sheet.name)
            }
        }
    }
}

private fun columnName(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }; return sb.reverse().toString() }

/** Spreadsheet-style grid: column letters, row numbers, horizontal + vertical scroll. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableGrid(
    rows: List<List<String>>, columns: Int, key: Any,
    onCellClick: ((Int, Int) -> Unit)? = null, onRowLongClick: ((Int) -> Unit)? = null,
) {
    val cellW = 120.dp; val numW = 44.dp
    val h = rememberScrollState()
    Box(Modifier.fillMaxSize().horizontalScroll(h)) {
        LazyColumn(Modifier.width(numW + cellW * columns.coerceAtLeast(1)), contentPadding = PaddingValues(bottom = bottomPad)) {
            item(key = "header-$key") {
                Row(Modifier.background(SbColors.SurfaceHigh)) {
                    Spacer(Modifier.width(numW))
                    repeat(columns) { c -> Text(columnName(c), Modifier.width(cellW).padding(vertical = 6.dp), color = SbColors.TextSecondary, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
            }
            itemsIndexed(rows) { r, row ->
                Row(Modifier.heightIn(min = 36.dp)) {
                    Text("${r + 1}", Modifier.width(numW).background(SbColors.SurfaceHigh)
                        .then(if (onRowLongClick != null) Modifier.combinedClickable(onClick = {}, onLongClick = { onRowLongClick(r) }) else Modifier)
                        .padding(vertical = 8.dp), color = SbColors.TextSecondary, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    repeat(columns) { c ->
                        val v = row.getOrNull(c).orEmpty()
                        Text(v, Modifier.width(cellW).border(0.5.dp, SbColors.Divider)
                            .then(if (onCellClick != null) Modifier.combinedClickable(onClick = { onCellClick(r, c) }, onLongClick = onRowLongClick?.let { { it(r) } }) else Modifier)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                            color = SbColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textAlign = if (v.toDoubleOrNull() != null) androidx.compose.ui.text.style.TextAlign.End else null)
                    }
                }
            }
        }
    }
}

@Composable
private fun PptxScreen(file: File, name: String, onBack: () -> Unit, actions: @Composable () -> Unit) {
    val pres = rememberParsed(file) { Pptx.parse(it) }
    pres?.exceptionOrNull()?.let { DocMessage(name, it.message.orEmpty(), onBack); return }
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    GlassScreen(title = name, collapsed = collapsed, onBack = onBack, actions = { actions() }) { mod ->
        val slides = pres?.getOrNull()?.slides
        LazyColumn(mod, state = list, contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
            if (slides == null) item { CircularProgressIndicator(Modifier.padding(SbSpace.xl), color = SbColors.TextSecondary) }
            else if (slides.isEmpty()) item { Text("This presentation has no slides.", Modifier.padding(SbSpace.screenH), color = SbColors.TextSecondary) }
            else items(slides, key = { it.index }) { s ->
                SectionCard(title = "Slide ${s.index} of ${slides.size}") {
                    Column(Modifier.fillMaxWidth().padding(SbSpace.l), verticalArrangement = Arrangement.spacedBy(SbSpace.s)) {
                        s.title?.let { Text(it, color = SbColors.TextPrimary, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)) }
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                s.paragraphs.forEach { Text(it, color = SbColors.TextPrimary, style = MaterialTheme.typography.bodyLarge) }
                            }
                        }
                        s.images.forEach { path -> SlideImage(file, path) }
                        s.notes?.let { Text("Notes: $it", color = SbColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlideImage(file: File, path: String) {
    val bmp by produceState<android.graphics.Bitmap?>(null, file, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = OoxmlPackage(file).use { it.bytes(path) } ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1; while (bounds.outWidth / sample > 1600) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 360.dp).background(SbColors.SurfaceHigh, SbRadius.Small), contentScale = ContentScale.Fit) }
}

/** CSV/TSV viewer and editor: tap a cell to edit, long-press a row for row actions, Save or Save as. */
@Composable
private fun CsvEditorScreen(node: FileNode, tsv: Boolean, onBack: () -> Unit) {
    val container = LocalContainer.current
    val office = container.office
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var delimiter by remember { mutableStateOf(if (tsv) '\t' else ',') }
    var rows by remember { mutableStateOf<List<List<String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var rowMenu by remember { mutableStateOf<Int?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(node.key) {
        runCatching { office.readText(node) }
            .onSuccess { t -> if (!tsv) delimiter = Csv.sniff(t); rows = Csv.rectangular(Csv.parse(t, delimiter)).ifEmpty { listOf(mutableListOf("")) } }
            .onFailure { error = it.message ?: "Couldn't read this file" }
    }
    error?.let { DocMessage(node.name, it, onBack); return }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if (tsv) "text/tab-separated-values" else "text/csv")) { uri ->
        val r = rows ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            if (office.writeTo(uri, Csv.write(r, delimiter))) { dirty = false; Toast.makeText(ctx, "Saved a copy", Toast.LENGTH_SHORT).show() }
            else Toast.makeText(ctx, "Couldn't save there", Toast.LENGTH_LONG).show()
        }
    }
    fun save(then: () -> Unit = {}) {
        val r = rows ?: return
        scope.launch {
            if (office.writeText(node, Csv.write(r, delimiter))) { dirty = false; Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show(); then() }
            else { Toast.makeText(ctx, "This location is read-only. Choose where to save a copy.", Toast.LENGTH_LONG).show(); saveAs.launch(node.name) }
        }
    }
    fun update(f: (MutableList<MutableList<String>>) -> Unit) {
        val copy = rows.orEmpty().map { it.toMutableList() }.toMutableList(); f(copy)
        val w = copy.maxOfOrNull { it.size } ?: 0
        rows = copy.map { r -> r + List(w - r.size) { "" } }; dirty = true
    }

    BackHandler(enabled = dirty) { confirmLeave = true }
    val cols = rows?.maxOfOrNull { it.size } ?: 0
    GlassScreen(title = (if (dirty) "• " else "") + node.name, collapsed = true, onBack = { if (dirty) confirmLeave = true else onBack() }, actions = {
        IconButton(onClick = { save() }, enabled = dirty) { Icon(Icons.Outlined.Save, "Save", tint = if (dirty) SbColors.TextPrimary else SbColors.TextTertiary) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.Add, "Add", tint = SbColors.TextPrimary) }
            DropdownMenu(menu, { menu = false }, containerColor = SbColors.SurfaceHigh, shape = SbRadius.Card) {
                DropdownMenuItem(text = { Text("Add row") }, onClick = { menu = false; update { it.add(MutableList(cols.coerceAtLeast(1)) { "" }) } })
                DropdownMenuItem(text = { Text("Add column") }, onClick = { menu = false; update { rs -> if (rs.isEmpty()) rs.add(mutableListOf()); rs.forEach { it.add("") } } })
                DropdownMenuItem(text = { Text("Delete last column") }, enabled = cols > 1, onClick = { menu = false; update { rs -> rs.forEach { if (it.isNotEmpty()) it.removeAt(it.lastIndex) } } })
                DropdownMenuItem(text = { Text("Save as…") }, onClick = { menu = false; saveAs.launch(node.name) })
            }
        }
    }) { mod ->
        Column(mod.padding(top = topPad)) {
            val r = rows
            if (r == null) CircularProgressIndicator(Modifier.padding(SbSpace.xl), color = SbColors.TextSecondary)
            else {
                Text("Tap a cell to edit · long-press a row for more · ${r.size} rows × $cols columns · " +
                    when (delimiter) { '\t' -> "tab"; ';' -> "semicolon"; else -> "comma" } + " separated",
                    Modifier.padding(horizontal = SbSpace.screenH, vertical = SbSpace.xs), color = SbColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                TableGrid(r, cols, key = "csv", onCellClick = { ri, ci -> editing = ri to ci }, onRowLongClick = { rowMenu = it })
            }
        }
    }

    editing?.let { (ri, ci) ->
        var text by remember(ri, ci) { mutableStateOf(rows?.getOrNull(ri)?.getOrNull(ci).orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null }, containerColor = SbColors.SurfaceHigh, shape = SbRadius.Large,
            title = { Text("${columnName(ci)}${ri + 1}") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 1, maxLines = 6) },
            confirmButton = { TextButton(onClick = { editing = null; if (text != rows?.getOrNull(ri)?.getOrNull(ci)) update { it[ri][ci] = text } }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", color = SbColors.TextSecondary) } },
        )
    }
    rowMenu?.let { ri ->
        AlertDialog(
            onDismissRequest = { rowMenu = null }, containerColor = SbColors.SurfaceHigh, shape = SbRadius.Large,
            title = { Text("Row ${ri + 1}") },
            text = {
                Column {
                    ListRow("Insert row above", onClick = { rowMenu = null; update { it.add(ri, MutableList(cols) { "" }) } })
                    ListRow("Insert row below", onClick = { rowMenu = null; update { it.add(ri + 1, MutableList(cols) { "" }) } })
                    ListRow("Delete row", onClick = { rowMenu = null; update { if (it.size > 1) it.removeAt(ri) else it[0].replaceAll { "" } } })
                }
            },
            confirmButton = { TextButton(onClick = { rowMenu = null }) { Text("Close") } },
        )
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false }, containerColor = SbColors.SurfaceHigh, shape = SbRadius.Large,
        title = { Text("Save changes?") },
        text = { Text("“${node.name}” has unsaved changes.") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; save(onBack) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false; dirty = false; onBack() }) { Text("Discard", color = SbColors.Danger) } },
    )
}
