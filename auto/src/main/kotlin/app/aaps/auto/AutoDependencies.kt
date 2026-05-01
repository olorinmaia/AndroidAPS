package app.aaps.auto

import app.aaps.auto.data.AutoDataProvider
import app.aaps.core.interfaces.configuration.Config
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoDependencies {
    fun autoDataProvider(): AutoDataProvider
    fun config(): Config
}
