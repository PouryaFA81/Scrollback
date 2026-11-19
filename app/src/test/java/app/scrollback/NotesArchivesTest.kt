package app.scrollback

import app.scrollback.files.ArchiveFormat
import app.scrollback.files.ArchiveLimitException
import app.scrollback.files.ArchivePaths
import app.scrollback.files.ArchiveSink
import app.scrollback.files.Archives
import app.scrollback.files.ExtractLimits
import app.scrollback.notes.Markdown
import app.scrollback.notes.MdBlock
import app.scrollback.notes.MdEdit
import app.scrollback.notes.NoteText
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NotesArchivesTest {
    private class MapSink : ArchiveSink {
        val files = linkedMapOf<String, String>(); val dirs = mutableListOf<String>()
        override fun dir(path: String) { dirs += path }
        override fun file(path: String, input: InputStream) { files[path] = input.readBytes().decodeToString() }
    }

    @Test fun detectsFormatsLongestSuffixFirst() {
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.detect("a.tar.gz"))
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.detect("A.TGZ"))
        assertEquals(ArchiveFormat.GZ, ArchiveFormat.detect("log.gz"))
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveFormat.detect("x.7z"))
        assertNull(ArchiveFormat.detect("movie.mp4"))
        assertNull(ArchiveFormat.detect(".zip"))
        assertEquals("photos", ArchiveFormat.baseName("photos.tar.gz"))
        assertEquals("notes.txt", ArchiveFormat.baseName("notes.txt.gz"))
    }

    @Test fun zipSlipIsBlocked() {
        assertNull(ArchivePaths.safe("../evil.sh"))
        assertNull(ArchivePaths.safe("a/../../evil"))
        assertEquals("etc/passwd", ArchivePaths.safe("/etc/passwd"))
        assertEquals("b/c.txt", ArchivePaths.safe("a/../b/./c.txt"))
        assertEquals("x/y", ArchivePaths.safe("C:\\x\\y"))
        assertNull(ArchivePaths.safe("./"))
    }

    @Test fun zipRoundTripWithTraversalEntrySkipped() {
        val bytes = ByteArrayOutputStream()
        Archives.createZip(bytes, listOf(
            Archives.ZipItem("dir", true) { null },
            Archives.ZipItem("dir/a.txt", false) { "hello".byteInputStream() },
            Archives.ZipItem("b.md", false) { "# hi".byteInputStream() },
        ))
        // add a hostile entry by hand
        val hostile = ByteArrayOutputStream()
        ZipOutputStream(hostile).use { z -> z.putNextEntry(ZipEntry("../../x")); z.write(1); z.closeEntry(); z.putNextEntry(ZipEntry("ok")); z.write("y".toByteArray()); z.closeEntry() }
        val sink = MapSink()
        val r = Archives.extract(ArchiveFormat.ZIP, "t.zip", bytes.size().toLong(), { ByteArrayInputStream(bytes.toByteArray()) }, null, sink)
        assertEquals(mapOf("dir/a.txt" to "hello", "b.md" to "# hi"), sink.files)
        assertEquals(2, r.files)
        val sink2 = MapSink()
        val r2 = Archives.extract(ArchiveFormat.ZIP, "h.zip", 0, { ByteArrayInputStream(hostile.toByteArray()) }, null, sink2)
        assertEquals(mapOf("ok" to "y"), sink2.files); assertEquals(1, r2.skipped)
    }

    @Test fun tarGzAndSevenZExtract() {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(bytes)).use { t ->
            val data = "tar body".toByteArray()
            t.putArchiveEntry(TarArchiveEntry("folder/file.txt").apply { size = data.size.toLong() }); t.write(data); t.closeArchiveEntry()
            t.putArchiveEntry(TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK).apply { linkName = "/etc/passwd" }); t.closeArchiveEntry()
        }
        val sink = MapSink()
        val r = Archives.extract(ArchiveFormat.TAR_GZ, "a.tgz", bytes.size().toLong(), { ByteArrayInputStream(bytes.toByteArray()) }, null, sink)
        assertEquals(mapOf("folder/file.txt" to "tar body"), sink.files); assertEquals(1, r.skipped)

        val f = File.createTempFile("sbtest", ".7z")
        SevenZOutputFile(f).use { s ->
            val e = SevenZArchiveEntry().apply { name = "seven/one.txt" }; s.putArchiveEntry(e); s.write("7z!".toByteArray()); s.closeArchiveEntry()
        }
        val sink7 = MapSink()
        Archives.extract(ArchiveFormat.SEVEN_Z, f.name, f.length(), { f.inputStream() }, f, sink7)
        assertEquals(mapOf("seven/one.txt" to "7z!"), sink7.files)
        f.delete()
    }

    @Test(expected = ArchiveLimitException::class) fun bombLimitTrips() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z -> z.putNextEntry(ZipEntry("zeros")); z.write(ByteArray(4_000_000)); z.closeEntry() }
        Archives.extract(ArchiveFormat.ZIP, "b.zip", bytes.size().toLong(), { ByteArrayInputStream(bytes.toByteArray()) }, null, MapSink(),
            ExtractLimits(maxRatio = 10, ratioFloor = 1_000_000))
    }

    @Test fun markdownBlocks() {
        val md = "---\ntags: [a]\n---\n# Title\n\nSome *text*\ncontinues.\n\n- [x] done\n- [ ] todo\n  - nested\n1. first\n> quote\n\n```kt\nval x = 1\n```\n| a | b |\n|---|---|\n| 1 | 2 |\n\n***"
        val b = Markdown.parse(md)
        assertEquals(MdBlock.FrontMatter("tags: [a]"), b[0])
        assertEquals(MdBlock.Heading(1, "Title"), b[1])
        assertEquals(MdBlock.Paragraph("Some *text* continues."), b[2])
        assertEquals(MdBlock.ListItem("done", 0, null, true), b[3])
        assertEquals(MdBlock.ListItem("todo", 0, null, false), b[4])
        assertEquals(MdBlock.ListItem("nested", 1, null, null), b[5])
        assertEquals(MdBlock.ListItem("first", 0, 1, null), b[6])
        assertEquals(MdBlock.Quote("quote"), b[7])
        assertEquals(MdBlock.Code("kt", "val x = 1"), b[8])
        assertEquals(MdBlock.Table(listOf("a", "b"), listOf(listOf("1", "2"))), b[9])
        assertEquals(MdBlock.Rule, b[10])
    }

    @Test fun markdownInline() {
        val s = Markdown.inline("a **b** *c* `d` [e](http://x) [[Note|alias]] #tag snake_case ~~s~~")
        assertTrue(s.any { it.text == "b" && it.bold })
        assertTrue(s.any { it.text == "c" && it.italic })
        assertTrue(s.any { it.text == "d" && it.code })
        assertTrue(s.any { it.text == "e" && it.link == "http://x" })
        assertTrue(s.any { it.text == "alias" && it.wiki && it.link == "Note" })
        assertTrue(s.any { it.text == "#tag" && it.tag })
        assertTrue(s.any { "snake_case" in it.text && !it.italic })
        assertTrue(s.any { it.text == "s" && it.strike })
    }

    @Test fun swipeTabTargets() {
        val t = app.scrollback.ui.components.SwipeTabsLogic
        assertEquals(1, t.target(0, 4, -100f, 72f))
        assertEquals(0, t.target(0, 4, 100f, 72f))
        assertEquals(3, t.target(3, 4, -100f, 72f))
        assertEquals(2, t.target(3, 4, 80f, 72f))
        assertEquals(2, t.target(2, 4, 30f, 72f))
    }

    @Test fun toolbarEdits() {
        assertEquals(MdEdit.Edit("a **bc** d", 4, 6), MdEdit.wrap("a bc d", 2, 4, "**"))
        assertEquals(MdEdit.Edit("a bc d", 2, 4), MdEdit.wrap("a **bc** d", 2, 8, "**"))
        assertEquals("- one\n- two", MdEdit.prefixLines("one\ntwo", 0, 7, "- ").text)
        assertEquals("one\ntwo", MdEdit.prefixLines("- one\n- two", 0, 11, "- ").text)
        assertEquals(MdEdit.Edit("x\n# y", 5, 5), MdEdit.prefixLines("x\ny", 3, 3, "# "))
        assertEquals("[go](https://)", MdEdit.link("go", 0, 2).text)
        assertEquals("Shopping list.md", NoteText.fileName("Shopping list"))
        assertEquals("a.txt", NoteText.fileName("a.txt"))
        assertEquals("a-b.md", NoteText.fileName("a/b"))
        assertEquals(3, NoteText.words(" one two\nthree "))
    }
}
