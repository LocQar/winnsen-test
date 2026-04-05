package com.locqar.winnsentest.hardware

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Direct hardware UART serial port access.
 *
 * Winnsen control boards communicate with the tablet via a built-in
 * UART/TTL serial connection — NOT USB. The manufacturer's tablet
 * exposes this as a device file like /dev/ttyS*, /dev/ttyHS*, etc.
 *
 * This class opens the device file directly and reads/writes bytes.
 * Baud rate is set via termios (native code or stty command).
 */
class HardwareSerial {

    companion object {
        private const val TAG = "HardwareSerial"
        private const val READ_TIMEOUT_MS = 2000
        private const val WRITE_TIMEOUT_MS = 1000

        /**
         * Common serial device paths on Android tablets.
         * Winnsen tablets typically use one of these.
         */
        val SERIAL_PATHS = listOf(
            // Common UART paths
            "/dev/ttyS0", "/dev/ttyS1", "/dev/ttyS2", "/dev/ttyS3", "/dev/ttyS4",
            // Qualcomm High-Speed UART
            "/dev/ttyHS0", "/dev/ttyHS1", "/dev/ttyHS2", "/dev/ttyHS3",
            // Qualcomm MSM UART
            "/dev/ttyMSM0", "/dev/ttyMSM1",
            // Generic
            "/dev/ttyO0", "/dev/ttyO1", "/dev/ttyO2",
            // USB serial (if any)
            "/dev/ttyUSB0", "/dev/ttyUSB1",
            // ACM
            "/dev/ttyACM0", "/dev/ttyACM1",
            // Additional common paths
            "/dev/ttyGS0", "/dev/ttyGS1",
            "/dev/ttySAC0", "/dev/ttySAC1", "/dev/ttySAC2", "/dev/ttySAC3",
            "/dev/ttyMT0", "/dev/ttyMT1", "/dev/ttyMT2",
            "/dev/ttyHSL0", "/dev/ttyHSL1",
        )
    }

    data class SerialDeviceInfo(
        val path: String,
        val exists: Boolean,
        val readable: Boolean,
        val writable: Boolean
    )

    enum class ConnectionState { DISCONNECTED, CONNECTED, ERROR }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private val _connectedPort = MutableStateFlow<String?>(null)
    val connectedPort = _connectedPort.asStateFlow()

    private val _availablePorts = MutableStateFlow<List<SerialDeviceInfo>>(emptyList())
    val availablePorts = _availablePorts.asStateFlow()

    val isConnected: Boolean get() = _connectionState.value == ConnectionState.CONNECTED

    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private var nativeFd: Int = -1

    /**
     * Scan for all available serial device files.
     * Returns info about each: exists, readable, writable.
     */
    fun scanPorts(): List<SerialDeviceInfo> {
        val results = mutableListOf<SerialDeviceInfo>()

        // Also scan /dev/ for any tty* files we might have missed
        val devDir = File("/dev")
        val allTtyFiles = try {
            devDir.listFiles()?.filter { it.name.startsWith("tty") && it.name != "tty" }
                ?.map { it.absolutePath }?.sorted() ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot list /dev: ${e.message}")
            emptyList()
        }

        // Combine known paths with discovered ones
        val allPaths = (SERIAL_PATHS + allTtyFiles).distinct().sorted()

        for (path in allPaths) {
            val file = File(path)
            if (file.exists()) {
                val info = SerialDeviceInfo(
                    path = path,
                    exists = true,
                    readable = file.canRead(),
                    writable = file.canWrite()
                )
                results.add(info)
                Log.i(TAG, "Found: $path readable=${info.readable} writable=${info.writable}")
            }
        }

        _availablePorts.value = results
        Log.i(TAG, "Scan complete: ${results.size} serial devices found")
        return results
    }

    private val _baudRate = MutableStateFlow(9600)
    val baudRate = _baudRate.asStateFlow()

    private val _baudRateSet = MutableStateFlow(false)
    val baudRateSet = _baudRateSet.asStateFlow()

    private val _sttyTools = MutableStateFlow<List<String>>(emptyList())
    val sttyTools = _sttyTools.asStateFlow()

    /**
     * Connect to a specific serial port with the given baud rate.
     * Uses JNI native termios for proper baud rate configuration.
     */
    fun connect(path: String, baud: Int = 9600) {
        disconnect()
        _baudRate.value = baud

        val file = File(path)
        if (!file.exists()) {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "$path does not exist"
            return
        }

        try {
            // Method 1: JNI native open with termios (preferred)
            val fd = SerialPortJNI.nativeOpen(path, baud)

            if (fd >= 0) {
                // Create FileDescriptor from native fd using reflection
                val fileDescriptor = FileDescriptor()
                val fdField = FileDescriptor::class.java.getDeclaredField("descriptor")
                fdField.isAccessible = true
                fdField.setInt(fileDescriptor, fd)

                inputStream = FileInputStream(fileDescriptor)
                outputStream = FileOutputStream(fileDescriptor)
                nativeFd = fd
                _baudRateSet.value = true

                _connectionState.value = ConnectionState.CONNECTED
                _connectedPort.value = path
                _errorMessage.value = null
                Log.i(TAG, "Connected via JNI to $path @ $baud baud (fd=$fd)")
            } else {
                // Method 2: Fallback to Java file open + stty
                Log.w(TAG, "JNI open failed, falling back to Java FileStream")
                _sttyTools.value = NativeSerial.findAvailableTools()
                _baudRateSet.value = NativeSerial.configureBaudRate(path, baud)

                outputStream = FileOutputStream(file)
                inputStream = FileInputStream(file)

                _connectionState.value = ConnectionState.CONNECTED
                _connectedPort.value = path
                _errorMessage.value = null
                Log.i(TAG, "Connected via Java to $path (baud set: ${_baudRateSet.value})")
            }

        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "Cannot open $path: ${e.message}"
            Log.e(TAG, "Failed to open $path", e)
        }
    }

    /**
     * Send data and wait for response.
     */
    fun sendAndReceive(txData: ByteArray, expectedResponseLen: Int): ByteArray? {
        val os = outputStream ?: return null
        val is_ = inputStream ?: return null

        return try {
            // Send
            os.write(txData)
            os.flush()

            // Read with timeout
            val accumulated = mutableListOf<Byte>()
            val buffer = ByteArray(64)
            val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS

            while (accumulated.size < expectedResponseLen && System.currentTimeMillis() < deadline) {
                if (is_.available() > 0) {
                    val bytesRead = is_.read(buffer, 0, minOf(buffer.size, is_.available()))
                    if (bytesRead > 0) {
                        for (i in 0 until bytesRead) accumulated.add(buffer[i])
                    }
                } else {
                    Thread.sleep(10) // Small delay before checking again
                }
            }

            if (accumulated.size >= expectedResponseLen) {
                accumulated.toByteArray().copyOfRange(0, expectedResponseLen)
            } else {
                Log.w(TAG, "Timeout: got ${accumulated.size}/$expectedResponseLen bytes")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Serial I/O error", e)
            null
        }
    }

    fun disconnect() {
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        if (nativeFd >= 0) {
            try { SerialPortJNI.nativeClose(nativeFd) } catch (_: Exception) {}
            nativeFd = -1
        }
        inputStream = null
        outputStream = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedPort.value = null
    }
}
