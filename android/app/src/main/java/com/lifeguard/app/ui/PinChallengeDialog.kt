package com.lifeguard.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * Full-screen PIN challenge dialog.
 * Shows a countdown timer. If the user fails or time runs out, [onFailed] fires.
 * If user enters correct PIN, [onSuccess] fires.
 *
 * @param title       e.g. "Guard Mode Release Detected"
 * @param subtitle    e.g. "Enter PIN to cancel SOS"
 * @param countdownSeconds Seconds before auto-fail (0 = no countdown)
 * @param onSuccess   Called with the entered PIN string — caller validates it
 * @param onFailed    Called when countdown expires or user taps "I'm in danger"
 * @param onDismiss   Called when dialog is dismissed (cancel / back press)
 */
@Composable
fun PinChallengeDialog(
    title: String,
    subtitle: String,
    countdownSeconds: Int = 30,
    onSuccess: (pin: String) -> Unit,
    onFailed: () -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var timeLeft by remember { mutableIntStateOf(countdownSeconds) }
    var shakeError by remember { mutableStateOf(false) }

    // Countdown timer
    LaunchedEffect(Unit) {
        if (countdownSeconds > 0) {
            while (timeLeft > 0) {
                delay(1000L)
                timeLeft--
            }
            onFailed()
        }
    }

    // Shake animation for wrong PIN
    val shakeOffset by animateFloatAsState(
        targetValue = if (shakeError) 10f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy),
        finishedListener = { shakeError = false },
        label = "shake"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xEE0A0A0A)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .offset(x = shakeOffset.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Countdown ring
                    if (countdownSeconds > 0) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { timeLeft.toFloat() / countdownSeconds.toFloat() },
                                modifier = Modifier.size(72.dp),
                                color = if (timeLeft > 10) Color(0xFFFF9800) else Color(0xFFD32F2F),
                                strokeWidth = 6.dp,
                                trackColor = Color(0xFF333333)
                            )
                            Text(
                                text = "$timeLeft",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Text(
                        text = title,
                        color = Color(0xFFD32F2F),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = subtitle,
                        color = Color(0xFFAAAAAA),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    // PIN dots display
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(bottom = 20.dp)
                    ) {
                        repeat(6) { index ->
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(
                                        color = if (index < pin.length) Color(0xFFD32F2F) else Color(0xFF444444),
                                        shape = RoundedCornerShape(50)
                                    )
                            )
                        }
                    }

                    // Numeric keypad
                    val keys = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("⌫", "0", "✓")
                    )

                    keys.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            row.forEach { key ->
                                TextButton(
                                    onClick = {
                                        when (key) {
                                            "⌫" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                            "✓" -> {
                                                if (pin.length == 6) {
                                                    onSuccess(pin)
                                                } else {
                                                    shakeError = true
                                                    pin = ""
                                                }
                                            }
                                            else -> {
                                                if (pin.length < 6) {
                                                    val nextPin = pin + key
                                                    pin = nextPin
                                                    if (nextPin.length == 6) {
                                                        onSuccess(nextPin)
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(64.dp),
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = key,
                                        color = Color.White,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Emergency override — explicitly trigger SOS
                    TextButton(onClick = onFailed) {
                        Text(
                            text = "🆘  I'm in danger — Send SOS Now",
                            color = Color(0xFFD32F2F),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
