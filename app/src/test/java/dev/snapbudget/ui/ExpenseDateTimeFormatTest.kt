package dev.snapbudget.ui

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseDateTimeFormatTest {
    @Test fun readableAndCanonicalFormatsRoundTripStrictly() {
        val cases = listOf(
            LocalDateTime.of(2025, 5, 3, 0, 0) to "3 May 2025, 12:00 AM",
            LocalDateTime.of(2025, 5, 3, 12, 0) to "3 May 2025, 12:00 PM",
            LocalDateTime.of(2024, 2, 29, 21, 41) to "29 Feb 2024, 9:41 PM",
        )
        cases.forEach { (dateTime, readable) ->
            val canonical = dateTime.toString().take(16)
            assertEquals(readable, ExpenseDateTimeFormat.format(dateTime))
            assertEquals(readable, ExpenseDateTimeFormat.canonicalToReadable(canonical))
            assertEquals(canonical, ExpenseDateTimeFormat.inputToCanonical(readable))
            assertEquals(canonical, ExpenseDateTimeFormat.inputToCanonical(canonical))
        }
        assertEquals("3 May 2025, 9:41 PM", ExpenseDateTimeFormat.inputToCanonical("3 may 2025, 9:41 pm")?.let {
            ExpenseDateTimeFormat.canonicalToReadable(it)
        })
    }

    @Test fun rejectsImpossibleDatesSecondsAndPartialInputWithoutChangingThem() {
        listOf("31 Feb 2025, 9:41 PM", "29 Feb 2025, 9:41 PM", "3 May 2025, 9:41:12 PM", "3 May 2025, ", "").forEach {
            assertNull(it, ExpenseDateTimeFormat.inputToCanonical(it))
            assertTrue(ExpenseDateTimeFormat.canonicalToReadable(it) == it)
        }
        listOf("2025-02-29T21:41", "2025-05-03T21:41:12", "2025-05-03T25:41").forEach {
            assertNull(it, ExpenseDateTimeFormat.inputToCanonical(it))
            assertEquals(it, ExpenseDateTimeFormat.canonicalToReadable(it))
        }
        assertTrue(ExpenseDateTimeFormat.isValidInput("3 May 2025, 9:41 PM"))
        assertTrue(!ExpenseDateTimeFormat.isValidInput("31 Feb 2025, 9:41 PM"))
    }
}
