package com.lumovault.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingsDao {
    /** Null until the first preference is written; the repository supplies the product default. */
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observe(): Flow<AppSettingsEntity?>

    @Upsert
    suspend fun upsert(settings: AppSettingsEntity)
}
