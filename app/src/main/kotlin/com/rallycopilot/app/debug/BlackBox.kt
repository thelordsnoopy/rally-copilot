package com.rallycopilot.app.debug

import android.content.Context
import com.rallycopilot.core.engine.Telemetry
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * The black box recorder.
 *
 * One JSON-lines file per drive, written to the app's external files directory so
 * it can be pulled off the phone over a cable with no root and no permissions:
 *
 *     adb pull /sdcard/Android/data/com.rallycopilot.app/files/blackbox
 *
 * This exists because the app has been debugged from memory — "it spammed five
 * corners", "the map cut off somewhere near Whiteshill" — which is a slow and lossy
 * channel for something that makes a hundred decisions a minute. A trace answers
 * the question the driver cannot: not just what was said, but what was nearly said,
 * what was rejected, and on what numbers.
 *
 * Writing happens on its own thread behind an unbounded queue; the engine thread
 * never touches the filesystem. If the queue somehow outruns the writer the oldest
 * entries are dropped rather than stalling a drive — a lost line matters less than
 * a late corner call.
 */
class BlackBox(context: Context, runId: Long) : Telemetry {

    private val queue = LinkedBlockingQueue<String>(MAX_QUEUED)
    @Volatile private var running = true
    val file: File

    init {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "blackbox")
        dir.mkdirs()
        pruneOldFiles(dir)
        file = File(dir, "drive-$runId.jsonl")
        Thread({
            file.bufferedWriter().use { w ->
                while (running || queue.isNotEmpty()) {
                    val line = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        ?: continue
                    w.write(line); w.write("\n")
                    // Flush often: the interesting drives are the ones that end in a
                    // crash or a battery pull, and an unflushed buffer is no record.
                    if (queue.isEmpty()) w.flush()
                }
                w.flush()
            }
        }, "blackbox").apply { isDaemon = true; start() }
    }

    override fun log(kind: String, fields: Map<String, Any?>) {
        if (!running) return
        val o = JSONObject()
        o.put("t", System.currentTimeMillis())
        o.put("k", kind)
        for ((k, v) in fields) {
            o.put(k, when (v) {
                null -> JSONObject.NULL
                is Double -> if (v.isFinite()) Math.round(v * 1000.0) / 1000.0 else JSONObject.NULL
                is Float -> if (v.isFinite()) Math.round(v * 1000.0) / 1000.0 else JSONObject.NULL
                else -> v
            })
        }
        // Never block the caller: if the writer is behind, drop the oldest line.
        if (!queue.offer(o.toString())) {
            queue.poll()
            queue.offer(o.toString())
        }
    }

    fun close() {
        running = false
    }

    companion object {
        private const val MAX_QUEUED = 20_000
        /** Keep the last few drives... */
        private const val KEEP_FILES = 8
        /**
         * ...but bounded by total size first, because this records everything at
         * full tick rate: roughly 10 lines a second of engine state plus 10 of IMU
         * plus every GPS fix, which is a few MB for every ten minutes driven. The
         * phone's storage matters more than the eighth-oldest drive.
         */
        private const val MAX_TOTAL_BYTES = 300L * 1024 * 1024

        private fun pruneOldFiles(dir: File) {
            var files = (dir.listFiles { f -> f.name.endsWith(".jsonl") } ?: return)
                .sortedByDescending { it.lastModified() }
            if (files.size > KEEP_FILES) {
                files.drop(KEEP_FILES).forEach { runCatching { it.delete() } }
                files = files.take(KEEP_FILES)
            }
            var total = 0L
            for (f in files) {
                total += f.length()
                if (total > MAX_TOTAL_BYTES) runCatching { f.delete() }
            }
        }

        /** Where the traces live, for the settings screen to show. */
        fun directory(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "blackbox")
    }
}
