package com.lifeguard.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeguard.app.data.LoginRequest
import com.lifeguard.app.data.PinHasher
import com.lifeguard.app.data.UserSession
import com.lifeguard.app.network.NetworkClient
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, onGoToRegister: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phoneNumber by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }
    var serverUrlInput by remember { mutableStateOf(UserSession.getServerUrl(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("LIFE GUARD", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Welcome Back", color = Color(0xFF4CAF50), fontSize = 16.sp)
        Spacer(modifier = Modifier.height(48.dp))

        OutlinedTextField(
            value = phoneNumber,
            onValueChange = { phoneNumber = it },
            label = { Text("Phone Number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFD32F2F), unfocusedBorderColor = Color.Gray,
                focusedLabelColor = Color(0xFFD32F2F), unfocusedLabelColor = Color.Gray
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pin = it },
            label = { Text("Secret PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFD32F2F), unfocusedBorderColor = Color.Gray,
                focusedLabelColor = Color(0xFFD32F2F), unfocusedLabelColor = Color.Gray
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                when {
                    phoneNumber.length < 10 -> Toast.makeText(context, "Enter a valid phone number", Toast.LENGTH_SHORT).show()
                    pin.length < 4 -> Toast.makeText(context, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                    else -> {
                        isLoading = true
                        scope.launch {
                            try {
                                val response = NetworkClient.apiService.login(
                                    LoginRequest(phoneNumber, pin)
                                )
                                isLoading = false
                                if (response.isSuccessful && response.body() != null) {
                                    val pinHash = PinHasher.hash(pin)
                                    UserSession.saveUser(context, response.body()!!.user, pinHash)
                                    Toast.makeText(context, "Login successful!", Toast.LENGTH_SHORT).show()
                                    onLoginSuccess()
                                } else {
                                    Toast.makeText(context, "Invalid phone number or PIN", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                isLoading = false
                                Toast.makeText(context, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            },
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            } else {
                Text("LOGIN", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onGoToRegister) {
            Text("Don't have an account? Register", color = Color(0xFF4CAF50))
        }

        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = { 
            serverUrlInput = UserSession.getServerUrl(context)
            showServerDialog = true 
        }) {
            Text(
                "⚙️ Server: ${UserSession.getServerUrl(context)}",
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
        }

        if (showServerDialog) {
            AlertDialog(
                onDismissRequest = { showServerDialog = false },
                title = { Text("Backend Server URL", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            "Enter the backend address (Wi-Fi IP, Cloud URL, or Emulator):",
                            color = Color(0xFFCCCCCC),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = serverUrlInput,
                            onValueChange = { serverUrlInput = it },
                            label = { Text("Server URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { serverUrlInput = "http://10.30.183.220:8000" }) {
                                Text("Wi-Fi IP", fontSize = 11.sp, color = Color(0xFF4CAF50))
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
}
