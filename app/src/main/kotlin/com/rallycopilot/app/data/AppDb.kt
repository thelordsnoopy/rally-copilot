package com.rallycopilot.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rallycopilot.core.engine.RunLog
import com.rallycopilot.core.model.Conditions
import com.rallycopilot.core.model.CornerObservation
import com.rallycopilot.core.model.DriverProfile
import com.rallycopilot.core.model.Fix
import com.rallycopilot.core.model.RunEvent
import com.rallycopilot.core.model.SeverityBand
import org.json.JSONObject

/**
 * App-side persistence: runs, fixes, events, observations, profile.
 * Plain SQLite by design — fewer build-time moving parts than Room, and the
 * write pattern (append-only logs) doesn't need an ORM.
 *
 * ONE instance per process (get()) — screens and the service previously opened six
 * independent connections, risking SQLITE_BUSY between a screen query and the
 * service's write transactions.
 */
class AppDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "rallycopilot.db", null, 4) {

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: AppDb(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE runs(id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER,
            ended_at INTEGER, distance_m REAL DEFAULT 0, conditions TEXT, was_calibration INTEGER DEFAULT 0,
            feedback TEXT)""")
        db.execSQL("""CREATE TABLE run_fixes(run_id INTEGER, t_ms INTEGER, lat REAL, lon REAL,
            speed_mps REAL, bearing_deg REAL, accuracy_m REAL, edge_id INTEGER, offset_m REAL,
            confidence REAL, predicted INTEGER)""")
        db.execSQL("CREATE INDEX idx_rf ON run_fixes(run_id)")
        db.execSQL("CREATE INDEX idx_rf_run_t ON run_fixes(run_id, t_ms)")
        db.execSQL("CREATE TABLE run_events(run_id INTEGER, t_ms INTEGER, type TEXT, payload TEXT)")
        db.execSQL("""CREATE TABLE observations(run_id INTEGER, corner_id INTEGER, t_ms INTEGER,
            band TEXT, min_r REAL, v_entry REAL, v_min REAL, v_exit REAL, a_lat REAL,
            map_conf REAL, path_conf REAL, constrained INTEGER, conditions TEXT, throttle REAL,
            spirited INTEGER DEFAULT 1, car TEXT DEFAULT 'default')""")
        db.execSQL("CREATE INDEX idx_obs_run ON observations(run_id)")
        db.execSQL("CREATE TABLE kv(key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("""CREATE TABLE corner_audit(corner_id INTEGER PRIMARY KEY,
            passes INTEGER DEFAULT 0, ratio_ema REAL DEFAULT 1.0)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) db.execSQL("ALTER TABLE observations ADD COLUMN spirited INTEGER DEFAULT 1")
        if (old < 3) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_rf_run_t ON run_fixes(run_id, t_ms)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_obs_run ON observations(run_id)")
        }
        if (old < 4) {
            db.execSQL("ALTER TABLE observations ADD COLUMN car TEXT DEFAULT 'default'")
            db.execSQL("""CREATE TABLE IF NOT EXISTS corner_audit(corner_id INTEGER PRIMARY KEY,
                passes INTEGER DEFAULT 0, ratio_ema REAL DEFAULT 1.0)""")
        }
    }

    // ---- car identity ----
    // Profiles, learning cutoffs and observations are keyed by CAR — "vin:<VIN>"
    // once OBD has read one, "default" before that. One drive in a soft borrowed
    // car must never drag the learned profile of the real one.

    /** The car the app believes it is in: the last VIN OBD reported, or default. */
    fun activeCarKey(): String = kvGet("car_vin")?.let { "vin:$it" } ?: "default"

    // ---- runs ----

    fun startRun(conditions: Conditions, calibration: Boolean): Long {
        // A run whose process died never got endRun — close it against its last fix
        // so the log doesn't show phantom "0 min" runs forever.
        writableDatabase.execSQL(
            """UPDATE runs SET ended_at = COALESCE(
                 (SELECT MAX(t_ms) FROM run_fixes WHERE run_id = runs.id), started_at)
               WHERE ended_at IS NULL"""
        )
        val v = ContentValues().apply {
            put("started_at", System.currentTimeMillis())
            put("conditions", conditions.name)
            put("was_calibration", if (calibration) 1 else 0)
        }
        return writableDatabase.insert("runs", null, v)
    }

    fun endRun(runId: Long, distanceM: Double) {
        writableDatabase.execSQL(
            "UPDATE runs SET ended_at=?, distance_m=? WHERE id=?",
            arrayOf(System.currentTimeMillis(), distanceM, runId)
        )
    }

    fun setFeedback(runId: Long, answer: String) {
        writableDatabase.execSQL("UPDATE runs SET feedback=? WHERE id=?", arrayOf(answer, runId))
    }

    data class RunRow(val id: Long, val startedAt: Long, val endedAt: Long?, val distanceM: Double, val feedback: String?)

    fun runs(limit: Int = 50): List<RunRow> {
        val out = ArrayList<RunRow>()
        readableDatabase.rawQuery(
            "SELECT id, started_at, ended_at, distance_m, feedback FROM runs ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out += RunRow(
                c.getLong(0), c.getLong(1),
                if (c.isNull(2)) null else c.getLong(2), c.getDouble(3),
                if (c.isNull(4)) null else c.getString(4),
            )
        }
        return out
    }

    // ---- run log (implements the core port via a per-run adapter) ----

    fun runLogFor(runId: Long): RunLog = object : RunLog {
        override fun logFix(fix: Fix, matchedEdgeId: Long?, offsetM: Double?, confidence: Double?, wasPredicted: Boolean) {
            val v = ContentValues().apply {
                put("run_id", runId); put("t_ms", fix.tMs)
                put("lat", fix.lat); put("lon", fix.lon)
                put("speed_mps", fix.speedMps); put("bearing_deg", fix.bearingDeg)
                put("accuracy_m", fix.accuracyM)
                matchedEdgeId?.let { put("edge_id", it) }
                offsetM?.let { put("offset_m", it) }
                confidence?.let { put("confidence", it) }
                put("predicted", if (wasPredicted) 1 else 0)
            }
            writableDatabase.insert("run_fixes", null, v)
        }

        override fun logEvent(event: RunEvent) {
            val v = ContentValues().apply {
                put("run_id", runId); put("t_ms", event.tMs)
                put("type", event.type.name); put("payload", event.payload)
            }
            writableDatabase.insert("run_events", null, v)
        }
    }

    fun fixesFor(runId: Long): List<Fix> {
        val out = ArrayList<Fix>()
        readableDatabase.rawQuery(
            "SELECT t_ms, lat, lon, speed_mps, bearing_deg, accuracy_m FROM run_fixes WHERE run_id=? ORDER BY t_ms",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) out += Fix(
                c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getDouble(4), c.getDouble(5)
            )
        }
        return out
    }

    // ---- observations ----

    fun saveObservations(rows: List<CornerObservation>, car: String = activeCarKey()) {
        // First time a real VIN shows up, the pre-VIN history inherits it: every
        // "default" row so far was almost certainly driven in this same car.
        if (car != "default" && kvGet("obs_car_backfill_v1") == null) {
            writableDatabase.execSQL(
                "UPDATE observations SET car=? WHERE car='default'", arrayOf(car))
            kvPut("obs_car_backfill_v1", car)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (o in rows) {
                val v = ContentValues().apply {
                    put("car", car)
                    put("run_id", o.runId); put("corner_id", o.cornerId); put("t_ms", o.tMs)
                    put("band", o.band.name); put("min_r", o.minRadiusM)
                    put("v_entry", o.vEntryMps); put("v_min", o.vMinMps); put("v_exit", o.vExitMps)
                    put("a_lat", o.aLatObserved); put("map_conf", o.mapConfidence)
                    put("path_conf", o.pathConfidence); put("constrained", if (o.wasConstrained) 1 else 0)
                    put("conditions", o.conditions.name)
                    o.throttleMean?.let { put("throttle", it) }
                    put("spirited", if (o.spirited) 1 else 0)
                }
                db.insert("observations", null, v)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readObservations(where: String, args: Array<String>?): List<CornerObservation> {
        val out = ArrayList<CornerObservation>()
        readableDatabase.rawQuery("SELECT * FROM observations $where ORDER BY t_ms", args).use { c ->
            while (c.moveToNext()) out += CornerObservation(
                runId = c.getLong(0), cornerId = c.getLong(1), tMs = c.getLong(2),
                band = SeverityBand.valueOf(c.getString(3)), minRadiusM = c.getDouble(4),
                vEntryMps = c.getDouble(5), vMinMps = c.getDouble(6), vExitMps = c.getDouble(7),
                aLatObserved = c.getDouble(8), mapConfidence = c.getDouble(9),
                pathConfidence = c.getDouble(10), wasConstrained = c.getInt(11) == 1,
                conditions = Conditions.valueOf(c.getString(12)),
                throttleMean = if (c.isNull(13)) null else c.getDouble(13),
                spirited = c.getInt(14) == 1,
            )
        }
        return out
    }

    fun allObservations(): List<CornerObservation> = readObservations("", null)

    // ---- learning cutoff ----
    // Corner observations are never deleted: they are the only record of what you
    // actually drove. But a profile is DERIVED from the whole history every time,
    // so observations taken before a maths fix would poison every future drive
    // forever, and "reset profile" was a no-op — the next drive re-derived the old
    // numbers straight back. A cutoff timestamp fixes both: history is kept, and
    // learning simply ignores anything recorded before it.

    fun learnCutoffMs(car: String = activeCarKey()): Long =
        kvGet("learn_from_ms_$car")?.toLongOrNull()
            ?: kvGet("learn_from_ms")?.toLongOrNull() ?: 0L

    /** Start learning afresh from now — for THIS car only. Keeps every observation. */
    fun resetLearning(conditions: Conditions, car: String = activeCarKey()) {
        kvPut("learn_from_ms_$car", System.currentTimeMillis().toString())
        saveProfile(coldStart(conditions), conditions, car)
    }

    /** The observations a profile may be derived from: this car, right conditions,
     *  after the cutoff. */
    fun observationsForLearning(conditions: Conditions, car: String = activeCarKey()): List<CornerObservation> {
        val cutoff = learnCutoffMs(car)
        return readObservations("WHERE conditions=? AND car=? AND t_ms>=?",
            arrayOf(conditions.name, car, cutoff.toString()))
    }

    /**
     * One-time reset on upgrade. Everything recorded before v0.8.2 used corner
     * radii from fragmented OSM geometry, which inflates observed lateral g
     * (aLat = v²/radius), so those rows would drag the model permanently.
     */
    fun migrateLearningCutoff() {
        if (kvGet("learn_cutoff_v1") == "done") return
        kvPut("learn_from_ms", System.currentTimeMillis().toString())
        kvPut("learn_cutoff_v1", "done")
        for (c in Conditions.entries) saveProfile(coldStart(c), c)
    }

    fun observationsFor(runId: Long): List<CornerObservation> =
        readObservations("WHERE run_id=?", arrayOf(runId.toString()))

    /** User override from the post-drive sheet: reclassify a whole run's style, then
     *  the caller re-derives the profile from history so the correction takes effect. */
    fun overrideRunStyle(runId: Long, spirited: Boolean) {
        writableDatabase.execSQL(
            "UPDATE observations SET spirited=? WHERE run_id=?",
            arrayOf(if (spirited) 1 else 0, runId),
        )
    }

    // ---- profiles (stored as JSON in kv; always re-derivable from observations) ----
    // Dry and wet are SEPARATE learned profiles: wet observations never touch the dry
    // model and vice versa. The wet cold start seeds lower (0.4 g vs 0.5 g).

    /** The default car keeps the historical key, so old installs migrate for free;
     *  a VIN-keyed car reads the historical profile once as a seed, then diverges. */
    private fun profileKey(c: Conditions, car: String) =
        if (car == "default") "profile_${c.name}" else "profile_${car}_${c.name}"

    private fun coldStart(c: Conditions) =
        if (c == Conditions.WET) DriverProfile(emptyMap(), emptyMap(), seedALat = 0.4 * 9.81)
        else DriverProfile.COLD_START

    fun loadProfile(conditions: Conditions = Conditions.DRY, car: String = activeCarKey()): DriverProfile {
        val json = kvGet(profileKey(conditions, car))
            ?: kvGet(profileKey(conditions, "default")) // pre-VIN history seeds the car
            ?: kvGet("profile").takeIf { conditions == Conditions.DRY } // legacy migration
            ?: return coldStart(conditions)
        return try {
            val o = JSONObject(json)
            val aLat = HashMap<SeverityBand, Double>()
            val counts = HashMap<SeverityBand, Int>()
            val a = o.getJSONObject("aLat")
            for (k in a.keys()) aLat[SeverityBand.valueOf(k)] = a.getDouble(k)
            val n = o.getJSONObject("counts")
            for (k in n.keys()) counts[SeverityBand.valueOf(k)] = n.getInt(k)
            DriverProfile(
                aLatByBand = aLat, sampleCountByBand = counts,
                pushFactor = o.optDouble("push", 1.0),
                capALat = if (o.has("cap")) o.getDouble("cap") else null,
                seedALat = o.optDouble("seed", coldStart(conditions).seedALat),
            )
        } catch (_: Exception) {
            coldStart(conditions)
        }
    }

    fun saveProfile(p: DriverProfile, conditions: Conditions = Conditions.DRY, car: String = activeCarKey()) {
        val o = JSONObject()
        o.put("aLat", JSONObject(p.aLatByBand.mapKeys { it.key.name }))
        o.put("counts", JSONObject(p.sampleCountByBand.mapKeys { it.key.name }))
        o.put("push", p.pushFactor)
        p.capALat?.let { o.put("cap", it) }
        o.put("seed", p.seedALat)
        kvPut(profileKey(conditions, car), o.toString())
    }

    fun kvGet(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM kv WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun kvPut(key: String, value: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO kv VALUES (?,?)", arrayOf(key, value))
    }
}
