package com.rallycopilot.core.model

/** A single GNSS (or replayed) fix. Times are epoch millis from the injected clock domain. */
data class Fix(
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    val bearingDeg: Double,
    val accuracyM: Double,
)

/** Severity bands shared by the note table and the learned driver profile. */
enum class SeverityBand(val spoken: String, val urgent: Boolean) {
    HAIRPIN("hairpin", true),
    ONE("one", true),
    TWO("two", true),
    THREE("three", false),
    FOUR("four", false),
    FIVE("five", false),
    SIX("six", false),
    FLAT("", false); // not called

    companion object {
        /** Default radius thresholds (metres) → band. User-adjustable at runtime. */
        fun forRadius(rM: Double, table: SeverityTable = SeverityTable.DEFAULT): SeverityBand =
            table.bandFor(rM)
    }
}

/** Radius → band thresholds. Upper bound is exclusive, metres. */
data class SeverityTable(val upperBounds: List<Pair<Double, SeverityBand>>) {
    fun bandFor(rM: Double): SeverityBand =
        upperBounds.firstOrNull { rM < it.first }?.second ?: SeverityBand.FLAT

    companion object {
        val DEFAULT = SeverityTable(
            listOf(
                12.0 to SeverityBand.HAIRPIN,
                25.0 to SeverityBand.ONE,
                40.0 to SeverityBand.TWO,
                70.0 to SeverityBand.THREE,
                120.0 to SeverityBand.FOUR,
                200.0 to SeverityBand.FIVE,
                400.0 to SeverityBand.SIX,
            )
        )
    }
}

enum class Direction { LEFT, RIGHT }

/** A precomputed corner on an edge, read from the region database. */
data class Corner(
    val id: Long,
    val edgeId: Long,
    val startOffsetM: Double,
    val apexOffsetM: Double,
    val endOffsetM: Double,
    val direction: Direction,
    val minRadiusM: Double,
    val entryRadiusM: Double,
    val exitRadiusM: Double,
    val arcLengthM: Double,
    val confidence: Double,
    /** Average slope of the 80 m approaching this corner. Positive = uphill.
     *  Gravity helps you stop going up and fights you going down. */
    val approachGrade: Double = 0.0,
)

/** Fixed hazards from OSM node tags, called like cautions. */
enum class HazardKind(val spoken: String) {
    JUNCTION("junction"),
    CROSSING("crossing"),
    FORD("ford"),
    CATTLE_GRID("cattle grid"),
    NARROW_BRIDGE("narrow bridge"),
    GATE("gate"),
    LEVEL_CROSSING("level crossing"),
    /** Learned from YOUR drives — repeated slowdowns / rough surface / confirmed prompt. */
    LEARNED("caution"),
    /** Fixed speed camera. The one call that is ALWAYS made, spirited or not. */
    SPEED_CAMERA("speed camera"),
    /** Average-speed (SPECS-style) enforcement zone. */
    AVERAGE_CAMERA("average speed check"),
    /** A brow the road disappears over — the one hazard corner geometry cannot express. */
    CREST("crest"),
    ;

    /** Announced even in quiet mode — the whole point of quiet mode is that these
     *  still get through when nothing else does. */
    val isAlwaysAnnounced: Boolean get() = this == SPEED_CAMERA || this == AVERAGE_CAMERA

    /** Called by name alone. "Caution crest" is not how a co-driver says it. */
    val isPlainCall: Boolean
        get() = this == SPEED_CAMERA || this == AVERAGE_CAMERA || this == CREST
}

data class Hazard(val edgeId: Long, val offsetM: Double, val kind: HazardKind)

/** One drivable edge of the road graph. Geometry is resampled ~5 m points. */
data class Edge(
    val id: Long,
    val fromNodeId: Long,
    val toNodeId: Long,
    val lengthM: Double,
    val name: String?,
    val ref: String?,
    val highwayClass: String,
    val maxspeedKph: Int?,
    val oneway: Boolean,
    /** Packed lat/lon pairs, resampled at build time. */
    val geometry: List<LatLon>,
)

data class LatLon(val lat: Double, val lon: Double)

data class Junction(val nodeId: Long, val lat: Double, val lon: Double, val edgeIds: List<Long>)

/** Where the matcher thinks we are on the graph. */
data class MatchedPosition(
    val tMs: Long,
    val edgeId: Long,
    val offsetM: Double,
    /** true = travelling from edge.fromNodeId toward toNodeId */
    val forward: Boolean,
    val speedMps: Double,
    val bearingDeg: Double,
    val confidence: Double,
)

/** Modifiers attached to a called corner. */
enum class Modifier(val spoken: String) {
    TIGHTENS("tightens"),
    OPENS("opens"),
    LONG("long"),
    INTO("into"),
    /** Road leans out of the corner — the radius maths flatters this one. */
    OFF_CAMBER("off_camber"),
    /** Low map confidence: the geometry may under-claim. Spoken before the call. */
    CAUTION("caution"),
}

/** A corner projected onto the current predicted path. */
data class HorizonCorner(
    val corner: Corner,
    val distanceAheadM: Double,
    val pathConfidence: Double,
    val band: SeverityBand,
    val modifiers: List<Modifier>,
    val vTargetMps: Double,
    val brakingPointM: Double,
    val triggerDistanceM: Double,
    val gear: Int? = null,
)

data class HorizonHazard(val hazard: Hazard, val distanceAheadM: Double, val pathConfidence: Double)

/** The predicted path ahead and everything on it. */
data class Horizon(
    val builtAtMs: Long,
    val pathEdgeIds: List<Long>,
    val totalLengthM: Double,
    val confidenceAtEnd: Double,
    val corners: List<HorizonCorner>,
    val hazards: List<HorizonHazard>,
    /** Road class of the edge the horizon starts on — the expected-speed baseline resets when it changes. */
    val currentEdgeHighway: String? = null,
    val currentEdgeMaxspeedKph: Int? = null,
)

enum class Conditions { DRY, WET }

/** The learned driver model. Derived from observations, never edited directly. */
data class DriverProfile(
    val aLatByBand: Map<SeverityBand, Double>,
    val sampleCountByBand: Map<SeverityBand, Int>,
    val pushFactor: Double = 1.0,
    /** Optional absolute ceiling on effective lateral g. Null = off (default, by user decision). */
    val capALat: Double? = null,
    val seedALat: Double = SEED_A_LAT,
) {
    /** Blended per-band lateral acceleration (m/s²), spirited seed fading out as samples arrive. */
    fun aLatFor(band: SeverityBand): Double {
        val learned = aLatByBand[band]
        val n = sampleCountByBand[band] ?: 0
        val blended = if (learned == null || n == 0) seedALat
        else (n * learned + BLEND_K * seedALat) / (n + BLEND_K)
        return capALat?.let { minOf(blended, it) } ?: blended
    }

    companion object {
        /** 0.5 g spirited cold start, in m/s². */
        const val SEED_A_LAT = 0.5 * 9.81
        const val BLEND_K = 20
        val COLD_START = DriverProfile(emptyMap(), emptyMap())
    }
}

/** One corner you actually drove — the learning system's training row. */
data class CornerObservation(
    val runId: Long,
    val cornerId: Long,
    val tMs: Long,
    val band: SeverityBand,
    val minRadiusM: Double,
    val vEntryMps: Double,
    val vMinMps: Double,
    val vExitMps: Double,
    val aLatObserved: Double,
    val mapConfidence: Double,
    val pathConfidence: Double,
    val wasConstrained: Boolean,
    val conditions: Conditions,
    /** Mean throttle through the corner 0..1, if OBD connected. Sharper constraint detection. */
    val throttleMean: Double? = null,
    /** Was the style detector reading SPIRITED when this corner was driven? Only
     *  spirited corners train the model — normal driving must never drag it down. */
    val spirited: Boolean = true,
    /**
     * Did the map matcher actually place the car on this corner's edge while it was
     * being driven?
     *
     * [pathConfidence] is a PREDICTION — how sure the app was, beforehand, that you
     * would go this way — and it is the right question for deciding whether to speak
     * a call. It is the wrong question for learning, which happens afterwards, when
     * whether you drove the corner is no longer a matter of opinion. Gating learning
     * on the prediction threw away the best samples of the first real drive: a 0.90 g
     * three, a 0.78 g five and a 0.69 g two, all driven, all confirmed by the
     * matcher, all discarded because the app had not been sure in advance.
     */
    val confirmed: Boolean = true,
    /**
     * The car was sliding through this corner — body rotation and path curvature
     * disagreed (see SlipEstimator).
     *
     * `aLatObserved` is computed as v²/R against the MAP's radius, which silently
     * assumes the car followed the road's curve. A car that is understeering wide
     * or rotating did not, so the number is not a measurement of grip and must not
     * become one. With no absolute ceiling on the learning loop by the user's
     * decision, a slide teaching the model that a corner is quick is exactly the
     * drift the remaining guardrails exist to prevent.
     */
    val slid: Boolean = false,
)

enum class FeedbackAnswer { EASY, GOOD, HARD }

/** A single utterance queued for the audio engine: an ordered list of vocabulary clip keys. */
data class Utterance(
    val clipKeys: List<String>,
    val urgent: Boolean,
    /** Distance along route at which this must have FINISHED playing. */
    val deadlineDistanceM: Double,
    val forCornerId: Long?,
)

/** Run-log event types. The negative ones matter as much as the positive ones. */
enum class RunEventType {
    NOTE_SPOKEN, NOTE_SUPPRESSED_LOW_CONFIDENCE, NOTE_CHAINED, NOTE_COMPRESSED,
    /** Driver said "wrong" — the last call plus its context, for offline tuning. */
    NOTE_FLAGGED,
    HORIZON_REBUILT, MPP_AMBIGUOUS, MATCH_LOST, GPS_LOST, REGION_MISSING,
    OBSERVATION_RECORDED, OBSERVATION_REJECTED, HAZARD_SPOKEN,
    OBD_CONNECTED, OBD_LOST, HEALTH_WARNING, INCIDENT_SUSPECTED,
    HAZARD_PROMPT, HAZARD_CONFIRMED, HAZARD_AUTO_NO,
}

data class RunEvent(val tMs: Long, val type: RunEventType, val payload: String = "")
