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
 * API (no auth, works on any network), and hands the user Android's one-tap install
 * sheet. In-place update: data survives.
 *
 * CHECKING and DOWNLOADING are deliberately separate. They used to be one call, so
 * "check for updates" silently pulled a 39 MB APK before it could answer — which
 * looked exactly like the button having hung, and quietly spent mobile data on every
 * single app launch. A check is now one small API request; the download is an
 * explicit, cancellable step with visible progress.
 */
object Updater {

    private const val REPO = "thelordsnoopy/rally-copilot"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    /** A newer release that exists on GitHub. Nothing has been downloaded yet. */
    data class Release(
        val version: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val notes: String,
    ) {
        val sizeMb: String get() = "%.0f MB".format(sizeBytes / 1_048_576.0)
    }

    /** A release that IS downloaded and ready for the installer. */
    data class Available(val version: String, val apkFile: File, val notes: String)

    /**
     * Outcome of a check. A manual check has to say WHY it found nothing — "you're
     * up to date" and "I couldn't reach GitHub" look identical otherwise, and a
     * button that silently does nothing reads as broken.
     */
    sealed class Result {
        data class Update(val release: Release) : Result()
        data class UpToDate(val version: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /** Fast: one API request, no download. Safe to call on every launch. */
    suspend fun checkNow(currentVersion: String): Result = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code == 403) { conn.disconnect(); return@withContext Result.Failed("GitHub rate limit — try again later") }
            if (code != 200) { conn.disconnect(); return@withContext Result.Failed("GitHub returned $code") }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isEmpty()) return@withContext Result.Failed("no release found")
            if (!isNewer(tag, currentVersion)) return@withContext Result.UpToDate(currentVersion)

            val assets = json.optJSONArray("assets")
                ?: return@withContext Result.Failed("release v$tag has no files")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return@withContext Result.Update(
                        Release(
                            version = tag,
                            apkUrl = a.optString("browser_download_url"),
                            sizeBytes = a.optLong("size", 0L),
                            notes = json.optString("body", ""),
                        )
                    )
                }
            }
            Result.Failed("release v$tag has no APK")
        } catch (e: Exception) {
            Result.Failed(describe(e))
        }
    }

    /** An already-downloaded APK for this version, if one is sitting in the cache. */
    fun cached(context: Context, release: Release): Available? {
        val f = File(File(context.cacheDir, "updates"), "update-${release.version}.apk")
        val complete = f.exists() &&
            (release.sizeBytes <= 0L || f.length() == release.sizeBytes) &&
            f.length() > 1_000_000
        return if (complete) Available(release.version, f, release.notes) else null
    }

    /**
     * Download the APK, reporting 0..100. Writes to a temp name and renames only on
     * a verified-complete file, so an interrupted download can never be cached and
     * re-offered as a broken installer.
     */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit = {},
    ): kotlin.Result<Available> = withContext(Dispatchers.IO) {
        cached(context, release)?.let { return@withContext kotlin.Result.success(it) }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "update-${release.version}.apk")
        val tmp = File(dir, "update-${release.version}.apk.part")
        try {
            val dl = URL(release.apkUrl).openConnection() as HttpURLConnection
            dl.connectTimeout = 10000
            // No read timeout would let a stalled network pin this forever.
            dl.readTimeout = 30000
            dl.instanceFollowRedirects = true
            val expected = if (release.sizeBytes > 0) release.sizeBytes else dl.contentLengthLong
            dl.inputStream.use { input ->
                tmp.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        total += n
                        if (expected > 0) {
                            val pct = ((total * 100) / expected).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            dl.disconnect()
            if (expected > 0 && tmp.length() != expected) {
                throw java.io.IOException("incomplete download (${tmp.length()} of $expected)")
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw java.io.IOException("could not save the download")
            // Clean older cached updates.
            dir.listFiles()?.forEach { if (it != out) it.delete() }
            onProgress(100)
            kotlin.Result.success(Available(release.version, out, release.notes))
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            kotlin.Result.failure(IllegalStateException(describe(e)))
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "no internet connection"
        is java.net.SocketTimeoutException -> "connection timed out"
        is javax.net.ssl.SSLException -> "secure connection failed"
        else -> e.message?.take(70) ?: e.javaClass.simpleName
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
