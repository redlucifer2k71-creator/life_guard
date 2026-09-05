package com.lifeguard.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeguard.app.data.UserSession

/**
 * 3-screen onboarding flow shown on first launch only.
 * After completion, saves a flag so it never shows again.
 *
 * Pages:
 * 1. "Your safety network" — community SOS concept
 * 2. "Guard Mode" — gesture overview
 * 3. "Always protected" — timer + route deviation overview
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    var currentPage by remember { mutableIntStateOf(0) }

    val pages = listOf(
        OnboardingPage(
            emoji = "🛡️",
            title = "Your Safety Network",
            description = "Life Guard connects you to nearby community members. When you're in danger, your SOS alert reaches everyone around you instantly.",
            gradientColors = listOf(Color(0xFF1A0505), Color(0xFF0D0D0D)),
            accentColor = Color(0xFFD32F2F)
        ),
        OnboardingPage(
            emoji = "✋",
            title = "Guard Mode Gestures",
            description = "Double-tap for instant SOS.\nHold 3 seconds to activate Guard Mode.\nRelease triggers a PIN safety check.\nNo PIN? Help is already on the way.",
            gradientColors = listOf(Color(0xFF1A0D00), Color(0xFF0D0D0D)),
            accentColor = Color(0xFFFF6D00)
        ),
        OnboardingPage(
            emoji = "⏱️",
            title = "Always Protected",
            description = "Set a check-in timer — miss it and SOS fires automatically.\nShare your route and we'll alert your community if you go off-track.",
            gradientColors = listOf(Color(0xFF041A04), Color(0xFF0D0D0D)),
            accentColor = Color(0xFF4CAF50)
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(pages[currentPage].gradientColors)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // Skip button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onComplete) {
                    Text("Skip", color = Color(0xFF666666), fontSize = 14.sp)
                }
            }

            // Page content
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInHorizontally(),
                exit = fadeOut() + slideOutHorizontally()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = pages[currentPage].emoji,
                        fontSize = 88.sp
                    )

                    Spacer(modifier = Modifier.height(40.dp))

                    Text(
                        text = pages[currentPage].title,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = pages[currentPage].description,
                        fontSize = 16.sp,
                        color = Color(0xFFAAAAAA),
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp
                    )
                }
            }

            // Bottom controls
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Page indicator dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 32.dp)
                ) {
                    pages.forEachIndexed { index, _ ->
                        val isActive = index == currentPage
                        val width by animateDpAsState(
                            targetValue = if (isActive) 28.dp else 8.dp,
                            animationSpec = tween(300),
                            label = "dotWidth"
                        )
                        Box(
                            modifier = Modifier
                                .height(8.dp)
                                .width(width)
                                .clip(CircleShape)
                                .background(
                                    if (isActive) pages[currentPage].accentColor
                                    else Color(0xFF333333)
                                )
                        )
                    }
                }

                // Next / Get Started button
                Button(
                    onClick = {
                        if (currentPage < pages.size - 1) {
                            currentPage++
                        } else {
                            onComplete()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = pages[currentPage].accentColor
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = if (currentPage < pages.size - 1) "Next →" else "🛡️ Let's Start",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

private data class OnboardingPage(
    val emoji: String,
    val title: String,
    val description: String,
    val gradientColors: List<Color>,
    val accentColor: Color
)
