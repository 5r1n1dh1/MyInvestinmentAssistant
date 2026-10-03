package com.mia.app.work

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/**
 * The dispatcher the Patterns, Accuracy and Settings screens do their heavy work on.
 *
 * ### Why this lives in `com.mia.app.work` and is shared
 * `ui/technicals` declares its own `[com.mia.app.ui.technicals.TechnicalsBackgroundDispatcher]`,
 * and that file is not mine to edit. Rather than declare three more near-identical qualifiers, the
 * screens this module owns share one: all three do the same kind of work — a detector or scanner
 * pass, or a set of folds over stored rows — and all three are CPU-bound, not IO-bound.
 *
 * ### Why `Dispatchers.Default` and not `Dispatchers.IO`
 * `PatternEngine.run` is a zig-zag pass plus a fifteen-indicator snapshot over up to 500 bars, with
 * no IO at all. Putting that on an unbounded IO pool would let several CPU-bound scans occupy every
 * IO thread, which is the opposite of what plan.md §12's 2 GB-RAM target needs. The repository reads
 * that feed it are already on their own IO dispatcher.
 *
 * ### If the orchestrator wants one app-wide qualifier
 * Delete this file and this binding, add `:android-data`'s `@IoDispatcher` to the three ViewModel
 * constructors, and change nothing else: the annotation is the only coupling.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MiaBackgroundDispatcher

/**
 * Binds [MiaBackgroundDispatcher] to [Dispatchers.Default].
 *
 * Unbounded on purpose, for the same reason `:android-data`'s dispatcher module is: a load is one
 * `withContext` around one screen's work, so concurrency is bounded by how many screens are open,
 * not by anything configured here.
 */
@Module
@InstallIn(SingletonComponent::class)
object MiaDispatchersModule {

    @Provides
    @MiaBackgroundDispatcher
    fun provideMiaBackgroundDispatcher(): CoroutineDispatcher = Dispatchers.Default
}