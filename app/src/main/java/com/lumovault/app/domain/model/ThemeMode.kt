package com.lumovault.app.domain.model

/**
 * Appearance preference. `System` defers to the device setting; the other two force a scheme.
 *
 * Values are persisted by [storageKey] rather than [Enum.name] so renaming an entry cannot
 * silently invalidate a stored preference.
 */
enum class ThemeMode(val storageKey: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        /** PRD section 44 sets Dark as the default appearance. */
        val Default: ThemeMode = Dark

        fun fromStorageKey(key: String?, fallback: ThemeMode = Default): ThemeMode =
            entries.firstOrNull { it.storageKey == key } ?: fallback
    }
}
