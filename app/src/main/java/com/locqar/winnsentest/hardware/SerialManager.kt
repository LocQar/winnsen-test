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
import com.hoho.android.usbserial.driver.*
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

    // All USB devices visible to the tablet (for debug display)
    private val _detectedDevices = MutableStateFlow<List<String>>(emptyList())
    val detectedDevices = _detectedDevices.asStateFlow()

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

    /**
     * Scan all USB devices and list them for debugging.
     * Then try to find a serial driver (default prober + custom CDC/ACM fallback).
     */
    fun connect() {
        // Step 1: Log ALL USB devices the tablet can see
        val allDevices = usbManager.deviceList
        val deviceInfo = mutableListOf<String>()

        if (allDevices.isEmpty()) {
            deviceInfo.add("No USB devices detected at all")
            _detectedDevices.value = deviceInfo
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "No USB devices found. Check USB cable."
            Log.w(TAG, "No USB devices found")
            return
        }

        for ((name, device) in allDevices) {
            val info = "VID:${"%04X".format(device.vendorId)} PID:${"%04X".format(device.productId)} " +
                "${device.productName ?: "Unknown"} [${device.deviceName}] " +
                "class=${device.deviceClass} interfaces=${device.interfaceCount}"
            deviceInfo.add(info)
            Log.i(TAG, "USB device: $info")
        }
        _detectedDevices.value = deviceInfo

        // Step 2: Try default prober (CH340, FTDI, PL2303, CP210x)
        var availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        // Step 3: If no standard drivers found, try CDC/ACM driver on all devices
        // (Winnsen boards may present as CDC/ACM serial devices)
        if (availableDrivers.isEmpty()) {
            Log.i(TAG, "No standard drivers. Trying CDC/ACM on all ${allDevices.size} devices...")

            val customTable = ProbeTable()
            for ((_, device) in allDevices) {
                // Try CDC/ACM driver for each device
                customTable.addProduct(device.vendorId, device.productId, CdcAcmSerialDriver::class.java)
            }
            val customProber = UsbSerialProber(customTable)
            availableDrivers = customProber.findAllDrivers(usbManager)

            if (availableDrivers.isEmpty()) {
                // Step 4: Last resort — try all driver types
                Log.i(TAG, "CDC/ACM failed. Trying all driver types...")
                val bruteTable = ProbeTable()
                for ((_, device) in allDevices) {
                    bruteTable.addProduct(device.vendorId, device.productId, Ch34xSerialDriver::class.java)
                    bruteTable.addProduct(device.vendorId, device.productId, FtdiSerialDriver::class.java)
                    bruteTable.addProduct(device.vendorId, device.productId, ProlificSerialDriver::class.java)
                    bruteTable.addProduct(device.vendorId, device.productId, Cp21xxSerialDriver::class.java)
                }
                val bruteProber = UsbSerialProber(bruteTable)
                availableDrivers = bruteProber.findAllDrivers(usbManager)
            }
        }

        if (availableDrivers.isEmpty()) {
            val devList = deviceInfo.joinToString("\n")
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "Found ${allDevices.size} USB device(s) but none recognized as serial.\n$devList"
            Log.w(TAG, "No serial drivers matched")
            return
        }

        Log.i(TAG, "Found ${availableDrivers.size} serial driver(s)")
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
            // Try all probers to find the right driver
            var driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                .find { it.device == device }

            if (driver == null) {
                // Try CDC/ACM
                val customTable = ProbeTable()
                customTable.addProduct(device.vendorId, device.productId, CdcAcmSerialDriver::class.java)
                driver = UsbSerialProber(customTable).findAllDrivers(usbManager)
                    .find { it.device == device }
            }

            if (driver == null) {
                // Try all types
                val driverTypes = listOf(
                    Ch34xSerialDriver::class.java,
                    FtdiSerialDriver::class.java,
                    ProlificSerialDriver::class.java,
                    Cp21xxSerialDriver::class.java
                )
                for (driverType in driverTypes) {
                    val table = ProbeTable()
                    table.addProduct(device.vendorId, device.productId, driverType)
                    driver = UsbSerialProber(table).findAllDrivers(usbManager)
                        .find { it.device == device }
                    if (driver != null) break
                }
            }

            if (driver == null) throw IllegalStateException("No driver found for VID:${"%04X".format(device.vendorId)} PID:${"%04X".format(device.productId)}")

            val connection = usbManager.openDevice(device)
                ?: throw IllegalStateException("Could not open USB device")

            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            serialPort = port
            _connectionState.value = ConnectionState.CONNECTED
            _connectedDeviceName.value = "VID:${"%04X".format(device.vendorId)} PID:${"%04X".format(device.productId)} ${device.productName ?: ""} (${device.deviceName})"
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
