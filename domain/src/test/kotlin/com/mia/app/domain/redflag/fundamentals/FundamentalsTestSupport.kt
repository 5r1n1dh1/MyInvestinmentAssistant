package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.QuarterRecord
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.redflag.RedFlagContext
import java.time.LocalDate

/**
 * Contexts built from the **real captured fixtures**, plus the hand-built contexts the "fires"
 * cases need.
 *
 * ### Where the golden numbers come from
 * Every figure in [Golden] was read out of `data/src/test/resources/fixtures/{RELIANCE,TCS,
 * HDFCBANK,TITAN}.html`, captured 2026-10-02, and is reproduced here the way
 * `DerivedMetrics` would hand it to `:domain`:
 *
 * * **annual columns only** — the `TTM` column is dropped, exactly as
 *   `DerivedMetrics.series` drops it, because a trailing-twelve-months figure is not a fiscal
 *   year and must not sit beside twelve annual ones;
 * * `CFO/OP` divided by 100, because the page prints a percentage and `cfoToOpByYear` holds a
 *   ratio;
 * * `equityByYear` = `Equity Capital + Reserves`, because the page has no single net-worth row;
 * * `ebitByYear` = `Operating Profit + Depreciation`, because the page has no EBIT row.
 *
 * The as-of date is the **capture date, 2026-10-01**, not a fiscal year end: `top-ratios` is a
 * live scrape value with no publication stamp (spec §11 rule 9), so the P/E and market cap in
 * [Golden.relianceRatios] and friends belong to that date.
 *
 * ### Sector choices are the ones the resolver would make
 * RELIANCE is [Sector.OTHER] — "Oil & Gas" matches no keyword in `SectorResolver`'s table, and
 * plan.md §7.1 says default to OTHER and never guess. TCS is [Sector.IT] and TITAN is
 * [Sector.DURABLES] (both match). HDFCBANK is [Sector.BANKING], and its published statements are
 * bank-shaped: no `Operating Profit` row at all, so `ebitByYear` is **empty**, which is itself
 * one of the cases these rules have to survive.
 */
internal object Golden {

    val CAPTURE_DATE: LocalDate = LocalDate.of(2026, 10, 1)

    // ------------------------------------------------------------------ RELIANCE

    /**
     * RELIANCE FY2026, the values the brief pins: CFO `192,113`, PAT `95,754`, borrowings
     * `402,962`, equity `13,532 + 890,498 = 904,030` ⇒ D/E `0.4457`; EBIT
     * `179,065 + 57,688 = 236,753` over interest `27,061` ⇒ coverage `8.7489`; other income
     * `28,846` over PAT `95,754` ⇒ `30.13 %`; P/E `21.2`, ROCE `10.3`, ROE `8.91`, market cap
     * `15,80,194`.
     */
    val relianceDerived = CashFlowDerived(
        cfoByYear = mapOf(
            2015 to 34374.0, 2016 to 38134.0, 2017 to 49550.0, 2018 to 71459.0, 2019 to 42346.0,
            2020 to 94877.0, 2021 to 26958.0, 2022 to 110654.0, 2023 to 115032.0, 2024 to 158788.0,
            2025 to 178703.0, 2026 to 192113.0,
        ),
        patByYear = mapOf(
            2015 to 23640.0, 2016 to 29861.0, 2017 to 29833.0, 2018 to 36080.0, 2019 to 39837.0,
            2020 to 39880.0, 2021 to 53739.0, 2022 to 67845.0, 2023 to 74088.0, 2024 to 79020.0,
            2025 to 81309.0, 2026 to 95754.0,
        ),
        fcfByYear = mapOf(
            2015 to -28588.0, 2016 to -8420.0, 2017 to -27077.0, 2018 to -1495.0, 2019 to -50431.0,
            2020 to 19324.0, 2021 to -76560.0, 2022 to 13646.0, 2023 to -16770.0, 2024 to 21212.0,
            2025 to 41079.0, 2026 to 70023.0,
        ),
        cfoToOpByYear = mapOf(
            2015 to 1.09, 2016 to 1.12, 2017 to 1.29, 2018 to 1.26, 2019 to 0.65, 2020 to 1.16,
            2021 to 0.37, 2022 to 1.05, 2023 to 0.85, 2024 to 1.05, 2025 to 1.15, 2026 to 1.13,
        ),
        revenueByYear = mapOf(
            2015 to 374372.0, 2016 to 272583.0, 2017 to 303954.0, 2018 to 390823.0,
            2019 to 568337.0, 2020 to 596679.0, 2021 to 466307.0, 2022 to 694673.0,
            2023 to 876396.0, 2024 to 899041.0, 2025 to 962820.0, 2026 to 1055780.0,
        ),
        otherIncomeByYear = mapOf(
            2015 to 8528.0, 2016 to 12212.0, 2017 to 9222.0, 2018 to 9869.0, 2019 to 8406.0,
            2020 to 8570.0, 2021 to 22432.0, 2022 to 19600.0, 2023 to 12020.0, 2024 to 15792.0,
            2025 to 17824.0, 2026 to 28846.0,
        ),
        totalAssetsByYear = mapOf(
            2015 to 504486.0, 2016 to 598997.0, 2017 to 706802.0, 2018 to 811273.0,
            2019 to 997630.0, 2020 to 1163015.0, 2021 to 1320065.0, 2022 to 1498622.0,
            2023 to 1605882.0, 2024 to 1755048.0, 2025 to 1949713.0, 2026 to 2177546.0,
        ),
        debtByYear = mapOf(
            2015 to 168251.0, 2016 to 194714.0, 2017 to 217475.0, 2018 to 239843.0,
            2019 to 307714.0, 2020 to 355133.0, 2021 to 278962.0, 2022 to 319158.0,
            2023 to 451664.0, 2024 to 350719.0, 2025 to 374313.0, 2026 to 402962.0,
        ),
        equityByYear = mapOf(
            2015 to 218499.0, 2016 to 231556.0, 2017 to 263709.0, 2018 to 293506.0,
            2019 to 387112.0, 2020 to 449166.0, 2021 to 700172.0, 2022 to 779485.0,
            2023 to 715872.0, 2024 to 793481.0, 2025 to 843200.0, 2026 to 904030.0,
        ),
        interestByYear = mapOf(
            2015 to 3316.0, 2016 to 3691.0, 2017 to 3849.0, 2018 to 8052.0, 2019 to 16495.0,
            2020 to 22027.0, 2021 to 21189.0, 2022 to 14584.0, 2023 to 19571.0, 2024 to 23118.0,
            2025 to 24269.0, 2026 to 27061.0,
        ),
        ebitByYear = mapOf(
            2015 to 48996.0, 2016 to 53346.0, 2017 to 57953.0, 2018 to 81021.0, 2019 to 105184.0,
            2020 to 111469.0, 2021 to 110572.0, 2022 to 172101.0, 2023 to 202801.0,
            2024 to 213330.0, 2025 to 218734.0, 2026 to 236753.0,
        ),
        sharesOutstanding = 13_532.0 * 1e7 / 10.0,
        promoterPledgePercent = null,
    )

    val relianceRatios = Ratios(
        symbol = "RELIANCE",
        sector = Sector.OTHER,
        asOf = CAPTURE_DATE,
        source = Source.SCREENER_IN,
        confidence = Confidence.SINGLE,
        roce = 10.3,
        roe = 8.91,
        operatingMargin = 17.0,
        fcf = 70023.0,
        pat = 95754.0,
        cfoToOp = 1.13,
        pe = 21.2,
        pb = 1.75,
        dividendYield = 0.51,
        marketCap = 1_580_194.0,
    )

    /** The `ratios` card: debtor days `16 → 20`, inventory days `85 → 88`, ROCE flat at 10. */
    val relianceCard = RatiosCardSeries(
        debtorDays = mapOf(
            2015 to 5.0, 2016 to 6.0, 2017 to 10.0, 2018 to 16.0, 2019 to 19.0, 2020 to 12.0,
            2021 to 15.0, 2022 to 12.0, 2023 to 12.0, 2024 to 13.0, 2025 to 16.0, 2026 to 20.0,
        ),
        inventoryDays = mapOf(
            2015 to 66.0, 2016 to 90.0, 2017 to 84.0, 2018 to 83.0, 2019 to 63.0, 2020 to 67.0,
            2021 to 102.0, 2022 to 83.0, 2023 to 87.0, 2024 to 95.0, 2025 to 85.0, 2026 to 88.0,
        ),
        daysPayable = mapOf(
            2015 to 74.0, 2016 to 117.0, 2017 to 132.0, 2018 to 146.0, 2019 to 100.0,
            2020 to 87.0, 2021 to 136.0, 2022 to 123.0, 2023 to 91.0, 2024 to 111.0,
            2025 to 108.0, 2026 to 84.0,
        ),
        rocePercent = mapOf(
            2015 to 9.0, 2016 to 10.0, 2017 to 10.0, 2018 to 11.0, 2019 to 12.0, 2020 to 11.0,
            2021 to 8.0, 2022 to 8.0, 2023 to 9.0, 2024 to 10.0, 2025 to 10.0, 2026 to 10.0,
        ),
    )

    // ----------------------------------------------------------------------- TCS

    /** TCS FY2026: EBIT `72,398 + 5,560 = 77,958` over interest `1,227` ⇒ coverage `63.5354`. */
    val tcsDerived = CashFlowDerived(
        cfoByYear = mapOf(
            2015 to 19369.0, 2016 to 19109.0, 2017 to 25223.0, 2018 to 25067.0, 2019 to 28593.0,
            2020 to 32369.0, 2021 to 38802.0, 2022 to 39949.0, 2023 to 41965.0, 2024 to 44338.0,
            2025 to 48908.0, 2026 to 52094.0,
        ),
        patByYear = mapOf(
            2015 to 20060.0, 2016 to 24338.0, 2017 to 26357.0, 2018 to 25880.0, 2019 to 31562.0,
            2020 to 32447.0, 2021 to 32562.0, 2022 to 38449.0, 2023 to 42303.0, 2024 to 46099.0,
            2025 to 48797.0, 2026 to 49454.0,
        ),
        fcfByYear = mapOf(
            2015 to 16426.0, 2016 to 17144.0, 2017 to 23230.0, 2018 to 23263.0, 2019 to 26461.0,
            2020 to 29281.0, 2021 to 35663.0, 2022 to 36985.0, 2023 to 38902.0, 2024 to 41688.0,
            2025 to 44994.0, 2026 to 48013.0,
        ),
        cfoToOpByYear = mapOf(
            2015 to 1.10, 2016 to 0.87, 2017 to 1.03, 2018 to 1.00, 2019 to 0.98, 2020 to 0.91,
            2021 to 1.03, 2022 to 0.97, 2023 to 0.93, 2024 to 0.88, 2025 to 0.96, 2026 to 0.92,
        ),
        revenueByYear = mapOf(
            2015 to 94648.0, 2016 to 108646.0, 2017 to 117966.0, 2018 to 123104.0,
            2019 to 146463.0, 2020 to 156949.0, 2021 to 164177.0, 2022 to 191754.0,
            2023 to 225458.0, 2024 to 240893.0, 2025 to 255324.0, 2026 to 267021.0,
        ),
        // TCS publishes a *negative* other income in FY2026, which is why the flag is quiet.
        otherIncomeByYear = mapOf(
            2015 to 3720.0, 2016 to 3084.0, 2017 to 4221.0, 2018 to 3642.0, 2019 to 4311.0,
            2020 to 4592.0, 2021 to 1916.0, 2022 to 4018.0, 2023 to 3449.0, 2024 to 3464.0,
            2025 to 3962.0, 2026 to -124.0,
        ),
        debtByYear = mapOf(
            2015 to 358.0, 2016 to 245.0, 2017 to 289.0, 2018 to 247.0, 2019 to 62.0,
            2020 to 8174.0, 2021 to 7795.0, 2022 to 7818.0, 2023 to 7688.0, 2024 to 8021.0,
            2025 to 9392.0, 2026 to 11283.0,
        ),
        equityByYear = mapOf(
            2015 to 50635.0, 2016 to 71072.0, 2017 to 86214.0, 2018 to 85128.0, 2019 to 89446.0,
            2020 to 84126.0, 2021 to 86433.0, 2022 to 89139.0, 2023 to 90424.0, 2024 to 90489.0,
            2025 to 94756.0, 2026 to 107240.0,
        ),
        interestByYear = mapOf(
            2015 to 104.0, 2016 to 33.0, 2017 to 32.0, 2018 to 52.0, 2019 to 198.0, 2020 to 924.0,
            2021 to 637.0, 2022 to 784.0, 2023 to 779.0, 2024 to 778.0, 2025 to 796.0,
            2026 to 1227.0,
        ),
        ebitByYear = mapOf(
            2015 to 26281.0, 2016 to 32565.0, 2017 to 34298.0, 2018 to 34530.0, 2019 to 41562.0,
            2020 to 45638.0, 2021 to 50611.0, 2022 to 58081.0, 2023 to 64281.0, 2024 to 69281.0,
            2025 to 72649.0, 2026 to 77958.0,
        ),
        promoterPledgePercent = null,
    )

    val tcsRatios = Ratios(
        symbol = "TCS",
        sector = Sector.IT,
        asOf = CAPTURE_DATE,
        source = Source.SCREENER_IN,
        confidence = Confidence.SINGLE,
        roce = 63.0,
        roe = 51.8,
        fcf = 48013.0,
        pat = 49454.0,
        cfoToOp = 0.92,
        pe = 14.0,
        dividendYield = 3.08,
        marketCap = 750_753.0,
    )

    /**
     * TCS's `ratios` card has debtor days and ROCE but **no inventory days and no days
     * payable** — a services company publishes empty cells there. That is a real
     * "cannot evaluate" case, not a synthetic one.
     */
    val tcsCard = RatiosCardSeries(
        debtorDays = mapOf(
            2015 to 79.0, 2016 to 81.0, 2017 to 70.0, 2018 to 74.0, 2019 to 68.0, 2020 to 71.0,
            2021 to 67.0, 2022 to 80.0, 2023 to 81.0, 2024 to 81.0, 2025 to 84.0, 2026 to 93.0,
        ),
        rocePercent = mapOf(
            2015 to 50.0, 2016 to 51.0, 2017 to 44.0, 2018 to 39.0, 2019 to 47.0, 2020 to 47.0,
            2021 to 49.0, 2022 to 54.0, 2023 to 59.0, 2024 to 64.0, 2025 to 65.0, 2026 to 63.0,
        ),
    )

    // ------------------------------------------------------------------ HDFCBANK

    /**
     * HDFCBANK FY2026. Note `ebitByYear` is **empty**: the bank's profit-loss publishes
     * `Financing Profit`, not `Operating Profit`, so there is no EBIT to divide — which is one
     * reason interest coverage is suppressed for banks and one reason it could not be computed
     * even if it were not.
     */
    val hdfcBankDerived = CashFlowDerived(
        cfoByYear = mapOf(
            2015 to -21281.0, 2016 to -34435.0, 2017 to 17282.0, 2018 to 17214.0, 2019 to -62872.0,
            2020 to -16869.0, 2021 to 42476.0, 2022 to -11960.0, 2023 to 20814.0,
            2024 to 19069.0, 2025 to 127242.0, 2026 to 113506.0,
        ),
        patByYear = mapOf(
            2015 to 10703.0, 2016 to 12821.0, 2017 to 15317.0, 2018 to 18561.0, 2019 to 22446.0,
            2020 to 27296.0, 2021 to 31857.0, 2022 to 38151.0, 2023 to 46149.0, 2024 to 65446.0,
            2025 to 73440.0, 2026 to 79219.0,
        ),
        fcfByYear = mapOf(
            2015 to -22020.0, 2016 to -35301.0, 2017 to 16134.0, 2018 to 16377.0, 2019 to -64470.0,
            2020 to -18486.0, 2021 to 40796.0, 2022 to -14176.0, 2023 to 17390.0,
            2024 to 14882.0, 2025 to 123267.0, 2026 to 110144.0,
        ),
        cfoToOpByYear = mapOf(
            2015 to -0.45, 2016 to -0.63, 2017 to 0.51, 2018 to 0.49, 2019 to -0.72, 2020 to -0.08,
            2021 to 0.73, 2022 to 0.04, 2023 to 0.35, 2024 to 0.38, 2025 to 0.98, 2026 to 0.94,
        ),
        revenueByYear = mapOf(
            2015 to 50666.0, 2016 to 63162.0, 2017 to 73271.0, 2018 to 85288.0, 2019 to 105161.0,
            2020 to 122189.0, 2021 to 128552.0, 2022 to 135936.0, 2023 to 170754.0,
            2024 to 283649.0, 2025 to 336367.0, 2026 to 348615.0,
        ),
        // 146,848 / 79,219 = 185 %: the single largest other-income share in the fixtures, and
        // the reason the flag is suppressed for banks rather than fired.
        otherIncomeByYear = mapOf(
            2015 to 9546.0, 2016 to 11212.0, 2017 to 12905.0, 2018 to 16057.0, 2019 to 18947.0,
            2020 to 24879.0, 2021 to 27333.0, 2022 to 31759.0, 2023 to 33912.0, 2024 to 124346.0,
            2025 to 134548.0, 2026 to 146848.0,
        ),
        totalAssetsByYear = mapOf(
            2015 to 607097.0, 2016 to 762212.0, 2017 to 892344.0, 2018 to 1103186.0,
            2019 to 1292806.0, 2020 to 1580830.0, 2021 to 1799507.0, 2022 to 2122934.0,
            2023 to 2530432.0, 2024 to 4030194.0, 2025 to 4392110.0, 2026 to 4907701.0,
        ),
        debtByYear = mapOf(
            2015 to 59478.0, 2016 to 103714.0, 2017 to 98416.0, 2018 to 156442.0, 2019 to 157733.0,
            2020 to 186834.0, 2021 to 177697.0, 2022 to 226966.0, 2023 to 256549.0,
            2024 to 730615.0, 2025 to 634606.0, 2026 to 588485.0,
        ),
        equityByYear = mapOf(
            2015 to 63154.0, 2016 to 74304.0, 2017 to 91794.0, 2018 to 109599.0, 2019 to 153673.0,
            2020 to 176358.0, 2021 to 209810.0, 2022 to 247327.0, 2023 to 289438.0,
            2024 to 456396.0, 2025 to 521789.0, 2026 to 586059.0,
        ),
        interestByYear = mapOf(
            2015 to 27288.0, 2016 to 34070.0, 2017 to 38042.0, 2018 to 42381.0, 2019 to 53713.0,
            2020 to 62137.0, 2021 to 59248.0, 2022 to 58584.0, 2023 to 77780.0, 2024 to 154139.0,
            2025 to 183894.0, 2026 to 185491.0,
        ),
        ebitByYear = emptyMap(),
        promoterPledgePercent = null,
    )

    val hdfcBankRatios = Ratios(
        symbol = "HDFCBANK",
        sector = Sector.BANKING,
        asOf = CAPTURE_DATE,
        source = Source.SCREENER_IN,
        confidence = Confidence.SINGLE,
        roce = 7.02,
        roe = 13.6,
        pat = 79219.0,
        cfoToOp = 0.94,
        pe = 14.1,
        pb = 1.85,
        dividendYield = 1.80,
        marketCap = 1_111_989.0,
    )

    /** The bank's `ratios` card holds `ROE %` and nothing else (verified on the fixture). */
    val hdfcBankCard = RatiosCardSeries(
        roePercent = mapOf(
            2015 to 20.0, 2016 to 19.0, 2017 to 18.0, 2018 to 18.0, 2019 to 17.0, 2020 to 16.0,
            2021 to 16.0, 2022 to 17.0, 2023 to 17.0, 2024 to 17.0, 2025 to 14.0, 2026 to 14.0,
        ),
    )

    // --------------------------------------------------------------------- TITAN

    /** TITAN FY2026: D/E `30,621 ÷ 15,703 = 1.9501`; inventory days `217 → 222`; CFO FY2025 `-541`. */
    val titanDerived = CashFlowDerived(
        cfoByYear = mapOf(
            2015 to 503.0, 2016 to 576.0, 2017 to 1712.0, 2018 to -51.0, 2019 to 1243.0,
            2020 to -348.0, 2021 to 4139.0, 2022 to -724.0, 2023 to 1370.0, 2024 to 1695.0,
            2025 to -541.0, 2026 to 5590.0,
        ),
        patByYear = mapOf(
            2015 to 816.0, 2016 to 675.0, 2017 to 697.0, 2018 to 1102.0, 2019 to 1389.0,
            2020 to 1493.0, 2021 to 974.0, 2022 to 2198.0, 2023 to 3274.0, 2024 to 3496.0,
            2025 to 3337.0, 2026 to 5073.0,
        ),
        fcfByYear = mapOf(
            2015 to 296.0, 2016 to 324.0, 2017 to 1461.0, 2018 to -350.0, 2019 to 980.0,
            2020 to -693.0, 2021 to 4000.0, 2022 to -940.0, 2023 to 950.0, 2024 to 1024.0,
            2025 to -1011.0, 2026 to 4713.0,
        ),
        cfoToOpByYear = mapOf(
            2015 to 0.65, 2016 to 0.83, 2017 to 1.71, 2018 to 0.25, 2019 to 0.94, 2020 to 0.08,
            2021 to 2.56, 2022 to 0.02, 2023 to 0.52, 2024 to 0.54, 2025 to 0.10, 2026 to 0.88,
        ),
        revenueByYear = mapOf(
            2015 to 11913.0, 2016 to 11276.0, 2017 to 13261.0, 2018 to 16120.0,
            2019 to 19779.0, 2020 to 21052.0, 2021 to 21644.0, 2022 to 28799.0,
            2023 to 40575.0, 2024 to 51084.0, 2025 to 60456.0, 2026 to 87584.0,
        ),
        otherIncomeByYear = mapOf(
            2015 to 67.0, 2016 to 69.0, 2017 to -42.0, 2018 to 70.0, 2019 to 178.0, 2020 to 153.0,
            2021 to 180.0, 2022 to 177.0, 2023 to 306.0, 2024 to 534.0, 2025 to 487.0,
            2026 to 450.0,
        ),
        totalAssetsByYear = mapOf(
            2015 to 5868.0, 2016 to 6342.0, 2017 to 8408.0, 2018 to 9521.0, 2019 to 11710.0,
            2020 to 13544.0, 2021 to 16444.0, 2022 to 21188.0, 2023 to 27020.0, 2024 to 31547.0,
            2025 to 40645.0, 2026 to 60561.0,
        ),
        debtByYear = mapOf(
            2015 to 100.0, 2016 to 113.0, 2017 to 1882.0, 2018 to 1691.0, 2019 to 2393.0,
            2020 to 3562.0, 2021 to 5638.0, 2022 to 7275.0, 2023 to 9367.0, 2024 to 15528.0,
            2025 to 20777.0, 2026 to 30621.0,
        ),
        equityByYear = mapOf(
            2015 to 3084.0, 2016 to 3507.0, 2017 to 4233.0, 2018 to 5090.0, 2019 to 6070.0,
            2020 to 6669.0, 2021 to 7497.0, 2022 to 9303.0, 2023 to 11851.0, 2024 to 9393.0,
            2025 to 11624.0, 2026 to 15703.0,
        ),
        interestByYear = mapOf(
            2015 to 81.0, 2016 to 42.0, 2017 to 38.0, 2018 to 53.0, 2019 to 53.0, 2020 to 166.0,
            2021 to 203.0, 2022 to 218.0, 2023 to 300.0, 2024 to 619.0, 2025 to 953.0,
            2026 to 1180.0,
        ),
        ebitByYear = mapOf(
            2015 to 1243.0, 2016 to 1037.0, 2017 to 1275.0, 2018 to 1775.0, 2019 to 2157.0,
            2020 to 2811.0, 2021 to 2100.0, 2022 to 3743.0, 2023 to 5323.0, 2024 to 5876.0,
            2025 to 6387.0, 2026 to 9183.0,
        ),
        promoterPledgePercent = null,
    )

    val titanRatios = Ratios(
        symbol = "TITAN",
        sector = Sector.DURABLES,
        asOf = CAPTURE_DATE,
        source = Source.SCREENER_IN,
        confidence = Confidence.SINGLE,
        roce = 20.5,
        roe = 37.7,
        pat = 5073.0,
        cfoToOp = 0.88,
        pe = 68.7,
        pb = 25.5,
        dividendYield = 0.33,
        marketCap = 400_898.0,
    )

    /** TITAN's inventory days run 169 → 222 over twelve years; the last step is +5 days. */
    val titanCard = RatiosCardSeries(
        debtorDays = mapOf(
            2015 to 6.0, 2016 to 6.0, 2017 to 6.0, 2018 to 7.0, 2019 to 8.0, 2020 to 5.0,
            2021 to 6.0, 2022 to 7.0, 2023 to 6.0, 2024 to 7.0, 2025 to 6.0, 2026 to 4.0,
        ),
        inventoryDays = mapOf(
            2015 to 169.0, 2016 to 198.0, 2017 to 189.0, 2018 to 185.0, 2019 to 178.0,
            2020 to 195.0, 2021 to 187.0, 2022 to 230.0, 2023 to 199.0, 2024 to 176.0,
            2025 to 217.0, 2026 to 222.0,
        ),
        daysPayable = mapOf(
            2015 to 81.0, 2016 to 74.0, 2017 to 30.0, 2018 to 27.0, 2019 to 23.0, 2020 to 14.0,
            2021 to 18.0, 2022 to 22.0, 2023 to 15.0, 2024 to 13.0, 2025 to 15.0, 2026 to 15.0,
        ),
        rocePercent = mapOf(
            2015 to 35.0, 2016 to 27.0, 2017 to 23.0, 2018 to 25.0, 2019 to 26.0, 2020 to 24.0,
            2021 to 13.0, 2022 to 21.0, 2023 to 25.0, 2024 to 23.0, 2025 to 19.0, 2026 to 21.0,
        ),
    )
}

/**
 * A [RedFlagContext] with every input defaulted, so a test states only what it cares about.
 *
 * `today` is fixed at the capture date rather than `LocalDate.now()` for two reasons: a flag
 * whose wording depends on "today" would otherwise change with the calendar, and
 * `FreshnessPolicy`'s own default is the one place this codebase is allowed to reach for the
 * clock.
 */
internal fun testContext(
    symbol: String = "TESTCO",
    sector: Sector = Sector.OTHER,
    ratios: Ratios? = null,
    derived: CashFlowDerived = CashFlowDerived(),
    quarters: List<QuarterRecord> = emptyList(),
    candles: List<Candle> = emptyList(),
    today: LocalDate = Golden.CAPTURE_DATE,
): RedFlagContext = RedFlagContext(
    symbol = symbol,
    sector = sector,
    ratios = ratios ?: Ratios(
        symbol = symbol,
        sector = sector,
        asOf = Golden.CAPTURE_DATE,
        source = Source.SCREENER_IN,
        confidence = Confidence.SINGLE,
    ),
    derived = derived,
    quarters = quarters,
    candles = candles,
    today = today,
)

/**
 * RELIANCE, sector unresolved.
 *
 * The `ratios`-card series is **not** part of a context — it is a rule constructor argument —
 * so the rules that need it are constructed with [Golden.relianceCard] in the tests.
 */
internal fun relianceContext(): RedFlagContext = testContext(
    symbol = "RELIANCE",
    sector = Sector.OTHER,
    ratios = Golden.relianceRatios,
    derived = Golden.relianceDerived,
)

/** TCS, [Sector.IT], with the real `ratios` card series. */
internal fun tcsContext(): RedFlagContext = testContext(
    symbol = "TCS",
    sector = Sector.IT,
    ratios = Golden.tcsRatios,
    derived = Golden.tcsDerived,
)

/** HDFCBANK, [Sector.BANKING], with the real `ratios` card series. */
internal fun hdfcBankContext(): RedFlagContext = testContext(
    symbol = "HDFCBANK",
    sector = Sector.BANKING,
    ratios = Golden.hdfcBankRatios,
    derived = Golden.hdfcBankDerived,
)

/** TITAN, [Sector.DURABLES], with the real `ratios` card series. */
internal fun titanContext(): RedFlagContext = testContext(
    symbol = "TITAN",
    sector = Sector.DURABLES,
    ratios = Golden.titanRatios,
    derived = Golden.titanDerived,
)

/** A daily bar, for the cached-close half of the multiple-on-falling-earnings rule. */
internal fun dailyCandle(date: LocalDate, close: Double, symbol: String = "TESTCO"): Candle = Candle(
    symbol = symbol,
    timeframe = Timeframe.D1,
    date = date,
    open = close,
    high = close,
    low = close,
    close = close,
    volume = 100_000L,
)

/** A quarterly EPS observation in **calendar** quarters, matching `StatementParsers`. */
internal fun quarter(year: Int, quarter: Int, eps: Double?): QuarterRecord =
    QuarterRecord(year = year, quarter = quarter, eps = eps)