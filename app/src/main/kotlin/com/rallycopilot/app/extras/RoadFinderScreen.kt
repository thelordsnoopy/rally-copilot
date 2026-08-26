package com.rallycopilot.app.extras

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.rallycopilot.app.data.AppDb
import java.io.File

/**
 * Twisty-and-quiet road discovery. Twistiness is precomputed geometry (severity-weighted
 * corners per km); quietness is learned from YOUR drives — the fraction of corners where
 * you were constrained on that road. No live traffic feed, no network.
 */
data class RoadRank(
    val name: String,
    val lengthKm: Double,
    val twistiness: Double,
    val hairpins: Int,
    /** 0..1 constrained fraction from your own history; null = never driven. */
    val trafficScore: Double?,
)

fun rankRoads(context: Context, limit: Int = 30): List<RoadRank> {
    val f = File(context.filesDir, "region.sqlite")
    if (!f.exists()) return emptyList()
    val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
    val rows = ArrayList<RoadRank>()
    db.rawQuery(
        """
        SELECT COALESCE(e.ref, e.name) road,
               SUM(e.length_m) len,
               SUM(CASE WHEN c.min_r < 12 THEN 6.0 WHEN c.min_r < 25 THEN 5.0
                        WHEN c.min_r < 40 THEN 4.0 WHEN c.min_r < 70 THEN 3.0
                        WHEN c.min_r < 120 THEN 2.0 WHEN c.min_r < 200 THEN 1.0
                        ELSE 0.5 END * c.confidence) w,
               SUM(CASE WHEN c.min_r < 12 AND c.confidence > 0.5 THEN 1 ELSE 0 END) hp
        FROM edges e JOIN corners c ON c.edge_id = e.id
        WHERE road IS NOT NULL AND e.highway NOT IN ('residential','motorway','trunk')
        GROUP BY road
        HAVING len > 3000
        """, null
    ).use { c ->
        while (c.moveToNext()) {
            val len = c.getDouble(1)
            rows += RoadRank(
                name = c.getString(0),
                lengthKm = len / 1000.0,
                twistiness = c.getDouble(2) / (len / 1000.0),
                hairpins = c.getInt(3),
                trafficScore = null,
            )
        }
    }
    // Learned quietness: for roads you have driven, the constrained fraction of your
    // observations on their edges. Roads never driven stay null (unknown, not "quiet").
    val appDb = AppDb(context)
    val edgeRoad = HashMap<Long, String>()
    db.rawQuery(
        "SELECT id, COALESCE(ref, name) FROM edges WHERE COALESCE(ref, name) IS NOT NULL", null
    ).use { c -> while (c.moveToNext()) edgeRoad[c.getLong(0)] = c.getString(1) }
    db.close()

    val stats = HashMap<String, IntArray>() // road -> [constrained, total]
    appDb.readableDatabase.rawQuery(
        """
        SELECT rf.edge_id, o.constrained FROM observations o
        JOIN run_fixes rf ON rf.run_id = o.run_id AND ABS(rf.t_ms - o.t_ms) < 3000
        WHERE rf.edge_id IS NOT NULL
        """, null
    ).use { c ->
        while (c.moveToNext()) {
            val road = edgeRoad[c.getLong(0)] ?: continue
            val s = stats.getOrPut(road) { IntArray(2) }
            if (c.getInt(1) == 1) s[0]++
            s[1]++
        }
    }

    return rows
        .map { r ->
            val s = stats[r.name]
            if (s != null && s[1] >= 5) r.copy(trafficScore = s[0].toDouble() / s[1]) else r
        }
        .sortedByDescending { it.twistiness * (1.0 - 0.5 * (it.trafficScore ?: 0.3)) }
        .take(limit)
}

@Composable
fun RoadFinderScreen(context: Context) {
    val roads = remember { rankRoads(context) }
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0F14)).padding(20.dp)) {
        Text("ROAD FINDER", color = Color.White, fontSize = 24.sp)
        Text(
            "severity-weighted corners per km · quiet score fills in as you drive",
            color = Color(0xFF8899AA), fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(roads) { r ->
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(r.name, fontSize = 17.sp)
                            Text(
                                "%.1f km%s".format(
                                    r.lengthKm,
                                    if (r.hairpins > 0) " · ${r.hairpins} hairpins" else "",
                                ),
                                fontSize = 12.sp, color = Color(0xFF8899AA),
                            )
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text(
                                "%.1f".format(r.twistiness),
                                fontSize = 22.sp,
                                color = when {
                                    r.twistiness > 20 -> Color(0xFFFF5252)
                                    r.twistiness > 10 -> Color(0xFFFFC24B)
                                    else -> Color(0xFF1DB954)
                                },
                            )
                            r.trafficScore?.let {
                                Text(
                                    if (it < 0.25) "quiet" else if (it < 0.5) "some traffic" else "busy",
                                    fontSize = 11.sp, color = Color(0xFF8899AA),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
