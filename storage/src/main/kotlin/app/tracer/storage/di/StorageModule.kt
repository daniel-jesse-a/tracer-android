package app.tracer.storage.di

import android.content.Context
import app.tracer.core.runtime.RunRepository
import app.tracer.core.runtime.SampleSink
import app.tracer.storage.db.TracerDatabase
import app.tracer.storage.repo.RoomRunRepository
import app.tracer.storage.writer.BatchingSampleWriter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the live [TracerDatabase] and binds the storage implementations of the core contracts. */
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    abstract fun bindSampleSink(impl: BatchingSampleWriter): SampleSink

    @Binds
    abstract fun bindRunRepository(impl: RoomRunRepository): RunRepository

    companion object {
        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): TracerDatabase = TracerDatabase.build(context)
    }
}
