package com.rallycopilot.app.drive

import com.rallycopilot.core.model.Conditions
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Auto wet/dry: one Open-Meteo request at drive start (no key, no account).
 * Raining now, or meaningful rain in the last three hours → the roads are wet.
 * Entirely best-effort — offline returns null and the caller falls back to the
 * last auto choice. Auto is a default, never a lock: DRY/WET stay one tap away.
 */
object WeatherCheck {

    /** Rain rate that counts as "raining now", mm/h. */
    private const val RAIN_NOW_MM = 0.1
    /** Accumulated rain over the last 3 h that leaves the roads wet, mm. */
    private const val RECENT_RAIN_MM = 1.0

    data class Result(val conditions: Conditions, val why: String)

    fun check(lat: Double, lon: Double, timeoutMs: Int = 3000): Result? {
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=%.3f&longitude=%.3f".format(lat, lon) +
                    "&current=precipitation&hourly=precipitation&past_hours=3&forecast_hours=1"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val o = JSONObject(body)
            val now = o.getJSONObject("current").optDouble("precipitation", 0.0)
            var recent = 0.0
            o.optJSONObject("hourly")?.optJSONArray("precipitation")?.let { arr ->
                for (i in 0 until arr.length()) recent += arr.optDouble(i, 0.0)
            }
            when {
                now >= RAIN_NOW_MM -> Result(Conditions.WET, "raining now")
                recent >= RECENT_RAIN_MM ->
                    Result(Conditions.WET, "%.1f mm rain in the last 3 h".format(recent))
                else -> Result(Conditions.DRY, "no recent rain")
            }
        } catch (_: Exception) {
            null
        }
    }
}
