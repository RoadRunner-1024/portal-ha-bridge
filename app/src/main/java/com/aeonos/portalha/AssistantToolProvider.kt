package com.aeonos.portalha

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tool-provider plugin for portal-assistant ("Jarvis"). Lets you control this Portal
 * and your whole Home Assistant by voice WITHOUT modifying Jarvis: Jarvis discovers
 * this exported provider from the manifest meta-data (com.portal.assistant.tools =
 * the JSON tool declarations in @string/portal_tools), the user enables it in Jarvis
 * Settings -> External tools, and when the model calls a tool Jarvis does a
 * ContentResolver.call(method="invoke", arg=toolName, extras=ARGS). We do the action
 * and return a JSON result that Jarvis speaks back.
 *
 * The literal contract strings (not a code dependency) are mirrored in [Companion].
 *
 * Security: the tools control the screen, camera, and the entire smart home, so only
 * the assistant package may invoke them, and the Home Assistant tools additionally
 * require a long-lived token set in this app's settings (Profile -> Long-Lived Tokens).
 */
class AssistantToolProvider : ContentProvider() {

    companion object {
        private const val TAG = "PortalHA"
        // portal-assistant ToolContract literals (kept in sync by string, no dependency).
        private const val METHOD_INVOKE = "invoke"
        private const val EXTRA_ARGS_JSON = "com.portal.assistant.tools.extra.ARGS"
        private const val EXTRA_RESULT_JSON = "com.portal.assistant.tools.extra.RESULT"
        // Only the assistant app may invoke our tools (they're powerful + the provider
        // is necessarily exported for Jarvis to reach it).
        private const val ASSISTANT_PKG = "com.portal.assistant"
        // Our tool namespace (reverse-domain; must NOT start with "portal." per contract).
        private const val PREFIX = "com.aeonos.portalha."
        private const val HTTP_TIMEOUT_MS = 4000
    }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // Guard: only the assistant may drive these tools.
        val caller = callingPackage
        if (caller != ASSISTANT_PKG) {
            Log.w(TAG, "tools: rejected invoke from '$caller' (only $ASSISTANT_PKG allowed)")
            return result(err("unauthorized caller"))
        }
        if (method != METHOD_INVOKE || arg == null) return result(err("bad invoke"))
        val args = runCatching { JSONObject(extras?.getString(EXTRA_ARGS_JSON) ?: "{}") }
            .getOrDefault(JSONObject())
        Log.i(TAG, "tools: invoke $arg $args")
        return runCatching { result(handleTool(arg, args)) }
            .getOrElse { e ->
                Log.w(TAG, "tools: '$arg' failed: ${e.message}")
                result(err(e.message ?: "tool error"))
            }
    }

    private fun handleTool(name: String, args: JSONObject): JSONObject {
        val ctx = context ?: return err("no context")
        return when (name) {
            PREFIX + "set_screen" -> {
                val on = args.optBoolean("on", true)
                if (on) ScreenControl.wake(ctx) else ScreenControl.sleep()
                ok().put("screen", if (on) "on" else "off")
            }
            PREFIX + "set_camera" -> {
                val on = args.optBoolean("on", true)
                BridgeService.setCamera(ctx, on)
                ok().put("camera", if (on) "on" else "off")
            }
            PREFIX + "get_presence" -> {
                val p = BridgeService.currentPresence()
                ok().put("present", p ?: false).put("known", p != null)
            }
            PREFIX + "home_assistant" ->
                haConversation(ctx, args.optString("command", "").trim())
            PREFIX + "home_assistant_list" ->
                haList(ctx, args.optString("query", "").trim(), args.optString("domain", "").trim())
            PREFIX + "home_assistant_service" -> haService(
                ctx,
                args.optString("domain", "").trim(),
                args.optString("service", "").trim(),
                args.optString("entity_id", "").trim(),
                args.optString("data_json", "").trim()
            )
            else -> err("unknown tool: $name")
        }
    }

    // ── Home Assistant REST passthrough ───────────────────────────────────────
    // Free-text -> HA's Assist conversation engine (uses the user's exposed entities).
    private fun haConversation(ctx: Context, command: String): JSONObject {
        if (command.isEmpty()) return err("empty command")
        val (base, token) = haCreds(ctx)
            ?: return err("Home Assistant URL/token not set in Portal HA Bridge settings")
        val body = JSONObject().put("text", command).put("language", "en")
        val resp = httpPost("$base/api/conversation/process", token, body.toString())
            ?: return err("Home Assistant request failed")
        val speech = runCatching {
            JSONObject(resp).optJSONObject("response")
                ?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech")
        }.getOrNull()
        return ok().put("response", speech?.takeIf { it.isNotBlank() } ?: "Done.")
    }

    // Direct service call (precise; works on any entity, exposed to Assist or not).
    private fun haService(ctx: Context, domain: String, service: String,
                          entityId: String, dataJson: String): JSONObject {
        if (domain.isEmpty() || service.isEmpty()) return err("domain and service are required")
        val (base, token) = haCreds(ctx)
            ?: return err("Home Assistant URL/token not set in Portal HA Bridge settings")
        val body = if (dataJson.isNotEmpty())
            runCatching { JSONObject(dataJson) }.getOrDefault(JSONObject()) else JSONObject()
        if (entityId.isNotEmpty()) body.put("entity_id", entityId)
        httpPost("$base/api/services/$domain/$service", token, body.toString())
            ?: return err("Home Assistant service call failed")
        return ok().put("called", "$domain.$service")
    }

    // Discover entities so the model can find an entity_id by name, then control it
    // via haService — works for ALL entities, no HA Assist exposure needed. Filters
    // the full state list by domain and/or a name/id substring; caps the result so a
    // large install (hundreds of entities) doesn't bloat the conversation.
    private fun haList(ctx: Context, query: String, domain: String): JSONObject {
        val (base, token) = haCreds(ctx)
            ?: return err("Home Assistant URL/token not set in Portal HA Bridge settings")
        val body = httpGet("$base/api/states", token) ?: return err("Home Assistant request failed")
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return err("bad states response")
        val q = query.lowercase()
        val out = JSONArray()
        var matched = 0
        val cap = 40
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = e.optString("entity_id")
            if (domain.isNotEmpty() && !id.startsWith("$domain.")) continue
            val name = e.optJSONObject("attributes")?.optString("friendly_name") ?: ""
            if (q.isNotEmpty() && !id.lowercase().contains(q) && !name.lowercase().contains(q)) continue
            matched++
            if (out.length() < cap)
                out.put(JSONObject().put("entity_id", id).put("name", name).put("state", e.optString("state")))
        }
        val res = ok().put("count", matched).put("entities", out)
        if (matched > cap) res.put("note", "showing first $cap of $matched - narrow with query or domain")
        return res
    }

    private fun haCreds(ctx: Context): Pair<String, String>? {
        val p = Prefs(ctx)
        val base = p.haUrl.trim().trimEnd('/')
        val token = p.haToken
        return if (base.isEmpty() || token.isEmpty()) null else base to token
    }

    // POST a JSON body with the bearer token; return the response text, or null on
    // a network error / non-2xx. Synchronous (runs on a binder thread) with a short
    // timeout so a slow HA never blocks the conversation past the assistant's window.
    private fun httpPost(url: String, token: String, json: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(json.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            if (code in 200..299) text
            else { Log.w(TAG, "tools: HA HTTP $code: ${text.take(200)}"); null }
        } catch (e: Exception) {
            Log.w(TAG, "tools: HA HTTP error: ${e.message}"); null
        } finally {
            conn?.disconnect()
        }
    }

    // GET with the bearer token; returns the response body or null on error/non-2xx.
    private fun httpGet(url: String, token: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            if (code in 200..299) text
            else { Log.w(TAG, "tools: HA GET $code: ${text.take(200)}"); null }
        } catch (e: Exception) {
            Log.w(TAG, "tools: HA GET error: ${e.message}"); null
        } finally {
            conn?.disconnect()
        }
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)
    private fun result(json: JSONObject) =
        Bundle().apply { putString(EXTRA_RESULT_JSON, json.toString()) }

    // Unused CRUD surface — this provider only serves call().
    override fun query(uri: Uri, p: Array<String>?, s: String?, sa: Array<String>?, so: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, sa: Array<String>?): Int = 0
}
