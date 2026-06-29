package com.aeonos.portalha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

// Receives PackageInstaller session callbacks. The important one is
// STATUS_PENDING_USER_ACTION: the system hands us an Intent we must launch to
// show the "Update?" confirmation. Other statuses are just logged.
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Log.w("PortalHA", "update: couldn't launch installer UI: ${it.message}") }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("PortalHA", "update: install succeeded")
            else -> Log.w("PortalHA", "update: install status=" +
                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) + " " +
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
