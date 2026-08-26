package com.rallycopilot.core.engine

import com.rallycopilot.core.model.Corner
import com.rallycopilot.core.model.Edge
import com.rallycopilot.core.model.Fix
import com.rallycopilot.core.model.Hazard
import com.rallycopilot.core.model.Junction
import com.rallycopilot.core.model.LatLon
import com.rallycopilot.core.model.RunEvent
import com.rallycopilot.core.model.Utterance
import kotlinx.coroutines.flow.Flow

/**
 * The ports that keep :core pure. The Android app provides real implementations;
 * tests provide fakes and replays. This is the decision that makes the project tractable.
 */

/** Where fixes come from: real GPS, or a replayed run log. */
interface FixSource {
    val fixes: Flow<Fix>
}

/** Injected time, so replays are deterministic. */
interface Clock {
    fun nowMs(): Long
}

/** Read access to the precomputed region database. */
interface MapStore {
    /** Edges whose geometry passes within [radiusM] of [p]. Spatially indexed. */
    fun edgesNear(p: LatLon, radiusM: Double): List<Edge>
    fun edge(id: Long): Edge?
    fun junction(nodeId: Long): Junction?
    fun cornersOn(edgeId: Long): List<Corner>
    fun hazardsOn(edgeId: Long): List<Hazard>
    fun isEmptyAt(p: LatLon): Boolean
}

/** Plays pre-rendered vocabulary clips. Implementation handles A2DP keep-alive. */
interface AudioSink {
    /** Duration in ms of the given clip key, from the voice pack manifest. */
    fun clipDurationMs(key: String): Long
    /** Enqueue for immediate gapless playback. */
    fun play(utterance: Utterance)
    fun isSpeaking(): Boolean
    /** Estimated ms of audio still queued/playing. */
    fun remainingMs(): Long
}

/** Persists everything. Implementations: Room on device, in-memory in tests. */
interface RunLog {
    fun logFix(fix: Fix, matchedEdgeId: Long?, offsetM: Double?, confidence: Double?, wasPredicted: Boolean)
    fun logEvent(event: RunEvent)
}

/** Live vehicle data from the ELM327, if connected. All null when absent. */
interface VehicleData {
    /** Wheel speed, m/s — preferred over GPS speed when fresh. */
    fun obdSpeedMps(): Double?
    fun rpm(): Int?
    fun throttle01(): Double?
    fun coolantC(): Int?
    fun batteryV(): Double?
    fun currentGear(): Int?
}

object NullVehicleData : VehicleData {
    override fun obdSpeedMps(): Double? = null
    override fun rpm(): Int? = null
    override fun throttle01(): Double? = null
    override fun coolantC(): Int? = null
    override fun batteryV(): Double? = null
    override fun currentGear(): Int? = null
}
