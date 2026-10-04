package com.mia.app.domain.pattern

import com.mia.app.domain.model.MatchScoreLabels

/**
 * The catalogue itself: the full textbook list of chart patterns, each marked with what this app
 * actually does about it.
 *
 * ### Why the catalogue is larger than the detector set
 * The 14 detectable patterns are a subset of the textbook vocabulary, and a reader asking "what
 * am I not seeing?" can only answer that question if the app is willing to name what it does not
 * look for. Every `NOT_IMPLEMENTED` row therefore states that plainly, and still teaches its
 * shape and carries a glossary entry.
 *
 * This is the mechanism that makes the catalogue honest rather than promotional. A list containing
 * only what the app can score would read as "these are the patterns that exist"; a list containing
 * everything, marked, reads as "here is the vocabulary, and here is exactly how much of it the
 * software can evaluate".
 *
 * ### Provenance and the copy rule
 * Descriptions are written from the standard chart-analysis vocabulary — the formations Edwards &
 * Magee defined and that every textbook since repeats. They describe **geometry**, and no entry
 * contains a prediction, a probability or advice; see [GlossaryEntry] for the rule and the
 * per-entry [GlossaryEntry.doesNotPredict].
 *
 * ### Ordering
 * Family, then the textbook's own rough chronology within the family. Grouping by family is what
 * makes the list teachable — reversal patterns next to each other are comparable in a way that
 * "alphabetical" or "by detector id" is not.
 */
internal object PatternCatalogData {

    private val DETECTED = DetectionStatus.DETECTED
    private val NOT_IMPLEMENTED = DetectionStatus.NOT_IMPLEMENTED

    /**
     * The 14 detectable patterns, with authored shapes.
     *
     * Shapes are authored in the shape's own frame: `x` left→right through the formation, `y`
     * bottom→top in price. They are deliberately **textbook-exact rather than flattering** — a
     * double top whose peaks are exactly level looks better on the chart than a real one, so the
     * peaks carry a small difference, matching the `DT_PEAK_SYM = 0.97` tolerance the detector
     * itself enforces. An overlay that is tidier than reality would teach the wrong tolerance.
     */
    private val DETECTABLE: List<PatternSpec> = listOf(
        // ---------------------------------------------------------------- reversal (ids 7..10)
        // The six candlestick rows that used to sit above this were moved to
        // KNOWN_NOT_EVALUATED: the detectors still exist and still run, but the catalogue no longer
        // claims the app evaluates them. See the note there.
        PatternSpec(
            name = "Head & Shoulders",
            family = PatternFamily.REVERSAL,
            detectionStatus = DETECTED,
            minBars = 20,
            shapeSummary = "Three peaks, the middle highest, over a shared neckline.",
            shape = PatternShape.Polyline(
                points = listOf(
                    NormalisedPoint(0f, 0.25f),   // left shoulder
                    NormalisedPoint(0.3f, 0.15f),  // neckline breached
                    NormalisedPoint(0.45f, 0.50f), // head
                    NormalisedPoint(0.62f, 0.15f), // neckline breached
                    NormalisedPoint(0.78f, 0.30f), // right shoulder
                    NormalisedPoint(0.88f, 0.12f), // right shoulder fails
                ),
                closed = true,
                roleLabels = listOf(
                    "Left shoulder",
                    "Neckline",
                    "Head",
                    "Neckline",
                    "Right shoulder",
                    "Break",
                ),
            ),
            glossary = GlossaryEntry(
                whatItIs = "A trend-reversal formation of three peaks above a shared support level, " +
                    "with the middle peak the highest.",
                howItLooks = "Left shoulder, a fall, a higher head, a fall, a right shoulder that " +
                    "fails to exceed the head — all three peaks above one roughly level neckline.",
                textbookSays = "Associated with a change from an uptrend toward a downtrend, " +
                    "completed when the neckline is broken.",
                doesNotPredict = "Until the neckline breaks it is an unconfirmed shape, and the " +
                    "app scores it as such. It also marks no price level and no duration: the " +
                    "projected move is a geometry-based measurement, not a target.",
            ),
        ),
        PatternSpec(
            name = "Inverse Head & Shoulders",
            family = PatternFamily.REVERSAL,
            detectionStatus = DETECTED,
            minBars = 20,
            shapeSummary = "Three troughs, the middle lowest, under a shared resistance level.",
            shape = PatternShape.Polyline(
                points = listOf(
                    NormalisedPoint(0f, 0.75f),   // left shoulder, inverted y
                    NormalisedPoint(0.3f, 0.85f),  // neckline
                    NormalisedPoint(0.45f, 0.50f), // head, the lowest point
                    NormalisedPoint(0.62f, 0.85f), // neckline
                    NormalisedPoint(0.78f, 0.70f), // right shoulder
                    NormalisedPoint(0.88f, 0.88f), // break
                ),
                closed = true,
                roleLabels = listOf(
                    "Left shoulder",
                    "Neckline",
                    "Head",
                    "Neckline",
                    "Right shoulder",
                    "Break",
                ),
            ),
            glossary = GlossaryEntry(
                whatItIs = "The head and shoulders turned upside down: three troughs below a " +
                    "shared resistance level, the middle one the deepest.",
                howItLooks = "Left shoulder, a rise, a lower head, a rise, a right shoulder that " +
                    "fails to undercut the head — all three troughs under one level.",
                textbookSays = "Associated with a change from a downtrend toward an uptrend, " +
                    "completed on the neckline break.",
                doesNotPredict = "Unconfirmed until the neckline breaks. As with its mirror it " +
                    "specifies no level and no duration.",
            ),
        ),
        PatternSpec(
            name = "Double Top",
            family = PatternFamily.REVERSAL,
            detectionStatus = DETECTED,
            minBars = 15,
            shapeSummary = "Two peaks of similar height, separated by a trough.",
            shape = PatternShape.Polyline(
                points = listOf(
                    NormalisedPoint(0.05f, 0.85f), // first peak
                    NormalisedPoint(0.38f, 0.30f), // the trough between them
                    NormalisedPoint(0.68f, 0.82f), // second peak, slightly lower
                    NormalisedPoint(0.80f, 0.70f), // failure below the first peak
                    NormalisedPoint(0.92f, 0.25f), // break of the trough
                ),
                closed = true,
                roleLabels = listOf(
                    "First peak",
                    "Trough",
                    "Second peak",
                    "Failure",
                    "Break",
                ),
            ),
            glossary = GlossaryEntry(
                whatItIs = "A reversal formation of two peaks at similar heights separated by a " +
                    "trough, with the second peak failing.",
                howItLooks = "Rise, fall to a trough, rise to roughly the first peak's height, then " +
                    "a move back down through the trough.",
                textbookSays = "Associated with a change from a rise toward a decline, completed " +
                    "on the break of the trough, which is then the pattern's resistance-turned-support.",
                doesNotPredict = "The trough is a level, not a forecast. And the two peaks need not " +
                    "be identical — the app measures their symmetry rather than demanding a perfect " +
                    "match, because the textbook's own tolerance is wide.",
            ),
        ),
        PatternSpec(
            name = "Double Bottom",
            family = PatternFamily.REVERSAL,
            detectionStatus = DETECTED,
            minBars = 15,
            shapeSummary = "Two troughs of similar depth, separated by a peak.",
            shape = PatternShape.Polyline(
                points = listOf(
                    NormalisedPoint(0.05f, 0.15f), // first trough
                    NormalisedPoint(0.38f, 0.70f), // the peak between them
                    NormalisedPoint(0.68f, 0.18f), // second trough, slightly shallower
                    NormalisedPoint(0.80f, 0.30f), // failure above the first trough
                    NormalisedPoint(0.92f, 0.75f), // break of the peak
                ),
                closed = true,
                roleLabels = listOf(
                    "First trough",
                    "Peak",
                    "Second trough",
                    "Failure",
                    "Break",
                ),
            ),
            glossary = GlossaryEntry(
                whatItIs = "The double top inverted: two troughs at similar depths separated by a " +
                    "peak, with the second trough failing.",
                howItLooks = "Fall, rise to a peak, fall to roughly the first trough's depth, then " +
                    "a move back up through the peak.",
                textbookSays = "Associated with a change from a decline toward a rise, completed on " +
                    "the break of the peak.",
                doesNotPredict = "The peak is a level, not a forecast, and the pattern names no " +
                    "duration.",
            ),
        ),

        // ------------------------------------------------------------ continuation (ids 11..14)
        PatternSpec(
            name = "Ascending Triangle",
            family = PatternFamily.CONTINUATION,
            detectionStatus = DETECTED,
            minBars = 15,
            shapeSummary = "A flat ceiling with rising lows, pressed against resistance.",
            // Two independent boundary lines, not one point list. Listing support then resistance
            // in a single list gave x = 0, 0.5, 1, 0, 1, and the draw pass joins points in list
            // order — so it would have rendered a zigzag leaping back to the left edge.
            shape = PatternShape.Boundaries(
                upper = listOf(
                    NormalisedPoint(0f, 0.80f),   // flat resistance
                    NormalisedPoint(0.5f, 0.80f),
                    NormalisedPoint(1f, 0.80f),
                ),
                lower = listOf(
                    NormalisedPoint(0f, 0.55f),   // rising support
                    NormalisedPoint(0.5f, 0.30f),
                    NormalisedPoint(1f, 0.10f),
                ),
            ),
            glossary = GlossaryEntry(
                whatItIs = "A continuation formation bounded above by a level resistance and " +
                    "below by a rising support line.",
                howItLooks = "Successive lows that are higher than the last, pressing toward a " +
                    "ceiling that does not move.",
                textbookSays = "Associated with an existing uptrend pausing and then continuing, " +
                    "with the measure of the pattern's height applied to the break.",
                doesNotPredict = "It is a statement about converging boundaries, not about the " +
                    "break's size being certain. The pattern also depends on a prior uptrend, " +
                    "which the app scores separately rather than assuming.",
            ),
        ),
        PatternSpec(
            name = "Descending Triangle",
            family = PatternFamily.CONTINUATION,
            detectionStatus = DETECTED,
            minBars = 15,
            shapeSummary = "A flat floor with falling highs, pressed against support.",
            // The exact vertical mirror of the ascending triangle: every level is 1 − y and the
            // boundaries swap. So its flat 0.80 resistance becomes a flat 0.20 support, and its
            // rising 0.55 → 0.10 support becomes a falling 0.45 → 0.90 resistance.
            shape = PatternShape.Boundaries(
                upper = listOf(
                    NormalisedPoint(0f, 0.45f),   // falling resistance
                    NormalisedPoint(0.5f, 0.70f),
                    NormalisedPoint(1f, 0.90f),
                ),
                lower = listOf(
                    NormalisedPoint(0f, 0.20f),   // flat support
                    NormalisedPoint(0.5f, 0.20f),
                    NormalisedPoint(1f, 0.20f),
                ),
            ),
            glossary = GlossaryEntry(
                whatItIs = "The ascending triangle inverted: flat support below, falling " +
                    "resistance above.",
                howItLooks = "Successive highs that are lower than the last, pressing toward a " +
                    "floor that does not move.",
                textbookSays = "Associated with an existing downtrend pausing and then continuing.",
                doesNotPredict = "As with its mirror, the converging boundaries say nothing about " +
                    "how far a break would travel.",
            ),
        ),
        PatternSpec(
            name = "Bull Flag",
            family = PatternFamily.CONTINUATION,
            detectionStatus = DETECTED,
            minBars = 10,
            shapeSummary = "A near-vertical pole, then a small downward-sloping channel.",
            // Pole and channel are separate shapes. Joining them into one point list would draw a
            // line from the pole's top to the flag's first touch — not part of the pattern, but it
            // would look plausible, which is worse than drawing nothing.
            shape = PatternShape.Composite(
                parts = listOf(
                    PatternShape.Polyline(
                        points = listOf(
                            NormalisedPoint(0.05f, 0.10f), // the pole: sharp rise
                            NormalisedPoint(0.28f, 0.78f),
                        ),
                        closed = false,
                        roleLabels = listOf("Pole base", "Pole top"),
                    ),
                    PatternShape.Boundaries(
                        upper = listOf(
                            NormalisedPoint(0.35f, 0.82f), // gently falling
                            NormalisedPoint(0.95f, 0.60f),
                        ),
                        lower = listOf(
                            NormalisedPoint(0.35f, 0.66f), // parallel
                            NormalisedPoint(0.95f, 0.44f),
                        ),
                    ),
                ),
            ),
            glossary = GlossaryEntry(
                whatItIs = "A continuation formation of a sharp near-vertical advance, a pause in " +
                    "a narrow downward-sloping channel, then a resumption.",
                howItLooks = "A steep pole, then two roughly parallel lines both falling gently, " +
                    "containing several small sessions.",
                textbookSays = "Associated with a rising trend resuming after the pause, with the " +
                    "pole's height applied to the resumption.",
                doesNotPredict = "The measured move is a geometric projection from the pole, not a " +
                    "target — the app labels it as such. And the app cannot score a flag whose pole " +
                    "has not yet formed, which is the commonest early case.",
            ),
        ),
        PatternSpec(
            name = "Bear Flag",
            family = PatternFamily.CONTINUATION,
            detectionStatus = DETECTED,
            minBars = 10,
            shapeSummary = "A near-vertical drop, then a small upward-sloping channel.",
            // The exact mirror of the bull flag. Its pole (0.10 → 0.78) reflects to 0.90 → 0.22,
            // and the channel's two lines swap: the bull flag's upper 0.82 → 0.60 becomes this
            // flag's lower 0.18 → 0.40, and its lower 0.66 → 0.44 becomes the upper 0.34 → 0.56.
            shape = PatternShape.Composite(
                parts = listOf(
                    PatternShape.Polyline(
                        points = listOf(
                            NormalisedPoint(0.05f, 0.90f), // the pole: sharp fall
                            NormalisedPoint(0.28f, 0.22f),
                        ),
                        closed = false,
                        roleLabels = listOf("Pole base", "Pole top"),
                    ),
                    PatternShape.Boundaries(
                        upper = listOf(
                            NormalisedPoint(0.35f, 0.34f), // gently rising
                            NormalisedPoint(0.95f, 0.56f),
                        ),
                        lower = listOf(
                            NormalisedPoint(0.35f, 0.18f), // parallel
                            NormalisedPoint(0.95f, 0.40f),
                        ),
                    ),
                ),
            ),
            glossary = GlossaryEntry(
                whatItIs = "The bull flag inverted: a sharp fall, a pause in a narrow " +
                    "upward-sloping channel, then a resumption.",
                howItLooks = "A steep pole down, then two roughly parallel lines both rising gently.",
                textbookSays = "Associated with a falling trend resuming after the pause.",
                doesNotPredict = "As with its mirror: a geometric projection, not a target, and " +
                    "nothing to score until the pole exists.",
            ),
        ),
    )

    /**
     * The textbook patterns this app names honestly but does not evaluate.
     *
     * Each still carries a real shape summary and a full glossary entry, because a reader asking
     * "what is a cup and handle?" should be answered. [PatternSpec.detectionStatus] says so on the
     * row rather than the row being absent.
     *
     * ### Two different reasons to be on this list
     * Most rows here are **not written yet** — the Rising Wedge has no detector at all. The six
     * candlestick rows are different: a detector exists and runs, and the row is here because
     * evaluating them would claim something the app cannot deliver. Those rows carry a
     * `detectionStatus` of `NOT_IMPLEMENTED` too, because from the reader's side the two are the
     * same fact — "the app did not evaluate this" — and pretending otherwise for a subset would
     * mean a second status whose only job was to be more accurate than the user.
     *
     * The distinction is kept in the comment above the candlestick block rather than in a new enum
     * value, because nothing downstream behaves differently on it.
     */
    private val KNOWN_NOT_EVALUATED: List<PatternSpec> = listOf(
        // ------------------------------------------------------------------------- reversal
        PatternSpec(
            name = "Triple Top",
            family = PatternFamily.REVERSAL,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 25,
            shapeSummary = "Three peaks of similar height, which the third confirms.",
            glossary = GlossaryEntry(
                whatItIs = "A reversal formation of three peaks at similar heights, the third " +
                    "confirming the earlier double top.",
                howItLooks = "Rise, fall, rise, fall, rise, then a move down through both troughs.",
                textbookSays = "Associated with a change from a rise toward a decline.",
                doesNotPredict = "Three similar peaks make the pattern harder to distinguish from " +
                    "ordinary chop, and the third peak is the confirmation rather than a prediction.",
            ),
        ),
        PatternSpec(
            name = "Triple Bottom",
            family = PatternFamily.REVERSAL,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 25,
            shapeSummary = "Three troughs of similar depth, which the third confirms.",
            glossary = GlossaryEntry(
                whatItIs = "The triple top inverted: three troughs at similar depths.",
                howItLooks = "Fall, rise, fall, rise, fall, then a move up through both peaks.",
                textbookSays = "Associated with a change from a decline toward a rise.",
                doesNotPredict = "Shares the triple top's difficulty of being distinct from " +
                    "ordinary chop.",
            ),
        ),
        PatternSpec(
            name = "Rounding Bottom",
            family = PatternFamily.REVERSAL,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 30,
            shapeSummary = "A long U-shaped recovery over many sessions.",
            glossary = GlossaryEntry(
                whatItIs = "A gradual, bowl-shaped decline and recovery rather than a sharp V.",
                howItLooks = "A falling arc, a rounded base, and a rising arc of similar length.",
                textbookSays = "Associated with a change from a decline toward a rise, over a " +
                    "longer period than the other reversals.",
                doesNotPredict = "It spans many sessions, so it identifies neither a session nor a " +
                    "level where it began.",
            ),
        ),
        PatternSpec(
            name = "Rounding Top",
            family = PatternFamily.REVERSAL,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 30,
            shapeSummary = "A long dome-shaped rise and rollover.",
            glossary = GlossaryEntry(
                whatItIs = "A gradual, dome-shaped rise and rollover.",
                howItLooks = "A rising arc, a rounded top, and a falling arc.",
                textbookSays = "Associated with a change from a rise toward a decline.",
                doesNotPredict = "As with its inverse, it identifies no single decisive session.",
            ),
        ),
        PatternSpec(
            name = "V-Reversal",
            family = PatternFamily.REVERSAL,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 15,
            shapeSummary = "A sharp fall immediately followed by a sharp rise.",
            glossary = GlossaryEntry(
                whatItIs = "A reversal whose two legs are near-vertical, forming a V.",
                howItLooks = "A steep fall over a few sessions and an equally steep rise.",
                textbookSays = "Associated with a quick change of direction.",
                doesNotPredict = "A V is mostly a description of steepness. It carries no level and " +
                    "no confirmation beyond the shape itself.",
            ),
        ),
        PatternSpec(
            name = "Island Reversal",
            family = PatternFamily.REVERSAL,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 20,
            shapeSummary = "A gap away, a stretch of trading, then a gap back.",
            glossary = GlossaryEntry(
                whatItIs = "A reversal marked by a gap leaving a price range and a later gap " +
                    "returning to it.",
                howItLooks = "A gap up or down, several sessions trading elsewhere, then a gap " +
                    "back through the original range.",
                textbookSays = "Associated with a gap-driven change in the traded price level.",
                doesNotPredict = "It is usually a consequence of news or a corporate action rather " +
                    "than a forecast, and the app cannot distinguish those cases from price alone.",
            ),
        ),

        // --------------------------------------------------------------------- continuation
        PatternSpec(
            name = "Symmetrical Triangle",
            family = PatternFamily.CONTINUATION,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 15,
            shapeSummary = "Converging boundaries of opposite slope, neither one flat.",
            glossary = GlossaryEntry(
                whatItIs = "A continuation or reversal formation bounded by a falling line above " +
                    "and a rising line below, converging to a point.",
                howItLooks = "Each session's range lies between two lines that approach each other.",
                textbookSays = "Read as continuation of whichever trend preceded it, and so " +
                    "ambiguous by nature that it is sometimes called a coil.",
                doesNotPredict = "Its direction is genuinely undetermined until it breaks, so it is " +
                    "the clearest case of a pattern that names no outcome.",
            ),
        ),
        PatternSpec(
            name = "Rising Wedge",
            family = PatternFamily.CONTINUATION,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 20,
            shapeSummary = "Rising highs and rising lows, the highs rising more steeply.",
            glossary = GlossaryEntry(
                whatItIs = "A formation bounded below by a rising support line and above by a " +
                    "steeper line that converges toward it.",
                howItLooks = "Higher highs and higher lows, narrowing as they go.",
                textbookSays = "Associated with a slowing rise, and often read as a reversal once it " +
                    "breaks the lower boundary.",
                doesNotPredict = "A rising wedge can break either way; the narrowing is the " +
                    "pattern, not a direction.",
            ),
        ),
        PatternSpec(
            name = "Falling Wedge",
            family = PatternFamily.CONTINUATION,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 20,
            shapeSummary = "Falling highs and falling lows, the lows falling more steeply.",
            glossary = GlossaryEntry(
                whatItIs = "The rising wedge inverted: converging downward.",
                howItLooks = "Lower highs and lower lows, narrowing as they go.",
                textbookSays = "Associated with a slowing decline, often read as a reversal on a " +
                    "break of the upper boundary.",
                doesNotPredict = "As with its inverse, the narrowing does not fix a direction.",
            ),
        ),
        PatternSpec(
            name = "Pennant",
            family = PatternFamily.CONTINUATION,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 10,
            shapeSummary = "A short, wedge-shaped pause after a pole.",
            glossary = GlossaryEntry(
                whatItIs = "A brief downward-sloping consolidation after a sharp advance, usually " +
                    "shorter and looser than a flag.",
                howItLooks = "A pole, then a small converging channel that drifts down.",
                textbookSays = "Associated with a rising trend resuming.",
                doesNotPredict = "It differs from a flag by degree, not by kind, so the two are " +
                    "easy to confuse and the app does not yet attempt the distinction.",
            ),
        ),
        PatternSpec(
            name = "Rectangle",
            family = PatternFamily.BILATERAL,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 20,
            shapeSummary = "Two horizontal boundaries with several touches of each.",
            glossary = GlossaryEntry(
                whatItIs = "A sideways formation bounded by parallel horizontal support and " +
                    "resistance.",
                howItLooks = "Repeated bounces between two levels, with neither moving.",
                textbookSays = "Associated with a period of balance before a directional move, " +
                    "without saying which.",
                doesNotPredict = "A rectangle's whole content is indecision. Nothing in the shape " +
                    "favours an upward or downward break.",
            ),
        ),
        PatternSpec(
            name = "Cup & Handle",
            family = PatternFamily.REVERSAL,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 40,
            shapeSummary = "A rounded cup-shaped fall and recovery, then a small flag-like dip.",
            glossary = GlossaryEntry(
                whatItIs = "A long rounded recovery with a shallow downward-sloping interruption, " +
                    "partway through.",
                howItLooks = "A U-shaped arc, a small flag against the rim, then a move above the rim.",
                textbookSays = "Associated with a sustained recovery in a rising trend.",
                doesNotPredict = "It is the longest of the textbook formations, so its early stages " +
                    "are indistinguishable from an ordinary decline until much later.",
            ),
        ),
        PatternSpec(
            name = "Measured Move",
            family = PatternFamily.CONTINUATION,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 15,
            shapeSummary = "Projecting a prior impulse's height from the consolidation's range.",
            glossary = GlossaryEntry(
                whatItIs = "Not a formation but a projection method: measure the height of the " +
                    "move before a flag or channel, then project that height from the break.",
                howItLooks = "There is no distinct shape to draw — it is applied to a flag, triangle " +
                    "or channel.",
                textbookSays = "Associated with the continuation of the original impulse.",
                doesNotPredict = "This is the case most often mistaken for a forecast. It is a " +
                    "geometric projection from a past height, and the app labels every projected " +
                    "path with " + MatchScoreLabels.NOT_A_TARGET_WARNING,
            ),
        ),
        PatternSpec(
            name = "Channel",
            family = PatternFamily.CONTINUATION,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 20,
            shapeSummary = "Two roughly parallel lines holding a directional drift.",
            glossary = GlossaryEntry(
                whatItIs = "Two parallel boundaries, both sloping the same way, containing the price.",
                howItLooks = "Touches alternating between two lines that slope together.",
                textbookSays = "Associated with a trend continuing along its own line.",
                doesNotPredict = "A channel is defined by its slope, so it describes the drift " +
                    "already present rather than adding new information.",
            ),
        ),

        // ------------------------------------------------------------------------ candlestick
        PatternSpec(
            name = "Doji",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "An open and close that are equal, or near enough to draw no body.",
            glossary = GlossaryEntry(
                whatItIs = "A single session whose open and close are equal, so no body is drawn.",
                howItLooks = "Open, close and the rest of the range collapse to one level, with " +
                    "upper and lower shadows either side.",
                textbookSays = "Associated with indecision within a session.",
                doesNotPredict = "Indecision is the reading and nothing more. It is not a signal of " +
                    "a turn in either direction, and it occurs in trends as readily as in ranges.",
            ),
        ),
        PatternSpec(
            name = "Marubozu",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "A candle with a long body and almost no shadows.",
            glossary = GlossaryEntry(
                whatItIs = "A single session that opened near one extreme and closed near the other, " +
                    "leaving little or no shadow either side.",
                howItLooks = "One long body, negligible upper and lower wicks.",
                textbookSays = "Associated with one side controlling the session's range.",
                doesNotPredict = "It describes a single session's coverage and says nothing about " +
                    "what follows it.",
            ),
        ),
        PatternSpec(
            name = "Inside Bar",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "A candle whose range falls entirely within the previous candle's.",
            glossary = GlossaryEntry(
                whatItIs = "A two-candle formation where the second session's entire high-low range " +
                    "is contained inside the first's.",
                howItLooks = "The second bar is wholly within the first bar's range, shadows " +
                    "included.",
                textbookSays = "Associated with a contraction of the traded range.",
                doesNotPredict = "Contraction is the whole content of the pattern; it does not " +
                    "indicate which way the range re-expands.",
            ),
        ),
        PatternSpec(
            name = "Outside Bar",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "A candle whose range completely covers the previous candle's.",
            glossary = GlossaryEntry(
                whatItIs = "The inside bar inverted: the second session's range contains the first's.",
                howItLooks = "The second bar's shadows extend beyond the first's on both sides.",
                textbookSays = "Associated with an expansion of the traded range.",
                doesNotPredict = "As with the inside bar, it describes range and nothing more.",
            ),
        ),
        PatternSpec(
            name = "Three White Soldiers",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "Three consecutive long bullish candles with rising closes.",
            glossary = GlossaryEntry(
                whatItIs = "A three-candle advance pattern: three strong sessions in succession, " +
                    "each closing above the last.",
                howItLooks = "Three long bodies of the same direction with rising closes and small " +
                    "opening gaps.",
                textbookSays = "Associated with sustained buying across three sessions.",
                doesNotPredict = "Three sessions do not make a trend, and the pattern is as common " +
                    "partway through a rise as at its start.",
            ),
        ),
        PatternSpec(
            name = "Three Black Crows",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "Three consecutive long bearish candles with falling closes.",
            glossary = GlossaryEntry(
                whatItIs = "The three white soldiers inverted.",
                howItLooks = "Three long bodies of the same direction with falling closes.",
                textbookSays = "Associated with sustained selling across three sessions.",
                doesNotPredict = "As with its inverse, three sessions do not establish a trend.",
            ),
        ),
        PatternSpec(
            name = "Harami",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "A tiny second candle contained within the first's body.",
            glossary = GlossaryEntry(
                whatItIs = "A two-candle formation where the second candle's body sits entirely " +
                    "within the first's.",
                howItLooks = "A large body followed by a very small one inside it.",
                textbookSays = "Associated with a reduction in momentum between the two sessions.",
                doesNotPredict = "It describes a single relationship between two bodies and " +
                    "carries no direction.",
            ),
        ),
        PatternSpec(
            name = "Tweezer Tops",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "Two candles whose highs are equal, following a rise.",
            glossary = GlossaryEntry(
                whatItIs = "A two-candle formation where consecutive sessions reach the same high.",
                howItLooks = "Two bars with matching upper shadows at the same price level.",
                textbookSays = "Associated with a repeated failure to make a higher high.",
                doesNotPredict = "Matching highs are a common coincidence in a narrow range, so the " +
                    "pattern has no meaning without the context around it.",
            ),
        ),
        PatternSpec(
            name = "Tweezer Bottoms",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "Two candles whose lows are equal, following a fall.",
            glossary = GlossaryEntry(
                whatItIs = "The tweezer tops inverted: consecutive sessions reach the same low.",
                howItLooks = "Two bars with matching lower shadows at the same price level.",
                textbookSays = "Associated with a repeated failure to make a lower low.",
                doesNotPredict = "Shares the tweezer tops' weakness: without context it is a " +
                    "coincidence of two equal prices.",
            ),
        ),
        PatternSpec(
            name = "Inverted Hammer",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "A small body at the bottom with a long upper wick.",
            glossary = GlossaryEntry(
                whatItIs = "The hammer's reflection: small body at the low of the range, long upper " +
                    "wick.",
                howItLooks = "Long shadow above, body near the low of the session's range.",
                textbookSays = "Associated with rejection of lower prices, read as a possible end " +
                    "to a decline.",
                doesNotPredict = "It is a single session. Its resemblance to the hammer is exactly " +
                    "why position in the trend has to be measured rather than assumed.",
            ),
        ),
        PatternSpec(
            name = "Hanging Man",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "A hammer-shaped candle appearing after a rise.",
            glossary = GlossaryEntry(
                whatItIs = "A hammer that appears at the top of a rise rather than at the bottom of " +
                    "a fall, and is therefore named for where it hangs.",
                howItLooks = "Identical in shape to a hammer; the difference is entirely its position " +
                    "in the trend.",
                textbookSays = "Associated with a possible end to a rise.",
                doesNotPredict = "The same shape means opposite things in opposite positions, " +
                    "which is why the app measures trend context as a scored factor rather than " +
                    "assuming it.",
            ),
        ),
        PatternSpec(
            name = "Kicker",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "A gap and a long candle continuing a prior move.",
            glossary = GlossaryEntry(
                whatItIs = "A two-candle formation where the second session gaps away and closes " +
                    "strongly in the direction of the first.",
                howItLooks = "A gap on the open followed by a long body.",
                textbookSays = "Associated with the continuation of an established move.",
                doesNotPredict = "Kickers usually accompany news or a corporate action rather than " +
                    "forecasting anything.",
            ),
        ),

        // --------------------------------------------------------------------------- volume
        PatternSpec(
            name = "Volume Climax",
            family = PatternFamily.VOLUME,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 60,
            shapeSummary = "An extreme volume session after a long advance or decline.",
            glossary = GlossaryEntry(
                whatItIs = "A session trading at an extreme of recent volume, following a long run " +
                    "in one direction.",
                howItLooks = "One bar's volume far above the recent range, at the end of a long move.",
                textbookSays = "Associated with the end of a move that has been sustained a long time.",
                doesNotPredict = "Extreme volume can mark continuation as easily as exhaustion, so " +
                    "the pattern does not by itself indicate a turn.",
            ),
        ),
        PatternSpec(
            name = "Volume Dry-Up",
            family = PatternFamily.VOLUME,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 60,
            shapeSummary = "A contraction of volume inside a narrow range.",
            glossary = GlossaryEntry(
                whatItIs = "A run of sessions whose volume falls steadily while the price range narrows.",
                howItLooks = "Short bars within a tightening range, each smaller than the last.",
                textbookSays = "Associated with a market waiting, often shortly before a move.",
                doesNotPredict = "It describes a lull and its cause is not observable from volume " +
                    "alone.",
            ),
        ),
        // ------------------------------------------------------------ candlestick (ids 1..6)
        //
        // Moved out of DETECTABLE. These six rows used to be marked `DETECTED` and the app did
        // score them — but two facts made the claim misleading, and both are measured rather than
        // argued:
        //
        // * **None can enter the track record.** `OutcomeTracker.rejectionReason` refuses any match
        //   whose `measuredMove` is null, and every candlestick detector emits null (spec DD-14: a
        //   candle formation has no height to project). A walk-forward census over 17 large caps ×
        //   372 bars found 121 candlestick matches and every one was correctly untrackable. So the
        //   row said "evaluated" and the app could never say whether any of it worked.
        // * **The score is a 3-bar shape scored on 20 bars of context.** A hammer is two candles
        //   wide; the factors behind its score reach back 20. The number is real but it is not a
        //   formation measurement in the way a double top's is.
        //
        // The detectors are deliberately **not** deleted. They still run for the Explorer's
        // near-miss view and the red-flag engine reads the same signals, so removing them would
        // change scoring behaviour rather than documentation. This is a labelling correction.
        PatternSpec(
            name = "Bullish Engulfing",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "A candle whose body engulfs the previous candle's body.",
                        glossary = GlossaryEntry(
                whatItIs = "A two-candle reversal formation: the second candle's real body " +
                    "completely covers the first candle's real body.",
                howItLooks = "The first bar is small and coloured one way; the next is larger and " +
                    "the opposite colour, opening at or below the first close and closing at or " +
                    "above the first open.",
                textbookSays = "Associated with a change in the balance of buyers and sellers over " +
                    "those two sessions.",
                doesNotPredict = "It describes two sessions only, and says nothing about the days " +
                    "around them. A textbook needs a downtrend before it for the reading to carry " +
                    "its usual meaning, and the app measures that as a separate factor.",
            ),
        ),
        PatternSpec(
            name = "Bearish Engulfing",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "A candle whose body engulfs the previous candle's body, opposite way.",
            glossary = GlossaryEntry(
                whatItIs = "The bearish mirror of the bullish engulfing: the second candle's " +
                    "real body completely covers the first.",
                howItLooks = "The first bar is small; the next is larger and the opposite colour, " +
                    "opening at or above the first close and closing at or below the first open.",
                textbookSays = "Associated with a shift toward sellers over those two sessions.",
                doesNotPredict = "It describes two sessions. Like its bullish mirror it carries no " +
                    "information about the sessions that follow.",
            ),
        ),
        PatternSpec(
            name = "Hammer",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "A small body near the top with a long lower wick.",
                        glossary = GlossaryEntry(
                whatItIs = "A single candle with a small real body in the upper third of its " +
                    "range and a lower wick at least twice the body's length.",
                howItLooks = "Long shadow beneath, little above, and the body near the high of the " +
                    "session's range.",
                textbookSays = "Associated with rejection of lower prices during that session, and " +
                    "read as a possible end to a decline.",
                doesNotPredict = "One candle cannot confirm anything on its own. The textbook " +
                    "requires it to follow a decline, and a confirmation candle; the app scores " +
                    "both as separate factors rather than assuming them.",
            ),
        ),
        PatternSpec(
            name = "Shooting Star",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "A small body near the bottom with a long upper wick.",
            glossary = GlossaryEntry(
                whatItIs = "The hammer's mirror image, at the top of a range: small body low in " +
                    "the range, upper wick at least twice the body.",
                howItLooks = "Long shadow above, little below, body near the low of the session.",
                textbookSays = "Associated with rejection of higher prices, and read as a possible " +
                    "end to a rise.",
                doesNotPredict = "As with the hammer, a single candle is not a signal on its own " +
                    "and says nothing about later sessions.",
            ),
        ),
        PatternSpec(
            name = "Morning Star",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "Three candles: a long one down, a small one, then a long one up.",
                        glossary = GlossaryEntry(
                whatItIs = "A three-candle reversal at a bottom: a long down candle, a small " +
                    "indecision candle that does not extend the fall, then a long up candle.",
                howItLooks = "Three bars — long, small, long — with the third recovering at least " +
                    "half of the first candle's body.",
                textbookSays = "Associated with a bottoming process after a decline.",
                doesNotPredict = "Three candles are still three sessions. It establishes neither a " +
                    "level nor a duration, and the app measures the recovery as a fraction rather " +
                    "than assuming it is complete.",
            ),
        ),
        PatternSpec(
            name = "Evening Star",
            family = PatternFamily.CANDLESTICK,
            detectionStatus = NOT_IMPLEMENTED,
            minBars = 22,
            shapeSummary = "Three candles: a long one up, a small one, then a long one down.",
            glossary = GlossaryEntry(
                whatItIs = "The morning star's mirror at a top: a long up candle, a small " +
                    "indecision candle, then a long down candle.",
                howItLooks = "Three bars — long, small, long — with the third giving back at least " +
                    "half of the first candle's body.",
                textbookSays = "Associated with a topping process after a rise.",
                doesNotPredict = "As with the morning star, it covers three sessions and nothing " +
                    "beyond them.",
            ),
        ),

    )

    /** Every row: the evaluated patterns first, then the ones this app names but does not look for. */
    val ALL: List<PatternSpec> = DETECTABLE + KNOWN_NOT_EVALUATED
}