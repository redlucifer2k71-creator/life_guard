package com.lifeguard.app.ui

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeguard.app.data.PinHasher
import com.lifeguard.app.data.RegisterRequest
import com.lifeguard.app.data.UserSession
import com.lifeguard.app.network.NetworkClient
import com.lifeguard.app.service.LocationTrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun RegisterScreen(onRegisterSuccess: () -> Unit, onGoToLogin: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fullName by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var emergencyContact by remember { mutableStateOf("") }
    var isPinVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingStatusText by remember { mutableStateOf("Creating your account...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
            Surface(
                shape = CircleShape,
                color = Color(0x224CAF50),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("🛡️", fontSize = 32.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "CREATE ACCOUNT",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
            Text(
                text = "Set up your Life Guard 6-Digit Protection",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF182234)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // Full Name
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = {
                            fullName = it
                            errorMessage = null
                        },
                        label = { Text("Full Name") },
                        placeholder = { Text("John Doe") },
                        leadingIcon = { Text("👤", modifier = Modifier.padding(start = 4.dp)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedLabelColor = Color(0xFF4CAF50),
                            unfocusedLabelColor = Color(0xFF94A3B8)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Mobile Number
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = {
                            phoneNumber = it
                            errorMessage = null
                        },
                        label = { Text("Mobile Number (10 digits)") },
                        placeholder = { Text("9876543210") },
                        leadingIcon = { Text("📱", modifier = Modifier.padding(start = 4.dp)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedLabelColor = Color(0xFF4CAF50),
                            unfocusedLabelColor = Color(0xFF94A3B8)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 6-Digit Secret PIN
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            if (it.length <= 6 && it.all { c -> c.isDigit() }) {
                                pin = it
                                errorMessage = null
                            }
                        },
                        label = { Text("Secret PIN (Exactly 6 digits)") },
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
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedLabelColor = Color(0xFF4CAF50),
                            unfocusedLabelColor = Color(0xFF94A3B8)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Confirm 6-Digit Secret PIN
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = {
                            if (it.length <= 6 && it.all { c -> c.isDigit() }) {
                                confirmPin = it
                                errorMessage = null
                            }
                        },
                        label = { Text("Confirm 6-Digit PIN") },
                        placeholder = { Text("••••••") },
                        leadingIcon = { Text("🔑", modifier = Modifier.padding(start = 4.dp)) },
                        singleLine = true,
                        visualTransformation = if (isPinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedLabelColor = Color(0xFF4CAF50),
                            unfocusedLabelColor = Color(0xFF94A3B8)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Emergency Contact
                    OutlinedTextField(
                        value = emergencyContact,
                        onValueChange = {
                            emergencyContact = it
                            errorMessage = null
                        },
                        label = { Text("Emergency Contact Phone (Optional)") },
                        placeholder = { Text("Friend or Parent phone") },
                        leadingIcon = { Text("🆘", modifier = Modifier.padding(start = 4.dp)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedLabelColor = Color(0xFF4CAF50),
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

                    // Register Button
                    Button(
                        onClick = {
                            val cleanDigits = phoneNumber.replace(Regex("[^0-9]"), "")
                            val last10 = if (cleanDigits.length >= 10) cleanDigits.takeLast(10) else cleanDigits

                            when {
                                fullName.trim().length < 2 -> {
                                    errorMessage = "Please enter your full name"
                                }
                                last10.length < 10 -> {
                                    errorMessage = "Please enter a valid 10-digit mobile number"
                                }
                                pin.length != 6 -> {
                                    errorMessage = "Secret PIN must be strictly 6 digits"
                                }
                                pin != confirmPin -> {
                                    errorMessage = "The two PIN entries do not match"
                                }
                                else -> {
                                    errorMessage = null
                                    isLoading = true
                                    loadingStatusText = "Connecting to server..."

                                    val statusJob = scope.launch {
                                        delay(3500)
                                        if (isLoading) {
                                            loadingStatusText = "Waking up cloud server (~15s)..."
                                        }
                                    }

                                    scope.launch {
                                        try {
                                            val response = NetworkClient.apiService.register(
                                                RegisterRequest(
                                                    fullName = fullName.trim(),
                                                    phoneNumber = phoneNumber.trim(),
                                                    pin = pin.trim(),
                                                    emergencyContactPhone = emergencyContact.trim().ifBlank { null }
                                                )
                                            )
                                            statusJob.cancel()
                                            isLoading = false

                                            if (response.isSuccessful && response.body() != null) {
                                                val pinHash = PinHasher.hash(pin.trim())
                                                UserSession.saveUser(context, response.body()!!.user, pinHash)
                                                UserSession.syncFcmTokenWithBackend(context)
                                                startLocationService(context)
                                                Toast.makeText(context, "Account ready! Logged in.", Toast.LENGTH_SHORT).show()
                                                onRegisterSuccess()
                                            } else {
                                                val rawError = response.errorBody()?.string() ?: ""
                                                val parsedDetail = try {
                                                    JSONObject(rawError).optString("detail", "Registration failed (${response.code()})")
                                                } catch (e: Exception) {
                                                    "Registration failed (${response.code()})"
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
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
                            Text("🛡️ CREATE ACCOUNT", fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onGoToLogin) {
                Text(
                    text = "Already have an account? Login",
                    color = Color(0xFF64B5F6),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
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
        android.util.Log.w("RegisterScreen", "Unable to start location service: ${e.message}")
    }
}
