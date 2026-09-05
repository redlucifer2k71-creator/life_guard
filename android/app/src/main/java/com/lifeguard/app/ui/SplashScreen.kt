package com.lifeguard.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.delay

/**
 * Animated splash screen shown for 2.5 seconds on cold start.
 *
 * Animation sequence:
 * 1. Logo emoji scales up from 0 → 1 with an overshoot spring (0–700ms)
 * 2. App name fades in (400–900ms)
 * 3. Tagline fades in (700–1200ms)
 * 4. Pulse ring expands outward (continuous while visible)
 * 5. Everything fades out and onComplete() is called
 */
@Composable
fun SplashScreen(onComplete: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    var startFadeOut by remember { mutableStateOf(false) }

    // Logo scale — spring with overshoot
    val logoScale by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "logoScale"
    )

    // App name alpha
    val titleAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(600, delayMillis = 300),
        label = "titleAlpha"
    )

    // Tagline alpha
    val taglineAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(600, delayMillis = 600),
        label = "taglineAlpha"
    )

    // Full-screen fade out
    val screenAlpha by animateFloatAsState(
        targetValue = if (startFadeOut) 0f else 1f,
        animationSpec = tween(400),
        label = "screenAlpha",
        finishedListener = { onComplete() }
    )

    // Pulse ring animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseRing"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(2200)
        startFadeOut = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(screenAlpha)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1A0A0A), Color(0xFF0D0D0D)),
                    radius = 1200f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Pulse ring
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(pulseScale)
                .alpha(if (startAnimation) pulseAlpha else 0f)
                .background(Color(0x33D32F2F), shape = androidx.compose.foundation.shape.CircleShape)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo emoji
            Text(
                text = "🛡️",
                fontSize = 80.sp,
                modifier = Modifier.scale(logoScale)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // App name
            Text(
                text = "LIFE GUARD",
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 6.sp,
                color = Color.White,
                modifier = Modifier.alpha(titleAlpha)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Tagline
            Text(
                text = "Community Safety Network",
                fontSize = 14.sp,
                color = Color(0xFF888888),
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(taglineAlpha)
            )
        }

        // Version at bottom
        Text(
            text = "v1.0",
            fontSize = 11.sp,
            color = Color(0xFF333333),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .alpha(titleAlpha)
        )
    }
}
