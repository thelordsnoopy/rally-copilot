package com.rallycopilot.app.audio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Hands-free control: a small closed command set, recognised continuously during a
 * drive with on-device speech recognition. Hands stay on the wheel.
 *
 *   "again"            → repeat the last utterance
 *   "quiet" / "talk"   → mute / unmute the co-driver
 *   "louder"/"quieter" → volume nudges
 *   "wrong"            → flag the last call into the run log (tuning data)
 *
 * Best-effort by design: recognition over cabin noise is imperfect, so every
 * command is harmless to miss and harmless to false-trigger — nothing here brakes
 * the car or deletes data. Errors restart the listener with backoff; a device
 * with no recogniser simply never starts.
 */
class VoiceCommands(
    private val context: Context,
    private val onCommand: (Command) -> Unit,
) {
    enum class Command { AGAIN, QUIET, TALK, LOUDER, QUIETER, WRONG }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var running = false
    private var errorStreak = 0

    // Contains-matching on the recognised phrase, most specific first. "say that
    // again" must hit AGAIN, not fall through; "no quiet" is not worth solving.
    private val phrases: List<Pair<List<String>, Command>> = listOf(
        listOf("again", "repeat", "what was that", "say that") to Command.AGAIN,
        listOf("quiet", "shut up", "mute") to Command.QUIET,
        listOf("talk", "unmute", "speak") to Command.TALK,
        listOf("louder", "volume up", "turn it up") to Command.LOUDER,
        listOf("quieter", "volume down", "turn it down") to Command.QUIETER,
        listOf("wrong", "rubbish", "not right") to Command.WRONG,
    )

    fun start() {
        if (running) return
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        running = true
        handler.post { createAndListen() }
    }

    fun stop() {
        running = false
        handler.post {
            recognizer?.let { runCatching { it.destroy() } }
            recognizer = null
        }
    }

    /** Main thread only — SpeechRecognizer demands it. */
    private fun createAndListen() {
        if (!running) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            recognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    matchAndFire(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                    errorStreak = 0
                    restart(300)
                }

                override fun onError(error: Int) {
                    // NO_MATCH / timeout are the normal idle case; real faults back off
                    // harder so a broken recogniser doesn't spin the battery... or the
                    // one thing that never comes back: permissions.
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stop(); return }
                    errorStreak++
                    val delay = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 250L
                        else -> (1000L * errorStreak).coerceAtMost(10_000L)
                    }
                    restart(delay)
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            // On-device only: no network round trip, no audio leaving the phone.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { r.startListening(intent) }
    }

    private fun restart(delayMs: Long) {
        if (!running) return
        handler.postDelayed({ if (running) createAndListen() }, delayMs)
    }

    private fun matchAndFire(candidates: List<String>?) {
        if (candidates.isNullOrEmpty()) return
        for (heard in candidates) {
            val text = heard.lowercase()
            for ((words, cmd) in phrases) {
                if (words.any { text.contains(it) }) {
                    onCommand(cmd)
                    return
                }
            }
        }
    }
}
