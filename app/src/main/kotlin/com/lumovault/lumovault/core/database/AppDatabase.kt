package com.lumovault.lumovault.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lumovault.lumovault.core.database.converter.Converters
import com.lumovault.lumovault.core.database.dao.AlbumDao
import com.lumovault.lumovault.core.database.dao.FaceDao
import com.lumovault.lumovault.core.database.dao.MediaDao
import com.lumovault.lumovault.core.database.entity.AlbumEntity
import com.lumovault.lumovault.core.database.entity.AlbumItemEntity
import com.lumovault.lumovault.core.database.entity.FaceEntity
import com.lumovault.lumovault.core.database.entity.FacePersonEntity
import com.lumovault.lumovault.core.database.entity.FaceScanEntity
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.core.database.entity.PersonEntity

@Database(
    entities = [
        MediaItemEntity::class,
        FaceEntity::class,
        PersonEntity::class,
        FacePersonEntity::class,
        FaceScanEntity::class,
        AlbumEntity::class,
        AlbumItemEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun faceDao(): FaceDao
    abstract fun albumDao(): AlbumDao

    companion object {
        const val DB_NAME = "lumovault.db"

        /**
         * Migration from v1 to v2: no schema change needed (v1 was the
         * initial Kotlin schema). This migration exists so Room has an
         * explicit path and doesn't trigger destructive fallback on upgrade
         * from a v1 Kotlin install.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v1 and v2 share the same schema; bump only so the
                // destructive-fallback guard is no longer the upgrade path.
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
    }
}
