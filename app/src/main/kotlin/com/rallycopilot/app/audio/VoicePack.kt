package com.rallycopilot.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.rallycopilot.core.engine.AudioSink
import com.rallycopilot.core.model.Utterance
import org.json.JSONObject
import java.io.File

/**
 * Voice pack playback: pre-rendered vocabulary clips concatenated with measured
 * durations, plus an A2DP keep-alive stream so car head units never idle the link
 * and swallow the first syllable.
 */
class VoicePack(private val context: Context) : AudioSink {

    private val durations = HashMap<String, Long>()   // "normal/left_four" -> ms
    private val handler = Handler(Looper.getMainLooper())
    private var speakingUntil = 0L
    private var player: MediaPlayer? = null
    private var queue = ArrayDeque<String>()          // asset paths remaining in current utterance
    private var keepAlive: AudioTrack? = null
    private var keepAliveThread: Thread? = null

    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        val manifest = context.assets.open("voice/manifest.json").bufferedReader().readText()
        val sets = JSONObject(manifest).getJSONObject("sets")
        for (setName in sets.keys()) {
            val set = sets.getJSONObject(setName)
            for (key in set.keys()) durations["$setName/$key"] = set.getLong(key)
        }
    }

    // ---- AudioSink ----

    override fun clipDurationMs(key: String): Long =
        durations["normal/$key"] ?: durations["urgent/$key"] ?: 600L

    override fun play(utterance: Utterance) {
        val set = if (utterance.urgent) "urgent" else "normal"
        val paths = utterance.clipKeys.map { key ->
            val k = "$set/$key"
            if (durations.containsKey(k)) "voice/$k.mp3" else "voice/normal/$key.mp3"
        }
        val totalMs = utterance.clipKeys.sumOf { clipDurationMs(it) }
        speakingUntil = System.currentTimeMillis() + totalMs + 300
        handler.post {
            stopPlayerQuietly()
            queue = ArrayDeque(paths)
            playNext()
        }
    }

    override fun isSpeaking(): Boolean = System.currentTimeMillis() < speakingUntil

    override fun remainingMs(): Long =
        (speakingUntil - System.currentTimeMillis()).coerceAtLeast(0)

    // ---- playback chain ----

    private fun playNext() {
        val path = queue.removeFirstOrNull() ?: return
        try {
            val afd = context.assets.openFd(path)
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(audioAttrs)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setOnCompletionListener { it.release(); if (player === it) player = null; playNext() }
            mp.setOnErrorListener { p, _, _ -> p.release(); if (player === p) player = null; playNext(); true }
            mp.prepare()
            mp.start()
        } catch (_: Exception) {
            playNext()
        }
    }

    private fun stopPlayerQuietly() {
        queue.clear()
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
    }

    // ---- A2DP keep-alive: continuous near-silence so the BT link never sleeps ----

    fun startKeepAlive() {
        if (keepAlive != null) return
        val sampleRate = 16000
        val buf = ShortArray(sampleRate / 10) // 100 ms chunks
        // Not literal zeros: some head units gate on digital silence. 1-LSB dither.
        for (i in buf.indices) buf[i] = if (i % 97 == 0) 1 else 0
        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buf.size * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.setVolume(0.01f)
        keepAlive = track
        track.play()
        keepAliveThread = Thread {
            while (keepAlive === track) {
                try { track.write(buf, 0, buf.size) } catch (_: Exception) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stopKeepAlive() {
        val t = keepAlive
        keepAlive = null
        try { t?.stop(); t?.release() } catch (_: Exception) {}
        keepAliveThread = null
    }

    fun requestFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    }

    fun release() {
        stopKeepAlive()
        stopPlayerQuietly()
    }
}
