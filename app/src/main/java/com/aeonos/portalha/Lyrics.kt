package com.aeonos.portalha

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lyrics from LRCLIB (lrclib.net) — free, no API key, keyed by exactly the fields Music
 * Assistant hands us in the DIDL (artist, track, album, duration). Returns time-stamped
 * (synced) lyrics for sing-along when available, plain lyrics otherwise.
 */
object Lyrics {
    private const val TAG = "PortalHA"

    data class Line(val atMs: Int, val text: String)
    data class Result(val synced: List<Line>?, val plain: String?) {
        val hasAny get() = !synced.isNullOrEmpty() || !plain.isNullOrBlank()
    }

    /** Blocking — call from a background thread. Null on network error / nothing found. */
    fun fetch(artist: String, title: String, album: String, durationSec: Int): Result? {
        if (title.isBlank() && artist.isBlank()) return null
        // /api/get is an exact match on artist+track+album+duration (±2s). The duration Music
        // Assistant reports often disagrees with LRCLIB's (different pressing/edit), so a miss
        // here is common — fall back to a fuzzy search on just artist + track.
        exact(artist, title, album, durationSec)?.let { return it }
        return search(artist, title, durationSec)
    }

    private fun exact(artist: String, title: String, album: String, durationSec: Int): Result? {
        val q = buildString {
            append("https://lrclib.net/api/get")
            append("?track_name=").append(enc(title))
            append("&artist_name=").append(enc(artist))
            if (album.isNotBlank()) append("&album_name=").append(enc(album))
            if (durationSec > 0) append("&duration=").append(durationSec)
        }
        val body = httpGet(q) ?: return null
        return runCatching { toResult(JSONObject(body)) }.getOrNull()
    }

    /** Fuzzy fallback: search by artist+track, prefer synced lyrics then the closest duration. */
    private fun search(artist: String, title: String, durationSec: Int): Result? {
        val q = "https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}"
        val body = httpGet(q) ?: return null
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hasSynced = o.optString("syncedLyrics", "").isNotBlank()
            val hasPlain = o.optString("plainLyrics", "").isNotBlank()
            if (!hasSynced && !hasPlain && !o.optBoolean("instrumental", false)) continue
            // Synced beats plain; within that, the nearest duration wins.
            var score = if (hasSynced) 1_000_000 else 0
            if (durationSec > 0) {
                val d = o.optInt("duration", 0)
                if (d > 0) score -= kotlin.math.abs(d - durationSec)
            }
            if (score > bestScore) { bestScore = score; best = o }
        }
        val chosen = best ?: return null
        Log.i(TAG, "lyrics: search matched '${chosen.optString("artistName")} - " +
            "${chosen.optString("trackName")}' (${chosen.optInt("duration")}s vs ${durationSec}s)")
        return toResult(chosen)
    }

    private fun toResult(json: JSONObject): Result? {
        if (json.optBoolean("instrumental", false)) return Result(null, "♪ (instrumental)")
        val synced = json.optString("syncedLyrics", "").takeIf { it.isNotBlank() }?.let { parseLrc(it) }
        val plain = json.optString("plainLyrics", "").takeIf { it.isNotBlank() }
        return Result(synced, plain).takeIf { it.hasAny }
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000; readTimeout = 5000
                setRequestProperty("User-Agent",
                    "PortalHABridge (github.com/RoadRunner-1024/portal-ha-bridge)")
            }
            val code = conn.responseCode
            if (code != 200) { Log.i(TAG, "lyrics: LRCLIB $code for $url"); null }
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.i(TAG, "lyrics: fetch failed: ${e.message}"); null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // LRC: lines like "[01:23.45] text", possibly several timestamps per line. We flatten to
    // one (time, text) per timestamp and sort.
    private fun parseLrc(lrc: String): List<Line> {
        val stamp = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
        val out = ArrayList<Line>()
        for (raw in lrc.split('\n')) {
            val text = raw.replace(stamp, "").trim()
            for (m in stamp.findAll(raw)) {
                val min = m.groupValues[1].toInt()
                val sec = m.groupValues[2].toInt()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.take(3).toInt() }
                out.add(Line((min * 60 + sec) * 1000 + ms, text))
            }
        }
        return out.sortedBy { it.atMs }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
