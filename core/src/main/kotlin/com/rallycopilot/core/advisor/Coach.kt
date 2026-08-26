package com.rallycopilot.core.advisor

import com.rallycopilot.core.model.CornerObservation
import com.rallycopilot.core.model.HorizonCorner

/**
 * A word about the corner you just took — but only ever in the gaps.
 *
 * The rule that makes this bearable rather than irritating: the co-driver's job is
 * the road AHEAD, so coaching never competes with it. A verdict is formed when a
 * corner closes, held, and spoken only if a genuine straight follows. If the next
 * call comes due first, the verdict is dropped entirely rather than queued —
 * yesterday's corner is worth nothing next to the one you are arriving at.
 */
class Coach(private val params: Params = Params()) {

    data class Params(
        /** Slower than this fraction of target and you left something on the table. */
        val hadMoreBelow: Double = 0.80,
        /** Faster than this and you were over what the model expects to stick. */
        val tooHotAbove: Double = 1.15,
        /** Nothing at all for this long after speaking, whatever happens. */
        val cooldownMs: Long = 75_000,
        /** "Good" is worth hearing occasionally, not every other corner. */
        val praiseCooldownMs: Long = 240_000,
        /** A verdict older than this is stale — the moment has passed. */
        val staleAfterMs: Long = 20_000,
        /** Clear road needed ahead before a word is spoken, in seconds of travel. */
        val minGapSeconds: Double = 5.0,
    )

    /** A held verdict, waiting for a gap that may never come. */
    data class Verdict(val clipKey: String, val formedAtMs: Long, val cornerId: Long)

    private var pending: Verdict? = null
    private var lastSpokenMs = Long.MIN_VALUE / 2
    private var lastPraiseMs = Long.MIN_VALUE / 2

    /**
     * A corner has just closed. Form a verdict, or decide there is nothing to say.
     * Constrained corners (traffic, a junction, someone in the way) teach nothing
     * about the driver, so they are never commented on.
     */
    fun onCornerClosed(obs: CornerObservation, hc: HorizonCorner, nowMs: Long) {
        if (obs.wasConstrained || !obs.spirited) return
        val target = hc.vTargetMps
        if (target <= 0.0 || !obs.vMinMps.isFinite()) return
        val ratio = obs.vMinMps / target

        val key = when {
            ratio > params.tooHotAbove -> "coach_hot"
            ratio < params.hadMoreBelow -> "coach_more"
            nowMs - lastPraiseMs > params.praiseCooldownMs -> "coach_good"
            else -> return
        }
        pending = Verdict(key, nowMs, obs.cornerId)
    }

    /**
     * Is there something to say, and is now the moment?
     *
     * [gapSeconds] is how long the road is clear for — time until the next call is
     * due. [speaking] is whether the co-driver is mid-sentence.
     */
    fun poll(nowMs: Long, gapSeconds: Double, speaking: Boolean): String? {
        val v = pending ?: return null
        // Stale verdicts are dropped, never queued: the next corner matters more.
        if (nowMs - v.formedAtMs > params.staleAfterMs) { pending = null; return null }
        if (speaking) return null
        if (nowMs - lastSpokenMs < params.cooldownMs) return null
        if (gapSeconds < params.minGapSeconds) return null

        pending = null
        lastSpokenMs = nowMs
        if (v.clipKey == "coach_good") lastPraiseMs = nowMs
        return v.clipKey
    }

    /** Drop anything held — used when the drive state changes underneath us. */
    fun clear() { pending = null }
}
