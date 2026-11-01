package com.anishudupan.bajarpulsarn160

import com.anishudupan.bajarpulsarn160.protocol.PulsarProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class PulsarProtocolUnitTest {

    @Test
    fun testGeneralPacketV1Build() {
        val state = PulsarProtocol.GeneralState(
            batteryBars = 4,
            callState = PulsarProtocol.CallState.INCOMING_CALL,
            signalBars = 4,
            missedCallCount = 1,
            smsCount = 2,
            callerName = "John",
            callProgressSeconds = 15,
            heartbeat = 1
        )

        val packet = PulsarProtocol.buildGeneralPacket(state, null)

        assertEquals(55, packet.size)
        // Byte 0: 0xC5
        assertEquals(0xC5.toByte(), packet[0])
        // Byte 1: 0x21 (1 | (4 << 3) = 33 = 0x21)
        assertEquals(0x21.toByte(), packet[1])
        // Byte 2: 0x04 (Signal bars 4)
        assertEquals(0x04.toByte(), packet[2])
        // Byte 13: 0x01
        assertEquals(0x01.toByte(), packet[13])
        // Byte 15: 0x02
        assertEquals(0x02.toByte(), packet[15])
        // Byte 18: 0x01
        assertEquals(0x01.toByte(), packet[18])
        // Byte 20: 0x04 (callerName length)
        assertEquals(0x04.toByte(), packet[20])
        // Bytes 21..24: "John"
        assertEquals('J'.code.toByte(), packet[21])
        assertEquals('o'.code.toByte(), packet[22])
        assertEquals('h'.code.toByte(), packet[23])
        assertEquals('n'.code.toByte(), packet[24])
    }

    @Test
    fun testMissedCallPacketV1Build() {
        val packet = PulsarProtocol.buildMissedCallPacket("Alice", "+919876543210")
        assertEquals(74, packet.size)
        assertEquals(0x01.toByte(), packet[0])
        assertEquals(5.toByte(), packet[1])
        assertEquals('A'.code.toByte(), packet[2])
        assertEquals(13.toByte(), packet[34])
        assertEquals('+'.code.toByte(), packet[35])
    }

    @Test
    fun testAlertsPacketBuild() {
        val packet = PulsarProtocol.buildAlertsPacket(2, "WhatsApp: Hello")
        assertEquals(40, packet.size)
        assertEquals(2.toByte(), packet[0])
        assertEquals(15.toByte(), packet[1])
        assertEquals('W'.code.toByte(), packet[2])
    }

    @Test
    fun testTbtPacketBuild() {
        val packet = PulsarProtocol.buildTbtPacket(turn = PulsarProtocol.PrimaryTurn.TURN_LEFT, distanceMeters = 250, isNavActive = true, streetName = "MG Road")
        assertEquals(48, packet.size)
        assertEquals(0x11.toByte(), packet[0]) // Meters unit 1 << 4 | activeBit 1
        assertEquals(73.toByte(), packet[1]) // Turn Left = 73 ASCII 'I'
        assertEquals(250.toByte(), packet[2])
        assertEquals(0x05.toByte(), packet[12]) // distUnit 1 | GPS Fixed 0x04
        assertEquals(7.toByte(), packet[14]) // "MG Road" length
        assertEquals('M'.code.toByte(), packet[15])
    }

    @Test
    fun testFavContactsPacketBuild() {
        val contacts = listOf(PulsarProtocol.ContactSlot("Jane", "9876543210"))
        val packet = PulsarProtocol.buildFavContactsPacket(contacts)
        assertEquals(151, packet.size)
        assertEquals(0xC0.toByte(), packet[0])
        assertEquals(0x10.toByte(), packet[1]) // (1 & 15) << 4 = 16 = 0x10
    }

    @Test
    fun testRecentCallsPacketBuild() {
        val calls = listOf(PulsarProtocol.ContactSlot("Jane", "9876543210", status = 3))
        val packet = PulsarProtocol.buildRecentCallsPacket(calls)
        assertEquals(151, packet.size)
        assertEquals(0x80.toByte(), packet[0])
        assertEquals(0x10.toByte(), packet[1])
        assertEquals(3.toByte(), packet[137]) // Status offset
    }
}
