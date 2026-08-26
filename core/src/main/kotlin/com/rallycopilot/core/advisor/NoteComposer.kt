package com.rallycopilot.core.advisor

import com.rallycopilot.core.model.HorizonCorner
import com.rallycopilot.core.model.HorizonHazard
import com.rallycopilot.core.model.Modifier
import com.rallycopilot.core.model.SeverityBand
import com.rallycopilot.core.model.Utterance

/**
 * Composes utterances as ordered vocabulary clip keys, and applies burst compression.
 *
 * Clip key convention (must match the voice pack manifest from tools/voicebuild):
 *   "left_four", "right_hairpin", "tightens", "opens", "long", "into",
 *   "d_100" (link distances), "brake", "caution", "gear_2".., hazards by name.
 * Urgent variants are the same key in the "urgent/" set, chosen by [Utterance.urgent].
 */
object NoteComposer {

    /** Round a link distance to the spoken vocabulary. */
    fun linkKey(distanceM: Double): String? {
        val steps = intArrayOf(50, 100, 150, 200, 250, 300, 400, 500, 600, 800, 1000)
        if (distanceM < 35) return null // too close to bother saying
        val nearest = steps.minBy { kotlin.math.abs(it - distanceM) }
        return "d_$nearest"
    }

    fun cornerKeys(c: HorizonCorner, includeGear: Boolean): List<String> {
        val dir = c.corner.direction.spoken()
        val keys = ArrayList<String>(4)
        keys += when (c.band) {
            SeverityBand.HAIRPIN -> "${dir}_hairpin"
            else -> "${dir}_${c.band.spoken}"
        }
        for (m in c.modifiers) keys += m.spoken
        if (includeGear && c.gear != null) keys += "gear_${c.gear}"
        return keys
    }

    fun hazardKeys(h: HorizonHazard): List<String> =
        if (h.hazard.kind == com.rallycopilot.core.model.HazardKind.LEARNED) listOf("caution")
        else listOf("caution", h.hazard.kind.name.lowercase())

    /**
     * Build one utterance for a chain of corners (and optional leading link distance),
     * compressing to fit [budgetMs] of speech. Drop order: link distances first, then
     * modifiers, keeping every corner call. Losing "one hundred" is survivable;
     * arriving at the corner still talking about the last one is not.
     */
    fun compose(
        chain: List<HorizonCorner>,
        gapsM: List<Double?>,               // gap BEFORE each corner (null for the first)
        includeGear: Boolean,
        deadlineDistanceM: Double,
        budgetMs: Long,
        durationOf: (String) -> Long,
    ): Utterance {
        require(chain.isNotEmpty())
        val urgent = chain.any { it.band.urgent }

        fun assemble(withLinks: Boolean, withModifiers: Boolean): List<String> {
            val keys = ArrayList<String>()
            for ((i, c) in chain.withIndex()) {
                if (withLinks && i > 0) gapsM[i]?.let { g -> linkKey(g)?.let { keys += it } }
                val cornerKeys = cornerKeys(c, includeGear)
                keys += if (withModifiers) cornerKeys else cornerKeys.take(1) +
                    (if (includeGear && c.gear != null) listOf("gear_${c.gear}") else emptyList())
            }
            return keys
        }

        var keys = assemble(withLinks = true, withModifiers = true)
        if (keys.sumOf(durationOf) > budgetMs) keys = assemble(withLinks = false, withModifiers = true)
        if (keys.sumOf(durationOf) > budgetMs) keys = assemble(withLinks = false, withModifiers = false)

        return Utterance(
            clipKeys = keys,
            urgent = urgent,
            deadlineDistanceM = deadlineDistanceM,
            forCornerId = chain.first().corner.id,
        )
    }
}
