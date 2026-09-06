package com.lifeguard.app.route

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

object GoogleMapsSharingHelper {
    private const val TAG = "GoogleMapsSharingHelper"
    private const val MAPS_PACKAGE = "com.google.android.apps.maps"

    /**
     * Opens Google Maps native "Location Sharing" screen so the user can
     * directly select and stream their live location to their emergency contact.
     */
    fun openLocationSharing(context: Context) {
        try {
            val uri = Uri.parse("https://www.google.com/maps/@?api=1&map_action=location_sharing")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(MAPS_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch Maps location sharing via package, falling back: ${e.message}")
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (ex: Exception) {
                Toast.makeText(context, "Could not open Google Maps", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Launches Google Maps Navigation directly to the route destination.
     */
    fun startNavigation(context: Context, destLat: Double, destLng: Double) {
        try {
            val uri = Uri.parse("google.navigation:q=$destLat,$destLng&mode=d")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(MAPS_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch Google Maps navigation: ${e.message}")
            val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$destLat,$destLng")
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }

    /**
     * Launches Google Maps app so user can search their route.
     */
    fun openGoogleMaps(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(MAPS_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Could not launch Google Maps", Toast.LENGTH_SHORT).show()
        }
    }
}
