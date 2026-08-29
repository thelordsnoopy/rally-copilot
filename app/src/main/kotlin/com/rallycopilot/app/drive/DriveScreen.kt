package com.rallycopilot.app.drive

import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.widthIn
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
import com.rallycopilot.app.obd.ObdClient
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

// Per-frame easing factors for the map. Low enough to kill GPS twitch, high enough
// that the map still tracks the car rather than trailing behind it.
private const val POSITION_EASE = 0.14f
private const val BEARING_EASE = 0.09f

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
 * The live HUD. Portrait-locked. Real road geometry behind, notation panel on top,
 * instruments along the bottom. Silence is always explained by a status chip.
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

    // Auto-stop prompt: the service saw the car stop and the dongle go quiet.
    // Ticks the remaining seconds; -1 means no prompt is active.
    val autoStopLeftS by produceState(-1L) {
        while (true) {
            val dl = activity.driveService?.autoStopDeadlineMs ?: 0L
            value = if (dl == 0L) -1L
            else ((dl - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            delay(250)
        }
    }
    if (autoStopLeftS >= 0 && !showFeedback) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { activity.driveService?.autoStopStillDriving() },
            title = { Text("Stopped driving?") },
            text = {
                Text(
                    "The car has been still for a while and the OBD link has gone quiet. " +
                        "The drive stops itself in ${autoStopLeftS}s."
                )
            },
            confirmButton = {
                Button(onClick = { showFeedback = true }) { Text("STOP THE DRIVE") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { activity.driveService?.autoStopStillDriving() }
                ) { Text("STILL DRIVING") }
            },
        )
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
            // ---- OBD connection overlay ----
            // Connecting to a dongle takes seconds and can fail for half a dozen
            // mundane reasons. Show the attempt while it happens and get out of the
            // way once it is live; if it can't connect, say why rather than leaving
            // a dark light to be interpreted.
            val obd by androidx.compose.runtime.produceState<Pair<ObdClient.State, String>?>(null) {
                while (true) {
                    value = activity.driveService?.let { it.obdState() to it.obdStatusText() }
                    delay(600)
                }
            }
            obd?.let { (state, text) ->
                if (state != ObdClient.State.LIVE && state != ObdClient.State.OFF) {
                    val working = state == ObdClient.State.CONNECTING ||
                        state == ObdClient.State.HANDSHAKING
                    Row(
                        Modifier.align(Alignment.TopCenter).padding(top = 52.dp)
                            .background(Color(0xE60D1218), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Canvas(Modifier.size(8.dp)) {
                            drawCircle(if (working) Amber else Red)
                        }
                        Text(
                            "  OBD  ", color = InkDim, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                        )
                        Text(
                            text, color = if (working) Ink else Color(0xFFFFB4B4),
                            fontSize = 12.sp, maxLines = 2,
                            modifier = Modifier.widthIn(max = 250.dp),
                        )
                    }
                }
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

            // "Was there a hazard?" — auto-answers NO at the deadline; one big YES.
            hud?.hazardPrompt?.let { prompt ->
                val secondsLeft = ((prompt.deadlineMs - System.currentTimeMillis()) / 1000)
                    .coerceAtLeast(0)
                Button(
                    // Marshalled onto the engine thread by the service — the engine
                    // has no locks of its own.
                    onClick = {
                        runCatching { activity.driveService?.answerHazard(true) }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp).fillMaxWidth(0.85f).height(72.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "HAZARD BACK THERE?  TAP = YES",
                            fontSize = 18.sp, color = Bg, fontWeight = FontWeight.Black,
                        )
                        Text(
                            "auto-dismisses as no in ${secondsLeft}s",
                            fontSize = 12.sp, color = Color(0xCC06080B),
                        )
                    }
                }
            }
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
        // Speed, BIG, colour-coded against the current corner's target:
        // green = on the money, amber = close, red = far off.
        val speed = hud?.speedMps ?: 0.0
        val target = hud?.currentNote?.vTargetMps
        val delta = if (target != null && target > 0) speed - target else null
        val speedColour = when {
            delta == null -> Ink
            kotlin.math.abs(delta) <= maxOf(1.34, target!! * 0.08) -> Green
            kotlin.math.abs(delta) <= maxOf(3.6, target * 0.15) -> Amber
            else -> Red
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${(speed * 2.23694).toInt()}",
                color = speedColour, fontSize = 58.sp, fontWeight = FontWeight.Black, lineHeight = 58.sp,
            )
            Column(Modifier.padding(start = 4.dp, bottom = 4.dp)) {
                if (delta != null && kotlin.math.abs(delta) > maxOf(1.34, target!! * 0.08)) {
                    Text(
                        if (delta > 0) "▼ slow" else "▲ ok to push",
                        color = speedColour, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Text("mph", color = InkDim, fontSize = 13.sp)
            }
        }
        // status chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The speed source is never left implicit: whichever one the number on
            // the left came from is the one lit green. The OBD light tracks DATA,
            // not the Bluetooth link — a connected dongle with a silent car is
            // amber at best, never green.
            val fromObd = hud?.speedFromObd == true
            Chip("GPS", if (hud?.gpsOk != true) Red else if (fromObd) InkDim else Green)
            Chip("OBD", if (fromObd) Green else if (hud?.obdConnected == true) Amber else InkDim)
            if (hud?.obdSilent == true) Chip("NO CAR DATA", Amber)
            // Quiet mode is a deliberate silence, so say so — the driver must never
            // wonder whether the co-driver has died. Keep labels SHORT: this row
            // shares one line with a 58sp speed readout.
            if (hud?.pressingOn == false) Chip("QUIET", InkDim)
            else if (hud?.spirited == true) Chip("SPIRITED", Amber)
            else Chip("CALLING", Green)
            // The phone moving in its holder, not the car moving: alignment, camber
            // and the radius audit are all blind until the mount is wedged tight.
            if ((DriveService.instance?.mountWobbleDeg ?: 0.0) >= 8.0) Chip("LOOSE MOUNT", Amber)
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
        // Never wrap: this row is width-constrained, and a chip label that wraps
        // grows the instrument bar vertically and crushes the map above it.
        Modifier.background(Color(0x33202B36), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(7.dp)) { drawCircle(colour) }
        Text(
            "  $label", color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, softWrap = false,
        )
    }
}

/** How far around the car the map view loads road geometry. */
private const val MAP_RADIUS_M = 900.0

/** Re-load that geometry every this many metres travelled, so a long edge cannot
 *  outrun the data. Must stay comfortably below [MAP_RADIUS_M]. */
private const val MAP_REFETCH_M = 250.0

/**
 * Real road geometry, heading-up, following the car. All nearby roads dim; the
 * predicted path bright with severity-coloured corner sections. No tiles — this is
 * our own region data drawn directly.
 */
@Composable
private fun RoadMap(activity: MainActivity, hud: DriveEngine.HudState?, modifier: Modifier) {
    val store: SqliteMapStore? = activity.driveService?.map
    var nearby by remember { mutableStateOf<List<com.rallycopilot.core.model.Edge>>(emptyList()) }
    var centre by remember { mutableStateOf<LatLon?>(null) }
    // The matcher legitimately drops individual fixes (GPS jitter, momentary
    // ambiguity). Blanking the whole map for every dropped fix reads as the screen
    // "randomly going black" — keep drawing the last good position instead; the
    // status chip already explains the gap.
    var lastGood by remember { mutableStateOf<com.rallycopilot.core.model.MatchedPosition?>(null) }
    hud?.matched?.let { lastGood = it }

    val matched = hud?.matched ?: lastGood
    // The cell query loads hundreds of edges with geometry unpacking — that is IO
    // work, not something to run (or even start) inside composition.
    //
    // Re-fetch on DISTANCE TRAVELLED, not only on a change of edge. Keying this on
    // edgeId alone is why the map emptied out at the top of Whiteshill: edges are
    // split at junctions, so a country lane between two junctions runs well over a
    // kilometre (1,375 m right there), and the roads were fetched once, around
    // wherever the car happened to JOIN that edge. Drive more than the query radius
    // along it and everything ahead was simply never loaded — the map ran out
    // before the road did.
    val fetchKey = matched?.let { it.edgeId to (it.offsetM / MAP_REFETCH_M).toInt() }
    androidx.compose.runtime.LaunchedEffect(store, fetchKey) {
        val s = store ?: return@LaunchedEffect
        val m = matched ?: return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val e = s.edge(m.edgeId)
            if (e != null) {
                val cum = Polyline.cumulative(e.geometry)
                val c = Polyline.pointAt(e.geometry, cum, m.offsetM)
                val t0 = System.currentTimeMillis()
                val near = s.edgesNear(c, MAP_RADIUS_M)
                // Black box: exactly what the map view had to draw, and where from.
                // "The map cut off" is unanswerable without this.
                activity.driveService?.logMapFetch(
                    c.lat, c.lon, m.edgeId, m.offsetM, near.size,
                    System.currentTimeMillis() - t0,
                )
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    centre = c; nearby = near
                }
            }
        }
    }
    // Track the car along the already-cached current edge (cache hit, no IO).
    if (store != null && matched != null && centre != null) {
        val e = store.edge(matched.edgeId)
        if (e != null) {
            val cum = Polyline.cumulative(e.geometry)
            centre = Polyline.pointAt(e.geometry, cum, matched.offsetM)
        }
    }

    // ---- smoothing ----
    // GPS lands at 5-10 Hz and the matched offset steps with it, so drawing the raw
    // value makes the whole world twitch. Ease position and heading toward their
    // targets once per displayed frame instead: the map glides, and a single noisy
    // fix nudges it rather than snapping it. The easing runs in a frame callback,
    // never in composition — composition must stay free of side effects.
    val smoothed = remember { mutableStateOf<LatLon?>(null) }
    val smoothBearing = remember { mutableStateOf(Float.NaN) }
    val targetCentre = androidx.compose.runtime.rememberUpdatedState(centre)
    val targetBearing = androidx.compose.runtime.rememberUpdatedState(
        matched?.bearingDeg?.toFloat()?.takeIf { !it.isNaN() }
    )
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameMillis {
                targetCentre.value?.let { t ->
                    val cur = smoothed.value
                    smoothed.value = if (cur == null) t else LatLon(
                        cur.lat + (t.lat - cur.lat) * POSITION_EASE,
                        cur.lon + (t.lon - cur.lon) * POSITION_EASE,
                    )
                }
                targetBearing.value?.let { t ->
                    val cur = smoothBearing.value
                    smoothBearing.value = if (cur.isNaN()) t else {
                        // Shortest way round the circle, so 350° -> 10° eases through
                        // north instead of spinning the map the long way.
                        var delta = (t - cur) % 360f
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        (cur + delta * BEARING_EASE + 360f) % 360f
                    }
                }
            }
        }
    }

    Canvas(
        modifier.graphicsLayer {
            // Google-Maps-style perspective tilt, pivoting low so the far road recedes.
            rotationX = 35f
            cameraDistance = 9f * density
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.75f)
            // A 3D rotation projects content outside the layout bounds, which paints
            // roads over the instrument bar below. Keep it inside its own box.
            clip = true
        }
    ) {
        // Draw from the eased values, not the raw fix — see the smoothing above.
        val c = smoothed.value ?: centre ?: return@Canvas
        val heading = smoothBearing.value.takeIf { !it.isNaN() }
            ?: matched?.bearingDeg?.toFloat()?.takeIf { !it.isNaN() } ?: 0f
        val pathEdges = hud?.horizon?.pathEdgeIds?.toHashSet() ?: hashSetOf()

        val scale = (size.height / 520f) // px per metre-ish: ~520 m visible vertically
        val cx = size.width * 0.5f
        val cy = size.height * 0.68f

        fun toScreen(p: LatLon): Offset {
            val xy = Geo.toXY(p, c)
            return Offset(cx + (xy.x * scale).toFloat(), cy - (xy.y * scale).toFloat())
        }

        rotate(degrees = -heading, pivot = Offset(cx, cy)) {
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

/**
 * Post-drive sheet. The style detector already classified the drive; this shows its
 * verdict, lets the user correct it, optionally asks how the pace felt, and always
 * offers a no-questions exit. Every path through here ends the drive cleanly.
 */
@Composable
fun FeedbackSheet(activity: MainActivity, onDone: () -> Unit) {
    val db = remember { AppDb.get(activity) }
    // Capture everything we need BEFORE stopping the drive.
    val svc = activity.driveService
    val runId = remember { svc?.runId ?: -1 }
    val cond = remember { svc?.conditions ?: com.rallycopilot.core.model.Conditions.DRY }
    val spiritedFraction = remember {
        runCatching { svc?.engine?.hud?.value?.spiritedFraction }.getOrNull() ?: 0.0
    }
    val detectedSpirited = spiritedFraction > 0.20
    var stopped by remember { mutableStateOf(false) }

    fun endDrive() {
        if (!stopped) { stopped = true; activity.stopDrive() }
    }

    /** Full-history learning pass — real work, never on the main thread. */
    fun relearnAsync(alsoOverride: Boolean? = null) {
        Thread {
            runCatching {
                if (alsoOverride != null && runId >= 0) {
                    // stopDrive() persists this run's observations asynchronously;
                    // wait for them to land before rewriting their style flag.
                    val deadline = System.currentTimeMillis() + 10_000
                    while (db.observationsFor(runId).isEmpty() &&
                        System.currentTimeMillis() < deadline
                    ) Thread.sleep(200)
                    db.overrideRunStyle(runId, alsoOverride)
                }
                val history = db.observationsForLearning(cond)
                db.saveProfile(Learning.applySession(db.loadProfile(cond), history), cond)
            }
        }.start()
    }

    Column(
        Modifier.fillMaxSize().background(Bg)
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        Text("Drive finished", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        // ---- what the model detected ----
        Column(
            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (detectedSpirited) "Detected: SPIRITED driving"
                else "Detected: normal driving",
                color = if (detectedSpirited) Amber else Ink,
                fontSize = 17.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                "${(spiritedFraction * 100).toInt()}% of the drive read as pressing on · " +
                    if (detectedSpirited) "spirited corners will train your profile"
                    else "calibration untouched — normal pace never lowers your model",
                color = InkDim, fontSize = 12.sp, lineHeight = 15.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        endDrive()
                        relearnAsync(alsoOverride = false)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232D38)),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("it was normal", fontSize = 12.sp, color = InkDim) }
                Button(
                    onClick = {
                        endDrive()
                        relearnAsync(alsoOverride = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232D38)),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("it was spirited", fontSize = 12.sp, color = Amber) }
            }
        }

        // ---- how did the pace advice feel? ----
        Text(
            if (detectedSpirited) "How did the suggested speeds feel?"
            else "How did it feel? (only affects pace if you were pushing)",
            color = InkDim, fontSize = 13.sp,
        )
        for ((answer, label, sub, colour) in listOf(
            Quad(FeedbackAnswer.EASY, "EASY", "I had margin — push more", Color(0xFF2266AA)),
            Quad(FeedbackAnswer.GOOD, "GOOD", "about right", Green),
            Quad(FeedbackAnswer.HARD, "HARD", "it pushed me — back off", Color(0xFFCC6633)),
        )) {
            Button(
                onClick = {
                    endDrive()
                    if (runId >= 0) db.setFeedback(runId, answer.name)
                    // Push-factor feedback only means something if you were actually pushing.
                    if (detectedSpirited) {
                        db.saveProfile(Learning.applyFeedback(db.loadProfile(cond), answer), cond)
                    }
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().height(62.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colour),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 19.sp, color = Color.Black, fontWeight = FontWeight.Black)
                    Text(sub, fontSize = 11.sp, color = Color(0xCC06080B))
                }
            }
        }

        // ---- no questions, just leave ----
        Button(
            onClick = { endDrive(); onDone() },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF11161D)),
        ) { Text("skip — just exit", fontSize = 14.sp, color = InkDim) }
        Spacer(Modifier.height(8.dp))
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
