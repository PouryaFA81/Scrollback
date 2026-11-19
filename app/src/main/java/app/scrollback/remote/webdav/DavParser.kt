package app.scrollback.remote.webdav

import org.w3c.dom.Element
import java.io.InputStream
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** One resource in a PROPFIND multistatus response. */
data class DavEntry(
    /** Encoded absolute path as returned by the server, e.g. /remote.php/dav/files/me/My%20Videos/a.mp4 */
    val href: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val mime: String?,
    val etag: String?,
    val fileId: String?,
    val hasPreview: Boolean,
)

/** Pure-JVM PROPFIND parser (unit-tested). */
object DavParser {
    private const val DAV = "DAV:"
    private const val OC = "http://owncloud.org/ns"
    private const val NC = "http://nextcloud.org/ns"

    val PROPFIND_BODY = """<?xml version="1.0" encoding="UTF-8"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
  <d:prop>
    <d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getcontenttype/><d:getetag/>
    <oc:fileid/><oc:size/><nc:has-preview/>
  </d:prop>
</d:propfind>"""

    fun parse(input: InputStream): List<DavEntry> {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val doc = f.newDocumentBuilder().parse(input)
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        val out = ArrayList<DavEntry>(responses.length)
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = r.child(DAV, "href")?.textContent?.trim() ?: continue
            // Merge all propstats with a 200 status.
            var prop: Element? = null
            val stats = r.getElementsByTagNameNS(DAV, "propstat")
            for (j in 0 until stats.length) {
                val ps = stats.item(j) as Element
                val status = ps.child(DAV, "status")?.textContent.orEmpty()
                if (status.contains(" 200")) { prop = ps.child(DAV, "prop"); break }
            }
            val p = prop ?: continue
            val isDir = p.child(DAV, "resourcetype")?.child(DAV, "collection") != null
            val size = p.child(DAV, "getcontentlength")?.textContent?.trim()?.toLongOrNull()
                ?: p.child(OC, "size")?.textContent?.trim()?.toLongOrNull() ?: 0L
            out += DavEntry(
                href = href,
                name = nameOf(href),
                isDir = isDir,
                size = size,
                modified = p.child(DAV, "getlastmodified")?.textContent?.trim()?.let(::parseDate) ?: 0L,
                mime = p.child(DAV, "getcontenttype")?.textContent?.trim()?.ifEmpty { null },
                etag = p.child(DAV, "getetag")?.textContent?.trim()?.trim('"')?.ifEmpty { null },
                fileId = p.child(OC, "fileid")?.textContent?.trim()?.ifEmpty { null },
                hasPreview = p.child(NC, "has-preview")?.textContent?.trim() == "true",
            )
        }
        return out
    }

    fun nameOf(href: String): String = decode(href.trimEnd('/').substringAfterLast('/'))

    fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    /** Encodes each path segment, keeping the slashes. */
    fun encodePath(path: String): String = path.split('/').joinToString("/") { seg ->
        java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
    }

    fun parseDate(s: String): Long? = runCatching {
        ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()

    private fun Element.child(ns: String, local: String): Element? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.namespaceURI == ns && n.localName == local) return n
        }
        return null
    }
}
