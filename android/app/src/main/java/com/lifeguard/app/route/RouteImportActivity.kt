package com.lifeguard.app.route

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.LatLng
import com.lifeguard.app.data.UserSession
import com.lifeguard.app.ui.theme.LifeGuardTheme
import kotlinx.coroutines.launch

/**
 * Intercepts routes shared from Google Maps via Android's Share Sheet (ACTION_SEND).
 * Parses origin & destination, calculates the safety polyline corridor via OSRM,
 * and launches RouteDeviationService.
 */
class RouteImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = when {
            intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" -> {
                intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            }
            else -> ""
        }

        setContent {
            LifeGuardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xCC000000) // Dimmed backdrop
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        RouteImportDialog(
                            sharedText = sharedText,
                            onDismiss = { finish() },
                            onStartMonitoring = { startLat, startLng, destLat, destLng, encodedPolyline ->
                                // Save route to session and start RouteDeviationService
                                RouteDeviationService.saveRoute(
                                    this@RouteImportActivity,
                                    encodedPolyline,
                                    startLat, startLng,
                                    destLat, destLng
                                )

                                val serviceIntent = Intent(this@RouteImportActivity, RouteDeviationService::class.java).apply {
                                    action = RouteDeviationService.ACTION_START
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    startForegroundService(serviceIntent)
                                } else {
                                    startService(serviceIntent)
                                }

                                Toast.makeText(
                                    this@RouteImportActivity,
                                    "🛡️ Route Guard Activated! Off-route >150m triggers emergency alerts.",
                                    Toast.LENGTH_LONG
                                ).show()
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteImportDialog(
    sharedText: String,
    onDismiss: () -> Unit,
    onStartMonitoring: (Double, Double, Double, Double, String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var parsedRoute by remember { mutableStateOf<ParsedRouteInfo?>(null) }
    var routeResult by remember { mutableStateOf<RouteResult?>(null) }

    LaunchedEffect(sharedText) {
        if (sharedText.isBlank()) {
            errorMsg = "No route link detected in shared data."
            isLoading = false
            return@LaunchedEffect
        }

        val lastLat = UserSession.getLastLatitude(context)
        val lastLng = UserSession.getLastLongitude(context)
        val fallbackOrigin = if (lastLat != 0.0 && lastLng != 0.0) LatLng(lastLat, lastLng) else null

        val parsed = GoogleMapsUrlParser.parseSharedText(sharedText, fallbackOrigin)
        if (parsed == null || parsed.destination == null) {
            errorMsg = "Could not parse Google Maps destination from shared link."
            isLoading = false
            return@LaunchedEffect
        }

        parsedRoute = parsed

        // Calculate polyline corridor via OSRM
        val origin = parsed.origin ?: fallbackOrigin ?: LatLng(12.9716, 77.5946)
        val route = RouteManager.fetchRoute(origin, parsed.destination, "")
        isLoading = false

        if (route != null) {
            routeResult = route
        } else {
            errorMsg = "Failed to calculate route corridor. Check your internet connection."
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🗺️", fontSize = 36.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "ROUTE RECEIVED FROM GOOGLE MAPS",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))

            when {
                isLoading -> {
                    CircularProgressIndicator(color = Color(0xFF4CAF50), modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Analyzing Google Maps route...", color = Color(0xFF888888), fontSize = 13.sp)
                }

                errorMsg != null -> {
                    Text(
                        text = errorMsg!!,
                        color = Color(0xFFFF5252),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Close", color = Color.White)
                    }
                }

                parsedRoute != null && routeResult != null -> {
                    val p = parsedRoute!!
                    val r = routeResult!!

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF282828)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📍", fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("FROM", color = Color(0xFF888888), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text(p.originName ?: "Current Location", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🎯", fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("DESTINATION", color = Color(0xFF888888), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text(p.destinationName ?: "Selected Destination", color = Color(0xFF4CAF50), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = Color(0xFF3A3A3A))
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Distance: ${r.distanceText}", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                                Text("Est. Time: ${r.durationText}", color = Color(0xFF4285F4), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        "Life Guard will continuously monitor your GPS against this route. Deviation >150m triggers emergency alerts.",
                        color = Color(0xFF888888),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Start monitoring button
                    Button(
                        onClick = {
                            val origin = p.origin ?: LatLng(UserSession.getLastLatitude(context), UserSession.getLastLongitude(context))
                            val dest = p.destination!!
                            onStartMonitoring(
                                origin.latitude, origin.longitude,
                                dest.latitude, dest.longitude,
                                r.encodedPolyline
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("🛡️ START ROUTE GUARD", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Optional 1-tap Google Maps Location Sharing
                    OutlinedButton(
                        onClick = {
                            GoogleMapsSharingHelper.openLocationSharing(context)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4285F4)),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text("📍 Share Live Trip in Google Maps", fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color(0xFF888888))
                    }
                }
            }
        }
    }
}
