package com.locqar.winnsentest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.locqar.winnsentest.hardware.SerialManager

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
    val connState by vm.serial.connectionState.collectAsStateWithLifecycle()
    val deviceName by vm.serial.connectedDeviceName.collectAsStateWithLifecycle()
    val errorMsg by vm.serial.errorMessage.collectAsStateWithLifecycle()
    val detectedDevices by vm.serial.detectedDevices.collectAsStateWithLifecycle()
    val doorStates by vm.doorStates.collectAsStateWithLifecycle()
    val logEntries by vm.log.collectAsStateWithLifecycle()
    val autoPolling by vm.autoPolling.collectAsStateWithLifecycle()
    val lastResult by vm.lastResult.collectAsStateWithLifecycle()
    val station by vm.station.collectAsStateWithLifecycle()
    val lockNum by vm.lockNumber.collectAsStateWithLifecycle()

    val isConnected = connState == SerialManager.ConnectionState.CONNECTED
    val logListState = rememberLazyListState()

    LaunchedEffect(logEntries.size) {
        if (logEntries.isNotEmpty()) {
            logListState.animateScrollToItem(logEntries.size - 1)
        }
    }

    // Landscape layout: Left panel (controls + grid) | Right panel (log)
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // ===== LEFT PANEL: Controls + Door Grid =====
        Column(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight()
        ) {
            // Header
            Text(
                "Winnsen RS485 Protocol Test",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(16.dp))

            // Connection status bar
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (connState) {
                        SerialManager.ConnectionState.CONNECTED -> Color(0xFF1B5E20)
                        SerialManager.ConnectionState.ERROR -> Color(0xFF4A0000)
                        else -> Color(0xFF333333)
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                when (connState) {
                                    SerialManager.ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                                    SerialManager.ConnectionState.ERROR -> Color(0xFFF44336)
                                    SerialManager.ConnectionState.REQUESTING_PERMISSION -> Color(0xFFFF9800)
                                    else -> Color(0xFF666666)
                                }
                            )
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when (connState) {
                            SerialManager.ConnectionState.CONNECTED -> "Connected: ${deviceName ?: "USB"}"
                            SerialManager.ConnectionState.ERROR -> "Error: ${errorMsg ?: "Unknown"}"
                            SerialManager.ConnectionState.REQUESTING_PERMISSION -> "Requesting USB permission..."
                            else -> "Disconnected"
                        },
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { if (isConnected) vm.disconnect() else vm.connect() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConnected) Color(0xFFF44336) else Color(0xFF4CAF50)
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(
                            if (isConnected) "Disconnect" else "Connect",
                            fontSize = 16.sp
                        )
                    }
                }
            }

            // Show detected USB devices (debug info)
            if (detectedDevices.isNotEmpty() && !isConnected) {
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Detected USB Devices:",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFB74D)
                        )
                        Spacer(Modifier.height(4.dp))
                        detectedDevices.forEach { dev ->
                            Text(
                                dev,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFE0E0E0)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Config + Actions row
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
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

            Spacer(Modifier.height(16.dp))

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { vm.openLock() },
                    enabled = isConnected,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                    modifier = Modifier.weight(1f).height(56.dp)
                ) {
                    Text("OPEN LOCK", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { vm.pollOnce() },
                    enabled = isConnected,
                    modifier = Modifier.weight(0.7f).height(56.dp)
                ) {
                    Text("Poll Once", fontSize = 16.sp)
                }
                OutlinedButton(
                    onClick = { vm.toggleAutoPolling() },
                    enabled = isConnected,
                    modifier = Modifier.weight(0.7f).height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (autoPolling) Color(0xFFF44336) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        if (autoPolling) "Stop" else "Auto-Poll",
                        fontSize = 16.sp
                    )
                }
            }

            if (lastResult.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Result: $lastResult",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        lastResult.contains("OPENED") -> Color(0xFF4CAF50)
                        lastResult.contains("FAIL") || lastResult.contains("ERROR") -> Color(0xFFF44336)
                        lastResult.contains("TIMEOUT") -> Color(0xFFFF9800)
                        else -> Color.White
                    }
                )
            }

            Spacer(Modifier.height(20.dp))

            // Door Status Grid
            Text(
                "Door Status",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))

            // 4 rows x 4 cols for 16 locks — sized for 15" tablet
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                for (row in 0..3) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for (col in 1..4) {
                            val lockId = row * 4 + col
                            val isOpen = doorStates[lockId] ?: false
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1.6f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (doorStates.isEmpty()) Color(0xFF333333)
                                        else if (isOpen) Color(0xFFD32F2F) else Color(0xFF2E7D32)
                                    )
                                    .border(2.dp, Color(0xFF555555), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "$lockId",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 24.sp,
                                        color = Color.White
                                    )
                                    if (doorStates.isNotEmpty()) {
                                        Text(
                                            if (isOpen) "OPEN" else "CLOSED",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White.copy(alpha = 0.9f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ===== RIGHT PANEL: Protocol Log =====
        Column(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Protocol Log",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = { vm.clearLog() }) {
                    Text("Clear Log", fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                state = logListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0D1117))
                    .padding(12.dp)
            ) {
                items(logEntries) { entry ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            entry.timestamp,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF888888)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            entry.direction.padEnd(4),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = when (entry.direction) {
                                "TX" -> Color(0xFF64B5F6)
                                "RX" -> Color(0xFF81C784)
                                "OK" -> Color(0xFF4CAF50)
                                "ERR", "FAIL" -> Color(0xFFEF5350)
                                else -> Color(0xFFFFB74D)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        if (entry.hex.isNotEmpty() && entry.hex != "--") {
                            Text(
                                entry.hex,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFE0E0E0)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        if (entry.note.isNotEmpty()) {
                            Text(
                                entry.note,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFBDBDBD)
                            )
                        }
                    }
                }
            }
        }
    }
}
