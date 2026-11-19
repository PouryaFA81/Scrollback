package app.scrollback.core.hash

import java.io.InputStream
import java.security.MessageDigest

/**
 * Pure duplicate-detection logic, independent of Android so it can be unit tested.
 *
 * Strategy:
 * 1. Group candidates by exact byte size; singletons cannot be duplicates.
 * 2. Within a size group, compute a cheap partial hash (size + first 64 KiB + last 64 KiB).
 * 3. Only for partial-hash ties, compute a full SHA-256 of the content and group by it.
 */
object DuplicateFinder {
    const val CHUNK: Int = 64 * 1024

    /** Random-access view of one file's bytes. */
    interface ByteSource {
        val size: Long
        fun readRange(offset: Long, length: Int): ByteArray
        fun openStream(): InputStream
    }

    data class Candidate<K>(
        val key: K,
        val size: Long,
        val cachedPartial: String? = null,
        val cachedFull: String? = null,
    )

    fun partialHash(src: ByteSource): String {
        val md = MessageDigest.getInstance("SHA-256")
        val size = src.size
        md.update(longToBytes(size))
        val headLen = minOf(CHUNK.toLong(), size).toInt()
        if (headLen > 0) md.update(src.readRange(0, headLen))
        if (size > CHUNK) {
            val tailLen = minOf(CHUNK.toLong(), size - CHUNK).toInt()
            md.update(src.readRange(size - tailLen, tailLen))
        }
        return md.digest().toHex()
    }

    fun fullHash(src: ByteSource): String {
        val md = MessageDigest.getInstance("SHA-256")
        src.openStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    /**
     * Returns groups (size >= 2) of keys whose content is byte-identical by full hash.
     * [onHashed] is called whenever a hash is newly computed so callers can cache it.
     */
    fun <K> findGroups(
        candidates: List<Candidate<K>>,
        open: (K) -> ByteSource,
        onHashed: (key: K, partial: String?, full: String?) -> Unit = { _, _, _ -> },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<List<K>> {
        val sizeGroups = candidates.filter { it.size > 0 }.groupBy { it.size }.values.filter { it.size > 1 }
        val total = sizeGroups.sumOf { it.size }
        var done = 0
        val result = mutableListOf<List<K>>()
        for (group in sizeGroups) {
            val partials = group.map { c ->
                val p = c.cachedPartial ?: partialHash(open(c.key)).also { onHashed(c.key, it, null) }
                done++
                onProgress(done, total)
                c to p
            }
            val partialTies = partials.groupBy({ it.second }, { it.first }).values.filter { it.size > 1 }
            for (tie in partialTies) {
                val fulls = tie.map { c ->
                    val f = c.cachedFull ?: fullHash(open(c.key)).also { onHashed(c.key, null, it) }
                    c.key to f
                }
                fulls.groupBy({ it.second }, { it.first }).values.filter { it.size > 1 }.forEach { result += it }
            }
        }
        return result
    }

    private fun longToBytes(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (8 * i)).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
