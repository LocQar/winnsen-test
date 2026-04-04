package com.locqar.winnsentest.hardware

/**
 * RS485 protocol codec for Winnsen lock controller boards.
 *
 * Frame format (from Winnsen Serial Command Document V202104):
 *
 * Open Lock:
 *   TX: 90 06 05 <station> <lock> 03        (6 bytes)
 *   RX: 90 07 85 <station> <lock> <status> 03 (7 bytes)
 *       status: 01 = opened, 00 = failed
 *
 * Poll State (check locks):
 *   TX: 90 07 02 <station> <lowMask> <highMask> 03 (7 bytes)
 *   RX: 90 07 82 <station> <lowState> <highState> 03 (7 bytes)
 *       Each bit: 1 = open, 0 = closed. Bits 0-15 = locks 1-16.
 *
 * Serial: 9600 baud, 8 data bits, 1 stop bit, no parity.
 */
object WinnsenCodec {

    private const val FRAME_HEADER: Byte = 0x90.toByte()
    private const val FRAME_END: Byte = 0x03

    private const val FN_OPEN: Byte = 0x05
    private const val FN_POLL: Byte = 0x02

    private const val FN_OPEN_RESP: Byte = 0x85.toByte()
    private const val FN_POLL_RESP: Byte = 0x82.toByte()

    const val OPEN_RESPONSE_LEN = 7
    const val POLL_RESPONSE_LEN = 7

    fun buildOpenCommand(station: Int, lock: Int): ByteArray {
        require(station in 1..255) { "Station must be 1-255, got $station" }
        require(lock in 1..16) { "Lock must be 1-16, got $lock" }
        return byteArrayOf(
            FRAME_HEADER,
            0x06,
            FN_OPEN,
            station.toByte(),
            lock.toByte(),
            FRAME_END
        )
    }

    fun buildPollCommand(station: Int, mask: Int = 0xFFFF): ByteArray {
        require(station in 1..255) { "Station must be 1-255, got $station" }
        val lowMask = mask and 0xFF
        val highMask = (mask shr 8) and 0xFF
        return byteArrayOf(
            FRAME_HEADER,
            0x07,
            FN_POLL,
            station.toByte(),
            lowMask.toByte(),
            highMask.toByte(),
            FRAME_END
        )
    }

    data class OpenResponse(val station: Int, val lock: Int, val success: Boolean)

    data class PollResponse(
        val station: Int,
        val stateBits: Int,
        val doorStates: Map<Int, Boolean>
    )

    fun parseOpenResponse(data: ByteArray): OpenResponse? {
        if (data.size < OPEN_RESPONSE_LEN) return null
        if (data[0] != FRAME_HEADER) return null
        if (data[2] != FN_OPEN_RESP) return null
        if (data[6] != FRAME_END) return null
        return OpenResponse(
            station = data[3].toInt() and 0xFF,
            lock = data[4].toInt() and 0xFF,
            success = (data[5].toInt() and 0xFF) == 0x01
        )
    }

    fun parsePollResponse(data: ByteArray): PollResponse? {
        if (data.size < POLL_RESPONSE_LEN) return null
        if (data[0] != FRAME_HEADER) return null
        if (data[2] != FN_POLL_RESP) return null
        if (data[6] != FRAME_END) return null
        val station = data[3].toInt() and 0xFF
        val lowState = data[4].toInt() and 0xFF
        val highState = data[5].toInt() and 0xFF
        val stateBits = lowState or (highState shl 8)
        return PollResponse(station, stateBits, decodeDoorStates(stateBits))
    }

    fun decodeDoorStates(stateBits: Int): Map<Int, Boolean> =
        (1..16).associateWith { lock -> (stateBits shr (lock - 1)) and 1 == 1 }

    fun toHex(data: ByteArray): String =
        data.joinToString(" ") { "%02X".format(it) }
}
