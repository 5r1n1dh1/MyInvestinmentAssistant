package com.mia.app.ui.compare

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The compare screen's data-source binding, in its own module.
 *
 * ### Why not a `@Binds` line in `ui/di/UiDataSourceModule.kt`
 * That file belongs to another workstream in this wave, and a conflicting edit costs the whole
 * branch. A module per addition compiles to the same Hilt graph, so this is one extra file against a
 * merge conflict; a follow-up that owns `ui/di/` can move the line and delete this file.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CompareDataSourceModule {

    @Binds
    @Singleton
    abstract fun bindCompareDataSource(impl: RealCompareDataSource): CompareDataSource
}