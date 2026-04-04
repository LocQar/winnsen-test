package com.locqar.winnsentest.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SerialManager(private val context: Context) {

    companion object {
        private const val TAG = "SerialManager"
        private const val ACTION_USB_PERMISSION = "com.locqar.winnsentest.USB_PERMISSION"
        private const val BAUD_RATE = 9600
        private const val WRITE_TIMEOUT_MS = 1000
        private const val READ_TIMEOUT_MS = 2000
        private const val READ_CHUNK_SIZE = 64
    }

    enum class ConnectionState { DISCONNECTED, REQUESTING_PERMISSION, CONNECTED, ERROR }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName = _connectedDeviceName.asStateFlow()

    val isConnected: Boolean get() = _connectionState.value == ConnectionState.CONNECTED

    private var serialPort: UsbSerialPort? = null
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (granted && device != null) {
                    openDevice(device)
                } else {
                    _connectionState.value = ConnectionState.ERROR
                    _errorMessage.value = "USB permission denied"
                    Log.w(TAG, "USB permission denied")
                }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }
    }

    fun connect() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "No USB-to-RS485 adapter found"
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (usbManager.hasPermission(device)) {
            openDevice(device)
        } else {
            _connectionState.value = ConnectionState.REQUESTING_PERMISSION
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val permIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permIntent)
        }
    }

    private fun openDevice(device: UsbDevice) {
        try {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = drivers.find { it.device == device }
                ?: throw IllegalStateException("Driver not found")

            val connection = usbManager.openDevice(device)
                ?: throw IllegalStateException("Could not open USB device")

            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            serialPort = port
            _connectionState.value = ConnectionState.CONNECTED
            _connectedDeviceName.value = "${device.productName ?: "USB"} (${device.deviceName})"
            _errorMessage.value = null
            Log.i(TAG, "Connected to ${device.deviceName} at $BAUD_RATE baud")
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "Failed: ${e.message}"
            Log.e(TAG, "Failed to open USB device", e)
        }
    }

    fun sendAndReceive(txData: ByteArray, expectedResponseLen: Int): ByteArray? {
        val port = serialPort ?: return null
        return try {
            port.write(txData, WRITE_TIMEOUT_MS)

            val buffer = ByteArray(READ_CHUNK_SIZE)
            val accumulated = mutableListOf<Byte>()
            val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS

            while (accumulated.size < expectedResponseLen && System.currentTimeMillis() < deadline) {
                val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                val bytesRead = port.read(buffer, remaining.coerceAtMost(READ_TIMEOUT_MS))
                if (bytesRead > 0) {
                    for (i in 0 until bytesRead) accumulated.add(buffer[i])
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
        try { serialPort?.close() } catch (_: Exception) {}
        serialPort = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDeviceName.value = null
    }

    fun destroy() {
        disconnect()
        try { context.unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
    }
}
