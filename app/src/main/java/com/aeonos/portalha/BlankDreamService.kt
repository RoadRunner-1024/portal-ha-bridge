package com.aeonos.portalha

import android.graphics.Color
import android.service.dreams.DreamService
import android.util.Log
import android.view.View

/**
 * A deliberately empty screensaver, registered so that OURS is the dream that runs.
 *
 * On this Portal build the dream is not the ordinary Android daydream: Meta's power policy uses
 * it as an ambient mode and starts it at the screen timeout whenever presence says somebody is
 * about. That is why none of the usual gates work — `screensaver_enabled`,
 * `screensaver_activate_on_sleep` and `screensaver_activate_on_dock` are all ignored (measured),
 * and the component cannot be disabled even from adb. It is also why the dream cannot be hidden:
 * its window is TYPE_DREAM, which sits above every TYPE_APPLICATION_OVERLAY we can create, so
 * our own cover was always going to lose that fight.
 *
 * Since the dream cannot be prevented, it is claimed instead. Whatever is registered here is what
 * the user sees during the ambient window — and black is what a wall panel should show, rather
 * than a frame of somebody else's photo screensaver on every wake.
 *
 * The photo frame deliberately does NOT live here. A dream takes the foreground, which evicts
 * Camera 0 on Android 10 and kills the RTSP stream — measured at 32 KB/4 s while dreaming versus
 * ~1.05 MB/4 s awake, the residue being the audio track that needs no camera. [ScreensaverOverlay]
 * shows photos as an overlay instead, so the dashboard stays foreground and the camera keeps
 * streaming behind them. Black here, photos there; each where it costs nothing.
 */
class BlankDreamService : DreamService() {

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Interactive so a tap can dismiss it; not bright, because there is nothing to light up.
        isInteractive = true
        isFullscreen = true
        isScreenBright = false

        val black = View(this)
        black.setBackgroundColor(Color.BLACK)
        black.setOnClickListener {
            Log.i(TAG, "dream: tapped — waking")
            finish()
        }
        setContentView(black)
        Log.i(TAG, "dream: ours attached (blank)")
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        Log.i(TAG, "dream: started")
    }

    override fun onDetachedFromWindow() {
        Log.i(TAG, "dream: ended")
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG = "PortalHA"
    }
}
