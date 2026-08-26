package com.rallycopilot.app.drive

import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rallycopilot.app.MainActivity
import com.rallycopilot.app.data.AppDb
import com.rallycopilot.app.data.SqliteMapStore
import com.rallycopilot.core.engine.DriveEngine
import com.rallycopilot.core.geo.Geo
import com.rallycopilot.core.geo.Polyline
import com.rallycopilot.core.model.Direction
import com.rallycopilot.core.model.FeedbackAnswer
import com.rallycopilot.core.model.HorizonCorner
import com.rallycopilot.core.model.LatLon
import com.rallycopilot.core.model.SeverityBand
import com.rallycopilot.core.profile.Learning
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ---- palette ----
private val Bg = Color(0xFF06080B)
private val Panel = Color(0xCC0D1218)
private val RoadDim = Color(0xFF232D38)
private val RoadPath = Color(0xFF54687C)
private val Ink = Color(0xFFEAF0F6)
private val InkDim = Color(0xFF7C8B9A)
private val Amber = Color(0xFFFFB74D)
private val Green = Color(0xFF2EE06B)
private val Red = Color(0xFFFF4B4B)

private fun severityColour(band: SeverityBand): Color = when (band) {
    SeverityBand.HAIRPIN, SeverityBand.ONE -> Red
    SeverityBand.TWO, SeverityBand.THREE -> Amber
    else -> Green
}

private fun severityDigit(band: SeverityBand): String = when (band) {
    SeverityBand.HAIRPIN -> "H"
    SeverityBand.ONE -> "1"; SeverityBand.TWO -> "2"; SeverityBand.THREE -> "3"
    SeverityBand.FOUR -> "4"; SeverityBand.FIVE -> "5"; SeverityBand.SIX -> "6"
    SeverityBand.FLAT -> ""
}

/**
 * The live HUD. Landscape-locked. Real road geometry behind, notation panel on the
 * left, instruments along the bottom. Silence is always explained by a status chip.
 */
@Composable
fun DriveScreen(activity: MainActivity, onExit: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            view.keepScreenOn = false
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var showFeedback by remember { mutableStateOf(false) }

    val hud by produceState<DriveEngine.HudState?>(initialValue = null) {
        while (true) {
            value = activity.driveService?.let { runCatching { it.engine.hud.value }.getOrNull() }
            delay(66)
        }
    }

    if (showFeedback) {
        FeedbackSheet(activity) { onExit() }
        return
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // ---- note bar, top ----
        NotePanel(hud)

        // ---- map, filling the middle ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            RoadMap(activity, hud, Modifier.fillMaxSize())

            val status = when {
                hud == null -> "STARTING"
                hud?.gpsOk != true -> "GPS LOST"
                hud?.horizon == null -> "POSITION AMBIGUOUS"
                else -> null
            }
            if (status != null) {
                Text(
                    status,
                    color = Bg, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        .background(Amber, RoundedCornerShape(6.dp))
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                )
            }
            if (hud?.incidentSuspected == true) {
                Text(
                    "ARE YOU OK?  tap END if not",
                    color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                        .background(Red, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Button(
                onClick = { showFeedback = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(78.dp, 38.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF321A20)),
            ) { Text("END", fontSize = 13.sp, color = Color(0xFFFF8A8A), fontWeight = FontWeight.Bold) }
        }

        // ---- instrument bar, bottom ----
        InstrumentBar(hud, Modifier)
    }
}

/** Current note as a full-width top bar: arrow + digit left, details centre, next right. */
@Composable
private fun NotePanel(hud: DriveEngine.HudState?) {
    val cur = hud?.currentNote
    val next = hud?.nextNote
    val progress = hud?.progressM ?: 0.0

    Row(
        Modifier.fillMaxWidth().background(Panel)
            .padding(start = 18.dp, end = 14.dp, top = 40.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cur == null) {
            Text("—", color = InkDim, fontSize = 56.sp, lineHeight = 56.sp)
            Spacer(Modifier.width(14.dp))
            Text("no corner ahead", color = InkDim, fontSize = 14.sp)
        } else {
            val colour = severityColour(cur.band)
            CornerArrow(cur.band, cur.corner.direction, colour, Modifier.size(62.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                severityDigit(cur.band),
                color = colour, fontSize = 74.sp, fontWeight = FontWeight.Black, lineHeight = 74.sp,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                val mods = cur.modifiers.joinToString("  ") { it.spoken.uppercase() }
                if (mods.isNotEmpty()) {
                    Text(mods, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
                }
                val d = (cur.distanceAheadM - progress).coerceAtLeast(0.0)
                val mph = (cur.vTargetMps * 2.23694).toInt()
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (d < 15) "NOW" else "${(d / 10).toInt() * 10}",
                        color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp,
                    )
                    if (d >= 15) Text(" m", color = InkDim, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$mph", color = Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
                    Text(" mph", color = InkDim, fontSize = 12.sp)
                    hud.gear?.let {
                        Text("  $it", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
                        Text("g", color = InkDim, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (next != null) {
            Column(
                Modifier.background(Color(0x66202B36), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("then", color = InkDim, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CornerArrow(next.band, next.corner.direction, severityColour(next.band), Modifier.size(24.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        severityDigit(next.band),
                        color = severityColour(next.band), fontSize = 26.sp,
                        fontWeight = FontWeight.Bold, lineHeight = 26.sp,
                    )
                }
            }
        }
    }
}

/** A curved arrow whose bend reflects severity — the visual language of a roadbook. */
@Composable
private fun CornerArrow(band: SeverityBand, dir: Direction, colour: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val bend = when (band) {
            SeverityBand.HAIRPIN -> 1.0f
            SeverityBand.ONE -> 0.85f; SeverityBand.TWO -> 0.7f; SeverityBand.THREE -> 0.55f
            SeverityBand.FOUR -> 0.4f; SeverityBand.FIVE -> 0.28f; SeverityBand.SIX -> 0.16f
            SeverityBand.FLAT -> 0.05f
        }
        val sgn = if (dir == Direction.LEFT) -1f else 1f
        val path = Path()
        path.moveTo(w / 2, h)
        // quadratic curve up and to the side; harder corners bend more
        val endX = w / 2 + sgn * bend * w * 0.48f
        val endY = h * (0.42f - 0.28f * bend)
        path.quadraticBezierTo(w / 2, h * 0.30f, endX, endY)
        drawPath(path, colour, style = Stroke(width = w * 0.14f, cap = StrokeCap.Round))
        // arrowhead
        val angle = if (band == SeverityBand.HAIRPIN) 90f * sgn + 20f * sgn else 30f * sgn + 25f * sgn * bend
        val rad = Math.toRadians(angle.toDouble() - 90.0)
        val ax = cos(rad).toFloat(); val ay = sin(rad).toFloat()
        val headLen = w * 0.22f
        val perp = Offset(-ay, ax)
        val tip = Offset(endX, endY)
        val back = tip - Offset(ax, ay) * (headLen * 0.1f)
        val p2 = Path()
        p2.moveTo(tip.x + ax * headLen * 0.6f, tip.y + ay * headLen * 0.6f)
        p2.lineTo(back.x + perp.x * headLen * 0.55f, back.y + perp.y * headLen * 0.55f)
        p2.lineTo(back.x - perp.x * headLen * 0.55f, back.y - perp.y * headLen * 0.55f)
        p2.close()
        drawPath(p2, colour)
    }
}

@Composable
private fun InstrumentBar(hud: DriveEngine.HudState?, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
            .background(Panel, RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // speed
        Row(verticalAlignment = Alignment.Bottom) {
            val mph = ((hud?.speedMps ?: 0.0) * 2.23694).toInt()
            Text("$mph", color = Ink, fontSize = 44.sp, fontWeight = FontWeight.Black, lineHeight = 44.sp)
            Text(" mph", color = InkDim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        // status chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("GPS", if (hud?.gpsOk == true) Green else Red)
            Chip("OBD", if (hud?.obdConnected == true) Green else InkDim)
            Chip("MAP", if (hud?.horizon != null) Green else Amber)
        }
        // path confidence
        val conf = hud?.currentNote?.pathConfidence ?: hud?.matched?.confidence ?: 0.0
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(52.dp, 8.dp)) {
                drawRoundRect(RoadDim, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f))
                drawRoundRect(
                    if (conf > 0.6) Green else Amber,
                    size = androidx.compose.ui.geometry.Size(size.width * conf.toFloat(), size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f),
                )
            }
            Text("conf", color = InkDim, fontSize = 10.sp)
        }
    }
}

@Composable
private fun Chip(label: String, colour: Color) {
    Row(
        Modifier.background(Color(0x33202B36), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(7.dp)) { drawCircle(colour) }
        Text("  $label", color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Real road geometry, heading-up, following the car. All nearby roads dim; the
 * predicted path bright with severity-coloured corner sections. No tiles — this is
 * our own region data drawn directly.
 */
@Composable
private fun RoadMap(activity: MainActivity, hud: DriveEngine.HudState?, modifier: Modifier) {
    val store: SqliteMapStore? = activity.driveService?.map
    // Cache nearby edges; refresh when the matched edge changes.
    var cachedFor by remember { mutableStateOf(-1L) }
    var nearby by remember { mutableStateOf<List<com.rallycopilot.core.model.Edge>>(emptyList()) }
    var centre by remember { mutableStateOf<LatLon?>(null) }

    val matched = hud?.matched
    if (store != null && matched != null && matched.edgeId != cachedFor) {
        val e = store.edge(matched.edgeId)
        if (e != null) {
            val cum = Polyline.cumulative(e.geometry)
            val c = Polyline.pointAt(e.geometry, cum, matched.offsetM)
            centre = c
            nearby = store.edgesNear(c, 700.0)
            cachedFor = matched.edgeId
        }
    } else if (store != null && matched != null && centre != null) {
        // update centre along the current edge without re-querying
        val e = store.edge(matched.edgeId)
        if (e != null) {
            val cum = Polyline.cumulative(e.geometry)
            centre = Polyline.pointAt(e.geometry, cum, matched.offsetM)
        }
    }

    Canvas(
        modifier.graphicsLayer {
            // Google-Maps-style perspective tilt, pivoting low so the far road recedes.
            rotationX = 35f
            cameraDistance = 9f * density
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.75f)
        }
    ) {
        val c = centre ?: return@Canvas
        val m = matched ?: return@Canvas
        val pathEdges = hud.horizon?.pathEdgeIds?.toHashSet() ?: hashSetOf()

        val scale = (size.height / 520f) // px per metre-ish: ~520 m visible vertically
        val cx = size.width * 0.5f
        val cy = size.height * 0.68f

        fun toScreen(p: LatLon): Offset {
            val xy = Geo.toXY(p, c)
            return Offset(cx + (xy.x * scale).toFloat(), cy - (xy.y * scale).toFloat())
        }

        rotate(degrees = -m.bearingDeg.toFloat(), pivot = Offset(cx, cy)) {
            // all nearby roads, dim
            for (e in nearby) {
                if (e.id in pathEdges) continue
                val pts = e.geometry
                if (pts.size < 2) continue
                val p = Path()
                var started = false
                for (pt in pts) {
                    val s = toScreen(pt)
                    if (!started) { p.moveTo(s.x, s.y); started = true } else p.lineTo(s.x, s.y)
                }
                drawPath(p, RoadDim, style = Stroke(width = 7f, cap = StrokeCap.Round))
            }
            // predicted path, bright, with severity-coloured corner sections
            for (e in nearby) {
                if (e.id !in pathEdges) continue
                val pts = e.geometry
                if (pts.size < 2) continue
                val p = Path()
                var started = false
                for (pt in pts) {
                    val s = toScreen(pt)
                    if (!started) { p.moveTo(s.x, s.y); started = true } else p.lineTo(s.x, s.y)
                }
                drawPath(p, RoadPath, style = Stroke(width = 12f, cap = StrokeCap.Round))
                // severity-coloured corner overlays
                val cum = Polyline.cumulative(pts)
                for (corner in (activity.driveService?.map?.cornersOn(e.id) ?: emptyList())) {
                    if (corner.confidence < 0.3) continue
                    val band = com.rallycopilot.core.model.SeverityTable.DEFAULT.bandFor(corner.minRadiusM)
                    if (band == SeverityBand.FLAT || band == SeverityBand.SIX) continue
                    val cp = Path()
                    var s2 = false
                    var d = corner.startOffsetM
                    while (d <= corner.endOffsetM) {
                        val s = toScreen(Polyline.pointAt(pts, cum, d))
                        if (!s2) { cp.moveTo(s.x, s.y); s2 = true } else cp.lineTo(s.x, s.y)
                        d += 8.0
                    }
                    drawPath(cp, severityColour(band), style = Stroke(width = 13f, cap = StrokeCap.Round))
                }
            }
        }

        // car chevron, always centre, pointing up (heading-up frame)
        val ch = Path()
        ch.moveTo(cx, cy - 26f)
        ch.lineTo(cx - 19f, cy + 19f)
        ch.lineTo(cx, cy + 8f)
        ch.lineTo(cx + 19f, cy + 19f)
        ch.close()
        drawPath(ch, Color(0xFF06080B), style = Stroke(width = 10f))
        drawPath(ch, Green)
    }
}

/** How hard did that feel? Hard = it was pushing me -> back off. */
@Composable
fun FeedbackSheet(activity: MainActivity, onDone: () -> Unit) {
    val db = remember { AppDb(activity) }
    Column(
        Modifier.fillMaxSize().background(Bg).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text("How hard was that?", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Hard = it was pushing you → it backs off", color = InkDim, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        for ((answer, label, sub, colour) in listOf(
            Quad(FeedbackAnswer.EASY, "EASY", "I had margin — push more", Color(0xFF2266AA)),
            Quad(FeedbackAnswer.GOOD, "GOOD", "about right", Green),
            Quad(FeedbackAnswer.HARD, "HARD", "it pushed me — back off", Color(0xFFCC6633)),
        )) {
            Button(
                onClick = {
                    val svc = activity.driveService
                    val runId = svc?.runId ?: -1
                    val cond = svc?.conditions ?: com.rallycopilot.core.model.Conditions.DRY
                    activity.stopDrive()
                    if (runId >= 0) db.setFeedback(runId, answer.name)
                    // Feedback lands on the profile for the conditions just driven.
                    db.saveProfile(Learning.applyFeedback(db.loadProfile(cond), answer), cond)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().height(74.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colour),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 21.sp, color = Color.Black, fontWeight = FontWeight.Black)
                    Text(sub, fontSize = 12.sp, color = Color(0xCC06080B))
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
