package com.aeonos.portalha

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Vibrator
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * Implements Home Assistant's frontend "external app" bus (V1) so the HA frontend running in our
 * WebView treats us as a native wrapper — the same bridge the official companion app uses. The
 * frontend probes `window.externalApp`, then sends `{id,type:"config/get"}`; the capabilities we
 * return light up native features:
 *
 *  - hasSettingsScreen → HA shows an "App Configuration" item that sends `config_screen/show`;
 *    we open our own settings (a native way in, no WebView drawer needed).
 *  - hasAssist → the HA voice/mic button works even though our HA is HTTP (the browser mic is
 *    blocked on non-secure origins); on `assist/show` we drive the assistant natively.
 *
 * Wire: the frontend calls `window.externalApp.externalBus(json)` (this class); we reply by
 * evaluating `window.externalBus(json)` back into the page. See
 * https://developers.home-assistant.io/docs/frontend/external-bus/
 */
class HaExternalBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val prefs: Prefs,
) {
    companion object {
        private const val TAG = "PortalHA"
        // Re-hand the long-lived token this often (s). It never actually expires, but a
        // modest value lets the frontend re-validate periodically instead of churning.
        private const val TOKEN_TTL_S = 1800
    }

    // The HA frontend routes AUTH through the external app once window.externalApp exists: it calls
    // getExternalAuth and expects us to hand back an access token. We supply the user's long-lived
    // token (set for the Jarvis tools) — so the dashboard authenticates with no login and never logs
    // out. (DashboardActivity only injects this bridge when a token is configured.)
    @JavascriptInterface
    fun getExternalAuth(payload: String) {
        val cb = callbackName(payload, "externalAuthSetToken")
        val token = prefs.haToken
        webView.post {
            when {
                token.isBlank() -> authCallback(cb, false, null)
                !onHaPage() -> {
                    Log.w(TAG, "ha-bridge: refused token to non-HA page ${webView.url}")
                    authCallback(cb, false, null)
                }
                else -> authCallback(cb, true,
                    JSONObject().put("access_token", token).put("expires_in", TOKEN_TTL_S))
            }
        }
    }

    @JavascriptInterface
    fun revokeExternalAuth(payload: String) {
        val cb = callbackName(payload, "externalAuthRevokeToken")
        webView.post { authCallback(cb, true, null) }   // ack; the long-lived token lives in HA
    }

    private fun callbackName(payload: String, default: String): String =
        runCatching { JSONObject(payload).optString("callback").ifBlank { default } }.getOrDefault(default)

    // ★window.externalApp exists on EVERY page the WebView shows — a link the dashboard follows,
    // a page whoever controls the network swaps in, a URL changed under us — and the token it
    // hands out is a full Home Assistant credential. So only answer when the main frame is the
    // configured HA server. (Replies go to the main frame, so an iframe inside HA can't receive
    // one.) Main thread only: WebView.getUrl.
    private fun onHaPage(): Boolean {
        val page = webView.url?.let(Uri::parse) ?: return false
        val configured = prefs.haUrl.trim()
        val ha = Uri.parse(   // same normalisation as DashboardActivity.normalise
            if (configured.startsWith("http://") || configured.startsWith("https://")) configured
            else "http://$configured")
        if (ha.host.isNullOrEmpty() || !page.host.equals(ha.host, ignoreCase = true)) return false
        if (page.scheme == ha.scheme) return effectivePort(page) == effectivePort(ha)
        // A reverse proxy upgrading http://host to https://host is still the same server.
        return ha.scheme == "http" && page.scheme == "https" && ha.port == -1 && page.port == -1
    }

    private fun effectivePort(u: Uri): Int =
        if (u.port != -1) u.port else if (u.scheme == "https") 443 else 80

    private fun authCallback(cb: String, success: Boolean, data: JSONObject?) {
        if (!cb.matches(Regex("[A-Za-z0-9_]+"))) return   // callback name is always a plain identifier
        val js = "if (window.$cb) { window.$cb($success, ${data ?: "undefined"}); }"
        Log.i(TAG, "ha-bridge auth -> $cb(success=$success)")
        runCatching { webView.evaluateJavascript(js, null) }
    }

    // Called by the HA frontend on a WebView binder thread — parse, then hop to the main thread
    // for anything touching the WebView or UI.
    @JavascriptInterface
    fun externalBus(message: String) {
        val msg = runCatching { JSONObject(message) }.getOrNull() ?: return
        val type = msg.optString("type")
        val id = if (msg.has("id")) msg.optInt("id") else null
        webView.post { handle(type, id) }
    }

    private fun handle(type: String, id: Int?) {
        if (!onHaPage()) {
            Log.w(TAG, "ha-bridge: ignored '$type' from non-HA page ${webView.url}")
            return
        }
        when (type) {
            // The only command we advertise that expects a reply.
            "config/get" -> reply(id, JSONObject()
                .put("hasSettingsScreen", true)
                .put("hasAssist", true)
                .put("appVersion", BuildConfig.VERSION_NAME))

            // Fire-and-forget from the frontend — act, DON'T reply (a reply to a non-awaited
            // message triggers HA's "Received unknown msg ID" warning).
            "config_screen/show" -> runCatching { activity.startActivity(Intent(activity, MainActivity::class.java)) }
            "assist/show" -> BridgeService.requestAssist(activity)   // HA voice button → assistant (Jarvis)
            "haptic" -> vibrate()
            "theme-update", "connection-status", "sidebar/show" -> {}   // nothing to do

            else -> Log.i(TAG, "ha-bridge: unhandled type '$type'")
        }
    }

    private fun vibrate() {
        runCatching {
            val v = activity.getSystemService(Vibrator::class.java) ?: return
            if (!v.hasVibrator()) return
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(20)
        }
    }

    // A command reply the frontend matches to its request id; success with the given result.
    private fun reply(id: Int?, result: JSONObject) {
        id ?: return
        // window[CALLBACK_EXTERNAL_BUS] = (msg) => receiveMessage(msg) — the frontend expects an
        // OBJECT, not a JSON string (unlike the frontend→app direction, which is JSON.stringify'd).
        // Embed the JSON as a JS object literal; values are ours, so no injection risk.
        val obj = JSONObject().put("id", id).put("type", "result").put("success", true).put("result", result)
        val js = "if (window.externalBus) { window.externalBus($obj); }"
        runCatching { webView.evaluateJavascript(js, null) }
            .onFailure { Log.w(TAG, "ha-bridge reply failed: ${it.message}") }
    }
}
