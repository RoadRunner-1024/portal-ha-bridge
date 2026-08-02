package com.facebook.alohacommon.calling.voip.model

import android.os.Parcel
import android.os.Parcelable

/**
 * SHIM — deliberately NOT our package. Its fully-qualified name is identical to Meta's
 * real class so that when we hand it to the Portal's Alexa/Messenger client through the
 * START_CALL intent extra "call_parameters", the receiver resolves the name to ITS OWN
 * VoipCallStartParameters.CREATOR and unmarshals the bytes we write here. We only ever
 * WRITE (place an outbound call); createFromParcel is never used on our side.
 *
 * The body is a byte-accurate re-implementation of Meta's SafeParcel writer
 * (org.microg.safeparcel AutoSafeParcelable → JPb.A08 + AbstractC28980JPa header helpers),
 * reverse-engineered from the Portal Messenger APK. Wire format:
 *   - outer object header: writeInt(0x4F45 | 0xFFFF0000), then an int32 size back-patched
 *     to the body byte length.
 *   - fixed field (int/bool, 4 bytes): writeInt(fieldId | (4 << 16)), then writeInt(value).
 *   - variable field (String/StringList): writeInt(fieldId | 0xFFFF0000), an int32 size
 *     placeholder, the payload (writeString / writeStringList), then back-patch the size.
 *   - the reader dispatches by fieldId, so field order is free; null/unset fields are omitted.
 *
 * Field ids (from the decompiled @SafeParcelable.Field annotations):
 *   1 call_type(int)  2 contact_ids(List<String>)  3 direction(int)  8 self_id(String)
 *   12 trigger(String)  15 voip_environment(String)  16 voip_stack_type(String)  18 call_id(String)
 */
class VoipCallStartParameters(
    private val callType: Int,
    private val contactIds: List<String>,
    private val direction: Int,
    private val selfId: String,
    private val callId: String,
    private val voipEnvironment: String,
    private val voipStackType: String,
    private val trigger: String?,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        fun writeHeader(fieldId: Int, size: Int) {
            if (size >= 0xFFFF) {
                parcel.writeInt(fieldId or -0x10000)   // fieldId | 0xFFFF0000
                parcel.writeInt(size)
            } else {
                parcel.writeInt(fieldId or (size shl 16))
            }
        }
        fun beginVar(fieldId: Int): Int {
            writeHeader(fieldId, 0xFFFF)               // sentinel header + placeholder size
            return parcel.dataPosition()
        }
        fun endVar(start: Int) {
            val end = parcel.dataPosition()
            parcel.setDataPosition(start - 4)
            parcel.writeInt(end - start)
            parcel.setDataPosition(end)
        }

        // outer object header (magic 20293 = 0x4F45), size back-patched last
        writeHeader(20293, 0xFFFF)
        val objStart = parcel.dataPosition()

        // fixed-size fields
        writeHeader(1, 4); parcel.writeInt(callType)
        writeHeader(3, 4); parcel.writeInt(direction)

        // variable-length fields (omit nulls, exactly like Meta's writer)
        run { val s = beginVar(2); parcel.writeStringList(contactIds); endVar(s) }
        run { val s = beginVar(8); parcel.writeString(selfId); endVar(s) }
        trigger?.let { val s = beginVar(12); parcel.writeString(it); endVar(s) }
        run { val s = beginVar(15); parcel.writeString(voipEnvironment); endVar(s) }
        run { val s = beginVar(16); parcel.writeString(voipStackType); endVar(s) }
        run { val s = beginVar(18); parcel.writeString(callId); endVar(s) }

        endVar(objStart)
    }

    companion object {
        // Required by the Parcelable contract; never invoked on our side (the receiver uses
        // its own CREATOR). Present so the class is a valid Parcelable to write.
        @JvmField
        val CREATOR: Parcelable.Creator<VoipCallStartParameters> =
            object : Parcelable.Creator<VoipCallStartParameters> {
                override fun createFromParcel(source: Parcel): VoipCallStartParameters =
                    throw UnsupportedOperationException("write-only shim")
                override fun newArray(size: Int): Array<VoipCallStartParameters?> = arrayOfNulls(size)
            }
    }
}
