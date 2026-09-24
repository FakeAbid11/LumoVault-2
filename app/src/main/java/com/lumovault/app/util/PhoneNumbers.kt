package com.lumovault.app.util

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber

/**
 * Turns "country + whatever the user typed" into a single E.164 number, which is the only form
 * Telegram accepts.
 *
 * Everything funnels through libphonenumber's parser instead of string concatenation. Prepending
 * the calling code by hand is what produces `+880+8801712345678`, and it also mishandles the cases
 * where the user already typed `+880`, `00880`, or a leading trunk `0`. Parsing with the selected
 * region as the *default* resolves all of those without assuming anything about the number itself,
 * so a fully international number survives untouched.
 */
object PhoneNumbers {
    private const val MIN_SUBSCRIBER_DIGITS = 4

    private val phoneUtil: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /** Forgiving about formatting without letting letters through; the parser does the real work. */
    fun sanitizeInput(raw: String): String = raw.filter {
        it.isDigit() || it == '+' || it.isWhitespace() || it == '-' || it == '(' || it == ')'
    }

    /** Null when the text cannot be read as a phone number for [iso2]. */
    fun toE164(iso2: String, rawInput: String): String? = parse(iso2, rawInput)
        ?.let { phoneUtil.format(it, PhoneNumberFormat.E164) }

    /**
     * A bare country code parses successfully but is not something Telegram can send a code to,
     * so the submit button needs this check on top of [toE164].
     */
    fun hasSubscriberNumber(iso2: String, rawInput: String): Boolean =
        parse(iso2, rawInput)
            ?.let { it.nationalNumber > 0L && phoneUtil.getNationalSignificantNumber(it).length >= MIN_SUBSCRIBER_DIGITS }
            ?: false

    /** Local-shaped placeholder such as `1712345678`, so the field hints the expected format. */
    fun exampleFor(iso2: String): String? =
        phoneUtil.getExampleNumber(iso2)?.let { phoneUtil.format(it, PhoneNumberFormat.NATIONAL) }

    private fun parse(iso2: String, rawInput: String): PhoneNumber? = try {
        phoneUtil.parse(sanitizeInput(rawInput), iso2)
    } catch (_: NumberParseException) {
        null
    }
}
