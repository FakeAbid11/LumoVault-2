package com.lumovault.app.util

/**
 * Display helpers for a codebase that handles phone numbers, one-time codes, passwords and session files.
 *
 * Codes and passwords are never passed to anything here. Logs do not use this file at all: masking the
 * digits of an arbitrary string covered a phone number but not a file path, so a logged failure carries a
 * code, a mapped kind and an exception class instead of any of its text.
 */
object Privacy {
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
