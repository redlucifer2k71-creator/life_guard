package com.lifeguard.app.ui

import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeguard.app.data.AlertTriggerRequest
import com.lifeguard.app.data.PinHasher
import com.lifeguard.app.data.UserSession
import com.lifeguard.app.network.NetworkClient
import com.lifeguard.app.route.RouteDeviationService
import com.lifeguard.app.service.LocationTrackingService
import com.lifeguard.app.timer.TimerCheckInManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "HomeScreen"
private const val DOUBLE_PRESS_WINDOW_MS = 400L
private const val GUARD_MODE_HOLD_MS = 3000L

@Composable
fun HomeScreen(
    onLogout: () -> Unit,
    onNavigateToRoute: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- State ---
    val userId = remember { UserSession.getUserId(context) }
    val userName = remember { UserSession.getFullName(context) }

    // Guard mode gesture states
    var isGuardModeActive by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pendingAlertType by remember { mutableStateOf("") }

    // Timer check-in session state
    var isTimerActive by remember { mutableStateOf(TimerCheckInManager.isActive(context)) }
    var selectedIntervalMin by remember { mutableIntStateOf(10) }
    val intervalOptions = listOf(5, 10, 15, 30, 60)

    // Pulsing animation when Guard Mode is active
    val pulseScale by animateFloatAsState(
        targetValue = if (isGuardModeActive) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            isGuardModeActive -> Color(0xFFFF6D00)   // Orange = Guard Mode on
            else -> Color(0xFFD32F2F)                 // Red = default
        },
        animationSpec = tween(300),
        label = "buttonColor"
    )

    // Double-tap detection state (held in a ref to avoid recomposition)
    var lastTapTime by remember { mutableLongStateOf(0L) }

    // --- PIN Dialog ---
    if (showPinDialog) {
        PinChallengeDialog(
            title = when (pendingAlertType) {
                "GUARD_MODE_RELEASE" -> "🛡️ Guard Mode Released"
                "GUARD_MODE_DOUBLE_PRESS" -> "⚠️ Double Press Detected"
                else -> "🆘 Emergency SOS"
            },
            subtitle = when (pendingAlertType) {
                "GUARD_MODE_RELEASE" -> "Enter your PIN within 30s to cancel SOS"
                "GUARD_MODE_DOUBLE_PRESS" -> "Enter PIN to cancel — or wait to send SOS"
                else -> "Enter PIN to cancel emergency alert"
            },
            countdownSeconds = 30,
            onSuccess = { enteredPin ->
                showPinDialog = false
                scope.launch {
                    val verified = verifyPin(context, enteredPin)
                    if (verified) {
                        isGuardModeActive = false
                        Toast.makeText(context, "✅ PIN accepted — SOS cancelled", Toast.LENGTH_SHORT).show()
                    } else {
                        // Wrong PIN = trigger alert anyway
                        Toast.makeText(context, "❌ Wrong PIN — Sending SOS!", Toast.LENGTH_LONG).show()
                        triggerSosAlert(context, userId, pendingAlertType)
                        isGuardModeActive = false
                    }
                }
            },
            onFailed = {
                showPinDialog = false
                scope.launch {
                    triggerSosAlert(context, userId, pendingAlertType)
                }
                isGuardModeActive = false
            },
            onDismiss = {}  // Cannot be dismissed by back-press
        )
    }

    // --- Main Layout ---
    val scrollState = androidx.compose.foundation.rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {

        // ── Top Header ──
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🛡️", fontSize = 24.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "LIFE GUARD",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 3.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Welcome, $userName",
                color = Color(0xFF888888),
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Status pill
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isGuardModeActive) Color(0x33FF6D00) else Color(0x224CAF50)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📍",
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isGuardModeActive) "Guard Mode ACTIVE" else "Protection Running",
                        color = if (isGuardModeActive) Color(0xFFFF6D00) else Color(0xFF4CAF50),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ── Central SOS Button ──
        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            // Instruction label above button
            Text(
                text = if (isGuardModeActive)
                    "GUARD MODE ON\nRelease to trigger PIN challenge"
                else
                    "Double-tap = Instant SOS\nHold 3s = Guard Mode",
                color = Color(0xFF888888),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(24.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(220.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(buttonColor)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                val now = System.currentTimeMillis()
                                val delta = now - lastTapTime
                                if (delta < DOUBLE_PRESS_WINDOW_MS && lastTapTime != 0L) {
                                    // ── Double Tap = Instant SOS ──
                                    vibrate(context, longArrayOf(0, 100, 80, 200))
                                    pendingAlertType = "GUARD_MODE_DOUBLE_PRESS"
                                    showPinDialog = true
                                    lastTapTime = 0L
                                } else {
                                    lastTapTime = now
                                }
                            },
                            onLongPress = {
                                // ── Long Press (3s) = Activate Guard Mode ──
                                vibrate(context, longArrayOf(0, 50, 50, 50, 50, 200))
                                isGuardModeActive = true
                                Toast.makeText(context, "🛡️ Guard Mode ACTIVATED", Toast.LENGTH_SHORT).show()
                            },
                            onPress = { _ ->
                                val longPressJob = scope.launch {
                                    delay(GUARD_MODE_HOLD_MS)
                                }
                                tryAwaitRelease()
                                longPressJob.cancel()

                                // If guard mode was active and they released → PIN challenge
                                if (isGuardModeActive) {
                                    vibrate(context, longArrayOf(0, 200, 100, 200))
                                    pendingAlertType = "GUARD_MODE_RELEASE"
                                    showPinDialog = true
                                }
                            }
                        )
                    }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "SOS",
                        color = Color.White,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    if (isGuardModeActive) {
                        Text(
                            text = "GUARD MODE",
                            color = Color(0xFFFFE0B2),
                            fontSize = 10.sp,
                            letterSpacing = 2.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Gesture guide cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GestureCard(
                    emoji = "👆👆",
                    label = "Double Tap",
                    desc = "Instant SOS broadcast",
                    modifier = Modifier.weight(1f)
                )
                GestureCard(
                    emoji = "⏱️",
                    label = "Hold 3s",
                    desc = "Enable Guard Mode",
                    modifier = Modifier.weight(1f)
                )
                GestureCard(
                    emoji = "✋",
                    label = "Release",
                    desc = "PIN challenge fires",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── Timer Check-In Card ──
        TimerCheckInCard(
            isActive = isTimerActive,
            selectedInterval = selectedIntervalMin,
            intervalOptions = intervalOptions,
            onIntervalSelected = { selectedIntervalMin = it },
            onStart = {
                TimerCheckInManager.startSession(context, selectedIntervalMin)
                isTimerActive = true
                Toast.makeText(
                    context,
                    "⏱️ Check-in timer started — every ${selectedIntervalMin}min",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onStop = {
                TimerCheckInManager.stopSession(context)
                isTimerActive = false
                Toast.makeText(context, "Timer stopped", Toast.LENGTH_SHORT).show()
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Route Deviation Guard Card ──
        RouteGuardCard(
            isRouteActive = RouteDeviationService.isRouteActive(context),
            onOpenMap = onNavigateToRoute
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Bottom Bar ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "User ID: $userId",
                color = Color(0xFF555555),
                fontSize = 11.sp
            )
            TextButton(onClick = {
                // Stop all foreground services on logout
                context.stopService(Intent(context, LocationTrackingService::class.java))
                context.stopService(Intent(context, RouteDeviationService::class.java))
                TimerCheckInManager.stopSession(context)
                UserSession.logout(context)
                onLogout()
            }) {
                Text("Logout", color = Color(0xFF555555), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun GestureCard(emoji: String, label: String, desc: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E1E1E)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = Color(0xFF888888), fontSize = 10.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TimerCheckInCard(
    isActive: Boolean,
    selectedInterval: Int,
    intervalOptions: List<Int>,
    onIntervalSelected: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) Color(0x1A4CAF50) else Color(0xFF1A1A1A)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⏱️", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Timer Check-In",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isActive) "Active — every ${selectedInterval}min" else "Off",
                            color = if (isActive) Color(0xFF4CAF50) else Color(0xFF666666),
                            fontSize = 11.sp
                        )
                    }
                }

                // Start / Stop switch
                Switch(
                    checked = isActive,
                    onCheckedChange = { checked -> if (checked) onStart() else onStop() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF4CAF50),
                        uncheckedThumbColor = Color(0xFF888888),
                        uncheckedTrackColor = Color(0xFF333333)
                    )
                )
            }

            // Interval selector — only shown when timer is off
            if (!isActive) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Check-in interval",
                    color = Color(0xFF888888),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    intervalOptions.forEach { mins ->
                        val isSelected = mins == selectedInterval
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isSelected) Color(0xFFD32F2F) else Color(0xFF2A2A2A),
                            onClick = { onIntervalSelected(mins) }
                        ) {
                            Text(
                                text = "${mins}m",
                                color = if (isSelected) Color.White else Color(0xFF888888),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteGuardCard(
    isRouteActive: Boolean,
    onOpenMap: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isRouteActive) Color(0x1A4CAF50) else Color(0xFF1A1A1A),
        onClick = onOpenMap
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text("🗺️", fontSize = 20.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Route Deviation Guard",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isRouteActive) "Active — Monitoring deviation (>150m triggers SOS)" else "Tap to set route on Google Maps",
                        color = if (isRouteActive) Color(0xFF4CAF50) else Color(0xFF888888),
                        fontSize = 11.sp
                    )
                }
            }
            Text("→", color = Color(0xFF888888), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Helpers ──

private fun vibrate(context: Context, pattern: LongArray) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    } catch (e: Exception) {
        Log.w(TAG, "Vibration failed: ${e.message}")
    }
}

private fun verifyPin(context: Context, enteredPin: String): Boolean {
    val storedHash = UserSession.getStoredPinHash(context)
    return PinHasher.verify(enteredPin, storedHash)
}

private suspend fun triggerSosAlert(context: Context, userId: Long, alertType: String) {
    try {
        val response = NetworkClient.apiService.triggerAlert(
            AlertTriggerRequest(
                userId = userId,
                alertType = alertType,
                latitude = UserSession.getLastLatitude(context),
                longitude = UserSession.getLastLongitude(context),
                radiusMeters = 500.0
            )
        )
        CoroutineScope(Dispatchers.Main).launch {
            if (response.isSuccessful) {
                val recipientsCount = response.body()?.totalRecipientsNotified ?: 0
                Toast.makeText(
                    context,
                    "🆘 SOS Sent! $recipientsCount nearby helpers notified",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(context, "SOS Failed: ${response.code()}", Toast.LENGTH_LONG).show()
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "SOS trigger error", e)
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, "Network error sending SOS: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
