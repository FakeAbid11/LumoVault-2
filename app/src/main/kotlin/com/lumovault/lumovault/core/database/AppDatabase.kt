package com.lumovault.lumovault.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun faceDao(): FaceDao
    abstract fun albumDao(): AlbumDao

    companion object {
        const val DB_NAME = "lumovault.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                // Fresh app (clean break from the Flutter format); while there
                // are no users, a schema bump may simply rebuild the table.
                // Replace with explicit migrations before any release.
                .fallbackToDestructiveMigration()
                .build()
    }
}
