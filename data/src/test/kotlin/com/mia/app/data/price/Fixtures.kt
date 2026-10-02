package com.mia.app.data.price

/**
 * Real payloads captured from the live NSE endpoints, stored under
 * `data/src/test/resources/fixtures/` (see docs/CONTRACTS.md).
 *
 * Everything the parser is asserted against comes from here — no hand-written CSV stands in
 * for the real file, because the failure mode being guarded (plan.md §13: "bhavcopy
 * format/URL changes silently") is precisely a difference between a plausible fake and the
 * real bytes.
 */
internal object Fixtures {

    /** Real `archives.nseindia.com/content/cm/...20261001...zip`, 208,712 bytes. */
    val bhavcopyZip: ByteArray by lazy { bytes("bhavcopy_20261001.zip") }

    /** The archive's single member, extracted verbatim: 640,497 bytes, 3,712 instruments. */
    val bhavcopyCsv: String by lazy { text("bhavcopy_20261001.csv") }

    /** Real `sec_bhavdata_full_01102026.csv`, 401,205 bytes, 3,534 instruments. */
    val legacyDeliveryCsv: String by lazy { text("sec_bhavdata_20261001.csv") }

    fun bytes(name: String): ByteArray =
        stream(name)?.use { it.readBytes() } ?: error("missing fixture /fixtures/$name")

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    private fun stream(name: String) =
        Fixtures::class.java.getResourceAsStream("/fixtures/$name")
}
