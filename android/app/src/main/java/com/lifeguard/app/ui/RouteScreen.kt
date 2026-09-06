package com.lifeguard.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.maps.model.LatLng
import com.lifeguard.app.data.UserSession
import com.lifeguard.app.route.RouteDeviationService
import com.lifeguard.app.route.RouteManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Locale

/**
 * 2D Open-Source Map powered by native OpenStreetMap (osmdroid-android).
 * 100% Free & Open-Source — Requires ZERO API keys, ZERO Google Cloud billing.
 * Features:
 * 1. Native Android MapView rendering OpenStreetMap tiles with offline disk caching.
 * 2. Real-time GPS marker for user location.
 * 3. Tap-to-Route: Tap any destination to calculate road route via OSRM.
 * 4. Freehand Finger Drawing: Drag finger across streets to draw a custom path on Canvas.
 * 5. 1-tap activation of Route Deviation Guard (150m corridor monitoring).
 */
@Composable
fun RouteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Initialize osmdroid configuration
    LaunchedEffect(Unit) {
        try {
            val osmConfig = Configuration.getInstance()
            osmConfig.load(context, context.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE))
            osmConfig.userAgentValue = context.packageName
            val osmBaseDir = File(context.cacheDir, "osmdroid")
            val osmTilesDir = File(osmBaseDir, "tiles")
            osmConfig.osmdroidBasePath = osmBaseDir
            osmConfig.osmdroidTileCache = osmTilesDir
        } catch (e: Exception) {
            android.util.Log.w("RouteScreen", "OSM config init: " + e.message)
        }
    }

    val userLat = UserSession.getLastLatitude(context).let { if (it != 0.0) it else 13.0827 }
    val userLng = UserSession.getLastLongitude(context).let { if (it != 0.0) it else 80.2707 }

    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }
    var currentPolylineOverlay by remember { mutableStateOf<Polyline?>(null) }
    var destMarkerOverlay by remember { mutableStateOf<Marker?>(null) }

    // Route state
    var isDrawingMode by remember { mutableStateOf(false) }
    var drawnScreenPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var routePoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var routeDistanceMeters by remember { mutableStateOf(0.0) }
    var routeSource by remember { mutableStateOf("none") } // "drawn" or "osrm"
    var isCalculatingRoute by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {

        // ── Native OpenStreetMap MapView ──
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(16.0)
                    controller.setCenter(GeoPoint(userLat, userLng))

                    // Add current user location marker
                    val userMarker = Marker(this).apply {
                        position = GeoPoint(userLat, userLng)
                        title = "My Location"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    overlays.add(userMarker)

                    // Add tap listener for tap-to-route mode
                    val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                            if (isDrawingMode) return false
                            scope.launch {
                                isCalculatingRoute = true
                                val origin = LatLng(userLat, userLng)
                                val dest = LatLng(p.latitude, p.longitude)
                                val route = RouteManager.fetchRoute(origin, dest, "")
                                isCalculatingRoute = false

                                if (route != null && route.polylinePoints.isNotEmpty()) {
                                    routePoints = route.polylinePoints
                                    routeSource = "osrm"

                                    var dist = 0.0
                                    for (i in 0 until route.polylinePoints.size - 1) {
                                        dist += RouteManager.haversineDistance(route.polylinePoints[i], route.polylinePoints[i + 1])
                                    }
                                    routeDistanceMeters = dist

                                    withContext(Dispatchers.Main) {
                                        // Remove previous route & dest marker
                                        currentPolylineOverlay?.let { overlays.remove(it) }
                                        destMarkerOverlay?.let { overlays.remove(it) }

                                        val destMarker = Marker(this@apply).apply {
                                            position = p
                                            title = "Destination"
                                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                        }
                                        destMarkerOverlay = destMarker
                                        overlays.add(destMarker)

                                        val poly = Polyline(this@apply).apply {
                                            outlinePaint.color = AndroidColor.parseColor("#00E676")
                                            outlinePaint.strokeWidth = 14f
                                            setPoints(route.polylinePoints.map { GeoPoint(it.latitude, it.longitude) })
                                        }
                                        currentPolylineOverlay = poly
                                        overlays.add(poly)
                                        invalidate()
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "Road route not found. Use 'Draw Route' to draw it freely!", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            return true
                        }

                        override fun longPressHelper(p: GeoPoint): Boolean = false
                    })
                    overlays.add(eventsOverlay)

                    mapViewInstance = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Transparent Freehand Finger Drawing Overlay ──
        if (isDrawingMode) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                drawnScreenPoints = listOf(offset)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                drawnScreenPoints = drawnScreenPoints + change.position
                            },
                            onDragEnd = {
                                val mv = mapViewInstance
                                if (mv != null && drawnScreenPoints.size >= 2) {
                                    val geoList = mutableListOf<LatLng>()
                                    val proj = mv.projection
                                    for (pt in drawnScreenPoints) {
                                        val gp = proj.fromPixels(pt.x.toInt(), pt.y.toInt()) as? GeoPoint
                                        if (gp != null) {
                                            geoList.add(LatLng(gp.latitude, gp.longitude))
                                        }
                                    }

                                    if (geoList.size >= 2) {
                                        routePoints = geoList
                                        routeSource = "drawn"
                                        var dist = 0.0
                                        for (i in 0 until geoList.size - 1) {
                                            dist += RouteManager.haversineDistance(geoList[i], geoList[i + 1])
                                        }
                                        routeDistanceMeters = dist
                                    }
                                }
                            }
                        )
                    }
            ) {
                if (drawnScreenPoints.size >= 2) {
                    val path = Path().apply {
                        moveTo(drawnScreenPoints.first().x, drawnScreenPoints.first().y)
                        for (i in 1 until drawnScreenPoints.size) {
                            lineTo(drawnScreenPoints[i].x, drawnScreenPoints[i].y)
                        }
                    }
                    drawPath(
                        path = path,
                        color = Color(0xFFFF1744),
                        style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
        }

        // ── Top Header Controls ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xDD1E1E1E),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Text("←", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text(
                                text = "🗺️ 2D OpenStreetMap",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isDrawingMode) "✏️ Draw path with your finger" else "📍 Tap map to set destination",
                                color = if (isDrawingMode) Color(0xFFFF5252) else Color(0xFF00E5FF),
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Re-center on user GPS
                    IconButton(onClick = {
                        mapViewInstance?.controller?.animateTo(GeoPoint(userLat, userLng))
                        mapViewInstance?.controller?.setZoom(16.0)
                    }) {
                        Text("🎯", fontSize = 18.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Floating Actions: Draw Route Toggle & Clear ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        isDrawingMode = !isDrawingMode
                        if (isDrawingMode) {
                            Toast.makeText(context, "✏️ Draw Mode ON: Drag finger across the map to draw your route", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Map pan/zoom restored", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDrawingMode) Color(0xFFD32F2F) else Color(0xFF2A2A2A)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Text(
                        text = if (isDrawingMode) "✋ Done Drawing" else "✏️ Draw Route",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = {
                        drawnScreenPoints = emptyList()
                        routePoints = emptyList()
                        routeDistanceMeters = 0.0
                        routeSource = "none"
                        mapViewInstance?.let { mv ->
                            currentPolylineOverlay?.let { mv.overlays.remove(it) }
                            destMarkerOverlay?.let { mv.overlays.remove(it) }
                            currentPolylineOverlay = null
                            destMarkerOverlay = null
                            mv.invalidate()
                        }
                        Toast.makeText(context, "Route cleared", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFAAAAAA)),
                    modifier = Modifier.height(38.dp)
                ) {
                    Text("🗑️ Clear", fontSize = 11.sp)
                }
            }
        }

        // ── Calculating Progress Indicator ──
        if (isCalculatingRoute) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xCC000000)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFF00E5FF))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Calculating road route...", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        // ── Bottom Action Card: Route Summary & Start Monitoring ──
        AnimatedVisibility(
            visible = routePoints.size >= 2,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xEE1A1A1A),
                shadowElevation = 12.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = if (routeSource == "drawn") "✏️ Hand-Drawn Custom Path" else "🚗 Road Snapped Path",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val distText = if (routeDistanceMeters >= 1000) {
                                String.format(Locale.US, "%.2f km", routeDistanceMeters / 1000.0)
                            } else {
                                String.format(Locale.US, "%.0f meters", routeDistanceMeters)
                            }
                            Text(
                                text = "Distance: " + distText + " • " + routePoints.size + " points",
                                color = Color(0xFF4CAF50),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x224CAF50)
                        ) {
                            Text(
                                text = "🛡️ 150m Safe Corridor",
                                color = Color(0xFF4CAF50),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            if (routePoints.size < 2) return@Button
                            val startPt = routePoints.first()
                            val endPt = routePoints.last()
                            val encoded = RouteManager.encodePolyline(routePoints)

                            // Save route corridor
                            RouteDeviationService.saveRoute(
                                context = context,
                                encodedPolyline = encoded,
                                startLat = startPt.latitude,
                                startLng = startPt.longitude,
                                endLat = endPt.latitude,
                                endLng = endPt.longitude
                            )

                            // Start background monitoring service
                            val serviceIntent = Intent(context, RouteDeviationService::class.java).apply {
                                action = RouteDeviationService.ACTION_START
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }

                            Toast.makeText(
                                context,
                                "🛡️ Route Guard Activated! Off-route >150m for 5 mins triggers SOS.",
                                Toast.LENGTH_LONG
                            ).show()

                            onBack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(
                            text = "🛡️ START ROUTE GUARD",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}
