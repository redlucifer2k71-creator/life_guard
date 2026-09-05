package com.lifeguard.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.lifeguard.app.route.RouteDeviationService
import com.lifeguard.app.route.RouteManager
import kotlinx.coroutines.launch

private const val TAG = "RouteScreen"

// Default camera — Bangalore
private val DEFAULT_POS = LatLng(12.9716, 77.5946)

@Composable
fun RouteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Map state
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(DEFAULT_POS, 14f)
    }

    // Route setup state
    var startPoint by remember { mutableStateOf<LatLng?>(null) }
    var endPoint by remember { mutableStateOf<LatLng?>(null) }
    var placingMode by remember { mutableStateOf("start") } // "start", "end", "done"

    // Route result
    var routePoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var routeDistance by remember { mutableStateOf("") }
    var routeDuration by remember { mutableStateOf("") }

    // Loading/monitoring state
    var isLoadingRoute by remember { mutableStateOf(false) }
    var isMonitoring by remember { mutableStateOf(RouteDeviationService.isRouteActive(context)) }

    // Get API key from AndroidManifest
    val apiKey = remember {
        try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA
            )
            appInfo.metaData?.getString("com.google.android.geo.API_KEY") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Google Map ──
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = false,
                mapType = MapType.NORMAL
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                mapToolbarEnabled = false
            ),
            onMapClick = { latLng ->
                when (placingMode) {
                    "start" -> {
                        startPoint = latLng
                        placingMode = "end"
                        Toast.makeText(context, "✅ Start point set — Now tap your destination", Toast.LENGTH_SHORT).show()
                    }
                    "end" -> {
                        endPoint = latLng
                        placingMode = "done"
                        Toast.makeText(context, "✅ Destination set — Tap 'Get Route'", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        ) {
            // Start marker
            startPoint?.let { pos ->
                Marker(
                    state = MarkerState(position = pos),
                    title = "Start",
                    snippet = "Your starting point"
                )
            }

            // End marker
            endPoint?.let { pos ->
                Marker(
                    state = MarkerState(position = pos),
                    title = "Destination",
                    snippet = "Your destination"
                )
            }

            // Route polyline
            if (routePoints.isNotEmpty()) {
                Polyline(
                    points = routePoints,
                    color = Color(0xFF4285F4),
                    width = 14f
                )
                // Deviation radius visualization (subtle red border)
                Polyline(
                    points = routePoints,
                    color = Color(0x33D32F2F),
                    width = 80f
                )
            }
        }

        // ── Top Bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button
            Surface(
                shape = CircleShape,
                color = Color(0xDD1E1E1E),
                onClick = {
                    if (isMonitoring) {
                        Toast.makeText(context, "Stop monitoring before going back", Toast.LENGTH_SHORT).show()
                    } else {
                        onBack()
                    }
                }
            ) {
                Text(
                    text = "←",
                    color = Color.White,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xDD1E1E1E)
            ) {
                Text(
                    text = if (isMonitoring) "🗺️ Route Guard ACTIVE"
                           else "🗺️ Route Guard Setup",
                    color = if (isMonitoring) Color(0xFF4CAF50) else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }

        // ── Bottom Control Panel ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Color(0xF0121212),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                )
                .padding(20.dp)
        ) {
            // Instruction / Status
            when {
                isMonitoring -> {
                    // Active monitoring display
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Route Guard Active", color = Color(0xFF4CAF50),
                                fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("$routeDistance • $routeDuration",
                                color = Color(0xFF888888), fontSize = 12.sp)
                            Text("Off-route > 5 min → SOS alert",
                                color = Color(0xFF666666), fontSize = 11.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Stop monitoring button
                    Button(
                        onClick = {
                            stopRouteMonitor(context)
                            isMonitoring = false
                            routePoints = emptyList()
                            startPoint = null
                            endPoint = null
                            placingMode = "start"
                            Toast.makeText(context, "Route monitoring stopped", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("STOP MONITORING", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                routePoints.isNotEmpty() -> {
                    // Route calculated — ready to start monitoring
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Route Ready", color = Color(0xFF4285F4),
                                fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("$routeDistance • $routeDuration",
                                color = Color(0xFF888888), fontSize = 13.sp)
                        }
                        Text("${routePoints.size} pts", color = Color(0xFF555555), fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Start monitoring button
                    Button(
                        onClick = {
                            startRouteMonitor(context, startPoint!!, endPoint!!, routePoints)
                            isMonitoring = true
                            Toast.makeText(context, "🗺️ Route Guard activated!", Toast.LENGTH_LONG).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text("🛡️ START ROUTE GUARD", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // Reset route
                    TextButton(onClick = {
                        routePoints = emptyList()
                        startPoint = null
                        endPoint = null
                        placingMode = "start"
                    }) {
                        Text("Reset route", color = Color(0xFF888888))
                    }
                }

                else -> {
                    // Setup mode — place points
                    Text(
                        text = when (placingMode) {
                            "start" -> "Tap on the map to set your START point"
                            "end" -> "Tap on the map to set your DESTINATION"
                            else -> "Both points set"
                        },
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Point chips
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PointChip(
                            label = "Start",
                            point = startPoint,
                            isActive = placingMode == "start",
                            onClick = { placingMode = "start" },
                            modifier = Modifier.weight(1f)
                        )
                        PointChip(
                            label = "Destination",
                            point = endPoint,
                            isActive = placingMode == "end",
                            onClick = { if (startPoint != null) placingMode = "end" },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Get Route button
                    AnimatedVisibility(visible = startPoint != null && endPoint != null) {
                        Column {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    if (apiKey.isBlank() || apiKey == "YOUR_GOOGLE_MAPS_API_KEY_HERE") {
                                        Toast.makeText(context, "⚠️ Set your Google Maps API key in AndroidManifest.xml", Toast.LENGTH_LONG).show()
                                        return@Button
                                    }
                                    isLoadingRoute = true
                                    scope.launch {
                                        val result = RouteManager.fetchRoute(startPoint!!, endPoint!!, apiKey)
                                        isLoadingRoute = false
                                        if (result != null) {
                                            routePoints = result.polylinePoints
                                            routeDistance = result.distanceText
                                            routeDuration = result.durationText

                                            // Zoom camera to fit the route
                                            val bounds = com.google.android.gms.maps.model.LatLngBounds.builder()
                                            result.polylinePoints.forEach { bounds.include(it) }
                                            cameraPositionState.animate(
                                                CameraUpdateFactory.newLatLngBounds(bounds.build(), 100)
                                            )
                                        } else {
                                            Toast.makeText(context, "Failed to fetch route — check API key", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                enabled = !isLoadingRoute,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(52.dp)
                            ) {
                                if (isLoadingRoute) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                } else {
                                    Text("🗺️ GET ROUTE", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PointChip(
    label: String,
    point: LatLng?,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = when {
            isActive -> Color(0xFF2A2A2A)
            point != null -> Color(0xFF1A3A1A)
            else -> Color(0xFF1A1A1A)
        },
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (point != null) "✅ $label" else "📍 $label",
                color = if (point != null) Color(0xFF4CAF50) else Color(0xFF888888),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            if (point != null) {
                Text(
                    text = "${String.format("%.4f", point.latitude)}, ${String.format("%.4f", point.longitude)}",
                    color = Color(0xFF555555),
                    fontSize = 10.sp
                )
            } else {
                Text(
                    text = if (isActive) "Tap map to set" else "Waiting...",
                    color = Color(0xFF444444),
                    fontSize = 10.sp
                )
            }
        }
    }
}

// ── Service control helpers ──

private fun startRouteMonitor(
    context: Context,
    start: LatLng,
    end: LatLng,
    polylinePoints: List<LatLng>
) {
    // We need to re-encode or store the route for the service to read.
    // For simplicity, we'll just store the start/end and re-fetch won't be needed
    // because we also store the encoded polyline via saveRoute.
    // Re-encode using a simple approach — store the overview polyline.
    // Actually, RouteManager already returned it, but we need to thread it through.
    // The cleanest way is to store the encoded polyline when we fetch it.
    // Let's get it from the route manager's last result via the polyline points.

    // For the service, encode the points back (or we store the encoded string globally)
    // Simplest: store encoded polyline in RouteManager and retrieve here.
    // Better approach: store in SharedPrefs via RouteDeviationService.saveRoute

    // Since we have the decoded points, re-encode them for storage
    val encoded = encodePolyline(polylinePoints)

    RouteDeviationService.saveRoute(
        context, encoded,
        start.latitude, start.longitude,
        end.latitude, end.longitude
    )

    val intent = Intent(context, RouteDeviationService::class.java).apply {
        action = RouteDeviationService.ACTION_START
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun stopRouteMonitor(context: Context) {
    val intent = Intent(context, RouteDeviationService::class.java).apply {
        action = RouteDeviationService.ACTION_STOP
    }
    context.stopService(intent)
    RouteDeviationService.clearRoute(context)
}

/**
 * Encode a list of LatLng points back into Google's encoded polyline format.
 */
private fun encodePolyline(points: List<LatLng>): String {
    val result = StringBuilder()
    var prevLat = 0
    var prevLng = 0

    for (point in points) {
        val lat = (point.latitude * 1E5).toInt()
        val lng = (point.longitude * 1E5).toInt()

        encodeValue(lat - prevLat, result)
        encodeValue(lng - prevLng, result)

        prevLat = lat
        prevLng = lng
    }
    return result.toString()
}

private fun encodeValue(value: Int, result: StringBuilder) {
    var v = if (value < 0) (value shl 1).inv() else (value shl 1)
    while (v >= 0x20) {
        result.append(((0x20 or (v and 0x1F)) + 63).toChar())
        v = v shr 5
    }
    result.append((v + 63).toChar())
}
