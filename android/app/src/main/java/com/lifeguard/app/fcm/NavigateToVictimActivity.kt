package com.lifeguard.app.fcm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * Transparent pass-through activity launched by the notification "Navigate" action.
 * Immediately opens Google Maps directions to the victim's GPS coordinates and finishes.
 *
 * This is a separate activity (not HomeScreen) so the Maps deeplink
 * works reliably even when the app is in background / killed.
 */
class NavigateToVictimActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val latitude = intent.getDoubleExtra(LifeGuardFirebaseService.EXTRA_LATITUDE, 0.0)
        val longitude = intent.getDoubleExtra(LifeGuardFirebaseService.EXTRA_LONGITUDE, 0.0)
        val victimName = intent.getStringExtra(LifeGuardFirebaseService.EXTRA_VICTIM_NAME) ?: "Victim"

        if (latitude == 0.0 && longitude == 0.0) {
            Toast.makeText(this, "Location not available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Open Google Maps with walking/driving directions to victim
        val mapsUri = Uri.parse(
            "https://www.google.com/maps/dir/?api=1&destination=$latitude,$longitude&travelmode=driving"
        )
        val mapsIntent = Intent(Intent.ACTION_VIEW, mapsUri).apply {
            // Open in Google Maps app if installed, otherwise browser
            setPackage("com.google.android.apps.maps")
        }

        if (mapsIntent.resolveActivity(packageManager) != null) {
            startActivity(mapsIntent)
        } else {
            // Fallback: open in browser if Maps not installed
            startActivity(Intent(Intent.ACTION_VIEW, mapsUri))
        }

        Toast.makeText(this, "Navigating to $victimName...", Toast.LENGTH_SHORT).show()
        finish()
    }
}
