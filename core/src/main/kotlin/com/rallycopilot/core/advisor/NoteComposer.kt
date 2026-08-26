package com.rallycopilot.core.advisor

import com.rallycopilot.core.model.HorizonCorner
import com.rallycopilot.core.model.HorizonHazard
import com.rallycopilot.core.model.Modifier
import com.rallycopilot.core.model.SeverityBand
import com.rallycopilot.core.model.Utterance
import kotlin.math.roundToInt

/**
 * Composes utterances as ordered vocabulary clip keys, and applies burst compression.
 *
 * Clip key convention (must match the voice pack manifest from tools/voicebuild):
 *   "left_four", "right_hairpin", "tightens", "opens", "long", "into",
 *   "d_100" (link distances, spoken BEFORE a call), "s_40" (target speed in mph,
 *   spoken AFTER a call — position is what tells the two apart), "brake",
 *   "caution", "gear_2".., hazards by name.
 * Urgent variants are the same key in the "urgent/" set, chosen by [Utterance.urgent].
 */
object NoteComposer {

    /** What to include beyond the bare corner call. The engine decides per corner. */
    data class Detail(
        val speed: Boolean = false,
        val gear: Boolean = false,
        val dangerModifiers: Boolean = true,
        val shapeModifiers: Boolean = true,
        /**
         * "into" — the word that says two corners are one continuous piece of road
         * with no straight between them.
         *
         * Kept separate from the other shape words because it is not decoration: it
         * changes what the driver is being told. It used to ride with "long" in the
         * shape tier and was therefore the third thing thrown overboard whenever a
         * burst ran over budget, so precisely the joined-up corners that most need
         * the word were the ones that lost it.
         */
        val linkWords: Boolean = true,
    )

    /** Round a link distance to the spoken vocabulary. */
    fun linkKey(distanceM: Double): String? {
        val steps = intArrayOf(50, 100, 150, 200, 250, 300, 400, 500, 600, 800, 1000)
        if (distanceM < 35) return null // too close to bother saying
        val nearest = steps.minBy { kotlin.math.abs(it - distanceM) }
        return "d_$nearest"
    }

    /** Target speed in mph, rounded to the spoken 5 mph steps. */
    fun speedKey(vTargetMps: Double): String? {
        val mph = vTargetMps * 2.23694
        val step = (mph / 5.0).roundToInt() * 5
        return if (step in 20..100) "s_$step" else null
    }

    /** Modifiers that change how dangerous the corner is — the last thing to drop. */
    private val DANGER = setOf(Modifier.TIGHTENS, Modifier.OPENS, Modifier.OFF_CAMBER)

    fun cornerKeys(c: HorizonCorner, detail: Detail): List<String> {
        val dir = c.corner.direction.spoken()
        val keys = ArrayList<String>(6)
        if (Modifier.CAUTION in c.modifiers) keys += "caution"
        keys += when (c.band) {
            SeverityBand.HAIRPIN -> "${dir}_hairpin"
            else -> "${dir}_${c.band.spoken}"
        }
        if (detail.shapeModifiers && Modifier.LONG in c.modifiers) keys += Modifier.LONG.spoken
        if (detail.dangerModifiers) {
            for (m in c.modifiers) if (m in DANGER) keys += m.spoken
        }
        if (detail.speed) speedKey(c.vTargetMps)?.let { keys += it }
        if (detail.gear) c.gear?.let { keys += "gear_$it" }
        if (detail.linkWords && Modifier.INTO in c.modifiers) keys += Modifier.INTO.spoken
        return keys
    }

    /** Backwards-compatible shorthand used by the engine's speech-lead estimate. */
    fun cornerKeys(c: HorizonCorner, includeGear: Boolean): List<String> =
        cornerKeys(c, Detail(gear = includeGear))

    fun hazardKeys(h: HorizonHazard): List<String> = when {
        h.hazard.kind == com.rallycopilot.core.model.HazardKind.LEARNED -> listOf("caution")
        // A camera is not a hazard to be cautious of, it is a fact to act on —
        // "caution camera" would be both wrong and slower to say.
        h.hazard.kind.isPlainCall -> listOf(h.hazard.kind.name.lowercase())
        else -> listOf("caution", h.hazard.kind.name.lowercase())
    }

    /**
     * Build one utterance for a chain of corners (and optional leading link distance),
     * compressing to fit [budgetMs] of speech.
     *
     * Drop order, least to most important — arriving at a corner still talking about
     * the last one is the one failure that actually costs you:
     *   link distances → gear → shape (long/into) → target speed → tightens/opens/
     *   off-camber → never the corner call itself.
     */
    fun compose(
        chain: List<HorizonCorner>,
        gapsM: List<Double?>,               // gap BEFORE each corner (null for the first)
        detail: Detail,
        deadlineDistanceM: Double,
        budgetMs: Long,
        durationOf: (String) -> Long,
    ): Utterance {
        require(chain.isNotEmpty())
        val urgent = chain.any { it.band.urgent }

        fun assemble(withLinks: Boolean, d: Detail): List<String> {
            val keys = ArrayList<String>()
            for ((i, c) in chain.withIndex()) {
                if (withLinks && i > 0) gapsM[i]?.let { g -> linkKey(g)?.let { keys += it } }
                keys += cornerKeys(c, d)
            }
            return keys
        }

        val ladder = listOf(
            true to detail,
            false to detail,
            false to detail.copy(gear = false),
            false to detail.copy(gear = false, shapeModifiers = false),
            false to detail.copy(gear = false, shapeModifiers = false, speed = false),
            // Last resort: the corner calls and the word joining them, nothing else.
            false to Detail(speed = false, gear = false, dangerModifiers = false,
                shapeModifiers = false, linkWords = true),
        )
        var keys = assemble(ladder.first().first, ladder.first().second)
        for ((withLinks, d) in ladder) {
            keys = assemble(withLinks, d)
            if (keys.sumOf(durationOf) <= budgetMs) break
        }

        return Utterance(
            clipKeys = keys,
            urgent = urgent,
            deadlineDistanceM = deadlineDistanceM,
            forCornerId = chain.first().corner.id,
        )
    }
}
