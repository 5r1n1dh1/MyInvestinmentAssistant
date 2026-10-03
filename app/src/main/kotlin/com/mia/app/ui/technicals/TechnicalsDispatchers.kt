package com.mia.app.ui.technicals

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/**
 * The dispatcher the Technicals tab's heavy work runs on.
 *
 * ### Why the qualifier is declared here and not borrowed
 * `:android-data` has its own `@IoDispatcher` binding. Borrowing it would make this file depend on a
 * symbol in a module that is being written in parallel and that cannot be compiled on this machine,
 * and the tab's work is not IO anyway: `IndicatorSnapshot.of` is fifteen passes over a
 * 500-element array with no IO at all. That is CPU work, so [Dispatchers.Default] is both the
 * honest and the correct pool — an unbounded IO pool would let fifteen CPU-bound passes occupy
 * every IO thread.
 *
 * ### How to collapse the duplication later
 * If the orchestrator prefers one qualifier app-wide, delete this file and this binding, add
 * `:android-data`'s `@IoDispatcher` to `TechnicalsViewModel`'s constructor, and delete nothing else:
 * the annotation is the only coupling.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TechnicalsBackgroundDispatcher

/**
 * Binds [TechnicalsBackgroundDispatcher] to [Dispatchers.Default].
 *
 * Not bounded on purpose, the same reasoning `:android-data`'s dispatcher module gives: the load is a
 * single `withContext` around one symbol's indicators, so the pool size is naturally limited by the
 * number of concurrent tab loads (one per visible screen), not by anything here.
 */
@Module
@InstallIn(SingletonComponent::class)
object TechnicalsDispatchersModule {

    @Provides
    @TechnicalsBackgroundDispatcher
    fun provideTechnicalsBackgroundDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
