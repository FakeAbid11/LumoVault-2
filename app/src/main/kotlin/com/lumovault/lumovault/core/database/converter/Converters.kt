package com.lumovault.lumovault.core.database.converter

import androidx.room.TypeConverter
import com.lumovault.lumovault.core.database.entity.MediaStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * JSON-backed converters, matching the Flutter Drift TypeConverters so that
 * on-disk list/map shapes are identical ([{"a","b"}] arrays, named landmark
 * maps, numeric embedding arrays).
 */
class Converters {

    private val json = Json { ignoreUnknownKeys = true }

    private val stringListSerializer = ListSerializer(String.serializer())
    private val doubleListSerializer = ListSerializer(Double.serializer())
    private val floatListSerializer = ListSerializer(Float.serializer())
    private val landmarksSerializer = MapSerializer(
        String.serializer(),
        PairSerializer(Double.serializer(), Double.serializer()),
    )

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(stringListSerializer, value) }.getOrDefault(emptyList())
    }

    @TypeConverter
    fun fromStringList(value: List<String>): String =
        json.encodeToString(stringListSerializer, value)

    @TypeConverter
    fun toDoubleList(value: String?): List<Double> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(doubleListSerializer, value) }.getOrDefault(emptyList())
    }

    @TypeConverter
    fun fromDoubleList(value: List<Double>): String =
        json.encodeToString(doubleListSerializer, value)

    @TypeConverter
    fun toFloatList(value: String?): List<Float>? {
        if (value.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(floatListSerializer, value) }.getOrNull()
    }

    @TypeConverter
    fun fromFloatList(value: List<Float>?): String? {
        return value?.let { json.encodeToString(floatListSerializer, it) }
    }

    @TypeConverter
    fun toLandmarks(value: String?): Map<String, Pair<Double, Double>> {
        if (value.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString(landmarksSerializer, value) }.getOrDefault(emptyMap())
    }

    @TypeConverter
    fun fromLandmarks(value: Map<String, Pair<Double, Double>>): String =
        json.encodeToString(landmarksSerializer, value)

    @TypeConverter
    fun toMediaStatus(value: Int?): MediaStatus? =
        value?.let { MediaStatus.entries.getOrElse(it) { MediaStatus.pending } }

    @TypeConverter
    fun fromMediaStatus(value: MediaStatus?): Int? = value?.ordinal
}
