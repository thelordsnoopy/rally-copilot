package com.rallycopilot.app.data

import android.content.ContentValues
import com.rallycopilot.core.knowledge.KnowledgeMath
import com.rallycopilot.core.knowledge.KnowledgeStore
import com.rallycopilot.core.knowledge.RoadBucket

/**
 * SQLite-backed personal knowledge layer, living in the app database. Every row is
 * 25 m of road your drives have taught the app something about.
 */
class KnowledgeDb(private val db: AppDb) : KnowledgeStore {

    init {
        db.writableDatabase.execSQL(
            """CREATE TABLE IF NOT EXISTS knowledge(
                edge_id INTEGER, bucket INTEGER,
                slow_events INTEGER DEFAULT 0, clean_passes INTEGER DEFAULT 0,
                rough_sum REAL DEFAULT 0, rough_n INTEGER DEFAULT 0,
                hazard_confirmed INTEGER DEFAULT 0, speed_factor REAL DEFAULT 1.0,
                camber_sum REAL DEFAULT 0, camber_n INTEGER DEFAULT 0,
                PRIMARY KEY(edge_id, bucket))"""
        )
        // Older installs: add camber columns in place.
        runCatching { db.writableDatabase.execSQL("ALTER TABLE knowledge ADD COLUMN camber_sum REAL DEFAULT 0") }
        runCatching { db.writableDatabase.execSQL("ALTER TABLE knowledge ADD COLUMN camber_n INTEGER DEFAULT 0") }
    }

    override fun get(edgeId: Long, bucket: Int): RoadBucket? =
        db.readableDatabase.rawQuery(
            "SELECT * FROM knowledge WHERE edge_id=? AND bucket=?",
            arrayOf(edgeId.toString(), bucket.toString())
        ).use { c ->
            if (!c.moveToFirst()) null else RoadBucket(
                edgeId = c.getLong(0), bucket = c.getInt(1),
                slowEvents = c.getInt(2), cleanPasses = c.getInt(3),
                roughSum = c.getDouble(4), roughN = c.getInt(5),
                hazardConfirmed = c.getInt(6) == 1, speedFactor = c.getDouble(7),
                camberSum = c.getDouble(8), camberN = c.getInt(9),
            )
        }

    override fun put(b: RoadBucket) {
        val v = ContentValues().apply {
            put("edge_id", b.edgeId); put("bucket", b.bucket)
            put("slow_events", b.slowEvents); put("clean_passes", b.cleanPasses)
            put("rough_sum", b.roughSum); put("rough_n", b.roughN)
            put("hazard_confirmed", if (b.hazardConfirmed) 1 else 0)
            put("speed_factor", b.speedFactor)
            put("camber_sum", b.camberSum); put("camber_n", b.camberN)
        }
        db.writableDatabase.insertWithOnConflict(
            "knowledge", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    override fun cautionsOn(edgeId: Long): List<RoadBucket> {
        val out = ArrayList<RoadBucket>()
        db.readableDatabase.rawQuery(
            "SELECT * FROM knowledge WHERE edge_id=?", arrayOf(edgeId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val b = RoadBucket(
                    edgeId = c.getLong(0), bucket = c.getInt(1),
                    slowEvents = c.getInt(2), cleanPasses = c.getInt(3),
                    roughSum = c.getDouble(4), roughN = c.getInt(5),
                    hazardConfirmed = c.getInt(6) == 1, speedFactor = c.getDouble(7),
                    camberSum = c.getDouble(8), camberN = c.getInt(9),
                )
                if (KnowledgeMath.warrantsCaution(b)) out += b
            }
        }
        return out
    }

    override fun factorFor(edgeId: Long, startM: Double, endM: Double): Double {
        val b0 = RoadBucket.bucketOf(minOf(startM, endM)) - 1
        val b1 = RoadBucket.bucketOf(maxOf(startM, endM)) + 1
        var factor = 1.0
        db.readableDatabase.rawQuery(
            "SELECT MIN(speed_factor) FROM knowledge WHERE edge_id=? AND bucket BETWEEN ? AND ?",
            arrayOf(edgeId.toString(), b0.toString(), b1.toString())
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) factor = c.getDouble(0) }
        return factor.coerceIn(0.6, 1.0)
    }

    override fun camberFor(edgeId: Long, startM: Double, endM: Double): Double? {
        val b0 = RoadBucket.bucketOf(minOf(startM, endM))
        val b1 = RoadBucket.bucketOf(maxOf(startM, endM))
        db.readableDatabase.rawQuery(
            "SELECT SUM(camber_sum), SUM(camber_n) FROM knowledge WHERE edge_id=? AND bucket BETWEEN ? AND ?",
            arrayOf(edgeId.toString(), b0.toString(), b1.toString())
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(1) && c.getInt(1) >= 5) {
                return c.getDouble(0) / c.getInt(1)
            }
        }
        return null
    }

    /** Camber sample accrual for the bucket currently under the wheels. */
    fun addCamber(edgeId: Long, offsetM: Double, deg: Double) {
        val bucket = RoadBucket.bucketOf(offsetM)
        val b = get(edgeId, bucket) ?: RoadBucket(edgeId, bucket)
        put(KnowledgeMath.addCamber(b, deg))
    }

    /** IMU roughness accrual for the bucket currently under the wheels. */
    fun addRoughness(edgeId: Long, offsetM: Double, rms: Double) {
        val bucket = RoadBucket.bucketOf(offsetM)
        val b = get(edgeId, bucket) ?: RoadBucket(edgeId, bucket)
        put(KnowledgeMath.addRoughness(b, rms))
    }
}
