package com.lifeguard.app.data

object PinHasher {
    private const val SALT = "lifeguard_pin_salt_2026"

    fun hash(pin: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest((pin + SALT).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun verify(pin: String, storedHash: String): Boolean = hash(pin) == storedHash
}