package com.rallycopilot.app.extras

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Path
import androidx.core.content.FileProvider
import com.rallycopilot.app.data.AppDb
import com.rallycopilot.core.model.Fix
import com.rallycopilot.core.model.SeverityBand
import com.rallycopilot.core.report.Report
import java.io.File

/**
 * Post-drive report assembly, self-leaderboard, and the shareable drive card PNG.
 */
object DriveReport {

    data class Full(
        val summary: Report.DriveSummary,
        val roadNames: List<String>,
        val personalBest: Boolean,
        val fixes: List<Fix>,
    )

    fun build(context: Context, runId: Long): Full {
        val db = AppDb.get(context)
        val fixes = db.fixesFor(runId)
        val obs = db.observationsFor(runId)
        val run = db.runs().firstOrNull { it.id == runId }
        val durationMs = (run?.endedAt ?: 0L) - (run?.startedAt ?: 0L)
        val summary = Report.summarise(run?.distanceM ?: 0.0, durationMs, obs) { null }

        // Personal best: smoothest run over this distance band so far.
        val prevBest = db.kvGet("best_smooth")?.toIntOrNull() ?: 0
        val pb = summary.smoothness > prevBest && obs.size >= 10
        if (pb) db.kvPut("best_smooth", summary.smoothness.toString())

        return Full(summary, emptyList(), pb, fixes)
    }

    /** 1200x630 share card: route trace, stats, biggest corner count. */
    fun renderCard(context: Context, runId: Long): File {
        val full = build(context, runId)
        val w = 1200; val h = 630
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(AColor.rgb(11, 15, 20))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Route trace from fixes, scaled to fit the left 60%.
        if (full.fixes.size >= 2) {
            val lats = full.fixes.map { it.lat }
            val lons = full.fixes.map { it.lon }
            val minLat = lats.min(); val maxLat = lats.max()
            val minLon = lons.min(); val maxLon = lons.max()
            val spanLat = (maxLat - minLat).coerceAtLeast(1e-6)
            val spanLon = (maxLon - minLon).coerceAtLeast(1e-6)
            val pad = 60f
            val availW = w * 0.58f - 2 * pad
            val availH = h - 2 * pad
            val path = Path()
            full.fixes.forEachIndexed { i, f ->
                val x = pad + ((f.lon - minLon) / spanLon * availW).toFloat()
                val y = pad + ((maxLat - f.lat) / spanLat * availH).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f
            paint.color = AColor.rgb(29, 185, 84)
            c.drawPath(path, paint)
        }

        // Stats column.
        paint.style = Paint.Style.FILL
        paint.color = AColor.WHITE
        paint.textSize = 54f
        val x = w * 0.62f
        c.drawText("RALLY COPILOT", x, 90f, paint)
        paint.textSize = 40f
        paint.color = AColor.rgb(184, 196, 208)
        val miles = full.summary.distanceM / 1609.34
        val mins = full.summary.durationMs / 60000
        c.drawText("%.1f miles · %d min".format(miles, mins), x, 170f, paint)
        val cornerTotal = full.summary.cornersByBand.values.sum()
        c.drawText("$cornerTotal corners", x, 230f, paint)
        val tight = full.summary.cornersByBand.filterKeys {
            it == SeverityBand.HAIRPIN || it == SeverityBand.ONE || it == SeverityBand.TWO
        }.values.sum()
        paint.color = AColor.rgb(255, 194, 75)
        c.drawText("$tight tight ones", x, 290f, paint)
        paint.color = AColor.rgb(29, 185, 84)
        paint.textSize = 72f
        c.drawText("${full.summary.smoothness}", x, 400f, paint)
        paint.textSize = 32f
        paint.color = AColor.rgb(136, 153, 170)
        c.drawText("smoothness", x, 445f, paint)
        if (full.personalBest) {
            paint.color = AColor.rgb(255, 82, 82)
            paint.textSize = 40f
            c.drawText("★ PERSONAL BEST", x, 520f, paint)
        }

        val dir = File(context.cacheDir, "cards").apply { mkdirs() }
        val f = File(dir, "drive_$runId.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    fun share(context: Context, runId: Long) {
        val f = renderCard(context, runId)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share drive"))
    }
}
