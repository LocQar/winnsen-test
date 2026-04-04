package com.locqar.winnsentest

import com.locqar.winnsentest.hardware.WinnsenCodec
import org.junit.Assert.*
import org.junit.Test

class WinnsenCodecTest {

    @Test
    fun `buildOpenCommand produces correct 6-byte frame`() {
        val cmd = WinnsenCodec.buildOpenCommand(station = 1, lock = 5)
        assertEquals(6, cmd.size)
        assertEquals(0x90.toByte(), cmd[0])
        assertEquals(0x06.toByte(), cmd[1])
        assertEquals(0x05.toByte(), cmd[2])
        assertEquals(0x01.toByte(), cmd[3])
        assertEquals(0x05.toByte(), cmd[4])
        assertEquals(0x03.toByte(), cmd[5])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildOpenCommand rejects station 0`() {
        WinnsenCodec.buildOpenCommand(station = 0, lock = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildOpenCommand rejects lock 17`() {
        WinnsenCodec.buildOpenCommand(station = 1, lock = 17)
    }

    @Test
    fun `buildPollCommand produces correct 7-byte frame`() {
        val cmd = WinnsenCodec.buildPollCommand(station = 2, mask = 0x0FFF)
        assertEquals(7, cmd.size)
        assertEquals(0x90.toByte(), cmd[0])
        assertEquals(0x07.toByte(), cmd[1])
        assertEquals(0x02.toByte(), cmd[2])
        assertEquals(0x02.toByte(), cmd[3])
        assertEquals(0xFF.toByte(), cmd[4])
        assertEquals(0x0F.toByte(), cmd[5])
        assertEquals(0x03.toByte(), cmd[6])
    }

    @Test
    fun `parseOpenResponse with successful open`() {
        val data = byteArrayOf(
            0x90.toByte(), 0x07, 0x85.toByte(),
            0x01, 0x05, 0x01, 0x03
        )
        val resp = WinnsenCodec.parseOpenResponse(data)!!
        assertEquals(1, resp.station)
        assertEquals(5, resp.lock)
        assertTrue(resp.success)
    }

    @Test
    fun `parseOpenResponse with failed open`() {
        val data = byteArrayOf(
            0x90.toByte(), 0x07, 0x85.toByte(),
            0x01, 0x03, 0x00, 0x03
        )
        val resp = WinnsenCodec.parseOpenResponse(data)!!
        assertFalse(resp.success)
    }

    @Test
    fun `parseOpenResponse returns null for invalid frame`() {
        assertNull(WinnsenCodec.parseOpenResponse(byteArrayOf(0x00)))
        assertNull(WinnsenCodec.parseOpenResponse(byteArrayOf()))
    }

    @Test
    fun `parsePollResponse all doors closed`() {
        val data = byteArrayOf(
            0x90.toByte(), 0x07, 0x82.toByte(),
            0x01, 0x00, 0x00, 0x03
        )
        val resp = WinnsenCodec.parsePollResponse(data)!!
        assertEquals(0, resp.stateBits)
        for (lock in 1..16) assertFalse(resp.doorStates[lock]!!)
    }

    @Test
    fun `parsePollResponse door 1 open`() {
        val data = byteArrayOf(
            0x90.toByte(), 0x07, 0x82.toByte(),
            0x01, 0x01, 0x00, 0x03
        )
        val resp = WinnsenCodec.parsePollResponse(data)!!
        assertTrue(resp.doorStates[1]!!)
        assertFalse(resp.doorStates[2]!!)
    }

    @Test
    fun `parsePollResponse doors 1 and 9 open`() {
        val data = byteArrayOf(
            0x90.toByte(), 0x07, 0x82.toByte(),
            0x01, 0x01, 0x01, 0x03
        )
        val resp = WinnsenCodec.parsePollResponse(data)!!
        assertTrue(resp.doorStates[1]!!)
        assertTrue(resp.doorStates[9]!!)
        assertFalse(resp.doorStates[2]!!)
    }

    @Test
    fun `parsePollResponse all doors open`() {
        val data = byteArrayOf(
            0x90.toByte(), 0x07, 0x82.toByte(),
            0x01, 0xFF.toByte(), 0xFF.toByte(), 0x03
        )
        val resp = WinnsenCodec.parsePollResponse(data)!!
        for (lock in 1..16) assertTrue(resp.doorStates[lock]!!)
    }

    @Test
    fun `round-trip open command`() {
        val cmd = WinnsenCodec.buildOpenCommand(station = 3, lock = 7)
        val response = byteArrayOf(
            0x90.toByte(), 0x07, 0x85.toByte(),
            cmd[3], cmd[4], 0x01, 0x03
        )
        val parsed = WinnsenCodec.parseOpenResponse(response)!!
        assertEquals(3, parsed.station)
        assertEquals(7, parsed.lock)
        assertTrue(parsed.success)
    }

    @Test
    fun `toHex formats correctly`() {
        assertEquals(
            "90 06 05 01 01 03",
            WinnsenCodec.toHex(byteArrayOf(0x90.toByte(), 0x06, 0x05, 0x01, 0x01, 0x03))
        )
    }
}
