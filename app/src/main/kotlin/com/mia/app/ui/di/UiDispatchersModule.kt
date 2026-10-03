package com.mia.app.ui.di

import com.mia.app.ui.detail.DetailBackgroundDispatcher
import com.mia.app.ui.fundamentals.FundamentalsBackgroundDispatcher
import com.mia.app.ui.search.SearchBackgroundDispatcher
import com.mia.app.ui.stocklist.StockListBackgroundDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The four screen dispatchers whose placeholder module files were deleted.
 *
 * ### Why they live here
 * The qualifier *declarations* stay where they are — `DetailBackgroundDispatcher` is declared in
 * `ui/detail/StockDetailDataSource.kt` and nothing in this file redeclares one, so the adapter and its
 * dispatcher can never disagree about which annotation they mean. Only the `@Provides` binding moved.
 *
 * It had to move. `SearchModule.kt`, `StockListModule.kt`, `StockDetailModule.kt` and
 * `FundamentalsModule.kt` each carried **both** a placeholder `@Provides` for the screen's data source
 * **and** the binding for that screen's dispatcher, and every one of those files said to delete itself
 * when the real adapter landed. Deleting the file without moving the binding would leave
 * `SearchViewModel`, `StockListViewModel`, `StockDetailViewModel` and `FundamentalsViewModel` with no
 * dispatcher at all — a missing binding is a compile error that blocks every screen, which is the very
 * failure the placeholders existed to avoid.
 *
 * ### Why these four pools, restated from the bindings they replace
 * | qualifier | pool | why |
 * |---|---|---|
 * | [SearchBackgroundDispatcher] | `Default` | a 2,593-row CSV parse plus a linear scan — CPU work with no IO (plan.md §9 requirement 5, plan.md §12's memory target). |
 * | [FundamentalsBackgroundDispatcher] | `Default` | a dozen `RatioComputation` calls, two of which walk ten years of annual columns, then a sort — CPU work. |
 * | [StockListBackgroundDispatcher] | `IO` | Room queries over the tracked symbols and their last bars — IO, with no CPU-bound pass to justify a CPU pool. |
 * | [DetailBackgroundDispatcher] | `IO` | four cached fields: a symbol-master row and a candle row. |
 *
 * The reasoning is copied from the four declarations verbatim rather than re-derived, because the
 * qualifier's own KDoc is where a reviewer will look for it and two versions of the same reasoning is
 * two things that can drift.
 *
 * ### What is deliberately *not* here
 * [com.mia.app.ui.technicals.TechnicalsBackgroundDispatcher] and
 * [com.mia.app.work.MiaBackgroundDispatcher] keep their bindings in `TechnicalsDispatchers.kt` and
 * `MiaDispatchers.kt`. Neither file was a placeholder, neither was deleted, and moving a working
 * binding to make this file tidier would be churn.
 */
@Module
@InstallIn(SingletonComponent::class)
object UiDispatchersModule {

    @Provides
    @SearchBackgroundDispatcher
    fun provideSearchBackgroundDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @FundamentalsBackgroundDispatcher
    fun provideFundamentalsBackgroundDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @StockListBackgroundDispatcher
    fun provideStockListBackgroundDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DetailBackgroundDispatcher
    fun provideDetailBackgroundDispatcher(): CoroutineDispatcher = Dispatchers.IO
}