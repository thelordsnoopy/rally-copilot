package com.rallycopilot.core.horizon

import com.rallycopilot.core.engine.MapStore
import com.rallycopilot.core.geo.Geo
import com.rallycopilot.core.geo.Polyline
import com.rallycopilot.core.model.Corner
import com.rallycopilot.core.model.Edge
import com.rallycopilot.core.model.Hazard
import com.rallycopilot.core.model.MatchedPosition

/**
 * Walks the most-probable path forward from the matched position, decaying confidence
 * at every junction. Corners and hazards are read from the precomputed store and
 * projected into distance-ahead space.
 *
 * A wrong note is worse than no note: the confidence this produces is what the engine
 * gates speech on.
 */
class HorizonBuilder(
    private val map: MapStore,
    private val knowledge: com.rallycopilot.core.knowledge.KnowledgeStore? = null,
) {

    data class Params(
        val horizonM: Double = 1200.0,
        /** Below this path probability a branch is not followed at all. */
        val branchPrune: Double = 0.30,
        /** Class rank for continuation scoring. */
        val classRank: Map<String, Int> = mapOf(
            "motorway" to 6, "trunk" to 5, "primary" to 4, "secondary" to 3,
            "tertiary" to 2, "unclassified" to 1, "residential" to 0,
        ),
    )

    private val params = Params()

    data class PathStep(
        val edge: Edge,
        val forward: Boolean,
        /** Distance-ahead at which this edge starts (0 for the current edge segment). */
        val startAheadM: Double,
        val confidence: Double,
    )

    /**
     * A corner projected onto the path. [corner] is already expressed in TRAVEL frame:
     * direction and entry/exit radii are mirrored when the edge is traversed against
     * its node order. Offsets stay in the edge's forward frame (knowledge-store keys).
     * [forward] records the traversal so camber (stored forward-frame) can be re-signed.
     */
    data class RawCorner(
        val corner: Corner,
        val distanceAheadM: Double,
        val pathConfidence: Double,
        val forward: Boolean,
    )

    data class RawHorizon(
        val steps: List<PathStep>,
        val totalLengthM: Double,
        val confidenceAtEnd: Double,
        val corners: List<RawCorner>,
        val hazards: List<Triple<Hazard, Double, Double>>,
    )

    fun build(pos: MatchedPosition): RawHorizon? {
        val firstEdge = map.edge(pos.edgeId) ?: return null
        val steps = ArrayList<PathStep>()
        var ahead = 0.0
        var confidence = pos.confidence
        var edge = firstEdge
        var forward = pos.forward
        var offset = pos.offsetM
        var guard = 0

        while (ahead < params.horizonM && guard++ < 64) {
            steps += PathStep(edge, forward, ahead, confidence)
            val remaining = if (forward) edge.lengthM - offset else offset
            ahead += remaining.coerceAtLeast(0.0)
            if (ahead >= params.horizonM) break

            val endNode = if (forward) edge.toNodeId else edge.fromNodeId
            val junction = map.junction(endNode) ?: break
            val exitBearing = exitBearingOf(edge, forward)
            val nextEdges = junction.edgeIds
                .filter { it != edge.id }
                .mapNotNull { map.edge(it) }
            var choices = nextEdges
                .mapNotNull { next -> scoreContinuation(edge, exitBearing, next, endNode, maxDeflectionDeg = 150.0) }
            if (choices.isEmpty()) {
                // A switchback split at its apex node deflects > 150° yet is the genuine
                // continuation. Allow it only when it is the SOLE way on, at reduced score.
                val relaxed = nextEdges
                    .mapNotNull { next -> scoreContinuation(edge, exitBearing, next, endNode, maxDeflectionDeg = 168.0) }
                if (relaxed.size == 1) choices = relaxed.map { it.first to it.second * 0.4 }
            }
            if (choices.isEmpty()) break

            val totalScore = choices.sumOf { it.second }
            val best = choices.maxBy { it.second }
            val prob = best.second / totalScore
            if (prob < params.branchPrune) break // ambiguous: stop the horizon here, engine goes quiet beyond

            confidence *= prob
            edge = best.first.first
            forward = best.first.second
            offset = if (forward) 0.0 else edge.lengthM
        }

        if (steps.isEmpty()) return null

        // Project corners and hazards into distance-ahead space.
        val corners = ArrayList<RawCorner>()
        val hazards = ArrayList<Triple<Hazard, Double, Double>>()
        for (step in steps) {
            val stepStartOffset = if (step === steps.first()) pos.offsetM else if (step.forward) 0.0 else step.edge.lengthM
            for (c in map.cornersOn(step.edge.id)) {
                val entryOffset = if (step.forward) c.startOffsetM else step.edge.lengthM - c.endOffsetM
                val delta = directedDelta(step.forward, stepStartOffset, entryOffset, step.edge.lengthM)
                    ?: continue
                val aheadAt = step.startAheadM + delta
                if (aheadAt in 0.0..params.horizonM) {
                    // Corner geometry is stored in the edge's node order. Driving the edge
                    // the other way, a stored LEFT is the driver's RIGHT and the entry and
                    // exit thirds swap.
                    val directed = if (step.forward) c else c.copy(
                        direction = when (c.direction) {
                            com.rallycopilot.core.model.Direction.LEFT -> com.rallycopilot.core.model.Direction.RIGHT
                            com.rallycopilot.core.model.Direction.RIGHT -> com.rallycopilot.core.model.Direction.LEFT
                        },
                        entryRadiusM = c.exitRadiusM,
                        exitRadiusM = c.entryRadiusM,
                    )
                    corners += RawCorner(directed, aheadAt, step.confidence, step.forward)
                }
            }
            val learned = knowledge?.cautionsOn(step.edge.id)?.map { b ->
                com.rallycopilot.core.model.Hazard(
                    step.edge.id,
                    b.bucket * com.rallycopilot.core.knowledge.RoadBucket.BUCKET_M +
                        com.rallycopilot.core.knowledge.RoadBucket.BUCKET_M / 2,
                    com.rallycopilot.core.model.HazardKind.LEARNED,
                )
            } ?: emptyList()
            for (h in map.hazardsOn(step.edge.id) + learned) {
                val hOffset = if (step.forward) h.offsetM else step.edge.lengthM - h.offsetM
                val delta = directedDelta(step.forward, stepStartOffset, hOffset, step.edge.lengthM)
                    ?: continue
                val aheadAt = step.startAheadM + delta
                if (aheadAt in 0.0..params.horizonM) hazards += Triple(h, aheadAt, step.confidence)
            }
        }
        corners.sortBy { it.distanceAheadM }
        hazards.sortBy { it.second }

        return RawHorizon(
            steps = steps,
            totalLengthM = ahead.coerceAtMost(params.horizonM),
            confidenceAtEnd = confidence,
            corners = corners,
            hazards = hazards,
        )
    }

    /** Distance from current position on the step's edge to a target offset, or null if behind. */
    private fun directedDelta(forward: Boolean, fromOffset: Double, toOffsetDirected: Double, lengthM: Double): Double? {
        // Work in "directed offset" space: distance travelled from the step's entry point.
        val fromDirected = if (forward) fromOffset else lengthM - fromOffset
        val delta = toOffsetDirected - fromDirected
        return if (delta >= -1.0) delta.coerceAtLeast(0.0) else null
    }

    private fun exitBearingOf(edge: Edge, forward: Boolean): Double {
        val g = edge.geometry
        return if (forward) Geo.bearingDeg(g[g.size - 2], g[g.size - 1])
        else Geo.bearingDeg(g[1], g[0])
    }

    /** Score a candidate continuation. Returns ((edge, forward), score) or null if untraversable. */
    private fun scoreContinuation(
        current: Edge,
        exitBearing: Double,
        next: Edge,
        viaNode: Long,
        maxDeflectionDeg: Double = 150.0,
    ): Pair<Pair<Edge, Boolean>, Double>? {
        val forward = when (viaNode) {
            next.fromNodeId -> true
            next.toNodeId -> false
            else -> return null
        }
        if (next.oneway && !forward) return null

        val entryBearing = if (forward) Geo.bearingDeg(next.geometry[0], next.geometry[1])
        else Geo.bearingDeg(next.geometry[next.geometry.size - 1], next.geometry[next.geometry.size - 2])
        val deflection = Geo.bearingDiffDeg(exitBearing, entryBearing)
        if (deflection > maxDeflectionDeg) return null // effectively a U-turn

        var score = 1.0
        // Straightest continuation preferred.
        score *= 1.0 - (deflection / 180.0) * 0.8
        // Same named/numbered road is a strong signal.
        if (current.ref != null && current.ref == next.ref) score *= 2.0
        else if (current.name != null && current.name == next.name) score *= 1.6
        // Same or higher functional class preferred.
        val curRank = params.classRank[current.highwayClass] ?: 1
        val nextRank = params.classRank[next.highwayClass] ?: 1
        if (nextRank >= curRank) score *= 1.3 else score *= 0.8

        return (next to forward) to score
    }
}
