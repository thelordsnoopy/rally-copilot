package com.rallycopilot.core.matcher

import com.rallycopilot.core.engine.MapStore
import com.rallycopilot.core.geo.Geo
import com.rallycopilot.core.geo.Polyline
import com.rallycopilot.core.model.Edge
import com.rallycopilot.core.model.Fix
import com.rallycopilot.core.model.LatLon
import com.rallycopilot.core.model.MatchedPosition
import kotlin.math.exp

/**
 * Map matcher: fix → (edge, offset, direction, confidence).
 *
 * Scoring per candidate edge:
 *   - lateral distance from the edge (Gaussian, sigma = accuracy + 5 m)
 *   - heading agreement with the edge direction at the projected point
 *   - continuity: bonus for staying on the previous edge or moving to a connected one
 *
 * Confidence is the winner's share of total candidate score — 1.0 when unambiguous,
 * ~0.5 at a parallel-road ambiguity. The engine gates speech on this.
 */
class MapMatcher(private val map: MapStore) {

    private var prev: MatchedPosition? = null

    data class Params(
        val searchRadiusM: Double = 35.0,
        val headingWeight: Double = 0.5,
        val continuityBonus: Double = 0.35,
        val minConfidence: Double = 0.15,
    )

    private val params = Params()

    fun reset() { prev = null }

    fun match(fix: Fix): MatchedPosition? {
        val p = LatLon(fix.lat, fix.lon)
        val candidates = map.edgesNear(p, params.searchRadiusM)
        if (candidates.isEmpty()) { prev = null; return null }

        data class Scored(val edge: Edge, val offsetM: Double, val forward: Boolean, val score: Double)

        val sigma = fix.accuracyM + 5.0
        val scored = ArrayList<Scored>(candidates.size * 2)
        for (edge in candidates) {
            val cum = Polyline.cumulative(edge.geometry)
            val proj = Polyline.project(edge.geometry, cum, p)
            val distScore = exp(-(proj.lateralOffsetM * proj.lateralOffsetM) / (2 * sigma * sigma))
            val edgeBearing = Polyline.bearingAt(edge.geometry, cum, proj.distanceAlongM)
            // Consider both directions of travel unless oneway.
            val dirs = if (edge.oneway) listOf(true) else listOf(true, false)
            for (forward in dirs) {
                val travelBearing = if (forward) edgeBearing else (edgeBearing + 180.0) % 360.0
                val hDiff = Geo.bearingDiffDeg(fix.bearingDeg, travelBearing)
                // Heading only meaningful when moving and actually reported (NaN = no bearing).
                val headingUsable = fix.speedMps >= 2.0 && !fix.bearingDeg.isNaN()
                val headingScore = if (!headingUsable) 1.0
                else exp(-(hDiff * hDiff) / (2 * 40.0 * 40.0))
                // At real driving speed the bearing is decisive: without this, the reverse
                // direction of the matched edge keeps ~half the winner's score and caps
                // confidence near 0.70 on a lone unambiguous road, muting the co-driver
                // beyond the next junction.
                val w = when {
                    !headingUsable -> 0.0
                    fix.speedMps < 4.0 -> params.headingWeight
                    else -> 0.95
                }
                var s = distScore * (1 - w + w * headingScore)
                val pv = prev
                if (pv != null) {
                    val connected = pv.edgeId == edge.id ||
                        edge.fromNodeId == endNodeOf(pv) || edge.toNodeId == endNodeOf(pv)
                    if (pv.edgeId == edge.id && pv.forward == forward) s *= (1 + params.continuityBonus)
                    else if (connected) s *= (1 + params.continuityBonus * 0.5)
                }
                scored += Scored(edge, proj.distanceAlongM, forward, s)
            }
        }
        val total = scored.sumOf { it.score }
        if (total <= 0.0) { prev = null; return null }
        val best = scored.maxBy { it.score }
        val confidence = (best.score / total).coerceIn(0.0, 1.0)
        if (confidence < params.minConfidence) { prev = null; return null }

        val m = MatchedPosition(
            tMs = fix.tMs,
            edgeId = best.edge.id,
            offsetM = best.offsetM,
            forward = best.forward,
            speedMps = fix.speedMps,
            bearingDeg = fix.bearingDeg,
            confidence = confidence,
        )
        prev = m
        return m
    }

    /** Node id we are travelling toward on the previous match. */
    private fun endNodeOf(m: MatchedPosition): Long {
        val e = map.edge(m.edgeId) ?: return -1
        return if (m.forward) e.toNodeId else e.fromNodeId
    }
}
