package com.aeonos.portalha

import android.app.Activity
import android.os.Bundle

/**
 * The root of AvaKeepAlive's parked window: an invisible activity in its own freeform task, off
 * the bottom-right corner. Whenever it is on top of that task (just created, or the assistant's
 * activity above it went away) it hands over to AvaKeepAlive, which checks the task really is
 * freeform and then starts the assistant's activity inside this same task. If freeform isn't
 * active on this boot the task is removed again and the assistant is never started full screen.
 */
class AvaKeepAliveActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        // After the first layout pass, so isInMultiWindowMode and the window position are settled.
        window.decorView.post { if (!isFinishing) AvaKeepAlive.onHostResumed(this) }
    }
}
