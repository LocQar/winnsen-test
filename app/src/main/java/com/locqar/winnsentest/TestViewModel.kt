package com.locqar.winnsentest

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.locqar.winnsentest.hardware.SerialManager
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

    val serial = SerialManager(app.applicationContext)

    // Config
    val station = MutableStateFlow(1)
    val lockNumber = MutableStateFlow(1)

    // Door states: lock number -> isOpen
    private val _doorStates = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val doorStates = _doorStates.asStateFlow()

    // Hex log entries
    data class LogEntry(val timestamp: String, val direction: String, val hex: String, val note: String)

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log = _log.asStateFlow()

    // Auto-poll
    private val _autoPolling = MutableStateFlow(false)
    val autoPolling = _autoPolling.asStateFlow()
    private var pollJob: Job? = null

    // Last action result
    private val _lastResult = MutableStateFlow("")
    val lastResult = _lastResult.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun connect() {
        serial.connect()
        addLog("SYS", "--", "Connecting to USB adapter...")
    }

    fun disconnect() {
        stopAutoPolling()
        serial.disconnect()
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

                val rxData = serial.sendAndReceive(txFrame, WinnsenCodec.OPEN_RESPONSE_LEN)

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
        viewModelScope.launch(Dispatchers.IO) {
            doPoll()
        }
    }

    private suspend fun doPoll() {
        val st = station.value
        try {
            val txFrame = WinnsenCodec.buildPollCommand(st)
            addLog("TX", WinnsenCodec.toHex(txFrame), "Poll station=$st")

            val rxData = serial.sendAndReceive(txFrame, WinnsenCodec.POLL_RESPONSE_LEN)

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
            Log.e(TAG, "poll error", e)
        }
    }

    fun toggleAutoPolling() {
        if (_autoPolling.value) {
            stopAutoPolling()
        } else {
            startAutoPolling()
        }
    }

    private fun startAutoPolling() {
        _autoPolling.value = true
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            addLog("SYS", "--", "Auto-poll started (1s interval)")
            while (isActive) {
                doPoll()
                delay(1000)
            }
        }
    }

    private fun stopAutoPolling() {
        pollJob?.cancel()
        pollJob = null
        _autoPolling.value = false
        addLog("SYS", "--", "Auto-poll stopped")
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    private fun addLog(direction: String, hex: String, note: String) {
        val entry = LogEntry(timeFormat.format(Date()), direction, hex, note)
        _log.value = _log.value + entry
    }

    override fun onCleared() {
        serial.destroy()
        super.onCleared()
    }
}
