package com.locqar.winnsentest.hardware

/**
 * JNI bridge to native serial port functions.
 * Uses termios to properly configure baud rate on Android.
 */
object SerialPortJNI {

    init {
        System.loadLibrary("serial_port")
    }

    /**
     * Open a serial port with proper termios configuration.
     * @param path Device path (e.g., "/dev/ttyS1")
     * @param baudRate Baud rate (e.g., 9600, 115200)
     * @return File descriptor (>= 0) on success, -1 on failure
     */
    @JvmStatic
    external fun nativeOpen(path: String, baudRate: Int): Int

    /**
     * Close a serial port file descriptor.
     */
    @JvmStatic
    external fun nativeClose(fd: Int)
}
