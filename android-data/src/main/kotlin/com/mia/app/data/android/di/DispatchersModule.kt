package com.mia.app.data.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/**
 * The single dispatcher qualifier in the app.
 *
 * ### Why this exists rather than `Dispatchers.IO` inline
 * Every repository, DAO call and worker in this module must run off the main thread, and the way
 * to make that auditable is to make it injectable: a `Dispatchers.IO` literal in a repository is
 * a hidden dependency that no JVM test can replace, because `:android-data` cannot run tests on
 * this build machine at all. With the qualifier, the *shape* of the code is checkable here and
 * the substitution happens in one place.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Dispatcher bindings.
 *
 * `Dispatchers.IO` is **not** bounded here on purpose: an elastic pool is the right default for
 * a WorkManager coroutine that must not block its own thread, and the parallelism bound that
 * actually matters (plan.md §13's 2 GB-RAM budget devices) is applied where the work is
 * enumerated, by the sequential fan-out in `PatternScanWorker`.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}