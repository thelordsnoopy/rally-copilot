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
    @Volatile private var focusHeld = false
    @Volatile private var speakingUntil = 0L

    /**
     * What the co-driver is allowed to do to whatever else is playing.
     *
     * NONE is the default at the user's request: a co-driver in a real car simply
     * talks over the stereo, and every alternative touches somebody else's playback
     * — which is how this app spent several versions pausing, ducking and even
     * starting music that was never playing.
     */
    enum class FocusMode {
        /** Never ask for audio focus at all: notes mix over whatever is playing. */
        NONE,
        /** Ask the music to dip, and talk over the top of it. */
        DUCK,
        /** Pause the music for the call, then let it resume. */
        PAUSE,
    }

    @Volatile
    var focusMode: FocusMode = FocusMode.NONE

    /** Voice level, 0..1. */
    @Volatile var volume: Float = 1.0f
        private set

    /**
     * Extra gain ON TOP of full volume, dB, via Android's LoudnessEnhancer.
     *
     * The volume slider tops out at digital full scale, and against a car stereo
     * at road-trip volume that was not enough — Chad had 100% set and still could
     * not hear the calls over music. The clips are now mastered ~7 dB hotter at
     * build time, and this adds up to 12 dB more at playback for the driver who
     * wants the co-driver to simply be the loudest thing in the car.
     */
    @Volatile var boostDb: Int = 6

    /** Hands-free "quiet": everything is swallowed until "talk". The keep-alive
     *  stream keeps running so unmuting doesn't cost a swallowed first syllable. */
    @Volatile var muted: Boolean = false

    /** The last utterance requested, for the hands-free "again" command. */
    @Volatile private var lastUtterance: Utterance? = null

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

    /** The same route to the car's speakers the notes use — the chirp must
     *  travel it too, or it would be measuring a different path. */
    val attributes: AudioAttributes get() = audioAttrs

    /**
     * Which channel the voice travels on.
     *
     * NAVIGATION is semantically correct — this IS turn-by-turn guidance — and it
     * is what makes well-behaved players duck. But phones and head units are free
     * to give navigation its own mix, and plenty give it a quieter one: Samsung
     * handsets and many car stereos deliberately hold guidance below media so a
     * satnav cannot drown the music. That is the opposite of what a co-driver is
     * for, and it is invisible from inside the app — the clips leave here at full
     * scale either way.
     *
     * MEDIA puts the voice in exactly the same mix as the music, at the same level.
     * Ducking still works, because ducking follows the audio-focus request, not the
     * usage tag.
     */
    @Volatile var outputAsMedia: Boolean = false
        private set

    /** True/false once a boost has been attempted; null before that. Some devices
     *  refuse the effect outright, and a boost that never engaged looks identical
     *  to one that did nothing. */
    @Volatile var boostEngaged: Boolean? = null
        private set

    private fun buildAttrs(media: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(
            if (media) AudioAttributes.USAGE_MEDIA
            else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        )
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Volatile private var audioAttrs = buildAttrs(false)

    /**
     * Switch channel. Everything built from the attributes has to be rebuilt: the
     * keep-alive stream carries them, and the focus request bakes them in.
     */
    fun setOutputAsMedia(media: Boolean) {
        if (media == outputAsMedia) return
        outputAsMedia = media
        audioAttrs = buildAttrs(media)
        focusRequest = null
        builtForGain = -1
        if (keepAlive != null) { stopKeepAlive(); startKeepAlive() }
    }

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

    override fun play(utterance: Utterance) = play(utterance, force = false)

    /**
     * [force] bypasses mute — used only by the driver's own "again" request:
     * an answer you explicitly asked for should never be swallowed by the mute
     * you also asked for.
     */
    fun play(utterance: Utterance, force: Boolean) {
        lastUtterance = utterance
        if (muted && !force) return
        val set = if (utterance.urgent) "urgent" else "normal"
        val paths = utterance.clipKeys.map { key ->
            val k = "$set/$key"
            if (durations.containsKey(k)) "voice/$k.mp3" else "voice/normal/$key.mp3"
        }
        val totalMs = utterance.clipKeys.sumOf { clipDurationMs(it) }
        speakingUntil = System.currentTimeMillis() + totalMs + 300
        handler.post {
            acquireFocus()
            stopPlayerQuietly()
            queue = ArrayDeque(paths)
            playNext()
            // Grace period covers the gap between chained clips and the head unit's
            // own output delay, so the music is not yo-yoing mid-note.
            scheduleFocusRelease(totalMs + 1200)
        }
    }

    /** Hands-free "again": replay the last thing said, mute or no mute. */
    fun repeatLast() {
        lastUtterance?.let { play(it, force = true) }
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
            var boost: android.media.audiofx.LoudnessEnhancer? = null
            fun cleanup(m: MediaPlayer) {
                runCatching { boost?.release() }
                m.release()
                if (player === m) player = null
            }
            mp.setOnCompletionListener { cleanup(it); playNext() }
            mp.setOnErrorListener { p, _, _ -> cleanup(p); playNext(); true }
            mp.prepare()
            val (l, r) = channelGains()
            mp.setVolume(l, r)
            // Best effort: some devices refuse the effect; the call still plays.
            if (boostDb > 0) {
                boost = runCatching {
                    android.media.audiofx.LoudnessEnhancer(mp.audioSessionId).apply {
                        setTargetGain(boostDb * 100) // millibels
                        enabled = true
                    }
                }.getOrNull()
                boostEngaged = boost?.let { runCatching { it.enabled }.getOrDefault(false) } ?: false
            }
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

    /**
     * Continuous near-silence so a car head unit never idles the A2DP link and
     * swallows the first syllable of the next note.
     *
     * The cost: if the music is coming from the car itself — radio, USB, another
     * phone — an always-active Bluetooth stream makes many head units switch source
     * to Bluetooth and stay there. That is the second way this app can take over
     * someone's music, and it has nothing to do with audio focus. Hence the toggle.
     */
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

    /**
     * Audio focus is taken PER UTTERANCE and handed back as soon as the call is
     * over — never held across a drive.
     *
     * Holding AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK from the moment DRIVE was tapped
     * until the drive ended is what silenced Chad's music for an entire journey:
     * "transient" is a promise to give it back shortly, and plenty of players
     * (and car head units) respond to it by pausing outright rather than ducking.
     * A co-driver wants the music dipped for the second and a half it is talking,
     * and not one moment longer.
     */
    /**
     * Is somebody ELSE actually playing music right now?
     *
     * This gate exists because abandoning audio focus tells the previous owner it
     * may resume — and a media app that was sitting paused takes that as "play".
     * So a co-driver that grabs focus when nothing is playing does not merely
     * interrupt the music, it STARTS music the driver never asked for. If nothing
     * is playing there is nothing to pause, so we never ask for focus at all.
     *
     * AudioManager.isMusicActive() is no use on its own here: our own keep-alive
     * stream and our own notes can both make it read true. The playback
     * configuration list carries each player's usage, so our navigation-guidance
     * tracks can be told apart from someone's music.
     */
    private fun someoneElseIsPlaying(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val configs = runCatching { am.activePlaybackConfigurations }.getOrNull()
        if (!configs.isNullOrEmpty()) {
            return configs.any { c ->
                when (c.audioAttributes.usage) {
                    AudioAttributes.USAGE_MEDIA,
                    AudioAttributes.USAGE_GAME,
                    AudioAttributes.USAGE_UNKNOWN -> true
                    else -> false
                }
            }
        }
        // Nothing legible in the list (some devices anonymise it). Fall back to the
        // blunt check, but only when none of our own audio could be confusing it.
        if (keepAlive != null || isSpeaking()) return false
        return runCatching { am.isMusicActive }.getOrDefault(false)
    }

    @Synchronized
    private fun acquireFocus() {
        if (focusMode == FocusMode.NONE || focusHeld) return
        // Nothing playing => nothing to pause => nothing to accidentally un-pause.
        if (!someoneElseIsPlaying()) return
        val gain = if (focusMode == FocusMode.PAUSE) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // The request is rebuilt when the mode changes: gain type is baked into it.
        val req = focusRequest?.takeIf { builtForGain == gain }
            ?: android.media.AudioFocusRequest.Builder(gain)
                .setAudioAttributes(audioAttrs)
                .setWillPauseWhenDucked(false)
                .build().also { focusRequest = it; builtForGain = gain }
        am.requestAudioFocus(req)
        focusHeld = true
    }

    private var builtForGain: Int = -1

    @Synchronized
    private fun releaseFocus() {
        if (!focusHeld) return
        focusHeld = false
        focusRequest?.let {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.abandonAudioFocusRequest(it)
        }
    }

    /**
     * Hold the music off for a measurement — the latency chirp has to be heard by
     * the microphone over the cabin, and a song playing across it is the one thing
     * guaranteed to make the measurement fail or lie.
     *
     * This IGNORES [focusMode], deliberately. "Leave my music alone" is about the
     * co-driver's ordinary talking; the chirp is a ten-second instrument reading at
     * drive start whose failure mistimes every corner call of the drive. Pause is
     * requested whatever the setting says — but still only when something is
     * actually playing, so a paused player is never woken by the focus handback.
     */
    fun beginMeasurement() {
        handler.removeCallbacks(releaseFocusRunnable)
        if (measurementFocus != null || !someoneElseIsPlaying()) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = android.media.AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttrs)
            .build()
        measurementFocus = req
        am.requestAudioFocus(req)
    }

    fun endMeasurement() {
        handler.postDelayed({
            measurementFocus?.let {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.abandonAudioFocusRequest(it)
            }
            measurementFocus = null
        }, 400)
    }

    private var measurementFocus: android.media.AudioFocusRequest? = null

    /** Hand the music back once this utterance is done — unless another call has
     *  started by then, in which case that one owns the focus and its own release. */
    private val releaseFocusRunnable = Runnable { releaseFocus() }

    private fun scheduleFocusRelease(afterMs: Long) {
        handler.removeCallbacks(releaseFocusRunnable)
        handler.postDelayed(releaseFocusRunnable, afterMs)
    }

    fun release() {
        stopKeepAlive()
        handler.removeCallbacks(releaseFocusRunnable)
        handler.post { stopPlayerQuietly() }
        // Give the driver their music back — holding transient focus after the drive
        // leaves other apps ducked (or paused) until this process dies.
        releaseFocus()
        focusRequest = null
    }

    /** Final teardown when the owning service dies. */
    fun shutdown() {
        release()
        playbackThread.quitSafely()
    }
}
