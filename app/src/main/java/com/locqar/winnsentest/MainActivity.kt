package com.locqar.winnsentest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locqar.winnsentest.hardware.HardwareSerial

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val vm: TestViewModel = viewModel()
                    val appMode by vm.appMode.collectAsStateWithLifecycle()

                    when (appMode) {
                        TestViewModel.AppMode.USER -> PinEntryScreen(vm)
                        TestViewModel.AppMode.ADMIN -> TabletTestScreen(vm)
                    }
                }
            }
        }
    }
}

// ===== USER-FACING PIN ENTRY SCREEN =====
@Composable
fun PinEntryScreen(vm: TestViewModel) {
    val pinInput by vm.pinInput.collectAsStateWithLifecycle()
    val pinResult by vm.pinResult.collectAsStateWithLifecycle()
    val connState by vm.hwSerial.connectionState.collectAsStateWithLifecycle()
    val doorStates by vm.doorStates.collectAsStateWithLifecycle()
    val isConnected = connState == HardwareSerial.ConnectionState.CONNECTED

    // Auto-connect on entering user mode
    LaunchedEffect(Unit) {
        if (!vm.isConnected) vm.quickConnect()
    }

    Row(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E1A)),
        horizontalArrangement = Arrangement.Center
    ) {
        // ===== LEFT: Locker Status =====
        Column(
            modifier = Modifier.weight(0.4f).fillMaxHeight().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "LocQar Smart Locker",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isConnected) "System Online" else "Connecting...",
                fontSize = 16.sp,
                color = if (isConnected) Color(0xFF81C784) else Color(0xFFFF9800)
            )

            Spacer(Modifier.height(32.dp))

            // Locker grid
            Text("Locker Status", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in 0..3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        for (col in 1..4) {
                            val lockId = row * 4 + col
                            val isOpen = doorStates[lockId] ?: false
                            val hasPin = vm.pinManager.getActivePinForLock(lockId) != null
                            Box(
                                modifier = Modifier.weight(1f).aspectRatio(1.4f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        when {
                                            doorStates.isEmpty() -> Color(0xFF333333)
                                            isOpen -> Color(0xFFD32F2F)
                                            hasPin -> Color(0xFF1565C0) // Blue = has package
                                            else -> Color(0xFF2E7D32)   // Green = available
                                        }
                                    )
                                    .border(2.dp, Color(0xFF444444), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$lockId", fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Color.White)
                                    Text(
                                        when {
                                            doorStates.isEmpty() -> ""
                                            isOpen -> "OPEN"
                                            hasPin -> "IN USE"
                                            else -> "EMPTY"
                                        },
                                        fontSize = 11.sp, color = Color.White.copy(0.85f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Switch to admin mode (small button at bottom)
            TextButton(
                onClick = { vm.switchMode(TestViewModel.AppMode.ADMIN) }
            ) {
                Text("Admin Mode", fontSize = 12.sp, color = Color(0xFF555555))
            }
        }

        // ===== RIGHT: PIN Keypad =====
        Column(
            modifier = Modifier.weight(0.5f).fillMaxHeight().padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Enter Your PIN",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                "to collect your package",
                fontSize = 16.sp,
                color = Color(0xFF888888)
            )

            Spacer(Modifier.height(32.dp))

            // PIN display (dots)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(vertical = 16.dp)
            ) {
                for (i in 0 until 6) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                if (i < pinInput.length) Color(0xFF4CAF50) else Color(0xFF333333)
                            )
                            .border(2.dp, Color(0xFF555555), CircleShape)
                    )
                }
            }

            // Result message
            if (pinResult != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    pinResult!!,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        pinResult!!.contains("OPENED") || pinResult!!.contains("collect") -> Color(0xFF4CAF50)
                        pinResult!!.contains("Invalid") || pinResult!!.contains("Failed") -> Color(0xFFF44336)
                        else -> Color(0xFFFF9800)
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(24.dp))

            // Numeric keypad
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("C", "0", "<")
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (keyRow in keys) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for (key in keyRow) {
                            Button(
                                onClick = {
                                    when (key) {
                                        "C" -> vm.onPinClear()
                                        "<" -> vm.onPinBackspace()
                                        else -> vm.onPinDigit(key)
                                    }
                                },
                                modifier = Modifier.weight(1f).height(72.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when (key) {
                                        "C" -> Color(0xFF424242)
                                        "<" -> Color(0xFF424242)
                                        else -> Color(0xFF1E1E2E)
                                    }
                                )
                            ) {
                                Text(
                                    when (key) {
                                        "<" -> "\u232B"  // backspace symbol
                                        else -> key
                                    },
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Submit button
            Button(
                onClick = { vm.submitPin() },
                enabled = pinInput.length >= 4 && isConnected,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text("OPEN LOCKER", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ===== ADMIN SCREEN (existing debug + PIN management) =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletTestScreen(vm: TestViewModel = viewModel()) {
    val connState by vm.hwSerial.connectionState.collectAsStateWithLifecycle()
    val connPort by vm.hwSerial.connectedPort.collectAsStateWithLifecycle()
    val errorMsg by vm.hwSerial.errorMessage.collectAsStateWithLifecycle()
    val availablePorts by vm.hwSerial.availablePorts.collectAsStateWithLifecycle()
    val doorStates by vm.doorStates.collectAsStateWithLifecycle()
    val logEntries by vm.log.collectAsStateWithLifecycle()
    val autoPolling by vm.autoPolling.collectAsStateWithLifecycle()
    val lastResult by vm.lastResult.collectAsStateWithLifecycle()
    val station by vm.station.collectAsStateWithLifecycle()
    val lockNum by vm.lockNumber.collectAsStateWithLifecycle()

    val isConnected = connState == HardwareSerial.ConnectionState.CONNECTED
    val logListState = rememberLazyListState()

    LaunchedEffect(logEntries.size) {
        if (logEntries.isNotEmpty()) logListState.animateScrollToItem(logEntries.size - 1)
    }

    // Auto-scan on first launch
    LaunchedEffect(Unit) {
        vm.scanPorts()
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // ===== LEFT PANEL =====
        Column(modifier = Modifier.weight(0.45f).fillMaxHeight()) {
            Text(
                "Winnsen RS485 Protocol Test",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hardware UART Mode", fontSize = 14.sp, color = Color(0xFFFFB74D))
                Button(
                    onClick = { vm.switchMode(TestViewModel.AppMode.USER) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                    modifier = Modifier.height(36.dp)
                ) { Text("User Mode", fontSize = 12.sp) }
            }

            Spacer(Modifier.height(12.dp))

            // Connection status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (connState) {
                        HardwareSerial.ConnectionState.CONNECTED -> Color(0xFF1B5E20)
                        HardwareSerial.ConnectionState.ERROR -> Color(0xFF4A0000)
                        else -> Color(0xFF333333)
                    }
                )
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(
                        when (connState) {
                            HardwareSerial.ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                            HardwareSerial.ConnectionState.ERROR -> Color(0xFFF44336)
                            else -> Color(0xFF666666)
                        }
                    ))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when (connState) {
                            HardwareSerial.ConnectionState.CONNECTED -> "Connected: $connPort"
                            HardwareSerial.ConnectionState.ERROR -> "Error: ${errorMsg ?: "Unknown"}"
                            else -> "Disconnected"
                        },
                        fontSize = 14.sp,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    if (isConnected) {
                        Button(
                            onClick = { vm.disconnect() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                            modifier = Modifier.height(40.dp)
                        ) { Text("Disconnect") }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Quick Connect + Auto-detect + Scan buttons
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.quickConnect() },
                    modifier = Modifier.height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text("Quick Connect", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { vm.autoDetect() },
                    modifier = Modifier.height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) { Text("Auto-Detect", fontSize = 14.sp) }
                OutlinedButton(
                    onClick = { vm.scanPorts() },
                    modifier = Modifier.height(44.dp)
                ) { Text("Scan", fontSize = 13.sp) }
                Text("${availablePorts.size} ports", fontSize = 12.sp, color = Color(0xFF888888))
            }

            // Baud rate selector
            Spacer(Modifier.height(6.dp))
            val currentBaud by vm.baudRate.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Baud:", fontSize = 12.sp, color = Color(0xFF888888))
                vm.baudRates.forEach { baud ->
                    val isSelected = baud == currentBaud
                    OutlinedButton(
                        onClick = { vm.changeBaudRate(baud) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (isSelected) Color(0xFF4CAF50) else Color(0xFF888888)
                        )
                    ) { Text("$baud", fontSize = 11.sp) }
                }
            }

            // Available ports — scrollable row of buttons
            if (availablePorts.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                // Show accessible ports first
                val accessiblePorts = availablePorts.filter { it.readable && it.writable }
                val readOnlyPorts = availablePorts.filter { it.readable && !it.writable }

                if (accessiblePorts.isNotEmpty()) {
                    Text("Read+Write ports:", fontSize = 12.sp, color = Color(0xFF4CAF50))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        accessiblePorts.forEach { port ->
                            val isCurrent = connPort == port.path
                            OutlinedButton(
                                onClick = { vm.connectToPort(port.path) },
                                modifier = Modifier.height(36.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (isCurrent) Color(0xFF4CAF50) else Color.White
                                )
                            ) {
                                Text(port.path.removePrefix("/dev/"), fontSize = 11.sp)
                            }
                        }
                    }
                }

                if (readOnlyPorts.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Read-only ports:", fontSize = 12.sp, color = Color(0xFFFF9800))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        readOnlyPorts.forEach { port ->
                            OutlinedButton(
                                onClick = { vm.connectToPort(port.path) },
                                modifier = Modifier.height(36.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9800))
                            ) {
                                Text(port.path.removePrefix("/dev/"), fontSize = 11.sp)
                            }
                        }
                    }
                }

                // Show no-access ports count
                val noAccessCount = availablePorts.count { !it.readable && !it.writable }
                if (noAccessCount > 0) {
                    Text(
                        "$noAccessCount ports exist but have no R/W access (may need root)",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Config — use local text state so typing isn't blocked by validation
            var stationText by remember { mutableStateOf(station.toString()) }
            var lockText by remember { mutableStateOf(lockNum.toString()) }

            // Sync when ViewModel updates externally (e.g. auto-detect)
            LaunchedEffect(station) { stationText = station.toString() }
            LaunchedEffect(lockNum) { lockText = lockNum.toString() }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = stationText,
                    onValueChange = { text ->
                        // Allow empty field and any digits while typing
                        if (text.isEmpty() || text.all { it.isDigit() }) {
                            stationText = text
                            text.toIntOrNull()?.let { v ->
                                vm.station.value = v.coerceIn(0, 255)
                            }
                        }
                    },
                    label = { Text("Station #", fontSize = 14.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 20.sp)
                )
                OutlinedTextField(
                    value = lockText,
                    onValueChange = { text ->
                        if (text.isEmpty() || text.all { it.isDigit() }) {
                            lockText = text
                            text.toIntOrNull()?.let { v ->
                                vm.lockNumber.value = v.coerceIn(1, 16)
                            }
                        }
                    },
                    label = { Text("Lock #", fontSize = 14.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 20.sp)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.openLock() },
                    enabled = isConnected,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("OPEN LOCK", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = { vm.pollOnce() },
                    enabled = isConnected,
                    modifier = Modifier.weight(0.7f).height(52.dp)
                ) { Text("Poll Once", fontSize = 14.sp) }
                OutlinedButton(
                    onClick = { vm.toggleAutoPolling() },
                    enabled = isConnected,
                    modifier = Modifier.weight(0.7f).height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (autoPolling) Color(0xFFF44336) else MaterialTheme.colorScheme.primary
                    )
                ) { Text(if (autoPolling) "Stop" else "Auto-Poll", fontSize = 14.sp) }
            }

            if (lastResult.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Result: $lastResult", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = when {
                        lastResult.contains("OPENED") -> Color(0xFF4CAF50)
                        lastResult.contains("FAIL") || lastResult.contains("ERROR") -> Color(0xFFF44336)
                        else -> Color(0xFFFF9800)
                    })
            }

            Spacer(Modifier.height(16.dp))

            // Door grid with PIN management
            val pinAssignments by vm.pinManager.assignments.collectAsStateWithLifecycle()
            var showPinDialog by remember { mutableStateOf<Int?>(null) }
            var generatedPin by remember { mutableStateOf<String?>(null) }

            Text("Door Status (tap to assign PIN)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in 0..3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        for (col in 1..4) {
                            val lockId = row * 4 + col
                            val isOpen = doorStates[lockId] ?: false
                            val activePin = pinAssignments.values.find {
                                it.lockNumber == lockId && it.status == PinManager.AssignmentStatus.ACTIVE
                            }
                            Box(
                                modifier = Modifier.weight(1f).aspectRatio(1.6f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        when {
                                            doorStates.isEmpty() -> Color(0xFF333333)
                                            isOpen -> Color(0xFFD32F2F)
                                            activePin != null -> Color(0xFF1565C0)
                                            else -> Color(0xFF2E7D32)
                                        }
                                    )
                                    .border(2.dp, Color(0xFF555555), RoundedCornerShape(10.dp))
                                    .clickable { showPinDialog = lockId },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$lockId", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                                    if (activePin != null) {
                                        Text("PIN: ${activePin.pin}", fontSize = 10.sp, color = Color(0xFFBBDEFB))
                                    } else if (doorStates.isNotEmpty()) {
                                        Text(if (isOpen) "OPEN" else "CLOSED", fontSize = 10.sp, color = Color.White.copy(0.9f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // PIN Dialog
            if (showPinDialog != null) {
                val lockId = showPinDialog!!
                val existingPin = pinAssignments.values.find {
                    it.lockNumber == lockId && it.status == PinManager.AssignmentStatus.ACTIVE
                }

                AlertDialog(
                    onDismissRequest = { showPinDialog = null; generatedPin = null },
                    title = { Text("Locker $lockId") },
                    text = {
                        Column {
                            if (existingPin != null) {
                                Text("Active PIN: ${existingPin.pin}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                                Spacer(Modifier.height(8.dp))
                                Text("Status: ${existingPin.status}", fontSize = 14.sp)
                            } else if (generatedPin != null) {
                                Text("PIN Generated!", fontSize = 16.sp, color = Color(0xFF4CAF50))
                                Spacer(Modifier.height(8.dp))
                                Text(generatedPin!!, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White,
                                    fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.height(8.dp))
                                Text("Share this PIN with the customer", fontSize = 14.sp, color = Color(0xFF888888))
                            } else {
                                Text("No active PIN for this locker", fontSize = 14.sp)
                            }
                        }
                    },
                    confirmButton = {
                        if (existingPin != null) {
                            Button(
                                onClick = {
                                    vm.removePin(existingPin.pin)
                                    showPinDialog = null
                                    generatedPin = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                            ) { Text("Remove PIN") }
                        } else if (generatedPin == null) {
                            Button(
                                onClick = { generatedPin = vm.generatePinForLock(lockId) }
                            ) { Text("Generate PIN") }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPinDialog = null; generatedPin = null }) {
                            Text("Close")
                        }
                    }
                )
            }
        }

        // ===== RIGHT PANEL: Log =====
        Column(modifier = Modifier.weight(0.55f).fillMaxHeight()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Protocol Log", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = { vm.clearLog() }) { Text("Clear Log", fontSize = 14.sp) }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                state = logListState,
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0D1117))
                    .padding(12.dp)
            ) {
                items(logEntries) { entry ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(entry.timestamp, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF888888))
                        Spacer(Modifier.width(6.dp))
                        Text(entry.direction.padEnd(4), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            color = when (entry.direction) {
                                "TX" -> Color(0xFF64B5F6)
                                "RX" -> Color(0xFF81C784)
                                "OK" -> Color(0xFF4CAF50)
                                "ERR", "FAIL" -> Color(0xFFEF5350)
                                else -> Color(0xFFFFB74D)
                            })
                        Spacer(Modifier.width(6.dp))
                        if (entry.hex.isNotEmpty() && entry.hex != "--") {
                            Text(entry.hex, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFE0E0E0))
                            Spacer(Modifier.width(6.dp))
                        }
                        if (entry.note.isNotEmpty()) {
                            Text(entry.note, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFBDBDBD))
                        }
                    }
                }
            }
        }
    }
}
