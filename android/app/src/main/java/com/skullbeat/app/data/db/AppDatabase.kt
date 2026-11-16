package com.skullbeat.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.skullbeat.app.domain.models.DownloadedTrack
import com.skullbeat.app.domain.models.Favorite
import com.skullbeat.app.domain.models.HistoryItem
import com.skullbeat.app.domain.models.Playlist
import com.skullbeat.app.domain.models.PlaylistTrack
import com.skullbeat.app.domain.models.QueueItem
import com.skullbeat.app.domain.models.Track

@Database(
    entities = [
        Track::class,
        Favorite::class,
        Playlist::class,
        PlaylistTrack::class,
        HistoryItem::class,
        QueueItem::class,
        DownloadedTrack::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun queueDao(): QueueDao
    abstract fun downloadedTrackDao(): DownloadedTrackDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cursor = db.query("PRAGMA table_info(playlists)")
                var hasCoverPath = false
                while (cursor.moveToNext()) {
                    val nameIdx = cursor.getColumnIndex("name")
                    if (nameIdx != -1 && cursor.getString(nameIdx) == "coverPath") {
                        hasCoverPath = true
                        break
                    }
                }
                cursor.close()
                if (!hasCoverPath) {
                    db.execSQL("ALTER TABLE playlists ADD COLUMN coverPath TEXT")
                }
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloaded_tracks` (
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `durationSec` INTEGER NOT NULL,
                        `durationFormatted` TEXT NOT NULL,
                        `thumbnailUrl` TEXT NOT NULL,
                        `localFilePath` TEXT NOT NULL,
                        `downloadedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`videoId`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
