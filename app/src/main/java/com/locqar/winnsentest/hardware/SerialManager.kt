package com.locqar.winnsentest.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * USB serial manager that handles both:
 * 1. Standard USB-to-RS485 adapters (CH340, FTDI, etc.) via usb-serial-for-android
 * 2. Winnsen control board (VID:05C6 Qualcomm) via raw USB bulk transfer
 *
 * The Winnsen board is a composite USB device with 5 interfaces.
 * We scan each interface for bulk IN/OUT endpoints for serial comms.
 */
class SerialManager(private val context: Context) {

    companion object {
        private const val TAG = "SerialManager"
        private const val ACTION_USB_PERMISSION = "com.locqar.winnsentest.USB_PERMISSION"
        private const val BAUD_RATE = 9600
        private const val WRITE_TIMEOUT_MS = 1000
        private const val READ_TIMEOUT_MS = 2000

        // Winnsen control board identifiers
        private const val WINNSEN_VID = 0x05C6
        private const val WINNSEN_PID = 0x9025
    }

    enum class ConnectionState { DISCONNECTED, REQUESTING_PERMISSION, CONNECTED, ERROR }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName = _connectedDeviceName.asStateFlow()

    private val _detectedDevices = MutableStateFlow<List<String>>(emptyList())
    val detectedDevices = _detectedDevices.asStateFlow()

    val isConnected: Boolean get() = _connectionState.value == ConnectionState.CONNECTED

    // Raw USB handles for Winnsen board
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    // Also support standard serial library as fallback
    private var serialPort: com.hoho.android.usbserial.driver.UsbSerialPort? = null

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
        val allDevices = usbManager.deviceList
        val deviceInfo = mutableListOf<String>()

        if (allDevices.isEmpty()) {
            deviceInfo.add("No USB devices detected")
            _detectedDevices.value = deviceInfo
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "No USB devices found. Check USB cable."
            return
        }

        // Log all devices with full detail
        var winnsenDevice: UsbDevice? = null
        for ((_, device) in allDevices) {
            val info = buildString {
                append("VID:${"%04X".format(device.vendorId)} PID:${"%04X".format(device.productId)} ")
                append("${device.productName ?: "Unknown"} [${device.deviceName}] ")
                append("class=${device.deviceClass} interfaces=${device.interfaceCount}")
            }
            deviceInfo.add(info)

            // Log detailed interface info
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val ifaceInfo = buildString {
                    append("  iface[$i]: class=${iface.interfaceClass} sub=${iface.interfaceSubclass} ")
                    append("proto=${iface.interfaceProtocol} endpoints=${iface.endpointCount}")
                }
                deviceInfo.add(ifaceInfo)
                Log.i(TAG, ifaceInfo)

                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                        else -> "?"
                    }
                    val epInfo = "    ep[$e]: $dir $type maxPacket=${ep.maxPacketSize}"
                    deviceInfo.add(epInfo)
                    Log.i(TAG, epInfo)
                }
            }

            // Check if this is the Winnsen board
            if (device.vendorId == WINNSEN_VID && device.productId == WINNSEN_PID) {
                winnsenDevice = device
            }
        }
        _detectedDevices.value = deviceInfo

        // Priority: Winnsen board first, then try standard serial adapters
        val targetDevice = winnsenDevice ?: run {
            // Try standard serial library
            val drivers = com.hoho.android.usbserial.driver.UsbSerialProber.getDefaultProber()
                .findAllDrivers(usbManager)
            if (drivers.isNotEmpty()) {
                val driver = drivers[0]
                // Use standard serial path
                requestPermission(driver.device)
                return
            }

            // No known device found
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "No Winnsen board (VID:05C6) or serial adapter found"
            return
        }

        requestPermission(targetDevice)
    }

    private fun requestPermission(device: UsbDevice) {
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
        if (device.vendorId == WINNSEN_VID && device.productId == WINNSEN_PID) {
            openWinnsenDevice(device)
        } else {
            openStandardSerial(device)
        }
    }

    /**
     * Open the Winnsen control board using raw USB bulk transfers.
     * Scans all interfaces for bulk IN + OUT endpoint pairs.
     */
    private fun openWinnsenDevice(device: UsbDevice) {
        try {
            val connection = usbManager.openDevice(device)
                ?: throw IllegalStateException("Could not open USB device")

            // Scan all interfaces for bulk endpoints
            var foundInterface: UsbInterface? = null
            var foundIn: UsbEndpoint? = null
            var foundOut: UsbEndpoint? = null
            val triedInterfaces = mutableListOf<String>()

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var bulkIn: UsbEndpoint? = null
                var bulkOut: UsbEndpoint? = null

                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep
                        if (ep.direction == UsbConstants.USB_DIR_OUT) bulkOut = ep
                    }
                }

                val status = "iface[$i] class=${iface.interfaceClass}: bulkIn=${bulkIn != null} bulkOut=${bulkOut != null}"
                triedInterfaces.add(status)
                Log.i(TAG, status)

                if (bulkIn != null && bulkOut != null && foundInterface == null) {
                    foundInterface = iface
                    foundIn = bulkIn
                    foundOut = bulkOut
                    Log.i(TAG, "Using interface $i for serial comms")
                }
            }

            if (foundInterface == null || foundIn == null || foundOut == null) {
                connection.close()
                val detail = triedInterfaces.joinToString("\n")
                throw IllegalStateException("No bulk IN+OUT endpoint pair found.\n$detail")
            }

            // Claim the interface
            if (!connection.claimInterface(foundInterface, true)) {
                connection.close()
                throw IllegalStateException("Could not claim interface ${foundInterface.id}")
            }

            // Set baud rate via CDC SET_LINE_CODING if applicable
            // This is a standard USB CDC request: 0x20 = SET_LINE_CODING
            val lineCoding = byteArrayOf(
                (BAUD_RATE and 0xFF).toByte(),
                ((BAUD_RATE shr 8) and 0xFF).toByte(),
                ((BAUD_RATE shr 16) and 0xFF).toByte(),
                ((BAUD_RATE shr 24) and 0xFF).toByte(),
                0x00, // 1 stop bit
                0x00, // no parity
                0x08  // 8 data bits
            )
            // Try to set line coding (may silently fail on non-CDC devices, that's OK)
            connection.controlTransfer(
                0x21, // bmRequestType: host-to-device, class, interface
                0x20, // SET_LINE_CODING
                0, foundInterface.id, lineCoding, lineCoding.size, WRITE_TIMEOUT_MS
            )

            // Enable DTR/RTS (SET_CONTROL_LINE_STATE)
            connection.controlTransfer(
                0x21, 0x22, // SET_CONTROL_LINE_STATE
                0x03, // DTR + RTS
                foundInterface.id, null, 0, WRITE_TIMEOUT_MS
            )

            usbConnection = connection
            usbInterface = foundInterface
            endpointIn = foundIn
            endpointOut = foundOut

            _connectionState.value = ConnectionState.CONNECTED
            _connectedDeviceName.value = "Winnsen Board VID:${"%04X".format(device.vendorId)} iface=${foundInterface.id}"
            _errorMessage.value = null
            Log.i(TAG, "Connected to Winnsen board on interface ${foundInterface.id}")

        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "Winnsen: ${e.message}"
            Log.e(TAG, "Failed to open Winnsen device", e)
        }
    }

    /**
     * Fallback: open standard USB-serial adapter (CH340, FTDI, etc.)
     */
    private fun openStandardSerial(device: UsbDevice) {
        try {
            val drivers = com.hoho.android.usbserial.driver.UsbSerialProber.getDefaultProber()
                .findAllDrivers(usbManager)
            val driver = drivers.find { it.device == device }
                ?: throw IllegalStateException("No serial driver for this device")

            val connection = usbManager.openDevice(device)
                ?: throw IllegalStateException("Could not open USB device")

            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(BAUD_RATE, 8,
                com.hoho.android.usbserial.driver.UsbSerialPort.STOPBITS_1,
                com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)

            serialPort = port
            _connectionState.value = ConnectionState.CONNECTED
            _connectedDeviceName.value = "${device.productName ?: "USB Serial"} (${device.deviceName})"
            _errorMessage.value = null
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "Serial: ${e.message}"
            Log.e(TAG, "Failed to open serial device", e)
        }
    }

    fun sendAndReceive(txData: ByteArray, expectedResponseLen: Int): ByteArray? {
        // Raw USB path (Winnsen board)
        if (usbConnection != null && endpointOut != null && endpointIn != null) {
            return rawSendAndReceive(txData, expectedResponseLen)
        }

        // Standard serial library path
        val port = serialPort ?: return null
        return try {
            port.write(txData, WRITE_TIMEOUT_MS)
            val buffer = ByteArray(64)
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

    private fun rawSendAndReceive(txData: ByteArray, expectedResponseLen: Int): ByteArray? {
        val conn = usbConnection ?: return null
        val epOut = endpointOut ?: return null
        val epIn = endpointIn ?: return null

        return try {
            // Send
            val written = conn.bulkTransfer(epOut, txData, txData.size, WRITE_TIMEOUT_MS)
            if (written < 0) {
                Log.e(TAG, "Bulk write failed: $written")
                return null
            }
            Log.d(TAG, "Wrote $written bytes")

            // Receive — accumulate until we have enough
            val buffer = ByteArray(epIn.maxPacketSize.coerceAtLeast(64))
            val accumulated = mutableListOf<Byte>()
            val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS

            while (accumulated.size < expectedResponseLen && System.currentTimeMillis() < deadline) {
                val timeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                val bytesRead = conn.bulkTransfer(epIn, buffer, buffer.size, timeout.coerceAtMost(READ_TIMEOUT_MS))
                if (bytesRead > 0) {
                    for (i in 0 until bytesRead) accumulated.add(buffer[i])
                    Log.d(TAG, "Read $bytesRead bytes, total=${accumulated.size}")
                }
            }

            if (accumulated.size >= expectedResponseLen) {
                accumulated.toByteArray().copyOfRange(0, expectedResponseLen)
            } else {
                Log.w(TAG, "Timeout: got ${accumulated.size}/$expectedResponseLen bytes")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Raw USB I/O error", e)
            null
        }
    }

    fun disconnect() {
        // Raw USB cleanup
        try {
            usbInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (_: Exception) {}
        usbConnection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null

        // Serial library cleanup
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
