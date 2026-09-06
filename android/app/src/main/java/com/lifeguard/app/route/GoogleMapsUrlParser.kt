package com.lifeguard.app.route

import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.lifeguard.app.data.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ParsedRouteInfo(
    val origin: LatLng?,
    val destination: LatLng?,
    val originName: String? = null,
    val destinationName: String? = null,
    val rawUrl: String
)

object GoogleMapsUrlParser {
    private const val TAG = "GoogleMapsUrlParser"

    // Custom HTTP client with manual redirect control to prevent crashes on intent:// or consent redirects
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val URL_PATTERN = Pattern.compile("https?://\\S+")
    private val COORD_PATTERN = Pattern.compile("(-?\\d{1,2}\\.\\d+)[,\\s]+(-?\\d{1,3}\\.\\d+)")
    private val PROTOBUF_WP_PATTERN = Pattern.compile("!1d(-?\\d{1,3}\\.\\d+)!2d(-?\\d{1,2}\\.\\d+)") // lng, lat
    private val PROTOBUF_PLACE_PATTERN = Pattern.compile("!3d(-?\\d{1,2}\\.\\d+)!4d(-?\\d{1,3}\\.\\d+)") // lat, lng
    private val AT_COORD_PATTERN = Pattern.compile("@(-?\\d{1,2}\\.\\d+),(-?\\d{1,3}\\.\\d+)")
    private val ROUTE_TEXT_PATTERN = Pattern.compile("From\\s+([^\\n\\r]+?)\\s+to\\s+([^\\n\\r]+?)(?:\\s+via|\\.|\\n|\\r|$)", Pattern.CASE_INSENSITIVE)

    /**
     * Extracts and unshortens Google Maps URLs from shared text,
     * resolving origin and destination coordinates via multiple strategies.
     */
    suspend fun parseSharedText(
        context: Context,
        text: String,
        fallbackOrigin: LatLng? = null
    ): ParsedRouteInfo? = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Parsing shared text: $text")

            // 1. Extract any URL in the text
            val urlMatcher = URL_PATTERN.matcher(text)
            val extractedUrl = if (urlMatcher.find()) urlMatcher.group() else null

            // 2. Unshorten URL if found
            val resolvedUrl = if (extractedUrl != null) {
                unshortenUrl(extractedUrl)
            } else {
                text
            }
            Log.i(TAG, "Resolved URL: $resolvedUrl")

            var origin: LatLng? = null
            var destination: LatLng? = null
            var originName: String? = null
            var destinationName: String? = null

            // ── Strategy A: Parse route text from shared message body ──
            val textMatcher = ROUTE_TEXT_PATTERN.matcher(text)
            if (textMatcher.find()) {
                val fromText = textMatcher.group(1)?.trim()
                val toText = textMatcher.group(2)?.trim()
                if (!fromText.isNullOrBlank() && !fromText.equals("My Location", ignoreCase = true) && !fromText.equals("Your Location", ignoreCase = true) && !fromText.equals("Current Location", ignoreCase = true)) {
                    originName = fromText
                }
                if (!toText.isNullOrBlank()) {
                    destinationName = toText
                }
            }

            // ── Strategy B: Extract Protobuf Waypoints (!1d<lng>!2d<lat>) ──
            // Google Maps direction URLs encode start and end points as !1d<lng>!2d<lat>
            val wpMatcher = PROTOBUF_WP_PATTERN.matcher(resolvedUrl)
            val wpCoords = mutableListOf<LatLng>()
            while (wpMatcher.find()) {
                val lng = wpMatcher.group(1)?.toDoubleOrNull()
                val lat = wpMatcher.group(2)?.toDoubleOrNull()
                if (lat != null && lng != null) {
                    wpCoords.add(LatLng(lat, lng))
                }
            }
            if (wpCoords.size >= 2) {
                origin = wpCoords[0]
                destination = wpCoords[1]
                Log.i(TAG, "Extracted waypoints from protobuf: origin=$origin, dest=$destination")
            } else if (wpCoords.size == 1 && destination == null) {
                destination = wpCoords[0]
            }

            // ── Strategy C: Extract Protobuf Place (!3d<lat>!4d<lng>) ──
            if (destination == null) {
                val placeMatcher = PROTOBUF_PLACE_PATTERN.matcher(resolvedUrl)
                if (placeMatcher.find()) {
                    val lat = placeMatcher.group(1)?.toDoubleOrNull()
                    val lng = placeMatcher.group(2)?.toDoubleOrNull()
                    if (lat != null && lng != null) {
                        destination = LatLng(lat, lng)
                        Log.i(TAG, "Extracted destination from place protobuf: $destination")
                    }
                }
            }

            // ── Strategy D: Look for /dir/Origin/Destination ──
            if (resolvedUrl.contains("/dir/")) {
                val dirPart = resolvedUrl.substringAfter("/dir/").substringBefore("/@").substringBefore("?")
                val segments = dirPart.split("/").filter { it.isNotBlank() }

                if (segments.size >= 2) {
                    if (origin == null) origin = parseLatLngOrNull(segments[0])
                    if (destination == null) destination = parseLatLngOrNull(segments[1])

                    if (originName == null) originName = cleanPlaceName(segments[0])
                    if (destinationName == null) destinationName = cleanPlaceName(segments[1])
                } else if (segments.size == 1) {
                    if (destination == null) destination = parseLatLngOrNull(segments[0])
                    if (destinationName == null) destinationName = cleanPlaceName(segments[0])
                }
            }

            // ── Strategy E: Look for /place/Name ──
            if (destinationName == null && resolvedUrl.contains("/place/")) {
                val placePart = resolvedUrl.substringAfter("/place/").substringBefore("/@").substringBefore("?")
                destinationName = cleanPlaceName(placePart)
            }

            // ── Strategy F: Query parameters (q, saddr, daddr, origin, destination, ll) ──
            val qParam = extractQueryParam(resolvedUrl, "q")
                ?: extractQueryParam(resolvedUrl, "query")
                ?: extractQueryParam(resolvedUrl, "daddr")
                ?: extractQueryParam(resolvedUrl, "destination")
                ?: extractQueryParam(resolvedUrl, "ll")

            if (destination == null && qParam != null) {
                destination = parseLatLngOrNull(qParam)
                if (destination == null && destinationName == null) {
                    destinationName = cleanPlaceName(qParam)
                }
            }

            val originParam = extractQueryParam(resolvedUrl, "saddr") ?: extractQueryParam(resolvedUrl, "origin")
            if (origin == null && originParam != null) {
                origin = parseLatLngOrNull(originParam)
                if (origin == null && originName == null) {
                    originName = cleanPlaceName(originParam)
                }
            }

            // ── Strategy G: @lat,lng center point ──
            if (destination == null) {
                val atMatcher = AT_COORD_PATTERN.matcher(resolvedUrl)
                if (atMatcher.find()) {
                    val lat = atMatcher.group(1)?.toDoubleOrNull()
                    val lng = atMatcher.group(2)?.toDoubleOrNull()
                    if (lat != null && lng != null) {
                        destination = LatLng(lat, lng)
                        Log.i(TAG, "Extracted destination from @coords: $destination")
                    }
                }
            }

            // ── Strategy H: Any numeric coordinates in text or URL ──
            if (destination == null) {
                val anyCoordMatcher = COORD_PATTERN.matcher(resolvedUrl)
                val coords = mutableListOf<LatLng>()
                while (anyCoordMatcher.find()) {
                    val lat = anyCoordMatcher.group(1)?.toDoubleOrNull()
                    val lng = anyCoordMatcher.group(2)?.toDoubleOrNull()
                    if (lat != null && lng != null && Math.abs(lat) <= 90.0 && Math.abs(lng) <= 180.0) {
                        coords.add(LatLng(lat, lng))
                    }
                }
                if (coords.size >= 2) {
                    if (origin == null) origin = coords[0]
                    destination = coords[1]
                } else if (coords.size == 1) {
                    destination = coords[0]
                }
            }

            // ── Strategy I: Geocode destinationName if coordinates are still missing ──
            if (destination == null && !destinationName.isNullOrBlank()) {
                Log.i(TAG, "Attempting Geocoder lookup for destination: $destinationName")
                destination = geocodeLocationName(context, destinationName)
            }

            // ── Strategy J: Geocode originName if coordinates are missing ──
            if (origin == null && !originName.isNullOrBlank()) {
                Log.i(TAG, "Attempting Geocoder lookup for origin: $originName")
                origin = geocodeLocationName(context, originName)
            }

            // ── Fallback Origin: Device's live GPS position ──
            if (origin == null) {
                origin = fallbackOrigin ?: getLiveDeviceLocation(context)
                if (originName == null) originName = "Current Location"
            }

            if (destination == null) {
                Log.e(TAG, "Could not resolve destination from shared text or URL: $resolvedUrl")
                return@withContext null
            }

            Log.i(TAG, "Successfully parsed route: Origin=$origin ($originName), Dest=$destination ($destinationName)")
            ParsedRouteInfo(
                origin = origin,
                destination = destination,
                originName = originName ?: "Current Location",
                destinationName = destinationName ?: "Destination (${String.format(Locale.US, "%.4f, %.4f", destination.latitude, destination.longitude)})",
                rawUrl = resolvedUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Google Maps shared text", e)
            null
        }
    }

    /**
     * Unshortens shortened URLs (maps.app.goo.gl, goo.gl/maps) by manually
     * following redirects, avoiding crashes on intent:// or consent pages.
     */
    private fun unshortenUrl(initialUrl: String): String {
        var currentUrl = initialUrl
        var hops = 0
        val maxHops = 6

        while (hops < maxHops) {
            hops++
            try {
                val request = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                val code = response.code
                val locationHeader = response.header("Location")
                val responseBody = response.body?.string() ?: ""
                response.close()

                if (code in 300..399 && locationHeader != null) {
                    val nextUrl = locationHeader.trim()
                    Log.d(TAG, "HTTP $code redirect from $currentUrl to $nextUrl")

                    // Handle intent:// redirects from Android Google Maps
                    if (nextUrl.startsWith("intent://") || nextUrl.startsWith("intent:#Intent")) {
                        val fallback = extractIntentFallbackUrl(nextUrl)
                        if (fallback != null) {
                            currentUrl = fallback
                            continue
                        } else {
                            break
                        }
                    }

                    // Handle consent.google.com redirects
                    if (nextUrl.contains("consent.google.com") && nextUrl.contains("continue=")) {
                        val target = extractQueryParam(nextUrl, "continue")
                        if (target != null) {
                            currentUrl = URLDecoder.decode(target, "UTF-8")
                            continue
                        }
                    }

                    currentUrl = if (nextUrl.startsWith("http://") || nextUrl.startsWith("https://")) {
                        nextUrl
                    } else {
                        "https://www.google.com$nextUrl"
                    }
                    continue
                }

                // If 200 OK, check for HTML meta refresh or canonical links
                if (code == 200) {
                    val metaRefresh = findMetaRefreshUrl(responseBody)
                    if (metaRefresh != null) {
                        currentUrl = metaRefresh
                        continue
                    }
                    val canonical = findCanonicalUrl(responseBody)
                    if (canonical != null && canonical.contains("/maps/")) {
                        currentUrl = canonical
                        continue
                    }
                }

                // No further redirect found
                break
            } catch (e: Exception) {
                Log.w(TAG, "Redirect resolution stopped at hop $hops: ${e.message}")
                break
            }
        }
        return currentUrl
    }

    private fun extractIntentFallbackUrl(intentUri: String): String? {
        return try {
            if (intentUri.contains("S.browser_fallback_url=")) {
                val encoded = intentUri.substringAfter("S.browser_fallback_url=").substringBefore(";")
                URLDecoder.decode(encoded, "UTF-8")
            } else if (intentUri.contains("data=")) {
                val encoded = intentUri.substringAfter("data=").substringBefore(";")
                URLDecoder.decode(encoded, "UTF-8")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun findMetaRefreshUrl(html: String): String? {
        val pattern = Pattern.compile("<meta[^>]*content=[\"'][^\"']*url=([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(html)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun findCanonicalUrl(html: String): String? {
        val pattern = Pattern.compile("<link[^>]*rel=[\"']canonical[\"'][^>]*href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(html)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun cleanPlaceName(raw: String): String {
        return try {
            val decoded = URLDecoder.decode(raw, "UTF-8").replace("+", " ")
            decoded.substringBefore("@").trim()
        } catch (e: Exception) {
            raw.replace("+", " ")
        }
    }

    private fun parseLatLngOrNull(value: String): LatLng? {
        val matcher = COORD_PATTERN.matcher(value)
        if (matcher.find()) {
            val lat = matcher.group(1)?.toDoubleOrNull()
            val lng = matcher.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null && Math.abs(lat) <= 90.0 && Math.abs(lng) <= 180.0) {
                return LatLng(lat, lng)
            }
        }
        return null
    }

    private fun extractQueryParam(url: String, param: String): String? {
        return try {
            val query = url.substringAfter("?", "")
            if (query.isBlank()) return null
            query.split("&").firstOrNull { it.startsWith("$param=") }?.substringAfter("$param=")
        } catch (e: Exception) {
            null
        }
    }

    private fun geocodeLocationName(context: Context, locationName: String): LatLng? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocationName(locationName, 1)
            if (!results.isNullOrEmpty()) {
                val loc = results[0]
                LatLng(loc.latitude, loc.longitude)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder lookup failed for '$locationName': ${e.message}")
            null
        }
    }

    /**
     * Tries to fetch immediate device GPS fix if available
     */
    private suspend fun getLiveDeviceLocation(context: Context): LatLng? {
        // First check session cache
        val sessLat = UserSession.getLastLatitude(context)
        val sessLng = UserSession.getLastLongitude(context)
        if (sessLat != 0.0 && sessLng != 0.0) {
            return LatLng(sessLat, sessLng)
        }

        // Second check FusedLocationProviderClient
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val lastLoc = client.lastLocation.await()
            if (lastLoc != null) {
                UserSession.saveLocation(context, lastLoc.latitude, lastLoc.longitude)
                return LatLng(lastLoc.latitude, lastLoc.longitude)
            }
        } catch (e: Exception) {
            Log.w(TAG, "FusedLocationProviderClient await failed: ${e.message}")
        }

        // Third check LocationManager
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                UserSession.saveLocation(context, loc.latitude, loc.longitude)
                return LatLng(loc.latitude, loc.longitude)
            }
        } catch (e: Exception) {
            Log.w(TAG, "LocationManager fallback failed: ${e.message}")
        }

        return null
    }
}
