package com.locqar.winnsentest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
                    TabletTestScreen()
                }
            }
        }
    }
}

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
            Text("Hardware UART Mode", fontSize = 14.sp, color = Color(0xFFFFB74D))

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

            // Auto-detect + Scan buttons
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.autoDetect() },
                    modifier = Modifier.height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) { Text("Auto-Detect", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
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

            // Config
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = station.toString(),
                    onValueChange = { it.toIntOrNull()?.let { v -> vm.station.value = v.coerceIn(1, 255) } },
                    label = { Text("Station #", fontSize = 14.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 20.sp)
                )
                OutlinedTextField(
                    value = lockNum.toString(),
                    onValueChange = { it.toIntOrNull()?.let { v -> vm.lockNumber.value = v.coerceIn(1, 16) } },
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

            // Door grid
            Text("Door Status", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in 0..3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        for (col in 1..4) {
                            val lockId = row * 4 + col
                            val isOpen = doorStates[lockId] ?: false
                            Box(
                                modifier = Modifier.weight(1f).aspectRatio(1.6f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (doorStates.isEmpty()) Color(0xFF333333)
                                        else if (isOpen) Color(0xFFD32F2F) else Color(0xFF2E7D32)
                                    )
                                    .border(2.dp, Color(0xFF555555), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$lockId", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color.White)
                                    if (doorStates.isNotEmpty()) {
                                        Text(if (isOpen) "OPEN" else "CLOSED", fontSize = 11.sp, color = Color.White.copy(0.9f))
                                    }
                                }
                            }
                        }
                    }
                }
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
