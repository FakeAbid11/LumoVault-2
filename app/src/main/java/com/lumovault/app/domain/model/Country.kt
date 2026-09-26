package com.lumovault.app.domain.model

/**
 * A dialling region as the country selector needs it. [iso2] is the uppercase ISO 3166-1 alpha-2
 * code, which is what libphonenumber keys its metadata on.
 */
data class Country(
    val iso2: String,
    val callingCode: Int,
    val name: String,
) {
    val dialPrefix: String get() = "+$callingCode"

    /**
     * Flag as regional-indicator code points rather than image assets: it renders with the system
     * emoji font, scales for free, and adds nothing to the APK. `BD` becomes 🇧🇩 by mapping each
     * letter onto its indicator, which lives above the BMP, hence `appendCodePoint`.
     */
    val flag: String
        get() = buildString {
            iso2.uppercase().forEach { letter ->
                appendCodePoint(letter.code + REGIONAL_INDICATOR_OFFSET)
            }
        }

    companion object {
        private const val REGIONAL_INDICATOR_OFFSET = 0x1F1E6 - 'A'.code
    }
}

/**
 * Search matches the three ways a user might look for their country: by name, by calling code, or
 * by ISO code. Called on every keystroke, so it stays allocation-light and case-insensitive.
 */
fun List<Country>.search(query: String): List<Country> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this

    val digits = trimmed.filter { it.isDigit() }
    val letters = trimmed.filter { it.isLetter() }.uppercase()

    return filter { country ->
        country.name.contains(trimmed, ignoreCase = true) ||
            (digits.isNotEmpty() && country.callingCode.toString().startsWith(digits)) ||
            (letters.isNotEmpty() && country.iso2.startsWith(letters))
    }
}
