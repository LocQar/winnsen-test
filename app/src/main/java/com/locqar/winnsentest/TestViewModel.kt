package com.locqar.winnsentest

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.locqar.winnsentest.hardware.HardwareSerial
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

    // Config
    val station = MutableStateFlow(1)
    val lockNumber = MutableStateFlow(1)
    val baudRate = MutableStateFlow(9600)
    val baudRates = listOf(9600, 19200, 38400, 57600, 115200, 4800, 2400)

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
            val baudOk = hwSerial.baudRateSet.value
            val tools = hwSerial.sttyTools.value
            addLog("OK", "--", "Connected to $path")
            addLog("SYS", "--", "Baud rate set: $baudOk, tools: ${tools.ifEmpty { listOf("none found") }}")
        } else {
            addLog("ERR", "--", "Failed: ${hwSerial.errorMessage.value}")
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
     * Auto-detect: try all ports at all baud rates, send a poll command,
     * and see which one responds.
     */
    fun autoDetect() {
        viewModelScope.launch(Dispatchers.IO) {
            addLog("SYS", "--", "Auto-detecting locker port...")
            val ports = hwSerial.scanPorts().filter { it.readable && it.writable }

            if (ports.isEmpty()) {
                addLog("ERR", "--", "No accessible ports found")
                return@launch
            }

            for (baud in baudRates) {
                for (port in ports) {
                    addLog("SYS", "--", "Trying ${port.path} @ $baud...")
                    hwSerial.disconnect()
                    hwSerial.connect(port.path, baud)

                    if (!hwSerial.isConnected) continue

                    // Try polling station 1
                    val txFrame = WinnsenCodec.buildPollCommand(1)
                    addLog("TX", WinnsenCodec.toHex(txFrame), "Poll station=1")

                    val rxData = hwSerial.sendAndReceive(txFrame, WinnsenCodec.POLL_RESPONSE_LEN)

                    if (rxData != null) {
                        addLog("RX", WinnsenCodec.toHex(rxData), "")
                        val resp = WinnsenCodec.parsePollResponse(rxData)
                        if (resp != null) {
                            addLog("OK", "--", "FOUND! ${port.path} @ $baud baud, station=${resp.station}")
                            baudRate.value = baud
                            station.value = resp.station
                            _doorStates.value = resp.doorStates
                            _lastResult.value = "DETECTED: ${port.path} @ $baud"
                            return@launch
                        } else {
                            addLog("SYS", "--", "Got data but invalid Winnsen response")
                        }
                    }

                    hwSerial.disconnect()
                }
            }

            addLog("ERR", "--", "Auto-detect failed — no port responded")
            _lastResult.value = "NOT FOUND"
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
