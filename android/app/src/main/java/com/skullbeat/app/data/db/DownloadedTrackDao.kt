package com.skullbeat.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.skullbeat.app.domain.models.DownloadedTrack
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadedTrack(track: DownloadedTrack)

    @Query("DELETE FROM downloaded_tracks WHERE videoId = :videoId")
    suspend fun deleteDownloadedTrack(videoId: String)

    @Query("SELECT * FROM downloaded_tracks ORDER BY downloadedAt DESC")
    fun getAllDownloadedTracksFlow(): Flow<List<DownloadedTrack>>

    @Query("SELECT * FROM downloaded_tracks ORDER BY downloadedAt DESC")
    suspend fun getAllDownloadedTracks(): List<DownloadedTrack>

    @Query("SELECT * FROM downloaded_tracks WHERE videoId = :videoId LIMIT 1")
    suspend fun getDownloadedTrack(videoId: String): DownloadedTrack?
}
