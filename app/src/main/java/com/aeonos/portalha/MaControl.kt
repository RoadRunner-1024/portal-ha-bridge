package com.aeonos.portalha

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Next/previous for the now-playing overlay. A DLNA renderer is a passive speaker — Music
 * Assistant owns the queue — so "skip" can't be done through the renderer. Instead we ask
 * Home Assistant to call the media_player service on THIS Portal's Music Assistant entity,
 * which we resolve by matching the Portal's name against the media_player entities' friendly
 * names (cached after the first lookup). Degrades to a logged no-op if HA isn't configured or
 * no matching entity is found.
 */
object MaControl {
    private const val TAG = "PortalHA"
    @Volatile private var cachedEntity: String? = null

    fun next(ctx: Context) = skip(ctx, "media_next_track")
    fun previous(ctx: Context) = skip(ctx, "media_previous_track")
    fun playPause(ctx: Context) = skip(ctx, "media_play_pause")

    /**
     * What Music Assistant says this Portal is playing. The DLNA renderer can't be trusted for
     * this: with MA's "flow mode" the whole queue arrives as ONE continuous stream, so the
     * renderer only ever sees the track that was current when the stream began. HA's media_player
     * entity always reflects the real current track.
     */
    data class MaState(
        val state: String, val title: String, val artist: String, val album: String,
        val artUrl: String, val durationMs: Int, val positionMs: Int,
        /** HA's media_position_updated_at verbatim — a change means "position was re-reported". */
        val positionStamp: String,
    ) {
        val playing: Boolean get() = state == "playing"
        val active: Boolean get() = (state == "playing" || state == "paused") && title.isNotBlank()
    }

    fun poll(ctx: Context): MaState? {
        val p = Prefs(ctx)
        val base = p.haUrl.trim().trimEnd('/')
        val token = p.haToken
        if (base.isEmpty() || token.isEmpty()) return null
        val entity = (cachedEntity
            ?: resolveEntity(base, token, p.deviceName)?.also { cachedEntity = it }) ?: return null
        val body = get("$base/api/states/$entity", token) ?: return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val a = o.optJSONObject("attributes") ?: JSONObject()
        val state = o.optString("state")
        var pic = a.optString("entity_picture")
        if (pic.startsWith("/")) pic = base + pic       // HA serves proxied art as a relative path
        val durMs = (a.optDouble("media_duration", 0.0) * 1000).toInt()
        // media_position is only a SNAPSHOT taken at media_position_updated_at — for MA players
        // it's typically written once when the track starts and never refreshed, so the elapsed
        // time since that stamp must be added. The allowance has to cover a whole track (an
        // earlier 60s cap made the position snap back to 0 one minute in); anything beyond the
        // track length is clock skew or a stale entity, so fall back to the raw snapshot.
        val stamp = a.optString("media_position_updated_at")
        var posMs = (a.optDouble("media_position", 0.0) * 1000).toInt()
        if (state == "playing") {
            val upMs = parseIsoMs(stamp)
            if (upMs > 0) {
                val delta = System.currentTimeMillis() - upMs
                val cap = if (durMs > 0) (durMs - posMs + 30_000L) else 20 * 60_000L
                if (delta > 0 && delta <= cap) posMs += delta.toInt()
            }
        }
        if (durMs > 0) posMs = posMs.coerceIn(0, durMs)
        return MaState(
            state = state,
            title = a.optString("media_title"),
            artist = a.optString("media_artist"),
            album = a.optString("media_album_name"),
            artUrl = pic,
            durationMs = durMs,
            positionMs = posMs.coerceAtLeast(0),
            positionStamp = stamp,
        )
    }

    private fun parseIsoMs(s: String): Long =
        if (s.isBlank()) 0L
        else runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .getOrDefault(0L)

    private fun skip(ctx: Context, service: String) {
        thread(isDaemon = true) {
            val p = Prefs(ctx)
            val base = p.haUrl.trim().trimEnd('/')
            val token = p.haToken
            if (base.isEmpty() || token.isEmpty()) {
                Log.i(TAG, "dlna: skip ignored — HA URL/token not set"); return@thread
            }
            val entity = cachedEntity ?: resolveEntity(base, token, p.deviceName)?.also { cachedEntity = it }
            if (entity == null) {
                Log.w(TAG, "dlna: skip — no Music Assistant media_player entity matching '${p.deviceName}'")
                return@thread
            }
            val ok = post("$base/api/services/media_player/$service", token,
                JSONObject().put("entity_id", entity).toString())
            if (!ok) {
                Log.w(TAG, "dlna: skip $service failed on $entity — clearing cache to re-resolve")
                cachedEntity = null   // maybe the entity id changed; re-resolve next time
            } else {
                Log.i(TAG, "dlna: $service -> $entity")
            }
        }
    }

    // Find the media_player entity whose friendly name matches the Portal's name. MA names its
    // players after the DLNA friendlyName, so an exact (case-insensitive) match wins; failing
    // that, a contains-match on the name or a slug match on the entity_id.
    private fun resolveEntity(base: String, token: String, deviceName: String): String? {
        val body = get("$base/api/states", token) ?: return null
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val want = deviceName.trim().lowercase()
        val slug = want.replace(Regex("[^a-z0-9]+"), "_").trim('_')
        var contains: String? = null
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = e.optString("entity_id")
            if (!id.startsWith("media_player.")) continue
            val name = (e.optJSONObject("attributes")?.optString("friendly_name") ?: "").lowercase()
            if (name == want) return id                                    // exact name
            if (id == "media_player.$slug") return id                      // exact slug
            if (contains == null && (name.contains(want) || id.contains(slug))) contains = id
        }
        return contains
    }

    private fun get(url: String, token: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 5000; readTimeout = 5000
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) { Log.i(TAG, "dlna: HA GET failed: ${e.message}"); null }
        finally { runCatching { conn?.disconnect() } }
    }

    private fun post(url: String, token: String, json: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 5000; readTimeout = 5000; doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(json.toByteArray()) }
            conn.responseCode in 200..299
        } catch (e: Exception) { Log.i(TAG, "dlna: HA POST failed: ${e.message}"); false }
        finally { runCatching { conn?.disconnect() } }
    }
}
