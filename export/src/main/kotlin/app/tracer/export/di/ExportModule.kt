package app.tracer.export.di

import app.tracer.core.runtime.BundleService
import app.tracer.export.BundleServiceImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the :export implementation of [BundleService]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExportModule {
    @Binds
    abstract fun bindBundleService(impl: BundleServiceImpl): BundleService
}
