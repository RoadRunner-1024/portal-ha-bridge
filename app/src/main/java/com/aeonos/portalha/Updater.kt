package com.aeonos.portalha

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Self-update from GitHub releases. Checks the latest release, downloads the
// signed APK (same keystore → installs in place over the current app), and hands
// it to PackageInstaller. The system shows its install confirmation — we can't
// skip that without device-owner privileges, which a sideloaded app doesn't have.
object Updater {
    private const val TAG = "PortalHA"
    private const val REPO = "RoadRunner-1024/portal-ha-bridge"
    private const val APK_ASSET = "portal-ha-bridge.apk"

    data class Release(val version: String, val apkUrl: String)

    // Fetch the latest release's version (tag, "v" stripped) + APK download URL.
    // Runs on a background thread; throws on network/parse error.
    fun fetchLatest(): Release {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection()
            as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "portal-ha-bridge")   // GitHub API requires a UA
        }
        try {
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").ifBlank { json.optString("name") }
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == APK_ASSET) { apkUrl = a.optString("browser_download_url"); break }
            }
            if (apkUrl.isBlank())
                apkUrl = "https://github.com/$REPO/releases/latest/download/$APK_ASSET"
            return Release(tag.removePrefix("v").removePrefix("V"), apkUrl)
        } finally { conn.disconnect() }
    }

    // Semantic-ish compare of dotted versions ("1.8.0" > "1.7.0").
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun downloadApk(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "portal-ha-bridge")
        }
        try {
            val total = conn.contentLength
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var readTotal = 0L; var n: Int; var lastPct = -1
                    while (input.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n); readTotal += n
                        if (total > 0) {
                            val pct = (readTotal * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
        } finally { conn.disconnect() }
    }

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    // Hand the downloaded APK to PackageInstaller; the system prompts to install.
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, UpdateInstallReceiver::class.java), flags)
            session.commit(pi.intentSender)
            Log.i(TAG, "update: install session committed")
        }
    }
}
