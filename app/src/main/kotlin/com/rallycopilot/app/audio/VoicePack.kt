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
    /** Playback runs on its own thread: MediaPlayer prepare() is synchronous I/O and
     *  has no business on the main thread mid-drive. */
    private val playbackThread = android.os.HandlerThread("voice").apply { start() }
    private val handler = Handler(playbackThread.looper)
    private var focusRequest: android.media.AudioFocusRequest? = null
    @Volatile private var speakingUntil = 0L

    /** Voice level, 0..1. */
    @Volatile var volume: Float = 1.0f
        private set

    /**
     * Stereo balance, -1 = full left … 0 = centre … +1 = full right.
     *
     * Android cannot address one physical car speaker over Bluetooth — the head unit
     * owns the speakers and A2DP carries a plain stereo pair. Panning is the real
     * lever: hard left puts the voice in the left-hand (UK driver's side) speakers
     * and silences the right. Whether that lands on the door or the dash depends on
     * how the car mixes a stereo source; the head unit's own fader can finish the job.
     */
    @Volatile var balance: Float = 0.0f
        private set

    fun setVolume(v: Float) { volume = v.coerceIn(0f, 1f); applyGain() }
    fun setBalance(b: Float) { balance = b.coerceIn(-1f, 1f); applyGain() }

    /** Per-channel gains from volume + balance, equal-power so centre isn't loud. */
    private fun channelGains(): Pair<Float, Float> {
        val l = if (balance <= 0f) 1f else (1f - balance)
        val r = if (balance >= 0f) 1f else (1f + balance)
        return (l * volume) to (r * volume)
    }

    private fun applyGain() {
        val (l, r) = channelGains()
        handler.post { runCatching { player?.setVolume(l, r) } }
    }
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
            val (l, r) = channelGains()
            mp.setVolume(l, r)
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
        val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttrs)
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    fun release() {
        stopKeepAlive()
        handler.post { stopPlayerQuietly() }
        // Give the driver their music back — holding transient focus after the drive
        // leaves other apps ducked (or paused) until this process dies.
        focusRequest?.let {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.abandonAudioFocusRequest(it)
        }
        focusRequest = null
    }

    /** Final teardown when the owning service dies. */
    fun shutdown() {
        release()
        playbackThread.quitSafely()
    }
}
