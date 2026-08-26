package com.rallycopilot.core.profile

import com.rallycopilot.core.model.CornerObservation
import com.rallycopilot.core.model.DriverProfile
import com.rallycopilot.core.model.FeedbackAnswer
import com.rallycopilot.core.model.SeverityBand

/**
 * The learning system. The profile is DERIVED from observations, never edited directly —
 * change a parameter here and the whole profile re-derives from history.
 *
 * There is no absolute ceiling by user decision, so the brakes that remain are
 * load-bearing: the per-session ratchet limit, asymmetric feedback, and hard sample
 * filtering. Do not weaken them casually.
 */
object Learning {

    data class Params(
        /** Percentile of observed lateral g taken per band: the good corners, not the average. */
        val percentile: Double = 0.80,
        /** A single session can move any band by at most this fraction. Primary drift brake. */
        val sessionRatchet: Double = 0.05,
        val minMapConfidence: Double = 0.6,
        val minPathConfidence: Double = 0.6,
        val minSamplesPerBand: Int = 3,
        /** Onset detection: rolling window size and fraction of session p90 to reach. */
        val onsetWindow: Int = 5,
        val onsetFractionOfP90: Double = 0.80,
        /** pushFactor moves. Down is bigger than up on purpose. */
        val easyDelta: Double = +0.04,
        val hardDelta: Double = -0.05,
        val pushMin: Double = 0.85,
        val pushMax: Double = 1.15,
    )

    val params = Params()

    /** Filter to observations worth learning from: unconstrained, confident, and
     *  driven SPIRITED — a normal-pace corner says nothing about your limits. */
    fun usable(obs: List<CornerObservation>): List<CornerObservation> = obs.filter {
        !it.wasConstrained &&
            it.spirited &&
            it.mapConfidence >= params.minMapConfidence &&
            it.pathConfidence >= params.minPathConfidence &&
            it.aLatObserved.isFinite() && it.aLatObserved > 0.5 // discard parked/crawling noise
    }

    /**
     * Calibration onset: the index of the first corner from which you were actually
     * driving properly. Rolling median of aLatObserved over [Params.onsetWindow]; onset
     * is the first window whose median reaches onsetFractionOfP90 × (p90 of all window
     * medians). Deliberately simple and explainable — it is shown on the replay screen
     * and must be arguable-with.
     */
    fun onsetIndex(session: List<CornerObservation>): Int {
        val w = params.onsetWindow
        if (session.size < w) return 0
        val medians = (0..session.size - w).map { i ->
            session.subList(i, i + w).map { it.aLatObserved }.sorted()[w / 2]
        }
        val p90 = percentileOf(medians, 0.90)
        val threshold = params.onsetFractionOfP90 * p90
        val idx = medians.indexOfFirst { it >= threshold }
        return if (idx < 0) 0 else idx
    }

    /**
     * Derive per-band learned lateral g from a full observation history.
     * Returns (aLatByBand, sampleCountByBand).
     */
    fun derive(history: List<CornerObservation>): Pair<Map<SeverityBand, Double>, Map<SeverityBand, Int>> {
        val use = usable(history)
        val byBand = use.groupBy { it.band }
        val aLat = HashMap<SeverityBand, Double>()
        val counts = HashMap<SeverityBand, Int>()
        for ((band, rows) in byBand) {
            counts[band] = rows.size
            if (rows.size >= params.minSamplesPerBand) {
                aLat[band] = percentileOf(rows.map { it.aLatObserved }, params.percentile)
            }
        }
        return aLat to counts
    }

    /**
     * Apply one session's worth of new observations to the profile, respecting the
     * per-session ratchet: no band moves more than sessionRatchet in one drive.
     */
    fun applySession(
        current: DriverProfile,
        history: List<CornerObservation>,
    ): DriverProfile {
        val (derived, counts) = derive(history)
        val ratcheted = HashMap<SeverityBand, Double>()
        for ((band, target) in derived) {
            val prev = current.aLatByBand[band]
            ratcheted[band] = if (prev == null) target
            else {
                val maxUp = prev * (1 + params.sessionRatchet)
                val maxDown = prev * (1 - params.sessionRatchet)
                target.coerceIn(maxDown, maxUp)
            }
        }
        return current.copy(aLatByBand = ratcheted, sampleCountByBand = counts)
    }

    /** Easy/Good/Hard. "Hard" = it was pushing me → back off. */
    fun applyFeedback(current: DriverProfile, answer: FeedbackAnswer): DriverProfile {
        val delta = when (answer) {
            FeedbackAnswer.EASY -> params.easyDelta
            FeedbackAnswer.GOOD -> 0.0
            FeedbackAnswer.HARD -> params.hardDelta
        }
        return current.copy(
            pushFactor = (current.pushFactor + delta).coerceIn(params.pushMin, params.pushMax)
        )
    }

    fun percentileOf(values: List<Double>, p: Double): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val idx = (p * (sorted.size - 1))
        val lo = sorted[idx.toInt()]
        val hi = sorted[minOf(idx.toInt() + 1, sorted.size - 1)]
        val frac = idx - idx.toInt()
        return lo + (hi - lo) * frac
    }
}
