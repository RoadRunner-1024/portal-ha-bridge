package com.aeonos.portalha

import android.content.Context
import android.content.Intent
import android.util.Log
import com.facebook.alohacommon.calling.voip.model.VoipCallStartParameters
import java.util.UUID

/**
 * Places an outbound Meta (Messenger) call on a Portal by firing the same START_CALL intent
 * the stock contacts app uses, with a hand-marshalled VoipCallStartParameters extra (see the
 * shim class). Values reverse-engineered from the Portal Messenger client:
 *   - action com.facebook.aloha.calling.START_CALL → Messenger RootActivity
 *   - extra key "call_parameters" (a VoipCallStartParameters SafeParcelable)
 *   - callType 1 = audio, 2 = video; direction 3 = outgoing
 *   - voip_environment "consumer"; voip_stack_type = the Messenger package name
 *
 * self_id (this Portal's logged-in Meta account fbid) and the callee fbid are the only runtime
 * inputs; both are stable Facebook user IDs. self_id is captured per Portal; callee ids are
 * captured/entered per contact (Meta's contacts provider is signature-gated, so they can't be
 * resolved by name at runtime).
 */
object PortalCaller {
    private const val TAG = "PortalHA"
    private const val MESSENGER_PKG = "com.facebook.aloha.app.messenger"
    private const val ROOT_ACTIVITY = "com.facebook.alohacommon.pef.RootActivity"
    private const val ACTION_START_CALL = "com.facebook.aloha.calling.START_CALL"

    const val CALL_TYPE_AUDIO = 1
    const val CALL_TYPE_VIDEO = 2
    private const val DIRECTION_OUTGOING = 3
    private const val VOIP_ENVIRONMENT = "consumer"

    /** Returns true if the intent was dispatched (not that the call connected). */
    fun placeCall(context: Context, selfId: String, calleeFbid: String, video: Boolean = false): Boolean {
        if (selfId.isBlank() || calleeFbid.isBlank()) {
            Log.w(TAG, "call: missing selfId/callee — cannot place call")
            return false
        }
        val params = VoipCallStartParameters(
            callType = if (video) CALL_TYPE_VIDEO else CALL_TYPE_AUDIO,
            contactIds = listOf(calleeFbid),
            direction = DIRECTION_OUTGOING,
            selfId = selfId,
            callId = UUID.randomUUID().toString(),
            voipEnvironment = VOIP_ENVIRONMENT,
            voipStackType = MESSENGER_PKG,
            trigger = "audio_call_button",
        )
        val intent = Intent(ACTION_START_CALL)
            .setClassName(MESSENGER_PKG, ROOT_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("call_parameters", params)
        return runCatching {
            context.startActivity(intent)
            Log.i(TAG, "call: placed ${if (video) "video" else "audio"} call self=$selfId -> $calleeFbid")
            true
        }.onFailure { Log.w(TAG, "call: placeCall failed: ${it.message}") }.getOrDefault(false)
    }
}
