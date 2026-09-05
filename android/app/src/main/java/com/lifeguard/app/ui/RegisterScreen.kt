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
import com.lifeguard.app.data.PinHasher
import com.lifeguard.app.data.RegisterRequest
import com.lifeguard.app.data.UserSession
import com.lifeguard.app.network.NetworkClient
import kotlinx.coroutines.launch

@Composable
fun RegisterScreen(onRegisterSuccess: () -> Unit, onGoToLogin: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fullName by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var emergencyContact by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

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
        Text("Create Your Account", color = Color(0xFF4CAF50), fontSize = 16.sp)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = fullName,
            onValueChange = { fullName = it },
            label = { Text("Full Name") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFD32F2F), unfocusedBorderColor = Color.Gray,
                focusedLabelColor = Color(0xFFD32F2F), unfocusedLabelColor = Color.Gray
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

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
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pin = it },
            label = { Text("Secret PIN (4-6 digits)") },
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
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = confirmPin,
            onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) confirmPin = it },
            label = { Text("Confirm PIN") },
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
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = emergencyContact,
            onValueChange = { emergencyContact = it },
            label = { Text("Emergency Contact (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFD32F2F), unfocusedBorderColor = Color.Gray,
                focusedLabelColor = Color(0xFFD32F2F), unfocusedLabelColor = Color.Gray
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                when {
                    fullName.isBlank() -> Toast.makeText(context, "Enter your full name", Toast.LENGTH_SHORT).show()
                    phoneNumber.length < 10 -> Toast.makeText(context, "Enter a valid phone number", Toast.LENGTH_SHORT).show()
                    pin.length < 4 -> Toast.makeText(context, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                    pin != confirmPin -> Toast.makeText(context, "PINs do not match", Toast.LENGTH_SHORT).show()
                    else -> {
                        isLoading = true
                        scope.launch {
                            try {
                                val response = NetworkClient.apiService.register(
                                    RegisterRequest(fullName, phoneNumber, pin,
                                        emergencyContact.ifBlank { null })
                                )
                                isLoading = false
                                if (response.isSuccessful && response.body() != null) {
                                    val pinHash = PinHasher.hash(pin)
                                    UserSession.saveUser(context, response.body()!!.user, pinHash)
                                    Toast.makeText(context, "Registration successful!", Toast.LENGTH_SHORT).show()
                                    onRegisterSuccess()
                                } else {
                                    Toast.makeText(context, "Registration failed: ${response.code()}", Toast.LENGTH_LONG).show()
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
                Text("REGISTER", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onGoToLogin) {
            Text("Already have an account? Login", color = Color(0xFF4CAF50))
        }
    }
}
