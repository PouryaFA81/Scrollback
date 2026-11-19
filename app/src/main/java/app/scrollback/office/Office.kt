package app.scrollback.office

import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Compact office suite (v0.10). No Apache POI: OOXML files are zips of XML, so a pull parser over the
 * few parts we need (document, sheets, slides, shared strings, relationships) keeps the APK small.
 */
enum class OfficeFormat(val label: String) {
    PDF("PDF"), DOCX("Word document"), XLSX("Spreadsheet"), PPTX("Presentation"), CSV("CSV table"), TSV("TSV table"), TEXT("Text");

    companion object {
        fun detect(name: String): OfficeFormat? = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> PDF
            "docx", "docm", "dotx" -> DOCX
            "xlsx", "xlsm", "xltx" -> XLSX
            "pptx", "pptm", "ppsx", "potx" -> PPTX
            "csv" -> CSV
            "tsv", "tab" -> TSV
            "txt", "text", "log", "md", "markdown" -> TEXT
            else -> null
        }

        fun fromMime(mime: String?): OfficeFormat? = when (mime?.lowercase()?.substringBefore(';')?.trim()) {
            "application/pdf" -> PDF
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DOCX
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> XLSX
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> PPTX
            "text/csv", "text/comma-separated-values", "application/csv" -> CSV
            "text/tab-separated-values" -> TSV
            "text/plain", "text/markdown", "text/x-markdown" -> TEXT
            else -> null
        }

        /** MIME types offered in the picker and registered for "Open with". */
        val MIME_TYPES = arrayOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/csv", "text/comma-separated-values", "text/tab-separated-values", "text/plain", "text/markdown",
        )
    }
}

/** Pull-parser source. Android supplies one; JVM unit tests swap in kXML2. */
object Xml {
    @Volatile var factory: () -> XmlPullParser = { android.util.Xml.newPullParser() }

    fun parser(input: InputStream): XmlPullParser = factory().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, null)
    }
}

/** "w:p" -> "p". Prefixes are conventional in OOXML but not guaranteed, so we match local names. */
internal val XmlPullParser.local: String get() = name.substringAfter(':')

internal fun XmlPullParser.attr(localName: String): String? {
    for (i in 0 until attributeCount) if (getAttributeName(i).substringAfter(':') == localName) return getAttributeValue(i)
    return null
}

/** The relationship id (r:id), not a plain "id" attribute such as sldId's numeric id. */
internal fun XmlPullParser.relId(): String? {
    for (i in 0 until attributeCount) {
        val n = getAttributeName(i)
        if (n.contains(':') && n.substringAfter(':') == "id") return getAttributeValue(i)
    }
    return null
}

/** Read-only view of a zip package with limits so a hostile file can't exhaust memory. */
class OoxmlPackage(private val zip: ZipFile) : AutoCloseable {
    constructor(file: File) : this(ZipFile(file))

    fun has(path: String) = zip.getEntry(path) != null

    fun open(path: String): InputStream? = zip.getEntry(path)?.let { e ->
        if (e.size > MAX_PART) throw IOException("Part too large: $path")
        zip.getInputStream(e)
    }

    fun bytes(path: String, max: Long = MAX_MEDIA): ByteArray? = zip.getEntry(path)?.let { e ->
        if (e.size > max) return null
        zip.getInputStream(e).use { it.readNBytes(max.toInt() + 1) }.takeIf { it.size <= max }
    }

    /** Relationship id -> absolute part path, for the part at [partPath]. */
    fun rels(partPath: String): Map<String, String> {
        val dir = partPath.substringBeforeLast('/', "")
        val relPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + partPath.substringAfterLast('/') + ".rels"
        val out = HashMap<String, String>()
        open(relPath)?.use { s ->
            val p = Xml.parser(s)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.local == "Relationship" && p.attr("TargetMode") != "External") {
                    val id = p.attr("Id") ?: continue
                    val target = p.attr("Target") ?: continue
                    out[id] = resolve(dir, target)
                }
            }
        }
        return out
    }

    override fun close() = zip.close()

    companion object {
        const val MAX_PART = 64L * 1024 * 1024
        const val MAX_MEDIA = 16L * 1024 * 1024

        /** Resolves "../media/image1.png" relative to "ppt/slides" -> "ppt/media/image1.png". */
        fun resolve(baseDir: String, target: String): String {
            if (target.startsWith("/")) return target.trimStart('/')
            val parts = ArrayDeque(if (baseDir.isEmpty()) emptyList() else baseDir.split('/'))
            for (seg in target.split('/')) when (seg) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(seg)
            }
            return parts.joinToString("/")
        }
    }
}

// ---------------------------------------------------------------- DOCX

data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false)

sealed interface DocBlock {
    /** [heading] 0 = body text, 1..6 = heading level. [list] null = not a list item. */
    data class Paragraph(val runs: List<Run>, val heading: Int = 0, val list: ListItem? = null) : DocBlock {
        val text: String get() = runs.joinToString("") { it.text }
    }
    data class Table(val rows: List<List<String>>) : DocBlock
}

data class ListItem(val level: Int, val ordered: Boolean)

data class DocxDocument(val blocks: List<DocBlock>)

object Docx {
    fun parse(pkg: OoxmlPackage): DocxDocument {
        val main = "word/document.xml"
        if (!pkg.has(main)) throw IOException("Not a Word document (word/document.xml missing)")
        val numbering = numbering(pkg)
        val styles = headingStyles(pkg)
        val blocks = ArrayList<DocBlock>()
        pkg.open(main)!!.use { s ->
            val p = Xml.parser(s)
            var tableDepth = 0
            // Paragraph state
            var runs = ArrayList<Run>(); var heading = 0; var numId: String? = null; var ilvl = 0
            var bold = false; var italic = false; var inRun = false; var inRunProps = false; var inParaProps = false
            val text = StringBuilder()
            // Table state (outermost table only; nested tables are flattened into cell text)
            var rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val cell = StringBuilder()
            var cellParas = 0

            fun flushRun() { if (text.isNotEmpty()) { runs.add(Run(text.toString(), bold, italic)); text.clear() } }

            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.local) {
                        "tbl" -> { if (tableDepth == 0) rows = ArrayList(); tableDepth++ }
                        "tr" -> if (tableDepth == 1) row = ArrayList()
                        "tc" -> if (tableDepth == 1) { cell.clear(); cellParas = 0 }
                        "p" -> { runs = ArrayList(); heading = 0; numId = null; ilvl = 0 }
                        "pPr" -> inParaProps = true
                        "pStyle" -> if (inParaProps) heading = styles[p.attr("val")] ?: headingLevel(p.attr("val"))
                        "outlineLvl" -> if (inParaProps && heading == 0) heading = ((p.attr("val")?.toIntOrNull() ?: 9) + 1).takeIf { it in 1..6 } ?: 0
                        "numId" -> if (inParaProps) numId = p.attr("val")
                        "ilvl" -> if (inParaProps) ilvl = p.attr("val")?.toIntOrNull() ?: 0
                        "r" -> { inRun = true; bold = false; italic = false }
                        "rPr" -> if (inRun) inRunProps = true
                        "b" -> if (inRunProps) bold = p.attr("val").let { it == null || it == "1" || it == "true" }
                        "i" -> if (inRunProps) italic = p.attr("val").let { it == null || it == "1" || it == "true" }
                        "t" -> if (inRun) text.append(p.nextText())
                        "tab" -> if (inRun) text.append('\t')
                        "br", "cr" -> if (inRun) text.append('\n')
                    }
                    XmlPullParser.END_TAG -> when (p.local) {
                        "rPr" -> inRunProps = false
                        "pPr" -> inParaProps = false
                        "r" -> { flushRun(); inRun = false }
                        "p" -> {
                            flushRun()
                            if (tableDepth > 0) {
                                val t = runs.joinToString("") { it.text }
                                if (cellParas++ > 0) cell.append('\n')
                                cell.append(t)
                            } else {
                                val list = numId?.takeIf { it != "0" }?.let { ListItem(ilvl, numbering[it to ilvl] ?: numbering[it to 0] ?: false) }
                                blocks.add(DocBlock.Paragraph(runs, heading, list))
                            }
                        }
                        "tc" -> if (tableDepth == 1) row.add(cell.toString())
                        "tr" -> if (tableDepth == 1) rows.add(row)
                        "tbl" -> { tableDepth--; if (tableDepth == 0) blocks.add(DocBlock.Table(rows)) }
                    }
                }
            }
        }
        // Drop trailing/duplicate empty paragraphs, keep single blank lines.
        val cleaned = ArrayList<DocBlock>()
        for (b in blocks) {
            val empty = b is DocBlock.Paragraph && b.text.isBlank() && b.list == null
            if (empty && (cleaned.isEmpty() || (cleaned.last() as? DocBlock.Paragraph)?.text?.isBlank() == true)) continue
            cleaned.add(b)
        }
        while ((cleaned.lastOrNull() as? DocBlock.Paragraph)?.text?.isBlank() == true) cleaned.removeAt(cleaned.lastIndex)
        return DocxDocument(cleaned)
    }

    /** "Heading2" / "heading 2" / "Title" -> level. */
    fun headingLevel(style: String?): Int {
        if (style == null) return 0
        val s = style.lowercase().replace(" ", "")
        if (s == "title") return 1
        if (s.startsWith("heading")) return s.removePrefix("heading").toIntOrNull()?.takeIf { it in 1..6 } ?: 0
        return 0
    }

    /** styleId -> heading level, from styles.xml names (covers localized ids like "berschrift1"). */
    private fun headingStyles(pkg: OoxmlPackage): Map<String, Int> {
        val out = HashMap<String, Int>()
        pkg.open("word/styles.xml")?.use { s ->
            val p = Xml.parser(s)
            var id: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.local) {
                    "style" -> id = p.attr("styleId")
                    "name" -> { val lvl = headingLevel(p.attr("val")); if (id != null && lvl > 0) out[id!!] = lvl }
                }
            }
        }
        return out
    }

    /** (numId, level) -> ordered? from numbering.xml. */
    private fun numbering(pkg: OoxmlPackage): Map<Pair<String, Int>, Boolean> {
        val abstract = HashMap<String, MutableMap<Int, Boolean>>()
        val nums = HashMap<String, String>()
        pkg.open("word/numbering.xml")?.use { s ->
            val p = Xml.parser(s)
            var absId: String? = null; var lvl = 0; var numId: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.local) {
                    "abstractNum" -> { absId = p.attr("abstractNumId"); numId = null }
                    "lvl" -> lvl = p.attr("ilvl")?.toIntOrNull() ?: 0
                    "numFmt" -> absId?.let { a -> if (numId == null) abstract.getOrPut(a) { HashMap() }[lvl] = p.attr("val").let { it != null && it != "bullet" && it != "none" } }
                    "num" -> { numId = p.attr("numId"); absId = null }
                    "abstractNumId" -> numId?.let { n -> p.attr("val")?.let { nums[n] = it } }
                }
            }
        }
        val out = HashMap<Pair<String, Int>, Boolean>()
        for ((n, a) in nums) abstract[a]?.forEach { (l, ordered) -> out[n to l] = ordered }
        return out
    }
}

// ---------------------------------------------------------------- XLSX

data class Sheet(val name: String, val rows: List<List<String>>, val truncated: Boolean = false) {
    val columns: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

data class Workbook(val sheets: List<Sheet>)

object Xlsx {
    const val MAX_ROWS = 5000
    const val MAX_COLS = 200

    fun parse(pkg: OoxmlPackage): Workbook {
        val wb = "xl/workbook.xml"
        if (!pkg.has(wb)) throw IOException("Not a spreadsheet (xl/workbook.xml missing)")
        val shared = sharedStrings(pkg)
        val rels = pkg.rels(wb)
        val sheets = ArrayList<Pair<String, String>>() // name, part
        pkg.open(wb)!!.use { s ->
            val p = Xml.parser(s)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.local == "sheet") {
                    val part = p.relId()?.let { rels[it] } ?: continue
                    sheets.add((p.attr("name") ?: "Sheet ${sheets.size + 1}") to part)
                }
            }
        }
        return Workbook(sheets.map { (name, part) -> sheet(pkg, name, part, shared) })
    }

    /** "AB12" -> 27 (0-based column). */
    fun columnIndex(ref: String): Int {
        var n = 0
        for (c in ref) { if (!c.isLetter()) break; n = n * 26 + (c.uppercaseChar() - 'A' + 1) }
        return n - 1
    }

    private fun sharedStrings(pkg: OoxmlPackage): List<String> {
        val out = ArrayList<String>()
        pkg.open("xl/sharedStrings.xml")?.use { s ->
            val p = Xml.parser(s)
            val sb = StringBuilder(); var inSi = false; var inPhonetic = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.local) {
                        "si" -> { inSi = true; sb.clear() }
                        "rPh" -> inPhonetic = true
                        "t" -> if (inSi && !inPhonetic) sb.append(p.nextText())
                    }
                    XmlPullParser.END_TAG -> when (p.local) {
                        "rPh" -> inPhonetic = false
                        "si" -> { out.add(sb.toString()); inSi = false }
                    }
                }
            }
        }
        return out
    }

    private fun sheet(pkg: OoxmlPackage, name: String, part: String, shared: List<String>): Sheet {
        val rows = ArrayList<MutableList<String>>()
        var truncated = false
        pkg.open(part)?.use { s ->
            val p = Xml.parser(s)
            var rowIdx = -1; var col = 0; var type: String? = null; var value: String? = null
            val inline = StringBuilder(); var inInline = false
            loop@ while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.local) {
                        "row" -> {
                            rowIdx = (p.attr("r")?.toIntOrNull()?.minus(1)) ?: (rowIdx + 1)
                            if (rowIdx >= MAX_ROWS) { truncated = true; break@loop }
                            col = 0
                        }
                        "c" -> {
                            col = p.attr("r")?.let { columnIndex(it) }?.takeIf { it >= 0 } ?: col
                            type = p.attr("t"); value = null; inline.clear()
                        }
                        "v" -> value = p.nextText()
                        "is" -> inInline = true
                        "t" -> if (inInline) inline.append(p.nextText())
                    }
                    XmlPullParser.END_TAG -> when (p.local) {
                        "is" -> inInline = false
                        "c" -> {
                            val text = when (type) {
                                "s" -> value?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                                "inlineStr" -> inline.toString()
                                "b" -> if (value == "1") "TRUE" else "FALSE"
                                else -> value?.let(::formatNumber).orEmpty()
                            }
                            if (rowIdx >= 0 && col < MAX_COLS && text.isNotEmpty()) {
                                while (rows.size <= rowIdx) rows.add(ArrayList())
                                val r = rows[rowIdx]
                                while (r.size < col) r.add("")
                                if (r.size == col) r.add(text) else r[col] = text
                            } else if (col >= MAX_COLS) truncated = true
                            col++
                        }
                    }
                }
            }
        }
        return Sheet(name, rows, truncated)
    }

    /** "3.0" -> "3", keeps everything else (dates show as serial numbers; formats aren't applied). */
    fun formatNumber(v: String): String {
        val d = v.toDoubleOrNull() ?: return v
        return if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else v
    }
}

// ---------------------------------------------------------------- PPTX

data class Slide(val index: Int, val title: String?, val paragraphs: List<String>, val images: List<String>, val notes: String? = null)

data class Presentation(val slides: List<Slide>)

object Pptx {
    fun parse(pkg: OoxmlPackage): Presentation {
        val pres = "ppt/presentation.xml"
        if (!pkg.has(pres)) throw IOException("Not a presentation (ppt/presentation.xml missing)")
        val rels = pkg.rels(pres)
        val order = ArrayList<String>()
        pkg.open(pres)!!.use { s ->
            val p = Xml.parser(s)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.local == "sldId") p.relId()?.let { rels[it] }?.let(order::add)
            }
        }
        return Presentation(order.mapIndexed { i, part -> slide(pkg, i + 1, part) })
    }

    private fun slide(pkg: OoxmlPackage, index: Int, part: String): Slide {
        val rels = pkg.rels(part)
        val paras = ArrayList<String>(); val images = ArrayList<String>(); var title: String? = null
        pkg.open(part)?.use { s ->
            val p = Xml.parser(s)
            var isTitleShape = false; val sb = StringBuilder(); var inPara = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.local) {
                        "sp" -> isTitleShape = false
                        "ph" -> if (p.attr("type") in setOf("title", "ctrTitle")) isTitleShape = true
                        "p" -> if (p.name.startsWith("a:") || !p.name.contains(':')) { inPara = true; sb.clear() }
                        "t" -> if (inPara) sb.append(p.nextText())
                        "br" -> if (inPara) sb.append('\n')
                        "blip" -> p.attr("embed")?.let { rels[it] }?.let { if (it !in images) images.add(it) }
                    }
                    XmlPullParser.END_TAG -> if (p.local == "p" && inPara) {
                        inPara = false
                        val t = sb.toString().trim()
                        if (t.isNotEmpty()) { if (isTitleShape && title == null) title = t else paras.add(t) }
                    }
                }
            }
        }
        // No title placeholder (plain text boxes, common in exported decks): the first line acts as the title.
        if (title == null && paras.isNotEmpty()) title = paras.removeAt(0)
        // Speaker notes, if present.
        val notesPart = rels.values.firstOrNull { it.contains("notesSlide") }
        val notes = notesPart?.let { np ->
            pkg.open(np)?.use { s ->
                val p = Xml.parser(s); val out = StringBuilder(); var inBody = false
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when (p.local) {
                            "ph" -> inBody = p.attr("type") == "body"
                            "sp" -> inBody = false
                            "t" -> if (inBody) out.append(p.nextText())
                        }
                        XmlPullParser.END_TAG -> if (p.local == "p" && inBody && out.isNotEmpty() && out.last() != '\n') out.append('\n')
                    }
                }
                out.toString().trim().ifEmpty { null }
            }
        }
        return Slide(index, title, paras, images.filter { pkg.has(it) }, notes)
    }
}

// ---------------------------------------------------------------- CSV / TSV

object Csv {
    /** RFC 4180-style parse: quoted fields, doubled quotes, newlines inside quotes, CRLF or LF. */
    fun parse(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>(); val field = StringBuilder()
        var i = 0; var quoted = false; var any = false
        val t = text.removePrefix("\uFEFF")
        while (i < t.length) {
            val c = t[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < t.length && t[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(c)
            } else when (c) {
                '"' -> { quoted = true; any = true }
                delimiter -> { row.add(field.toString()); field.clear(); any = true }
                '\r' -> Unit
                '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = ArrayList(); any = false }
                else -> { field.append(c); any = true }
            }
            i++
        }
        if (any || field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }

    fun write(rows: List<List<String>>, delimiter: Char = ','): String = buildString {
        for (r in rows) {
            r.forEachIndexed { i, f ->
                if (i > 0) append(delimiter)
                if (f.any { it == delimiter || it == '"' || it == '\n' || it == '\r' } || f.startsWith(' ') || f.endsWith(' ')) {
                    append('"').append(f.replace("\"", "\"\"")).append('"')
                } else append(f)
            }
            append('\n')
        }
    }

    /** For .csv: picks ',' or ';' (European exports) or tab, by which splits the first line most. */
    fun sniff(text: String): Char {
        val first = text.removePrefix("\uFEFF").lineSequence().firstOrNull().orEmpty()
        return listOf(',', ';', '\t').maxByOrNull { d -> first.count { it == d } }?.takeIf { d -> first.contains(d) } ?: ','
    }

    /** Pads rows to the same width so the grid editor has a rectangle. */
    fun rectangular(rows: List<List<String>>): List<MutableList<String>> {
        val w = rows.maxOfOrNull { it.size } ?: 0
        return rows.map { r -> (r + List(w - r.size) { "" }).toMutableList() }
    }
}
