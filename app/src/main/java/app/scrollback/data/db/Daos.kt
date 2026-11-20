// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val VISIBLE = "missing = 0 AND (hidden = 0 OR :showHidden) AND kind != 'audio'"
private const val AUDIO = "kind = 'audio' AND missing = 0 AND (hidden = 0 OR :showHidden)"
private const val ARTIST = "COALESCE(NULLIF(artist, ''), 'Unknown artist')"
private const val ALBUM = "COALESCE(NULLIF(album, ''), 'Unknown album')"

@Dao
interface SourceDao {
    @Query(
        """SELECT s.id, s.type, s.uri, s.displayName, s.lastScanAt,
           (SELECT COUNT(*) FROM media m WHERE m.sourceId = s.id AND m.kind = 'video' AND m.missing = 0) AS videoCount,
           (SELECT COUNT(*) FROM media m WHERE m.sourceId = s.id AND m.kind = 'image' AND m.missing = 0) AS imageCount,
           (SELECT COUNT(*) FROM media m WHERE m.sourceId = s.id AND m.kind = 'audio' AND m.missing = 0) AS audioCount
           FROM sources s ORDER BY s.addedAt"""
    )
    fun observeSummaries(): Flow<List<SourceSummary>>

    @Query("SELECT * FROM sources ORDER BY addedAt")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun byId(id: Long): SourceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(source: SourceEntity): Long

    @Query("SELECT * FROM sources WHERE uri = :uri")
    suspend fun byUri(uri: String): SourceEntity?

    @Query("UPDATE sources SET lastScanAt = :at WHERE id = :id")
    suspend fun setScanned(id: Long, at: Long)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MediaDao {
    @Query("SELECT * FROM media WHERE kind = 'video' AND $VISIBLE ORDER BY dateModified DESC")
    fun observeVideos(showHidden: Boolean): Flow<List<MediaEntity>>

    @Query(
        """SELECT * FROM media WHERE kind = 'video' AND $VISIBLE
           ORDER BY CASE WHEN :newestFirst THEN -dateModified ELSE dateModified END, id"""
    )
    fun observeVideosByDate(newestFirst: Boolean, showHidden: Boolean): Flow<List<MediaEntity>>

    @Query(
        """SELECT * FROM media WHERE kind = 'image' AND $VISIBLE
           ORDER BY CASE WHEN :newestFirst THEN -dateModified ELSE dateModified END, id"""
    )
    fun observeImages(newestFirst: Boolean, showHidden: Boolean): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE liked = 1 AND $VISIBLE ORDER BY likedAt DESC")
    fun observeLiked(showHidden: Boolean): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE lastWatchedAt > 0 AND $VISIBLE ORDER BY lastWatchedAt DESC LIMIT 500")
    fun observeHistory(showHidden: Boolean): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE hidden = 1 AND missing = 0 AND kind != 'audio' ORDER BY dateModified DESC")
    fun observeHidden(): Flow<List<MediaEntity>>

    @Query(
        """SELECT DISTINCT m.* FROM media m
           LEFT JOIN media_tags mt ON mt.mediaId = m.id
           LEFT JOIN tags t ON t.id = mt.tagId
           WHERE m.missing = 0 AND (m.hidden = 0 OR :showHidden) AND m.kind != 'audio'
             AND (m.name LIKE :pattern OR m.note LIKE :pattern OR m.relativePath LIKE :pattern OR t.name LIKE :pattern)
           ORDER BY m.dateModified DESC"""
    )
    fun observeSearch(pattern: String, showHidden: Boolean): Flow<List<MediaEntity>>

    @Query(
        """SELECT m.* FROM media m JOIN media_tags mt ON mt.mediaId = m.id
           WHERE mt.tagId IN (:tagIds) AND m.missing = 0 AND (m.hidden = 0 OR :showHidden) AND m.kind != 'audio'
           GROUP BY m.id HAVING COUNT(DISTINCT mt.tagId) = :tagCount
           ORDER BY m.dateModified DESC"""
    )
    fun observeWithAllTags(tagIds: List<Long>, tagCount: Int, showHidden: Boolean): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE id = :id")
    fun observeById(id: Long): Flow<MediaEntity?>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun byId(id: Long): MediaEntity?

    /** v0.13 full player: the other videos in the same folder, by name (hidden ones only when unlocked). */
    @Query("SELECT * FROM media WHERE sourceId = :sourceId AND relativePath = :relativePath AND kind = 'video' AND missing = 0 AND (hidden = 0 OR :showHidden OR id = :keepId) ORDER BY name COLLATE NOCASE")
    suspend fun folderVideos(sourceId: Long, relativePath: String, showHidden: Boolean, keepId: Long): List<MediaEntity>

    @Query("SELECT id, uri, size, dateModified, missing, liked, metaUpdatedAt, remoteId FROM media WHERE sourceId = :sourceId")
    suspend fun indexRows(sourceId: Long): List<IndexRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<MediaEntity>)

    @Query(
        """UPDATE media SET name = :name, relativePath = :relativePath, mime = :mime, size = :size,
           dateModified = :dateModified, partialHash = NULL, fullHash = NULL, missing = 0 WHERE id = :id"""
    )
    suspend fun updateChanged(id: Long, name: String, relativePath: String, mime: String, size: Long, dateModified: Long)

    @Query("UPDATE media SET missing = :missing WHERE id IN (:ids)")
    suspend fun setMissing(ids: List<Long>, missing: Boolean)

    @Query("UPDATE media SET liked = :liked, likedAt = :at, metaUpdatedAt = :now WHERE id = :id")
    suspend fun setLiked(id: Long, liked: Boolean, at: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE media SET hidden = :hidden, metaUpdatedAt = :now WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE media SET note = :note, metaUpdatedAt = :now WHERE id = :id")
    suspend fun setNote(id: Long, note: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE media SET lastPositionMs = :positionMs, metaUpdatedAt = :now WHERE id = :id")
    suspend fun savePosition(id: Long, positionMs: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE media SET lastWatchedAt = :at, watchCount = watchCount + 1, metaUpdatedAt = :at WHERE id = :id")
    suspend fun markWatched(id: Long, at: Long)

    @Query("UPDATE media SET lastWatchedAt = 0, lastPositionMs = 0, metaUpdatedAt = :now WHERE lastWatchedAt > 0 OR lastPositionMs > 0")
    suspend fun clearHistory(now: Long = System.currentTimeMillis())

    @Query("UPDATE media SET durationMs = :durationMs, width = :width, height = :height WHERE id = :id")
    suspend fun setMetadata(id: Long, durationMs: Long, width: Int, height: Int)

    @Query("UPDATE media SET partialHash = COALESCE(:partial, partialHash), fullHash = COALESCE(:full, fullHash) WHERE id = :id")
    suspend fun setHashes(id: Long, partial: String?, full: String?)

    @Query("SELECT * FROM media WHERE kind = 'video' AND missing = 0 AND durationMs = 0 AND uri NOT LIKE 'http%' AND uri NOT LIKE 'subsonic:%' AND uri NOT LIKE 'jellyfin:%' LIMIT :limit")
    suspend fun missingMetadata(limit: Int): List<MediaEntity>

    @Query(
        """SELECT * FROM media WHERE missing = 0 AND size > 0 AND uri NOT LIKE 'subsonic:%' AND uri NOT LIKE 'jellyfin:%' AND uri NOT LIKE 'http%' AND size IN
           (SELECT size FROM media WHERE missing = 0 AND size > 0 GROUP BY size HAVING COUNT(*) > 1)"""
    )
    suspend fun sameSizeCandidates(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<MediaEntity>

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM media WHERE missing = 0")
    suspend fun allPresent(): List<MediaEntity>

    // ---- Music ----
    @Query("SELECT * FROM media WHERE $AUDIO ORDER BY COALESCE(NULLIF(title, ''), name) COLLATE NOCASE")
    fun observeSongs(showHidden: Boolean): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE $AUDIO AND liked = 1 ORDER BY likedAt DESC")
    fun observeLikedSongs(showHidden: Boolean): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE $AUDIO AND lastWatchedAt > 0 ORDER BY lastWatchedAt DESC LIMIT 200")
    fun observeRecentSongs(showHidden: Boolean): Flow<List<MediaEntity>>

    @Query(
        """SELECT $ARTIST AS artist, COUNT(*) AS songs, COUNT(DISTINCT $ALBUM) AS albums, MAX(thumbUri) AS cover
           FROM media WHERE $AUDIO GROUP BY $ARTIST ORDER BY $ARTIST COLLATE NOCASE"""
    )
    fun observeArtists(showHidden: Boolean): Flow<List<ArtistRow>>

    @Query(
        """SELECT $ALBUM AS album, MAX(artist) AS artist, COUNT(*) AS songs, MAX(thumbUri) AS cover, MAX(dateModified) AS year
           FROM media WHERE $AUDIO GROUP BY $ALBUM ORDER BY $ALBUM COLLATE NOCASE"""
    )
    fun observeAlbums(showHidden: Boolean): Flow<List<AlbumRow>>

    @Query(
        """SELECT CASE WHEN relativePath = '' THEN '/' ELSE relativePath END AS folder, COUNT(*) AS songs
           FROM media WHERE $AUDIO GROUP BY folder ORDER BY folder COLLATE NOCASE"""
    )
    fun observeMusicFolders(showHidden: Boolean): Flow<List<FolderRow>>

    @Query("SELECT * FROM media WHERE $AUDIO AND $ARTIST = :artist ORDER BY $ALBUM COLLATE NOCASE, trackNo, name")
    fun observeArtistSongs(artist: String, showHidden: Boolean): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE $AUDIO AND $ALBUM = :album ORDER BY trackNo, name")
    fun observeAlbumSongs(album: String, showHidden: Boolean): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE $AUDIO AND (relativePath = :folder OR (:folder = '/' AND relativePath = '')) ORDER BY trackNo, name")
    fun observeFolderSongs(folder: String, showHidden: Boolean): Flow<List<MediaEntity>>

    @Query(
        """SELECT * FROM media WHERE $AUDIO AND (title LIKE :q OR artist LIKE :q OR album LIKE :q OR name LIKE :q)
           ORDER BY COALESCE(NULLIF(title, ''), name) COLLATE NOCASE LIMIT 300"""
    )
    fun observeSongSearch(q: String, showHidden: Boolean): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE kind = 'audio' AND missing = 0 AND durationMs = 0 AND uri NOT LIKE 'http%' AND uri NOT LIKE 'subsonic:%' AND uri NOT LIKE 'jellyfin:%' LIMIT :limit")
    suspend fun missingAudioTags(limit: Int): List<MediaEntity>

    @Query("UPDATE media SET title = :title, artist = :artist, album = :album, trackNo = :trackNo, discNo = :discNo, durationMs = :durationMs, thumbUri = :thumbUri WHERE id = :id")
    suspend fun setAudioTags(id: Long, title: String?, artist: String?, album: String?, trackNo: Int, discNo: Int, durationMs: Long, thumbUri: String?)

    @Query("SELECT * FROM media WHERE sourceId = :sourceId AND remoteId = :remoteId LIMIT 1")
    suspend fun byRemoteId(sourceId: Long, remoteId: String): MediaEntity?

    @Query("UPDATE media SET thumbUri = :thumbUri, artist = :artist, album = :album, title = :title, trackNo = :trackNo, discNo = :discNo, remoteId = :remoteId, durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END WHERE id = :id")
    suspend fun setExtras(id: Long, thumbUri: String?, artist: String?, album: String?, title: String?, trackNo: Int, discNo: Int, remoteId: String?, durationMs: Long)

    @Query("UPDATE media SET localUri = :localUri WHERE id = :id")
    suspend fun setLocalUri(id: Long, localUri: String?)

    @Query(
        """UPDATE media SET liked = :liked, likedAt = :likedAt, hidden = :hidden, note = :note,
           lastPositionMs = :lastPositionMs, lastWatchedAt = :lastWatchedAt, watchCount = :watchCount,
           metaUpdatedAt = :updatedAt WHERE id = :id"""
    )
    suspend fun applyRemote(
        id: Long, liked: Boolean, likedAt: Long, hidden: Boolean, note: String,
        lastPositionMs: Long, lastWatchedAt: Long, watchCount: Int, updatedAt: Long,
    )
}

@Dao
interface TagDao {
    @Query("SELECT mt.mediaId AS mediaId, t.name AS name FROM media_tags mt JOIN tags t ON t.id = mt.tagId")
    suspend fun allNames(): List<MediaTagName>

    @Query(
        """SELECT t.id, t.name, COUNT(m.id) AS count FROM tags t
           JOIN media_tags mt ON mt.tagId = t.id
           JOIN media m ON m.id = mt.mediaId AND m.missing = 0 AND (m.hidden = 0 OR :showHidden)
           GROUP BY t.id ORDER BY count DESC, t.name"""
    )
    fun observeTagCounts(showHidden: Boolean): Flow<List<TagCount>>

    @Query("SELECT t.* FROM tags t JOIN media_tags mt ON mt.tagId = t.id WHERE mt.mediaId = :mediaId ORDER BY t.name")
    fun observeTagsFor(mediaId: Long): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun byName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRefs(refs: List<MediaTagRef>)

    @Query("DELETE FROM media_tags WHERE mediaId = :mediaId")
    suspend fun clearRefs(mediaId: Long)

    @Query("DELETE FROM tags WHERE id NOT IN (SELECT DISTINCT tagId FROM media_tags)")
    suspend fun deleteOrphans()
}

@Dao
interface PlaylistDao {
    @Query(
        """SELECT p.id, p.name,
           (SELECT COUNT(*) FROM playlist_entries e JOIN media m ON m.id = e.mediaId
              WHERE e.playlistId = p.id AND m.missing = 0 AND (m.hidden = 0 OR :showHidden)) AS count,
           (SELECT m.uri FROM playlist_entries e JOIN media m ON m.id = e.mediaId
              WHERE e.playlistId = p.id AND m.missing = 0 AND (m.hidden = 0 OR :showHidden)
              ORDER BY e.position LIMIT 1) AS coverUri
           FROM playlists p ORDER BY p.createdAt DESC"""
    )
    fun observeSummaries(showHidden: Boolean): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Query(
        """SELECT m.* FROM media m JOIN playlist_entries e ON e.mediaId = m.id
           WHERE e.playlistId = :playlistId AND m.missing = 0 AND (m.hidden = 0 OR :showHidden)
           ORDER BY e.position"""
    )
    fun observeItems(playlistId: Long, showHidden: Boolean): Flow<List<MediaEntity>>

    @Query("SELECT playlistId FROM playlist_entries WHERE mediaId = :mediaId")
    fun observeMembership(mediaId: Long): Flow<List<Long>>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntry(entry: PlaylistEntry)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId AND mediaId = :mediaId")
    suspend fun removeEntry(playlistId: Long, mediaId: Long)

    @Query("UPDATE playlist_entries SET position = :position WHERE playlistId = :playlistId AND mediaId = :mediaId")
    suspend fun setPosition(playlistId: Long, mediaId: Long, position: Int)

    @Query("SELECT mediaId FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    suspend fun orderedMediaIds(playlistId: Long): List<Long>

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM playlists")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): PlaylistEntity?

    @Query("UPDATE playlists SET uuid = :uuid WHERE id = :id")
    suspend fun setUuid(id: Long, uuid: String)

    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun applyRemote(id: Long, name: String, updatedAt: Long)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun clearEntries(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(t: SyncTombstone)

    @Query("SELECT * FROM sync_tombstones")
    suspend fun tombstones(): List<SyncTombstone>
}
