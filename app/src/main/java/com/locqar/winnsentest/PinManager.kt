package com.locqar.winnsentest

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages PIN-to-locker assignments.
 * PINs are stored locally in SharedPreferences for now.
 * Later this will sync with the LocQar backend API.
 *
 * Data model:
 *   PIN (4-6 digits) → LockerAssignment(lockNumber, status, createdAt)
 */
class PinManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "locker_pins"
        private const val KEY_PREFIX_LOCK = "pin_lock_"    // pin_lock_<pin> = lockNumber
        private const val KEY_PREFIX_STATUS = "pin_status_" // pin_status_<pin> = status
        private const val KEY_PREFIX_TIME = "pin_time_"     // pin_time_<pin> = createdAt
        private const val KEY_ALL_PINS = "all_pins"         // comma-separated list of all PINs
    }

    enum class AssignmentStatus {
        ACTIVE,      // PIN is assigned and waiting for pickup
        PICKED_UP,   // Door was opened with this PIN
        EXPIRED      // PIN has expired
    }

    data class LockerAssignment(
        val pin: String,
        val lockNumber: Int,
        val status: AssignmentStatus,
        val createdAt: Long
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _assignments = MutableStateFlow<Map<String, LockerAssignment>>(emptyMap())
    val assignments = _assignments.asStateFlow()

    init {
        loadAll()
    }

    /**
     * Assign a PIN to a locker. Returns true if successful.
     */
    fun assignPin(pin: String, lockNumber: Int): Boolean {
        if (pin.length !in 4..6) return false
        if (!pin.all { it.isDigit() }) return false

        // Check if PIN already exists
        if (_assignments.value.containsKey(pin)) return false

        // Check if lock already has an active PIN
        val existingForLock = _assignments.value.values.find {
            it.lockNumber == lockNumber && it.status == AssignmentStatus.ACTIVE
        }
        if (existingForLock != null) return false

        val assignment = LockerAssignment(
            pin = pin,
            lockNumber = lockNumber,
            status = AssignmentStatus.ACTIVE,
            createdAt = System.currentTimeMillis()
        )

        save(assignment)
        return true
    }

    /**
     * Generate a random PIN and assign it to a locker.
     * Returns the generated PIN, or null if the lock already has an active PIN.
     */
    fun generateAndAssign(lockNumber: Int, pinLength: Int = 4): String? {
        // Check if lock already has an active PIN
        val existingForLock = _assignments.value.values.find {
            it.lockNumber == lockNumber && it.status == AssignmentStatus.ACTIVE
        }
        if (existingForLock != null) return null

        // Generate unique PIN
        val random = java.util.Random()
        var pin: String
        val max = when (pinLength) {
            4 -> 9999
            5 -> 99999
            6 -> 999999
            else -> 9999
        }
        do {
            pin = String.format("%0${pinLength}d", random.nextInt(max + 1))
        } while (_assignments.value.containsKey(pin))

        assignPin(pin, lockNumber)
        return pin
    }

    /**
     * Validate a PIN. Returns the lock number if valid and ACTIVE, null otherwise.
     */
    fun validatePin(pin: String): LockerAssignment? {
        val assignment = _assignments.value[pin] ?: return null
        return if (assignment.status == AssignmentStatus.ACTIVE) assignment else null
    }

    /**
     * Mark a PIN as picked up (door was opened).
     */
    fun markPickedUp(pin: String) {
        val assignment = _assignments.value[pin] ?: return
        val updated = assignment.copy(status = AssignmentStatus.PICKED_UP)
        save(updated)
    }

    /**
     * Remove a PIN assignment.
     */
    fun removePin(pin: String) {
        prefs.edit().apply {
            remove("$KEY_PREFIX_LOCK$pin")
            remove("$KEY_PREFIX_STATUS$pin")
            remove("$KEY_PREFIX_TIME$pin")

            val allPins = getAllPinKeys().toMutableSet()
            allPins.remove(pin)
            putString(KEY_ALL_PINS, allPins.joinToString(","))
            apply()
        }

        _assignments.value = _assignments.value.toMutableMap().apply { remove(pin) }
    }

    /**
     * Clear all PIN assignments.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        _assignments.value = emptyMap()
    }

    /**
     * Get the active PIN for a specific lock, if any.
     */
    fun getActivePinForLock(lockNumber: Int): LockerAssignment? {
        return _assignments.value.values.find {
            it.lockNumber == lockNumber && it.status == AssignmentStatus.ACTIVE
        }
    }

    private fun save(assignment: LockerAssignment) {
        prefs.edit().apply {
            putInt("$KEY_PREFIX_LOCK${assignment.pin}", assignment.lockNumber)
            putString("$KEY_PREFIX_STATUS${assignment.pin}", assignment.status.name)
            putLong("$KEY_PREFIX_TIME${assignment.pin}", assignment.createdAt)

            val allPins = getAllPinKeys().toMutableSet()
            allPins.add(assignment.pin)
            putString(KEY_ALL_PINS, allPins.joinToString(","))
            apply()
        }

        _assignments.value = _assignments.value.toMutableMap().apply {
            put(assignment.pin, assignment)
        }
    }

    private fun loadAll() {
        val map = mutableMapOf<String, LockerAssignment>()
        for (pin in getAllPinKeys()) {
            val lock = prefs.getInt("$KEY_PREFIX_LOCK$pin", -1)
            val statusStr = prefs.getString("$KEY_PREFIX_STATUS$pin", null)
            val time = prefs.getLong("$KEY_PREFIX_TIME$pin", 0)

            if (lock >= 0 && statusStr != null) {
                val status = try { AssignmentStatus.valueOf(statusStr) } catch (_: Exception) { AssignmentStatus.ACTIVE }
                map[pin] = LockerAssignment(pin, lock, status, time)
            }
        }
        _assignments.value = map
    }

    private fun getAllPinKeys(): List<String> {
        val raw = prefs.getString(KEY_ALL_PINS, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(",").filter { it.isNotEmpty() }
    }
}
