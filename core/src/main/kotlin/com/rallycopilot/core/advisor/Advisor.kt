package com.rallycopilot.core.advisor

import com.rallycopilot.core.horizon.HorizonBuilder
import com.rallycopilot.core.model.Conditions
import com.rallycopilot.core.model.Corner
import com.rallycopilot.core.model.Direction
import com.rallycopilot.core.model.DriverProfile
import com.rallycopilot.core.model.Horizon
import com.rallycopilot.core.model.HorizonCorner
import com.rallycopilot.core.model.HorizonHazard
import com.rallycopilot.core.model.Modifier
import com.rallycopilot.core.model.SeverityBand
import com.rallycopilot.core.model.SeverityTable
import kotlin.math.sqrt

/**
 * Turns raw horizon corners into called corners: severity band, personal target speed,
 * braking point, and the distance at which the utterance must fire.
 */
class Advisor(
    var profile: DriverProfile,
    var severityTable: SeverityTable = SeverityTable.DEFAULT,
    var conditions: Conditions = Conditions.DRY,
    /** Personal knowledge layer: learned speed factor for a stretch of an edge. */
    var speedFactorLookup: ((edgeId: Long, startM: Double, endM: Double) -> Double)? = null,
) {
    data class Params(
        /** Comfortable but firm braking on the road, m/s². */
        val aBrake: Double = 0.35 * 9.81,
        val reactionSeconds: Double = 1.0,
        /** Driver must have heard the whole note this long before the braking point. */
        val endLeadSeconds: Double = 1.2,
        /** Corners whose confidence is below this are called one band softer, with caution. */
        val softenBelowConfidence: Double = 0.55,
        /** tightens/opens threshold: exit vs entry radius ratio. */
        val radiusTrendRatio: Double = 0.75,
        val longArcM: Double = 120.0,
        val intoGapM: Double = 40.0,
        /** Speed limit clamp on suggested speeds (mph tag honoured upstream). */
        val clampToSpeedLimit: Boolean = false,
        val userMaxMps: Double = 44.7, // 100 mph ceiling on suggestions
        val wetFactor: Double = 0.8,
    )

    val params = Params()

    fun vTargetFor(corner: Corner, band: SeverityBand): Double {
        // No conditions multiplier here: dry and wet are separate learned profiles,
        // and the wet profile seeds lower. Multiplying again would double-penalise.
        val aLat = profile.aLatFor(band) * profile.pushFactor
        val v = sqrt(aLat * corner.minRadiusM)
        return v.coerceAtMost(params.userMaxMps)
    }

    /**
     * Annotate a raw horizon. [currentSpeedMps] drives braking-point maths; recomputed
     * per horizon rebuild and refined at trigger time by the engine.
     */
    fun annotate(raw: HorizonBuilder.RawHorizon, currentSpeedMps: Double, nowMs: Long): Horizon {
        val out = ArrayList<HorizonCorner>(raw.corners.size)
        for ((i, entry) in raw.corners.withIndex()) {
            val (corner, aheadM, pathConf) = entry
            var band = severityTable.bandFor(corner.minRadiusM)
            if (band == SeverityBand.FLAT) continue

            // Low map confidence: call it one band softer rather than with false precision.
            val soften = corner.confidence < params.softenBelowConfidence
            if (soften) band = softerBand(band)

            val modifiers = ArrayList<Modifier>(2)
            if (corner.exitRadiusM < corner.entryRadiusM * params.radiusTrendRatio) modifiers += Modifier.TIGHTENS
            else if (corner.entryRadiusM < corner.exitRadiusM * params.radiusTrendRatio) modifiers += Modifier.OPENS
            if (corner.arcLengthM > params.longArcM) modifiers += Modifier.LONG
            val next = raw.corners.getOrNull(i + 1)
            if (next != null && next.second - (aheadM + corner.arcLengthM) < params.intoGapM) modifiers += Modifier.INTO

            var vTarget = vTargetFor(corner, band)
            // Your history with this exact stretch of road trims the suggestion.
            speedFactorLookup?.let { lookup ->
                vTarget *= lookup(corner.edgeId, corner.startOffsetM, corner.endOffsetM)
            }
            val v = currentSpeedMps
            val brakingDistance = if (v > vTarget) (v * v - vTarget * vTarget) / (2 * params.aBrake) else 0.0
            val brakingPointM = (aheadM - brakingDistance).coerceAtLeast(0.0)
            val triggerDistanceM = (brakingPointM - v * (params.reactionSeconds + params.endLeadSeconds))
                .coerceAtLeast(0.0)

            out += HorizonCorner(
                corner = corner,
                distanceAheadM = aheadM,
                pathConfidence = pathConf,
                band = band,
                modifiers = modifiers,
                vTargetMps = vTarget,
                brakingPointM = brakingPointM,
                triggerDistanceM = triggerDistanceM,
            )
        }
        return Horizon(
            builtAtMs = nowMs,
            pathEdgeIds = raw.steps.map { it.edge.id },
            totalLengthM = raw.totalLengthM,
            confidenceAtEnd = raw.confidenceAtEnd,
            corners = out,
            hazards = raw.hazards.map { HorizonHazard(it.first, it.second, it.third) },
        )
    }

    private fun softerBand(b: SeverityBand): SeverityBand = when (b) {
        SeverityBand.HAIRPIN -> SeverityBand.ONE
        SeverityBand.ONE -> SeverityBand.TWO
        SeverityBand.TWO -> SeverityBand.THREE
        SeverityBand.THREE -> SeverityBand.FOUR
        SeverityBand.FOUR -> SeverityBand.FIVE
        SeverityBand.FIVE -> SeverityBand.SIX
        else -> SeverityBand.SIX
    }
}

/** Direction word for the vocabulary. */
fun Direction.spoken(): String = when (this) {
    Direction.LEFT -> "left"
    Direction.RIGHT -> "right"
}
