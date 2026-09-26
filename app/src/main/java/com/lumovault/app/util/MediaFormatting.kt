package com.lumovault.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** `1:04:22`, `4:07`, or seconds alone under a minute. Truncates, as Android's players do. */
fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE

    return if (hours > 0) {
        "$hours:${minutes.pad()}:${seconds.pad()}"
    } else {
        "$minutes:${seconds.pad()}"
    }
}

private fun Long.pad(): String = toString().padStart(2, '0')

/**
 * How far a media day sits from today. The wording stays out of here — "Today" and "Yesterday" are
 * user-facing text and belong in resources, not in a utility.
 */
enum class DayDistance { Today, Yesterday, ThisYear, Earlier }

fun dayDistance(day: LocalDate, today: LocalDate): DayDistance =
    when (today.toEpochDay() - day.toEpochDay()) {
        0L -> DayDistance.Today
        1L -> DayDistance.Yesterday
        else -> if (day.year == today.year) DayDistance.ThisYear else DayDistance.Earlier
    }

/**
 * Date text for a timeline header (PRD section 16). A year is shown only when the day is outside
 * the current one, so "September 24" carries the same weight as the rest of the app's dates without
 * repeating a year the user already knows.
 */
fun formatDay(day: LocalDate, distance: DayDistance, locale: Locale = Locale.getDefault()): String {
    val pattern = if (distance == DayDistance.Earlier) PATTERN_FULL else PATTERN_DAY_MONTH
    return day.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * Month text for the timeline's date rail, in the same "show a year only when it is not this one" shape as
 * [formatDay]. Abbreviated because the rail is a strip of pixels a finger wide and a label that pushes the
 * thumbnails aside is the one thing a scrubber may not do.
 */
fun formatMonth(month: YearMonth, showYear: Boolean, locale: Locale = Locale.getDefault()): String =
    month.format(DateTimeFormatter.ofPattern(if (showYear) PATTERN_MONTH_YEAR else PATTERN_MONTH, locale))

/** Day number for an epoch-second timestamp, in the given zone. */
fun localDateOf(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate()

private const val PATTERN_DAY_MONTH = "MMMM d"
private const val PATTERN_FULL = "MMMM d, yyyy"
private const val PATTERN_MONTH = "MMM"
private const val PATTERN_MONTH_YEAR = "MMM yyyy"
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
