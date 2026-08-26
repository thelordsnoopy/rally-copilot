package com.rallycopilot.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.rallycopilot.core.engine.MapStore
import com.rallycopilot.core.geo.Geo
import com.rallycopilot.core.model.Corner
import com.rallycopilot.core.model.Direction
import com.rallycopilot.core.model.Edge
import com.rallycopilot.core.model.Hazard
import com.rallycopilot.core.model.HazardKind
import com.rallycopilot.core.model.Junction
import com.rallycopilot.core.model.LatLon
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Reads the region database produced by tools/mapbuild. The bundled region asset is
 * copied to filesDir (atomically: temp file + rename, so a crash mid-copy never
 * leaves a truncated database behind) and re-copied whenever the APK version
 * changes — an update shipping corrected map data must actually take effect.
 * Edges are cached in memory as they are touched — a drive touches a few hundred
 * edges, not sixty thousand.
 *
 * Methods are synchronized: the engine thread and the map view both read this.
 */
class SqliteMapStore(context: Context, assetName: String = "regions/stroud30.sqlite") : MapStore {

    private val db: SQLiteDatabase
    /** Copied region file length — moves only when the map data itself changes.
     *  Used to invalidate stores keyed by (unstable) corner ids. */
    val fingerprint: Long
    private val edgeCache = HashMap<Long, Edge>()
    private val cornerCache = HashMap<Long, List<Corner>>()
    private val hazardCache = HashMap<Long, List<Hazard>>()
    private val junctionCache = HashMap<Long, Junction?>()

    init {
        val f = File(context.filesDir, "region.sqlite")
        val versionFile = File(context.filesDir, "region.version")
        val wantVersion = com.rallycopilot.app.BuildConfig.VERSION_CODE.toString()

        fun copyAsset() {
            val tmp = File(context.filesDir, "region.sqlite.tmp")
            context.assets.open(assetName).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) throw java.io.IOException("rename failed: ${tmp.path}")
            versionFile.writeText(wantVersion)
        }

        fun openValidated(): SQLiteDatabase {
            val d = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
            // Cheap integrity probe: a truncated copy fails here, not mid-drive.
            d.rawQuery("SELECT value FROM meta WHERE key='schema'", null).use { it.moveToFirst() }
            return d
        }

        if (!f.exists() || versionFile.takeIf { it.exists() }?.readText() != wantVersion) copyAsset()
        db = try {
            openValidated()
        } catch (_: Exception) {
            // Corrupt (earlier crash mid-copy on an old build, or disk trouble): recopy once.
            copyAsset()
            openValidated()
        }
        fingerprint = f.length()
    }

    @Synchronized
    fun close() { runCatching { db.close() } }

    private fun cellOf(lat: Double, lon: Double) =
        "${floor(lat * 100).toInt()}:${floor(lon * 100).toInt()}"

    @Synchronized
    override fun edgesNear(p: LatLon, radiusM: Double): List<Edge> {
        // Cover the requested radius honestly, in RINGS OF CELLS sized from the
        // radius — a fixed 3x3 block silently capped every caller at one cell's
        // reach, and the two axes are not the same size. 0.01 deg of latitude is
        // ~1113 m everywhere, but 0.01 deg of LONGITUDE is 1113·cos(lat) — about
        // 690 m at Stroud. So a 3x3 block guarantees only ~690 m to the east or
        // west, which does not even cover the 700 m the map view asks for.
        //
        // Worst case the car sits hard against a cell boundary, so the ring count
        // is ceil(radius / cell size): enough cells to reach `radius` beyond the
        // far edge of the cell we are standing in.
        val latCellM = 1113.0
        val lonCellM = latCellM * kotlin.math.cos(Math.toRadians(p.lat)).coerceAtLeast(0.05)
        val nLat = kotlin.math.ceil(radiusM / latCellM).toInt().coerceIn(1, 8)
        val nLon = kotlin.math.ceil(radiusM / lonCellM).toInt().coerceIn(1, 8)
        val cells = ArrayList<String>((2 * nLat + 1) * (2 * nLon + 1))
        for (dLat in -nLat..nLat) for (dLon in -nLon..nLon) {
            cells += cellOf(p.lat + dLat * 0.01, p.lon + dLon * 0.01)
        }
        val ids = LinkedHashSet<Long>()
        db.rawQuery(
            "SELECT DISTINCT edge_id FROM edge_cells WHERE cell IN (${cells.joinToString(",") { "?" }})",
            cells.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) ids += c.getLong(0)
        }
        // Cells are a coarse pre-filter — in a town the block above holds a couple of
        // thousand edges, and every one of them would be unpacked, held and redrawn
        // each frame. Now that the ring count is honest the radius has to be honest
        // too: keep only edges that genuinely come within it. Geometry is resampled
        // at ~5 m, so testing the points is exact enough at this range.
        val r2 = radiusM * radiusM
        return ids.mapNotNull { edge(it) }.filter { e ->
            e.geometry.any { pt -> Geo.approxSquareMetres(p, pt) <= r2 }
        }
    }

    @Synchronized
    override fun edge(id: Long): Edge? = edgeCache.getOrPut(id) {
        db.rawQuery("SELECT * FROM edges WHERE id=?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            Edge(
                id = c.getLong(0),
                fromNodeId = c.getLong(1),
                toNodeId = c.getLong(2),
                lengthM = c.getDouble(3),
                name = if (c.isNull(4)) null else c.getString(4),
                ref = if (c.isNull(5)) null else c.getString(5),
                highwayClass = c.getString(6),
                maxspeedKph = if (c.isNull(7)) null else c.getInt(7),
                oneway = c.getInt(8) == 1,
                geometry = unpack(c.getBlob(9)),
            )
        }
    }

    @Synchronized
    override fun junction(nodeId: Long): Junction? = junctionCache.getOrPut(nodeId) {
        db.rawQuery("SELECT lat, lon, edge_ids FROM junctions WHERE node_id=?", arrayOf(nodeId.toString())).use { c ->
            if (!c.moveToFirst()) null
            else Junction(nodeId, c.getDouble(0), c.getDouble(1),
                c.getString(2).split(",").mapNotNull { it.toLongOrNull() })
        }
    }

    @Synchronized
    override fun cornersOn(edgeId: Long): List<Corner> = cornerCache.getOrPut(edgeId) {
        val out = ArrayList<Corner>()
        db.rawQuery("SELECT * FROM corners WHERE edge_id=? ORDER BY start_m", arrayOf(edgeId.toString())).use { c ->
            while (c.moveToNext()) {
                out += Corner(
                    id = c.getLong(0), edgeId = c.getLong(1),
                    startOffsetM = c.getDouble(2), apexOffsetM = c.getDouble(3), endOffsetM = c.getDouble(4),
                    direction = if (c.getString(5) == "LEFT") Direction.LEFT else Direction.RIGHT,
                    minRadiusM = c.getDouble(6), entryRadiusM = c.getDouble(7), exitRadiusM = c.getDouble(8),
                    arcLengthM = c.getDouble(9), confidence = c.getDouble(10),
                    // Column added in schema 2; older copies simply read as flat.
                    approachGrade = if (c.columnCount > 11 && !c.isNull(11)) c.getDouble(11) else 0.0,
                )
            }
        }
        out
    }

    @Synchronized
    override fun hazardsOn(edgeId: Long): List<Hazard> = hazardCache.getOrPut(edgeId) {
        val out = ArrayList<Hazard>()
        db.rawQuery("SELECT offset_m, kind FROM hazards WHERE edge_id=?", arrayOf(edgeId.toString())).use { c ->
            while (c.moveToNext()) {
                val kind = runCatching { HazardKind.valueOf(c.getString(1)) }.getOrNull() ?: continue
                out += Hazard(edgeId, c.getDouble(0), kind)
            }
        }
        out
    }

    @Synchronized
    override fun isEmptyAt(p: LatLon): Boolean = edgesNear(p, 500.0).isEmpty()

    private fun unpack(blob: ByteArray): List<LatLon> {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val n = blob.size / 16
        val out = ArrayList<LatLon>(n)
        repeat(n) { out += LatLon(buf.double, buf.double) }
        return out
    }
}
