package com.lumovault.lumovault.features.gallery.data.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Reverse geocoding result with city, state, and country.
 */
@Serializable
data class GeoResult(
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
) {
    val displayName: String
        get() = buildList {
            if (!city.isNullOrEmpty()) add(city)
            if (!state.isNullOrEmpty() && state != city) add(state)
            if (!country.isNullOrEmpty()) add(country)
        }.joinToString(", ")

    val isEmpty: Boolean get() = displayName.isEmpty()
}

/**
 * Reverse geocoding service using Nominatim (OpenStreetMap) with disk cache.
 *
 * Ported from Flutter `lib/features/gallery/data/repositories/geocoding_service.dart`.
 *
 * Results are cached to avoid repeated API calls. Rate-limited to 1 req/sec
 * per Nominatim's acceptable use policy. Concurrent callers are serialized
 * through a 1-second-per-request gap.
 */
@Singleton
class GeocodingService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val memoryCache = HashMap<String, GeoResult?>()
    private var diskCache: HashMap<String, GeoResult?>? = null
    private var lastRequestTime = 0L
    private val rateLimitMutex = Mutex()

    private val cacheFile: File
        get() = File(context.filesDir, CACHE_FILE_NAME)

    // --------------------------------------------------------------- public

    /**
     * Reverse-geocode a coordinate pair. Returns null if no result is found
     * or if the network request fails.
     */
    suspend fun reverseGeocode(lat: Double, lng: Double): GeoResult? {
        val key = cacheKey(lat, lng)

        // 1. Memory cache hit.
        memoryCache[key]?.let { return it }

        // 2. Disk cache hit.
        ensureDiskCache()
        diskCache?.get(key)?.let { result ->
            memoryCache[key] = result
            return result
        }

        // 3. Rate limit: 1 request per second.
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRequestTime
            if (elapsed < 1000) {
                kotlinx.coroutines.delay(1000 - elapsed)
            }
            lastRequestTime = System.currentTimeMillis()
        }

        // 4. Nominatim API call.
        return try {
            val encodedLat = URLEncoder.encode(lat.toString(), "UTF-8")
            val encodedLng = URLEncoder.encode(lng.toString(), "UTF-8")
            val urlString = "$NOMINATIM_BASE_URL?lat=$encodedLat&lon=$encodedLng&format=json&zoom=10"

            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            try {
                if (connection.responseCode == 200) {
                    val body = connection.inputStream.bufferedReader().readText()
                    val root = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(body)
                    val address = root["address"] as? Map<String, kotlinx.serialization.json.JsonElement>

                    if (address != null) {
                        val result = GeoResult(
                            city = address["city"]?.toString()?.trim('"')
                                ?: address["town"]?.toString()?.trim('"')
                                ?: address["village"]?.toString()?.trim('"')
                                ?: address["hamlet"]?.toString()?.trim('"')
                                ?: address["municipality"]?.toString()?.trim('"'),
                            state = address["state"]?.toString()?.trim('"'),
                            country = address["country"]?.toString()?.trim('"'),
                            countryCode = address["country_code"]?.toString()?.trim('"'),
                        )
                        memoryCache[key] = result
                        diskCache?.put(key, result)
                        persistCache()
                        return result
                    }

                    // Empty spot — cache null so map panning doesn't re-query.
                    memoryCache[key] = null
                    diskCache?.put(key, null)
                    persistCache()
                    null
                } else {
                    null
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            // Network/parse failure — don't negative-cache.
            null
        }
    }

    /** Clear all cached results. */
    suspend fun clearCache() {
        memoryCache.clear()
        diskCache?.clear()
        persistCache()
    }

    // --------------------------------------------------------------- internal

    private fun cacheKey(lat: Double, lng: Double): String =
        "%.4f,%.4f".format(lat, lng)

    private fun ensureDiskCache() {
        if (diskCache != null) return
        diskCache = try {
            if (cacheFile.exists()) {
                val raw = cacheFile.readText()
                if (raw.isNotBlank()) {
                    json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(raw)
                        .mapValues { (_, v) ->
                            if (v is kotlinx.serialization.json.JsonNull) null
                            else v?.let {
                                json.decodeFromString(GeoResult.serializer(), it.toString())
                            }
                        }.let { HashMap(it) }
                } else {
                    HashMap()
                }
            } else {
                HashMap()
            }
        } catch (_: Exception) {
            HashMap()
        }
    }

    private fun persistCache() {
        try {
            val map = diskCache?.mapValues { (_, v) ->
                v?.let { json.encodeToString(GeoResult.serializer(), it) }
            } ?: return
            cacheFile.writeText(json.encodeToString(
                MapSerializer(kotlinx.serialization.builtins.serializer<String>(), kotlinx.serialization.builtins.serializer<String>()),
                map.mapValues { (_, v) -> v ?: "null" }
            ))
        } catch (_: Exception) {
            // Best-effort persistence.
        }
    }

    companion object {
        private const val CACHE_FILE_NAME = "geocoding_cache.json"
        private const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org/reverse"
        private const val USER_AGENT = "LumoVault/1.0 (photo-backup-app)"
    }
}
