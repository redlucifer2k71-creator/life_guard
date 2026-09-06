package com.lifeguard.app.route

import android.util.Log
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val URL_PATTERN = Pattern.compile("https?://\\S+")
    private val COORD_PATTERN = Pattern.compile("(-?\\d{1,2}\\.\\d+),\\s*(-?\\d{1,3}\\.\\d+)")

    /**
     * Extracts and unshortens Google Maps URLs from shared text,
     * resolving origin and destination coordinates.
     */
    suspend fun parseSharedText(text: String, fallbackOrigin: LatLng? = null): ParsedRouteInfo? = withContext(Dispatchers.IO) {
        try {
            val matcher = URL_PATTERN.matcher(text)
            if (!matcher.find()) {
                Log.w(TAG, "No URL found in shared text: $text")
                return@withContext null
            }

            val extractedUrl = matcher.group()
            val resolvedUrl = unshortenUrl(extractedUrl)
            Log.i(TAG, "Resolved URL: $resolvedUrl")

            var origin: LatLng? = null
            var destination: LatLng? = null
            var originName: String? = null
            var destinationName: String? = null

            // Strategy 1: Look for /dir/Origin/Destination
            if (resolvedUrl.contains("/dir/")) {
                val dirPart = resolvedUrl.substringAfter("/dir/").substringBefore("/@").substringBefore("?")
                val segments = dirPart.split("/").filter { it.isNotBlank() }

                if (segments.size >= 2) {
                    origin = parseLatLngOrNull(segments[0])
                    destination = parseLatLngOrNull(segments[1])

                    if (origin == null) originName = URLDecoder.decode(segments[0], "UTF-8").replace("+", " ")
                    if (destination == null) destinationName = URLDecoder.decode(segments[1], "UTF-8").replace("+", " ")
                } else if (segments.size == 1) {
                    destination = parseLatLngOrNull(segments[0])
                    if (destination == null) destinationName = URLDecoder.decode(segments[0], "UTF-8").replace("+", " ")
                }
            }

            // Strategy 2: Look for query parameters saddr & daddr or origin & destination
            if (origin == null && (resolvedUrl.contains("saddr=") || resolvedUrl.contains("origin="))) {
                val saddr = extractQueryParam(resolvedUrl, "saddr") ?: extractQueryParam(resolvedUrl, "origin")
                if (saddr != null) {
                    origin = parseLatLngOrNull(saddr)
                    if (origin == null) originName = URLDecoder.decode(saddr, "UTF-8").replace("+", " ")
                }
            }

            if (destination == null && (resolvedUrl.contains("daddr=") || resolvedUrl.contains("destination=") || resolvedUrl.contains("q="))) {
                val daddr = extractQueryParam(resolvedUrl, "daddr") 
                    ?: extractQueryParam(resolvedUrl, "destination")
                    ?: extractQueryParam(resolvedUrl, "q")
                if (daddr != null) {
                    destination = parseLatLngOrNull(daddr)
                    if (destination == null) destinationName = URLDecoder.decode(daddr, "UTF-8").replace("+", " ")
                }
            }

            // Strategy 3: Fallback regex on all coordinates in the URL
            if (origin == null && destination == null) {
                val coordMatcher = COORD_PATTERN.matcher(resolvedUrl)
                val coords = mutableListOf<LatLng>()
                while (coordMatcher.find()) {
                    val lat = coordMatcher.group(1)?.toDoubleOrNull()
                    val lng = coordMatcher.group(2)?.toDoubleOrNull()
                    if (lat != null && lng != null) {
                        coords.add(LatLng(lat, lng))
                    }
                }
                if (coords.size >= 2) {
                    origin = coords[0]
                    destination = coords[1]
                } else if (coords.size == 1) {
                    destination = coords[0]
                }
            }

            // If origin is missing, use user's current GPS position as the route origin
            if (origin == null && fallbackOrigin != null) {
                origin = fallbackOrigin
                if (originName == null) originName = "Current Location"
            }

            if (destination == null) {
                Log.e(TAG, "Failed to resolve destination from URL: $resolvedUrl")
                return@withContext null
            }

            ParsedRouteInfo(
                origin = origin,
                destination = destination,
                originName = originName ?: "Start Point",
                destinationName = destinationName ?: "Destination",
                rawUrl = resolvedUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Google Maps shared text", e)
            null
        }
    }

    private fun unshortenUrl(url: String): String {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            val finalUrl = response.request.url.toString()
            response.close()
            finalUrl
        } catch (e: Exception) {
            Log.w(TAG, "Could not unshorten URL, using original: ${e.message}")
            url
        }
    }

    private fun parseLatLngOrNull(value: String): LatLng? {
        val matcher = COORD_PATTERN.matcher(value)
        if (matcher.find()) {
            val lat = matcher.group(1)?.toDoubleOrNull()
            val lng = matcher.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null) {
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
}
