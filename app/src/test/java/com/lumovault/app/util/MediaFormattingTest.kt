package com.lumovault.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MediaFormattingTest {
    @Test
    fun `durations read the way a video cell should show them`() {
        assertEquals("0:45", formatDuration(45_000))
        assertEquals("4:07", formatDuration(247_000))
        assertEquals("1:04:22", formatDuration(3_862_000))
        assertEquals("0:00", formatDuration(0))
    }

    @Test
    fun `sub-second durations do not round up to a second`() {
        assertEquals("0:00", formatDuration(999))
    }

    @Test
    fun `today and yesterday are relative to the supplied day, not the device clock`() {
        val today = LocalDate.of(2026, 9, 25)

        assertEquals(DayDistance.Today, dayDistance(today, today))
        assertEquals(DayDistance.Yesterday, dayDistance(today.minusDays(1), today))
        assertEquals(DayDistance.ThisYear, dayDistance(today.withDayOfYear(3), today))
        assertEquals(DayDistance.Earlier, dayDistance(today.minusYears(1), today))
    }

    @Test
    fun `the year is shown only when the day is outside the current one`() {
        val today = LocalDate.of(2026, 9, 25)

        assertEquals(
            "September 24",
            formatDay(today.minusDays(1), DayDistance.Yesterday, java.util.Locale.ENGLISH),
        )
        assertEquals(
            "January 1, 2025",
            formatDay(
                LocalDate.of(2025, 1, 1),
                DayDistance.Earlier,
                java.util.Locale.ENGLISH,
            ),
        )
    }

    @Test
    fun `epoch seconds convert to a local date in the caller zone`() {
        assertEquals(
            LocalDate.of(2026, 9, 25),
            localDateOf(1_758_768_000, java.time.ZoneOffset.UTC),
        )
    }
}
