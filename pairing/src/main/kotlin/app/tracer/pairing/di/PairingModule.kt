package app.tracer.pairing.di

import app.tracer.core.runtime.PairingService
import app.tracer.pairing.PairingServiceImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the :pairing implementation of [PairingService]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PairingModule {
    @Binds
    abstract fun bindPairingService(impl: PairingServiceImpl): PairingService
}
