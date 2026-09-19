package com.lumovault.lumovault.core.ui

/**
 * Byte-count formatting shared by every size display in the app.
 *
 * Ported from lib/core/utils/format_utils.dart so a number reads identically
 * on the duplicates summary, the storage screens and the backup stats: B, KB,
 * MB or GB with one decimal, binary units.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${(bytes / 1024.0).oneDecimal()} KB"
    if (bytes < 1024 * 1024 * 1024) return "${(bytes / (1024.0 * 1024)).oneDecimal()} MB"
    return "${(bytes / (1024.0 * 1024 * 1024)).oneDecimal()} GB"
}

private fun Double.oneDecimal(): String = String.format("%.1f", this)
