package com.rallycopilot.app.drive

import com.rallycopilot.core.engine.MapStore
import com.rallycopilot.core.geo.Geo
import com.rallycopilot.core.geo.Polyline
import com.rallycopilot.core.model.Edge
import com.rallycopilot.core.model.Fix
import com.rallycopilot.core.model.LatLon
import kotlin.math.sqrt

/**
 * Synthesises a realistic drive along a real road from the region database:
 * walks connected edges from a twisty named road, slows for corners, accelerates
 * on straights. Used for the demo mode and for desk-testing the whole pipeline
 * without GPS.
 */
object DemoDrive {

    data class Sample(val p: LatLon, val bearingDeg: Double, val speedMps: Double)

    /** Build ~[lengthM] of drive samples spaced [stepM] apart along a real road. */
    fun route(map: MapStore, startRef: String = "B4066", lengthM: Double = 8000.0, stepM: Double = 5.0): List<Sample> {
        val start = findStart(map, startRef) ?: return emptyList()
        val chain = walk(map, start, lengthM)
        if (chain.isEmpty()) return emptyList()

        // Concatenate geometry in travel order.
        val points = ArrayList<LatLon>()
        for ((edge, forward) in chain) {
            val g = if (forward) edge.geometry else edge.geometry.reversed()
            if (points.isEmpty()) points += g else points += g.drop(1)
        }
        if (points.size < 3) return emptyList()
        val cum = Polyline.cumulative(points)

        // Corner-limited speed profile: target sqrt(aLat*R) at each point, then
        // forward/backward pass to respect accel/brake limits. Classic and cheap.
        val aLat = 0.45 * 9.81
        val aAcc = 2.2
        val aBrk = 3.5
        val vMax = 26.0 // ~58 mph demo ceiling
        val n = points.size
        val target = DoubleArray(n) { vMax }
        // radius via circumscribed circle on consecutive triplets
        for (i in 1 until n - 1) {
            val origin = points[i]
            val p0 = Geo.toXY(points[i - 1], origin)
            val p1 = Geo.toXY(points[i], origin)
            val p2 = Geo.toXY(points[i + 1], origin)
            val r = com.rallycopilot.core.geo.Curvature.circumradius(p0, p1, p2)
            if (r < 600) target[i] = minOf(vMax, sqrt(aLat * r))
        }
        // backward pass (braking), forward pass (acceleration)
        for (i in n - 2 downTo 0) {
            val d = cum[i + 1] - cum[i]
            target[i] = minOf(target[i], sqrt(target[i + 1] * target[i + 1] + 2 * aBrk * d))
        }
        target[0] = minOf(target[0], 8.0)
        for (i in 1 until n) {
            val d = cum[i] - cum[i - 1]
            target[i] = minOf(target[i], sqrt(target[i - 1] * target[i - 1] + 2 * aAcc * d))
        }

        val out = ArrayList<Sample>(n)
        for (i in 0 until n) {
            val bearing = if (i < n - 1) Geo.bearingDeg(points[i], points[i + 1])
            else Geo.bearingDeg(points[i - 1], points[i])
            out += Sample(points[i], bearing, target[i].coerceAtLeast(3.0))
        }
        return out
    }

    /** Fixes from samples: timestamps derived from the speed profile, 5 Hz-ish. */
    fun fixes(samples: List<Sample>, startTimeMs: Long): List<Fix> {
        val out = ArrayList<Fix>(samples.size)
        var t = startTimeMs.toDouble()
        for (i in samples.indices) {
            if (i > 0) {
                val d = Geo.haversineM(samples[i - 1].p, samples[i].p)
                val v = ((samples[i - 1].speedMps + samples[i].speedMps) / 2).coerceAtLeast(1.0)
                t += d / v * 1000.0
            }
            out += Fix(
                tMs = t.toLong(),
                lat = samples[i].p.lat, lon = samples[i].p.lon,
                speedMps = samples[i].speedMps,
                bearingDeg = samples[i].bearingDeg,
                accuracyM = 4.0,
            )
        }
        return out
    }

    private fun findStart(map: MapStore, ref: String): Pair<Edge, Boolean>? {
        // Probe cells across the region for an edge with the wanted ref; region store
        // has no by-ref query, so scan a coarse lat/lon grid around Stroud.
        for (dLat in -20..20) for (dLon in -20..20) {
            val p = LatLon(51.745 + dLat * 0.01, -2.218 + dLon * 0.01)
            val hit = map.edgesNear(p, 900.0).firstOrNull { it.ref == ref && it.lengthM > 150 }
            if (hit != null) return hit to true
        }
        // Fallback: any long edge near Stroud.
        val any = map.edgesNear(LatLon(51.745, -2.218), 3000.0)
            .filter { it.lengthM > 300 }
            .maxByOrNull { it.lengthM }
        return any?.let { it to true }
    }

    private fun walk(map: MapStore, start: Pair<Edge, Boolean>, lengthM: Double): List<Pair<Edge, Boolean>> {
        val chain = ArrayList<Pair<Edge, Boolean>>()
        var (edge, forward) = start
        var total = 0.0
        val used = HashSet<Long>()
        while (total < lengthM && edge.id !in used) {
            used += edge.id
            chain += edge to forward
            total += edge.lengthM
            val endNode = if (forward) edge.toNodeId else edge.fromNodeId
            val junction = map.junction(endNode) ?: break
            val exitBearing = run {
                val g = if (forward) edge.geometry else edge.geometry.reversed()
                Geo.bearingDeg(g[g.size - 2], g[g.size - 1])
            }
            var best: Pair<Edge, Boolean>? = null
            var bestScore = -1.0
            for (nextId in junction.edgeIds) {
                if (nextId == edge.id) continue
                val next = map.edge(nextId) ?: continue
                if (next.id in used) continue
                val nf = when (endNode) {
                    next.fromNodeId -> true
                    next.toNodeId -> false
                    else -> continue
                }
                if (next.oneway && !nf) continue
                val g = if (nf) next.geometry else next.geometry.reversed()
                if (g.size < 2) continue
                val entry = Geo.bearingDeg(g[0], g[1])
                val defl = Geo.bearingDiffDeg(exitBearing, entry)
                if (defl > 140) continue
                var score = 1.0 - defl / 180.0
                if (next.ref != null && next.ref == edge.ref) score += 1.0
                if (score > bestScore) { bestScore = score; best = next to nf }
            }
            best ?: break
            edge = best.first; forward = best.second
        }
        return chain
    }
}
