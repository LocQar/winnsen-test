package com.locqar.winnsentest.hardware

/**
 * Minimal Modbus RTU frame builder for testing communication
 * with Winnsen RTU lock controllers.
 *
 * The LD1.0 APK uses libQtModbus.so with functions like:
 *   readHR (Read Holding Registers, function 0x03)
 *   readDI (Read Discrete Inputs, function 0x02)
 *   readIR (Read Input Registers, function 0x04)
 *   writeRegister (Write Single Register, function 0x06)
 *   writeCoil (Write Single Coil, function 0x05)
 *   setSlave (set Modbus slave address)
 */
object ModbusRTU {

    /**
     * Build a Read Holding Registers (0x03) request frame.
     * Frame: [slave] [0x03] [startHi] [startLo] [countHi] [countLo] [CRClo] [CRChi]
     */
    fun buildReadHoldingRegisters(slave: Int, startReg: Int, count: Int): ByteArray {
        val frame = byteArrayOf(
            slave.toByte(),
            0x03,
            (startReg shr 8).toByte(),
            (startReg and 0xFF).toByte(),
            (count shr 8).toByte(),
            (count and 0xFF).toByte()
        )
        return appendCRC(frame)
    }

    /**
     * Build a Read Coils (0x01) request frame.
     * Used by RTULockerCtrl to check door states.
     */
    fun buildReadCoils(slave: Int, startCoil: Int, count: Int): ByteArray {
        val frame = byteArrayOf(
            slave.toByte(),
            0x01,
            (startCoil shr 8).toByte(),
            (startCoil and 0xFF).toByte(),
            (count shr 8).toByte(),
            (count and 0xFF).toByte()
        )
        return appendCRC(frame)
    }

    /**
     * Build a Read Discrete Inputs (0x02) request frame.
     */
    fun buildReadDiscreteInputs(slave: Int, startInput: Int, count: Int): ByteArray {
        val frame = byteArrayOf(
            slave.toByte(),
            0x02,
            (startInput shr 8).toByte(),
            (startInput and 0xFF).toByte(),
            (count shr 8).toByte(),
            (count and 0xFF).toByte()
        )
        return appendCRC(frame)
    }

    /**
     * Build a Write Single Coil (0x05) request frame.
     * Used by RTULockerCtrl to unlock a door.
     * value: true = ON (0xFF00), false = OFF (0x0000)
     */
    fun buildWriteSingleCoil(slave: Int, coilAddr: Int, value: Boolean): ByteArray {
        val valHi: Byte = if (value) 0xFF.toByte() else 0x00
        val frame = byteArrayOf(
            slave.toByte(),
            0x05,
            (coilAddr shr 8).toByte(),
            (coilAddr and 0xFF).toByte(),
            valHi,
            0x00
        )
        return appendCRC(frame)
    }

    /**
     * Build a Write Single Register (0x06) request frame.
     */
    fun buildWriteSingleRegister(slave: Int, regAddr: Int, value: Int): ByteArray {
        val frame = byteArrayOf(
            slave.toByte(),
            0x06,
            (regAddr shr 8).toByte(),
            (regAddr and 0xFF).toByte(),
            (value shr 8).toByte(),
            (value and 0xFF).toByte()
        )
        return appendCRC(frame)
    }

    /**
     * Calculate Modbus CRC-16 and append to frame.
     */
    private fun appendCRC(data: ByteArray): ByteArray {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) {
                    (crc shr 1) xor 0xA001
                } else {
                    crc shr 1
                }
            }
        }
        // CRC is appended low byte first, then high byte
        return data + byteArrayOf(
            (crc and 0xFF).toByte(),
            (crc shr 8).toByte()
        )
    }

    /**
     * Verify CRC of a received Modbus frame.
     */
    fun verifyCRC(frame: ByteArray): Boolean {
        if (frame.size < 4) return false
        val data = frame.copyOfRange(0, frame.size - 2)
        val expected = appendCRC(data)
        return expected[expected.size - 2] == frame[frame.size - 2] &&
               expected[expected.size - 1] == frame[frame.size - 1]
    }
}
