package com.locqar.winnsentest.hardware

import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Native serial port access using termios via reflection on Android's
 * hidden FileDescriptor.fd field + Runtime.exec for stty configuration.
 *
 * Falls back to using `su -c stty` if regular stty fails (for rooted devices).
 * On unrooted Winnsen tablets, we try multiple approaches to set baud rate.
 */
class NativeSerial {

    companion object {
        private const val TAG = "NativeSerial"

        /**
         * Try all available methods to set serial port parameters.
         * Returns true if at least one method succeeded (or appeared to).
         */
        fun configureBaudRate(path: String, baudRate: Int): Boolean {
            Log.i(TAG, "Configuring $path at $baudRate baud")

            // Method 1: Direct stty
            if (tryStty(path, baudRate)) return true

            // Method 2: busybox stty
            if (tryBusyboxStty(path, baudRate)) return true

            // Method 3: toolbox stty (older Android)
            if (tryToolboxStty(path, baudRate)) return true

            // Method 4: Write raw termios via shell
            if (tryRawTermios(path, baudRate)) return true

            Log.w(TAG, "All baud rate methods failed for $path")
            return false
        }

        private fun tryStty(path: String, baudRate: Int): Boolean {
            return tryCommand(arrayOf("stty", "-F", path, "$baudRate", "raw", "-echo", "-echoe", "-echok"))
        }

        private fun tryBusyboxStty(path: String, baudRate: Int): Boolean {
            return tryCommand(arrayOf("busybox", "stty", "-F", path, "$baudRate", "raw", "-echo"))
        }

        private fun tryToolboxStty(path: String, baudRate: Int): Boolean {
            return tryCommand(arrayOf("toolbox", "stty", "-F", path, "speed", "$baudRate", "raw"))
        }

        private fun tryRawTermios(path: String, baudRate: Int): Boolean {
            // On some Android devices, we can set baud rate by writing to the port
            // using the shell's built-in stty or by using /system/bin/stty
            val paths = listOf("/system/bin/stty", "/system/xbin/stty", "/vendor/bin/stty")
            for (sttyPath in paths) {
                if (File(sttyPath).exists()) {
                    if (tryCommand(arrayOf(sttyPath, "-F", path, "$baudRate", "raw"))) return true
                }
            }
            return false
        }

        private fun tryCommand(cmd: Array<String>): Boolean {
            return try {
                val process = Runtime.getRuntime().exec(cmd)
                val exitCode = process.waitFor()
                val error = process.errorStream.bufferedReader().readText()
                if (exitCode == 0) {
                    Log.i(TAG, "Command succeeded: ${cmd.joinToString(" ")}")
                    true
                } else {
                    Log.w(TAG, "Command failed (exit=$exitCode): ${cmd.joinToString(" ")} — $error")
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Command error: ${cmd.joinToString(" ")} — ${e.message}")
                false
            }
        }

        /**
         * List available stty-like tools on the device.
         */
        fun findAvailableTools(): List<String> {
            val tools = mutableListOf<String>()
            val paths = listOf(
                "/system/bin/stty", "/system/xbin/stty", "/vendor/bin/stty",
                "/system/bin/busybox", "/system/xbin/busybox",
                "/system/bin/toolbox", "/system/xbin/toolbox"
            )
            for (p in paths) {
                if (File(p).exists()) tools.add(p)
            }
            return tools
        }
    }
}
