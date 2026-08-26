package com.rallycopilot.app.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Over-the-air updates from GitHub Releases. The app checks the public latest-release
 * API (no auth, works on any network), downloads a newer APK in the background, and
 * hands the user Android's one-tap install sheet. In-place update: data survives.
 */
object Updater {

    private const val REPO = "thelordsnoopy/rally-copilot"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class Available(val version: String, val apkFile: File, val notes: String)

    /**
     * Outcome of a check. A manual check needs to say WHY it found nothing —
     * "you're up to date" and "I couldn't reach GitHub" look identical otherwise,
     * and a silent no-op is exactly what makes a button feel broken.
     */
    sealed class Result {
        data class Update(val available: Available) : Result()
        data class UpToDate(val version: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /** Returns a downloaded, ready-to-install update, or null if we're current. */
    suspend fun check(context: Context, currentVersion: String): Available? =
        (checkNow(context, currentVersion) as? Result.Update)?.available

    /** As [check], but reports the reason when no update comes back. */
    suspend fun checkNow(context: Context, currentVersion: String): Result = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isEmpty()) return@withContext Result.Failed("no release found")
            if (!isNewer(tag, currentVersion)) return@withContext Result.UpToDate(currentVersion)

            val assets = json.optJSONArray("assets")
                ?: return@withContext Result.Failed("release v$tag has no files")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url"); break
                }
            }
            apkUrl ?: return@withContext Result.Failed("release v$tag has no APK")

            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "update-$tag.apk")
            // Already downloaded on a previous check? Reuse.
            if (!out.exists() || out.length() < 1_000_000) {
                // Download to a temp name and rename only when COMPLETE — a partial
                // file under the final name would be cached and re-offered as a
                // broken installer forever.
                val tmp = File(dir, "update-$tag.apk.part")
                try {
                    val dl = URL(apkUrl).openConnection() as HttpURLConnection
                    dl.connectTimeout = 10000
                    // No read timeout = a stalled network pins this thread forever.
                    dl.readTimeout = 30000
                    dl.instanceFollowRedirects = true
                    val expected = dl.contentLengthLong
                    dl.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    dl.disconnect()
                    if (expected > 0 && tmp.length() != expected) throw java.io.IOException(
                        "short download: ${tmp.length()} of $expected"
                    )
                    if (out.exists()) out.delete()
                    if (!tmp.renameTo(out)) throw java.io.IOException("rename failed")
                } finally {
                    tmp.delete()
                }
            }
            // Clean older cached updates.
            dir.listFiles()?.forEach { if (it != out) it.delete() }

            Result.Update(Available(tag, out, json.optString("body", "")))
        } catch (e: Exception) {
            // Offline, rate-limited, or a bad payload. The launch check ignores this
            // and stays current; a manual check surfaces it.
            Result.Failed(
                when (e) {
                    is java.net.UnknownHostException -> "no internet connection"
                    is java.net.SocketTimeoutException -> "GitHub timed out"
                    else -> e.message?.take(60) ?: "check failed"
                }
            )
        }
    }

    /** Fire Android's package-install sheet for the downloaded APK. One tap for the user. */
    fun install(context: Context, update: Available) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", update.apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Numeric semver compare: "0.3.1" vs "0.3.0". */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split(".").mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        val b = current.split(".").mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
