package com.mia.app.ui.di

import com.mia.app.data.android.repo.AccuracyRepository
import com.mia.app.data.android.repo.DataFailure
import com.mia.app.domain.model.HitRate

/**
 * Turns the stored track record into the [HitRate] each tab renders.
 *
 * ### Why it is shared by three adapters
 * decision D6 asks for the figure **per stock and per pattern**, and the Technicals tab, the
 * Patterns tab and the share brief all need it. Three private copies would be three chances to
 * quietly drop the withholding rule, and the rule is the whole point: `HitRate.label()` is what
 * hides a percentage below 30 samples, and it only hides it if a `HitRate` is built rather than a
 * raw `Double`. So the conversion is built once and the number is *never* computed here.
 *
 * ### `null` versus a withheld figure — the distinction the screens depend on
 * * `null` means **"this symbol has never been tracked"**. There is no record, so there is nothing
 *   to show and nothing to withhold.
 * * a [HitRate] with `samples = 0` means **"tracked, no settled case yet"**, which is a different
 *   fact and renders through `HitRate.label()` as "Hit rate withheld — 0/30 samples".
 *
 * The two are separated by asking whether the symbol appears in `AccuracyReport.perSymbol` at all,
 * which registers a group for every tracked outcome — resolved or not — so a symbol that only has
 * open outcomes is still recognised as tracked.
 *
 * @param onFailure builds the calling screen's typed exception. Injected rather than imported so
 *   each adapter keeps its own exception type (`TechnicalsUnavailableException`,
 *   `PatternsUnavailableException`, `SettingsUnavailableException`) and the mapping stays visible
 *   at the call site.
 */
internal class TrackRecordReader(
    private val accuracyRepository: AccuracyRepository,
    private val onFailure: (DataFailure) -> RuntimeException,
) {

    /**
     * Per stock **and** per pattern when [patternName] is given; the stock's all-pattern figure when
     * it is `null`.
     */
    suspend fun hitRate(symbol: String, patternName: String?): HitRate? {
        // Read once and reused: `report()` folds the whole `outcomes` table in memory, and the two
        // branches below both need it, so a second call would be a second table read for the same
        // answer.
        val report = accuracyRepository.report().valueOrNull() ?: return null
        if (report.perSymbol.none { it.symbol == symbol }) return null

        if (patternName == null) {
            // `perSymbol` is already the stock's all-pattern figure, grouped and sorted by the
            // domain's own `HitRateCalculator`. No aggregation happens here.
            return report.perSymbol.firstOrNull { it.symbol == symbol }
        }

        val result = accuracyRepository.trackRecord(symbol, patternName)
        val context = result.valueOrNull() ?: throw onFailure(result.failureOrNull())
        return HitRate(
            symbol = symbol,
            patternName = patternName,
            hits = context.priorHits,
            samples = context.priorSamples,
        )
    }
}