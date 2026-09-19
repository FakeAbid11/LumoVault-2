package com.lumovault.lumovault.core.diagnostics

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.lumovault.lumovault.core.database.dao.MediaDao
import com.lumovault.lumovault.core.database.entity.MediaStatus
import com.lumovault.lumovault.core.storage.ThumbnailCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregated diagnostics info for debugging and support.
 */
data class DiagnosticsInfo(
    val platform: String = "Android",
    val deviceModel: String = Build.MODEL,
    val deviceManufacturer: String = Build.MANUFACTURER,
    val osVersion: String = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
    val totalMediaItems: Int = 0,
    val uploadedItems: Int = 0,
    val pendingItems: Int = 0,
    val failedItems: Int = 0,
    val databasePath: String = "",
    val databaseSizeBytes: Long = 0,
    val thumbnailCacheSizeBytes: Long = 0,
    val thumbnailCacheCount: Int = 0,
    val totalStorageBytes: Long = 0,
    val freeStorageBytes: Long = 0,
) {
    fun toMap(): Map<String, String> = mapOf(
        "Platform" to platform,
        "Device" to "$deviceManufacturer $deviceModel",
        "OS" to osVersion,
        "Total Media" to totalMediaItems.toString(),
        "Uploaded" to uploadedItems.toString(),
        "Pending" to pendingItems.toString(),
        "Failed" to failedItems.toString(),
        "DB Size" to formatBytes(databaseSizeBytes),
        "Thumbnail Cache" to "${formatBytes(thumbnailCacheSizeBytes)} ($thumbnailCacheCount files)",
        "Storage" to "${formatBytes(totalStorageBytes - freeStorageBytes)} / ${formatBytes(totalStorageBytes)}",
    )

    fun formatReport(): String = buildString {
        appendLine("=== LumoVault Diagnostics ===")
        appendLine("Platform: $platform")
        appendLine("Device: $deviceManufacturer $deviceModel")
        appendLine("OS: $osVersion")
        appendLine()
        appendLine("--- Media ---")
        appendLine("Total: $totalMediaItems")
        appendLine("Uploaded: $uploadedItems")
        appendLine("Pending: $pendingItems")
        appendLine("Failed: $failedItems")
        appendLine()
        appendLine("--- Storage ---")
        appendLine("Database: $databasePath (${formatBytes(databaseSizeBytes)})")
        appendLine("Thumbnail Cache: ${formatBytes(thumbnailCacheSizeBytes)} ($thumbnailCacheCount files)")
        appendLine("Device Storage: ${formatBytes(totalStorageBytes - freeStorageBytes)} / ${formatBytes(totalStorageBytes)}")
        appendLine()
        appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

/**
 * Gathers device and app diagnostics for debugging and support.
 *
 * Ported from Flutter `lib/core/diagnostics/diagnostics_service.dart`.
 */
@Singleton
class DiagnosticsService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
    private val thumbnailCache: ThumbnailCache,
) {

    /**
     * Collect diagnostics info from the device and database.
     */
    suspend fun collect(): DiagnosticsInfo = withContext(Dispatchers.IO) {
        val dbFile = context.getDatabasePath("lumovault")
        val allMedia = mediaDao.all()
        val totalMedia = allMedia.size
        val uploaded = allMedia.count { it.status == MediaStatus.uploaded }
        val pending = allMedia.count { it.status == MediaStatus.pending }
        val failed = allMedia.count { it.status == MediaStatus.failed }

        val stat = StatFs(Environment.getDataDirectory().path)

        DiagnosticsInfo(
            totalMediaItems = totalMedia,
            uploadedItems = uploaded,
            pendingItems = pending,
            failedItems = failed,
            databasePath = dbFile.absolutePath,
            databaseSizeBytes = if (dbFile.exists()) dbFile.length() else 0L,
            thumbnailCacheSizeBytes = thumbnailCache.diskCacheSizeBytes(),
            thumbnailCacheCount = thumbnailCache.diskCacheCount(),
            totalStorageBytes = stat.totalBytes,
            freeStorageBytes = stat.availableBytes,
        )
    }

    /**
     * Export a formatted diagnostics report to a timestamped file.
     *
     * @return The path to the exported report file.
     */
    suspend fun exportLogs(): String = withContext(Dispatchers.IO) {
        val info = collect()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "diagnostics_$timestamp.txt")
        file.writeText(info.formatReport())
        file.absolutePath
    }
}
