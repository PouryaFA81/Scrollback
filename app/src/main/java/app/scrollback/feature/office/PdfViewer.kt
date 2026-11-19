package app.scrollback.feature.office

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.chromeGlass
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** PdfRenderer wrapper: one page open at a time (the renderer isn't thread-safe), small bitmap cache. */
class PdfDoc(file: File) : AutoCloseable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(fd) } catch (t: Throwable) { fd.close(); throw t }
    private val lock = Mutex()
    private var closed = false
    val pageCount: Int = renderer.pageCount
    private val cache = object : LruCache<Int, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    data class Match(val page: Int, val rects: List<RectF>) // rects normalized to 0..1

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(index)?.takeIf { it.width >= widthPx * 0.9 }?.let { return@withContext it }
        lock.withLock {
            if (closed) return@withLock null
            renderer.openPage(index).use { page ->
                val w = widthPx.coerceIn(200, 2400)
                val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(1, 4000)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(index, bmp)
                bmp
            }
        }
    }

    val canSearch: Boolean get() = Build.VERSION.SDK_INT >= 35

    /** Text search (Android 15+ PdfRenderer text API). Scanned PDFs without a text layer return nothing. */
    suspend fun search(query: String, onProgress: (Int) -> Unit = {}): List<Match> = withContext(Dispatchers.IO) {
        if (!canSearch || query.isBlank()) return@withContext emptyList()
        val out = ArrayList<Match>()
        for (i in 0 until pageCount) {
            val m = lock.withLock {
                if (closed) return@withContext out
                renderer.openPage(i).use { page -> searchPage(page, query) }
            }
            if (m != null) out.add(m)
            onProgress(i)
        }
        out
    }

    @androidx.annotation.RequiresApi(35)
    private fun searchPage(page: PdfRenderer.Page, query: String): Match? {
        val hits = page.searchText(query)
        if (hits.isEmpty()) return null
        val w = page.width.toFloat(); val h = page.height.toFloat()
        val rects = hits.flatMap { it.bounds }.map { RectF(it.left / w, it.top / h, it.right / w, it.bottom / h) }
        return Match(page.index, rects)
    }

    override fun close() {
        kotlinx.coroutines.runBlocking { lock.withLock { closed = true; runCatching { renderer.close() }; runCatching { fd.close() } } }
        cache.evictAll()
    }
}

@Composable
fun PdfViewer(file: File, title: String, onBack: () -> Unit, actions: @Composable () -> Unit) {
    val doc by produceState<Result<PdfDoc>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { PdfDoc(file) }.recoverCatching { t ->
                throw if (t is SecurityException) IllegalStateException("This PDF is password-protected, which isn't supported yet.")
                else IllegalStateException("This PDF couldn't be opened (${t.message ?: t.javaClass.simpleName}).")
            }
        }
    }
    DisposableEffect(doc) { val d = doc?.getOrNull(); onDispose { d?.close() } }

    Box(Modifier.fillMaxSize().background(SbColors.Background)) {
        when (val r = doc) {
            null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = SbColors.TextSecondary)
            else -> r.fold(
                onSuccess = { PdfPages(it, title, onBack, actions) },
                onFailure = { DocMessage(title, it.message ?: "Couldn't open this PDF", onBack) },
            )
        }
    }
}

@Composable
private fun PdfPages(doc: PdfDoc, title: String, onBack: () -> Unit, actions: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { doc.pageCount }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var jumping by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<PdfDoc.Match>?>(null) }
    var matchIndex by remember { mutableIntStateOf(0) }
    var searchProgress by remember { mutableStateOf<Int?>(null) }
    var chrome by remember { mutableStateOf(true) }

    LaunchedEffect(pager.currentPage) { scale = 1f; offset = Offset.Zero }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() } * 2
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = scale <= 1.01f,
            beyondViewportPageCount = 1,
            key = { it },
        ) { index ->
            val bmp by produceState<Bitmap?>(null, index, widthPx) { value = doc.render(index, widthPx) }
            val isCurrent = index == pager.currentPage
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(index) {
                        detectTapGestures(
                            onTap = { chrome = !chrome },
                            onDoubleTap = { p ->
                                if (scale > 1.01f) { scale = 1f; offset = Offset.Zero }
                                else { scale = 2.5f; offset = Offset((size.width / 2f - p.x) * 1.5f, (size.height / 2f - p.y) * 1.5f) }
                            },
                        )
                    }
                    .pointerInput(index) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.size > 1 || scale > 1.01f) {
                                    val newScale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                                    val maxX = size.width * (newScale - 1) / 2f; val maxY = size.height * (newScale - 1) / 2f
                                    val o = offset + event.calculatePan()
                                    scale = newScale
                                    offset = if (newScale <= 1.01f) Offset.Zero else Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val b = bmp
                if (b == null) CircularProgressIndicator(color = SbColors.TextSecondary)
                else {
                    val pageMatches = matches?.firstOrNull { it.page == index }?.rects.orEmpty()
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)
                            .graphicsLayer {
                                if (isCurrent) { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                            },
                    ) {
                        Image(b.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        if (pageMatches.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
                            pageMatches.forEach { r ->
                                drawRect(androidx.compose.ui.graphics.Color(0x66FFD60A), Offset(r.left * size.width, r.top * size.height), Size(r.width() * size.width, r.height() * size.height))
                            }
                        }
                    }
                }
            }
        }

        // Top bar: back, title, page pill (tap to jump), search, extra actions.
        if (chrome) Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = SbSpace.m, vertical = SbSpace.s).chromeGlass(SbRadius.Pill).padding(horizontal = SbSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = SbColors.TextPrimary) }
            Text(title, Modifier.weight(1f), color = SbColors.TextPrimary, maxLines = 1, style = MaterialTheme.typography.titleSmall,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (doc.canSearch) IconButton(onClick = { searching = !searching }) { Icon(Icons.Outlined.Search, "Search", tint = SbColors.TextPrimary) }
            actions()
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).imePadding().padding(SbSpace.m),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SbSpace.s),
        ) {
            if (searching) Row(
                Modifier.fillMaxWidth().chromeGlass(SbRadius.Card).padding(horizontal = SbSpace.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    query, { query = it }, Modifier.weight(1f).padding(vertical = SbSpace.xs), singleLine = true,
                    placeholder = { Text("Find in document") },
                    supportingText = {
                        val m = matches
                        Text(when {
                            searchProgress != null -> "Searching… page ${searchProgress!! + 1} of ${doc.pageCount}"
                            m == null -> "Text search works on PDFs with a text layer"
                            m.isEmpty() -> "No matches"
                            else -> "Page ${m[matchIndex].page + 1} · ${matchIndex + 1} of ${m.size} pages with matches"
                        })
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val q = query
                        scope.launch {
                            matches = null; matchIndex = 0; searchProgress = 0
                            val m = doc.search(q) { searchProgress = it }
                            searchProgress = null; matches = m
                            m.firstOrNull()?.let { pager.animateScrollToPage(it.page) }
                        }
                    }),
                )
                val m = matches.orEmpty()
                IconButton(onClick = { if (m.isNotEmpty()) { matchIndex = (matchIndex - 1 + m.size) % m.size; scope.launch { pager.animateScrollToPage(m[matchIndex].page) } } }, enabled = m.size > 1) {
                    Icon(Icons.Outlined.KeyboardArrowUp, "Previous match", tint = SbColors.TextPrimary)
                }
                IconButton(onClick = { if (m.isNotEmpty()) { matchIndex = (matchIndex + 1) % m.size; scope.launch { pager.animateScrollToPage(m[matchIndex].page) } } }, enabled = m.size > 1) {
                    Icon(Icons.Outlined.KeyboardArrowDown, "Next match", tint = SbColors.TextPrimary)
                }
                IconButton(onClick = { searching = false; matches = null; query = "" }) { Icon(Icons.Outlined.Close, "Close search", tint = SbColors.TextPrimary) }
            }
            if (chrome || searching) GlassPill("${pager.currentPage + 1} / ${doc.pageCount}", selected = false, onClick = { jumping = true })
        }
    }

    if (jumping) {
        var text by remember { mutableStateOf("") }
        val go = { text.toIntOrNull()?.takeIf { it in 1..doc.pageCount }?.let { p -> jumping = false; scope.launch { pager.scrollToPage(p - 1) } }; Unit }
        AlertDialog(
            onDismissRequest = { jumping = false },
            containerColor = SbColors.SurfaceHigh, shape = SbRadius.Large,
            title = { Text("Go to page") },
            text = {
                OutlinedTextField(text, { text = it.filter(Char::isDigit).take(6) }, singleLine = true, label = { Text("1 – ${doc.pageCount}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }))
            },
            confirmButton = { TextButton(onClick = go) { Text("Go") } },
            dismissButton = { TextButton(onClick = { jumping = false }) { Text("Cancel", color = SbColors.TextSecondary) } },
        )
    }
}
