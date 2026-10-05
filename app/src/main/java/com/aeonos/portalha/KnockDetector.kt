package com.aeonos.portalha

/**
 * Double knock on the Portal's frame, from the accelerometer's gravity-free force.
 *
 * Plain logic, no Android types: SensorBridge feeds it samples and runs the one delayed check, so
 * the timing rules can be exercised off-device with synthetic traces.
 *
 *  - A knock is a spike above the threshold (the Tap sensitivity). One knock rings for a while,
 *    so the next only counts once the frame has settled below [rearmRatio] of the threshold for
 *    [rearmQuietMs].
 *  - Two knocks [minGapMs]..[maxGapMs] apart are a double knock. Closer than [minGapMs] is still
 *    the first one ringing; further apart than [maxGapMs] and the second starts a new pair.
 *  - After a double knock, [cooldownMs] of nothing, so a triple (or a nervous fourth) knock
 *    fires once, not twice.
 *  - Tapping the screen shakes the frame just like a knock, so a double knock is thrown away if
 *    any of our windows was touched from [touchGuardMs] before its first knock onwards. A touch
 *    can land just AFTER the spike it caused, so that is only known [touchGuardMs] after the
 *    second knock: hence [onSample] hands back a [Candidate] and the caller [confirm]s it then.
 */
class KnockDetector(
    private val minGapMs: Long = 150L,
    private val maxGapMs: Long = 800L,
    private val cooldownMs: Long = 5_000L,
    val touchGuardMs: Long = 400L,
    private val rearmRatio: Float = 0.5f,
    private val rearmQuietMs: Long = 40L,
) {
    data class Candidate(val firstMs: Long, val secondMs: Long) {
        val gapMs: Long get() = secondMs - firstMs
    }

    private var armed = true
    private var quietSinceMs = -1L
    private var firstMs = -1L          // first knock of a possible pair; -1 = none
    private var cooldownUntilMs = -1L

    /**
     * One sample: [tMs] on a monotonic millisecond clock, [force] the gravity-free magnitude,
     * [threshold] the knock threshold. Returns a double knock to [confirm] once [touchGuardMs]
     * has passed, or null.
     */
    fun onSample(tMs: Long, force: Float, threshold: Float): Candidate? {
        if (threshold <= 0f) return null
        if (force < threshold * rearmRatio) {
            if (quietSinceMs < 0) quietSinceMs = tMs
            if (!armed && tMs - quietSinceMs >= rearmQuietMs) armed = true
            return null
        }
        quietSinceMs = -1L
        if (!armed || force <= threshold) return null
        armed = false
        return onKnock(tMs)
    }

    private fun onKnock(tMs: Long): Candidate? {
        if (tMs < cooldownUntilMs) { firstMs = -1L; return null }
        val first = firstMs
        if (first >= 0) {
            val gap = tMs - first
            if (gap < minGapMs) return null
            if (gap <= maxGapMs) {
                firstMs = -1L
                cooldownUntilMs = tMs + cooldownMs
                return Candidate(first, tMs)
            }
        }
        firstMs = tMs
        return null
    }

    /**
     * Decide [c], at least [touchGuardMs] after its second knock. [lastTouchMs] is the latest
     * touch on any of our windows on the same clock (<= 0: never). A rejected pair gives back its
     * cooldown, so a real double knock right after a screen tap isn't lost as well.
     */
    fun confirm(c: Candidate, lastTouchMs: Long): Boolean {
        val touched = lastTouchMs > 0 && lastTouchMs >= c.firstMs - touchGuardMs
        if (touched && cooldownUntilMs == c.secondMs + cooldownMs) cooldownUntilMs = -1L
        return !touched
    }
}
