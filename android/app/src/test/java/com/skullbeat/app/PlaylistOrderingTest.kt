package com.skullbeat.app

import com.skullbeat.app.domain.models.PlaylistTrack
import com.skullbeat.app.domain.models.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOrderingTest {

    // Simulates PlaylistDao position logic in memory
    private class MockPlaylistDao {
        val tracks = mutableListOf<PlaylistTrack>()

        fun getMinPosition(playlistId: Long): Int? {
            return tracks.filter { it.playlistId == playlistId }.minOfOrNull { it.position }
        }

        fun getMaxPosition(playlistId: Long): Int? {
            return tracks.filter { it.playlistId == playlistId }.maxOfOrNull { it.position }
        }

        fun isTrackInPlaylist(playlistId: Long, videoId: String): Boolean {
            return tracks.any { it.playlistId == playlistId && it.videoId == videoId }
        }

        fun removeTrackFromPlaylist(playlistId: Long, videoId: String) {
            val idx = tracks.indexOfFirst { it.playlistId == playlistId && it.videoId == videoId }
            if (idx >= 0) tracks.removeAt(idx)
        }

        fun addTrackToPlaylist(playlistId: Long, videoId: String) {
            val minPos = (getMinPosition(playlistId) ?: 1) - 1
            tracks.add(PlaylistTrack(playlistId = playlistId, videoId = videoId, position = minPos))
        }

        fun addTracksToPlaylist(playlistId: Long, videoIds: List<String>) {
            if (videoIds.isEmpty()) return
            val baseMin = (getMinPosition(playlistId) ?: 1) - videoIds.size
            videoIds.forEachIndexed { index, vid ->
                tracks.add(PlaylistTrack(playlistId = playlistId, videoId = vid, position = baseMin + index))
            }
        }

        fun getPlaylistVideoIdsOrdered(playlistId: Long): List<String> {
            return tracks
                .filter { it.playlistId == playlistId }
                .sortedBy { it.position }
                .map { it.videoId }
        }
    }

    @Test
    fun addTrackToPlaylist_singleTrack_insertedAtPositionZero() {
        val dao = MockPlaylistDao()
        val playlistId = 1L

        dao.addTrackToPlaylist(playlistId, "song_1")

        val ordered = dao.getPlaylistVideoIdsOrdered(playlistId)
        assertEquals(listOf("song_1"), ordered)
        assertEquals(0, dao.tracks.first().position)
    }

    @Test
    fun addTrackToPlaylist_multipleTracksSequentially_alwaysComesFirst() {
        val dao = MockPlaylistDao()
        val playlistId = 1L

        dao.addTrackToPlaylist(playlistId, "song_A")
        dao.addTrackToPlaylist(playlistId, "song_B")
        dao.addTrackToPlaylist(playlistId, "song_C")

        val ordered = dao.getPlaylistVideoIdsOrdered(playlistId)
        // song_C added last -> must be FIRST (top)
        // song_B added second -> middle
        // song_A added first -> last
        assertEquals(listOf("song_C", "song_B", "song_A"), ordered)
        assertEquals(-2, dao.tracks.find { it.videoId == "song_C" }?.position)
        assertEquals(-1, dao.tracks.find { it.videoId == "song_B" }?.position)
        assertEquals(0, dao.tracks.find { it.videoId == "song_A" }?.position)
    }

    @Test
    fun addTracksToPlaylist_bulkImport_preservesSequentialAlbumOrder() {
        val dao = MockPlaylistDao()
        val playlistId = 2L
        val albumTracks = (1..10).map { "album_track_$it" }

        dao.addTracksToPlaylist(playlistId, albumTracks)

        val ordered = dao.getPlaylistVideoIdsOrdered(playlistId)
        assertEquals(albumTracks, ordered)
    }

    @Test
    fun addTrackToPlaylist_afterBulkImport_newTrackPrependBeforeImportedTracks() {
        val dao = MockPlaylistDao()
        val playlistId = 3L
        val imported = listOf("track_1", "track_2", "track_3")

        dao.addTracksToPlaylist(playlistId, imported)
        // User now manually adds a fresh track to the playlist
        dao.addTrackToPlaylist(playlistId, "brand_new_song")

        val ordered = dao.getPlaylistVideoIdsOrdered(playlistId)
        assertEquals(listOf("brand_new_song", "track_1", "track_2", "track_3"), ordered)
        assertEquals("brand_new_song", ordered.first())
    }

    @Test
    fun addTrackToPlaylist_duplicateTrack_addsMultiCopyOfExactSameSong() {
        val dao = MockPlaylistDao()
        val playlistId = 4L
        dao.addTracksToPlaylist(playlistId, listOf("track_A", "track_B", "track_C"))

        // Multi-copy: Adding track_C again adds another copy to the playlist without deleting existing copies
        val reAddedTrack = "track_C"
        dao.addTrackToPlaylist(playlistId, reAddedTrack)

        val ordered = dao.getPlaylistVideoIdsOrdered(playlistId)
        // track_C was added again at top position (-1), keeping the original track_C (pos 2)
        assertEquals(listOf("track_C", "track_A", "track_B", "track_C"), ordered)
        assertEquals(2, ordered.count { it == "track_C" })
    }

    @Test
    fun removeTrackFromPlaylist_withDuplicates_removesOnlySingleInstance() {
        val dao = MockPlaylistDao()
        val playlistId = 7L
        dao.addTracksToPlaylist(playlistId, listOf("track_A", "track_B", "track_A"))

        // Removing track_A should remove only one instance
        dao.removeTrackFromPlaylist(playlistId, "track_A")

        val ordered = dao.getPlaylistVideoIdsOrdered(playlistId)
        assertEquals(listOf("track_B", "track_A"), ordered)
        assertEquals(1, ordered.count { it == "track_A" })
    }

    @Test
    fun removeTrackFromPlaylist_deletesTrackAndPreservesRemainingOrder() {
        val dao = MockPlaylistDao()
        val playlistId = 5L
        dao.addTracksToPlaylist(playlistId, listOf("track_1", "track_2", "track_3"))

        dao.removeTrackFromPlaylist(playlistId, "track_2")

        val ordered = dao.getPlaylistVideoIdsOrdered(playlistId)
        assertEquals(listOf("track_1", "track_3"), ordered)
    }

    @Test
    fun addTrackToPlaylist_500SequentialAdds_maintainsStrictLifoArrivalOrder() {
        val dao = MockPlaylistDao()
        val playlistId = 6L

        (1..500).forEach { i ->
            dao.addTrackToPlaylist(playlistId, "vid_$i")
        }

        val ordered = dao.getPlaylistVideoIdsOrdered(playlistId)
        assertEquals(500, ordered.size)
        // vid_500 was added most recently, must be at index 0
        assertEquals("vid_500", ordered[0])
        assertEquals("vid_499", ordered[1])
        assertEquals("vid_1", ordered[499])
    }
}
