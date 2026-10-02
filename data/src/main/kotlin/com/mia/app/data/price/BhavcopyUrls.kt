package com.mia.app.data.price

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * URL construction for the NSE end-of-day bhavcopy and for the legacy delivery file.
 *
 * plan.md §3.1 names `nsearchives.nseindia.com` as the bhavcopy host. It is kept as the
 * **primary** because that is the canonical, documented location, but it is **not
 * sufficient on its own**: re-verified on 2026-10-02 from the development machine, the
 * `nsearchives` host fails the TLS handshake outright (`curl` exit code 16,
 * "HTTP/2 protocol error" after the ALPN upgrade) and then times out, while
 * `archives.nseindia.com` — the same CDN namespace, same path, no auth — returns `200`
 * with a **byte-identical** 208,712-byte archive:
 *
 * ```
 * archives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_20261001_F_0000.csv.zip
 *   → 200, 208712 bytes, md5 aa906c0012ac4c7b5cb8b7fe08044500
 * nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_20261001_F_0000.csv.zip
 *   → connection failure (curl exit 16/28)
 * ```
 *
 * [candidates] therefore returns primary-first, mirror-second, and [NseBhavcopySource]
 * walks it in order. Because the two hosts serve the identical file, the mirror is not a
 * downgrade in fidelity — it is the same document from a different front door.
 */
object BhavcopyUrls {

    /** Canonical host from plan.md §3.1. Kept first so the primary source stays primary. */
    const val PRIMARY: String = "https://nsearchives.nseindia.com"

    /** Front door verified to work when [PRIMARY] fails the handshake. Same namespace. */
    const val MIRROR: String = "https://archives.nseindia.com"

    /** Path of the capital-market EOD bhavcopy on both hosts. */
    const val BHAVCOPY_PATH: String = "/content/cm/"

    /** plan.md §3.5 legacy delivery-percentage file. */
    const val LEGACY_PATH: String = "/products/content/"

    /** plan.md §3.1: the archive holds roughly 18 months of history. */
    const val LOOKBACK_DAYS: Int = 10

    private val ZIP_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
    private val LEGACY_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ROOT)

    /**
     * The archive file name for [date], e.g. `BhavCopy_NSE_CM_0_0_0_20261001_F_0000.csv.zip`.
     *
     * The literal segments (`0_0_0`, `F_0000`) are part of NSE's naming scheme and are not
     * placeholders — the file is named exactly this way for every date.
     */
    fun zipFileName(date: LocalDate): String =
        "BhavCopy_NSE_CM_0_0_0_${ZIP_STAMP.format(date)}_F_0000.csv.zip"

    /** The CSV inside [zipFileName], used only for diagnostics and tests. */
    fun csvFileName(date: LocalDate): String = zipFileName(date).removeSuffix(".zip")

    fun primary(date: LocalDate): String = PRIMARY + BHAVCOPY_PATH + zipFileName(date)

    fun mirror(date: LocalDate): String = MIRROR + BHAVCOPY_PATH + zipFileName(date)

    /**
     * Hosts to try, in order: canonical first, verified mirror second.
     *
     * Order matters for the 1-request-per-day budget in plan.md §13 (CGNAT mitigation):
     * the whole market costs two requests *only* when the primary host is broken.
     */
    fun candidates(date: LocalDate): List<String> = listOf(primary(date), mirror(date))

    /** Legacy delivery file, plan.md §3.5. Verified `200`, 401,205 bytes for 2026-10-01. */
    fun legacy(date: LocalDate): String =
        MIRROR + LEGACY_PATH + "sec_bhavdata_full_${LEGACY_STAMP.format(date)}.csv"

    /**
     * Matching filter for an archive member name.
     *
     * NSE puts the CSV at the zip root, but a member may arrive under a folder on some
     * deployments, so the directory prefix is ignored rather than assumed.
     */
    fun isCsvEntry(name: String): Boolean =
        name.substringAfterLast('/').endsWith(".csv", ignoreCase = true)
}
