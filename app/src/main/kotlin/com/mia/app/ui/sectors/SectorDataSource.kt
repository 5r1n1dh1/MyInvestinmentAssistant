package com.mia.app.ui.sectors

import com.mia.app.data.android.repo.DataResult
import com.mia.app.domain.sectors.SectorComparison
import java.time.LocalDate

/**
 * The Sector screen's entire view of the outside world (planv2 §W3).
 *
 * ### Why it returns the `:domain` object rather than an `:app` mirror of it
 * Most screens in this app re-declare their shapes in `:app` so a screen's copy and the engine's
 * copy cannot drift. This one deliberately does not. `SectorComparison` is not a rendering of a
 * computation — it *is* the computation, assembled by `SectorComparisonBuilder`, and every rule
 * that keeps a fabricated median off the screen lives inside it: the subject is excluded from the
 * peer group, the sample floor refuses a value, §7.3 decides before any data is consulted, and
 * there is no difference or percentile field to render. A parallel `:app` copy would be a second
 * thing to keep those properties in, and the whole risk of this feature is a second, weaker
 * implementation of the sample rule.
 *
 * What this interface adds is the one thing `:domain` cannot decide: what a symbol the device has
 * never fetched means. That comes back as a typed [DataResult] failure with a sentence attached,
 * so the screen has an explained empty state rather than an empty table.
 *
 * ### Contract
 * * `suspend`, already off the main thread.
 * * A symbol with no stored page is a **failure with a reason**, not an empty comparison. Returning
 *   an empty comparison would render as "this company has no sector", which is a different and
 *   wrong statement: the company may have a sector; this device has never looked.
 */
interface SectorDataSource {

    /**
     * The subject's own figures beside the sector medians computed from this device's stored peers.
     *
     * @param symbol already normalised by the caller (trimmed, upper-cased). Normalisation is the
     *   caller's so that what is stored is what the reader sees in the text field.
     * @param today the market day the freshness check is made against. Passed in rather than read
     *   from the clock so a test does not depend on when it runs.
     */
    suspend fun sectorView(symbol: String, today: LocalDate): DataResult<SectorComparison>
}
