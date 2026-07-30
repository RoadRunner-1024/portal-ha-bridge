package com.aeonos.portalha

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

// Dialog-style prompt launched by the daily auto update check when a newer
// release is on GitHub: shows the version jump and the release notes, with
// Update now / Skip this version / Later. "Skip" persists per version, so the
// same release never nags twice; "Later" re-asks on the next daily check.
class UpdatePromptActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VERSION = "version"
        const val EXTRA_NOTES = "notes"
        const val EXTRA_APK_URL = "apkUrl"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update_prompt)

        val prefs = Prefs(this)
        val version = intent.getStringExtra(EXTRA_VERSION) ?: run { finish(); return }
        val notes = intent.getStringExtra(EXTRA_NOTES).orEmpty().ifBlank { "(no release notes)" }
        val apkUrl = intent.getStringExtra(EXTRA_APK_URL) ?: run { finish(); return }

        findViewById<TextView>(R.id.tv_update_title).text = "Update available — v$version"
        findViewById<TextView>(R.id.tv_update_sub).text =
            "This Portal is on v${BuildConfig.VERSION_NAME}. Your settings are kept."
        findViewById<TextView>(R.id.tv_update_notes).text = notes

        val btnUpdate = findViewById<Button>(R.id.btn_update_now)
        btnUpdate.setOnClickListener { downloadAndInstall(btnUpdate, apkUrl) }

        findViewById<Button>(R.id.btn_update_skip).setOnClickListener {
            prefs.skippedUpdateVersion = version
            Toast.makeText(this, "v$version skipped — you won't be asked again for this version", Toast.LENGTH_SHORT).show()
            finish()
        }
        findViewById<Button>(R.id.btn_update_later).setOnClickListener { finish() }
    }

    // Same steps as MainActivity's manual flow: download to cache with progress on
    // the button, flip Gen-1 installer contrast, hand to PackageInstaller.
    private fun downloadAndInstall(btn: Button, apkUrl: String) {
        if (!Updater.canInstall(this)) {
            Toast.makeText(this, "Allow \"Install unknown apps\" for Portal HA Bridge first", Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")))
            }
            return
        }
        val dest = File(cacheDir, "update.apk")
        btn.isEnabled = false; btn.text = "Downloading… 0%"
        Thread({
            val r = runCatching {
                Updater.downloadApk(apkUrl, dest) { pct -> runOnUiThread { btn.text = "Downloading… $pct%" } }
            }
            runOnUiThread {
                r.onSuccess {
                    Updater.enableInstallerContrast(this)
                    runCatching { Updater.install(this, dest) }
                        .onSuccess { finish() }   // the system installer takes over from here
                        .onFailure {
                            Updater.restoreInstallerContrast(this)
                            btn.isEnabled = true; btn.text = "Update now"
                            Toast.makeText(this, "Install failed: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                }.onFailure {
                    btn.isEnabled = true; btn.text = "Update now"
                    Toast.makeText(this, "Download failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, "portal-ha-update-dl").also { it.isDaemon = true }.start()
    }
}
