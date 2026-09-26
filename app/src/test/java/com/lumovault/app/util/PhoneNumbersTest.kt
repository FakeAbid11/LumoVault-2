package com.lumovault.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `+880+8801712345678` class of bug lives or dies here, so these cases are the ones the prompt
 * called out: a number typed with its country code already in it, a national number with its
 * leading zero, and separators from pasting.
 *
 * Assertions use numbers that are stable across libphonenumber metadata versions (their own canonical
 * example numbers) rather than formatting minutiae.
 */
class PhoneNumbersTest {
    @Test
    fun `typing the country code yourself is not counted twice`() {
        assertEquals("+8801712345678", PhoneNumbers.toE164("BD", "+8801712345678"))
        assertEquals("+8801712345678", PhoneNumbers.toE164("BD", "+880 1712 345678"))
    }

    @Test
    fun `a national number with its trunk zero resolves under the selected country`() {
        assertEquals("+16502530000", PhoneNumbers.toE164("US", "6502530000"))
        assertEquals("+442071838750", PhoneNumbers.toE164("GB", "020 7183 8750"))
        assertEquals("+61412345678", PhoneNumbers.toE164("AU", "0412 345 678"))
    }

    @Test
    fun `pasted punctuation is tolerated, letters are not`() {
        assertEquals("+8801712345678", PhoneNumbers.toE164("BD", " +880 (1712) 345-678 "))
        assertEquals("+8801712345678", PhoneNumbers.toE164("BD", "+880abc1712def345678"))
    }

    @Test
    fun `sanitising keeps dialling characters and drops the rest`() {
        // Parentheses are formatting a landline can legitimately contain, so they survive; letters
        // and quotes do not.
        assertEquals("+880 1712-3456 ()", PhoneNumbers.sanitizeInput("+880 1712-3456 abc;\"()"))
    }

    @Test
    fun `text with no subscriber digits is not a number`() {
        assertNull(PhoneNumbers.toE164("US", ""))
        assertFalse(PhoneNumbers.hasSubscriberNumber("BD", "+880"))
        assertFalse(PhoneNumbers.hasSubscriberNumber("BD", ""))
    }

    @Test
    fun `a complete number for another region is accepted without assuming its length`() {
        assertTrue(PhoneNumbers.hasSubscriberNumber("JP", "09012345678"))
        assertTrue(PhoneNumbers.hasSubscriberNumber("DE", "+49 30 901820"))
    }

    @Test
    fun `example numbers exist so the field can show a local shape`() {
        assertTrue(PhoneNumbers.exampleFor("US")?.isNotBlank() == true)
    }
}
