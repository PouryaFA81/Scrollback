package app.scrollback.data.repo

import app.scrollback.core.hash.DuplicateFinder
import app.scrollback.data.db.AppDatabase
import app.scrollback.data.db.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Finds byte-identical files across all sources, caching hashes in the index. */
class DuplicateRepository(
    private val db: AppDatabase,
    private val library: LibraryRepository,
) {
    suspend fun findDuplicates(onProgress: (Int, Int) -> Unit): List<List<MediaEntity>> = withContext(Dispatchers.IO) {
        val dao = db.media()
        val items = dao.sameSizeCandidates().associateBy { it.id }
        val candidates = items.values.map {
            DuplicateFinder.Candidate(it.id, it.size, it.partialHash, it.fullHash)
        }
        val groups = DuplicateFinder.findGroups(
            candidates = candidates,
            open = { id ->
                val m = items.getValue(id)
                runBlocking { library.mediaSourceFor(m) }?.byteSource(m.uri, m.size)
                    ?: error("Source missing for ${m.name}")
            },
            onHashed = { id, partial, full -> runBlocking { dao.setHashes(id, partial, full) } },
            onProgress = onProgress,
        )
        // Oldest copy first, so it is the one kept by default.
        groups.map { ids -> ids.mapNotNull { items[it] }.sortedWith(compareBy({ it.dateModified }, { it.id })) }
            .sortedByDescending { it.first().size }
    }
}
