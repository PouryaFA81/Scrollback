package app.scrollback.notes

/** A small, dependency-free Markdown reader for the note preview. Pure Kotlin, unit-tested. */
sealed interface MdBlock {
    data class FrontMatter(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItem(val text: String, val indent: Int, val ordered: Int?, val checked: Boolean?) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val lang: String, val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
    val wiki: Boolean = false,
    val tag: Boolean = false,
)

object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(?:\\[([ xX])]\\s+)?(.*)$")
    private val ordered = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val fence = Regex("^\\s{0,3}(```+|~~~+)\\s*([\\w+-]*).*$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(src: String): List<MdBlock> {
        val lines = src.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<MdBlock>()
        var i = 0
        if (lines.firstOrNull()?.trim() == "---") {
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" || lines[it].trim() == "..." }
            if (end != null) { out += MdBlock.FrontMatter(lines.subList(1, end).joinToString("\n")); i = end + 1 }
        }
        val para = StringBuilder()
        fun flush() { if (para.isNotEmpty()) { out += MdBlock.Paragraph(para.toString().trim()); para.clear() } }
        while (i < lines.size) {
            val line = lines[i]
            val f = fence.matchEntire(line)
            when {
                line.isBlank() -> flush()
                f != null -> {
                    flush()
                    val marker = f.groupValues[1]
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(marker)) { body += lines[i]; i++ }
                    out += MdBlock.Code(f.groupValues[2], body.joinToString("\n"))
                }
                heading.matchEntire(line) != null -> {
                    flush(); val m = heading.matchEntire(line)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                rule.matches(line) -> { flush(); out += MdBlock.Rule }
                line.trimStart().startsWith(">") -> {
                    flush()
                    val body = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) { body += lines[i].trimStart().removePrefix(">").removePrefix(" "); i++ }
                    out += MdBlock.Quote(body.joinToString("\n")); continue
                }
                bullet.matchEntire(line) != null -> {
                    flush(); val m = bullet.matchEntire(line)!!
                    val box = m.groupValues[2]
                    out += MdBlock.ListItem(m.groupValues[3], indentOf(m.groupValues[1]), null, if (box.isEmpty()) null else box != " ")
                }
                ordered.matchEntire(line) != null -> {
                    flush(); val m = ordered.matchEntire(line)!!
                    out += MdBlock.ListItem(m.groupValues[3], indentOf(m.groupValues[1]), m.groupValues[2].toInt(), null)
                }
                '|' in line && i + 1 < lines.size && tableSep.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(line)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && '|' in lines[i] && lines[i].isNotBlank()) { rows += cells(lines[i]); i++ }
                    out += MdBlock.Table(header, rows); continue
                }
                else -> { if (para.isNotEmpty()) para.append(if (line.endsWith("  ")) "\n" else " "); para.append(line.trim()) }
            }
            i++
        }
        flush()
        return out
    }

    private fun indentOf(ws: String) = ws.replace("\t", "    ").length / 2

    private fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** Inline spans: **bold**, *italic* / _italic_, `code`, ~~strike~~, [text](url), <url>, [[wiki|alias]], #tags. */
    fun inline(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        val buf = StringBuilder()
        var bold = false; var italic = false; var strike = false
        fun emit(span: MdSpan? = null) {
            if (buf.isNotEmpty()) { out += MdSpan(buf.toString(), bold, italic, strike = strike); buf.clear() }
            if (span != null) out += span
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val rest = text.substring(i)
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_{}[]()#+-.!~|<>" -> { buf.append(text[i + 1]); i += 2; continue }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) { emit(MdSpan(text.substring(i + 1, end), code = true)); i = end + 1; continue }
                }
                rest.startsWith("[[") -> {
                    val end = text.indexOf("]]", i + 2)
                    if (end > i) {
                        val inner = text.substring(i + 2, end)
                        emit(MdSpan(inner.substringAfter('|'), bold, italic, wiki = true, link = inner.substringBefore('|')))
                        i = end + 2; continue
                    }
                }
                c == '[' -> {
                    val close = text.indexOf("](", i + 1)
                    val end = if (close > 0) text.indexOf(')', close + 2) else -1
                    if (close > 0 && end > 0 && '\n' !in text.substring(i, end)) {
                        emit(MdSpan(text.substring(i + 1, close), bold, italic, strike = strike, link = text.substring(close + 2, end).trim().substringBefore(' ')))
                        i = end + 1; continue
                    }
                }
                c == '<' && (rest.startsWith("<http://") || rest.startsWith("<https://")) -> {
                    val end = text.indexOf('>', i)
                    if (end > 0) { val url = text.substring(i + 1, end); emit(MdSpan(url, link = url)); i = end + 1; continue }
                }
                rest.startsWith("**") || rest.startsWith("__") -> { emit(); bold = !bold; i += 2; continue }
                rest.startsWith("~~") -> { emit(); strike = !strike; i += 2; continue }
                (c == '*' || c == '_') && !(c == '_' && i > 0 && text[i - 1].isLetterOrDigit()) -> { emit(); italic = !italic; i++; continue }
                c == '#' && (i == 0 || text[i - 1].isWhitespace()) && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '/') -> {
                    var end = i + 1
                    while (end < text.length && (text[end].isLetterOrDigit() || text[end] in "_-/")) end++
                    emit(MdSpan(text.substring(i, end), tag = true)); i = end; continue
                }
            }
            buf.append(c); i++
        }
        emit()
        return out
    }
}

/** Formatting-toolbar edits on a text + selection. Pure; returns new text and selection. */
object MdEdit {
    data class Edit(val text: String, val start: Int, val end: Int)

    fun wrap(text: String, start: Int, end: Int, marker: String, placeholder: String = "text"): Edit {
        val s = minOf(start, end).coerceIn(0, text.length); val e = maxOf(start, end).coerceIn(0, text.length)
        val sel = text.substring(s, e)
        if (sel.length >= 2 * marker.length && sel.startsWith(marker) && sel.endsWith(marker)) {
            val inner = sel.substring(marker.length, sel.length - marker.length)
            return Edit(text.replaceRange(s, e, inner), s, s + inner.length)
        }
        val body = sel.ifEmpty { placeholder }
        val out = text.replaceRange(s, e, marker + body + marker)
        return Edit(out, s + marker.length, s + marker.length + body.length)
    }

    /** Toggles [prefix] on every line touched by the selection (e.g. "- ", "- [ ] ", "# "). */
    fun prefixLines(text: String, start: Int, end: Int, prefix: String): Edit {
        val s = minOf(start, end).coerceIn(0, text.length); val e = maxOf(start, end).coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', s - 1).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', e).let { if (it < 0) text.length else it }
        val lines = text.substring(lineStart, lineEnd).split('\n')
        val remove = lines.all { it.startsWith(prefix) }
        val changed = lines.joinToString("\n") { if (remove) it.removePrefix(prefix) else prefix + it }
        val delta = changed.length - (lineEnd - lineStart)
        val out = text.replaceRange(lineStart, lineEnd, changed)
        return if (lines.size == 1) {
            val shift = if (remove) -prefix.length else prefix.length
            Edit(out, (s + shift).coerceIn(lineStart, out.length), (e + shift).coerceIn(lineStart, out.length))
        } else Edit(out, lineStart, lineEnd + delta)
    }

    fun link(text: String, start: Int, end: Int): Edit {
        val s = minOf(start, end); val e = maxOf(start, end)
        val label = text.substring(s, e).ifEmpty { "link" }
        val out = text.replaceRange(s, e, "[$label](https://)")
        val urlStart = s + label.length + 3
        return Edit(out, urlStart, urlStart + "https://".length)
    }
}

object NoteText {
    val EXTENSIONS = setOf("md", "markdown", "txt", "text", "log")
    fun isNote(name: String) = name.substringAfterLast('.', "").lowercase() in EXTENSIONS
    fun isMarkdown(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")
    fun words(text: String): Int = Regex("\\S+").findAll(text).count()
    /** "Shopping list" -> "Shopping list.md"; keeps a typed .txt/.md extension. */
    fun fileName(title: String): String {
        val t = title.trim().replace(Regex("[\\\\/:*?\"<>|]"), "-").ifBlank { "Untitled" }
        return if (isNote(t)) t else "$t.md"
    }
}
