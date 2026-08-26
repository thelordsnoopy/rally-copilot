package com.rallycopilot.app.extras

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import android.view.Surface
import com.rallycopilot.app.data.AppDb
import com.rallycopilot.core.model.Fix
import java.io.File

/**
 * GoPro-style telemetry overlay: renders the run log (speed, lateral g, heading trace)
 * to an MP4 sized for laying over dashcam footage in any editor. Dark background —
 * use a "screen"/"lighten" blend, or chroma-key the near-black.
 *
 * H.264 via MediaCodec surface input + MediaMuxer. 1280x720 @ 30 fps.
 */
object OverlayExporter {

    fun export(context: Context, runId: Long, onProgress: (Int) -> Unit = {}): Uri? {
        val db = AppDb(context)
        val fixes = db.fixesFor(runId)
        if (fixes.size < 10) return null

        val w = 1280; val h = 720; val fps = 30
        val t0 = fixes.first().tMs
        val t1 = fixes.last().tMs
        val durationMs = t1 - t0
        val frames = ((durationMs / 1000.0) * fps).toInt().coerceAtMost(fps * 60 * 30)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface: Surface = codec.createInputSurface()
        codec.start()

        val outFile = File(context.cacheDir, "overlay_$runId.mp4")
        val muxer = MediaMuxer(outFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun drain(endOfStream: Boolean) {
            if (endOfStream) codec.signalEndOfInputStream()
            while (true) {
                val idx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start(); muxerStarted = true
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (bufferInfo.size > 0 && muxerStarted &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            muxer.writeSampleData(track, buf, bufferInfo)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER && endOfStream) continue
            }
        }

        var fi = 0
        for (frame in 0 until frames) {
            val tMs = t0 + (frame * 1000L / fps)
            while (fi < fixes.size - 2 && fixes[fi + 1].tMs < tMs) fi++
            val f = fixes[fi]

            val canvas = surface.lockCanvas(null)
            try {
                canvas.drawColor(AColor.rgb(2, 3, 4))
                drawTelemetry(canvas, paint, f, fixes, fi, w, h)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            drain(endOfStream = false)
            if (frame % (fps * 5) == 0) onProgress(frame * 100 / frames)
        }
        drain(endOfStream = true)
        runCatching { codec.stop(); codec.release() }
        runCatching { if (muxerStarted) muxer.stop(); muxer.release() }
        surface.release()

        // Publish to Movies via MediaStore.
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "rallycopilot_overlay_$runId.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RallyCopilot")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out ->
            outFile.inputStream().use { it.copyTo(out) }
        }
        outFile.delete()
        onProgress(100)
        return uri
    }

    private fun drawTelemetry(
        c: Canvas, paint: Paint, f: Fix, fixes: List<Fix>, fi: Int, w: Int, h: Int,
    ) {
        // Speed, bottom left, huge.
        paint.color = AColor.WHITE
        paint.textSize = 120f
        val mph = (f.speedMps * 2.23694).toInt()
        c.drawText("$mph", 60f, h - 80f, paint)
        paint.textSize = 40f
        paint.color = AColor.rgb(136, 153, 170)
        c.drawText("mph", 60f, h - 30f, paint)

        // Lateral g estimate from bearing rate, bottom centre as a dot on a bar.
        if (fi > 2) {
            val prev = fixes[fi - 2]
            val dt = (f.tMs - prev.tMs) / 1000.0
            if (dt > 0.05) {
                var dBearing = f.bearingDeg - prev.bearingDeg
                if (dBearing > 180) dBearing -= 360.0
                if (dBearing < -180) dBearing += 360.0
                val yawRate = Math.toRadians(dBearing) / dt
                val aLat = (f.speedMps * yawRate / 9.81).coerceIn(-1.2, 1.2)
                val cx = w / 2f
                val barW = 400f
                paint.color = AColor.rgb(42, 53, 64)
                c.drawRoundRect(cx - barW / 2, h - 70f, cx + barW / 2, h - 50f, 10f, 10f, paint)
                paint.color = if (kotlin.math.abs(aLat) > 0.6) AColor.rgb(255, 82, 82)
                else AColor.rgb(29, 185, 84)
                c.drawCircle(cx + (aLat / 1.2f * (barW / 2)).toFloat(), h - 60f, 18f, paint)
                paint.textSize = 30f
                paint.color = AColor.rgb(136, 153, 170)
                c.drawText("%.2f g".format(kotlin.math.abs(aLat)), cx - 40f, h - 90f, paint)
            }
        }

        // Mini route trace, top right, with position dot.
        val lats = fixes.map { it.lat }; val lons = fixes.map { it.lon }
        val minLat = lats.min(); val maxLat = lats.max()
        val minLon = lons.min(); val maxLon = lons.max()
        val spanLat = (maxLat - minLat).coerceAtLeast(1e-6)
        val spanLon = (maxLon - minLon).coerceAtLeast(1e-6)
        val boxW = 300f; val boxH = 220f
        val ox = w - boxW - 40f; val oy = 40f
        paint.color = AColor.argb(120, 42, 53, 64)
        paint.strokeWidth = 4f
        var lastX = 0f; var lastY = 0f
        fixes.forEachIndexed { i, p ->
            val x = ox + ((p.lon - minLon) / spanLon * boxW).toFloat()
            val y = oy + ((maxLat - p.lat) / spanLat * boxH).toFloat()
            if (i > 0) c.drawLine(lastX, lastY, x, y, paint)
            lastX = x; lastY = y
        }
        paint.color = AColor.rgb(29, 185, 84)
        val px = ox + ((f.lon - minLon) / spanLon * boxW).toFloat()
        val py = oy + ((maxLat - f.lat) / spanLat * boxH).toFloat()
        c.drawCircle(px, py, 10f, paint)
    }
}
