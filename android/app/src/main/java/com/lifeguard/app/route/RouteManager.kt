package com.lifeguard.app.route

import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * Handles Google Directions API calls, polyline decoding, and
 * real-time deviation distance calculations.
 */
object RouteManager {

    private const val TAG = "RouteManager"
    private const val DIRECTIONS_BASE_URL = "https://maps.googleapis.com/maps/api/directions/json"

    // Earth radius in meters (WGS-84 mean)
    private const val EARTH_RADIUS_M = 6_371_000.0

    // Deviation threshold — user is "off-route" if farther than this from the polyline
    const val DEVIATION_THRESHOLD_METERS = 150.0

    // How long the user must be continuously off-route before triggering PIN challenge
    const val DEVIATION_TIME_LIMIT_MS = 5 * 60 * 1000L  // 5 minutes

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── Directions API ──

    /**
     * Fetches a driving route from Google Directions API.
     * Returns the decoded polyline as a list of LatLng points, or null on failure.
     */
    suspend fun fetchRoute(
        origin: LatLng,
        destination: LatLng,
        apiKey: String
    ): RouteResult? = withContext(Dispatchers.IO) {
        // Try Google Directions first if key is present
        if (apiKey.isNotBlank() && !apiKey.startsWith("YOUR_")) {
            try {
                val url = "$DIRECTIONS_BASE_URL" +
                        "?origin=${origin.latitude},${origin.longitude}" +
                        "&destination=${destination.latitude},${destination.longitude}" +
                        "&mode=driving" +
                        "&key=$apiKey"

                val request = Request.Builder().url(url).get().build()
                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val directionsResponse = Gson().fromJson(body, DirectionsResponse::class.java)
                        if (directionsResponse.status == "OK" && directionsResponse.routes.isNotEmpty()) {
                            val route = directionsResponse.routes[0]
                            val encodedPolyline = route.overviewPolyline.points
                            val polylinePoints = decodePolyline(encodedPolyline)
                            val leg = route.legs.firstOrNull()
                            val distanceText = leg?.distance?.text ?: "?"
                            val durationText = leg?.duration?.text ?: "?"

                            Log.i(TAG, "Google Route fetched: $distanceText, $durationText, ${polylinePoints.size} points")
                            return@withContext RouteResult(
                                polylinePoints = polylinePoints,
                                distanceText = distanceText,
                                durationText = durationText,
                                encodedPolyline = encodedPolyline
                            )
                        } else {
                            Log.w(TAG, "Google Directions status: ${directionsResponse.status} — falling back to OSRM")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Google Directions API failed: ${e.message} — falling back to OSRM")
            }
        }

        // ── Robust Free Fallback: OSRM (Open Source Routing Machine) ──
        try {
            val osrmUrl = "https://router.project-osrm.org/route/v1/driving/" +
                    "${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}" +
                    "?overview=full&geometries=polyline"

            val request = Request.Builder().url(osrmUrl).get().build()
            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val osrmResponse = Gson().fromJson(body, OsrmResponse::class.java)
                    if (osrmResponse.code == "Ok" && osrmResponse.routes.isNotEmpty()) {
                        val route = osrmResponse.routes[0]
                        val polylinePoints = decodePolyline(route.geometry)

                        val distKm = route.distance / 1000.0
                        val distText = if (distKm < 1.0) "${route.distance.toInt()} m" else String.format("%.1f km", distKm)

                        val mins = (route.duration / 60.0).roundToInt()
                        val durText = if (mins < 60) "$mins mins" else "${mins / 60}h ${mins % 60}m"

                        Log.i(TAG, "OSRM Route fetched: $distText, $durText, ${polylinePoints.size} points")
                        return@withContext RouteResult(
                            polylinePoints = polylinePoints,
                            distanceText = distText,
                            durationText = durText,
                            encodedPolyline = route.geometry
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "OSRM fallback failed", e)
        }

        null
    }

    // ── Polyline Decoding (Google's encoded polyline algorithm) ──

    fun decodePolyline(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            // Decode latitude
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1F) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

            // Decode longitude
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1F) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return poly
    }

    /**
     * Encodes a list of LatLng coordinates into Google's polyline string format.
     */
    fun encodePolyline(points: List<LatLng>): String {
        val result = StringBuilder()
        var lastLat = 0
        var lastLng = 0

        for (point in points) {
            val lat = (point.latitude * 1e5).roundToInt()
            val lng = (point.longitude * 1e5).roundToInt()

            val dLat = lat - lastLat
            val dLng = lng - lastLng

            encodeValue(dLat, result)
            encodeValue(dLng, result)

            lastLat = lat
            lastLng = lng
        }

        return result.toString()
    }

    private fun encodeValue(value: Int, result: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            result.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        result.append((v + 63).toChar())
    }

    // ── Deviation Detection ──

    /**
     * Calculates the minimum distance (in meters) from a point to a polyline.
     * Iterates every segment of the polyline and returns the closest distance.
     */
    fun distanceToPolyline(point: LatLng, polyline: List<LatLng>): Double {
        if (polyline.isEmpty()) return Double.MAX_VALUE
        if (polyline.size == 1) return haversineDistance(point, polyline[0])

        var minDistance = Double.MAX_VALUE
        for (i in 0 until polyline.size - 1) {
            val dist = distanceToSegment(point, polyline[i], polyline[i + 1])
            if (dist < minDistance) {
                minDistance = dist
            }
            // Early exit optimization — if we're within 10m, no need to check further
            if (minDistance < 10.0) break
        }
        return minDistance
    }

    /**
     * Whether the user is currently deviating from the route.
     */
    fun isDeviating(point: LatLng, polyline: List<LatLng>): Boolean {
        return distanceToPolyline(point, polyline) > DEVIATION_THRESHOLD_METERS
    }

    // ── Geometry helpers ──

    /**
     * Haversine distance between two LatLng points in meters.
     */
    fun haversineDistance(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /**
     * Minimum distance from point P to line segment AB, in meters.
     * Projects P onto the line AB and clamps to the segment endpoints.
     */
    private fun distanceToSegment(p: LatLng, a: LatLng, b: LatLng): Double {
        val ap = haversineDistance(a, p)
        val ab = haversineDistance(a, b)
        val bp = haversineDistance(b, p)

        if (ab < 0.01) return ap  // A and B are the same point

        // Use the formula with projection parameter t
        // t = dot(AP, AB) / |AB|^2
        // We approximate using flat-earth for the projection (fine for short segments)
        val dx = Math.toRadians(b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2))
        val dy = Math.toRadians(b.latitude - a.latitude)
        val dxP = Math.toRadians(p.longitude - a.longitude) * cos(Math.toRadians((a.latitude + p.latitude) / 2))
        val dyP = Math.toRadians(p.latitude - a.latitude)

        val dot = dxP * dx + dyP * dy
        val lenSq = dx * dx + dy * dy

        val t = (dot / lenSq).coerceIn(0.0, 1.0)

        // Interpolate to find the nearest point on the segment
        val nearestLat = a.latitude + t * (b.latitude - a.latitude)
        val nearestLng = a.longitude + t * (b.longitude - a.longitude)
        val nearest = LatLng(nearestLat, nearestLng)

        return haversineDistance(p, nearest)
    }
}

// ── Data classes for Directions API response ──

data class RouteResult(
    val polylinePoints: List<LatLng>,
    val distanceText: String,
    val durationText: String,
    val encodedPolyline: String
)

data class DirectionsResponse(
    @SerializedName("status") val status: String,
    @SerializedName("routes") val routes: List<DirectionRoute>
)

data class DirectionRoute(
    @SerializedName("overview_polyline") val overviewPolyline: OverviewPolyline,
    @SerializedName("legs") val legs: List<RouteLeg>
)

data class OverviewPolyline(
    @SerializedName("points") val points: String
)

data class RouteLeg(
    @SerializedName("distance") val distance: TextValue,
    @SerializedName("duration") val duration: TextValue
)

data class TextValue(
    @SerializedName("text") val text: String,
    @SerializedName("value") val value: Int
)

data class OsrmResponse(
    @SerializedName("code") val code: String,
    @SerializedName("routes") val routes: List<OsrmRoute>
)

data class OsrmRoute(
    @SerializedName("geometry") val geometry: String,
    @SerializedName("distance") val distance: Double,
    @SerializedName("duration") val duration: Double
)
