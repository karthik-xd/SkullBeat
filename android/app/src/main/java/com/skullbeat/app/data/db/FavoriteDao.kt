package com.skullbeat.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.skullbeat.app.domain.models.Favorite
import com.skullbeat.app.domain.models.Track
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: Favorite)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorites(favorites: List<Favorite>)

    @Query("DELETE FROM favorites WHERE videoId = :videoId")
    suspend fun removeFavorite(videoId: String)

    @Query("SELECT COUNT(*) FROM favorites")
    suspend fun getFavoriteCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE videoId = :videoId)")
    fun isFavoriteFlow(videoId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE videoId = :videoId)")
    suspend fun isFavorite(videoId: String): Boolean

    @Query("SELECT t.* FROM tracks t INNER JOIN favorites f ON t.videoId = f.videoId ORDER BY f.addedAt DESC")
    fun getFavoriteTracksFlow(): Flow<List<Track>>

    @Query("SELECT t.* FROM tracks t INNER JOIN favorites f ON t.videoId = f.videoId ORDER BY f.addedAt DESC")
    suspend fun getFavoriteTracks(): List<Track>
}
