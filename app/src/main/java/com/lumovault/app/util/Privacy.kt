package com.lumovault.app.util

/**
 * Logging helpers for a codebase that handles phone numbers, one-time codes, passwords and session
 * files.
 *
 * Telegram's own error text can quote the number it was about, so diagnostics mask digit runs
 * before anything is written. Codes and passwords are never passed to these functions at all.
 */
object Privacy {
    private const val MASK = "****"

    /** `+8801712345678` becomes `****`. Runs of three or more digits are treated as identifiers. */
    fun maskDigits(input: String): String = input.replace(Regex("\\d{3,}"), MASK)

    /**
     * Last [visible] digits only, for the "we sent a code to …67" line. Phone numbers are personal
     * data, so the full value stays in the ViewModel's memory and never reaches disk or logs.
     */
    fun tailOf(number: String, visible: Int = 2): String {
        val trimmed = number.filter { it.isDigit() }
        if (trimmed.length <= visible) return trimmed
        return "…${trimmed.takeLast(visible)}"
    }
}
