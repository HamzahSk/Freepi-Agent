package rofil.movie.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    @PrimaryKey val slug: String,
    val title: String,
    val poster: String?,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val slug: String,
    val title: String,
    val poster: String?,
    val year: String?,
    val rating: String?,
    val addedAt: Long,
)

@Entity(tableName = "movie_cache")
data class MovieCacheEntity(
    @PrimaryKey val slug: String,
    val title: String,
    val poster: String?,
    val year: String?,
    val rating: String?,
    val quality: String?,
    val synopsis: String?,
    val genres: String,
    val payloadUpdatedAt: Long,
)
