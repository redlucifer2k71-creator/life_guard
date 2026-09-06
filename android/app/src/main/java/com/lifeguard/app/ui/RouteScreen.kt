package com.lifeguard.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 2D Open-Source Map powered by OpenStreetMap (OSM) & Leaflet.
 * Requires ZERO Google Cloud API keys, works 100% free, and allows:
 * 1. Live GPS tracking of current user location.
 * 2. Tap-to-Route (street-snapped routing via OSRM).
 * 3. Freehand Finger Route Drawing (draw paths directly on the map).
 * 4. 1-tap activation of Route Deviation Guard (150m corridor monitoring).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RouteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val userLat = UserSession.getLastLatitude(context).let { if (it != 0.0) it else 13.0827 }
    val userLng = UserSession.getLastLongitude(context).let { if (it != 0.0) it else 80.2707 }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isMapReady by remember { mutableStateOf(false) }

    // Route state
    var isDrawingMode by remember { mutableStateOf(false) }
    var routePoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var routeDistanceMeters by remember { mutableStateOf(0.0) }
    var routeSource by remember { mutableStateOf("none") } // "drawn" or "osrm"
    var isCalculatingRoute by remember { mutableStateOf(false) }

    // JS Bridge definition
    val jsBridge = remember {
        object {
            @JavascriptInterface
            fun onMapReady() {
                isMapReady = true
            }

            @JavascriptInterface
            fun onMapClick(lat: Double, lng: Double) {
                if (isDrawingMode) return
                scope.launch {
                    isCalculatingRoute = true
                    val origin = LatLng(userLat, userLng)
                    val dest = LatLng(lat, lng)
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

                        val jsonArr = JSONArray()
                        route.polylinePoints.forEach { pt ->
                            val obj = JSONObject()
                            obj.put("lat", pt.latitude)
                            obj.put("lng", pt.longitude)
                            jsonArr.put(obj)
                        }
                        withContext(Dispatchers.Main) {
                            webViewInstance?.evaluateJavascript("displayRoute(" + jsonArr.toString() + ")", null)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Could not compute road route. You can draw it with your finger!", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            @JavascriptInterface
            fun onRouteDrawn(jsonPointsStr: String) {
                try {
                    val jsonArr = JSONArray(jsonPointsStr)
                    val points = mutableListOf<LatLng>()
                    for (i in 0 until jsonArr.length()) {
                        val obj = jsonArr.getJSONObject(i)
                        points.add(LatLng(obj.getDouble("lat"), obj.getDouble("lng")))
                    }

                    if (points.size >= 2) {
                        routePoints = points
                        routeSource = "drawn"
                        var dist = 0.0
                        for (i in 0 until points.size - 1) {
                            dist += RouteManager.haversineDistance(points[i], points[i + 1])
                        }
                        routeDistanceMeters = dist
                    }
                } catch (e: Exception) {
                    android.util.Log.e("RouteScreen", "Failed to parse drawn points: " + e.message)
                }
            }
        }
    }

    val htmlContent = remember(userLat, userLng) {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                body, html, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #121212; }
                .user-pulse {
                    width: 16px; height: 16px; background: #00E5FF; border: 3px solid #FFFFFF;
                    border-radius: 50%; box-shadow: 0 0 10px #00E5FF;
                }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var map = L.map('map', { zoomControl: false }).setView([$userLat, $userLng], 15);
                L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
                    maxZoom: 19,
                    attribution: '© OpenStreetMap'
                }).addTo(map);

                var userMarker = L.marker([$userLat, $userLng], {
                    icon: L.divIcon({ className: 'user-pulse', iconSize: [16, 16], iconAnchor: [8, 8] })
                }).addTo(map);

                var routePolyline = null;
                var isDrawing = false;
                var drawPoints = [];

                function setDrawMode(enabled) {
                    isDrawing = enabled;
                    if (enabled) {
                        map.dragging.disable();
                        map.touchZoom.disable();
                        map.doubleClickZoom.disable();
                        map.scrollWheelZoom.disable();
                    } else {
                        map.dragging.enable();
                        map.touchZoom.enable();
                        map.doubleClickZoom.enable();
                        map.scrollWheelZoom.enable();
                    }
                }

                var mapDiv = document.getElementById('map');
                mapDiv.addEventListener('touchstart', function(e) {
                    if (!isDrawing) return;
                    drawPoints = [];
                    if (routePolyline) { map.removeLayer(routePolyline); routePolyline = null; }
                    var touch = e.touches[0];
                    var rect = mapDiv.getBoundingClientRect();
                    var pt = map.containerPointToLatLng(L.point(touch.clientX - rect.left, touch.clientY - rect.top));
                    drawPoints.push(pt);
                    routePolyline = L.polyline(drawPoints, { color: '#FF5252', weight: 6, opacity: 0.9 }).addTo(map);
                });

                mapDiv.addEventListener('touchmove', function(e) {
                    if (!isDrawing || drawPoints.length === 0) return;
                    var touch = e.touches[0];
                    var rect = mapDiv.getBoundingClientRect();
                    var pt = map.containerPointToLatLng(L.point(touch.clientX - rect.left, touch.clientY - rect.top));
                    drawPoints.push(pt);
                    routePolyline.setLatLngs(drawPoints);
                });

                mapDiv.addEventListener('touchend', function(e) {
                    if (!isDrawing || drawPoints.length < 2) return;
                    var ptsArray = [];
                    for (var i = 0; i < drawPoints.length; i++) {
                        ptsArray.push({ lat: drawPoints[i].lat, lng: drawPoints[i].lng });
                    }
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onRouteDrawn(JSON.stringify(ptsArray));
                    }
                });

                map.on('click', function(e) {
                    if (isDrawing) return;
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onMapClick(e.latlng.lat, e.latlng.lng);
                    }
                });

                function displayRoute(coords) {
                    if (routePolyline) { map.removeLayer(routePolyline); }
                    var latlngs = coords.map(function(c) { return [c.lat, c.lng]; });
                    routePolyline = L.polyline(latlngs, { color: '#00E676', weight: 6, opacity: 0.9 }).addTo(map);
                    map.fitBounds(routePolyline.getBounds(), { padding: [50, 50] });
                }

                function clearMapRoute() {
                    if (routePolyline) { map.removeLayer(routePolyline); routePolyline = null; }
                    drawPoints = [];
                }

                function centerOnLocation(lat, lng) {
                    map.setView([lat, lng], 16, { animate: true });
                }

                if (window.AndroidBridge) {
                    window.AndroidBridge.onMapReady();
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {

        // ── 2D OpenStreetMap WebView ──
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {}
                    addJavascriptInterface(jsBridge, "AndroidBridge")
                    loadDataWithBaseURL("https://openstreetmap.org", htmlContent, "text/html", "UTF-8", null)
                    webViewInstance = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Top Header & Mode Controls ──
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
                                text = "🗺️ 2D Open-Source Map",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isDrawingMode) "✏️ Drag finger across roads to draw" else "📍 Tap destination to snap route",
                                color = if (isDrawingMode) Color(0xFFFF5252) else Color(0xFF00E5FF),
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Re-center button
                    IconButton(onClick = {
                        webViewInstance?.evaluateJavascript("centerOnLocation($userLat, $userLng)", null)
                    }) {
                        Text("🎯", fontSize = 18.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Floating Action Bar: Draw Route Toggle & Clear ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Toggle Draw Mode Button
                Button(
                    onClick = {
                        val next = !isDrawingMode
                        isDrawingMode = next
                        webViewInstance?.evaluateJavascript("setDrawMode(" + next + ")", null)
                        if (next) {
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

                // Clear Route Button
                OutlinedButton(
                    onClick = {
                        routePoints = emptyList()
                        routeDistanceMeters = 0.0
                        routeSource = "none"
                        webViewInstance?.evaluateJavascript("clearMapRoute()", null)
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
                    Text("Calculating street route...", color = Color.White, fontSize = 13.sp)
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
                                text = if (routeSource == "drawn") "✏️ Custom Drawn Route" else "🚗 Street Snapped Route",
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
                                text = "Distance: " + distText + " • " + routePoints.size + " waypoints",
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
