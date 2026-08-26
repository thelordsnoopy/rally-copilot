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
    /** Learned camber, degrees, positive = road leans car-left when driven FORWARD (node order). */
    var camberLookup: ((edgeId: Long, startM: Double, endM: Double) -> Double?)? = null,
    /** Gear suggestion for a target speed (learned from OBD). Null = no gear calls. */
    var gearLookup: ((vTargetMps: Double) -> Int?)? = null,
) {
    data class Params(
        /** Comfortable but firm braking on the road, m/s². */
        val aBrake: Double = 0.35 * 9.81,
        val reactionSeconds: Double = 1.0,
        /** Driver must have heard the whole note this long before the braking point. */
        val endLeadSeconds: Double = 1.2,
        /** Corners whose map confidence is below this are called with a leading "caution" —
         *  the band and target speed stay honest (softening the band would RAISE the speed). */
        val softenBelowConfidence: Double = 0.55,
        /** tightens/opens threshold: exit vs entry radius ratio. */
        val radiusTrendRatio: Double = 0.75,
        val longArcM: Double = 120.0,
        val intoGapM: Double = 40.0,
        /**
         * Clamp suggested speeds to the road's posted/implied limit.
         *
         * OFF by user decision: the target speed is the corner's PHYSICAL limit
         * given your learned grip — a theoretical maximum, not a legal one. What
         * you do with that number is the driver's business. Available for anyone
         * who wants it, never on by default.
         */
        val clampToSpeedLimit: Boolean = false,
        val userMaxMps: Double = 44.7, // 100 mph ceiling on suggestions
        val wetFactor: Double = 0.8,
        /** Collapse same-direction corners closer than this into one call. */
        val mergeSameDirectionM: Double = 40.0,
    )

    /**
     * Implied limit where OSM has no maxspeed tag, m/s. UK defaults: national
     * single carriageway 60, dual/motorway 70, residential 30. Without this a
     * gentle "six" on a country lane derives a target from geometry alone and
     * comes out at 80-plus mph, which is not advice anyone should follow.
     */
    private fun impliedLimitMps(highwayClass: String?): Double = when (highwayClass) {
        "motorway", "motorway_link", "trunk", "trunk_link" -> 70 / 2.23694
        "residential", "living_street", "service" -> 30 / 2.23694
        else -> 60 / 2.23694
    }

    private fun limitFor(maxspeedKph: Int?, highwayClass: String?): Double =
        maxspeedKph?.takeIf { it in 5..130 }?.let { it / 3.6 } ?: impliedLimitMps(highwayClass)

    val params = Params()

    fun vTargetFor(corner: Corner, band: SeverityBand): Double {
        // No conditions multiplier here: dry and wet are separate learned profiles,
        // and the wet profile seeds lower. Multiplying again would double-penalise.
        val aLat = profile.aLatFor(band) * profile.pushFactor
        val v = sqrt(aLat * corner.minRadiusM)
        return v.coerceAtMost(params.userMaxMps)
    }

    /** Metres needed to shed [vNow] down to [vTarget] at the comfortable braking rate. */
    fun brakingDistanceM(vNow: Double, vTarget: Double): Double =
        if (vNow > vTarget) (vNow * vNow - vTarget * vTarget) / (2 * params.aBrake) else 0.0

    /** Seconds of thinking + settling the driver needs after the note finishes. */
    val noteLeadSeconds: Double get() = params.reactionSeconds + params.endLeadSeconds

    /**
     * Annotate a raw horizon. [currentSpeedMps] drives braking-point maths; recomputed
     * per horizon rebuild and refined at trigger time by the engine.
     */
    fun annotate(raw: HorizonBuilder.RawHorizon, currentSpeedMps: Double, nowMs: Long): Horizon {
        val out = ArrayList<HorizonCorner>(raw.corners.size)
        for ((i, entry) in raw.corners.withIndex()) {
            val corner = entry.corner
            val aheadM = entry.distanceAheadM
            val pathConf = entry.pathConfidence
            val band = severityTable.bandFor(corner.minRadiusM)
            if (band == SeverityBand.FLAT) continue

            val modifiers = ArrayList<Modifier>(3)
            // Low map confidence: keep the honest band and speed, lead with "caution".
            if (corner.confidence < params.softenBelowConfidence) modifiers += Modifier.CAUTION
            if (corner.exitRadiusM < corner.entryRadiusM * params.radiusTrendRatio) modifiers += Modifier.TIGHTENS
            else if (corner.entryRadiusM < corner.exitRadiusM * params.radiusTrendRatio) modifiers += Modifier.OPENS
            if (corner.arcLengthM > params.longArcM) modifiers += Modifier.LONG
            val next = raw.corners.getOrNull(i + 1)
            if (next != null && next.distanceAheadM - (aheadM + corner.arcLengthM) < params.intoGapM) modifiers += Modifier.INTO

            var vTarget = vTargetFor(corner, band)
            // Off by default — see [Params.clampToSpeedLimit].
            if (params.clampToSpeedLimit) {
                vTarget = minOf(vTarget, limitFor(entry.maxspeedKph, entry.highwayClass))
            }
            // Your history with this exact stretch of road trims the suggestion.
            speedFactorLookup?.let { lookup ->
                vTarget *= lookup(corner.edgeId, corner.startOffsetM, corner.endOffsetM)
            }
            // Measured camber: off-camber corners get called and slowed — the radius
            // maths flatters exactly these. Positive camber helps LEFT turns. Camber is
            // stored in the edge's forward frame; driving the edge the other way, the
            // same crown leans the other way in the car frame.
            val storedCamber = camberLookup?.invoke(corner.edgeId, corner.startOffsetM, corner.endOffsetM)
            val camber = storedCamber?.let { if (entry.forward) it else -it }
            if (camber != null) {
                val adverseDeg = when (corner.direction) {
                    Direction.LEFT -> (-camber).coerceAtLeast(0.0)
                    Direction.RIGHT -> camber.coerceAtLeast(0.0)
                }
                if (adverseDeg >= com.rallycopilot.core.knowledge.KnowledgeMath.CAMBER_ADVERSE_DEG) {
                    modifiers += Modifier.OFF_CAMBER
                    vTarget *= (1.0 - 0.03 * adverseDeg).coerceAtLeast(0.85)
                }
            }
            // Braking point / trigger for the HUD, from build-time speed. The ENGINE
            // recomputes both from live speed every tick — these are display values.
            val v = currentSpeedMps
            val brakingPointM = (aheadM - brakingDistanceM(v, vTarget)).coerceAtLeast(0.0)
            val triggerDistanceM = (brakingPointM - v * noteLeadSeconds).coerceAtLeast(0.0)

            out += HorizonCorner(
                corner = corner,
                distanceAheadM = aheadM,
                pathConfidence = pathConf,
                band = band,
                modifiers = modifiers,
                vTargetMps = vTarget,
                brakingPointM = brakingPointM,
                triggerDistanceM = triggerDistanceM,
                gear = gearLookup?.invoke(vTarget),
            )
        }
        // Collapse runs of same-direction corners that are really one bend. OSM
        // geometry routinely splits a single sweeper into two or three fragments,
        // and "right, right, right" for one corner is the fastest way to teach the
        // driver to tune the co-driver out. Keep the tightest of the run — that is
        // the one the driver has to actually deal with.
        val merged = ArrayList<HorizonCorner>(out.size)
        var runEndM = Double.NEGATIVE_INFINITY
        var runDir: Direction? = null
        for (c in out) {
            // Measure the gap from the END of the run so far, not from whichever
            // fragment happens to be the current pick — otherwise a third fragment
            // is compared against the first and escapes the merge.
            if (runDir == c.corner.direction && c.distanceAheadM - runEndM < params.mergeSameDirectionM) {
                // Tightest radius wins: two fragments often share a severity band
                // while one is meaningfully sharper than the other.
                if (c.corner.minRadiusM < merged.last().corner.minRadiusM) merged[merged.size - 1] = c
                runEndM = maxOf(runEndM, c.distanceAheadM + c.corner.arcLengthM)
                continue
            }
            merged += c
            runDir = c.corner.direction
            runEndM = c.distanceAheadM + c.corner.arcLengthM
        }

        val firstEdge = raw.steps.firstOrNull()?.edge
        return Horizon(
            builtAtMs = nowMs,
            pathEdgeIds = raw.steps.map { it.edge.id },
            totalLengthM = raw.totalLengthM,
            confidenceAtEnd = raw.confidenceAtEnd,
            corners = merged,
            hazards = raw.hazards.map { HorizonHazard(it.first, it.second, it.third) },
            currentEdgeHighway = firstEdge?.highwayClass,
            currentEdgeMaxspeedKph = firstEdge?.maxspeedKph,
        )
    }
}

/** Direction word for the vocabulary. */
fun Direction.spoken(): String = when (this) {
    Direction.LEFT -> "left"
    Direction.RIGHT -> "right"
}
