package com.anishudupan.bajarpulsarn160.protocol

import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.UUID

object PulsarProtocol {

    val SERVICE_UUID: UUID = UUID.fromString("0010676e-6972-6565-6e69-676e4543544f")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val CHAR_TBT_UUID: UUID = UUID.fromString("0110676e-6972-6565-6e69-676e4543544f")
    val CHAR_GENERAL_UUID: UUID = UUID.fromString("0210676e-6972-6565-6e69-676e4543544f")
    val CHAR_MISSED_CALL_UUID: UUID = UUID.fromString("0310676e-6972-6565-6e69-676e4543544f")
    val CHAR_ALERTS_INFO_UUID: UUID = UUID.fromString("0410676e-6972-6565-6e69-676e4543544f")
    val CHAR_PLAYLIST_INFO_UUID: UUID = UUID.fromString("0510676e-6972-6565-6e69-676e4543544f")
    val CHAR_MEDIA_INFO_UUID: UUID = UUID.fromString("0610676e-6972-6565-6e69-676e4543544f")
    val CHAR_FAV_CONTACTS_UUID: UUID = UUID.fromString("0910676e-6972-6565-6e69-676e4543544f")
    val CHAR_CONTROL_UUID: UUID = UUID.fromString("0a10676e-6972-6565-6e69-676e4543544f")
    val CHAR_RECENT_CALLS_UUID: UUID = UUID.fromString("0b10676e-6972-6565-6e69-676e4543544f")

    enum class CallState(val code: Int) {
        NO_CALL(0),
        INCOMING_CALL(1),
        OUTGOING_CALL(2),
        ACTIVE_CALL(3),
        END_CALL(4)
    }

    enum class PrimaryTurn(val code: Int) {
        STRAIGHT(71),
        TURN_LEFT(73),
        TURN_RIGHT(74),
        SLIGHT_LEFT(67),
        SLIGHT_RIGHT(68),
        U_TURN(79),
        DESTINATION_REACHED(72)
    }

    data class ContactSlot(
        val name: String,
        val number: String,
        val status: Int = 1 // 1=Incoming, 2=Outgoing, 3=Missed
    )

    data class GeneralState(
        var batteryBars: Int = 4, // 0..4 (Manual/Forced)
        var callState: CallState = CallState.NO_CALL,
        var signalBars: Int = 4, // 0..4 (Forced/Manual for N160 LCD)
        var missedCallCount: Int = 0,
        var smsCount: Int = 0,
        var callerName: String = "",
        var callerNumber: String = "",
        var callProgressSeconds: Int = 0,
        var heartbeat: Int = 0
    )

    data class ControlEvent(
        val volumeToSet: Int,
        val dialSource: Int,
        val callAcceptToggle: Byte,
        val callRejectToggle: Byte,
        val callRejectWithSmsToggle: Byte,
        val resumeSongToggle: Byte,
        val pauseSongToggle: Byte,
        val skipToNextToggle: Byte,
        val skipToPrevToggle: Byte,
        val stopSongToggle: Byte,
        val missedCallGetToggle: Byte,
        val alertGetToggle: Byte,
        val launchMediaPlayerToggle: Byte,
        val selectPlaylistSongReqToggle: Byte,
        val selectedPlaylistSong: Byte,
        val dialIndex: Byte,
        val dialTxn: Byte,
        val rawBytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ControlEvent
            return rawBytes.contentEquals(other.rawBytes)
        }

        override fun hashCode(): Int {
            return rawBytes.contentHashCode()
        }
    }

    private var heartbeatCounter = 0
    private var stickyCallerOverwriteLen = 0

    private fun sanitize(str: String?): String {
        return str?.replace("[\\p{So}\\p{Cn}^]".toRegex(), "") ?: ""
    }

    /**
     * Protocol V1 General Status Packet (55 Bytes)
     */
    fun buildGeneralPacket(state: GeneralState, lastControl: ByteArray?): ByteArray {
        heartbeatCounter++
        val buf = ByteArray(55)

        // Byte 0: Fixed Volume 5 + Prefix 0xC0
        buf[0] = 0xC5.toByte()

        // Byte 1: Call State & Battery Bars (batteryBars << 3)
        val batBars = state.batteryBars.coerceIn(0, 4)
        buf[1] = ((state.callState.code and 0x07) or ((batBars and 0x07) shl 3)).toByte()

        // Byte 2: Signal Strength (signalBars & 7, clamped 0..4 for N160)
        val signal = state.signalBars.coerceIn(0, 4)
        buf[2] = (signal and 0x07).toByte()

        // Byte 3: Active Call Flag
        buf[3] = if (state.callState == CallState.ACTIVE_CALL) 0x01.toByte() else 0x00.toByte()

        // Byte 4: Non-Active Call Flag
        buf[4] = if (state.callState == CallState.ACTIVE_CALL) 0x00.toByte() else 0x01.toByte()

        // ACKs
        if (lastControl != null && lastControl.size >= 20) {
            buf[5] = lastControl[2]  // callRejectAck
            buf[6] = lastControl[3]  // callRejectWithSmsAck
            buf[13] = lastControl[11] // missedCallGetAck
            buf[15] = lastControl[12] // alertsGetAck
        }

        // Byte 13: Missed Call Count
        buf[13] = (state.missedCallCount and 0xFF).toByte()

        // Byte 15: SMS Count
        buf[15] = (state.smsCount and 0xFF).toByte()

        // Caller Info (V1 offset: 18 valid, 20 len, 21..50 string)
        val isCallActive = state.callState == CallState.ACTIVE_CALL ||
                state.callState == CallState.INCOMING_CALL ||
                state.callState == CallState.OUTGOING_CALL

        if (isCallActive) {
            var strSanitize = sanitize(state.callerName)
            if (strSanitize.isEmpty()) {
                strSanitize = sanitize(state.callerNumber)
            }

            if (strSanitize.isEmpty()) {
                buf[18] = 0x00.toByte()
                buf[20] = 0x00.toByte()
                Arrays.fill(buf, 21, 51, 0x00.toByte())
            } else {
                buf[18] = 0x01.toByte()
                val bytes = strSanitize.toByteArray(StandardCharsets.UTF_8)
                val iMin = bytes.size.coerceAtMost(30)
                Arrays.fill(buf, 21, 51, 0x00.toByte())
                System.arraycopy(bytes, 0, buf, 21, iMin)

                var iMax = Math.max(iMin, stickyCallerOverwriteLen)
                if (iMax > 30) iMax = 30
                buf[20] = iMax.toByte()
                stickyCallerOverwriteLen = iMin
            }
        } else {
            state.callProgressSeconds = 0
            buf[18] = 0x00.toByte()
            buf[20] = 0x00.toByte()
        }

        // Byte 53: Heartbeat
        buf[53] = (heartbeatCounter and 0xFF).toByte()

        return buf
    }

    /**
     * Protocol V1 Missed Call Packet (74 Bytes)
     */
    fun buildMissedCallPacket(
        callerName: String,
        phoneNumber: String
    ): ByteArray {
        val buf = ByteArray(74)
        buf[0] = 0x01.toByte()

        val nameBytes = sanitize(callerName).toByteArray(StandardCharsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(32)
        buf[1] = nameLen.toByte()
        if (nameLen > 0) {
            System.arraycopy(nameBytes, 0, buf, 2, nameLen)
        }

        val numBytes = sanitize(phoneNumber).toByteArray(StandardCharsets.UTF_8)
        val numLen = numBytes.size.coerceAtMost(18)
        buf[34] = numLen.toByte()
        if (numLen > 0) {
            System.arraycopy(numBytes, 0, buf, 35, numLen)
        }

        return buf
    }

    /**
     * Protocol V1 Alerts Packet (40 Bytes)
     */
    fun buildAlertsPacket(
        alertType: Int, // 1=SMS, 2=WhatsApp, 3=Social
        message: String
    ): ByteArray {
        val buf = ByteArray(40)
        buf[0] = (alertType and 0xFF).toByte()
        val bytes = sanitize(message).toByteArray(StandardCharsets.UTF_8)
        val iMin = bytes.size.coerceAtMost(32)
        buf[1] = iMin.toByte()
        if (iMin > 0) {
            System.arraycopy(bytes, 0, buf, 2, iMin)
        }
        return buf
    }

    /**
     * Protocol V1 Turn-By-Turn Navigation Packet (48 Bytes) matching TbtNavFrame.java
     */
    fun buildTbtPacket(
        turn: PrimaryTurn = PrimaryTurn.STRAIGHT,
        distanceMeters: Int = 0,
        isNavActive: Boolean = true,
        streetName: String = ""
    ): ByteArray {
        val buf = ByteArray(48)

        val d2 = if (distanceMeters < 1000) distanceMeters.toDouble() else distanceMeters / 1000.0
        val distUnitByte: Byte = if (distanceMeters < 1000) 1 else 0 // 1=meters, 0=km
        val iVal = d2.toInt()
        val decVal = ((d2 - iVal) * 100.0).toInt()

        val activeBit = if (isNavActive) 1 else 0
        buf[0] = ((distUnitByte.toInt() shl 4) or activeBit).toByte()
        buf[1] = turn.code.toByte() // Official PrimaryTurns code (71=Straight, 73=Left, 74=Right, etc.)

        // Distance bytes uint16_le
        buf[2] = (iVal and 0xFF).toByte()
        buf[3] = ((iVal shr 8) and 0xFF).toByte()
        buf[4] = (decVal and 0xFF).toByte()
        buf[5] = ((decVal shr 8) and 0xFF).toByte()

        // Byte 12: GPS Status Byte (0x04 = GPS Fixed)
        buf[12] = (distUnitByte.toInt() or 0x04).toByte()

        val textBytes = sanitize(streetName).toByteArray(StandardCharsets.US_ASCII)
        val textLen = textBytes.size.coerceAtMost(31)
        if (textLen > 0) {
            buf[14] = textLen.toByte()
            System.arraycopy(textBytes, 0, buf, 15, textLen)
        }

        var sum = 0
        for (i in 0 until 47) {
            sum = (sum + (buf[i].toInt() and 0xFF)) and 0xFF
        }
        buf[47] = sum.toByte()

        return buf
    }

    /**
     * Favorite Contacts Packet (151 Bytes)
     */
    fun buildFavContactsPacket(contacts: List<ContactSlot> = emptyList()): ByteArray {
        val buf = ByteArray(151)
        buf[0] = 0xC0.toByte() // Header -64
        val count = contacts.size.coerceAtMost(5)
        buf[1] = ((count and 0x0F) shl 4).toByte()

        var offset = 2
        for (i in 0 until 5) {
            if (i < count) {
                val contact = contacts[i]
                val nameBytes = sanitize(contact.name).toByteArray(StandardCharsets.US_ASCII)
                val numBytes = sanitize(contact.number).toByteArray(StandardCharsets.US_ASCII)
                val nameLen = nameBytes.size.coerceAtMost(10)
                val numLen = numBytes.size.coerceAtMost(15)

                buf[offset++] = nameLen.toByte()
                for (j in 0 until 10) {
                    buf[offset++] = if (j < nameLen) nameBytes[j] else 0x00.toByte()
                }
                buf[offset++] = numLen.toByte()
                for (j in 0 until 15) {
                    buf[offset++] = if (j < numLen) numBytes[j] else 0x00.toByte()
                }
                offset += 2
            } else {
                buf[offset++] = 0x00.toByte()
                for (j in 0 until 10) buf[offset++] = 0x00.toByte()
                buf[offset++] = 0x00.toByte()
                for (j in 0 until 15) buf[offset++] = 0x00.toByte()
                offset += 2
            }
        }

        var sum = 0
        for (i in 0 until 150) {
            sum = (sum + (buf[i].toInt() and 0xFF)) and 0xFF
        }
        buf[150] = sum.toByte()
        return buf
    }

    /**
     * Recent Calls Packet (151 Bytes)
     */
    fun buildRecentCallsPacket(calls: List<ContactSlot> = emptyList()): ByteArray {
        val buf = ByteArray(151)
        buf[0] = 0x80.toByte() // Header -128
        val count = calls.size.coerceAtMost(5)
        buf[1] = ((count and 0x0F) shl 4).toByte()

        var offset = 2
        for (i in 0 until 5) {
            if (i < count) {
                val call = calls[i]
                val nameBytes = sanitize(call.name).toByteArray(StandardCharsets.US_ASCII)
                val numBytes = sanitize(call.number).toByteArray(StandardCharsets.US_ASCII)
                val nameLen = nameBytes.size.coerceAtMost(10)
                val numLen = numBytes.size.coerceAtMost(15)

                buf[offset++] = nameLen.toByte()
                for (j in 0 until 10) {
                    buf[offset++] = if (j < nameLen) nameBytes[j] else 0x00.toByte()
                }
                buf[offset++] = numLen.toByte()
                for (j in 0 until 15) {
                    buf[offset++] = if (j < numLen) numBytes[j] else 0x00.toByte()
                }
            } else {
                buf[offset++] = 0x00.toByte()
                for (j in 0 until 10) buf[offset++] = 0x00.toByte()
                buf[offset++] = 0x00.toByte()
                for (j in 0 until 15) buf[offset++] = 0x00.toByte()
            }
        }

        // Set status offset (bytes 137..141)
        for (i in 0 until 5) {
            buf[137 + i] = if (i < count) calls[i].status.toByte() else 0x00.toByte()
        }

        var sum = 0
        for (i in 0 until 150) {
            sum = (sum + (buf[i].toInt() and 0xFF)) and 0xFF
        }
        buf[150] = sum.toByte()
        return buf
    }

    fun parseControlPacket(data: ByteArray): ControlEvent? {
        if (data.size < 20) return null
        val vol = data[0].toInt() and 0x0F
        val dialSrc = (data[0].toInt() shr 6) and 0x03
        return ControlEvent(
            volumeToSet = vol,
            dialSource = dialSrc,
            callAcceptToggle = data[1],
            callRejectToggle = data[2],
            callRejectWithSmsToggle = data[3],
            resumeSongToggle = data[6],
            pauseSongToggle = data[7],
            skipToNextToggle = data[8],
            skipToPrevToggle = data[9],
            stopSongToggle = data[10],
            missedCallGetToggle = data[11],
            alertGetToggle = data[12],
            launchMediaPlayerToggle = data[13],
            selectPlaylistSongReqToggle = data[14],
            selectedPlaylistSong = data[15],
            dialIndex = data[16],
            dialTxn = data[17],
            rawBytes = data.copyOf()
        )
    }
}
