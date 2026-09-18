package com.lumovault.lumovault.core.di

import android.content.Context
import com.lumovault.lumovault.core.database.AppDatabase
import com.lumovault.lumovault.core.database.dao.AlbumDao
import com.lumovault.lumovault.core.database.dao.FaceDao
import com.lumovault.lumovault.core.database.dao.MediaDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.create(context)

    @Provides
    fun provideMediaDao(db: AppDatabase): MediaDao = db.mediaDao()

    @Provides
    fun provideFaceDao(db: AppDatabase): FaceDao = db.faceDao()

    @Provides
    fun provideAlbumDao(db: AppDatabase): AlbumDao = db.albumDao()
}
