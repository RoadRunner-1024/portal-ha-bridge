package com.aeonos.portalha

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service used for two things:
 *  - performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) to blank the screen without device admin;
 *  - reporting which app is in the foreground (window-state changes carry the package name),
 *    which is how BridgeService knows we are sitting in the Meta Calls flow. It is the only
 *    permission-free way to see the foreground package on this build (UsageStats isn't granted).
 */
class ScreenAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: ScreenAccessibility? = null
        private const val TAG = "PortalHA"
    }

    override fun onServiceConnected() {
        instance = this
        // Event types come from the XML config (typeWindowStateChanged). Do NOT override
        // serviceInfo here — a runtime setServiceInfo after a package update could land before
        // the system finishes wiring event delivery and leave us bound but deaf to events
        // (observed: gestures still dispatched, but no window events arrived).
        Log.i(TAG, "ScreenAccessibility connected (eventTypes=${serviceInfo?.eventTypes}, caps=${serviceInfo?.capabilities})")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "ScreenAccessibility unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // IMEs, toasts and pop-ups also fire this, but reporting their package is harmless —
        // BridgeService only acts on a known short list of calling packages, ignoring the rest.
        val pkg = event.packageName?.toString() ?: return
        BridgeService.noteForegroundPackage(pkg)
    }

    override fun onInterrupt() = Unit

    fun sleepNow() {
        Log.i(TAG, "sleepNow: GLOBAL_ACTION_LOCK_SCREEN")
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    /**
     * Inject a single tap at a fraction of the screen (0..1 in each axis), used to dismiss
     * Meta's launcher photo-home when we open it for a call — one tap anywhere clears it.
     * Fractions rather than pixels so it is correct whatever the panel size/rotation.
     */
    fun tapFraction(fx: Float, fy: Float) {
        val dm = resources.displayMetrics
        val x = (dm.widthPixels * fx).coerceIn(1f, dm.widthPixels - 1f)
        val y = (dm.heightPixels * fy).coerceIn(1f, dm.heightPixels - 1f)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        Log.i(TAG, "tap: injected at ($x,$y) dispatched=$ok")
    }
}
