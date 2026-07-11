package com.example.vigilantcascade

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    DashboardScreen()
                }
            }
        }
    }
}

class CascadeViewModel : ViewModel() {
    private val _dataPoints = MutableStateFlow<List<Float>>(List(128) { 0f })
    val dataPoints: StateFlow<List<Float>> = _dataPoints.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Awaiting Connection...")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _totalPacketsReceived = MutableStateFlow(0L)
    val totalPacketsReceived: StateFlow<Long> = _totalPacketsReceived.asStateFlow()

    private val _packetsPerSecond = MutableStateFlow(0f)
    val packetsPerSecond: StateFlow<Float> = _packetsPerSecond.asStateFlow()

    private val _alerts = MutableStateFlow<List<String>>(emptyList())
    val alerts: StateFlow<List<String>> = _alerts.asStateFlow()

    private val _degradationPercentage = MutableStateFlow(0f)
    val degradationPercentage: StateFlow<Float> = _degradationPercentage.asStateFlow()

    // Control flag state to tell the Compose UI whether to show the pop-up menu
    private val _showHighNoisePopup = MutableStateFlow(false)
    val showHighNoisePopup: StateFlow<Boolean> = _showHighNoisePopup.asStateFlow()

    private val _systemMemoryUsage = MutableStateFlow("0.0 MB")
    val systemMemoryUsage: StateFlow<String> = _systemMemoryUsage.asStateFlow()

    private var packetCounterInsideWindow = 0

    init {
        startServer()
        startMetricsEngine()
    }

    private fun triggerAlert(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _alerts.value = listOf("[$timestamp] CRITICAL: $message") + _alerts.value.take(19)
    }

    fun dismissPopup() {
        _showHighNoisePopup.value = false
    }

    private fun startMetricsEngine() {
        viewModelScope.launch(Dispatchers.Default) {
            val runtime = Runtime.getRuntime()
            while (true) {
                delay(1000)
                _packetsPerSecond.value = packetCounterInsideWindow.toFloat()
                packetCounterInsideWindow = 0

                val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                _systemMemoryUsage.value = "$usedMem MB"
            }
        }
    }

    private fun startServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverSocket = ServerSocket(8080)
                _connectionStatus.value = "Listening on port 8080..."

                while (true) {
                    val socket = serverSocket.accept()
                    _connectionStatus.value = "Connected to Source"

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    try {
                        reader.useLines { lines ->
                            lines.forEach { line ->
                                val rawTokens = line.split(",")
                                val parsedFloats = rawTokens.mapNotNull { it.toFloatOrNull() }
                                if (parsedFloats.isNotEmpty()) {
                                    _dataPoints.value = parsedFloats.take(128)
                                    _totalPacketsReceived.value += 1
                                    packetCounterInsideWindow += 1

                                    // Check peak amplitude value to evaluate signal variance or noise
                                    val peakValue = parsedFloats.maxOrNull() ?: 0f
                                    val calculatedDegradation = (peakValue * 10f).coerceIn(0f, 100f)
                                    _degradationPercentage.value = calculatedDegradation

                                    // CRITICAL LIMIT CONDITION (Noise or Degradation crosses 75%)
                                    if (calculatedDegradation > 75f) {
                                        triggerAlert("Asset structural integrity drop to ${calculatedDegradation.toInt()}%")

                                        // Fire the dynamic UI pop-up overlay window instantly
                                        _showHighNoisePopup.value = true
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        triggerAlert("Inbound streaming data loss event detected")
                    }

                    _connectionStatus.value = "Connection Lost. Re-listening..."
                }
            } catch (e: Exception) {
                _connectionStatus.value = "Network Error"
            }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: CascadeViewModel = viewModel()) {
    val streamData by viewModel.dataPoints.collectAsState()
    val status by viewModel.connectionStatus.collectAsState()
    val totalPackets by viewModel.totalPacketsReceived.collectAsState()
    val dataRate by viewModel.packetsPerSecond.collectAsState()
    val currentMemory by viewModel.systemMemoryUsage.collectAsState()
    val alertFeed by viewModel.alerts.collectAsState()
    val healthDegradation by viewModel.degradationPercentage.collectAsState()
    val isPopupVisible by viewModel.showHighNoisePopup.collectAsState()

    // --- POP-UP MODAL OVERLAY MENU ---
    if (isPopupVisible) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPopup() },
            title = {
                Text(
                    text = "⚠️ HIGH SIGNAL NOISE WARNING",
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "The telemetry stream has exceeded the structural threshold limits. Degradation level is currently sitting critical at ${healthDegradation.toInt()}%. Check source hardware values.",
                    color = Color.White,
                    fontSize = 14.sp
                )
            },
            containerColor = Color(0xFF221414), // Dark Red Alert Background
            confirmButton = {
                TextButton(
                    onClick = { viewModel.dismissPopup() }
                ) {
                    Text("DISMISS ALARM", color = Color(0xFFFFCDD2), fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // --- MAIN DASHBOARD INTERFACE LAYOUT GRID ---
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(2) }) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "VIGILANT CASCADE OPERATIONAL HUB",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Telemetry Status: $status",
                        color = if (status.contains("Connected")) Color.Green else Color.Yellow,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        item {
            val colorIndicator = when {
                healthDegradation > 75f -> Color(0xFFFF5252)
                healthDegradation > 40f -> Color(0xFFFFCC00)
                else -> Color(0xFF00E676)
            }
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(text = "Health Degradation", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = String.format(Locale.US, "%.1f %%", healthDegradation), color = colorIndicator, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        item {
            DiagnosticCard(title = "Data Rate", value = "${dataRate.toInt()} lines/sec", labelColor = Color(0xFF29B6F6))
        }

        item(span = { GridItemSpan(2) }) {
            Card(
                modifier = Modifier.height(260.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Real-Time Telemetry Plot Window",
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    SignalLineChart(data = streamData, modifier = Modifier.fillMaxSize())
                }
            }
        }

        item(span = { GridItemSpan(2) }) {
            Card(
                modifier = Modifier.height(200.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1111))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "HISTORICAL ALERT LIST LOG",
                        color = Color(0xFFFF5252),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    if (alertFeed.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = "No operational warnings logged.", color = Color.DarkGray, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(alertFeed) { alertMessage ->
                                Text(
                                    text = alertMessage,
                                    color = Color(0xFFFFCDD2),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticCard(title: String, value: String, labelColor: Color) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Text(text = title, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = value, color = labelColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SignalLineChart(data: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas

        val width = size.width
        val height = size.height
        val maxVal = (data.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val minVal = (data.minOrNull() ?: 0f).coerceAtMost(0f)
        val deltaY = (maxVal - minVal).coerceAtLeast(1f)

        val stepX = width / (data.size - 1).coerceAtLeast(1)
        val path = Path()

        data.forEachIndexed { index, value ->
            val x = index * stepX
            val normalizedY = (value - minVal) / deltaY
            val y = height - (normalizedY * height)

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = Color(0xFF00E676),
            style = Stroke(width = 4f)
        )
    }
}