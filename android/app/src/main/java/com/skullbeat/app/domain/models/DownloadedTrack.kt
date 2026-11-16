package com.skullbeat.app.domain.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_tracks")
data class DownloadedTrack(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val album: String = "Single",
    val durationSec: Long = 0L,
    val durationFormatted: String = "0:00",
    val thumbnailUrl: String = "",
    val localFilePath: String,
    val downloadedAt: Long = System.currentTimeMillis()
) {
    fun toTrack(): Track {
        return Track(
            videoId = videoId,
            title = title,
            artist = artist,
            album = album,
            durationSec = durationSec,
            durationFormatted = durationFormatted,
            thumbnailUrl = thumbnailUrl
        )
    }
}
