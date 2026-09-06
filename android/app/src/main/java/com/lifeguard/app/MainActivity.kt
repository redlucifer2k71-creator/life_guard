package com.lifeguard.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.firebase.messaging.FirebaseMessaging
import com.lifeguard.app.data.UserSession
import com.lifeguard.app.fcm.FcmTokenRequest
import com.lifeguard.app.fcm.LifeGuardFirebaseService
import com.lifeguard.app.network.NetworkClient
import com.lifeguard.app.service.LocationTrackingService
import com.lifeguard.app.ui.*
import com.lifeguard.app.ui.theme.LifeGuardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ── Navigation Route Constants ──
private object Routes {
    const val SPLASH     = "splash"
    const val ONBOARDING = "onboarding"
    const val LOGIN      = "login"
    const val REGISTER   = "register"
    const val HOME       = "home"
    const val ROUTE      = "route"
}

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted =
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (locationGranted) {
            startLocationService()
        } else {
            Toast.makeText(
                this,
                "Location permission is required for Life Guard protection",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize dynamic backend URL from user session
        NetworkClient.setBaseUrl(UserSession.getServerUrl(this))

        // Create notification channels early
        LifeGuardFirebaseService.createNotificationChannels(this)

        // Register FCM token with backend if already logged in
        if (UserSession.isLoggedIn(this)) {
            val userId = UserSession.getUserId(this)
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                UserSession.saveFcmToken(this, token)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        NetworkClient.apiService.registerFcmToken(
                            FcmTokenRequest(userId = userId, fcmToken = token)
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("MainActivity", "FCM token upload failed: ${e.message}")
                    }
                }
            }
        }

        // Request exact alarm permission for timer check-ins (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                startActivity(intent)
            }
        }

        setContent {
            LifeGuardTheme {
                LifeGuardApp(
                    isLoggedIn = UserSession.isLoggedIn(this),
                    hasSeenOnboarding = UserSession.hasSeenOnboarding(this),
                    onLocationPermissionNeeded = { checkAndRequestPermissions() }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val hasFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val isGpsEnabled = locationManager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true
        if (!isGpsEnabled) {
            Toast.makeText(this, "⚠️ Please turn ON device GPS for Life Guard tracking", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (e: Exception) {
                // Ignore if settings intent unavailable
            }
        }

        val hasSms = ContextCompat.checkSelfPermission(
            this, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            startLocationService()
        }

        if (!hasFine || !hasCoarse || !hasSms) {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.SEND_SMS
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun startLocationService() {
        val intent = Intent(this, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun LifeGuardApp(
    isLoggedIn: Boolean,
    hasSeenOnboarding: Boolean,
    onLocationPermissionNeeded: () -> Unit
) {
    val context = LocalContext.current
    val navController = rememberNavController()

    // Determine start destination:
    // Always show splash first, then navigate to correct screen from there
    val startDestination = Routes.SPLASH

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            fadeIn(animationSpec = tween(300)) +
            slideInHorizontally(animationSpec = tween(300)) { it / 8 }
        },
        exitTransition = {
            fadeOut(animationSpec = tween(200))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) +
            slideInHorizontally(animationSpec = tween(300)) { -it / 8 }
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(200)) +
            slideOutHorizontally(animationSpec = tween(300)) { it / 8 }
        }
    ) {

        // ── Splash Screen ──
        composable(Routes.SPLASH) {
            SplashScreen(
                onComplete = {
                    val next = when {
                        !hasSeenOnboarding -> Routes.ONBOARDING
                        isLoggedIn -> Routes.HOME
                        else -> Routes.LOGIN
                    }
                    navController.navigate(next) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        // ── Onboarding (first launch only) ──
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onComplete = {
                    UserSession.markOnboardingSeen(context)
                    val next = if (isLoggedIn) Routes.HOME else Routes.LOGIN
                    navController.navigate(next) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        // ── Login ──
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    onLocationPermissionNeeded()
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onGoToRegister = {
                    navController.navigate(Routes.REGISTER)
                }
            )
        }

        // ── Register ──
        composable(Routes.REGISTER) {
            RegisterScreen(
                onRegisterSuccess = {
                    onLocationPermissionNeeded()
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onGoToLogin = {
                    navController.popBackStack()
                }
            )
        }

        // ── Home (Guard Mode + SOS + Timer + Route card) ──
        composable(Routes.HOME) {
            onLocationPermissionNeeded()
            HomeScreen(
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onNavigateToRoute = {
                    navController.navigate(Routes.ROUTE)
                }
            )
        }

        // ── Route Deviation Guard + Google Maps ──
        composable(Routes.ROUTE) {
            RouteScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
