package com.rallycopilot.app.data

import com.rallycopilot.core.knowledge.AuditStore
import com.rallycopilot.core.knowledge.CornerAudit

/**
 * SQLite-backed radius-audit store. Corner ids are assigned sequentially at map
 * build time and are NOT stable across map rebuilds, so the whole table is wiped
 * whenever the shipped map data actually changes — [mapFingerprint] is the copied
 * region file's length, which only moves when the asset does.
 */
class AuditDb(private val db: AppDb, mapFingerprint: Long) : AuditStore {

    init {
        db.writableDatabase.execSQL(
            """CREATE TABLE IF NOT EXISTS corner_audit(corner_id INTEGER PRIMARY KEY,
               passes INTEGER DEFAULT 0, ratio_ema REAL DEFAULT 1.0)"""
        )
        val fp = mapFingerprint.toString()
        if (db.kvGet("audit_map_fp") != fp) {
            db.writableDatabase.execSQL("DELETE FROM corner_audit")
            db.kvPut("audit_map_fp", fp)
        }
    }

    override fun get(cornerId: Long): CornerAudit? =
        db.readableDatabase.rawQuery(
            "SELECT passes, ratio_ema FROM corner_audit WHERE corner_id=?",
            arrayOf(cornerId.toString())
        ).use { c ->
            if (!c.moveToFirst()) null
            else CornerAudit(cornerId, c.getInt(0), c.getDouble(1))
        }

    override fun put(a: CornerAudit) {
        db.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO corner_audit VALUES (?,?,?)",
            arrayOf(a.cornerId, a.passes, a.ratioEma)
        )
    }

    /** How many corners the audit currently disagrees with — for the profile screen. */
    fun disagreementCount(): Int =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM corner_audit WHERE ratio_ema < 0.85 OR ratio_ema > 1.35", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}
