package com.freepi.lk21.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchProgressDao {

    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE completed = 0 AND positionMs > 0 ORDER BY updatedAt DESC")
    fun observeContinueWatching(): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE completed = 1 ORDER BY updatedAt DESC")
    fun observeCompleted(): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE slug = :slug LIMIT 1")
    suspend fun get(slug: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE slug = :slug LIMIT 1")
    fun observe(slug: String): Flow<WatchProgressEntity?>

    @Upsert
    suspend fun upsert(entry: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE slug = :slug")
    suspend fun delete(slug: String)

    @Query("DELETE FROM watch_progress")
    suspend fun clear()
}

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE slug = :slug LIMIT 1")
    fun observe(slug: String): Flow<FavoriteEntity?>

    @Query("SELECT * FROM favorites WHERE slug = :slug LIMIT 1")
    suspend fun get(slug: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE slug = :slug")
    suspend fun delete(slug: String)
}

@Dao
interface MovieCacheDao {

    @Query("SELECT * FROM movie_cache WHERE slug = :slug LIMIT 1")
    suspend fun get(slug: String): MovieCacheEntity?

    @Upsert
    suspend fun upsert(entry: MovieCacheEntity)
}
