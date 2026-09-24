package com.lumovault.app.data.local.cloud

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudMediaDao {
    /**
     * Windowed like the local timeline: a channel can hold tens of thousands of messages, and the
     * Cloud screen must not load them all to draw a screen.
     */
    @Query("SELECT * FROM cloud_media ORDER BY date_seconds DESC, message_id DESC LIMIT :limit")
    fun observeWindow(limit: Int): Flow<List<CloudMediaEntity>>

    @Query("SELECT COUNT(*) FROM cloud_media")
    fun observeCount(): Flow<Int>

    @Query("SELECT media_type AS mediaType, COUNT(*) AS itemCount FROM cloud_media GROUP BY media_type")
    fun observeTypeCounts(): Flow<List<CloudTypeCountRow>>

    @Query("SELECT COUNT(*) FROM cloud_media")
    suspend fun currentCount(): Int

    @Upsert
    suspend fun upsertAll(items: List<CloudMediaEntity>)

    @Query("DELETE FROM cloud_media WHERE last_seen_scan_id < :scanId")
    suspend fun pruneBefore(scanId: Long): Int

    /**
     * Only for an account switch, where the whole index belongs to a different user. A failed or
     * partial scan never calls this; pruning by scan id does.
     */
    @Query("DELETE FROM cloud_media")
    suspend fun clear()
}

data class CloudTypeCountRow(
    val mediaType: String,
    val itemCount: Int,
)

@Dao
interface CloudChannelDao {
    // No Kotlin default arguments: Room's generated implementation of a defaulted parameter is a
    // known rough edge, and the one caller can pass the singleton id itself.
    @Query("SELECT * FROM cloud_channel WHERE id = :id LIMIT 1")
    suspend fun row(id: Int): CloudChannelEntity?

    @Query("SELECT * FROM cloud_channel WHERE id = :id LIMIT 1")
    fun observeRow(id: Int): Flow<CloudChannelEntity?>

    @Upsert
    suspend fun upsert(row: CloudChannelEntity)

    @Query("DELETE FROM cloud_channel")
    suspend fun clear()
}
