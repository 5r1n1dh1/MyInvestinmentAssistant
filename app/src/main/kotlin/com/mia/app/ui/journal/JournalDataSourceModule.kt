package com.mia.app.ui.journal

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The journal screen's data-source binding, in its own module.
 *
 * ### Why not a `@Binds` line in `ui/di/UiDataSourceModule.kt`
 * That file is where every other screen's seam is satisfied, and it is **not** this wave's file to
 * edit: another workstream owns it, and a conflicting edit costs the whole branch. A module per
 * addition compiles to the same Hilt graph, so the trade is one extra file against a merge conflict.
 * A follow-up that owns `ui/di/` can move this line and delete this file; the binding is identical.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class JournalDataSourceModule {

    @Binds
    @Singleton
    abstract fun bindJournalDataSource(impl: RealJournalDataSource): JournalDataSource
}