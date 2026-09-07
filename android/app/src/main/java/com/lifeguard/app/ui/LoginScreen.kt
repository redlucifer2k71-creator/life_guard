package com.lifeguard.app.ui

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeguard.app.data.LoginRequest
import com.lifeguard.app.data.PinHasher
import com.lifeguard.app.data.UserSession
import com.lifeguard.app.network.NetworkClient
import com.lifeguard.app.service.LocationTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, onGoToRegister: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phoneNumber by remember { mutableStateOf(UserSession.getLastKnownPhone(context)) }
    var pin by remember { mutableStateOf("") }
    var isPinVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingStatusText by remember { mutableStateOf("Connecting to server...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showServerDialog by remember { mutableStateOf(false) }
    var serverUrlInput by remember { mutableStateOf(UserSession.getServerUrl(context)) }

    // Pre-warm backend server in background on launch
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                NetworkClient.apiService.pingServer()
            } catch (e: Exception) {
                // Silently waking up Render instance
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF090D16), Color(0xFF1E111A))
                )
            )
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Shield Emblem Header
            Surface(
                shape = CircleShape,
                color = Color(0x22EF4444),
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("🛡️", fontSize = 36.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "LIFE GUARD",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
            Text(
                text = "Autonomous Safety & Emergency Response",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF182234)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "Secure Login",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Enter your registered mobile number & 6-digit PIN",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Mobile Number Field
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = {
                            phoneNumber = it
                            errorMessage = null
                        },
                        label = { Text("Mobile Number") },
                        placeholder = { Text("9876543210") },
                        leadingIcon = { Text("📱", modifier = Modifier.padding(start = 4.dp)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFEF4444),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedLabelColor = Color(0xFFEF4444),
                            unfocusedLabelColor = Color(0xFF94A3B8)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // 6-Digit Secret PIN Field
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            if (it.length <= 6 && it.all { c -> c.isDigit() }) {
                                pin = it
                                errorMessage = null
                            }
                        },
                        label = { Text("Secret PIN (6 digits)") },
                        placeholder = { Text("••••••") },
                        leadingIcon = { Text("🔒", modifier = Modifier.padding(start = 4.dp)) },
                        trailingIcon = {
                            IconButton(onClick = { isPinVisible = !isPinVisible }) {
                                Text(if (isPinVisible) "👁️" else "🙈", fontSize = 14.sp)
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (isPinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFEF4444),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedLabelColor = Color(0xFFEF4444),
                            unfocusedLabelColor = Color(0xFF94A3B8)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Error Banner
                    AnimatedVisibility(visible = errorMessage != null) {
                        Surface(
                            color = Color(0x33EF4444),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            Text(
                                text = "⚠️ ${errorMessage ?: ""}",
                                color = Color(0xFFFF8A80),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Login Button
                    Button(
                        onClick = {
                            val cleanDigits = phoneNumber.replace(Regex("[^0-9]"), "")
                            val last10Entered = if (cleanDigits.length >= 10) cleanDigits.takeLast(10) else cleanDigits

                            when {
                                last10Entered.length < 10 -> {
                                    errorMessage = "Please enter a valid 10-digit mobile number"
                                }
                                pin.length != 6 -> {
                                    errorMessage = "Secret PIN must be strictly 6 digits"
                                }
                                else -> {
                                    errorMessage = null

                                    // ⚡ FAST-PASS INSTANT LOGIN CHECK (< 0.05s)
                                    val last10Known = UserSession.getLastKnownPhone(context).replace(Regex("[^0-9]"), "").takeLast(10)
                                    val knownPinHash = UserSession.getLastKnownPinHash(context)

                                    if (last10Entered == last10Known && knownPinHash.isNotBlank() && PinHasher.verify(pin, knownPinHash)) {
                                        UserSession.setLoggedIn(context, true)
                                        UserSession.syncFcmTokenWithBackend(context)
                                        startLocationService(context)
                                        Toast.makeText(context, "⚡ Fast-Pass Login: Welcome back!", Toast.LENGTH_SHORT).show()
                                        onLoginSuccess()

                                        // Background silent server refresh
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                NetworkClient.apiService.login(LoginRequest(phoneNumber, pin))
                                            } catch (_: Exception) {}
                                        }
                                        return@Button
                                    }

                                    // Standard Cloud Login
                                    isLoading = true
                                    loadingStatusText = "Connecting to server..."

                                    // Launch progress timer for cold start feedback
                                    val statusJob = scope.launch {
                                        delay(3500)
                                        if (isLoading) {
                                            loadingStatusText = "Waking up cloud server (~15s)..."
                                        }
                                    }

                                    scope.launch {
                                        try {
                                            val response = NetworkClient.apiService.login(
                                                LoginRequest(phoneNumber.trim(), pin.trim())
                                            )
                                            statusJob.cancel()
                                            isLoading = false

                                            if (response.isSuccessful && response.body() != null) {
                                                val pinHash = PinHasher.hash(pin)
                                                UserSession.saveUser(context, response.body()!!.user, pinHash)
                                                UserSession.syncFcmTokenWithBackend(context)
                                                startLocationService(context)
                                                Toast.makeText(context, "Login successful!", Toast.LENGTH_SHORT).show()
                                                onLoginSuccess()
                                            } else {
                                                val rawError = response.errorBody()?.string() ?: ""
                                                val parsedDetail = try {
                                                    JSONObject(rawError).optString("detail", "Invalid mobile number or secret PIN.")
                                                } catch (e: Exception) {
                                                    "Invalid mobile number or secret PIN."
                                                }
                                                errorMessage = parsedDetail
                                            }
                                        } catch (e: Exception) {
                                            statusJob.cancel()
                                            isLoading = false
                                            errorMessage = "Server connection timeout (${e.message}). Please check internet or try again."
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (isLoading) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(loadingStatusText, fontSize = 12.sp, color = Color.White)
                            }
                        } else {
                            Text("⚡ LOGIN", fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Switch to Register
            TextButton(onClick = onGoToRegister) {
                Text(
                    text = "Don't have an account? Register with 6-Digit PIN",
                    color = Color(0xFF4CAF50),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Server configuration
            TextButton(onClick = {
                serverUrlInput = UserSession.getServerUrl(context)
                showServerDialog = true
            }) {
                Text(
                    text = "⚙️ Backend: ${UserSession.getServerUrl(context)}",
                    color = Color(0xFF64748B),
                    fontSize = 10.sp
                )
            }
        }
    }

    if (showServerDialog) {
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            title = { Text("Backend Server Configuration", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Default: https://life-guard.onrender.com\nOr enter your custom IP:",
                        color = Color(0xFFCCCCCC),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { serverUrlInput = it },
                        label = { Text("Server URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { serverUrlInput = "https://life-guard.onrender.com" }) {
                            Text("Cloud URL", fontSize = 11.sp, color = Color(0xFF4CAF50))
                        }
                        TextButton(onClick = { serverUrlInput = "http://10.0.2.2:8000" }) {
                            Text("Emulator", fontSize = 11.sp, color = Color(0xFF4CAF50))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (serverUrlInput.isNotBlank()) {
                            UserSession.setServerUrl(context, serverUrlInput.trim())
                            showServerDialog = false
                            Toast.makeText(context, "Server updated!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF222222)
        )
    }
}

private fun startLocationService(context: android.content.Context) {
    try {
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    } catch (e: Exception) {
        android.util.Log.w("LoginScreen", "Unable to start location service: ${e.message}")
    }
}
