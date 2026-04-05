package com.locqar.winnsentest

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.locqar.winnsentest.hardware.HardwareSerial
import com.locqar.winnsentest.hardware.ModbusRTU
import com.locqar.winnsentest.hardware.WinnsenCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

class TestViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "TestVM"
    }

    // Use hardware UART serial (not USB)
    val hwSerial = HardwareSerial()

    // Config — LD1.0 APK uses /dev/ttyS1 at 9600,8,N,1
    val station = MutableStateFlow(1)
    val lockNumber = MutableStateFlow(1)
    val baudRate = MutableStateFlow(9600)
    val baudRates = listOf(9600, 19200, 38400, 57600, 115200, 4800, 2400)

    // Protocol mode
    enum class ProtocolMode { WINNSEN, MODBUS_RTU }
    val protocolMode = MutableStateFlow(ProtocolMode.WINNSEN)

    // Door states
    private val _doorStates = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val doorStates = _doorStates.asStateFlow()

    // Log
    data class LogEntry(val timestamp: String, val direction: String, val hex: String, val note: String)
    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log = _log.asStateFlow()

    // Auto-poll
    private val _autoPolling = MutableStateFlow(false)
    val autoPolling = _autoPolling.asStateFlow()
    private var pollJob: Job? = null

    // Last result
    private val _lastResult = MutableStateFlow("")
    val lastResult = _lastResult.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val isConnected: Boolean get() = hwSerial.isConnected

    /**
     * Scan for available hardware serial ports.
     */
    fun scanPorts() {
        addLog("SYS", "--", "Scanning for serial ports...")
        val ports = hwSerial.scanPorts()
        if (ports.isEmpty()) {
            addLog("SYS", "--", "No serial ports found in /dev/")
        } else {
            addLog("SYS", "--", "Found ${ports.size} serial ports:")
            for (p in ports) {
                val access = buildString {
                    if (p.readable) append("R")
                    if (p.writable) append("W")
                    if (!p.readable && !p.writable) append("NO ACCESS")
                }
                addLog("SYS", "--", "  ${p.path} [$access]")
            }
        }
    }

    /**
     * Connect to a specific serial port path.
     */
    fun connectToPort(path: String) {
        stopAutoPolling()
        hwSerial.disconnect()
        val baud = baudRate.value
        addLog("SYS", "--", "Connecting to $path @ ${baud} baud...")
        hwSerial.connect(path, baud)

        val state = hwSerial.connectionState.value
        if (state == HardwareSerial.ConnectionState.CONNECTED) {
            addLog("OK", "--", "Connected: ${hwSerial.connectMethod.value}")
            // Show all diagnostics
            for (diag in hwSerial.diagnostics.value) {
                addLog("DIAG", "--", diag)
            }
        } else {
            addLog("ERR", "--", "Failed: ${hwSerial.errorMessage.value}")
            for (diag in hwSerial.diagnostics.value) {
                addLog("DIAG", "--", diag)
            }
        }
    }

    /**
     * Send raw hex bytes and show response.
     * Input format: "90 06 05 01 01 03"
     */
    fun sendRawHex(hexString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = hexString.trim().split("\\s+".toRegex())
                    .map { it.toInt(16).toByte() }
                    .toByteArray()

                addLog("TX", WinnsenCodec.toHex(bytes), "RAW ${bytes.size} bytes")

                val os = hwSerial.let {
                    if (!it.isConnected) {
                        addLog("ERR", "--", "Not connected")
                        return@launch
                    }
                }

                val rxData = hwSerial.sendAndReceive(bytes, 32) // read up to 32 bytes
                if (rxData != null) {
                    addLog("RX", WinnsenCodec.toHex(rxData), "Got ${rxData.size} bytes!")
                } else {
                    addLog("RX", "--", "TIMEOUT")
                }
            } catch (e: Exception) {
                addLog("ERR", "--", "Bad hex input: ${e.message}")
            }
        }
    }

    fun changeBaudRate(baud: Int) {
        baudRate.value = baud
        val currentPort = hwSerial.connectedPort.value
        if (currentPort != null) {
            // Reconnect with new baud rate
            connectToPort(currentPort)
        }
    }

    /**
     * Quick connect: use the known LD1.0 APK config — /dev/ttyS1 @ 9600 baud.
     * Then immediately try both Winnsen protocol and Modbus RTU.
     */
    fun quickConnect() {
        viewModelScope.launch(Dispatchers.IO) {
            val path = "/dev/ttyS1"
            val baud = 9600
            baudRate.value = baud

            addLog("SYS", "--", "Quick connect: $path @ $baud (from LD1.0 APK config)")
            hwSerial.disconnect()
            hwSerial.connect(path, baud)

            if (!hwSerial.isConnected) {
                addLog("ERR", "--", "Failed to open $path: ${hwSerial.errorMessage.value}")
                return@launch
            }

            addLog("OK", "--", "Connected: ${hwSerial.connectMethod.value}")
            // Show all diagnostics
            for (diag in hwSerial.diagnostics.value) {
                addLog("DIAG", "--", diag)
            }

            // Test 1: Winnsen protocol poll
            addLog("SYS", "--", "--- Testing Winnsen protocol ---")
            for (st in listOf(1, 0)) {
                val txFrame = WinnsenCodec.buildPollCommand(st)
                addLog("TX", WinnsenCodec.toHex(txFrame), "Winnsen Poll station=$st")
                val rxData = hwSerial.sendAndReceive(txFrame, WinnsenCodec.POLL_RESPONSE_LEN)
                if (rxData != null) {
                    addLog("RX", WinnsenCodec.toHex(rxData), "Got response!")
                    val resp = WinnsenCodec.parsePollResponse(rxData)
                    if (resp != null) {
                        addLog("OK", "--", "Winnsen protocol WORKS! station=${resp.station}")
                        station.value = resp.station
                        _doorStates.value = resp.doorStates
                        _lastResult.value = "WINNSEN OK: $path"
                        protocolMode.value = ProtocolMode.WINNSEN
                        return@launch
                    } else {
                        addLog("SYS", "--", "Got data but invalid Winnsen response")
                    }
                } else {
                    addLog("RX", "--", "TIMEOUT")
                }
            }

            // Test 2: Modbus RTU — read holding register 0 from slave 1
            addLog("SYS", "--", "--- Testing Modbus RTU ---")
            for (slaveAddr in listOf(1, 0, 2)) {
                val modbusFrame = ModbusRTU.buildReadHoldingRegisters(slaveAddr, 0, 1)
                addLog("TX", WinnsenCodec.toHex(modbusFrame), "Modbus RTU ReadHR slave=$slaveAddr reg=0")
                val rxData = hwSerial.sendAndReceive(modbusFrame, 7) // min response: addr+func+count+2data+2crc
                if (rxData != null) {
                    addLog("RX", WinnsenCodec.toHex(rxData), "Got Modbus response!")
                    _lastResult.value = "MODBUS OK: slave=$slaveAddr"
                    protocolMode.value = ProtocolMode.MODBUS_RTU
                    station.value = slaveAddr
                    return@launch
                } else {
                    addLog("RX", "--", "TIMEOUT")
                }
            }

            // Test 3: Modbus RTU — read coils (used by RTULockerCtrl for door state)
            addLog("SYS", "--", "--- Testing Modbus RTU Read Coils ---")
            for (slaveAddr in listOf(1, 0, 2)) {
                val modbusFrame = ModbusRTU.buildReadCoils(slaveAddr, 0, 16)
                addLog("TX", WinnsenCodec.toHex(modbusFrame), "Modbus ReadCoils slave=$slaveAddr")
                val rxData = hwSerial.sendAndReceive(modbusFrame, 6) // addr+func+count+2data+2crc... actually min 7
                if (rxData != null) {
                    addLog("RX", WinnsenCodec.toHex(rxData), "Got Modbus coil response!")
                    _lastResult.value = "MODBUS COILS OK: slave=$slaveAddr"
                    protocolMode.value = ProtocolMode.MODBUS_RTU
                    station.value = slaveAddr
                    return@launch
                } else {
                    addLog("RX", "--", "TIMEOUT")
                }
            }

            // Test 4: Raw read — see if anything is on the wire
            addLog("SYS", "--", "--- Raw read test ---")
            val rawBuf = ByteArray(64)
            val n = hwSerial.rawRead(rawBuf, 2000)
            if (n > 0) {
                addLog("RX", WinnsenCodec.toHex(rawBuf.copyOf(n)), "RAW: $n bytes (unsolicited data)")
                _lastResult.value = "RAW DATA: $n bytes"
            } else {
                addLog("SYS", "--", "No data on wire")
                _lastResult.value = "NO RESPONSE on ttyS1"
            }
        }
    }

    // Auto-detect state
    private val _autoDetecting = MutableStateFlow(false)
    val autoDetecting = _autoDetecting.asStateFlow()
    private var autoDetectJob: Job? = null

    /**
     * Auto-detect: try all ports at all baud rates with stations 0 and 1,
     * send a poll command, and see which one responds.
     * Also does a raw read test to detect any data on each port.
     */
    fun autoDetect() {
        if (_autoDetecting.value) {
            // Cancel if already running
            autoDetectJob?.cancel()
            _autoDetecting.value = false
            addLog("SYS", "--", "Auto-detect cancelled")
            return
        }

        autoDetectJob = viewModelScope.launch(Dispatchers.IO) {
            _autoDetecting.value = true
            addLog("SYS", "--", "Auto-detecting locker port...")
            addLog("SYS", "--", "Will try stations 0 and 1 on each port/baud combo")
            val ports = hwSerial.scanPorts().filter { it.readable && it.writable }

            if (ports.isEmpty()) {
                addLog("ERR", "--", "No accessible ports found")
                _autoDetecting.value = false
                return@launch
            }

            val stationsToTry = listOf(0, 1)

            for (baud in baudRates) {
                for (port in ports) {
                    if (!isActive) { _autoDetecting.value = false; return@launch }

                    addLog("SYS", "--", "Trying ${port.path} @ $baud...")
                    hwSerial.disconnect()
                    hwSerial.connect(port.path, baud)

                    if (!hwSerial.isConnected) continue

                    // Log JNI status
                    val jniOk = hwSerial.baudRateSet.value
                    if (!jniOk) {
                        addLog("WARN", "--", "Baud rate NOT set (JNI may have failed)")
                    }

                    for (st in stationsToTry) {
                        if (!isActive) { _autoDetecting.value = false; return@launch }

                        // Try polling this station
                        val txFrame = WinnsenCodec.buildPollCommand(st)
                        addLog("TX", WinnsenCodec.toHex(txFrame), "Poll station=$st")

                        val rxData = hwSerial.sendAndReceive(txFrame, WinnsenCodec.POLL_RESPONSE_LEN)

                        if (rxData != null) {
                            addLog("RX", WinnsenCodec.toHex(rxData), "Got ${rxData.size} bytes!")
                            val resp = WinnsenCodec.parsePollResponse(rxData)
                            if (resp != null) {
                                addLog("OK", "--", "FOUND! ${port.path} @ $baud baud, station=${resp.station}")
                                baudRate.value = baud
                                station.value = resp.station
                                _doorStates.value = resp.doorStates
                                _lastResult.value = "DETECTED: ${port.path} @ $baud"
                                _autoDetecting.value = false
                                return@launch
                            } else {
                                addLog("SYS", "--", "Got data but not a valid Winnsen poll response")
                            }
                        }
                    }

                    // Raw read test: check if port has any unsolicited data
                    try {
                        val rawBuf = ByteArray(64)
                        val available = hwSerial.rawRead(rawBuf, 500)
                        if (available > 0) {
                            val rawHex = WinnsenCodec.toHex(rawBuf.copyOf(available))
                            addLog("RX", rawHex, "RAW data on ${port.path}! ($available bytes)")
                        }
                    } catch (_: Exception) {}

                    hwSerial.disconnect()
                }
            }

            addLog("ERR", "--", "Auto-detect failed — no port responded at any baud/station")
            _lastResult.value = "NOT FOUND"
            _autoDetecting.value = false
        }
    }

    fun disconnect() {
        stopAutoPolling()
        hwSerial.disconnect()
        _doorStates.value = emptyMap()
        addLog("SYS", "--", "Disconnected")
    }

    fun openLock() {
        viewModelScope.launch(Dispatchers.IO) {
            val st = station.value
            val lk = lockNumber.value
            try {
                val txFrame = WinnsenCodec.buildOpenCommand(st, lk)
                addLog("TX", WinnsenCodec.toHex(txFrame), "Open station=$st lock=$lk")

                val rxData = hwSerial.sendAndReceive(txFrame, WinnsenCodec.OPEN_RESPONSE_LEN)

                if (rxData == null) {
                    addLog("RX", "--", "TIMEOUT - no response")
                    _lastResult.value = "TIMEOUT"
                    return@launch
                }

                addLog("RX", WinnsenCodec.toHex(rxData), "")
                val resp = WinnsenCodec.parseOpenResponse(rxData)

                if (resp == null) {
                    addLog("ERR", "", "Invalid response frame")
                    _lastResult.value = "INVALID RESPONSE"
                } else if (resp.success) {
                    addLog("OK", "", "Door OPENED: station=${resp.station} lock=${resp.lock}")
                    _lastResult.value = "OPENED"
                } else {
                    addLog("FAIL", "", "Open FAILED: station=${resp.station} lock=${resp.lock}")
                    _lastResult.value = "OPEN FAILED"
                }
            } catch (e: Exception) {
                addLog("ERR", "", "Error: ${e.message}")
                _lastResult.value = "ERROR: ${e.message}"
                Log.e(TAG, "openLock error", e)
            }
        }
    }

    fun pollOnce() {
        viewModelScope.launch(Dispatchers.IO) { doPoll() }
    }

    private suspend fun doPoll() {
        val st = station.value
        try {
            val txFrame = WinnsenCodec.buildPollCommand(st)
            addLog("TX", WinnsenCodec.toHex(txFrame), "Poll station=$st")

            val rxData = hwSerial.sendAndReceive(txFrame, WinnsenCodec.POLL_RESPONSE_LEN)

            if (rxData == null) {
                addLog("RX", "--", "TIMEOUT")
                return
            }

            addLog("RX", WinnsenCodec.toHex(rxData), "")
            val resp = WinnsenCodec.parsePollResponse(rxData)

            if (resp != null) {
                _doorStates.value = resp.doorStates
                val openDoors = resp.doorStates.filter { it.value }.keys.sorted()
                val summary = if (openDoors.isEmpty()) "All closed" else "Open: ${openDoors.joinToString()}"
                addLog("OK", "", summary)
            } else {
                addLog("ERR", "", "Invalid poll response")
            }
        } catch (e: Exception) {
            addLog("ERR", "", "Poll error: ${e.message}")
        }
    }

    fun toggleAutoPolling() {
        if (_autoPolling.value) stopAutoPolling() else startAutoPolling()
    }

    private fun startAutoPolling() {
        _autoPolling.value = true
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            addLog("SYS", "--", "Auto-poll started (1s interval)")
            while (isActive) { doPoll(); delay(1000) }
        }
    }

    private fun stopAutoPolling() {
        pollJob?.cancel(); pollJob = null; _autoPolling.value = false
    }

    fun clearLog() { _log.value = emptyList() }

    private fun addLog(direction: String, hex: String, note: String) {
        val entry = LogEntry(timeFormat.format(Date()), direction, hex, note)
        _log.value = _log.value + entry
    }

    override fun onCleared() {
        hwSerial.disconnect()
        super.onCleared()
    }
}
